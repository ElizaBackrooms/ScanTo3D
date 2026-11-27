package com.example.scanner

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class ScreenPoint2D(
    val nx: Float, // normalized 0..1 screen X
    val ny: Float, // normalized 0..1 screen Y
    val argb: Int,
    val surfaceType: Byte
)

data class ScreenPlaneOverlay(
    val points: List<Pair<Float, Float>>, // normalized 0..1 polygon vertices
    val surfaceType: Byte
)

data class ArLiveTelemetry(
    val isArActive: Boolean = false,
    val isTracking: Boolean = false,
    val trackingHeadline: String = "Initializing ARCore 6-DoF…",
    val depthApiActive: Boolean = false,
    val depthModeLabel: String = "Checking Depth…",
    val detectedPlaneCount: Int = 0,
    val reticleDistanceMeters: Float? = null,
    val reticleHitSource: String = "",
    val reticleWorldPoint: Vec3? = null,
    val cameraPosition: Vec3 = Vec3(0f, 1.5f, 0f),
    val yawRad: Float = 0f,
    val pitchRad: Float = 0f,
    val projectedPlanes: List<ScreenPlaneOverlay> = emptyList(),
    val projectedPoints: List<ScreenPoint2D> = emptyList(),
    val projectedCorners: List<Pair<Float, Float>> = emptyList()
)

private data class PendingHitTap(val xPx: Float, val yPx: Float, val onResult: (Vec3?) -> Unit)

/**
 * Real ARCore 6-DoF SLAM + Depth API + Plane Detection + Hit-Testing scanner renderer.
 *
 * Captures genuine metric 3D room points from:
 * 1. ARCore Depth API (`acquireRawDepthImage16Bits` + confidence, or `acquireDepthImage16Bits`)
 *    unprojected via exact camera intrinsics & 6-DoF world pose, textured with live RGB camera pixels.
 * 2. ARCore Tracked Planes (`HORIZONTAL_UPWARD_FACING`, `HORIZONTAL_DOWNWARD_FACING`, `VERTICAL`)
 *    densely sampled along their tracked polygons for razor-flat floors, walls, ceilings, and tables.
 * 3. ARCore 3D SLAM Feature PointCloud (`acquirePointCloud()`) as universal real-3D fallback on devices
 *    without Depth API support.
 */
