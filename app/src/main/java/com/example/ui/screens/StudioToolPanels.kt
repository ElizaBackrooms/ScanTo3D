package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.CropBounds
import com.example.engine.ExportEngine
import com.example.engine.ExportFormat
import com.example.engine.ExportPreset
import com.example.engine.MeasurementUnit
import com.example.engine.ShadingMode
import com.example.engine.UpAxis
import com.example.ui.CameraViewPreset
import com.example.ui.StudioUiState
import com.example.ui.components.RetroButton
import com.example.ui.components.RetroComboBox
import com.example.ui.components.RetroSunkenWell
import com.example.ui.theme.LocalStudioSkinExtras
import com.example.ui.theme.StudioThemeSkin
import com.example.ui.theme.TelemetryLargeStyle
import com.example.ui.theme.TelemetryTextStyle
import com.example.ui.theme.VistaAeroCyan
import com.example.ui.theme.Win98TitleActiveStart
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class ScaleChoice(val label: String, val factor: Float)

private val ExportScaleChoices = listOf(
    ScaleChoice("1.0× Meters (Blender)", 1.0f),
    ScaleChoice("4.096× Bryce Units", 4.096f),
    ScaleChoice("100× Centimeters", 100f),
    ScaleChoice("39.37× Inches", 39.3701f)
)

private val VoxelPresetChoices = listOf(
    "Ultra Detail (4 cm)" to 4,
    "Fine Detail (6 cm)" to 6,
    "High Quality (8 cm)" to 8,
    "Balanced Room (10 cm)" to 10,
    "Medium Mesh (14 cm)" to 14,
    "Light Proxy (20 cm)" to 20,
    "Draft Blockout (24 cm)" to 24
)

@Composable
private fun panelTextColor(): Color =
    if (LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98) Color.Black else Color.White

@Composable
private fun panelMutedColor(): Color =
    if (LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98) Color(0xFF3A3A3A) else Color(0xFFA8C6DC)

@Composable
private fun panelAccentColor(): Color =
    if (LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98) Win98TitleActiveStart else VistaAeroCyan

@Composable
fun ViewToolPanel(
    uiState: StudioUiState,
    onSnapCamera: (CameraViewPreset) -> Unit,
    onSelectShading: (ShadingMode) -> Unit,
    onSelectUnit: (MeasurementUnit) -> Unit
) {
    val unit = uiState.measurementUnit
    val b = uiState.activeMesh.bounds

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroComboBox(
                label = "Shading Mode",
                selectedText = uiState.shadingMode.label,
                options = ShadingMode.entries,
                optionLabel = { it.label },
                isSelected = { it == uiState.shadingMode },
                onSelect = onSelectShading,
                testTagName = "view_shading_dropdown",
                modifier = Modifier.weight(1f)
            )
            RetroComboBox(
                label = "Display Units",
                selectedText = unit.label,
                options = MeasurementUnit.entries,
                optionLabel = { "${it.label} (${it.shortSuffix})" },
                isSelected = { it == unit },
                onSelect = onSelectUnit,
                testTagName = "view_units_dropdown",
                modifier = Modifier.weight(1f)
            )
        }

        Text("Camera Viewpoint Snap", style = MaterialTheme.typography.labelMedium, color = panelTextColor())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            CameraViewPreset.entries.forEach { preset ->
                RetroButton(
                    onClick = { onSnapCamera(preset) },
                    minHeight = 40.dp,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("camera_snap_${preset.label.lowercase()}")
                ) {
                    Text(preset.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        RetroSunkenWell(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricStat("WIDTH (X)", unit.format(b.width))
                    MetricStat("DEPTH (Z)", unit.format(b.depth))
                    MetricStat("HEIGHT (Y)", unit.format(b.height))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricStat("VERTICES", "${uiState.activeMesh.vertexCount}")
                    MetricStat("TRIANGLES", "${uiState.activeMesh.triangleCount}")
                    MetricStat("VOXEL", "${uiState.voxelSizeCm} cm")
                }
            }
        }
    }
}

@Composable
private fun MetricStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = panelMutedColor())
        Text(value, style = TelemetryTextStyle, fontWeight = FontWeight.Bold, color = panelTextColor())
    }
}

