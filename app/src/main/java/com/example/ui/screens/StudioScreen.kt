package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.ExportFormat
import com.example.engine.ExportPreset
import com.example.engine.MeasurementUnit
import com.example.engine.ShadingMode
import com.example.ui.CameraViewPreset
import com.example.ui.StudioTool
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.components.DesktopWallpaperBox
import com.example.ui.components.RetroButton
import com.example.ui.components.RetroDropdownMenuBar
import com.example.ui.components.RetroMenuCategory
import com.example.ui.components.RetroMenuEntry
import com.example.ui.components.RetroStartMenuOverlay
import com.example.ui.components.RetroSunkenWell
import com.example.ui.components.RetroTaskbar
import com.example.ui.components.RetroWindowFrame
import com.example.ui.components.Viewport3DCanvas
import com.example.ui.components.win98RaisedBevel
import com.example.ui.theme.StudioThemeSkin
import com.example.ui.theme.VistaAeroCyan
import com.example.ui.theme.Win98Face
import com.example.ui.theme.Win98FaceLight
import com.example.ui.theme.Win98TitleActiveStart

@Composable
fun StudioScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    currentSkin: StudioThemeSkin,
    onSelectSkin: (StudioThemeSkin) -> Unit,
    onNewScan: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val isWin98 = currentSkin == StudioThemeSkin.WINDOWS_98
    var showRenameDialog by remember { mutableStateOf(false) }
    var showStartMenu by remember { mutableStateOf(false) }
    var expandedViewport by remember { mutableStateOf(false) }

    val toggleSkin: () -> Unit = {
        onSelectSkin(if (isWin98) StudioThemeSkin.WINDOWS_VISTA else StudioThemeSkin.WINDOWS_98)
    }

    val safExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            viewModel.saveExportToSafUri(uri, uiState.selectedExportFormat)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    val shareFormat: (ExportFormat) -> Unit = { fmt ->
        viewModel.selectExportFormat(fmt)
        viewModel.prepareShareExport(fmt) { uri, mimeType, displayName ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export $displayName to Bryce 3D / Blender"))
        }
    }
    val saveFilesFormat: (ExportFormat) -> Unit = { fmt ->
        viewModel.selectExportFormat(fmt)
        safExportLauncher.launch(viewModel.suggestedExportFileName(fmt))
    }

    val menus = remember(uiState.shadingMode, uiState.activeTool, uiState.smoothSurface, uiState.measurementUnit, uiState.exportOptions.preset, expandedViewport) {
        listOf(
            RetroMenuCategory(
                title = "File",
                entries = listOf(
                    RetroMenuEntry("New Room Scan...", shortcut = "Ctrl+N", onClick = onNewScan),
                    RetroMenuEntry("Rename Scan...", onClick = { showRenameDialog = true }),
                    RetroMenuEntry("", isDivider = true),
                    RetroMenuEntry("Save Current Format to Files...", onClick = { saveFilesFormat(uiState.selectedExportFormat) }),
                    RetroMenuEntry("Share Current Format...", onClick = { shareFormat(uiState.selectedExportFormat) }),
                    RetroMenuEntry("", isDivider = true),
                    RetroMenuEntry("Close to Explorer", onClick = onBack)
                )
            ),
            RetroMenuCategory(
                title = "Edit",
                entries = listOf(
                    RetroMenuEntry("Apply Crop Box", onClick = viewModel::applyCrop),
                    RetroMenuEntry("Reset Crop Box", onClick = viewModel::resetCropBounds),
                    RetroMenuEntry("", isDivider = true),
                    RetroMenuEntry(
                        label = "Surface Smoothing",
                        checked = uiState.smoothSurface,
                        onClick = { viewModel.updateDensity(uiState.voxelSizeCm, !uiState.smoothSurface) }
                    ),
                    RetroMenuEntry(
                        label = "Clear All Measurements",
                        enabled = uiState.measurements.isNotEmpty(),
                        onClick = viewModel::clearMeasurements
                    )
                )
            ),
            RetroMenuCategory(
                title = "View",
                entries = listOf(
                    *ShadingMode.entries.map { mode ->
                        RetroMenuEntry(mode.label, checked = uiState.shadingMode == mode, onClick = { viewModel.setShadingMode(mode) })
                    }.toTypedArray(),
                    RetroMenuEntry("", isDivider = true),
                    *CameraViewPreset.entries.map { cam ->
                        RetroMenuEntry("Camera: ${cam.label}", onClick = { viewModel.snapCamera(cam) })
                    }.toTypedArray(),
                    RetroMenuEntry("", isDivider = true),
                    RetroMenuEntry("Expanded Viewport", checked = expandedViewport, onClick = { expandedViewport = !expandedViewport }),
                    *MeasurementUnit.entries.map { u ->
                        RetroMenuEntry("Units: ${u.label}", checked = uiState.measurementUnit == u, onClick = { viewModel.setMeasurementUnit(u) })
                    }.toTypedArray()
                )
            ),
            RetroMenuCategory(
                title = "Export",
                entries = listOf(
                    *ExportPreset.entries.map { preset ->
                        RetroMenuEntry(
                            label = "Preset: ${preset.label}",
                            checked = uiState.exportOptions.preset == preset,
                            onClick = {
                                viewModel.selectExportPreset(preset)
                                viewModel.setActiveTool(StudioTool.EXPORT)
                            }
                        )
                    }.toTypedArray(),
                    RetroMenuEntry("", isDivider = true),
                    *ExportFormat.entries.map { fmt ->
                        RetroMenuEntry(
                            label = "Quick Share .${fmt.extension.uppercase()} (${fmt.title})",
                            onClick = { shareFormat(fmt) }
                        )
                    }.toTypedArray()
                )
            )
        )
    }

    DesktopWallpaperBox {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                val scanTitle = uiState.entity?.title ?: "RoomMesh"

                RetroWindowFrame(
                    title = "LidarForge 3D Studio - [$scanTitle]",
                    windowIcon = Icons.Filled.ViewInAr,
                    onMinimizeClick = onBack,
                    onMaximizeClick = { expandedViewport = !expandedViewport },
                    onCloseClick = onBack,
                    menuBar = { RetroDropdownMenuBar(menus = menus) },
                    statusBarText = if (uiState.activeTool == StudioTool.MEASURE) {
                        "Measure Mode: Tap mesh to place 3D pin • Drag to orbit"
                    } else {
                        "Drag: Orbit • Pinch: Zoom • Double-tap: Reset Camera"
                    },
                    statusBarRight = "${uiState.activeMesh.vertexCount}v / ${uiState.activeMesh.triangleCount}t",
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    "Loading 3D geometry…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isWin98) Color.Black else Color.White
                                )
                            }
                        }
                    } else {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp)
                        ) {
                            val isWide = maxWidth >= 700.dp

                            if (isWide) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    StudioViewportWell(
                                        uiState = uiState,
                                        viewModel = viewModel,
                                        modifier = Modifier
                                            .weight(if (expandedViewport) 0.72f else 0.56f)
                                            .fillMaxHeight()
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(if (expandedViewport) 0.28f else 0.44f)
                                            .fillMaxHeight()
                                    ) {
                                        RetroPropertyTabBar(
                                            activeTool = uiState.activeTool,
                                            isWin98 = isWin98,
                                            onSelectTool = viewModel::setActiveTool
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .then(
                                                    if (isWin98) Modifier.background(Win98Face).win98RaisedBevel()
                                                    else Modifier
                                                        .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                                                        .background(Color(0xAA102233))
                                                        .border(1.dp, Color(0x555EBCE0), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                                                )
                                        ) {
                                            ActiveToolPanelHost(
                                                uiState = uiState,
                                                viewModel = viewModel,
                                                onShare = { shareFormat(uiState.selectedExportFormat) },
                                                onSaveFiles = { saveFilesFormat(uiState.selectedExportFormat) }
                                            )
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    StudioViewportWell(
                                        uiState = uiState,
                                        viewModel = viewModel,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(if (expandedViewport) 0.68f else 0.48f)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(if (expandedViewport) 0.32f else 0.52f)
                                    ) {
                                        RetroPropertyTabBar(
                                            activeTool = uiState.activeTool,
                                            isWin98 = isWin98,
                                            onSelectTool = viewModel::setActiveTool
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                                .then(
                                                    if (isWin98) Modifier.background(Win98Face).win98RaisedBevel()
                                                    else Modifier
                                                        .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                                                        .background(Color(0xAA102233))
                                                        .border(1.dp, Color(0x555EBCE0), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                                                )
                                        ) {
                                            ActiveToolPanelHost(
                                                uiState = uiState,
                                                viewModel = viewModel,
                                                onShare = { shareFormat(uiState.selectedExportFormat) },
                                                onSaveFiles = { saveFilesFormat(uiState.selectedExportFormat) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                )
            }

            RetroTaskbar(
                activeWindowTag = "studio",
                activeWindowTitle = uiState.entity?.title ?: "3D Studio",
                currentSkin = currentSkin,
                onStartClick = { showStartMenu = !showStartMenu },
                onLibraryClick = onBack,
                onScanClick = onNewScan,
                onToggleSkin = toggleSkin
            )
        }

        if (showStartMenu) {
            RetroStartMenuOverlay(
                currentSkin = currentSkin,
                currentUnit = uiState.measurementUnit,
                onDismiss = { showStartMenu = false },
                onNewScan = onNewScan,
                onGoLibrary = {
                    showStartMenu = false
                    onBack()
                },
                onSelectUnit = viewModel::setMeasurementUnit
            )
        }
    }

    if (showRenameDialog) {
        val currentName = uiState.entity?.title ?: ""
        var draft by remember(currentName) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Rename Room Scan") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Scan title") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameCurrentScan(draft)
                    showRenameDialog = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun StudioViewportWell(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val meshForDisplay = if (uiState.activeTool == StudioTool.CROP) uiState.uncroppedMesh else uiState.activeMesh

    RetroSunkenWell(darkWell = true, modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Viewport3DCanvas(
                mesh = meshForDisplay,
                fullBounds = uiState.uncroppedMesh.bounds,
                camera = uiState.camera,
                shadingMode = uiState.shadingMode,
                cropBounds = uiState.cropBounds,
                showCropCage = uiState.activeTool == StudioTool.CROP,
                measurements = uiState.measurements,
                pendingMeasureStart = uiState.pendingMeasureStart,
                measurementUnit = uiState.measurementUnit,
                isMeasureMode = uiState.activeTool == StudioTool.MEASURE,
                onOrbit = viewModel::orbitBy,
                onPanAndZoom = viewModel::panAndZoom,
                onDoubleTapReset = viewModel::resetCamera,
                onPickMeasurePoint = viewModel::onMeasurementPointPicked
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                ShadingMode.entries.forEach { mode ->
                    RetroButton(
                        onClick = { viewModel.setShadingMode(mode) },
                        accent = uiState.shadingMode == mode,
                        active = uiState.shadingMode == mode,
                        minHeight = 32.dp
                    ) {
                        Text(mode.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RetroPropertyTabBar(
    activeTool: StudioTool,
    isWin98: Boolean,
    onSelectTool: (StudioTool) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isWin98) 2.dp else 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        StudioTool.entries.forEach { tool ->
            val selected = tool == activeTool
            val icon = when (tool) {
                StudioTool.VIEW -> Icons.Filled.ViewInAr
                StudioTool.CROP -> Icons.Filled.Crop
                StudioTool.DENSITY -> Icons.Filled.Grain
                StudioTool.MEASURE -> Icons.Filled.Straighten
                StudioTool.EXPORT -> Icons.Filled.IosShare
            }

            if (isWin98) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = if (selected) 46.dp else 42.dp)
                        .background(if (selected) Win98Face else Win98FaceLight)
                        .win98RaisedBevel(pressed = false)
                        .clickable { onSelectTool(tool) }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                        .testTag("studio_tab_${tool.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = icon,
                            contentDescription = tool.label,
                            tint = if (selected) Win98TitleActiveStart else Color(0xFF333333),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = tool.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Win98TitleActiveStart else Color.Black,
                            maxLines = 1
                        )
                    }
                }
            } else {
                val shape = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .clip(shape)
                        .background(
                            if (selected) Brush.verticalGradient(listOf(Color(0xAA56D2FF), Color(0xCC17527A)))
                            else Brush.verticalGradient(listOf(Color(0x55294D6B), Color(0x88112436)))
                        )
                        .border(1.dp, if (selected) VistaAeroCyan else Color(0x446EC8EC), shape)
                        .clickable { onSelectTool(tool) }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                        .testTag("studio_tab_${tool.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = icon,
                            contentDescription = tool.label,
                            tint = if (selected) Color.White else Color(0xFFA9CBE0),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = tool.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) Color.White else Color(0xFFA9CBE0),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveToolPanelHost(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onShare: () -> Unit,
    onSaveFiles: () -> Unit
) {
    when (uiState.activeTool) {
        StudioTool.VIEW -> ViewToolPanel(
            uiState = uiState,
            onSnapCamera = viewModel::snapCamera,
            onSelectShading = viewModel::setShadingMode,
            onSelectUnit = viewModel::setMeasurementUnit
        )
        StudioTool.CROP -> CropToolPanel(
            uiState = uiState,
            onCropChange = viewModel::updateCropBounds,
            onResetCrop = viewModel::resetCropBounds,
            onApplyCrop = viewModel::applyCrop
        )
        StudioTool.DENSITY -> DensityToolPanel(
            uiState = uiState,
            onUpdateDensity = viewModel::updateDensity
        )
        StudioTool.MEASURE -> MeasureToolPanel(
            uiState = uiState,
            onSelectUnit = viewModel::setMeasurementUnit,
            onDeleteMeasurement = viewModel::deleteMeasurement,
            onClearMeasurements = viewModel::clearMeasurements,
            onCancelPending = viewModel::cancelPendingMeasurement
        )
        StudioTool.EXPORT -> ExportToolPanel(
            uiState = uiState,
            onSelectPreset = viewModel::selectExportPreset,
            onSelectFormat = viewModel::selectExportFormat,
            onSelectUpAxis = viewModel::setExportUpAxis,
            onSelectScale = viewModel::setExportScale,
            onSelectHeightmapRes = viewModel::setHeightmapResolution,
            onToggleCenter = viewModel::toggleCenterOrigin,
            onToggleVertexColors = viewModel::toggleVertexColors,
            onShareSelected = onShare,
            onSaveToFilesSelected = onSaveFiles
        )
    }
}
