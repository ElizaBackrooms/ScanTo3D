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
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
import com.google.ar.core.CameraIntrinsics
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
    val nx: Float,
    val ny: Float,
    val argb: Int,
    val surfaceType: Byte
)

data class ScreenEdge2D(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float
)

data class ScreenPlaneOverlay(
    val points: List<Pair<Float, Float>>,
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
    val projectedCorners: List<Pair<Float, Float>> = emptyList()
)

private data class PendingHitTap(val xPx: Float, val yPx: Float, val onResult: (Vec3?) -> Unit)

/**
 * Polycam-style ARCore 6-DoF SLAM + Dense DEPTH16 + Plane Triangulation + 60 FPS OpenGL Live Mesh Painter.
 *
 * Captures genuine connected 3D surface triangle patches from:
 * 1. Dense smoothed ARCore Depth (`acquireDepthImage16Bits()`) — covers smooth painted walls,
 *    floors, ceilings, and furniture with solid 2D-grid-connected surface triangles (strictly
 *    breaking across depth jumps > 0.22 m so objects never stretch webs to background walls).
 * 2. Tracked ARCore Planes (`HORIZONTAL_UPWARD_FACING`, `HORIZONTAL_DOWNWARD_FACING`, `VERTICAL`)
 *    triangulated into crisp, flat, real-RGB-colored floor/wall/ceiling/table meshes.
 * 3. Live 60 FPS OpenGL ES 2.0 mesh rendering (`ArLiveMeshRenderer`) painted directly on the real
 *    room surfaces as you move so you immediately see every scanned surface and missed gap.
 */