@Composable
fun CropToolPanel(
    uiState: StudioUiState,
    onCropChange: (CropBounds) -> Unit,
    onResetCrop: () -> Unit,
    onApplyCrop: () -> Unit
) {
    val crop = uiState.cropBounds
    val full = uiState.uncroppedMesh.bounds
    val worldCrop = crop.toWorldBox(full)
    val unit = uiState.measurementUnit

    val cropPresets = listOf(
        "Full Room (100%)" to CropBounds(),
        "Trim Ceiling (Top 15%)" to CropBounds(0f, 1f, 0f, 0.85f, 0f, 1f),
        "Floor Level Only (Bottom 35%)" to CropBounds(0f, 1f, 0f, 0.35f, 0f, 1f),
        "Center Core (80% Box)" to CropBounds(0.1f, 0.9f, 0f, 1f, 0.1f, 0.9f)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RetroComboBox(
            label = "Quick Crop Preset",
            selectedText = if (crop.isFull) "Full Room (100%)"
            else "${unit.format(worldCrop.width)} × ${unit.format(worldCrop.depth)} × ${unit.format(worldCrop.height)}",
            options = cropPresets,
            optionLabel = { it.first },
            isSelected = { it.second == crop },
            onSelect = { onCropChange(it.second) },
            testTagName = "crop_preset_dropdown"
        )

        CropAxisSlider(
            label = "X Axis (Width)",
            range = crop.minX..crop.maxX,
            spanText = unit.format(worldCrop.width),
            onChange = { r ->
                onCropChange(crop.copy(minX = r.start, maxX = r.endInclusive.coerceAtLeast(r.start + 0.05f)))
            }
        )
        CropAxisSlider(
            label = "Y Axis (Height / Ceiling)",
            range = crop.minY..crop.maxY,
            spanText = unit.format(worldCrop.height),
            onChange = { r ->
                onCropChange(crop.copy(minY = r.start, maxY = r.endInclusive.coerceAtLeast(r.start + 0.05f)))
            }
        )
        CropAxisSlider(
            label = "Z Axis (Depth)",
            range = crop.minZ..crop.maxZ,
            spanText = unit.format(worldCrop.depth),
            onChange = { r ->
                onCropChange(crop.copy(minZ = r.start, maxZ = r.endInclusive.coerceAtLeast(r.start + 0.05f)))
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroButton(
                onClick = onResetCrop,
                minHeight = 46.dp,
                modifier = Modifier.weight(1f).testTag("crop_reset_button")
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.action_reset_crop), style = MaterialTheme.typography.labelLarge)
            }
            RetroButton(
                onClick = onApplyCrop,
                accent = true,
                minHeight = 46.dp,
                modifier = Modifier.weight(1.3f).testTag("crop_apply_button")
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.action_apply_crop), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CropAxisSlider(
    label: String,
    range: ClosedFloatingPointRange<Float>,
    spanText: String,
    onChange: (ClosedFloatingPointRange<Float>) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = panelMutedColor())
            Text(spanText, style = TelemetryTextStyle, color = panelTextColor())
        }
        RangeSlider(value = range, onValueChange = onChange, valueRange = 0f..1f)
    }
}

