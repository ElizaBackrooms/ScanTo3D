package com.example.ui

import android.graphics.Color as AndroidColor
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
import kotlin.math.floor

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
    private val voxelIndexMap = HashMap<Long, Int>(16_384) // 4cm spatial dedup map -> index in rawPoints
    private val voxelHitCounts = ArrayList<Int>(16_384)
    private val batchKeys = ArrayDeque<List<Long>>()
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

    /**
     * Ingests captured 3D spatial points with real-time 4 cm spatial voxel deduplication & averaging.
     * Revisiting a wall refines its 3D position and color instead of wasting buffer on duplicate points.
     */
    fun ingestCapturedPoints(batch: List<SpatialPoint>) {
        if (batch.isEmpty()) return
        val maxUniquePoints = 45_000
        val dedupInvVoxel = 25f // 1 / 0.04m (4cm live fusion grid)
        val previewSnapshot: List<SpatialPoint>
        val totalCount: Int
        val coverage: Int

        synchronized(rawPointsLock) {
            val newlyAddedKeys = ArrayList<Long>(batch.size)

            for (p in batch) {
                val gx = floor(p.x * dedupInvVoxel).toInt()
                val gy = floor(p.y * dedupInvVoxel).toInt()
                val gz = floor(p.z * dedupInvVoxel).toInt()
                val key = packSpatialKey(gx, gy, gz)

                val existingIdx = voxelIndexMap[key]
                if (existingIdx != null && existingIdx < rawPoints.size) {
                    // Running weighted average refines noisy depth hits into cleaner flat walls
                    val prev = rawPoints[existingIdx]
                    val hits = (voxelHitCounts[existingIdx] + 1).coerceAtMost(12)
                    voxelHitCounts[existingIdx] = hits
                    val alpha = 1f / hits
                    val invA = 1f - alpha
                    val nx = prev.x * invA + p.x * alpha
                    val ny = prev.y * invA + p.y * alpha
                    val nz = prev.z * invA + p.z * alpha
                    val nr = (AndroidColor.red(prev.argb) * invA + AndroidColor.red(p.argb) * alpha).toInt().coerceIn(0, 255)
                    val ng = (AndroidColor.green(prev.argb) * invA + AndroidColor.green(p.argb) * alpha).toInt().coerceIn(0, 255)
                    val nb = (AndroidColor.blue(prev.argb) * invA + AndroidColor.blue(p.argb) * alpha).toInt().coerceIn(0, 255)
                    rawPoints[existingIdx] = SpatialPoint(nx, ny, nz, AndroidColor.rgb(nr, ng, nb), p.surfaceType)
                } else if (rawPoints.size < maxUniquePoints) {
                    val idx = rawPoints.size
                    rawPoints.add(p)
                    voxelHitCounts.add(1)
                    voxelIndexMap[key] = idx
                    newlyAddedKeys.add(key)

                    val angle = atan2(p.z.toDouble(), p.x.toDouble()).toFloat()
                    val sector = (((angle + PI.toFloat()) / (2f * PI.toFloat())) * coveredSectors.size)
                        .toInt().coerceIn(0, coveredSectors.lastIndex)
                    coveredSectors[sector] = true
                }
            }

            if (newlyAddedKeys.isNotEmpty()) {
                batchKeys.addLast(newlyAddedKeys)
                if (batchKeys.size > 60) batchKeys.removeFirst()
            }

            totalCount = rawPoints.size
            val stride = (totalCount / 300).coerceAtLeast(1)
            previewSnapshot = ArrayList<SpatialPoint>(300).apply {
                var i = 0
                while (i < totalCount) {
                    add(rawPoints[i])
                    i += stride
                }
            }
            val sectorRatio = coveredSectors.count { it }.toFloat() / coveredSectors.size
            val cornerBoost = (_uiState.value.cornerAnchors.size * 0.10f).coerceAtMost(0.40f)
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
                statusMessage = "Corner #${updated.size} anchored (${String.format(Locale.US, "%.2fm", worldPoint.length())})"
            )
        }
    }

    fun undoLastCapture() {
        val cur = _uiState.value
        if (cur.cornerAnchors.isNotEmpty() && (batchKeys.isEmpty() || !cur.isScanning)) {
            _uiState.update {
                it.copy(
                    cornerAnchors = it.cornerAnchors.dropLast(1),
                    statusMessage = "Removed last corner anchor"
                )
            }
            return
        }
        synchronized(rawPointsLock) {
            if (batchKeys.isEmpty()) return
            val keysToUndo = batchKeys.removeLast()
            val removeCount = keysToUndo.size.coerceAtMost(rawPoints.size)
            for (k in keysToUndo) {
                voxelIndexMap.remove(k)
            }
            repeat(removeCount) {
                if (rawPoints.isNotEmpty()) rawPoints.removeAt(rawPoints.lastIndex)
                if (voxelHitCounts.isNotEmpty()) voxelHitCounts.removeAt(voxelHitCounts.lastIndex)
            }
            val totalCount = rawPoints.size
            val stride = (totalCount / 300).coerceAtLeast(1)
            val previewSnapshot = ArrayList<SpatialPoint>(300).apply {
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

    fun clearScan() {
        synchronized(rawPointsLock) {
            rawPoints.clear()
            voxelIndexMap.clear()
            voxelHitCounts.clear()
            batchKeys.clear()
            coveredSectors.fill(false)
        }
        _uiState.update {
            it.copy(
                isScanning = false,
                isPaused = false,
                pointCount = 0,
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

    private fun packSpatialKey(x: Int, y: Int, z: Int): Long {
        val lx = (x + 1048576).toLong() and 0x1FFFFFL
        val ly = (y + 1048576).toLong() and 0x1FFFFFL
        val lz = (z + 1048576).toLong() and 0x1FFFFFL
        return (lx shl 42) or (ly shl 21) or lz
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
