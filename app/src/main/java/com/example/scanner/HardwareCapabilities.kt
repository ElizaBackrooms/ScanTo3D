package com.example.scanner

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.google.ar.core.ArCoreApk

data class SensorReadiness(
    val hasRearCamera: Boolean = false,
    val hasHardwareDepth: Boolean = false,
    val hasArCoreSupport: Boolean = false,
    val hasRotationVector: Boolean = false,
    val hasGyroscope: Boolean = false,
    val activeTierLabel: String = "Checking Sensors...",
    val activeTierDetail: String = ""
)

object HardwareCapabilities {

    fun probe(context: Context): SensorReadiness {
        var hasRearCamera = false
        var hasDepth = false

        try {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (camMgr != null) {
                for (id in camMgr.cameraIdList) {
                    val chars = camMgr.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK || camMgr.cameraIdList.size == 1) {
                        hasRearCamera = true
                        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                        if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
                            hasDepth = true
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Gracefully handle restricted camera access before permission
        }

        val hasArCore = try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            availability.isSupported
        } catch (_: Throwable) {
            false
        }

        val sensorMgr = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasRotation = sensorMgr?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
            sensorMgr?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null
        val hasGyro = sensorMgr?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

        val (tierLabel, tierDetail) = when {
            hasDepth && hasArCore -> "ToF LiDAR + ARCore Depth" to "Hardware DEPTH16 & 6-DoF SLAM ready"
            hasDepth -> "Hardware ToF Depth" to "Camera2 DEPTH16 + 6-DoF IMU ready"
            hasArCore && hasRearCamera -> "ARCore Depth-from-Motion" to "CameraX RGB + IMU + ARCore ready"
            hasRearCamera && hasRotation -> "CameraX + 6-DoF IMU Sweep" to "Photometric depth sweep & corner raycast"
            hasRearCamera -> "CameraX Spatial Sweep" to "Visual feature sweep & corner anchor mode"
            else -> "IMU Corner Raycast Mode" to "Manual architectural anchor capture"
        }

        return SensorReadiness(
            hasRearCamera = hasRearCamera,
            hasHardwareDepth = hasDepth,
            hasArCoreSupport = hasArCore,
            hasRotationVector = hasRotation,
            hasGyroscope = hasGyro,
            activeTierLabel = tierLabel,
            activeTierDetail = tierDetail
        )
    }
}
