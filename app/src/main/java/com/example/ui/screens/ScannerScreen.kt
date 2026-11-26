package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.engine.MeasurementUnit
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
import com.example.scanner.ImuPose
import com.example.scanner.SensorReadiness
import com.example.scanner.SpatialCaptureController
import com.example.ui.CaptureDensity
import com.example.ui.ScannerUiState
import com.example.ui.components.DesktopWallpaperBox
import com.example.ui.components.RetroButton
import com.example.ui.components.RetroComboBox
import com.example.ui.components.RetroDropdownMenuBar
import com.example.ui.components.RetroMenuCategory
import com.example.ui.components.RetroMenuEntry
import com.example.ui.components.RetroStartMenuOverlay
import com.example.ui.components.RetroSunkenWell
import com.example.ui.components.RetroTaskbar
import com.example.ui.components.RetroWindowFrame
import com.example.ui.theme.LocalStudioSkinExtras
import com.example.ui.theme.StudioThemeSkin
import com.example.ui.theme.TelemetryTextStyle
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ScannerScreen(
    uiState: ScannerUiState,
    sensorReadiness: SensorReadiness,
    currentSkin: StudioThemeSkin,
    currentUnit: MeasurementUnit,
    onSelectSkin: (StudioThemeSkin) -> Unit,
    onSelectUnit: (MeasurementUnit) -> Unit,
    onBack: () -> Unit,
    onToggleScan: () -> Unit,
    onPointsCaptured: (List<SpatialPoint>) -> Unit,
    onAnchorCorner: (Vec3) -> Unit,
    onUndo: () -> Unit,
    onEyeHeightChange: (Float) -> Unit,
    onCeilingHeightChange: (Float) -> Unit,
    onDensityChange: (CaptureDensity) -> Unit,
    onFinishScan: (sensorMode: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val extras = LocalStudioSkinExtras.current
    val isWin98 = currentSkin == StudioThemeSkin.WINDOWS_98

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var useImuOnlyMode by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    var showRadarHud by remember { mutableStateOf(true) }
    var showStartMenu by remember { mutableStateOf(false) }
    var cameraBindError by remember { mutableStateOf<String?>(null) }

    val toggleSkin: () -> Unit = {
        onSelectSkin(if (isWin98) StudioThemeSkin.WINDOWS_VISTA else StudioThemeSkin.WINDOWS_98)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val captureController = remember(context) {
        SpatialCaptureController(context = context, onPointsCaptured = onPointsCaptured)
    }
    val imuPose by captureController.imuPose.collectAsState()

    LaunchedEffect(uiState.isScanning, uiState.isPaused, uiState.eyeHeightMeters, uiState.ceilingHeightMeters, uiState.captureDensity) {
        captureController.isActivelyCapturing = uiState.isScanning && !uiState.isPaused
        captureController.eyeHeightMeters = uiState.eyeHeightMeters
        captureController.ceilingHeightMeters = uiState.ceilingHeightMeters
        captureController.sampleStep = uiState.captureDensity.sampleStep
    }

    DisposableEffect(captureController) {
        captureController.startSensors()
        onDispose {
            captureController.isActivelyCapturing = false
            captureController.stopSensors()
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    val activeTier = if (cameraBindError != null || useImuOnlyMode) "IMU Raycast" else sensorReadiness.activeTierLabel

    val menus = remember(uiState.isScanning, uiState.isPaused, uiState.canFinish, uiState.captureDensity, showCalibration, showRadarHud, useImuOnlyMode) {
        listOf(
            RetroMenuCategory(
                title = "Capture",
                entries = listOf(
                    RetroMenuEntry(
                        label = if (uiState.isScanning && !uiState.isPaused) "Pause Sweep" else "Start Sweep",
                        onClick = onToggleScan
                    ),
                    RetroMenuEntry(
                        label = "Anchor Floor Corner",
                        onClick = { onAnchorCorner(captureController.raycastFloorCorner()) }
                    ),
                    RetroMenuEntry(
                        label = "Undo Last Segment",
                        enabled = uiState.pointCount > 0 || uiState.cornerAnchors.isNotEmpty(),
                        onClick = onUndo
                    ),
                    RetroMenuEntry("", isDivider = true),
                    RetroMenuEntry(
                        label = "Finish & Build 3D Mesh",
                        enabled = uiState.canFinish,
                        onClick = { onFinishScan(activeTier) }
                    ),
                    RetroMenuEntry("Close Capture Window", onClick = onBack)
                )
            ),
            RetroMenuCategory(
                title = "Density",
                entries = CaptureDensity.entries.map { d ->
                    RetroMenuEntry(
                        label = "${d.label} (${d.defaultVoxelCm} cm voxel)",
                        checked = uiState.captureDensity == d,
                        onClick = { onDensityChange(d) }
                    )
                }
            ),
            RetroMenuCategory(
                title = "View",
                entries = listOf(
                    RetroMenuEntry("Calibration Drawer", checked = showCalibration, onClick = { showCalibration = !showCalibration }),
                    RetroMenuEntry("Mini-Radar HUD", checked = showRadarHud, onClick = { showRadarHud = !showRadarHud }),
                    RetroMenuEntry("", isDivider = true),
                    RetroMenuEntry("IMU Corner-Only Mode", checked = useImuOnlyMode, onClick = { useImuOnlyMode = !useImuOnlyMode })
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
                RetroWindowFrame(
                    title = "LiDAR Capture Device - [$activeTier]",
                    windowIcon = Icons.Filled.CameraAlt,
                    onMinimizeClick = onBack,
                    onMaximizeClick = { showCalibration = !showCalibration },
                    onCloseClick = onBack,
                    menuBar = { RetroDropdownMenuBar(menus = menus) },
                    statusBarText = uiState.statusMessage ?: String.format(
                        Locale.US,
                        "AZ %03.0f° | PITCH %+.0f° | %02d:%02d",
                        imuPose.yawDegrees,
                        imuPose.pitchDegrees,
                        uiState.elapsedSeconds / 60,
                        uiState.elapsedSeconds % 60
                    ),
                    statusBarRight = "${uiState.pointCount} pts • ${uiState.coveragePercent}%",
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LcdReadoutCell("POINTS", "${uiState.pointCount}", Modifier.weight(1f))
                            LcdReadoutCell("CORNERS", "${uiState.cornerAnchors.size}", Modifier.weight(0.85f))
                            LcdReadoutCell("COVER", "${uiState.coveragePercent}%", Modifier.weight(0.95f))
                            RetroButton(
                                onClick = { showCalibration = !showCalibration },
                                active = showCalibration,
                                minHeight = 42.dp,
                                modifier = Modifier.testTag("scanner_calibration_toggle")
                            ) {
                                Icon(Icons.Filled.Tune, contentDescription = "Calibration", modifier = Modifier.size(16.dp))
                            }
                        }

                        if (showCalibration) {
                            CalibrationPanel(
                                isWin98 = isWin98,
                                eyeHeight = uiState.eyeHeightMeters,
                                ceilingHeight = uiState.ceilingHeightMeters,
                                density = uiState.captureDensity,
                                onEyeHeightChange = onEyeHeightChange,
                                onCeilingHeightChange = onCeilingHeightChange,
                                onDensityChange = onDensityChange
                            )
                        }

                        RetroSunkenWell(
                            darkWell = true,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (!hasCameraPermission && !useImuOnlyMode) {
                                CameraPermissionDialogWell(
                                    onRequestCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                    onUseImuCornerMode = { useImuOnlyMode = true },
                                    onBack = onBack
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (hasCameraPermission && !useImuOnlyMode && cameraBindError == null) {
                                        AndroidView(
                                            modifier = Modifier.fillMaxSize(),
                                            factory = { ctx ->
                                                val previewView = PreviewView(ctx).apply {
                                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                                }
                                                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                                                providerFuture.addListener({
                                                    try {
                                                        val cameraProvider = providerFuture.get()
                                                        val preview = Preview.Builder().build().also {
                                                            it.surfaceProvider = previewView.surfaceProvider
                                                        }
                                                        val imageAnalysis = ImageAnalysis.Builder()
                                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                            .build()
                                                            .also {
                                                                it.setAnalyzer(analysisExecutor, captureController.createImageAnalyzer())
                                                            }
                                                        val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                                                            CameraSelector.DEFAULT_BACK_CAMERA
                                                        } else {
                                                            CameraSelector.DEFAULT_FRONT_CAMERA
                                                        }
                                                        cameraProvider.unbindAll()
                                                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                                                    } catch (e: Exception) {
                                                        cameraBindError = e.message ?: "Camera unavailable"
                                                    }
                                                }, ContextCompat.getMainExecutor(ctx))
                                                previewView
                                            }
                                        )
                                    }

                                    LidarHudOverlay(
                                        imuPose = imuPose,
                                        isScanning = uiState.isScanning && !uiState.isPaused,
                                        showRadar = showRadarHud,
                                        previewPoints = uiState.previewPoints,
                                        cornerAnchors = uiState.cornerAnchors,
                                        onManualAimDrag = { dx, dy ->
                                            if (!imuPose.hasLiveImu || useImuOnlyMode || cameraBindError != null) {
                                                captureController.nudgeManualPose(dx * 0.006f, -dy * 0.005f)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    if (!imuPose.hasLiveImu || useImuOnlyMode || cameraBindError != null) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(8.dp)
                                                .background(Color(0xCC060C14))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "Drag screen to aim • Tap Corner at 3+ room corners",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = extras.laserBeamColor
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RetroButton(
                                onClick = onUndo,
                                enabled = uiState.pointCount > 0 || uiState.cornerAnchors.isNotEmpty(),
                                minHeight = 48.dp,
                                modifier = Modifier.testTag("scanner_undo_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.action_undo), modifier = Modifier.size(18.dp))
                            }

                            RetroButton(
                                onClick = { onAnchorCorner(captureController.raycastFloorCorner()) },
                                minHeight = 48.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("scanner_anchor_corner_button")
                            ) {
                                Icon(Icons.Filled.AddLocationAlt, contentDescription = stringResource(R.string.cd_anchor_corner), modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Corner", style = MaterialTheme.typography.labelLarge)
                            }

                            val active = uiState.isScanning && !uiState.isPaused
                            RetroButton(
                                onClick = onToggleScan,
                                enabled = !uiState.isProcessing,
                                accent = true,
                                active = active,
                                minHeight = 48.dp,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .testTag("scanner_toggle_capture_button")
                            ) {
                                Icon(
                                    imageVector = if (active) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.cd_toggle_scan),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (active) "Pause" else "Scan", style = MaterialTheme.typography.labelLarge)
                            }

                            RetroButton(
                                onClick = { onFinishScan(activeTier) },
                                enabled = uiState.canFinish,
                                accent = uiState.canFinish,
                                minHeight = 48.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("scanner_finish_button")
                            ) {
                                if (uiState.isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                } else {
                                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.action_finish_scan), modifier = Modifier.size(17.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text("Mesh", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            RetroTaskbar(
                activeWindowTag = "scanner",
                activeWindowTitle = "Capture",
                currentSkin = currentSkin,
                onStartClick = { showStartMenu = !showStartMenu },
                onLibraryClick = onBack,
                onScanClick = {},
                onToggleSkin = toggleSkin
            )
        }

        if (showStartMenu) {
            RetroStartMenuOverlay(
                currentSkin = currentSkin,
                currentUnit = currentUnit,
                onDismiss = { showStartMenu = false },
                onNewScan = { showStartMenu = false },
                onGoLibrary = {
                    showStartMenu = false
                    onBack()
                },
                onSelectUnit = onSelectUnit
            )
        }
    }
}

@Composable
private fun LcdReadoutCell(label: String, value: String, modifier: Modifier = Modifier) {
    val extras = LocalStudioSkinExtras.current
    RetroSunkenWell(darkWell = true, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8AA4BE))
            Text(value, style = TelemetryTextStyle, fontWeight = FontWeight.Bold, color = extras.laserBeamColor)
        }
    }
}

@Composable
private fun CalibrationPanel(
    isWin98: Boolean,
    eyeHeight: Float,
    ceilingHeight: Float,
    density: CaptureDensity,
    onEyeHeightChange: (Float) -> Unit,
    onCeilingHeightChange: (Float) -> Unit,
    onDensityChange: (CaptureDensity) -> Unit
) {
    val textColor = if (isWin98) Color.Black else Color.White
    RetroSunkenWell(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RetroComboBox(
                label = "Capture Density Preset",
                selectedText = "${density.label} (${density.defaultVoxelCm} cm voxel)",
                options = CaptureDensity.entries,
                optionLabel = { "${it.label} (${it.defaultVoxelCm} cm voxel)" },
                isSelected = { it == density },
                onSelect = onDensityChange,
                testTagName = "scanner_density_dropdown"
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Camera Eye Height", style = MaterialTheme.typography.labelSmall, color = textColor)
                Text(String.format(Locale.US, "%.2f m", eyeHeight), style = TelemetryTextStyle, color = textColor)
            }
            Slider(value = eyeHeight, onValueChange = onEyeHeightChange, valueRange = 0.9f..2.2f)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Room Ceiling Height", style = MaterialTheme.typography.labelSmall, color = textColor)
                Text(String.format(Locale.US, "%.2f m", ceilingHeight), style = TelemetryTextStyle, color = textColor)
            }
            Slider(value = ceilingHeight, onValueChange = onCeilingHeightChange, valueRange = 2.0f..4.5f)
        }
    }
}

@Composable
private fun LidarHudOverlay(
    imuPose: ImuPose,
    isScanning: Boolean,
    showRadar: Boolean,
    previewPoints: List<SpatialPoint>,
    cornerAnchors: List<Vec3>,
    onManualAimDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val extras = LocalStudioSkinExtras.current
    val infinite = rememberInfiniteTransition(label = "hud_laser")
    val laserPos by infinite.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(animation = tween(1800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "laser_y"
    )

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onManualAimDrag(dragAmount.x, dragAmount.y)
            }
        }
    ) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f

        if (isScanning) {
            val ly = size.height * laserPos
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, extras.laserBeamColor.copy(alpha = 0.18f), Color.Transparent),
                    startY = ly - 44.dp.toPx(),
                    endY = ly + 44.dp.toPx()
                ),
                topLeft = Offset(0f, ly - 44.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width, 88.dp.toPx())
            )
            drawLine(extras.laserBeamColor.copy(alpha = 0.85f), Offset(0f, ly), Offset(size.width, ly), strokeWidth = 2.dp.toPx())
        }

        if (showRadar) {
            val radarR = 46.dp.toPx()
            val rx = radarR + 12.dp.toPx()
            val ry = size.height - radarR - 12.dp.toPx()

            drawCircle(color = Color(0xCC060C14), radius = radarR, center = Offset(rx, ry))
            drawCircle(color = extras.laserBeamColor.copy(alpha = 0.55f), radius = radarR, center = Offset(rx, ry), style = Stroke(1.5.dp.toPx()))
            drawCircle(color = extras.laserBeamColor.copy(alpha = 0.22f), radius = radarR * 0.5f, center = Offset(rx, ry), style = Stroke(1.dp.toPx()))

            val radarScale = radarR / 4.8f
            for (p in previewPoints) {
                val px = rx + p.x * radarScale
                val py = ry + p.z * radarScale
                if ((px - rx) * (px - rx) + (py - ry) * (py - ry) <= radarR * radarR) {
                    drawCircle(color = extras.laserBeamColor.copy(alpha = 0.78f), radius = 2.2f, center = Offset(px, py))
                }
            }
            if (cornerAnchors.isNotEmpty()) {
                for (i in cornerAnchors.indices) {
                    val a = cornerAnchors[i]
                    val ax = rx + a.x * radarScale
                    val ay = ry + a.z * radarScale
                    if (i + 1 < cornerAnchors.size) {
                        val b = cornerAnchors[i + 1]
                        drawLine(extras.cropCageColor, Offset(ax, ay), Offset(rx + b.x * radarScale, ry + b.z * radarScale), strokeWidth = 2.dp.toPx())
                    }
                    drawCircle(color = extras.cropCageColor, radius = 4f, center = Offset(ax, ay))
                }
                if (cornerAnchors.size >= 3) {
                    val first = cornerAnchors.first()
                    val last = cornerAnchors.last()
                    drawLine(
                        extras.cropCageColor.copy(alpha = 0.6f),
                        Offset(rx + last.x * radarScale, ry + last.z * radarScale),
                        Offset(rx + first.x * radarScale, ry + first.z * radarScale),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            }
            val headX = rx + sin(imuPose.yawRad) * (radarR * 0.78f)
            val headY = ry - cos(imuPose.yawRad) * (radarR * 0.78f)
            drawLine(color = Color.White, start = Offset(rx, ry), end = Offset(headX, headY), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }

        val retR = 24.dp.toPx()
        val retColor = if (isScanning) extras.laserBeamColor else Color.White.copy(alpha = 0.9f)
        drawCircle(color = retColor.copy(alpha = 0.45f), radius = retR, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
        val tick = 9.dp.toPx()
        drawLine(retColor, Offset(cx - retR - tick, cy), Offset(cx - retR + 3.dp.toPx(), cy), strokeWidth = 2.dp.toPx())
        drawLine(retColor, Offset(cx + retR - 3.dp.toPx(), cy), Offset(cx + retR + tick, cy), strokeWidth = 2.dp.toPx())
        drawLine(retColor, Offset(cx, cy - retR - tick), Offset(cx, cy - retR + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
        drawLine(retColor, Offset(cx, cy + retR - 3.dp.toPx()), Offset(cx, cy + retR + tick), strokeWidth = 2.dp.toPx())
        drawCircle(color = extras.cropCageColor, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
private fun CameraPermissionDialogWell(
    onRequestCamera: () -> Unit,
    onUseImuCornerMode: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color(0xFF48D1FF), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.camera_permission_body), style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5D2E8), textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        RetroButton(onClick = onRequestCamera, accent = true, minHeight = 46.dp, modifier = Modifier.fillMaxWidth().testTag("grant_camera_permission_button")) {
            Text(stringResource(R.string.action_grant_camera), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(8.dp))
        RetroButton(onClick = onUseImuCornerMode, minHeight = 46.dp, modifier = Modifier.fillMaxWidth().testTag("use_imu_raycast_fallback_button")) {
            Text("Use IMU Corner Raycast Mode", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(8.dp))
        RetroButton(onClick = onBack, minHeight = 42.dp) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
