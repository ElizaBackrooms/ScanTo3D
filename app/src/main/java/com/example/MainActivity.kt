package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.StudioSettings
import com.example.engine.MeasurementUnit
import com.example.ui.LibraryViewModel
import com.example.ui.ScannerViewModel
import com.example.ui.StudioViewModel
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.LidarForgeTheme
import com.example.ui.theme.StudioThemeSkin
import kotlinx.coroutines.launch

const val ROUTE_LIBRARY = "library"
const val ROUTE_SCANNER = "scanner"
const val ROUTE_STUDIO = "studio/{scanId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer(applicationContext)
        setContent {
            LidarForgeApp(container = container)
        }
    }
}

@Composable
fun LidarForgeApp(container: AppContainer) {
    val settings by container.studioPreferences.settingsFlow
        .collectAsStateWithLifecycle(initialValue = StudioSettings())
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    val selectSkin: (StudioThemeSkin) -> Unit = { skin ->
        scope.launch { container.studioPreferences.setThemeSkin(skin) }
    }
    val selectUnit: (MeasurementUnit) -> Unit = { unit ->
        scope.launch { container.studioPreferences.setMeasurementUnit(unit) }
    }

    LidarForgeTheme(skin = settings.themeSkin) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_LIBRARY
        ) {
            composable(ROUTE_LIBRARY) {
                val vm: LibraryViewModel = viewModel(
                    factory = LibraryViewModel.factory(
                        repository = container.scanRepository,
                        preferences = container.studioPreferences,
                        readiness = container.sensorReadiness
                    )
                )
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                LibraryScreen(
                    uiState = uiState,
                    onStartScan = { navController.navigate(ROUTE_SCANNER) },
                    onOpenScan = { scanId -> navController.navigate("studio/$scanId") },
                    onSearchChange = vm::updateSearchQuery,
                    onSelectSort = vm::setSortOrder,
                    onSelectViewMode = vm::setViewMode,
                    onSelectSkin = vm::setThemeSkin,
                    onSelectUnit = vm::setMeasurementUnit,
                    onRenameScan = vm::renameScan,
                    onDeleteScan = vm::deleteScan
                )
            }

            composable(ROUTE_SCANNER) {
                val vm: ScannerViewModel = viewModel(
                    factory = ScannerViewModel.factory(container.scanRepository)
                )
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                ScannerScreen(
                    uiState = uiState,
                    liveAccumulator = vm.liveMeshAccumulator,
                    sensorReadiness = container.sensorReadiness,
                    currentSkin = settings.themeSkin,
                    currentUnit = settings.measurementUnit,
                    onSelectSkin = selectSkin,
                    onSelectUnit = selectUnit,
                    onBack = { navController.popBackStack() },
                    onToggleScan = vm::startOrToggleScan,
                    onPatchCaptured = vm::ingestMeshPatch,
                    onAnchorCorner = vm::anchorCorner,
                    onUndo = vm::undoLastCapture,
                    onClearScan = vm::clearScan,
                    onEyeHeightChange = vm::setEyeHeight,
                    onCeilingHeightChange = vm::setCeilingHeight,
                    onDensityChange = vm::setCaptureDensity,
                    onFinishScan = { sensorMode ->
                        vm.finishAndSaveScan(sensorMode = sensorMode) { newScanId ->
                            navController.navigate("studio/$newScanId") {
                                popUpTo(ROUTE_SCANNER) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(
                route = ROUTE_STUDIO,
                arguments = listOf(navArgument("scanId") { type = NavType.LongType })
            ) { backStackEntry ->
                val scanId = backStackEntry.arguments?.getLong("scanId") ?: 0L
                val vm: StudioViewModel = viewModel(
                    key = "studio_$scanId",
                    factory = StudioViewModel.factory(
                        scanId = scanId,
                        repository = container.scanRepository,
                        preferences = container.studioPreferences
                    )
                )
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                StudioScreen(
                    uiState = uiState,
                    viewModel = vm,
                    currentSkin = settings.themeSkin,
                    onSelectSkin = selectSkin,
                    onNewScan = { navController.navigate(ROUTE_SCANNER) },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
