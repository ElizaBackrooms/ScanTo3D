package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.engine.CropBounds
import com.example.engine.MeasurementPin

@Entity(tableName = "room_scans")
data class RoomScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val geometryFileName: String,
    val sensorMode: String,
    val vertexCount: Int,
    val triangleCount: Int,
    val widthMeters: Float,
    val depthMeters: Float,
    val heightMeters: Float,
    val voxelSizeCm: Int = 10,
    val smoothSurface: Boolean = true,
    val cropBoundsRaw: String = CropBounds().serialize(),
    val measurementsRaw: String = ""
) {
    fun parsedCropBounds(): CropBounds = CropBounds.deserialize(cropBoundsRaw)
    fun parsedMeasurements(): List<MeasurementPin> = MeasurementPin.deserializeList(measurementsRaw)
}
