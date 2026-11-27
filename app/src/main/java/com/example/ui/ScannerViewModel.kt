package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ScanRepository
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
import com.example.scanner.CapturedMeshPatch
import com.example.scanner.LiveMeshAccumulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CaptureDensity(val label: String, val sampleStep: Int, val defaultVoxelCm: Int) {
    FAST("Fast (10 cm)", 10, 10),
    BALANCED("Balanced (6 cm)", 14, 6),
    HIGH("High Detail (4 cm)", 18, 4)
}

data class ScannerUiState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val isProcessing: Boolean = false,
    val pointCount: Int = 0,
    val triangleCount: Int = 0,
    val cornerAnchors: List<Vec3> = emptyList(),
    val previewPoints: List<SpatialPoint> = emptyList(),
    val eyeHeightMeters: Float = 1.50f,
    val ceilingHeightMeters: Float = 2.60f,
    val captureDensity: CaptureDensity = CaptureDensity.BALANCED,
    val coveragePercent: Int = 0,
    val elapsedSeconds: Int = 0,
    val statusMessage: String? = null
) {
    val canFinish: Boolean get() = !isProcessing && (triangleCount >= 6 || pointCount >= 18 || cornerAnchors.size >= 3)
}

class ScannerViewModel(
    private val repository: ScanRepository
) : ViewModel() {

    val liveMeshAccumulator = LiveMeshAccumulator(weldVoxelMeters = 0.05f)

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val s = _uiState.value
                if (s.isScanning && !s.isPaused && !s.isProcessing) {
                    _uiState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
                }
            }
        }
    }

    fun startOrToggleScan() {
        _uiState.update { cur ->
            when {
                !cur.isScanning -> cur.copy(isScanning = true, isPaused = false, statusMessage = null)
                cur.isPaused -> cur.copy(isPaused = false, statusMessage = null)
                else -> cur.copy(isPaused = true, statusMessage = "Paused — tap Scan to keep painting or Mesh to finish")
            }
        }
    }

    fun ingestMeshPatch(patch: CapturedMeshPatch) {
        if (patch.vertices.isEmpty()) return
        val stats = liveMeshAccumulator.ingestPatch(patch, cornerCount = _uiState.value.cornerAnchors.size)
        _uiState.update {
            it.copy(
                pointCount = stats.vertexCount,
                triangleCount = stats.triangleCount,
                previewPoints = stats.previewPoints,
                coveragePercent = stats.coveragePercent
            )
        }
    }

    fun anchorCorner(worldPoint: Vec3) {
        liveMeshAccumulator.markCornerSector(worldPoint)
        _uiState.update { cur ->
            val updated = cur.cornerAnchors + worldPoint
            val stats = liveMeshAccumulator.currentStats(cornerCount = updated.size)
            cur.copy(
                cornerAnchors = updated,
                coveragePercent = stats.coveragePercent,
                statusMessage = "Corner #${updated.size} anchored (${String.format(Locale.US, "%.2fm", worldPoint.length())})"
            )
        }
    }

    fun undoLastCapture() {
        val cur = _uiState.value
        if (cur.cornerAnchors.isNotEmpty() && (!liveMeshAccumulator.hasUndoHistory() || !cur.isScanning)) {
            val updated = cur.cornerAnchors.dropLast(1)
            val stats = liveMeshAccumulator.currentStats(cornerCount = updated.size)
            _uiState.update {
                it.copy(
                    cornerAnchors = updated,
                    coveragePercent = stats.coveragePercent,
                    statusMessage = "Removed last corner anchor"
                )
            }
            return
        }
        val stats = liveMeshAccumulator.undoLastPatch(cornerCount = cur.cornerAnchors.size)
        _uiState.update {
            it.copy(
                pointCount = stats.vertexCount,
                triangleCount = stats.triangleCount,
                previewPoints = stats.previewPoints,
                coveragePercent = stats.coveragePercent,
                statusMessage = "Undid last scanned patch"
            )
        }
    }

    fun clearScan() {
        liveMeshAccumulator.clear()
        _uiState.update {
            it.copy(
                isScanning = false,
                isPaused = false,
                pointCount = 0,
                triangleCount = 0,
                cornerAnchors = emptyList(),
                previewPoints = emptyList(),
                coveragePercent = 0,
                elapsedSeconds = 0,
                statusMessage = "Scan cleared"
            )
        }
    }

    fun setEyeHeight(meters: Float) {
        _uiState.update { it.copy(eyeHeightMeters = meters.coerceIn(0.9f, 2.2f)) }
    }

    fun setCeilingHeight(meters: Float) {
        _uiState.update { it.copy(ceilingHeightMeters = meters.coerceIn(2.0f, 4.5f)) }
    }

    fun setCaptureDensity(density: CaptureDensity) {
        _uiState.update { it.copy(captureDensity = density) }
    }

    fun finishAndSaveScan(
        sensorMode: String,
        customTitle: String? = null,
        onSaved: (Long) -> Unit
    ) {
        val state = _uiState.value
        if (!state.canFinish) return

        val (pointsCopy, trianglesCopy) = liveMeshAccumulator.exportPointsAndTriangles()
        val cornersCopy = state.cornerAnchors.toList()
        val timeLabel = SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date())
        val finalTitle = customTitle?.trim()?.takeIf { it.isNotBlank() } ?: "Room Scan $timeLabel"

        _uiState.update {
            it.copy(
                isScanning = false,
                isPaused = true,
                isProcessing = true,
                statusMessage = "Building solid 3D room mesh…"
            )
        }

        viewModelScope.launch {
            val scanId = repository.saveNewScan(
                title = finalTitle,
                sensorMode = sensorMode,
                rawPoints = pointsCopy,
                capturedTriangles = trianglesCopy,
                cornerAnchors = cornersCopy,
                ceilingHeightMeters = state.ceilingHeightMeters,
                voxelSizeCm = state.captureDensity.defaultVoxelCm
            )
            _uiState.update { it.copy(isProcessing = false) }
            onSaved(scanId)
        }
    }

    companion object {
        fun factory(repository: ScanRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ScannerViewModel(repository) as T
            }
    }
}