@Composable
fun DensityToolPanel(
    uiState: StudioUiState,
    onUpdateDensity: (voxelCm: Int, smooth: Boolean) -> Unit
) {
    val estObj = ExportEngine.estimateSizeBytes(uiState.activeMesh, ExportFormat.OBJ, uiState.exportOptions)
    val estGlb = ExportEngine.estimateSizeBytes(uiState.activeMesh, ExportFormat.GLB, uiState.exportOptions)
    val currentPresetLabel = VoxelPresetChoices.firstOrNull { it.second == uiState.voxelSizeCm }?.first
        ?: "Custom (${uiState.voxelSizeCm} cm)"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroComboBox(
                label = "Voxel Grid Resolution",
                selectedText = currentPresetLabel,
                options = VoxelPresetChoices,
                optionLabel = { it.first },
                isSelected = { it.second == uiState.voxelSizeCm },
                onSelect = { onUpdateDensity(it.second, uiState.smoothSurface) },
                testTagName = "density_preset_dropdown",
                modifier = Modifier.weight(1f)
            )
            if (uiState.isRebuildingMesh) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(22.dp), strokeWidth = 2.dp)
            }
        }

        Column {
            Slider(
                value = uiState.voxelSizeCm.toFloat(),
                onValueChange = { v -> onUpdateDensity(v.roundToInt(), uiState.smoothSurface) },
                valueRange = 4f..24f,
                steps = 9,
                modifier = Modifier.testTag("density_voxel_slider")
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("4 cm • Ultra Detail", style = MaterialTheme.typography.labelSmall, color = panelMutedColor())
                Text("24 cm • Light Proxy", style = MaterialTheme.typography.labelSmall, color = panelMutedColor())
            }
        }

        RetroSunkenWell(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdateDensity(uiState.voxelSizeCm, !uiState.smoothSurface) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = uiState.smoothSurface,
                    onCheckedChange = { onUpdateDensity(uiState.voxelSizeCm, it) },
                    modifier = Modifier.testTag("density_smooth_switch")
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Laplacian Surface Smoothing", style = MaterialTheme.typography.labelLarge, color = panelTextColor())
                    Text("Smooths raw LiDAR wall & floor noise", style = MaterialTheme.typography.bodySmall, color = panelMutedColor())
                }
            }
        }

        RetroSunkenWell(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricStat("VERTS", "${uiState.activeMesh.vertexCount}")
                MetricStat("TRIS", "${uiState.activeMesh.triangleCount}")
                MetricStat("OBJ", ExportEngine.formatByteSize(estObj))
                MetricStat("GLB", ExportEngine.formatByteSize(estGlb))
            }
        }
    }
}

