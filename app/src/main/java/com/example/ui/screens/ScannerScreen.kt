package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.engine.MeasurementUnit
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
import com.example.scanner.ArCoreScannerRenderer
import com.example.scanner.CapturedMeshPatch
import com.example.scanner.LiveMeshAccumulator
import com.example.scanner.ScreenEdge2D
import com.example.scanner.ScreenPlaneOverlay
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ScannerEngineMode(val label: String) {
    ARCORE_DEPTH("ARCore Live Mesh + Depth16 (Auto)"),
    CAMERAX_IMU("CameraX + 6-DoF IMU"),
    IMU_CORNER_ONLY("Corner Raycast Only")
}

@Composable
fun ScannerScreen(
    uiState: ScannerUiState,
    liveAccumulator: LiveMeshAccumulator,
    sensorReadiness: SensorReadiness,
    currentSkin: StudioThemeSkin,
    currentUnit: MeasurementUnit,
    onSelectSkin: (StudioThemeSkin) -> Unit,
    onSelectUnit: (MeasurementUnit) -> Unit,
    onBack: () -> Unit,
    onToggleScan: () -> Unit,
    onPatchCaptured: (CapturedMeshPatch) -> Unit,
    onAnchorCorner: (Vec3) -> Unit,
    onUndo: () -> Unit,
    onClearScan: () -> Unit,
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
    var engineMode by remember(sensorReadiness.hasArCoreSupport) {
        mutableStateOf(
            if (sensorReadiness.hasArCoreSupport) ScannerEngineMode.ARCORE_DEPTH
            else ScannerEngineMode.CAMERAX_IMU
        )
    }
    var arFallbackReason by remember { mutableStateOf<String?>(null) }
    var showCalibration by remember { mutableStateOf(false) }
    var showRadarHud by remember { mutableStateOf(true) }
    var showLiveMesh by remember { mutableStateOf(true) }
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

    val useArCore = hasCameraPermission &&
        engineMode == ScannerEngineMode.ARCORE_DEPTH &&
        arFallbackReason == null

    // 1. Real ARCore 6-DoF + Dense DEPTH16 + 60 FPS Live OpenGL Mesh Painter
    val arRenderer = remember(context, liveAccumulator) {
        ArCoreScannerRenderer(
            context = context,
            liveAccumulator = liveAccumulator,
            onPatchCaptured = onPatchCaptured,
            onArUnavailable = { reason ->
                arFallbackReason = reason
                if (engineMode == ScannerEngineMode.ARCORE_DEPTH) {
                    engineMode = ScannerEngineMode.CAMERAX_IMU
                }
            }
        )
    }
    val arTelemetry by arRenderer.telemetry.collectAsState()

    // 2. Upgraded CameraX + IMU Fallback Engine (emits connected grid mesh patches)
    val captureController = remember(context) {
        SpatialCaptureController(context = context, onPatchCaptured = onPatchCaptured)
    }
    val imuPose by captureController.imuPose.collectAsState()

    LaunchedEffect(
        uiState.isScanning,
        uiState.isPaused,
        uiState.eyeHeightMeters,
        uiState.ceilingHeightMeters,
        uiState.captureDensity,
        uiState.cornerAnchors,
        showLiveMesh,
        useArCore
    ) {
        val active = uiState.isScanning && !uiState.isPaused
        arRenderer.isActivelyCapturing = active && useArCore
        arRenderer.showLiveMeshOverlay = showLiveMesh
        arRenderer.sampleStep = uiState.captureDensity.sampleStep
        arRenderer.currentCornerAnchors = uiState.cornerAnchors

        captureController.isActivelyCapturing = active && !useArCore
        captureController.eyeHeightMeters = uiState.eyeHeightMeters
        captureController.ceilingHeightMeters = uiState.ceilingHeightMeters
        captureController.sampleStep = uiState.captureDensity.sampleStep
        captureController.roomCorners = uiState.cornerAnchors
    }

    DisposableEffect(captureController, useArCore) {
        if (!useArCore) {
            captureController.startSensors()
        } else {
            captureController.stopSensors()
        }
        onDispose {
            captureController.isActivelyCapturing = false
            captureController.stopSensors()
        }
    }

    DisposableEffect(arRenderer, useArCore) {
        onDispose {
            arRenderer.isActivelyCapturing = false
            arRenderer.destroySession()
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    val activeTier = when {
        useArCore && arTelemetry.isArActive -> arTelemetry.depthModeLabel
        engineMode == ScannerEngineMode.IMU_CORNER_ONLY || cameraBindError != null -> "IMU Corner Raycast"
        else -> "CameraX + IMU Mesh"
    }

    val anchorCenterCorner: () -> Unit = {
        if (useArCore) {
            arRenderer.requestCenterCornerHit { hit ->
                val pt = hit ?: arTelemetry.reticleWorldPoint
                if (pt != null) onAnchorCorner(pt)
            }
        } else {
            onAnchorCorner(captureController.raycastFloorCorner())
        }
    }

    val menus = remember(
        uiState.isScanning,
        uiState.isPaused,
        uiState.canFinish,
        uiState.captureDensity,
        showCalibration,
        showRadarHud,
        showLiveMesh,
        engineMode
    ) {
        listOf(
            RetroMenuCategory(
                title = "Capture",
                entries = listOf(
                    RetroMenuEntry(
                        label = if (uiState.isScanning && !uiState.isPaused) "Pause Painting" else "Start Painting Mesh",
                        onClick = onToggleScan
                    ),
                    RetroMenuEntry("Anchor Corner at Reticle", onClick = anchorCenterCorner),
                    RetroMenuEntry(
                        label = "Undo Last Patch",
                        enabled = uiState.pointCount > 0 || uiState.cornerAnchors.isNotEmpty(),
                        onClick = onUndo
                    ),
                    RetroMenuEntry(
                        label = "Clear & Restart Scan",
                        enabled = uiState.pointCount > 0 || uiState.cornerAnchors.isNotEmpty(),
                        onClick = onClearScan
                    ),
                    RetroMenuEntry("", isDivider = true),
                    RetroMenuEntry(
                        label = "Finish & Open 3D Studio",
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
                        label = d.label,
                        checked = uiState.captureDensity == d,
                        onClick = { onDensityChange(d) }
                    )
                }
            ),
            RetroMenuCategory(
                title = "Engine",
                entries = ScannerEngineMode.entries.map { mode ->
                    RetroMenuEntry(
                        label = mode.label,
                        checked = engineMode == mode,
                        enabled = mode != ScannerEngineMode.ARCORE_DEPTH || arFallbackReason == null,
                        onClick = { engineMode = mode }
                    )
                }
            ),
            RetroMenuCategory(
                title = "View",
                entries = listOf(
                    RetroMenuEntry("Live 3D Mesh Blanket", checked = showLiveMesh, onClick = { showLiveMesh = !showLiveMesh }),
                    RetroMenuEntry("Mini-Radar HUD", checked = showRadarHud, onClick = { showRadarHud = !showRadarHud }),
                    RetroMenuEntry("Calibration Drawer", checked = showCalibration, onClick = { showCalibration = !showCalibration })
                )
            )
        )
    }

    val isActivelyPainting = uiState.isScanning && !uiState.isPaused
    val displayYawRad = if (useArCore) arTelemetry.yawRad else imuPose.yawRad
    val displayYawDeg = ((displayYawRad * 180f / PI.toFloat()) % 360f + 360f) % 360f
    val displayPitchDeg = if (useArCore) arTelemetry.pitchRad * 180f / PI.toFloat() else imuPose.pitchDegrees
    val reticleDist = if (useArCore) arTelemetry.reticleDistanceMeters else captureController.estimateReticleDistance()
    val reticleSrc = if (useArCore) arTelemetry.reticleHitSource else "PLANE"

    val overlayPlanes: List<ScreenPlaneOverlay> = if (useArCore && showLiveMesh) {
        arTelemetry.projectedPlanes
    } else emptyList()

    val fallbackWireEdges: List<ScreenEdge2D> = if (!useArCore && showLiveMesh && uiState.triangleCount > 0) {
        captureController.projectLiveMeshEdges(liveAccumulator)
    } else emptyList()

    val overlayCorners: List<Pair<Float, Float>> = when {
        !showLiveMesh -> emptyList()
        useArCore -> arTelemetry.projectedCorners
        else -> captureController.projectCornersToScreen(uiState.cornerAnchors)
    }

    val radarCamPos = if (useArCore) arTelemetry.cameraPosition else Vec3(0f, uiState.eyeHeightMeters, 0f)

    val topHeadline = when {
        useArCore -> {
            if (isActivelyPainting && arTelemetry.isTracking) {
                "PAINTING MESH • ${uiState.triangleCount} tris • Move slowly over walls & floor"
            } else arTelemetry.trackingHeadline
        }
        arFallbackReason != null -> "Fallback CameraX Mesh • Drag viewport to pan, press Scan to paint"
        engineMode == ScannerEngineMode.IMU_CORNER_ONLY -> "Corner Raycast • Drag to aim & tap 3+ room corners"
        else -> "CameraX + IMU Mesh • Press Scan & pan room slowly"
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
                    title = "LiDAR Capture - [$activeTier]",
                    windowIcon = Icons.Filled.CameraAlt,
                    onMinimizeClick = onBack,
                    onMaximizeClick = { showCalibration = !showCalibration },
                    onCloseClick = onBack,
                    menuBar = { RetroDropdownMenuBar(menus = menus) },
                    statusBarText = uiState.statusMessage ?: String.format(
                        Locale.US,
                        "AZ %03.0f° | PITCH %+.0f° | %02d:%02d",
                        displayYawDeg,
                        displayPitchDeg,
                        uiState.elapsedSeconds / 60,
                        uiState.elapsedSeconds % 60
                    ),
                    statusBarRight = "${uiState.triangleCount} tris • ${uiState.coveragePercent}%",
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
                            LcdReadoutCell("TRIANGLES", "${uiState.triangleCount}", Modifier.weight(1f))
                            LcdReadoutCell(
                                label = if (useArCore) "PLANES" else "CORNERS",
                                value = if (useArCore) "${arTelemetry.detectedPlaneCount}" else "${uiState.cornerAnchors.size}",
                                modifier = Modifier.weight(0.8f)
                            )
                            LcdReadoutCell(
                                label = "RANGE",
                                value = reticleDist?.let { String.format(Locale.US, "%.2fm", it) } ?: "--",
                                modifier = Modifier.weight(0.9f)
                            )
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
                                engineMode = engineMode,
                                arAvailable = arFallbackReason == null,
                                eyeHeight = uiState.eyeHeightMeters,
                                ceilingHeight = uiState.ceilingHeightMeters,
                                density = uiState.captureDensity,
                                onEngineModeChange = { engineMode = it },
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
                            if (!hasCameraPermission && engineMode != ScannerEngineMode.IMU_CORNER_ONLY) {
                                CameraPermissionDialogWell(
                                    onRequestCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                    onUseImuCornerMode = { engineMode = ScannerEngineMode.IMU_CORNER_ONLY },
                                    onBack = onBack
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    when {
                                        useArCore -> {
                                            ArCoreCameraSurface(
                                                renderer = arRenderer,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        hasCameraPermission && engineMode == ScannerEngineMode.CAMERAX_IMU && cameraBindError == null -> {
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
                                    }

                                    PolycamHudOverlay(
                                        yawRad = displayYawRad,
                                        cameraPos = radarCamPos,
                                        isScanning = isActivelyPainting,
                                        showRadar = showRadarHud,
                                        previewPoints = uiState.previewPoints,
                                        cornerAnchors = uiState.cornerAnchors,
                                        projectedPlanes = overlayPlanes,
                                        fallbackWireEdges = fallbackWireEdges,
                                        projectedCorners = overlayCorners,
                                        reticleDistMeters = reticleDist,
                                        reticleSource = reticleSrc,
                                        onTapViewport = { xPx, yPx, wPx, hPx ->
                                            if (useArCore) {
                                                arRenderer.requestTapCornerHit(xPx, yPx) { hit ->
                                                    if (hit != null) onAnchorCorner(hit)
                                                }
                                            } else {
                                                val nx = (xPx / wPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                                val ny = (yPx / hPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                                onAnchorCorner(captureController.raycastFloorCornerAt(nx, ny))
                                            }
                                        },
                                        onManualAimDrag = { dx, dy ->
                                            if (!useArCore) {
                                                captureController.nudgeManualPose(dx * 0.0055f, -dy * 0.0045f)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    // Top unmistakable status banner with live recording LED
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(6.dp)
                                            .background(Color(0xDC060C14))
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        isActivelyPainting -> Color(0xFF00E5A0)
                                                        useArCore && arTelemetry.isTracking -> Color(0xFF38BDF8)
                                                        else -> Color(0xFFFFB703)
                                                    }
                                                )
                                        )
                                        Text(
                                            text = topHeadline,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    if (reticleDist != null) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .padding(top = 68.dp)
                                                .background(Color(0xCC060C14))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = String.format(Locale.US, "%.2f m [%s]", reticleDist, reticleSrc.ifEmpty { "3D" }),
                                                style = TelemetryTextStyle,
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
                                onClick = anchorCenterCorner,
                                minHeight = 48.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("scanner_anchor_corner_button")
                            ) {
                                Icon(Icons.Filled.AddLocationAlt, contentDescription = stringResource(R.string.cd_anchor_corner), modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Corner", style = MaterialTheme.typography.labelLarge)
                            }

                            RetroButton(
                                onClick = onToggleScan,
                                enabled = !uiState.isProcessing,
                                accent = true,
                                active = isActivelyPainting,
                                minHeight = 48.dp,
                                modifier = Modifier
                                    .weight(1.25f)
                                    .testTag("scanner_toggle_capture_button")
                            ) {
                                Icon(
                                    imageVector = if (isActivelyPainting) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.cd_toggle_scan),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (isActivelyPainting) "Pause" else "Scan", style = MaterialTheme.typography.labelLarge)
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
                                Text("Done", style = MaterialTheme.typography.labelLarge)
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
private fun ArCoreCameraSurface(
    renderer: ArCoreScannerRenderer,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var glViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }

    DisposableEffect(lifecycleOwner, renderer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    renderer.resumeSession()
                    glViewRef?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    glViewRef?.onPause()
                    renderer.pauseSession()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            renderer.resumeSession()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glViewRef?.onPause()
            renderer.pauseSession()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glViewRef = this
            }
        }
    )
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
    engineMode: ScannerEngineMode,
    arAvailable: Boolean,
    eyeHeight: Float,
    ceilingHeight: Float,
    density: CaptureDensity,
    onEngineModeChange: (ScannerEngineMode) -> Unit,
    onEyeHeightChange: (Float) -> Unit,
    onCeilingHeightChange: (Float) -> Unit,
    onDensityChange: (CaptureDensity) -> Unit
) {
    val textColor = if (isWin98) Color.Black else Color.White
    val availableModes = if (arAvailable) ScannerEngineMode.entries else listOf(ScannerEngineMode.CAMERAX_IMU, ScannerEngineMode.IMU_CORNER_ONLY)

    RetroSunkenWell(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RetroComboBox(
                label = "Scanning Engine",
                selectedText = engineMode.label,
                options = availableModes,
                optionLabel = { it.label },
                isSelected = { it == engineMode },
                onSelect = onEngineModeChange,
                testTagName = "scanner_engine_dropdown"
            )
            RetroComboBox(
                label = "Capture Detail Preset",
                selectedText = density.label,
                options = CaptureDensity.entries,
                optionLabel = { it.label },
                isSelected = { it == density },
                onSelect = onDensityChange,
                testTagName = "scanner_density_dropdown"
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Camera Eye Height (Fallback)", style = MaterialTheme.typography.labelSmall, color = textColor)
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
private fun PolycamHudOverlay(
    yawRad: Float,
    cameraPos: Vec3,
    isScanning: Boolean,
    showRadar: Boolean,
    previewPoints: List<SpatialPoint>,
    cornerAnchors: List<Vec3>,
    projectedPlanes: List<ScreenPlaneOverlay>,
    fallbackWireEdges: List<ScreenEdge2D>,
    projectedCorners: List<Pair<Float, Float>>,
    reticleDistMeters: Float?,
    reticleSource: String,
    onTapViewport: (Float, Float, Float, Float) -> Unit,
    onManualAimDrag: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val extras = LocalStudioSkinExtras.current

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onTapViewport(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onManualAimDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f

        // 1. Subtle outlines for ARCore detected architectural planes (actual dense mesh is drawn in 60fps OpenGL!)
        for (plane in projectedPlanes) {
            if (plane.points.size < 3) continue
            val path = Path().apply {
                val first = plane.points.first()
                moveTo(first.first * w, first.second * h)
                for (i in 1 until plane.points.size) {
                    val pt = plane.points[i]
                    lineTo(pt.first * w, pt.second * h)
                }
                close()
            }
            val planeColor = when (plane.surfaceType) {
                SpatialPoint.SURFACE_FLOOR -> Color(0xFF00E5A0)
                SpatialPoint.SURFACE_CEILING -> Color(0xFF60A5FA)
                else -> Color(0xFF38BDF8)
            }
            drawPath(path = path, color = planeColor.copy(alpha = 0.08f), style = Fill)
            drawPath(path = path, color = planeColor.copy(alpha = 0.52f), style = Stroke(width = 1.4.dp.toPx()))
        }

        // 2. Fallback live 3D wireframe mesh edges (when running without ARCore OpenGL renderer)
        for (edge in fallbackWireEdges) {
            drawLine(
                color = extras.laserBeamColor.copy(alpha = 0.65f),
                start = Offset(edge.x0 * w, edge.y0 * h),
                end = Offset(edge.x1 * w, edge.y1 * h),
                strokeWidth = 1.3.dp.toPx()
            )
        }

        // 3. Projected 3D anchored room corners
        if (projectedCorners.isNotEmpty()) {
            for (i in projectedCorners.indices) {
                val a = Offset(projectedCorners[i].first * w, projectedCorners[i].second * h)
                if (i + 1 < projectedCorners.size) {
                    val b = Offset(projectedCorners[i + 1].first * w, projectedCorners[i + 1].second * h)
                    drawLine(extras.cropCageColor.copy(alpha = 0.85f), a, b, strokeWidth = 2.dp.toPx())
                }
                drawCircle(color = extras.cropCageColor, radius = 6.dp.toPx(), center = a, style = Stroke(2.dp.toPx()))
                drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = a)
            }
            if (projectedCorners.size >= 3) {
                val first = Offset(projectedCorners.first().first * w, projectedCorners.first().second * h)
                val last = Offset(projectedCorners.last().first * w, projectedCorners.last().second * h)
                drawLine(extras.cropCageColor.copy(alpha = 0.6f), last, first, strokeWidth = 1.6.dp.toPx())
            }
        }

        // 4. Top-Down Mini-Radar centered on camera's real 6-DoF position
        if (showRadar) {
            val radarR = 44.dp.toPx()
            val rx = radarR + 10.dp.toPx()
            val ry = h - radarR - 10.dp.toPx()

            drawCircle(color = Color(0xCC060C14), radius = radarR, center = Offset(rx, ry))
            drawCircle(color = extras.laserBeamColor.copy(alpha = 0.55f), radius = radarR, center = Offset(rx, ry), style = Stroke(1.5.dp.toPx()))
            drawCircle(color = extras.laserBeamColor.copy(alpha = 0.22f), radius = radarR * 0.5f, center = Offset(rx, ry), style = Stroke(1.dp.toPx()))

            val radarScale = radarR / 4.8f
            for (p in previewPoints) {
                val px = rx + (p.x - cameraPos.x) * radarScale
                val py = ry + (p.z - cameraPos.z) * radarScale
                if ((px - rx) * (px - rx) + (py - ry) * (py - ry) <= radarR * radarR) {
                    drawCircle(color = extras.laserBeamColor.copy(alpha = 0.82f), radius = 2.0f, center = Offset(px, py))
                }
            }
            if (cornerAnchors.isNotEmpty()) {
                for (i in cornerAnchors.indices) {
                    val a = cornerAnchors[i]
                    val ax = rx + (a.x - cameraPos.x) * radarScale
                    val ay = ry + (a.z - cameraPos.z) * radarScale
                    if (i + 1 < cornerAnchors.size) {
                        val b = cornerAnchors[i + 1]
                        drawLine(
                            extras.cropCageColor,
                            Offset(ax, ay),
                            Offset(rx + (b.x - cameraPos.x) * radarScale, ry + (b.z - cameraPos.z) * radarScale),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    drawCircle(color = extras.cropCageColor, radius = 4f, center = Offset(ax, ay))
                }
            }
            val headX = rx + sin(yawRad) * (radarR * 0.78f)
            val headY = ry - cos(yawRad) * (radarR * 0.78f)
            drawLine(color = Color.White, start = Offset(rx, ry), end = Offset(headX, headY), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }

        // 5. Crisp Rangefinder Reticle (clean without distracting fake green scan bar)
        val retR = 20.dp.toPx()
        val retColor = when {
            isScanning && reticleDistMeters != null -> Color(0xFF00E5A0)
            reticleSource == "DEPTH" -> extras.laserBeamColor
            else -> Color.White.copy(alpha = 0.85f)
        }
        drawCircle(color = retColor.copy(alpha = 0.55f), radius = retR, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
        val tick = 7.dp.toPx()
        drawLine(retColor, Offset(cx - retR - tick, cy), Offset(cx - retR + 3.dp.toPx(), cy), strokeWidth = 1.8.dp.toPx())
        drawLine(retColor, Offset(cx + retR - 3.dp.toPx(), cy), Offset(cx + retR + tick, cy), strokeWidth = 1.8.dp.toPx())
        drawLine(retColor, Offset(cx, cy - retR - tick), Offset(cx, cy - retR + 3.dp.toPx()), strokeWidth = 1.8.dp.toPx())
        drawLine(retColor, Offset(cx, cy + retR - 3.dp.toPx()), Offset(cx, cy + retR + tick), strokeWidth = 1.8.dp.toPx())
        drawCircle(color = extras.cropCageColor, radius = 3.dp.toPx(), center = Offset(cx, cy))
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
            Text("Use Corner Raycast Mode", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(8.dp))
        RetroButton(onClick = onBack, minHeight = 42.dp) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
