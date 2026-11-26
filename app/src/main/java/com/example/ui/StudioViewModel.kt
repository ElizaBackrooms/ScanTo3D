package com.example.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.RoomScanEntity
import com.example.data.ScanRepository
import com.example.data.StudioPreferences
import com.example.engine.CropBounds
import com.example.engine.ExportFormat
import com.example.engine.ExportOptions
import com.example.engine.ExportPreset
import com.example.engine.MeasurementPin
import com.example.engine.MeasurementUnit
import com.example.engine.ShadingMode
import com.example.engine.SpatialMesh
import com.example.engine.SpatialMeshProcessor
import com.example.engine.SpatialScanPackage
import com.example.engine.UpAxis
import com.example.engine.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class StudioTool(val label: String) {
    VIEW("View"),
    CROP("Crop"),
    DENSITY("Density"),
    MEASURE("Measure"),
    EXPORT("Export")
}

enum class CameraViewPreset(val label: String, val yaw: Float, val pitch: Float) {
    ISO("ISO", 0.68f, 0.52f),
    TOP("TOP", 0f, 1.48f),
    FRONT("FRONT", 0f, 0.08f),
    SIDE("SIDE", 1.5708f, 0.08f)
}

data class OrbitCameraState(
    val yawRad: Float = CameraViewPreset.ISO.yaw,
    val pitchRad: Float = CameraViewPreset.ISO.pitch,
    val zoom: Float = 1.0f,
    val panX: Float = 0f,
    val panY: Float = 0f
)

data class StudioUiState(
    val isLoading: Boolean = true,
    val isRebuildingMesh: Boolean = false,
    val isExporting: Boolean = false,
    val entity: RoomScanEntity? = null,
    val uncroppedMesh: SpatialMesh = SpatialMesh.empty(),
    val activeMesh: SpatialMesh = SpatialMesh.empty(),
    val camera: OrbitCameraState = OrbitCameraState(),
    val shadingMode: ShadingMode = ShadingMode.SHADED,
    val activeTool: StudioTool = StudioTool.VIEW,
    val cropBounds: CropBounds = CropBounds(),
    val voxelSizeCm: Int = 10,
    val smoothSurface: Boolean = true,
    val measurements: List<MeasurementPin> = emptyList(),
    val pendingMeasureStart: Vec3? = null,
    val measurementUnit: MeasurementUnit = MeasurementUnit.METERS,
    val exportOptions: ExportOptions = ExportOptions(),
    val selectedExportFormat: ExportFormat = ExportFormat.OBJ,
    val snackbarMessage: String? = null
)