@Composable
fun MeasureToolPanel(
    uiState: StudioUiState,
    onSelectUnit: (MeasurementUnit) -> Unit,
    onDeleteMeasurement: (String) -> Unit,
    onClearMeasurements: () -> Unit,
    onCancelPending: () -> Unit
) {
    val unit = uiState.measurementUnit

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RetroComboBox(
                label = "Measurement Unit",
                selectedText = "${unit.label} (${unit.shortSuffix})",
                options = MeasurementUnit.entries,
                optionLabel = { "${it.label} (${it.shortSuffix})" },
                isSelected = { it == unit },
                onSelect = onSelectUnit,
                testTagName = "measure_units_dropdown",
                modifier = Modifier.weight(1f)
            )
            if (uiState.measurements.isNotEmpty()) {
                RetroButton(
                    onClick = onClearMeasurements,
                    minHeight = 42.dp,
                    modifier = Modifier.testTag("measure_clear_all_button")
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.action_clear_measurements), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        RetroSunkenWell(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Straighten, contentDescription = null, tint = panelAccentColor())
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.pendingMeasureStart == null) "Tap Point A on the 3D mesh"
                        else "Point A locked — tap Point B",
                        style = MaterialTheme.typography.labelLarge,
                        color = panelTextColor()
                    )
                    Text(
                        text = "Single-tap snaps to nearest vertex; drag to orbit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = panelMutedColor()
                    )
                }
                if (uiState.pendingMeasureStart != null) {
                    IconButton(onClick = onCancelPending) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel point", tint = panelTextColor())
                    }
                }
            }
        }

        if (uiState.measurements.isEmpty()) {
            Text(
                text = "No measurement pins yet. Tap any two room corners in the viewport above.",
                style = MaterialTheme.typography.bodySmall,
                color = panelMutedColor()
            )
        } else {
            uiState.measurements.forEach { pin ->
                RetroSunkenWell(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(pin.label, style = TelemetryLargeStyle, color = panelAccentColor(), modifier = Modifier.width(34.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(unit.format(pin.distanceMeters), style = TelemetryLargeStyle, color = panelTextColor())
                            Text(
                                text = "Run ${unit.format(pin.horizontalMeters)} • Rise ${unit.format(pin.verticalMeters)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = panelMutedColor()
                            )
                        }
                        IconButton(onClick = { onDeleteMeasurement(pin.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${pin.label}", tint = panelTextColor(), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExportToolPanel(
    uiState: StudioUiState,
    onSelectPreset: (ExportPreset) -> Unit,
    onSelectFormat: (ExportFormat) -> Unit,
    onSelectUpAxis: (UpAxis) -> Unit,
    onSelectScale: (Float) -> Unit,
    onSelectHeightmapRes: (Int) -> Unit,
    onToggleCenter: (Boolean) -> Unit,
    onToggleVertexColors: (Boolean) -> Unit,
    onShareSelected: () -> Unit,
    onSaveToFilesSelected: () -> Unit
) {
    val opts = uiState.exportOptions
    val selectedFormat = uiState.selectedExportFormat
    val estBytes = ExportEngine.estimateSizeBytes(uiState.activeMesh, selectedFormat, opts)
    val selectedScale = ExportScaleChoices.firstOrNull { abs(it.factor - opts.scaleFactor) < 0.05f }
        ?: ScaleChoice(String.format(Locale.US, "%.2f× Custom", opts.scaleFactor), opts.scaleFactor)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Target App Preset + File Format Dropdowns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroComboBox(
                label = "Target App Preset",
                selectedText = opts.preset.label,
                options = ExportPreset.entries,
                optionLabel = { "${it.label} (${it.upAxis.label})" },
                isSelected = { it == opts.preset },
                onSelect = onSelectPreset,
                testTagName = "export_preset_dropdown",
                modifier = Modifier.weight(0.95f)
            )
            RetroComboBox(
                label = "3D Export Format",
                selectedText = ".${selectedFormat.extension.uppercase(Locale.US)} — ${selectedFormat.title}",
                options = ExportFormat.entries,
                optionLabel = { ".${it.extension.uppercase(Locale.US)} — ${it.title} (${it.targetBadge})" },
                isSelected = { it == selectedFormat },
                onSelect = onSelectFormat,
                testTagName = "export_format_dropdown",
                modifier = Modifier.weight(1.3f)
            )
        }

        // Coordinate Axis + Scale Factor Dropdowns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroComboBox(
                label = "Coordinate Up-Axis",
                selectedText = opts.upAxis.label,
                options = UpAxis.entries,
                optionLabel = { it.label },
                isSelected = { it == opts.upAxis },
                onSelect = onSelectUpAxis,
                testTagName = "export_axis_dropdown",
                modifier = Modifier.weight(1f)
            )
            RetroComboBox(
                label = "Export Scale",
                selectedText = selectedScale.label,
                options = ExportScaleChoices,
                optionLabel = { it.label },
                isSelected = { abs(it.factor - opts.scaleFactor) < 0.05f },
                onSelect = { onSelectScale(it.factor) },
                testTagName = "export_scale_dropdown",
                modifier = Modifier.weight(1f)
            )
        }

        if (selectedFormat == ExportFormat.HEIGHTMAP_PNG) {
            RetroComboBox(
                label = "Bryce Terrain Heightmap Resolution",
                selectedText = "${opts.heightmapResolution} × ${opts.heightmapResolution} px",
                options = listOf(256, 512, 1024),
                optionLabel = { "$it × $it px grayscale PNG" },
                isSelected = { it == opts.heightmapResolution },
                onSelect = onSelectHeightmapRes,
                testTagName = "export_heightmap_res_dropdown"
            )
        }

        // Format summary + checkboxes
        RetroSunkenWell(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(selectedFormat.description, style = MaterialTheme.typography.bodySmall, color = panelTextColor(), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(ExportEngine.formatByteSize(estBytes), style = TelemetryTextStyle, color = panelAccentColor())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = opts.centerOrigin, onCheckedChange = onToggleCenter)
                    Text("Center Floor at Origin (0,0,0)", style = MaterialTheme.typography.bodySmall, color = panelTextColor())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = opts.includeVertexColors, onCheckedChange = onToggleVertexColors)
                    Text("Include Vertex RGB Colors", style = MaterialTheme.typography.bodySmall, color = panelTextColor())
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RetroButton(
                onClick = onShareSelected,
                enabled = !uiState.isExporting,
                accent = true,
                minHeight = 48.dp,
                modifier = Modifier.weight(1f).testTag("export_share_button")
            ) {
                if (uiState.isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_share), style = MaterialTheme.typography.labelLarge)
            }
            RetroButton(
                onClick = onSaveToFilesSelected,
                enabled = !uiState.isExporting,
                minHeight = 48.dp,
                modifier = Modifier.weight(1f).testTag("export_save_files_button")
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_save_files), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