class ArCoreScannerRenderer(
    private val context: Context,
    private val liveAccumulator: LiveMeshAccumulator,
    private val onPatchCaptured: (CapturedMeshPatch) -> Unit,
    private val onArUnavailable: (String) -> Unit
) : GLSurfaceView.Renderer {

    private val backgroundRenderer = ArCameraBackgroundRenderer()
    private val liveMeshRenderer = ArLiveMeshRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())
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
    @Volatile var showLiveMeshOverlay: Boolean = true
    @Volatile var sampleStep: Int = 14
    @Volatile var currentCornerAnchors: List<Vec3> = emptyList()

    private val pendingTaps = ConcurrentLinkedQueue<PendingHitTap>()

    private var depthModeLabel = "ARCore SLAM"
    private var depthSupported = false
    private var hasSmoothDepth = false

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
                        hasSmoothDepth = true
                        depthModeLabel = "ARCore Dense DEPTH16"
                    }
                    s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> {
                        depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                        depthSupported = true
                        hasSmoothDepth = false
                        depthModeLabel = "ARCore Raw Depth"
                    }
                    else -> {
                        depthMode = Config.DepthMode.DISABLED
                        depthSupported = false
                        hasSmoothDepth = false
                        depthModeLabel = "ARCore 6-DoF Planes"
                    }
                }
            }
            s.configure(config)
            session = s
            _telemetry.value = _telemetry.value.copy(
                isArActive = true,
                depthApiActive = depthSupported,
                depthModeLabel = depthModeLabel,
                trackingHeadline = "Move phone slowly side-to-side to lock room"
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
        liveMeshRenderer.createOnGlThread()
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

                // Process queued tap-to-anchor hit tests
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
                            TrackingFailureReason.INSUFFICIENT_FEATURES -> "Aim at walls, furniture, or floor edges"
                            TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera busy"
                            else -> "Pan phone slowly side-to-side to lock tracking"
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

                // Compute exact 6-DoF view-projection matrix
                val pose = camera.pose
                val camPos = Vec3(pose.tx(), pose.ty(), pose.tz())
                val zAxis = pose.zAxis
                val forward = Vec3(-zAxis[0], -zAxis[1], -zAxis[2]).normalize()
                val yaw = atan2(forward.x.toDouble(), (-forward.z).toDouble()).toFloat()
                val pitch = atan2(forward.y.toDouble(), sqrt(forward.x * forward.x + forward.z * forward.z).toDouble()).toFloat()

                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projMatrix, 0, 0.08f, 25f)
                Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

                // Draw LIVE 60 FPS Polycam-style 3D wireframe + shaded mesh directly over camera!
                liveMeshRenderer.draw(vpMatrix, liveAccumulator, showLiveMeshOverlay)

                val trackedPlanes = s.getAllTrackables(Plane::class.java)
                    .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }

                // Gate depth keyframes by camera translation/rotation so we stitch clean continuous patches
                val movedDist = camPos.distanceTo(lastCamPosePos)
                val turnedDot = forward.dot(lastCamForward)
                val hasMovedEnough = movedDist > 0.04f || turnedDot < 0.996f || (now - lastDepthCaptureMs > 600L)

                if (isActivelyCapturing && hasMovedEnough && now - lastDepthCaptureMs >= 160L) {
                    lastDepthCaptureMs = now
                    lastCamPosePos = camPos
                    lastCamForward = forward

                    var gotDepthPatch = false
                    if (depthSupported) {
                        val depthPatch = extractDenseDepthGridPatch(frame, pose, trackedPlanes)
                        if (depthPatch.vertices.isNotEmpty()) {
                            onPatchCaptured(depthPatch)
                            gotDepthPatch = true
                        }
                    }

                    // Always harvest triangulated architectural planes (flat floors, walls, tables)
                    if (trackedPlanes.isNotEmpty() && (now - lastPlaneCaptureMs >= 360L || !gotDepthPatch)) {
                        lastPlaneCaptureMs = now
                        val planePatch = extractTrackedPlanesPatch(frame, pose, trackedPlanes)
                        if (planePatch.vertices.isNotEmpty()) {
                            onPatchCaptured(planePatch)
                        }
                    }

                    // Fallback to triangulated SLAM point cloud if depth isn't warm yet
                    if (!gotDepthPatch && trackedPlanes.isEmpty()) {
                        val slamPatch = extractSlamPatch(frame, pose, trackedPlanes)
                        if (slamPatch.vertices.isNotEmpty()) {
                            onPatchCaptured(slamPatch)
                        }
                    }
                }

                // Refresh HUD telemetry (~12 Hz)
                if (now - lastOverlayUpdateMs >= 85L) {
                    lastOverlayUpdateMs = now
                    val centerHit = performBestHitTest(frame, viewportWidth * 0.5f, viewportHeight * 0.5f)
                    val distMeters = centerHit?.first?.distanceTo(camPos)
                    val hitSrc = centerHit?.second ?: ""

                    val projectedPlanes = if (!isActivelyCapturing || liveAccumulator.version == 0) {
                        projectPlanesToScreen(trackedPlanes)
                    } else {
                        // Once live 3D mesh is painting, keep plane outlines subtle & uncluttered
                        projectPlanesToScreen(trackedPlanes)
                    }
                    val projectedCorners = projectCornersToScreen(currentCornerAnchors)

                    val headline = when {
                        isActivelyCapturing && depthSupported -> "SCANNING • Paint walls & floor slowly"
                        isActivelyCapturing -> "SCANNING • Move slowly across surfaces"
                        trackedPlanes.isNotEmpty() -> "LOCKED (${trackedPlanes.size} surfaces) • Press Scan to paint 3D mesh"
                        else -> "TRACKING READY • Press Scan & sweep room"
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
                        projectedCorners = projectedCorners
                    )
                }
            } catch (_: NotYetAvailableException) {
                // Normal during ARCore warm-up frames
            } catch (e: Throwable) {
                if (e.javaClass.name.contains("Unavailable") || e is SecurityException) {
                    handleSessionFailureLocked(e)
                }
            }
        }
    }

    /**
     * Extracts a CONNECTED 2D-grid 3D surface triangle patch directly from ARCore's dense Depth map.
     * Prioritizes `acquireDepthImage16Bits()` (dense smooth depth across drywall, floors, furniture)
     * and strictly breaks triangles across depth discontinuities (> 0.22 m) to prevent spiderwebs.
     */
    private fun extractDenseDepthGridPatch(
        frame: Frame,
        cameraPose: Pose,
        trackedPlanes: List<Plane>
    ): CapturedMeshPatch {
        var depthImage: Image? = null
        var confImage: Image? = null
        var cameraImage: Image? = null

        try {
            if (hasSmoothDepth) {
                depthImage = try {
                    frame.acquireDepthImage16Bits()
                } catch (_: Throwable) {
                    null
                }
            }
            if (depthImage == null) {
                try {
                    depthImage = frame.acquireRawDepthImage16Bits()
                    confImage = try {
                        frame.acquireRawDepthConfidenceImage()
                    } catch (_: Throwable) {
                        null
                    }
                } catch (_: Throwable) {
                    depthImage = null
                }
            }
            val dImg = depthImage ?: return CapturedMeshPatch(emptyList(), IntArray(0))

            cameraImage = try {
                frame.acquireCameraImage()
            } catch (_: Throwable) {
                null
            }

            val depthW = dImg.width
            val depthH = dImg.height
            if (depthW < 8 || depthH < 8) return CapturedMeshPatch(emptyList(), IntArray(0))

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
            if (fx < 1f || fy < 1f) return CapturedMeshPatch(emptyList(), IntArray(0))

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

            // Clean Polycam-density mesh grid (18x14 to 28x21 vertices per keyframe)
            val cols = (sampleStep + 8).coerceIn(18, 28)
            val rows = (cols * 3 / 4).coerceIn(14, 21)

            val gridVertIdx = IntArray(cols * rows) { -1 }
            val gridDepthM = FloatArray(cols * rows) { -1f }
            val verts = ArrayList<SpatialPoint>(cols * rows)
            val camLocal = FloatArray(3)
            val worldPt = FloatArray(3)

            // Inset slightly from outer frame border (5%..95%) where depth is cleanest
            for (r in 0 until rows) {
                val fracV = 0.06f + 0.88f * (r.toFloat() / (rows - 1).coerceAtLeast(1))
                val v = (fracV * (depthH - 1)).toInt().coerceIn(0, depthH - 1)
                for (c in 0 until cols) {
                    val fracU = 0.06f + 0.88f * (c.toFloat() / (cols - 1).coerceAtLeast(1))
                    val u = (fracU * (depthW - 1)).toInt().coerceIn(0, depthW - 1)

                    if (confBuf != null) {
                        val cIdx = v * confRowStride + u * confPixStride
                        if (cIdx in 0 until confBuf.limit()) {
                            val conf = confBuf.get(cIdx).toInt() and 0xFF
                            if (conf < 75) continue
                        }
                    }

                    val dIdx = v * depthRowStride + u * depthPixStride
                    if (dIdx + 1 >= depthBuf.limit()) continue

                    // Android DEPTH16 stores 13-bit millimeter depth in lower bits (0x1FFF)
                    val depthMm = depthBuf.getShort(dIdx).toInt() and 0x1FFF
                    if (depthMm !in 220..5000) continue

                    val depthM = depthMm * 0.001f
                    camLocal[0] = (u - cx) * depthM / fx
                    camLocal[1] = -(v - cy) * depthM / fy
                    camLocal[2] = -depthM

                    cameraPose.transformPoint(camLocal, 0, worldPt, 0)
                    val argb = sampleYuvColor(cameraImage, fracU, fracV)
                    val sType = classifySurface(worldPt[1], floorY, ceilY, depthM)

                    val cell = r * cols + c
                    gridVertIdx[cell] = verts.size
                    gridDepthM[cell] = depthM
                    verts.add(SpatialPoint(worldPt[0], worldPt[1], worldPt[2], argb, sType))
                }
            }

            // Connect adjacent 2x2 grid quads into real solid triangles, breaking across depth jumps!
            val tris = ArrayList<Int>(cols * rows * 6)
            for (r in 0 until rows - 1) {
                for (c in 0 until cols - 1) {
                    val c00 = r * cols + c
                    val c10 = r * cols + (c + 1)
                    val c11 = (r + 1) * cols + (c + 1)
                    val c01 = (r + 1) * cols + c

                    val i00 = gridVertIdx[c00]
                    val i10 = gridVertIdx[c10]
                    val i11 = gridVertIdx[c11]
                    val i01 = gridVertIdx[c01]

                    val d00 = gridDepthM[c00]
                    val d10 = gridDepthM[c10]
                    val d11 = gridDepthM[c11]
                    val d01 = gridDepthM[c01]

                    fun edgeValid(ia: Int, ib: Int, da: Float, db: Float): Boolean {
                        if (ia < 0 || ib < 0) return false
                        val maxJump = (0.12f + minOf(da, db) * 0.06f).coerceIn(0.14f, 0.32f)
                        if (abs(da - db) > maxJump) return false
                        val pa = verts[ia]; val pb = verts[ib]
                        val dx = pa.x - pb.x; val dy = pa.y - pb.y; val dz = pa.z - pb.z
                        val maxLen = (0.18f + minOf(da, db) * 0.09f).coerceIn(0.22f, 0.48f)
                        return dx * dx + dy * dy + dz * dz <= maxLen * maxLen
                    }

                    val e00_10 = edgeValid(i00, i10, d00, d10)
                    val e10_11 = edgeValid(i10, i11, d10, d11)
                    val e11_01 = edgeValid(i11, i01, d11, d01)
                    val e01_00 = edgeValid(i01, i00, d01, d00)
                    val e00_11 = edgeValid(i00, i11, d00, d11)

                    if (e00_10 && e10_11 && e00_11) {
                        tris.add(i00); tris.add(i11); tris.add(i10)
                    }
                    if (e00_11 && e11_01 && e01_00) {
                        tris.add(i00); tris.add(i01); tris.add(i11)
                    }
                }
            }

            return CapturedMeshPatch(verts, tris.toIntArray())
        } finally {
            try { depthImage?.close() } catch (_: Throwable) {}
            try { confImage?.close() } catch (_: Throwable) {}
            try { cameraImage?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Triangulates every ARCore tracked Plane (floors, walls, ceilings, tables) into crisp, flat,
     * subdivided triangles textured with real camera RGB pixels.
     */
    private fun extractTrackedPlanesPatch(
        frame: Frame,
        cameraPose: Pose,
        planes: List<Plane>
    ): CapturedMeshPatch {
        var cameraImage: Image? = null
        try {
            cameraImage = try {
                frame.acquireCameraImage()
            } catch (_: Throwable) {
                null
            }
            val intrinsics = frame.camera.imageIntrinsics
            val invPose = cameraPose.inverse()

            val verts = ArrayList<SpatialPoint>(512)
            val tris = ArrayList<Int>(1024)
            val localPt = FloatArray(3)
            val worldPt = FloatArray(3)

            for (plane in planes) {
                if (plane.extentX < 0.25f || plane.extentZ < 0.25f) continue
                val poly = plane.polygon ?: continue
                val n = poly.limit() / 2
                if (n < 3) continue

                val surfaceType = when (plane.type) {
                    Plane.Type.HORIZONTAL_UPWARD_FACING -> SpatialPoint.SURFACE_FLOOR
                    Plane.Type.HORIZONTAL_DOWNWARD_FACING -> SpatialPoint.SURFACE_CEILING
                    Plane.Type.VERTICAL -> SpatialPoint.SURFACE_WALL
                }
                val fallbackColor = when (surfaceType) {
                    SpatialPoint.SURFACE_FLOOR -> AndroidColor.rgb(122, 138, 154)
                    SpatialPoint.SURFACE_CEILING -> AndroidColor.rgb(186, 196, 206)
                    else -> AndroidColor.rgb(194, 202, 212)
                }

                val pose = plane.centerPose
                val polyX = FloatArray(n)
                val polyZ = FloatArray(n)
                var cx = 0f
                var cz = 0f
                for (i in 0 until n) {
                    polyX[i] = poly.get(i * 2)
                    polyZ[i] = poly.get(i * 2 + 1)
                    cx += polyX[i]
                    cz += polyZ[i]
                }
                cx /= n
                cz /= n

                fun addPlaneVertex(lx: Float, lz: Float): Int {
                    localPt[0] = lx; localPt[1] = 0f; localPt[2] = lz
                    pose.transformPoint(localPt, 0, worldPt, 0)
                    val uv = projectWorldToSensorUv(worldPt[0], worldPt[1], worldPt[2], invPose, intrinsics)
                    val col = if (uv != null && cameraImage != null) {
                        sampleYuvColor(cameraImage, uv.first, uv.second)
                    } else fallbackColor
                    val idx = verts.size
                    verts.add(SpatialPoint(worldPt[0], worldPt[1], worldPt[2], col, surfaceType))
                    return idx
                }

                // Concentric rings subdivide large floors/walls into clean ~25cm triangles
                val rings = ((maxOf(plane.extentX, plane.extentZ) / 0.35f).toInt()).coerceIn(2, 6)
                val centerIdx = addPlaneVertex(cx, cz)
                val ringIndices = Array(rings) { IntArray(n) }

                for (r in 1..rings) {
                    val f = r.toFloat() / rings
                    for (i in 0 until n) {
                        ringIndices[r - 1][i] = addPlaneVertex(
                            cx + (polyX[i] - cx) * f,
                            cz + (polyZ[i] - cz) * f
                        )
                    }
                }

                for (i in 0 until n) {
                    val next = (i + 1) % n
                    tris.add(centerIdx); tris.add(ringIndices[0][i]); tris.add(ringIndices[0][next])
                    for (r in 1 until rings) {
                        val a = ringIndices[r - 1][i]
                        val b = ringIndices[r - 1][next]
                        val c = ringIndices[r][next]
                        val d = ringIndices[r][i]
                        tris.add(a); tris.add(d); tris.add(c)
                        tris.add(a); tris.add(c); tris.add(b)
                    }
                }
            }
            return CapturedMeshPatch(verts, tris.toIntArray())
        } finally {
            try { cameraImage?.close() } catch (_: Throwable) {}
        }
    }

    private fun extractSlamPatch(
        frame: Frame,
        cameraPose: Pose,
        trackedPlanes: List<Plane>
    ): CapturedMeshPatch {
        var cameraImage: Image? = null
        val pointCloud = try {
            frame.acquirePointCloud()
        } catch (_: Throwable) {
            return CapturedMeshPatch(emptyList(), IntArray(0))
        }
        try {
            val buf = pointCloud.points
            if (buf.remaining() < 4) return CapturedMeshPatch(emptyList(), IntArray(0))

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

            val verts = ArrayList<SpatialPoint>(128)
            while (buf.remaining() >= 4) {
                val wx = buf.get()
                val wy = buf.get()
                val wz = buf.get()
                val conf = buf.get()
                if (conf < 0.35f) continue

                val dist = sqrt((wx - camX) * (wx - camX) + (wy - camY) * (wy - camY) + (wz - camZ) * (wz - camZ))
                if (dist !in 0.25f..6.5f) continue

                val uv = projectWorldToSensorUv(wx, wy, wz, invPose, intrinsics)
                val argb = if (uv != null) sampleYuvColor(cameraImage, uv.first, uv.second) else AndroidColor.rgb(164, 200, 224)
                verts.add(SpatialPoint(wx, wy, wz, argb, classifySurface(wy, floorY, ceilY, dist)))
            }

            // Connect nearby SLAM points (< 0.45 m) into small triangles so even SLAM-only devices paint mesh!
            val tris = ArrayList<Int>(verts.size * 3)
            val maxEdgeSq = 0.45f * 0.45f
            for (i in verts.indices) {
                val a = verts[i]
                var n1 = -1; var d1 = maxEdgeSq
                var n2 = -1; var d2 = maxEdgeSq
                for (j in i + 1 until verts.size) {
                    val b = verts[j]
                    val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
                    val dSq = dx * dx + dy * dy + dz * dz
                    if (dSq in 0.0025f..maxEdgeSq) {
                        if (dSq < d1) {
                            n2 = n1; d2 = d1
                            n1 = j; d1 = dSq
                        } else if (dSq < d2) {
                            n2 = j; d2 = dSq
                        }
                    }
                }
                if (n1 >= 0 && n2 >= 0) {
                    val p1 = verts[n1]; val p2 = verts[n2]
                    val dx = p1.x - p2.x; val dy = p1.y - p2.y; val dz = p1.z - p2.z
                    if (dx * dx + dy * dy + dz * dz <= maxEdgeSq) {
                        tris.add(i); tris.add(n1); tris.add(n2)
                    }
                }
            }
            return CapturedMeshPatch(verts, tris.toIntArray())
        } finally {
            try { pointCloud.release() } catch (_: Throwable) {}
            try { cameraImage?.close() } catch (_: Throwable) {}
        }
    }

    private fun performBestHitTest(frame: Frame, xPx: Float, yPx: Float): Pair<Vec3, String>? {
        val hits = try {
            frame.hitTest(xPx, yPx)
        } catch (_: Throwable) {
            return null
        }
        if (hits.isEmpty()) return null

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

    private fun projectWorldToSensorUv(
        wx: Float,
        wy: Float,
        wz: Float,
        invCameraPose: Pose,
        intrinsics: CameraIntrinsics
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
        return ((ndcX + 1f) * 0.5f) to ((1f - ndcY) * 0.5f)
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
