package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.RoomScanEntity
import com.example.engine.MeasurementUnit
import com.example.scanner.SensorReadiness
import com.example.ui.LibraryUiState
import com.example.ui.LibraryViewMode
import com.example.ui.ScanSortOrder
import com.example.ui.components.DesktopWallpaperBox
import com.example.ui.components.MiniWireframePreview
import com.example.ui.components.RetroButton
import com.example.ui.components.RetroComboBox
import com.example.ui.components.RetroDropdownMenuBar
import com.example.ui.components.RetroMenuCategory
import com.example.ui.components.RetroMenuEntry
import com.example.ui.components.RetroStartMenuOverlay
import com.example.ui.components.RetroSunkenWell
import com.example.ui.components.RetroTaskbar
import com.example.ui.components.RetroWindowFrame
import com.example.ui.components.win98RaisedBevel
import com.example.ui.theme.LocalStudioSkinExtras
import com.example.ui.theme.StudioThemeSkin
import com.example.ui.theme.TelemetryTextStyle
import com.example.ui.theme.VistaAeroCyan
import com.example.ui.theme.Win98FaceLight
import com.example.ui.theme.Win98TitleActiveStart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onStartScan: () -> Unit,
    onOpenScan: (Long) -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectSort: (ScanSortOrder) -> Unit,
    onSelectViewMode: (LibraryViewMode) -> Unit,
    onSelectSkin: (StudioThemeSkin) -> Unit,
    onSelectUnit: (MeasurementUnit) -> Unit,
    onRenameScan: (Long, String) -> Unit,
    onDeleteScan: (RoomScanEntity) -> Unit
) {
    val skin = uiState.settings.themeSkin
    val isWin98 = skin == StudioThemeSkin.WINDOWS_98

    var showStartMenu by remember { mutableStateOf(false) }
    var showSensorDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RoomScanEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<RoomScanEntity?>(null) }

    val toggleSkin: () -> Unit = {
        onSelectSkin(if (isWin98) StudioThemeSkin.WINDOWS_VISTA else StudioThemeSkin.WINDOWS_98)
    }

    val menus = remember(uiState.sortOrder, uiState.viewMode, uiState.settings.measurementUnit, uiState.searchQuery, uiState.filteredScans) {
        listOf(
            RetroMenuCategory(
                title = "File",
                entries = listOf(
                    RetroMenuEntry("New Room Scan...", shortcut = "Ctrl+N", onClick = onStartScan),
                    RetroMenuEntry(
                        label = "Open Latest Scan",
                        enabled = uiState.filteredScans.isNotEmpty(),
                        onClick = { uiState.filteredScans.firstOrNull()?.let { onOpenScan(it.id) } }
                    ),
                    RetroMenuEntry("", isDivider = true),
                    RetroMenuEntry("Sensor Diagnostics...", onClick = { showSensorDialog = true })
                )
            ),
            RetroMenuCategory(
                title = "Edit",
                entries = listOf(
                    RetroMenuEntry(
                        label = "Clear Filter",
                        enabled = uiState.searchQuery.isNotBlank(),
                        onClick = { onSearchChange("") }
                    ),
                    RetroMenuEntry("", isDivider = true),
                    *ScanSortOrder.entries.map { order ->
                        RetroMenuEntry(
                            label = "Sort: ${order.label}",
                            checked = uiState.sortOrder == order,
                            onClick = { onSelectSort(order) }
                        )
                    }.toTypedArray()
                )
            ),
            RetroMenuCategory(
                title = "View",
                entries = listOf(
                    *LibraryViewMode.entries.map { mode ->
                        RetroMenuEntry(
                            label = mode.label,
                            checked = uiState.viewMode == mode,
                            onClick = { onSelectViewMode(mode) }
                        )
                    }.toTypedArray(),
                    RetroMenuEntry("", isDivider = true),
                    *MeasurementUnit.entries.map { unit ->
                        RetroMenuEntry(
                            label = "Units: ${unit.label}",
                            checked = uiState.settings.measurementUnit == unit,
                            onClick = { onSelectUnit(unit) }
                        )
                    }.toTypedArray()
                )
            ),
            RetroMenuCategory(
                title = "Tools",
                entries = listOf(
                    RetroMenuEntry("Launch LiDAR Scanner", onClick = onStartScan),
                    RetroMenuEntry("Hardware Sensor Probe", onClick = { showSensorDialog = true })
                )
            )
        )
    }

    DesktopWallpaperBox {
        Column(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                val screenWidth = maxWidth
                val columns = when {
                    uiState.viewMode == LibraryViewMode.ICONS && screenWidth >= 600.dp -> 3
                    uiState.viewMode == LibraryViewMode.ICONS -> 2
                    screenWidth >= 880.dp -> 2
                    else -> 1
                }

                RetroWindowFrame(
                    title = "LidarForge 3D - Room Explorer [C:\\Scans]",
                    windowIcon = Icons.Filled.ViewInAr,
                    onMinimizeClick = { showStartMenu = true },
                    onMaximizeClick = {
                        onSelectViewMode(
                            if (uiState.viewMode == LibraryViewMode.DETAILS) LibraryViewMode.ICONS
                            else LibraryViewMode.DETAILS
                        )
                    },
                    menuBar = { RetroDropdownMenuBar(menus = menus) },
                    statusBarText = "${uiState.sensorReadiness.activeTierLabel} • ${uiState.sensorReadiness.activeTierDetail}",
                    statusBarRight = "${uiState.filteredScans.size} file(s)",
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Primary Scan CTA + Explorer Dropdowns (Sort & Units)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            RetroButton(
                                onClick = onStartScan,
                                accent = true,
                                minHeight = 42.dp,
                                modifier = Modifier
                                    .weight(1.1f)
                                    .testTag("start_room_scan_fab")
                            ) {
                                Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Scan Room", style = MaterialTheme.typography.labelLarge)
                            }
                            RetroComboBox(
                                selectedText = uiState.sortOrder.label,
                                options = ScanSortOrder.entries,
                                optionLabel = { it.label },
                                isSelected = { it == uiState.sortOrder },
                                onSelect = onSelectSort,
                                testTagName = "library_sort_dropdown",
                                modifier = Modifier.weight(1f)
                            )
                            RetroComboBox(
                                selectedText = uiState.settings.measurementUnit.shortSuffix.uppercase(),
                                options = MeasurementUnit.entries,
                                optionLabel = { "${it.label} (${it.shortSuffix})" },
                                isSelected = { it == uiState.settings.measurementUnit },
                                onSelect = onSelectUnit,
                                testTagName = "library_units_dropdown",
                                modifier = Modifier.width(88.dp)
                            )
                        }

                        // Explorer Address & Search Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Address:",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isWin98) Color.Black else Color(0xFFB5D8EE)
                            )
                            RetroSunkenWell(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 38.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (uiState.searchQuery.isEmpty()) Icons.Filled.FolderOpen else Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = if (isWin98) Win98TitleActiveStart else VistaAeroCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (uiState.searchQuery.isEmpty()) {
                                            Text(
                                                text = "C:\\LidarForge\\Scans\\  (filter scans)",
                                                style = TelemetryTextStyle,
                                                color = if (isWin98) Color(0xFF666666) else Color(0xFF7CA5C2),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        BasicTextField(
                                            value = uiState.searchQuery,
                                            onValueChange = onSearchChange,
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                fontFamily = TelemetryTextStyle.fontFamily,
                                                fontSize = 13.sp,
                                                color = if (isWin98) Color.Black else Color.White
                                            ),
                                            cursorBrush = SolidColor(if (isWin98) Win98TitleActiveStart else VistaAeroCyan),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("scan_search_input")
                                        )
                                    }
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Clear filter",
                                            tint = if (isWin98) Color.Black else Color.White,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clickable { onSearchChange("") }
                                        )
                                    }
                                }
                            }
                        }

                        // Sunken Explorer File Well
                        RetroSunkenWell(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (!uiState.isLoading && uiState.filteredScans.isEmpty()) {
                                EmptyExplorerWell(
                                    isFiltered = uiState.searchQuery.isNotBlank(),
                                    isWin98 = isWin98,
                                    onStartScan = onStartScan,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(columns),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(uiState.filteredScans, key = { it.id }) { scan ->
                                        if (uiState.viewMode == LibraryViewMode.ICONS) {
                                            ExplorerScanIconTile(
                                                scan = scan,
                                                unit = uiState.settings.measurementUnit,
                                                isWin98 = isWin98,
                                                onOpen = { onOpenScan(scan.id) },
                                                onRename = { renameTarget = scan },
                                                onDelete = { deleteTarget = scan }
                                            )
                                        } else {
                                            ExplorerScanItemCard(
                                                scan = scan,
                                                unit = uiState.settings.measurementUnit,
                                                isWin98 = isWin98,
                                                onOpen = { onOpenScan(scan.id) },
                                                onRename = { renameTarget = scan },
                                                onDelete = { deleteTarget = scan }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            RetroTaskbar(
                activeWindowTag = "library",
                activeWindowTitle = "Scans",
                currentSkin = skin,
                onStartClick = { showStartMenu = !showStartMenu },
                onLibraryClick = { showStartMenu = false },
                onScanClick = onStartScan,
                onToggleSkin = toggleSkin
            )
        }

        if (showStartMenu) {
            RetroStartMenuOverlay(
                currentSkin = skin,
                currentUnit = uiState.settings.measurementUnit,
                onDismiss = { showStartMenu = false },
                onNewScan = onStartScan,
                onGoLibrary = { showStartMenu = false },
                onOpenSensorsInfo = { showSensorDialog = true },
                onSelectUnit = onSelectUnit
            )
        }
    }

    if (showSensorDialog) {
        SensorDiagnosticsDialog(
            readiness = uiState.sensorReadiness,
            onDismiss = { showSensorDialog = false }
        )
    }

    renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Rename Scan File") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("File name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_scan_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameScan(target.id, text)
                        renameTarget = null
                    },
                    modifier = Modifier.testTag("rename_scan_confirm")
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Confirm File Delete") },
            text = { Text("Permanently delete \"${target.title}\" and its local 3D mesh file?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteScan(target)
                        deleteTarget = null
                    },
                    modifier = Modifier.testTag("delete_scan_confirm")
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SensorDiagnosticsDialog(
    readiness: SensorReadiness,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Hardware Sensor Diagnostics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Active Capture Tier: ${readiness.activeTierLabel}", style = MaterialTheme.typography.titleSmall)
                Text(readiness.activeTierDetail, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                DiagnosticRow("Rear Camera (CameraX)", readiness.hasRearCamera)
                DiagnosticRow("Hardware ToF / DEPTH16", readiness.hasHardwareDepth)
                DiagnosticRow("ARCore Depth Support", readiness.hasArCoreSupport)
                DiagnosticRow("6-DoF Rotation Vector", readiness.hasRotationVector)
                DiagnosticRow("Gyroscope Sensor", readiness.hasGyroscope)
            }
        },
        confirmButton = {
            RetroButton(onClick = onDismiss, accent = true, minHeight = 40.dp) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun DiagnosticRow(name: String, available: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodySmall)
        Text(
            text = if (available) "READY" else "N/A",
            style = TelemetryTextStyle,
            fontWeight = FontWeight.Bold,
            color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExplorerScanIconTile(
    scan: RoomScanEntity,
    unit: MeasurementUnit,
    isWin98: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val tileMod = if (isWin98) {
        Modifier
            .fillMaxWidth()
            .background(Win98FaceLight)
            .win98RaisedBevel()
            .clickable(onClick = onOpen)
            .padding(8.dp)
            .testTag("room_scan_card_${scan.id}")
    } else {
        Modifier
            .fillMaxWidth()
            .background(Color(0xAA173147), shape = MaterialTheme.shapes.small)
            .clickable(onClick = onOpen)
            .padding(8.dp)
            .testTag("room_scan_card_${scan.id}")
    }

    Column(
        modifier = tileMod,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        RetroSunkenWell(
            darkWell = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(94.dp)
        ) {
            MiniWireframePreview(scan.widthMeters, scan.depthMeters, scan.heightMeters, scan.id)
        }
        Text(
            text = scan.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isWin98) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${unit.format(scan.widthMeters)} × ${unit.format(scan.depthMeters)}",
            style = TelemetryTextStyle,
            color = if (isWin98) Win98TitleActiveStart else VistaAeroCyan
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RetroButton(onClick = onRename, minHeight = 32.dp) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_rename_scan), modifier = Modifier.size(14.dp))
            }
            RetroButton(onClick = onDelete, minHeight = 32.dp) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.cd_delete_scan), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ExplorerScanItemCard(
    scan: RoomScanEntity,
    unit: MeasurementUnit,
    isWin98: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(scan.createdAtMs) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(scan.createdAtMs))
    }

    val itemModifier = if (isWin98) {
        Modifier
            .fillMaxWidth()
            .background(Win98FaceLight)
            .win98RaisedBevel()
            .clickable(onClick = onOpen)
            .padding(8.dp)
            .testTag("room_scan_card_${scan.id}")
    } else {
        Modifier
            .fillMaxWidth()
            .background(Color(0xAA173147), shape = MaterialTheme.shapes.small)
            .clickable(onClick = onOpen)
            .padding(8.dp)
            .testTag("room_scan_card_${scan.id}")
    }

    Row(
        modifier = itemModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RetroSunkenWell(darkWell = true, modifier = Modifier.size(width = 84.dp, height = 74.dp)) {
            MiniWireframePreview(scan.widthMeters, scan.depthMeters, scan.heightMeters, scan.id)
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = scan.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isWin98) Color.Black else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${unit.format(scan.widthMeters)} × ${unit.format(scan.depthMeters)} × ${unit.format(scan.heightMeters)}",
                style = TelemetryTextStyle,
                color = if (isWin98) Win98TitleActiveStart else VistaAeroCyan
            )
            Text(
                text = "${scan.vertexCount}v • ${scan.triangleCount}t • ${scan.voxelSizeCm}cm • $dateStr",
                style = MaterialTheme.typography.labelSmall,
                color = if (isWin98) Color(0xFF333333) else Color(0xFFA7C7DE),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RetroButton(onClick = onRename, minHeight = 36.dp, modifier = Modifier.testTag("rename_scan_btn_${scan.id}")) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_rename_scan), modifier = Modifier.size(15.dp))
            }
            RetroButton(onClick = onDelete, minHeight = 36.dp, modifier = Modifier.testTag("delete_scan_btn_${scan.id}")) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.cd_delete_scan), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun EmptyExplorerWell(
    isFiltered: Boolean,
    isWin98: Boolean,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extras = LocalStudioSkinExtras.current
    val infinite = rememberInfiniteTransition(label = "crt_sweep")
    val sweepFrac by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(2400, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "crt_line"
    )

    Column(
        modifier = modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        RetroSunkenWell(darkWell = true, modifier = Modifier.size(width = 200.dp, height = 140.dp)) {
            Box(modifier = Modifier.fillMaxSize()) {
                MiniWireframePreview(4.0f, 3.2f, 2.6f, 1L)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val y = size.height * (0.14f + sweepFrac * 0.72f)
                    drawLine(
                        brush = Brush.horizontalGradient(listOf(Color.Transparent, extras.laserBeamColor, Color.Transparent)),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isFiltered) "0 matching files in C:\\LidarForge\\Scans\\" else "C:\\LidarForge\\Scans\\ is empty",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isWin98) Color.Black else Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isFiltered) "Clear the address filter above to view all room scans."
            else "Launch the 3D Room Scanner to capture a room, crop & measure it, and export .OBJ / .GLB / .PLY / .STL / Bryce Heightmap .PNG.",
            style = MaterialTheme.typography.bodySmall,
            color = if (isWin98) Color(0xFF333333) else Color(0xFFB5D6EC),
            textAlign = TextAlign.Center
        )
        if (!isFiltered) {
            Spacer(Modifier.height(16.dp))
            RetroButton(
                onClick = onStartScan,
                accent = true,
                minHeight = 48.dp,
                modifier = Modifier.testTag("empty_start_scan_button")
            ) {
                Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_start_scan), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
