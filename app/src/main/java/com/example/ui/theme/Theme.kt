package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Immutable
data class StudioSkinExtras(
    val skin: StudioThemeSkin,
    val viewportTop: Color,
    val viewportBottom: Color,
    val gridColor: Color,
    val gridAxisX: Color,
    val gridAxisY: Color,
    val gridAxisZ: Color,
    val wireframeColor: Color,
    val cropCageColor: Color,
    val measurementColor: Color,
    val bevelHighlight: Color,
    val bevelShadow: Color,
    val cardBorder: Color,
    val laserBeamColor: Color,
    val thermalNear: Color,
    val thermalMid: Color,
    val thermalFar: Color,
    val titleBarStart: Color,
    val titleBarEnd: Color,
    val taskbarBg: Color,
    val windowFace: Color,
    val sunkenWellBg: Color
)

private val Win98Extras = StudioSkinExtras(
    skin = StudioThemeSkin.WINDOWS_98,
    viewportTop = Color(0xFF141B24),
    viewportBottom = Color(0xFF232F3D),
    gridColor = Color(0x4400E5D4),
    gridAxisX = Color(0xFFFF4D4D),
    gridAxisY = Color(0xFF00E676),
    gridAxisZ = Color(0xFF40C4FF),
    wireframeColor = Color(0xFF00E5D4),
    cropCageColor = Color(0xFFFFD600),
    measurementColor = Color(0xFFFFEA00),
    bevelHighlight = Win98Highlight,
    bevelShadow = Win98Shadow,
    cardBorder = Win98Shadow,
    laserBeamColor = Color(0xFF00FFCC),
    thermalNear = Color(0xFFFF3D5A),
    thermalMid = Color(0xFFFFB300),
    thermalFar = Color(0xFF00E5FF),
    titleBarStart = Win98TitleActiveStart,
    titleBarEnd = Win98TitleActiveEnd,
    taskbarBg = Win98Face,
    windowFace = Win98Face,
    sunkenWellBg = Win98WellWhite
)

private val VistaExtras = StudioSkinExtras(
    skin = StudioThemeSkin.WINDOWS_VISTA,
    viewportTop = Color(0xFF091B2C),
    viewportBottom = Color(0xFF14384E),
    gridColor = Color(0x4448D1FF),
    gridAxisX = Color(0xFFFF6E6E),
    gridAxisY = Color(0xFF34D399),
    gridAxisZ = Color(0xFF48D1FF),
    wireframeColor = Color(0xFF5CE1FF),
    cropCageColor = Color(0xFF34D399),
    measurementColor = Color(0xFFFFD54F),
    bevelHighlight = Color(0x88CCF3FF),
    bevelShadow = Color(0xAA040C14),
    cardBorder = Color(0x886EC9E8),
    laserBeamColor = Color(0xFF48D1FF),
    thermalNear = Color(0xFFFF5C77),
    thermalMid = Color(0xFFFFB74D),
    thermalFar = Color(0xFF48D1FF),
    titleBarStart = Color(0xCC1E5378),
    titleBarEnd = Color(0xCC0E2E47),
    taskbarBg = Color(0xEB0A1926),
    windowFace = VistaGlassSurface,
    sunkenWellBg = VistaGlassInner
)

val LocalStudioSkinExtras = staticCompositionLocalOf { Win98Extras }

private val Win98Shapes = Shapes(
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp)
)

private val VistaShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp)
)

@Composable
fun LidarForgeTheme(
    skin: StudioThemeSkin = StudioThemeSkin.WINDOWS_98,
    content: @Composable () -> Unit
) {
    val colorScheme = when (skin) {
        StudioThemeSkin.WINDOWS_98 -> Win98ColorScheme
        StudioThemeSkin.WINDOWS_VISTA -> VistaColorScheme
    }
    val extras = when (skin) {
        StudioThemeSkin.WINDOWS_98 -> Win98Extras
        StudioThemeSkin.WINDOWS_VISTA -> VistaExtras
    }
    val shapes = when (skin) {
        StudioThemeSkin.WINDOWS_98 -> Win98Shapes
        StudioThemeSkin.WINDOWS_VISTA -> VistaShapes
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // Dark status bar icons off since both Win98 teal desktop & Vista aurora are rich/deep backgrounds
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = skin == StudioThemeSkin.WINDOWS_98
            }
        }
    }

    CompositionLocalProvider(LocalStudioSkinExtras provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = shapes,
            content = content
        )
    }
}

@Composable
fun studioCardBorder(accent: Boolean = false): BorderStroke {
    val extras = LocalStudioSkinExtras.current
    val primary = MaterialTheme.colorScheme.primary
    return when (extras.skin) {
        StudioThemeSkin.WINDOWS_98 -> BorderStroke(
            width = if (accent) 2.dp else 1.dp,
            color = if (accent) primary else Win98Shadow
        )
        StudioThemeSkin.WINDOWS_VISTA -> BorderStroke(
            width = if (accent) 1.5.dp else 1.dp,
            brush = Brush.verticalGradient(
                listOf(
                    if (accent) VistaAeroCyan else extras.bevelHighlight,
                    extras.cardBorder.copy(alpha = 0.45f)
                )
            )
        )
    }
}

fun Modifier.studioPanelChrome(
    extras: StudioSkinExtras,
    cornerRadius: Dp = 0.dp
): Modifier = this.drawWithContent {
    drawContent()
    when (extras.skin) {
        StudioThemeSkin.WINDOWS_98 -> {
            // Classic 2px 3D raised bevel
            val px1 = 1.dp.toPx()
            val px2 = 2.dp.toPx()
            drawLine(Win98Highlight, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = px1)
            drawLine(Win98Highlight, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = px1)
            drawLine(Win98LightEdge, Offset(px1, px1), Offset(size.width - px1, px1), strokeWidth = px1)
            drawLine(Win98LightEdge, Offset(px1, px1), Offset(px1, size.height - px1), strokeWidth = px1)
            drawLine(Win98Shadow, Offset(px1, size.height - px2), Offset(size.width - px1, size.height - px2), strokeWidth = px1)
            drawLine(Win98Shadow, Offset(size.width - px2, px1), Offset(size.width - px2, size.height - px1), strokeWidth = px1)
            drawLine(Win98DarkShadow, Offset(0f, size.height - px1), Offset(size.width, size.height - px1), strokeWidth = px1)
            drawLine(Win98DarkShadow, Offset(size.width - px1, 0f), Offset(size.width - px1, size.height), strokeWidth = px1)
        }
        StudioThemeSkin.WINDOWS_VISTA -> {
            val r = cornerRadius.toPx()
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0x66FFFFFF), Color(0x18FFFFFF), Color(0x44000000))
                ),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.25.dp.toPx())
            )
        }
    }
}
