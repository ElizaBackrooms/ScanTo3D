package com.example.scanner

import android.content.Context
import android.graphics.Color as AndroidColor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class ImuPose(
    val yawRad: Float = 0f,
    val pitchRad: Float = -0.22f,
    val rollRad: Float = 0f,
    val hasLiveImu: Boolean = false
) {
    val yawDegrees: Float get() = ((yawRad * 180f / PI.toFloat()) % 360f + 360f) % 360f
    val pitchDegrees: Float get() = pitchRad * 180f / PI.toFloat()

    /** Unit forward vector in Y-Up world coordinates */
    fun forwardVector(): Vec3 {
        val cp = cos(pitchRad)
        return Vec3(
            x = sin(yawRad) * cp,
            y = sin(pitchRad),
            z = -cos(yawRad) * cp
        ).normalize()
    }

    /** Unit right vector on horizontal plane */
    fun rightVector(): Vec3 = Vec3(cos(yawRad), 0f, sin(yawRad)).normalize()
}

/**
 * Fallback spatial capture controller for devices without ARCore (or when CameraX+IMU mode is chosen).
 * Combines:
 * 1. Android SensorManager orientation + additive manual touch-steering (always responsive on emulators).
 * 2. Live CameraX YUV ImageAnalysis with motion-gated capture (prevents duplicate stationary flooding).
 * 3. Wall-polygon & floor/ceiling plane ray intersection so anchored corners sculpt crisp room geometry.
 */