class StudioViewModel(
    private val scanId: Long,
    private val repository: ScanRepository,
    private val preferences: StudioPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var scanPackage: SpatialScanPackage? = null
    private var rebuildJob: Job? = null

    init {
        loadScan()
    }

    private fun loadScan() {
        viewModelScope.launch {
            val prefs = preferences.settingsFlow.first()
            val entity = repository.getScan(scanId)
            if (entity == null) {
                _uiState.update { it.copy(isLoading = false, snackbarMessage = "Room scan not found") }
                return@launch
            }
            val pkg = repository.loadScanGeometry(entity)
            scanPackage = pkg

            val uncropped = withContext(Dispatchers.Default) {
                SpatialMeshProcessor.buildMesh(
                    rawPoints = pkg.rawPoints,
                    cornerAnchors = pkg.cornerAnchors,
                    ceilingHeightMeters = pkg.ceilingHeightMeters,
                    voxelSizeCm = entity.voxelSizeCm,
                    smoothSurface = entity.smoothSurface,
                    cropBounds = CropBounds()
                )
            }
            val crop = entity.parsedCropBounds()
            val active = if (crop.isFull) uncropped else SpatialMeshProcessor.applyCrop(uncropped, crop)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    entity = entity,
                    uncroppedMesh = uncropped,
                    activeMesh = active,
                    cropBounds = crop,
                    voxelSizeCm = entity.voxelSizeCm,
                    smoothSurface = entity.smoothSurface,
                    measurements = entity.parsedMeasurements(),
                    measurementUnit = prefs.measurementUnit,
                    exportOptions = ExportOptions(preset = prefs.defaultExportPreset),
                    selectedExportFormat = prefs.defaultExportPreset.recommendedFormats.first()
                )
            }
        }
    }

    fun orbitBy(deltaYaw: Float, deltaPitch: Float) {
        _uiState.update { s ->
            s.copy(
                camera = s.camera.copy(
                    yawRad = s.camera.yawRad + deltaYaw,
                    pitchRad = (s.camera.pitchRad + deltaPitch).coerceIn(-1.45f, 1.52f)
                )
            )
        }
    }

    fun panAndZoom(deltaPanX: Float, deltaPanY: Float, zoomFactor: Float) {
        _uiState.update { s ->
            s.copy(
                camera = s.camera.copy(
                    zoom = (s.camera.zoom * zoomFactor).coerceIn(0.35f, 4.5f),
                    panX = (s.camera.panX + deltaPanX).coerceIn(-600f, 600f),
                    panY = (s.camera.panY + deltaPanY).coerceIn(-600f, 600f)
                )
            )
        }
    }

    fun snapCamera(preset: CameraViewPreset) {
        _uiState.update { s ->
            s.copy(
                camera = OrbitCameraState(
                    yawRad = preset.yaw,
                    pitchRad = preset.pitch,
                    zoom = 1.0f,
                    panX = 0f,
                    panY = 0f
                )
            )
        }
    }

    fun resetCamera() = snapCamera(CameraViewPreset.ISO)

    fun setShadingMode(mode: ShadingMode) {
        _uiState.update { it.copy(shadingMode = mode) }
    }

    fun setActiveTool(tool: StudioTool) {
        _uiState.update {
            it.copy(
                activeTool = tool,
                pendingMeasureStart = if (tool != StudioTool.MEASURE) null else it.pendingMeasureStart
            )
        }
    }

    fun updateCropBounds(bounds: CropBounds) {
        _uiState.update { it.copy(cropBounds = bounds) }
    }

    fun resetCropBounds() {
        _uiState.update { s ->
            s.copy(
                cropBounds = CropBounds(),
                activeMesh = s.uncroppedMesh,
                snackbarMessage = "Crop box reset to full room"
            )
        }
        persistCurrentEdits()
    }

    fun applyCrop() {
        val state = _uiState.value
        viewModelScope.launch {
            val cropped = withContext(Dispatchers.Default) {
                SpatialMeshProcessor.applyCrop(state.uncroppedMesh, state.cropBounds)
            }
            if (cropped.vertexCount == 0) {
                _uiState.update { it.copy(snackbarMessage = "Crop box is empty—widen bounds to keep geometry") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    activeMesh = cropped,
                    snackbarMessage = "Crop applied (${cropped.vertexCount} vertices kept)"
                )
            }
            persistCurrentEdits()
        }
    }

    fun updateDensity(voxelSizeCm: Int, smoothSurface: Boolean) {
        val pkg = scanPackage ?: return
        val clamped = voxelSizeCm.coerceIn(4, 24)
        _uiState.update { it.copy(voxelSizeCm = clamped, smoothSurface = smoothSurface, isRebuildingMesh = true) }

        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            val (uncropped, cropped) = withContext(Dispatchers.Default) {
                val full = SpatialMeshProcessor.buildMesh(
                    rawPoints = pkg.rawPoints,
                    cornerAnchors = pkg.cornerAnchors,
                    ceilingHeightMeters = pkg.ceilingHeightMeters,
                    voxelSizeCm = clamped,
                    smoothSurface = smoothSurface,
                    cropBounds = CropBounds()
                )
                val active = SpatialMeshProcessor.applyCrop(full, _uiState.value.cropBounds)
                full to (if (active.vertexCount > 0) active else full)
            }
            _uiState.update {
                it.copy(
                    uncroppedMesh = uncropped,
                    activeMesh = cropped,
                    isRebuildingMesh = false
                )
            }
            persistCurrentEdits()
        }
    }

    fun onMeasurementPointPicked(worldPoint: Vec3) {
        val cur = _uiState.value
        val start = cur.pendingMeasureStart
        if (start == null) {
            _uiState.update { it.copy(pendingMeasureStart = worldPoint) }
        } else {
            val nextIndex = cur.measurements.size + 1
            val pin = MeasurementPin(
                id = UUID.randomUUID().toString().take(8),
                label = "M$nextIndex",
                start = start,
                end = worldPoint
            )
            _uiState.update {
                it.copy(
                    pendingMeasureStart = null,
                    measurements = it.measurements + pin
                )
            }
            persistCurrentEdits()
        }
    }

    fun cancelPendingMeasurement() {
        _uiState.update { it.copy(pendingMeasureStart = null) }
    }

    fun deleteMeasurement(id: String) {
        _uiState.update { it.copy(measurements = it.measurements.filterNot { m -> m.id == id }) }
        persistCurrentEdits()
    }

    fun clearMeasurements() {
        _uiState.update { it.copy(measurements = emptyList(), pendingMeasureStart = null) }
        persistCurrentEdits()
    }

    fun setMeasurementUnit(unit: MeasurementUnit) {
        _uiState.update { it.copy(measurementUnit = unit) }
        viewModelScope.launch { preferences.setMeasurementUnit(unit) }
    }

    fun selectExportPreset(preset: ExportPreset) {
        _uiState.update {
            it.copy(
                exportOptions = it.exportOptions.copy(
                    preset = preset,
                    upAxis = preset.upAxis,
                    scaleFactor = preset.scaleFactor,
                    centerOrigin = preset.centerOrigin,
                    includeVertexColors = preset.includeVertexColors
                ),
                selectedExportFormat = preset.recommendedFormats.firstOrNull() ?: it.selectedExportFormat
            )
        }
        viewModelScope.launch { preferences.setDefaultExportPreset(preset) }
    }

    fun selectExportFormat(format: ExportFormat) {
        _uiState.update { it.copy(selectedExportFormat = format) }
    }

    fun setExportUpAxis(axis: UpAxis) {
        _uiState.update {
            it.copy(exportOptions = it.exportOptions.copy(preset = ExportPreset.CUSTOM, upAxis = axis))
        }
    }

    fun setExportScale(scale: Float) {
        _uiState.update {
            it.copy(exportOptions = it.exportOptions.copy(preset = ExportPreset.CUSTOM, scaleFactor = scale))
        }
    }

    fun toggleCenterOrigin(enabled: Boolean) {
        _uiState.update {
            it.copy(exportOptions = it.exportOptions.copy(centerOrigin = enabled))
        }
    }

    fun toggleVertexColors(enabled: Boolean) {
        _uiState.update {
            it.copy(exportOptions = it.exportOptions.copy(includeVertexColors = enabled))
        }
    }

    fun setHeightmapResolution(resolution: Int) {
        _uiState.update {
            it.copy(exportOptions = it.exportOptions.copy(heightmapResolution = resolution.coerceIn(256, 1024)))
        }
    }

    fun suggestedExportFileName(format: ExportFormat = _uiState.value.selectedExportFormat): String {
        val title = _uiState.value.entity?.title ?: "room_scan"
        return repository.buildExportFileName(title, format, _uiState.value.exportOptions.preset.id)
    }

    fun prepareShareExport(
        format: ExportFormat = _uiState.value.selectedExportFormat,
        onReady: (uri: Uri, mimeType: String, displayName: String) -> Unit
    ) {
        val state = _uiState.value
        val title = state.entity?.title ?: "Room Scan"
        if (state.activeMesh.vertexCount == 0) {
            _uiState.update { it.copy(snackbarMessage = "No geometry to export") }
            return
        }
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            try {
                val (uri, file) = repository.exportToShareUri(
                    scanTitle = title,
                    mesh = state.activeMesh,
                    format = format,
                    options = state.exportOptions
                )
                _uiState.update { it.copy(isExporting = false) }
                onReady(uri, format.mimeType, file.name)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExporting = false, snackbarMessage = "Export failed: ${e.message ?: "I/O error"}")
                }
            }
        }
    }

    fun saveExportToSafUri(uri: Uri, format: ExportFormat = _uiState.value.selectedExportFormat) {
        val state = _uiState.value
        val title = state.entity?.title ?: "Room Scan"
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            try {
                val ok = repository.exportToSafUri(
                    uri = uri,
                    scanTitle = title,
                    mesh = state.activeMesh,
                    format = format,
                    options = state.exportOptions
                )
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        snackbarMessage = if (ok) "Saved ${format.title} (${state.exportOptions.preset.label}) to Files"
                        else "Could not write to selected file"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExporting = false, snackbarMessage = "Save failed: ${e.message ?: "I/O error"}")
                }
            }
        }
    }

    fun renameCurrentScan(newTitle: String) {
        val entity = _uiState.value.entity ?: return
        val clean = newTitle.trim().ifBlank { return }
        _uiState.update { it.copy(entity = entity.copy(title = clean)) }
        viewModelScope.launch { repository.renameScan(entity.id, clean) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun persistCurrentEdits() {
        val s = _uiState.value
        val entity = s.entity ?: return
        viewModelScope.launch {
            repository.updateScanEdits(
                entity = entity,
                mesh = s.activeMesh,
                voxelSizeCm = s.voxelSizeCm,
                smoothSurface = s.smoothSurface,
                cropBounds = s.cropBounds,
                measurements = s.measurements
            )
            _uiState.update { cur ->
                cur.copy(
                    entity = cur.entity?.copy(
                        vertexCount = s.activeMesh.vertexCount,
                        triangleCount = s.activeMesh.triangleCount,
                        widthMeters = s.activeMesh.bounds.width,
                        depthMeters = s.activeMesh.bounds.depth,
                        heightMeters = s.activeMesh.bounds.height,
                        voxelSizeCm = s.voxelSizeCm,
                        smoothSurface = s.smoothSurface,
                        cropBoundsRaw = s.cropBounds.serialize(),
                        measurementsRaw = MeasurementPin.serializeList(s.measurements)
                    )
                )
            }
        }
    }

    companion object {
        fun factory(
            scanId: Long,
            repository: ScanRepository,
            preferences: StudioPreferences
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StudioViewModel(scanId, repository, preferences) as T
        }
    }
}
