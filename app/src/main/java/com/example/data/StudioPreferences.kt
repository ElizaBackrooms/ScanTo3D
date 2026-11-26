package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.ExportPreset
import com.example.engine.MeasurementUnit
import com.example.ui.theme.StudioThemeSkin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.studioDataStore: DataStore<Preferences> by preferencesDataStore(name = "lidarforge_prefs")

data class StudioSettings(
    val themeSkin: StudioThemeSkin = StudioThemeSkin.WINDOWS_98,
    val measurementUnit: MeasurementUnit = MeasurementUnit.METERS,
    val defaultExportPreset: ExportPreset = ExportPreset.BRYCE_3D
)

class StudioPreferences(private val context: Context) {
    private val keyThemeSkin = stringPreferencesKey("theme_skin")
    private val keyUnit = stringPreferencesKey("measurement_unit")
    private val keyPreset = stringPreferencesKey("export_preset")

    val settingsFlow: Flow<StudioSettings> = context.studioDataStore.data.map { prefs ->
        StudioSettings(
            themeSkin = StudioThemeSkin.fromId(prefs[keyThemeSkin]),
            measurementUnit = MeasurementUnit.fromId(prefs[keyUnit]),
            defaultExportPreset = ExportPreset.fromId(prefs[keyPreset])
        )
    }

    suspend fun setThemeSkin(skin: StudioThemeSkin) {
        context.studioDataStore.edit { it[keyThemeSkin] = skin.id }
    }

    suspend fun setMeasurementUnit(unit: MeasurementUnit) {
        context.studioDataStore.edit { it[keyUnit] = unit.id }
    }

    suspend fun setDefaultExportPreset(preset: ExportPreset) {
        context.studioDataStore.edit { it[keyPreset] = preset.id }
    }
}