class ArCoreScannerRenderer(
    private val context: Context,
    private val onPointsCaptured: (List<SpatialPoint>) -> Unit,
    private val onArUnavailable: (String) -> Unit
) : GLSurfaceView.Renderer {

    private val backgroundRenderer = ArCameraBackgroundRenderer()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sessionLock = Any()
    private var session: Session? = null
    private var sessionCreated = false
    private var isResumed = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var viewportChanged = true

    private val _telemetry = MutableStateFlow(ArLiveTelemetry())
    val telemetry: StateFlow<ArLiveTelemetry> = _telemetry.asStateFlow()

    @Volatile var isActivelyCapturing: Boolean = false
    @Volatile var sampleStep: Int = 14 // higher = denser grid
    @Volatile var currentCornerAnchors: List<Vec3> = emptyList()
    @Volatile var previewWorldPoints: List<SpatialPoint> = emptyList()

    private val pendingTaps = ConcurrentLinkedQueue<PendingHitTap>()

    private var depthModeLabel = "ARCore SLAM"
    private var depthSupported = false
    private var useRawDepth = false

    private var lastDepthCaptureMs = 0L
    private var lastPlaneCaptureMs = 0L
    private var lastOverlayUpdateMs = 0L
    private var lastCamPosePos = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    private var lastCamForward = Vec3(0f, 0f, -1f)

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    fun requestCenterCornerHit(onResult: (Vec3?) -> Unit) {
        pendingTaps.add(PendingHitTap(viewportWidth * 0.5f, viewportHeight * 0.5f, onResult))
    }

    fun requestTapCornerHit(xPx: Float, yPx: Float, onResult: (Vec3?) -> Unit) {
        pendingTaps.add(PendingHitTap(xPx, yPx, onResult))
    }

    fun resumeSession() {
        synchronized(sessionLock) {
            isResumed = true
            try {
                val s = ensureSessionLocked() ?: return
                s.resume()
            } catch (e: Throwable) {
                handleSessionFailureLocked(e)
            }
        }
    }

    fun pauseSession() {
        synchronized(sessionLock) {
            isResumed = false
            try {
                session?.pause()
            } catch (_: Throwable) {
            }
        }
    }

    fun destroySession() {
        synchronized(sessionLock) {
            isResumed = false
            try {
                session?.pause()
                session?.close()
            } catch (_: Throwable) {
            } finally {
                session = null
                sessionCreated = false
            }
        }
    }

    private fun ensureSessionLocked(): Session? {
        if (session != null) return session
        if (sessionCreated) return null
        sessionCreated = true
        return try {
            val s = Session(context)
            val config = Config(s).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                lightEstimationMode = Config.LightEstimationMode.DISABLED

                when {
                    s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> {
                        depthMode = Config.DepthMode.AUTOMATIC
                        depthSupported = true
                        useRawDepth = true
                        depthModeLabel = "ARCore DEPTH16 + SLAM"
                    }
                    s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> {
                        depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                        depthSupported = true
                        useRawDepth = true
                        depthModeLabel = "ARCore Raw Depth + SLAM"
                    }
                    else -> {
                        depthMode = Config.DepthMode.DISABLED
                        depthSupported = false
                        useRawDepth = false
                        depthModeLabel = "ARCore 6-DoF Planes + SLAM"
                    }
                }
            }
            s.configure(config)
            session = s
            _telemetry.value = _telemetry.value.copy(
                isArActive = true,
                depthApiActive = depthSupported,
                depthModeLabel = depthModeLabel,
                trackingHeadline = "Move phone slowly side-to-side to map room"
            )
            s
        } catch (e: Throwable) {
            handleSessionFailureLocked(e)
            null
        }
    }

    private fun handleSessionFailureLocked(e: Throwable) {
        val reason = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        _telemetry.value = _telemetry.value.copy(
            isArActive = false,
            isTracking = false,
            trackingHeadline = "ARCore unavailable ($reason)"
        )
        mainHandler.post { onArUnavailable(reason) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.03f, 0.05f, 0.08f, 1.0f)
        backgroundRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        synchronized(sessionLock) {
            val s = session ?: return
            if (!isResumed || backgroundRenderer.cameraTextureId == -1) return

            try {
                if (viewportChanged) {
                    val rotation = getDisplayRotation(context)
                    s.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
                    s.setCameraTextureName(backgroundRenderer.cameraTextureId)
                    viewportChanged = false
                }

                val frame = s.update()
                backgroundRenderer.draw(frame)

                val camera = frame.camera
                val isTracking = camera.trackingState == TrackingState.TRACKING
                val now = System.currentTimeMillis()

                // Process any queued tap-to-anchor hit tests first
                while (true) {
                    val tap = pendingTaps.poll() ?: break
                    val hitVec = if (isTracking) performBestHitTest(frame, tap.xPx, tap.yPx)?.first else null
                    mainHandler.post { tap.onResult(hitVec) }
                }

            if (!isTracking) {
                if (now - lastOverlayUpdateMs > 180L) {
                    lastOverlayUpdateMs = now
                    val msg = when (camera.trackingFailureReason) {
                        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark — turn on room lights"
                        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast — pan slowly"
                        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Aim at textured walls, corners, or floor"
                        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera occupied by another process"
                        else -> "Initializing 6-DoF tracking — pan phone slowly"
                    }
                    _telemetry.value = _telemetry.value.copy(
                        isArActive = true,
                        isTracking = false,
                        trackingHeadline = msg,
                        projectedPlanes = emptyList()
                    )
                }
                return
            }

            // Camera pose & VP matrix for 3D→2D AR overlays
            val pose = camera.pose
            val camPos = Vec3(pose.tx(), pose.ty(), pose.tz())
            val zAxis = pose.zAxis // camera looks along -Z
            val forward = Vec3(-zAxis[0], -zAxis[1], -zAxis[2]).normalize()
            val yaw = atan2(forward.x.toDouble(), (-forward.z).toDouble()).toFloat()
            val pitch = atan2(forward.y.toDouble(), sqrt(forward.x * forward.x + forward.z * forward.z).toDouble()).toFloat()

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 20f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            val trackedPlanes = s.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }

            // Check camera movement to avoid flooding duplicate frames while completely stationary
            val movedDist = camPos.distanceTo(lastCamPosePos)
            val turnedDot = forward.dot(lastCamForward)
            val hasMovedEnough = movedDist > 0.045f || turnedDot < 0.996f || (now - lastDepthCaptureMs > 650L)

            if (isActivelyCapturing && hasMovedEnough && now - lastDepthCaptureMs >= 160L) {
                lastDepthCaptureMs = now
                lastCamPosePos = camPos
                lastCamForward = forward

                val captured = ArrayList<SpatialPoint>(512)
                var gotDepthPoints = false

                if (depthSupported) {
                    val depthPts = extractArDepthPoints(frame, pose, trackedPlanes)
                    if (depthPts.isNotEmpty()) {
                        captured.addAll(depthPts)
                        gotDepthPoints = true
                    }
                }

                // Always harvest high-confidence 3D SLAM feature points if depth wasn't ready yet
                if (!gotDepthPoints) {
                    val slamPts = extractSlamPointCloud(frame, pose, trackedPlanes)
                    captured.addAll(slamPts)
                }

                // Periodically sample tracked architectural planes (walls/floor/ceiling/tables)
                if (now - lastPlaneCaptureMs >= 420L && trackedPlanes.isNotEmpty()) {
                    lastPlaneCaptureMs = now
                    val planePts = sampleTrackedPlanes(frame, trackedPlanes)
                    captured.addAll(planePts)
                }

                if (captured.isNotEmpty()) {
                    onPointsCaptured(captured)
                }
            }

            // Refresh HUD overlay & live laser rangefinder (~12 Hz)
            if (now - lastOverlayUpdateMs >= 85L) {
                lastOverlayUpdateMs = now
                val centerHit = performBestHitTest(frame, viewportWidth * 0.5f, viewportHeight * 0.5f)
                val distMeters = centerHit?.first?.distanceTo(camPos)
                val hitSrc = centerHit?.second ?: ""

                val projectedPlanes = projectPlanesToScreen(trackedPlanes)
                val projectedPts = projectWorldPointsToScreen(previewWorldPoints)
                val projectedCorners = projectCornersToScreen(currentCornerAnchors)

                val headline = buildString {
                    append(if (depthSupported) "DEPTH16 LOCKED" else "6-DoF SLAM LOCKED")
                    if (trackedPlanes.isNotEmpty()) append(" • ${trackedPlanes.size} surfaces")
                    if (distMeters != null) append(" • %.2fm".format(distMeters))
                }

                _telemetry.value = ArLiveTelemetry(
                    isArActive = true,
                    isTracking = true,
                    trackingHeadline = headline,
                    depthApiActive = depthSupported,
                    depthModeLabel = depthModeLabel,
                    detectedPlaneCount = trackedPlanes.size,
                    reticleDistanceMeters = distMeters,
                    reticleHitSource = hitSrc,
                    reticleWorldPoint = centerHit?.first,
                    cameraPosition = camPos,
                    yawRad = yaw,
                    pitchRad = pitch,
                    projectedPlanes = projectedPlanes,
                    projectedPoints = projectedPts,
                    projectedCorners = projectedCorners
                )
            }
            } catch (_: NotYetAvailableException) {
                // Normal during first few frames of ARCore warm-up
            } catch (e: Throwable) {
                // Only treat fatal session errors as unavailable; transient frame errors are ignored
                if (e.javaClass.name.contains("Unavailable") || e is SecurityException) {
                    handleSessionFailureLocked(e)
                }
            }
        }
    }

    private fun performBestHitTest(frame: Frame, xPx: Float, yPx: Float): Pair<Vec3, String>? {
        val hits = try {
            frame.hitTest(xPx, yPx)
        } catch (_: Throwable) {
            return null
        }
        if (hits.isEmpty()) return null

        // Prioritize real DepthPoint first, then Plane polygon hit, then Point feature
        val best = hits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull {
                val t = it.trackable
                t is Plane && t.isPoseInPolygon(it.hitPose)
            }
            ?: hits.firstOrNull {
                val t = it.trackable
                t is Point && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            ?: hits.firstOrNull()
            ?: return null

        val p = best.hitPose
        val src = when (best.trackable) {
            is DepthPoint -> "DEPTH"
            is Plane -> "PLANE"
            else -> "SLAM"
        }
        return Vec3(p.tx(), p.ty(), p.tz()) to src
    }

    private fun extractArDepthPoints(
        frame: Frame,
        cameraPose: Pose,
        trackedPlanes: List<Plane>
    ): List<SpatialPoint> {
        var depthImage: Image? = null
        var confImage: Image? = null
        var cameraImage: Image? = null

        try {
            if (useRawDepth) {
                try {
                    depthImage = frame.acquireRawDepthImage16Bits()
                    confImage = try {
                        frame.acquireRawDepthConfidenceImage()
                    } catch (_: Throwable) {
                        null
                    }
                } catch (_: NotYetAvailableException) {
                    depthImage = null
                } catch (_: Throwable) {
                    depthImage = null
                }
            }
            if (depthImage == null) {
                depthImage = try {
                    frame.acquireDepthImage16Bits()
                } catch (_: Throwable) {
                    null
                }
            }
            val dImg = depthImage ?: return emptyList()

            cameraImage = try {
                frame.acquireCameraImage()
            } catch (_: Throwable) {
                null
            }

            val depthW = dImg.width
            val depthH = dImg.height
            if (depthW < 8 || depthH < 8) return emptyList()

            val intrinsics = frame.camera.imageIntrinsics
            val imgDims = intrinsics.imageDimensions
            val imgW = imgDims[0].coerceAtLeast(1)
            val imgH = imgDims[1].coerceAtLeast(1)

            val scaleX = depthW.toFloat() / imgW.toFloat()
            val scaleY = depthH.toFloat() / imgH.toFloat()
            val fx = intrinsics.focalLength[0] * scaleX
            val fy = intrinsics.focalLength[1] * scaleY
            val cx = intrinsics.principalPoint[0] * scaleX
            val cy = intrinsics.principalPoint[1] * scaleY
            if (fx < 1f || fy < 1f) return emptyList()

            val depthPlane = dImg.planes[0]
            val depthBuf = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val depthRowStride = depthPlane.rowStride
            val depthPixStride = depthPlane.pixelStride

            val confPlane = confImage?.planes?.firstOrNull()
            val confBuf = confPlane?.buffer
            val confRowStride = confPlane?.rowStride ?: 0
            val confPixStride = confPlane?.pixelStride ?: 0

            val floorY = trackedPlanes
                .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                .minOfOrNull { it.centerPose.ty() }
            val ceilY = trackedPlanes
                .filter { it.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING }
                .maxOfOrNull { it.centerPose.ty() }

            // Adaptive stride based on CaptureDensity sampleStep (10=Fast, 14=Balanced, 18=High)
            val gridCols = (sampleStep + 6).coerceIn(14, 28)
            val stepX = (depthW / gridCols).coerceAtLeast(2)
            val stepY = (depthH / (gridCols * 3 / 4).coerceAtLeast(10)).coerceAtLeast(2)

            val out = ArrayList<SpatialPoint>(gridCols * gridCols)
            val camLocal = FloatArray(3)
            val worldPt = FloatArray(3)

            var v = stepY / 2
            while (v < depthH - 1) {
                var u = stepX / 2
                while (u < depthW - 1) {
                    // Reject low-confidence raw depth pixels to keep room geometry clean
                    if (confBuf != null) {
                        val cIdx = v * confRowStride + u * confPixStride
                        if (cIdx in 0 until confBuf.limit()) {
                            val conf = confBuf.get(cIdx).toInt() and 0xFF
                            if (conf < 115) {
                                u += stepX
                                continue
                            }
                        }
                    }

                    val dIdx = v * depthRowStride + u * depthPixStride
                    if (dIdx + 1 >= depthBuf.limit()) {
                        u += stepX
                        continue
                    }
                    // Android DEPTH16 packs 13-bit depth in millimeters in lower bits (0x1FFF) and 3-bit confidence in upper bits
                    val rawSample = depthBuf.getShort(dIdx).toInt()
                    val depthMm = rawSample and 0x1FFF
                    if (depthMm in 250..5500) {
                        val depthM = depthMm * 0.001f
                        camLocal[0] = (u - cx) * depthM / fx
                        camLocal[1] = -(v - cy) * depthM / fy
                        camLocal[2] = -depthM

                        cameraPose.transformPoint(camLocal, 0, worldPt, 0)
                        val wx = worldPt[0]
                        val wy = worldPt[1]
                        val wz = worldPt[2]

                        val argb = sampleYuvColor(
                            cameraImage = cameraImage,
                            normU = u.toFloat() / depthW.toFloat(),
                            normV = v.toFloat() / depthH.toFloat()
                        )
                        val surfaceType = classifySurface(wy, floorY, ceilY, depthM)
                        out.add(SpatialPoint(wx, wy, wz, argb, surfaceType))
                    }
                    u += stepX
                }
                v += stepY
            }
            return out
        } finally {
            try { depthImage?.close() } catch (_: Throwable) {}
            try { confImage?.close() } catch (_: Throwable) {}
            try { cameraImage?.close() } catch (_: Throwable) {}
        }
    }

    private fun extractSlamPointCloud(
        frame: Frame,
        cameraPose: Pose,
        trackedPlanes: List<Plane>
    ): List<SpatialPoint> {
        var cameraImage: Image? = null
        val pointCloud = try {
            frame.acquirePointCloud()
        } catch (_: Throwable) {
            return emptyList()
        }
        try {
            val buf = pointCloud.points
            if (buf.remaining() < 4) return emptyList()

            cameraImage = try {
                frame.acquireCameraImage()
            } catch (_: Throwable) {
                null
            }
            val intrinsics = frame.camera.imageIntrinsics
            val invPose = cameraPose.inverse()

            val floorY = trackedPlanes
                .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                .minOfOrNull { it.centerPose.ty() }
            val ceilY = trackedPlanes
                .filter { it.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING }
                .maxOfOrNull { it.centerPose.ty() }

            val camX = cameraPose.tx()
            val camY = cameraPose.ty()
            val camZ = cameraPose.tz()

            val out = ArrayList<SpatialPoint>(128)
            while (buf.remaining() >= 4) {
                val wx = buf.get()
                val wy = buf.get()
                val wz = buf.get()
                val conf = buf.get()
                if (conf < 0.35f) continue

                val dist = sqrt((wx - camX) * (wx - camX) + (wy - camY) * (wy - camY) + (wz - camZ) * (wz - camZ))
                if (dist !in 0.25f..6.5f) continue

                val uv = projectWorldToSensorUv(wx, wy, wz, invPose, intrinsics)
                val argb = if (uv != null) {
                    sampleYuvColor(cameraImage, uv.first, uv.second)
                } else {
                    AndroidColor.rgb(156, 198, 224)
                }
                val sType = classifySurface(wy, floorY, ceilY, dist)
                out.add(SpatialPoint(wx, wy, wz, argb, sType))
            }
            return out
        } finally {
            try { pointCloud.release() } catch (_: Throwable) {}
            try { cameraImage?.close() } catch (_: Throwable) {}
        }
    }

    private fun projectWorldToSensorUv(
        wx: Float,
        wy: Float,
        wz: Float,
        invCameraPose: Pose,
        intrinsics: com.google.ar.core.CameraIntrinsics
    ): Pair<Float, Float>? {
        val world = floatArrayOf(wx, wy, wz)
        val cam = FloatArray(3)
        invCameraPose.transformPoint(world, 0, cam, 0)
        val depth = -cam[2]
        if (depth <= 0.15f) return null
        val dims = intrinsics.imageDimensions
        val imgW = dims[0].coerceAtLeast(1).toFloat()
        val imgH = dims[1].coerceAtLeast(1).toFloat()
        val u = (cam[0] * intrinsics.focalLength[0] / depth + intrinsics.principalPoint[0]) / imgW
        val v = (-cam[1] * intrinsics.focalLength[1] / depth + intrinsics.principalPoint[1]) / imgH
        return if (u in 0f..1f && v in 0f..1f) (u to v) else null
    }

    private fun sampleTrackedPlanes(frame: Frame, planes: List<Plane>): List<SpatialPoint> {
        var cameraImage: Image? = null
        try {
            cameraImage = try {
                frame.acquireCameraImage()
            } catch (_: Throwable) {
                null
            }
            val intrinsics = frame.camera.imageIntrinsics
            val invPose = frame.camera.pose.inverse()
            val out = ArrayList<SpatialPoint>(256)
            val localPt = FloatArray(3)
            val worldPt = FloatArray(3)

            for (plane in planes) {
                if (plane.extentX < 0.22f || plane.extentZ < 0.22f) continue
                val poly = plane.polygon ?: continue
                val vertCount = poly.limit() / 2
                if (vertCount < 3) continue

                val surfaceType = when (plane.type) {
                    Plane.Type.HORIZONTAL_UPWARD_FACING -> SpatialPoint.SURFACE_FLOOR
                    Plane.Type.HORIZONTAL_DOWNWARD_FACING -> SpatialPoint.SURFACE_CEILING
                    Plane.Type.VERTICAL -> SpatialPoint.SURFACE_WALL
                }
                val fallbackColor = when (surfaceType) {
                    SpatialPoint.SURFACE_FLOOR -> AndroidColor.rgb(112, 134, 152)
                    SpatialPoint.SURFACE_CEILING -> AndroidColor.rgb(176, 190, 204)
                    else -> AndroidColor.rgb(186, 198, 212)
                }

                val pose = plane.centerPose
                val polyX = FloatArray(vertCount)
                val polyZ = FloatArray(vertCount)
                var cx = 0f
                var cz = 0f
                for (i in 0 until vertCount) {
                    polyX[i] = poly.get(i * 2)
                    polyZ[i] = poly.get(i * 2 + 1)
                    cx += polyX[i]
                    cz += polyZ[i]
                }
                cx /= vertCount
                cz /= vertCount

                // Emit perimeter vertices + interior concentric rings so walls/floors stay dense & flat
                val rings = 3
                for (r in 1..rings) {
                    val f = r.toFloat() / rings
                    for (i in 0 until vertCount) {
                        val lx = cx + (polyX[i] - cx) * f
                        val lz = cz + (polyZ[i] - cz) * f
                        localPt[0] = lx; localPt[1] = 0f; localPt[2] = lz
                        pose.transformPoint(localPt, 0, worldPt, 0)

                        val uv = projectWorldToSensorUv(worldPt[0], worldPt[1], worldPt[2], invPose, intrinsics)
                        val col = if (uv != null && cameraImage != null) {
                            sampleYuvColor(cameraImage, uv.first, uv.second)
                        } else fallbackColor
                        out.add(SpatialPoint(worldPt[0], worldPt[1], worldPt[2], col, surfaceType))

                        // Midpoint along edge for extra wall smoothness
                        if (r == rings) {
                            val next = (i + 1) % vertCount
                            localPt[0] = (polyX[i] + polyX[next]) * 0.5f
                            localPt[1] = 0f
                            localPt[2] = (polyZ[i] + polyZ[next]) * 0.5f
                            pose.transformPoint(localPt, 0, worldPt, 0)
                            val midUv = projectWorldToSensorUv(worldPt[0], worldPt[1], worldPt[2], invPose, intrinsics)
                            val midCol = if (midUv != null && cameraImage != null) {
                                sampleYuvColor(cameraImage, midUv.first, midUv.second)
                            } else fallbackColor
                            out.add(SpatialPoint(worldPt[0], worldPt[1], worldPt[2], midCol, surfaceType))
                        }
                    }
                }
                // Center vertex
                localPt[0] = cx; localPt[1] = 0f; localPt[2] = cz
                pose.transformPoint(localPt, 0, worldPt, 0)
                val centerUv = projectWorldToSensorUv(worldPt[0], worldPt[1], worldPt[2], invPose, intrinsics)
                val centerCol = if (centerUv != null && cameraImage != null) {
                    sampleYuvColor(cameraImage, centerUv.first, centerUv.second)
                } else fallbackColor
                out.add(SpatialPoint(worldPt[0], worldPt[1], worldPt[2], centerCol, surfaceType))
            }
            return out
        } finally {
            try { cameraImage?.close() } catch (_: Throwable) {}
        }
    }

    private fun classifySurface(wy: Float, floorY: Float?, ceilY: Float?, depthM: Float): Byte = when {
        floorY != null && abs(wy - floorY) < 0.14f -> SpatialPoint.SURFACE_FLOOR
        ceilY != null && abs(wy - ceilY) < 0.18f -> SpatialPoint.SURFACE_CEILING
        floorY != null && wy < floorY + 0.95f && depthM < 2.2f -> SpatialPoint.SURFACE_OBJECT
        else -> SpatialPoint.SURFACE_WALL
    }

    private fun projectPlanesToScreen(planes: List<Plane>): List<ScreenPlaneOverlay> {
        val out = ArrayList<ScreenPlaneOverlay>(planes.size)
        val localPt = FloatArray(3)
        val worldPt = FloatArray(3)

        for (plane in planes) {
            val poly = plane.polygon ?: continue
            val count = poly.limit() / 2
            if (count < 3) continue
            val sType = when (plane.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> SpatialPoint.SURFACE_FLOOR
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> SpatialPoint.SURFACE_CEILING
                Plane.Type.VERTICAL -> SpatialPoint.SURFACE_WALL
            }
            val pose = plane.centerPose
            val screenPts = ArrayList<Pair<Float, Float>>(count)
            for (i in 0 until count) {
                localPt[0] = poly.get(i * 2)
                localPt[1] = 0f
                localPt[2] = poly.get(i * 2 + 1)
                pose.transformPoint(localPt, 0, worldPt, 0)
                val p = projectToNdc(worldPt[0], worldPt[1], worldPt[2])
                if (p != null) screenPts.add(p)
            }
            if (screenPts.size >= 3) {
                out.add(ScreenPlaneOverlay(screenPts, sType))
            }
        }
        return out
    }

    private fun projectWorldPointsToScreen(points: List<SpatialPoint>): List<ScreenPoint2D> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<ScreenPoint2D>(points.size.coerceAtMost(240))
        for (p in points) {
            val s = projectToNdc(p.x, p.y, p.z) ?: continue
            if (s.first in -0.05f..1.05f && s.second in -0.05f..1.05f) {
                out.add(ScreenPoint2D(s.first, s.second, p.argb, p.surfaceType))
            }
        }
        return out
    }

    private fun projectCornersToScreen(corners: List<Vec3>): List<Pair<Float, Float>> {
        if (corners.isEmpty()) return emptyList()
        return corners.mapNotNull { projectToNdc(it.x, it.y, it.z) }
    }

    private fun projectToNdc(wx: Float, wy: Float, wz: Float): Pair<Float, Float>? {
        val clipX = vpMatrix[0] * wx + vpMatrix[4] * wy + vpMatrix[8] * wz + vpMatrix[12]
        val clipY = vpMatrix[1] * wx + vpMatrix[5] * wy + vpMatrix[9] * wz + vpMatrix[13]
        val clipW = vpMatrix[3] * wx + vpMatrix[7] * wy + vpMatrix[11] * wz + vpMatrix[15]
        if (clipW <= 0.12f) return null
        val ndcX = clipX / clipW
        val ndcY = clipY / clipW
        val normX = (ndcX + 1f) * 0.5f
        val normY = (1f - ndcY) * 0.5f
        return normX to normY
    }

    private fun sampleYuvColor(cameraImage: Image?, normU: Float, normV: Float): Int {
        if (cameraImage == null || cameraImage.planes.isEmpty()) {
            return AndroidColor.rgb(168, 206, 224)
        }
        val w = cameraImage.width
        val h = cameraImage.height
        val px = (normU * (w - 1)).toInt().coerceIn(0, w - 1)
        val py = (normV * (h - 1)).toInt().coerceIn(0, h - 1)

        val yPlane = cameraImage.planes[0]
        val yBuf: ByteBuffer = yPlane.buffer
        val yIdx = py * yPlane.rowStride + px * yPlane.pixelStride
        if (yIdx !in 0 until yBuf.limit()) return AndroidColor.rgb(168, 206, 224)
        val yVal = yBuf.get(yIdx).toInt() and 0xFF

        if (cameraImage.planes.size < 3) return AndroidColor.rgb(yVal, yVal, yVal)
        val uPlane = cameraImage.planes[1]
        val vPlane = cameraImage.planes[2]
        val cx = px / 2
        val cy = py / 2
        val uIdx = cy * uPlane.rowStride + cx * uPlane.pixelStride
        val vIdx = cy * vPlane.rowStride + cx * vPlane.pixelStride
        if (uIdx !in 0 until uPlane.buffer.limit() || vIdx !in 0 until vPlane.buffer.limit()) {
            return AndroidColor.rgb(yVal, yVal, yVal)
        }
        val u = (uPlane.buffer.get(uIdx).toInt() and 0xFF) - 128
        val v = (vPlane.buffer.get(vIdx).toInt() and 0xFF) - 128
        val r = (yVal + 1.370705f * v).toInt().coerceIn(0, 255)
        val g = (yVal - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
        val b = (yVal + 1.732446f * u).toInt().coerceIn(0, 255)
        return AndroidColor.rgb(r, g, b)
    }

    @Suppress("DEPRECATION")
    private fun getDisplayRotation(context: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.findActivity()?.display?.rotation ?: Surface.ROTATION_0
            } else {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }
    }

    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
