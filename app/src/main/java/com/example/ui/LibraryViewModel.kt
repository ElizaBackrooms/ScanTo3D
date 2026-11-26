package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.RoomScanEntity
import com.example.data.ScanRepository
import com.example.data.StudioPreferences
import com.example.data.StudioSettings
import com.example.engine.MeasurementUnit
import com.example.scanner.SensorReadiness
import com.example.ui.theme.StudioThemeSkin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ScanSortOrder(val label: String) {
    NEWEST("Newest First"),
    NAME_AZ("Name (A–Z)"),
    LARGEST_ROOM("Largest Room"),
    MOST_VERTICES("Most Vertices")
}

enum class LibraryViewMode(val label: String) {
    DETAILS("Details List"),
    ICONS("Icon Grid")
}

data class LibraryUiState(
    val scans: List<RoomScanEntity> = emptyList(),
    val searchQuery: String = "",
    val sortOrder: ScanSortOrder = ScanSortOrder.NEWEST,
    val viewMode: LibraryViewMode = LibraryViewMode.DETAILS,
    val settings: StudioSettings = StudioSettings(),
    val sensorReadiness: SensorReadiness = SensorReadiness(),
    val isLoading: Boolean = true
) {
    val filteredScans: List<RoomScanEntity>
        get() {
            val base = if (searchQuery.isBlank()) scans
            else scans.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.sensorMode.contains(searchQuery, ignoreCase = true)
            }
            return when (sortOrder) {
                ScanSortOrder.NEWEST -> base.sortedByDescending { it.createdAtMs }
                ScanSortOrder.NAME_AZ -> base.sortedBy { it.title.lowercase() }
                ScanSortOrder.LARGEST_ROOM -> base.sortedByDescending { it.widthMeters * it.depthMeters }
                ScanSortOrder.MOST_VERTICES -> base.sortedByDescending { it.vertexCount }
            }
        }
}

class LibraryViewModel(
    private val repository: ScanRepository,
    private val preferences: StudioPreferences,
    private val initialReadiness: SensorReadiness
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val sortOrder = MutableStateFlow(ScanSortOrder.NEWEST)
    private val viewMode = MutableStateFlow(LibraryViewMode.DETAILS)

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.allScans,
        searchQuery,
        sortOrder,
        viewMode,
        preferences.settingsFlow
    ) { scans, query, sort, mode, settings ->
        LibraryUiState(
            scans = scans,
            searchQuery = query,
            sortOrder = sort,
            viewMode = mode,
            settings = settings,
            sensorReadiness = initialReadiness,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(sensorReadiness = initialReadiness)
    )

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSortOrder(order: ScanSortOrder) {
        sortOrder.value = order
    }

    fun setViewMode(mode: LibraryViewMode) {
        viewMode.value = mode
    }

    fun setThemeSkin(skin: StudioThemeSkin) {
        viewModelScope.launch { preferences.setThemeSkin(skin) }
    }

    fun setMeasurementUnit(unit: MeasurementUnit) {
        viewModelScope.launch { preferences.setMeasurementUnit(unit) }
    }

    fun renameScan(id: Long, newTitle: String) {
        viewModelScope.launch { repository.renameScan(id, newTitle) }
    }

    fun deleteScan(entity: RoomScanEntity) {
        viewModelScope.launch { repository.deleteScan(entity) }
    }

    companion object {
        fun factory(
            repository: ScanRepository,
            preferences: StudioPreferences,
            readiness: SensorReadiness
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(repository, preferences, readiness) as T
        }
    }
}
