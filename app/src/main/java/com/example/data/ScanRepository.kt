package com.example.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.engine.CropBounds
import com.example.engine.ExportEngine
import com.example.engine.ExportFormat
import com.example.engine.ExportOptions
import com.example.engine.MeasurementPin
import com.example.engine.SpatialMesh
import com.example.engine.SpatialMeshProcessor
import com.example.engine.SpatialPoint
import com.example.engine.SpatialScanPackage
import com.example.engine.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ScanRepository(
    private val context: Context,
    private val dao: RoomScanDao
) {
    private val scansDir: File
        get() = File(context.filesDir, "scans").apply { mkdirs() }

    private val exportsDir: File
        get() = File(context.cacheDir, "exports").apply { mkdirs() }

    val allScans: Flow<List<RoomScanEntity>> = dao.observeAllScans()

    fun observeScan(id: Long): Flow<RoomScanEntity?> = dao.observeScanById(id)

    suspend fun getScan(id: Long): RoomScanEntity? = dao.getScanById(id)

    suspend fun saveNewScan(
        title: String,
        sensorMode: String,
        rawPoints: List<SpatialPoint>,
        cornerAnchors: List<Vec3>,
        ceilingHeightMeters: Float,
        voxelSizeCm: Int = 10
    ): Long = withContext(Dispatchers.Default) {
        val fileName = "scan_${UUID.randomUUID().toString().take(8)}.lfg"
        val file = File(scansDir, fileName)
        SpatialMeshProcessor.writeScanBinary(file, rawPoints, cornerAnchors, ceilingHeightMeters)

        val mesh = SpatialMeshProcessor.buildMesh(
            rawPoints = rawPoints,
            cornerAnchors = cornerAnchors,
            ceilingHeightMeters = ceilingHeightMeters,
            voxelSizeCm = voxelSizeCm,
            smoothSurface = true
        )

        val entity = RoomScanEntity(
            title = title,
            geometryFileName = fileName,
            sensorMode = sensorMode,
            vertexCount = mesh.vertexCount,
            triangleCount = mesh.triangleCount,
            widthMeters = mesh.bounds.width,
            depthMeters = mesh.bounds.depth,
            heightMeters = mesh.bounds.height,
            voxelSizeCm = voxelSizeCm,
            smoothSurface = true
        )
        dao.insertScan(entity)
    }

    suspend fun loadScanGeometry(entity: RoomScanEntity): SpatialScanPackage =
        withContext(Dispatchers.IO) {
            val file = File(scansDir, entity.geometryFileName)
            SpatialMeshProcessor.readScanBinary(
                file = file,
                voxelSizeCm = entity.voxelSizeCm,
                smoothSurface = entity.smoothSurface,
                cropBounds = entity.parsedCropBounds()
            )
        }

    suspend fun updateScanEdits(
        entity: RoomScanEntity,
        mesh: SpatialMesh,
        voxelSizeCm: Int,
        smoothSurface: Boolean,
        cropBounds: CropBounds,
        measurements: List<MeasurementPin>
    ) = withContext(Dispatchers.IO) {
        dao.updateScan(
            entity.copy(
                vertexCount = mesh.vertexCount,
                triangleCount = mesh.triangleCount,
                widthMeters = mesh.bounds.width,
                depthMeters = mesh.bounds.depth,
                heightMeters = mesh.bounds.height,
                voxelSizeCm = voxelSizeCm,
                smoothSurface = smoothSurface,
                cropBoundsRaw = cropBounds.serialize(),
                measurementsRaw = MeasurementPin.serializeList(measurements)
            )
        )
    }

    suspend fun renameScan(id: Long, newTitle: String) {
        dao.renameScan(id, newTitle.trim().ifBlank { "Untitled Room" })
    }

    suspend fun deleteScan(entity: RoomScanEntity) = withContext(Dispatchers.IO) {
        File(scansDir, entity.geometryFileName).delete()
        dao.deleteScanById(entity.id)
    }

    fun buildExportFileName(scanTitle: String, format: ExportFormat, presetId: String): String {
        val slug = scanTitle.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "room_scan" }
        val suffix = if (format == ExportFormat.HEIGHTMAP_PNG) "heightmap" else presetId
        return "${slug}_${suffix}.${format.extension}"
    }

    /**
     * Writes an export artifact to internal cache and returns its FileProvider content:// Uri
     * ready for Android Share Sheet (ACTION_SEND) to Bryce 3D / Blender companion apps.
     */
    suspend fun exportToShareUri(
        scanTitle: String,
        mesh: SpatialMesh,
        format: ExportFormat,
        options: ExportOptions
    ): Pair<Uri, File> = withContext(Dispatchers.IO) {
        val fileName = buildExportFileName(scanTitle, format, options.preset.id)
        val outFile = File(exportsDir, fileName)
        BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
            ExportEngine.export(mesh, format, options, scanTitle, bos)
        }
        // Also emit companion .mtl when exporting OBJ
        if (format == ExportFormat.OBJ) {
            val mtlFile = File(exportsDir, fileName.removeSuffix(".obj") + ".mtl")
            BufferedOutputStream(FileOutputStream(mtlFile)).use { bos ->
                ExportEngine.writeMtl(bos)
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        uri to outFile
    }

    /**
     * Streams the selected 3D format directly into a user-chosen Storage Access Framework (SAF) Uri.
     * Requires zero broad storage permissions.
     */
    suspend fun exportToSafUri(
        uri: Uri,
        scanTitle: String,
        mesh: SpatialMesh,
        format: ExportFormat,
        options: ExportOptions
    ): Boolean = withContext(Dispatchers.IO) {
        val os = context.contentResolver.openOutputStream(uri) ?: return@withContext false
        BufferedOutputStream(os).use { bos ->
            ExportEngine.export(mesh, format, options, scanTitle, bos)
        }
        true
    }
}