class SpatialCaptureController(
    context: Context,
    private val onPointsCaptured: (List<SpatialPoint>) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var sensorYaw = 0f
    @Volatile private var sensorPitch = -0.22f
    @Volatile private var sensorRoll = 0f
    @Volatile private var hasReceivedSensor = false
    @Volatile private var manualYawOffset = 0f
    @Volatile private var manualPitchOffset = 0f

    private val _imuPose = MutableStateFlow(ImuPose())
    val imuPose: StateFlow<ImuPose> = _imuPose.asStateFlow()

    @Volatile var isActivelyCapturing: Boolean = false
    @Volatile var eyeHeightMeters: Float = 1.50f
    @Volatile var ceilingHeightMeters: Float = 2.60f
    @Volatile var sampleStep: Int = 14
    @Volatile var roomCorners: List<Vec3> = emptyList()

    private var lastFrameTimeMs = 0L
    private var prevLumaSignature = FloatArray(0)
    private var lastCapturedYaw = Float.MAX_VALUE
    private var lastCapturedPitch = Float.MAX_VALUE

    private val tanHalfFovH = 0.58f
    private val tanHalfFovV = 0.44f

    fun startSensors() {
        val mgr = sensorManager ?: return
        if (rotationSensor != null) {
            mgr.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            mgr.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopSensors() {
        sensorManager?.unregisterListener(this)
    }

    /** Always allows touch-dragging the camera aim (additive on top of IMU, ideal on emulators). */
    fun nudgeManualPose(deltaYawRad: Float, deltaPitchRad: Float) {
        manualYawOffset += deltaYawRad
        manualPitchOffset = (manualPitchOffset + deltaPitchRad).coerceIn(-1.15f, 1.15f)
        emitCombinedPose()
    }

    private fun emitCombinedPose() {
        val yaw = sensorYaw + manualYawOffset
        val pitch = (sensorPitch + manualPitchOffset).coerceIn(-1.25f, 1.15f)
        _imuPose.value = ImuPose(
            yawRad = yaw,
            pitchRad = pitch,
            rollRad = sensorRoll,
            hasLiveImu = hasReceivedSensor
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remappedMatrix
                )
                SensorManager.getOrientation(remappedMatrix, orientation)
                sensorYaw = orientation[0]
                sensorPitch = (-orientation[1]).coerceIn(-1.30f, 1.30f)
                sensorRoll = orientation[2]
                hasReceivedSensor = true
                emitCombinedPose()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val norm = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(0.01f)
                sensorPitch = (-kotlin.math.asin((az / norm).coerceIn(-1f, 1f))).coerceIn(-1.25f, 1.25f)
                hasReceivedSensor = true
                emitCombinedPose()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Raycasts the center reticle onto y = 0 floor plane. */
    fun raycastFloorCorner(): Vec3 = raycastFloorCornerAt(0.5f, 0.5f)

    /** Raycasts any normalized (0..1, 0..1) tap coordinate onto y = 0 floor plane. */
    fun raycastFloorCornerAt(normX: Float, normY: Float): Vec3 {
        val pose = _imuPose.value
        val fwd = pose.forwardVector()
        val right = pose.rightVector()
        val up = right.cross(fwd).normalize()
        val ndcX = (normX - 0.5f) * 2f
        val ndcY = (0.5f - normY) * 2f
        val ray = (fwd + right * (ndcX * tanHalfFovH) + up * (ndcY * tanHalfFovV)).normalize()

        val downSlope = (-ray.y).coerceIn(0.18f, 1.25f)
        val dist = (eyeHeightMeters / downSlope).coerceIn(0.75f, 7.5f)
        val horizLen = sqrt(ray.x * ray.x + ray.z * ray.z).coerceAtLeast(0.01f)
        val horizDist = (eyeHeightMeters / tan(kotlin.math.asin(downSlope.coerceIn(0.18f, 0.95f)))).coerceIn(0.75f, 7.5f)
        return Vec3((ray.x / horizLen) * horizDist, 0f, (ray.z / horizLen) * horizDist)
    }

    /** Estimates range at center reticle for live HUD readout. */
    fun estimateReticleDistance(): Float {
        val pose = _imuPose.value
        val fwd = pose.forwardVector()
        return intersectRoomPlanes(fwd, eyeHeightMeters, ceilingHeightMeters, roomCorners).first
    }

    /** Projects world points into normalized (0..1) screen space for live fallback AR HUD overlay. */
    fun projectPointsToScreen(points: List<SpatialPoint>): List<ScreenPoint2D> {
        if (points.isEmpty()) return emptyList()
        val pose = _imuPose.value
        val fwd = pose.forwardVector()
        val right = pose.rightVector()
        val up = right.cross(fwd).normalize()
        val eye = Vec3(0f, eyeHeightMeters, 0f)
        val out = ArrayList<ScreenPoint2D>(points.size.coerceAtMost(220))
        for (p in points) {
            val rel = Vec3(p.x - eye.x, p.y - eye.y, p.z - eye.z)
            val depth = rel.dot(fwd)
            if (depth <= 0.15f) continue
            val ndcX = rel.dot(right) / (depth * tanHalfFovH)
            val ndcY = rel.dot(up) / (depth * tanHalfFovV)
            if (ndcX in -1.05f..1.05f && ndcY in -1.05f..1.05f) {
                out.add(ScreenPoint2D((ndcX + 1f) * 0.5f, (1f - ndcY) * 0.5f, p.argb, p.surfaceType))
            }
        }
        return out
    }

    fun projectCornersToScreen(corners: List<Vec3>): List<Pair<Float, Float>> {
        if (corners.isEmpty()) return emptyList()
        val pose = _imuPose.value
        val fwd = pose.forwardVector()
        val right = pose.rightVector()
        val up = right.cross(fwd).normalize()
        val eye = Vec3(0f, eyeHeightMeters, 0f)
        return corners.mapNotNull { c ->
            val rel = c - eye
            val depth = rel.dot(fwd)
            if (depth <= 0.15f) return@mapNotNull null
            val ndcX = rel.dot(right) / (depth * tanHalfFovH)
            val ndcY = rel.dot(up) / (depth * tanHalfFovV)
            ((ndcX + 1f) * 0.5f) to ((1f - ndcY) * 0.5f)
        }
    }

    fun createImageAnalyzer(): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image: ImageProxy ->
        try {
            if (!isActivelyCapturing) return@Analyzer
            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < 150L) return@Analyzer

            val pose = _imuPose.value
            val angularDelta = if (lastCapturedYaw == Float.MAX_VALUE) {
                1f
            } else {
                abs(pose.yawRad - lastCapturedYaw) + abs(pose.pitchRad - lastCapturedPitch)
            }

            // Avoid spamming thousands of duplicate points when device is completely motionless
            if (angularDelta < 0.025f && now - lastFrameTimeMs < 750L) {
                return@Analyzer
            }

            lastFrameTimeMs = now
            lastCapturedYaw = pose.yawRad
            lastCapturedPitch = pose.pitchRad

            val points = extractPointsFromFrame(image, pose, angularDelta)
            if (points.isNotEmpty()) {
                onPointsCaptured(points)
            }
        } finally {
            image.close()
        }
    }

    private fun extractPointsFromFrame(
        image: ImageProxy,
        pose: ImuPose,
        angularSpeed: Float
    ): List<SpatialPoint> {
        val width = image.width
        val height = image.height
        if (width < 32 || height < 32 || image.planes.isEmpty()) return emptyList()

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride

        val hasChroma = image.planes.size >= 3
        val uPlane = if (hasChroma) image.planes[1] else null
        val vPlane = if (hasChroma) image.planes[2] else null
        val uBuf = uPlane?.buffer
        val vBuf = vPlane?.buffer

        val cols = sampleStep.coerceIn(8, 20)
        val rows = (cols * 3 / 4).coerceIn(6, 15)
        val signature = FloatArray(cols * rows)

        val fwd = pose.forwardVector()
        val right = pose.rightVector()
        val up = right.cross(fwd).normalize()

        val cornersSnapshot = roomCorners
        val out = ArrayList<SpatialPoint>(cols * rows)
        val prevMatches = prevLumaSignature.size == signature.size

        for (r in 0 until rows) {
            val ny = (r + 0.5f) / rows
            val py = (ny * (height - 2)).toInt().coerceIn(1, height - 2)
            for (c in 0 until cols) {
                val nx = (c + 0.5f) / cols
                val px = (nx * (width - 2)).toInt().coerceIn(1, width - 2)

                val lumaIdx = py * yRowStride + px * yPixStride
                if (lumaIdx >= yBuf.limit()) continue
                val yVal = (yBuf.get(lumaIdx).toInt() and 0xFF)
                val yRight = (yBuf.get((py * yRowStride + (px + 1) * yPixStride).coerceAtMost(yBuf.limit() - 1)).toInt() and 0xFF)
                val yDown = (yBuf.get(((py + 1) * yRowStride + px * yPixStride).coerceAtMost(yBuf.limit() - 1)).toInt() and 0xFF)
                val gradient = (abs(yVal - yRight) + abs(yVal - yDown)) / 510f

                val sigIdx = r * cols + c
                val lumaNorm = yVal / 255f
                signature[sigIdx] = lumaNorm
                val temporalDelta = if (prevMatches) abs(lumaNorm - prevLumaSignature[sigIdx]) else 0.04f

                val ndcX = (nx - 0.5f) * 2f
                val ndcY = (0.5f - ny) * 2f
                val ray = (fwd + right * (ndcX * tanHalfFovH) + up * (ndcY * tanHalfFovV)).normalize()

                val (baseDist, baseSurface) = intersectRoomPlanes(
                    ray = ray,
                    eyeHeight = eyeHeightMeters,
                    ceilingHeight = ceilingHeightMeters,
                    corners = cornersSnapshot
                )

                // Modulate non-floor/ceiling hits slightly with local structural edges for relief
                val depth = if (baseSurface == SpatialPoint.SURFACE_WALL) {
                    val relief = (gradient * 0.25f + (if (angularSpeed > 0.02f) (temporalDelta * 0.25f) else 0f)).coerceIn(0f, 0.45f)
                    (baseDist - relief).coerceIn(0.65f, 6.5f)
                } else {
                    baseDist
                }
                val finalSurface = if (baseSurface == SpatialPoint.SURFACE_WALL && gradient > 0.18f) {
                    SpatialPoint.SURFACE_OBJECT
                } else {
                    baseSurface
                }

                val wx = ray.x * depth
                val wy = (eyeHeightMeters + ray.y * depth).coerceIn(0f, ceilingHeightMeters + 0.15f)
                val wz = ray.z * depth

                val argb = sampleRgbColor(yVal, px, py, uPlane, vPlane, uBuf, vBuf)
                out.add(SpatialPoint(wx, wy, wz, argb, finalSurface))
            }
        }
        prevLumaSignature = signature
        return out
    }

    /**
     * Intersects a world-space camera ray with:
     * 1. Floor plane (y = 0)
     * 2. Ceiling plane (y = ceilingHeight)
     * 3. Any anchored room wall segments (if >= 3 corners anchored) OR a crisp rectangular room fallback
     */
    private fun intersectRoomPlanes(
        ray: Vec3,
        eyeHeight: Float,
        ceilingHeight: Float,
        corners: List<Vec3>
    ): Pair<Float, Byte> {
        val floorDist = if (ray.y < -0.05f) (-eyeHeight / ray.y) else Float.MAX_VALUE
        val ceilDist = if (ray.y > 0.05f) ((ceilingHeight - eyeHeight) / ray.y) else Float.MAX_VALUE

        var wallDist = Float.MAX_VALUE
        if (corners.size >= 3) {
            val horizLen = sqrt(ray.x * ray.x + ray.z * ray.z)
            if (horizLen > 0.02f) {
                val dx = ray.x
                val dz = ray.z
                for (i in corners.indices) {
                    val p0 = corners[i]
                    val p1 = corners[(i + 1) % corners.size]
                    val sx = p1.x - p0.x
                    val sz = p1.z - p0.z
                    val denom = dx * sz - dz * sx
                    if (abs(denom) > 1e-5f) {
                        val t = (p0.x * sz - p0.z * sx) / denom
                        val u = (p0.x * dz - p0.z * dx) / denom
                        if (t > 0.35f && u in 0f..1f && t < wallDist) {
                            wallDist = t
                        }
                    }
                }
            }
        }

        // If no wall polygon hit, intersect an axis-aligned rectangular room box (±2.6m) rather than a round sphere
        if (wallDist == Float.MAX_VALUE) {
            val halfW = 2.6f
            val halfD = 3.0f
            val tx = if (abs(ray.x) > 1e-4f) halfW / abs(ray.x) else Float.MAX_VALUE
            val tz = if (abs(ray.z) > 1e-4f) halfD / abs(ray.z) else Float.MAX_VALUE
            wallDist = minOf(tx, tz).coerceIn(0.8f, 5.5f)
        }

        return when {
            floorDist <= wallDist && floorDist <= ceilDist && floorDist in 0.4f..7.0f ->
                floorDist to SpatialPoint.SURFACE_FLOOR
            ceilDist <= wallDist && ceilDist <= floorDist && ceilDist in 0.4f..7.0f ->
                ceilDist to SpatialPoint.SURFACE_CEILING
            else ->
                wallDist.coerceIn(0.5f, 7.0f) to SpatialPoint.SURFACE_WALL
        }
    }

    private fun sampleRgbColor(
        yVal: Int,
        px: Int,
        py: Int,
        uPlane: ImageProxy.PlaneProxy?,
        vPlane: ImageProxy.PlaneProxy?,
        uBuf: java.nio.ByteBuffer?,
        vBuf: java.nio.ByteBuffer?
    ): Int {
        if (uPlane == null || vPlane == null || uBuf == null || vBuf == null) {
            return AndroidColor.rgb(yVal, yVal, yVal)
        }
        val cx = px / 2
        val cy = py / 2
        val uIdx = cy * uPlane.rowStride + cx * uPlane.pixelStride
        val vIdx = cy * vPlane.rowStride + cx * vPlane.pixelStride
        if (uIdx >= uBuf.limit() || vIdx >= vBuf.limit()) {
            return AndroidColor.rgb(yVal, yVal, yVal)
        }
        val u = (uBuf.get(uIdx).toInt() and 0xFF) - 128
        val v = (vBuf.get(vIdx).toInt() and 0xFF) - 128
        val r = (yVal + 1.370705f * v).toInt().coerceIn(0, 255)
        val g = (yVal - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
        val b = (yVal + 1.732446f * u).toInt().coerceIn(0, 255)
        return AndroidColor.rgb(r, g, b)
    }
}
