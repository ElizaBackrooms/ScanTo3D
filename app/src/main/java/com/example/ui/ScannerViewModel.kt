package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ScanRepository
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
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
import kotlin.math.PI
import kotlin.math.atan2

enum class CaptureDensity(val label: String, val sampleStep: Int, val defaultVoxelCm: Int) {
    FAST("Fast", 10, 14),
    BALANCED("Balanced", 14, 10),
    HIGH("High Detail", 18, 6)
}

data class ScannerUiState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val isProcessing: Boolean = false,
    val pointCount: Int = 0,
    val cornerAnchors: List<Vec3> = emptyList(),
    val previewPoints: List<SpatialPoint> = emptyList(),
    val eyeHeightMeters: Float = 1.50f,
    val ceilingHeightMeters: Float = 2.60f,
    val captureDensity: CaptureDensity = CaptureDensity.BALANCED,
    val coveragePercent: Int = 0,
    val elapsedSeconds: Int = 0,
    val statusMessage: String? = null
) {
    val canFinish: Boolean get() = !isProcessing && (pointCount >= 18 || cornerAnchors.size >= 3)
}

class ScannerViewModel(
    private val repository: ScanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val rawPointsLock = Any()
    private val rawPoints = ArrayList<SpatialPoint>(16_384)
    private val batchSizes = ArrayDeque<Int>()
    private val coveredSectors = BooleanArray(24) // 15-degree azimuthal sectors

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
                cur.isPaused -> cur.copy(isPaused = false)
                else -> cur.copy(isPaused = true)
            }
        }
    }

    fun ingestCapturedPoints(batch: List<SpatialPoint>) {
        if (batch.isEmpty()) return
        val maxTotal = 24_000
        val previewSnapshot: List<SpatialPoint>
        val totalCount: Int
        val coverage: Int

        synchronized(rawPointsLock) {
            val remaining = maxTotal - rawPoints.size
            if (remaining <= 0) return
            val toAdd = if (batch.size <= remaining) batch else batch.subList(0, remaining)
            rawPoints.addAll(toAdd)
            batchSizes.addLast(toAdd.size)
            if (batchSizes.size > 60) batchSizes.removeFirst()

            for (p in toAdd) {
                val angle = atan2(p.z.toDouble(), p.x.toDouble()).toFloat()
                val sector = (((angle + PI.toFloat()) / (2f * PI.toFloat())) * coveredSectors.size)
                    .toInt().coerceIn(0, coveredSectors.lastIndex)
                coveredSectors[sector] = true
            }

            totalCount = rawPoints.size
            val stride = (totalCount / 220).coerceAtLeast(1)
            previewSnapshot = ArrayList<SpatialPoint>(220).apply {
                var i = 0
                while (i < totalCount) {
                    add(rawPoints[i])
                    i += stride
                }
            }
            val sectorRatio = coveredSectors.count { it }.toFloat() / coveredSectors.size
            val cornerBoost = (_uiState.value.cornerAnchors.size * 0.12f).coerceAtMost(0.48f)
            coverage = ((sectorRatio * 0.85f + cornerBoost) * 100f).toInt().coerceIn(0, 100)
        }

        _uiState.update {
            it.copy(
                pointCount = totalCount,
                previewPoints = previewSnapshot,
                coveragePercent = coverage
            )
        }
    }

    fun anchorCorner(worldPoint: Vec3) {
        val angle = atan2(worldPoint.z.toDouble(), worldPoint.x.toDouble()).toFloat()
        val sector = (((angle + PI.toFloat()) / (2f * PI.toFloat())) * coveredSectors.size)
            .toInt().coerceIn(0, coveredSectors.lastIndex)
        synchronized(rawPointsLock) {
            coveredSectors[sector] = true
        }
        _uiState.update { cur ->
            val updated = cur.cornerAnchors + worldPoint
            val sectorRatio = synchronized(rawPointsLock) {
                coveredSectors.count { it }.toFloat() / coveredSectors.size
            }
            val cov = ((sectorRatio * 0.65f + (updated.size / 4f).coerceAtMost(1f) * 0.45f) * 100f)
                .toInt().coerceIn(0, 100)
            cur.copy(
                cornerAnchors = updated,
                coveragePercent = cov,
                statusMessage = "Corner #${updated.size} anchored (${String.format(Locale.US, "%.1fm", worldPoint.length())})"
            )
        }
    }

    fun undoLastCapture() {
        val cur = _uiState.value
        if (cur.cornerAnchors.isNotEmpty() && (batchSizes.isEmpty() || !cur.isScanning)) {
            _uiState.update {
                it.copy(
                    cornerAnchors = it.cornerAnchors.dropLast(1),
                    statusMessage = "Removed last corner anchor"
                )
            }
            return
        }
        synchronized(rawPointsLock) {
            if (batchSizes.isEmpty()) return
            val removeCount = batchSizes.removeLast().coerceAtMost(rawPoints.size)
            repeat(removeCount) {
                if (rawPoints.isNotEmpty()) rawPoints.removeAt(rawPoints.lastIndex)
            }
            val totalCount = rawPoints.size
            val stride = (totalCount / 220).coerceAtLeast(1)
            val previewSnapshot = ArrayList<SpatialPoint>(220).apply {
                var i = 0
                while (i < totalCount) {
                    add(rawPoints[i])
                    i += stride
                }
            }
            _uiState.update {
                it.copy(
                    pointCount = totalCount,
                    previewPoints = previewSnapshot,
                    statusMessage = "Undid last sweep segment"
                )
            }
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

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun finishAndSaveScan(
        sensorMode: String,
        customTitle: String? = null,
        onSaved: (Long) -> Unit
    ) {
        val state = _uiState.value
        if (!state.canFinish) return

        val pointsCopy: List<SpatialPoint> = synchronized(rawPointsLock) {
            rawPoints.toList()
        }
        val cornersCopy = state.cornerAnchors.toList()
        val timeLabel = SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date())
        val finalTitle = customTitle?.trim()?.takeIf { it.isNotBlank() } ?: "Room Scan $timeLabel"

        _uiState.update { it.copy(isScanning = false, isPaused = true, isProcessing = true, statusMessage = "Reconstructing 3D room mesh…") }

        viewModelScope.launch {
            val scanId = repository.saveNewScan(
                title = finalTitle,
                sensorMode = sensorMode,
                rawPoints = pointsCopy,
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
