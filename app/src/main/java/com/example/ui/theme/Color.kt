package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class StudioThemeSkin(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val tagline: String,
    val isDark: Boolean
) {
    WINDOWS_98(
        id = "windows_98",
        displayName = "Windows 98 Classic",
        shortLabel = "Win98",
        tagline = "Classic 3D beveled dialog chrome, teal desktop & taskbar",
        isDark = false
    ),
    WINDOWS_VISTA(
        id = "windows_vista",
        displayName = "Windows Vista Aero",
        shortLabel = "Vista",
        tagline = "Translucent Aero glass frames, Aurora glow & glossy orb",
        isDark = true
    );

    companion object {
        fun fromId(id: String?): StudioThemeSkin =
            entries.firstOrNull { it.id == id } ?: WINDOWS_98
    }
}

// 1. Windows 98 Classic Palette
val Win98DesktopTeal = Color(0xFF008080)
val Win98DesktopTealDark = Color(0xFF006666)
val Win98Face = Color(0xFFC0C0C0)
val Win98FaceLight = Color(0xFFD4D0C8)
val Win98Highlight = Color(0xFFFFFFFF)
val Win98LightEdge = Color(0xFFDFDFDF)
val Win98Shadow = Color(0xFF808080)
val Win98DarkShadow = Color(0xFF000000)
val Win98TitleActiveStart = Color(0xFF000080)
val Win98TitleActiveEnd = Color(0xFF1084D0)
val Win98WellWhite = Color(0xFFFFFFFF)
val Win98SelectionBlue = Color(0xFF000080)
val Win98AccentCyan = Color(0xFF00B8D4)
val Win98AlertRed = Color(0xFFC00000)

val Win98ColorScheme = lightColorScheme(
    primary = Win98TitleActiveStart,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4F7),
    onPrimaryContainer = Color(0xFF000050),
    secondary = Color(0xFF006B6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E8),
    onSecondaryContainer = Color(0xFF003333),
    tertiary = Color(0xFF804000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE5CC),
    onTertiaryContainer = Color(0xFF402000),
    background = Win98DesktopTeal,
    onBackground = Color(0xFF000000),
    surface = Win98Face,
    onSurface = Color(0xFF000000),
    surfaceVariant = Win98FaceLight,
    onSurfaceVariant = Color(0xFF3A3A3A),
    surfaceContainer = Win98Face,
    surfaceContainerHigh = Win98LightEdge,
    surfaceContainerHighest = Win98WellWhite,
    outline = Win98Shadow,
    outlineVariant = Color(0xFFA0A0A0),
    error = Win98AlertRed,
    onError = Color(0xFFFFFFFF)
)

// 2. Windows Vista Aero Glass Palette
val VistaAuroraTop = Color(0xFF071E36)
val VistaAuroraMid = Color(0xFF0D4D61)
val VistaAuroraBottom = Color(0xFF0A2742)
val VistaAuroraGlow = Color(0xFF28B8A6)
val VistaGlassFrame = Color(0xCC1B3A54)
val VistaGlassBorder = Color(0x999CE7FF)
val VistaGlassInner = Color(0xEE101C29)
val VistaGlassSurface = Color(0xDD18293A)
val VistaGlassSurfaceLight = Color(0xE6223A52)
val VistaAeroCyan = Color(0xFF48D1FF)
val VistaAeroBlue = Color(0xFF1E88E5)
val VistaAeroEmerald = Color(0xFF34D399)
val VistaAeroCloseRed = Color(0xFFD94436)
val VistaOnSurface = Color(0xFFF0F8FF)
val VistaOnSurfaceMuted = Color(0xFFA8C6DC)

val VistaColorScheme = darkColorScheme(
    primary = VistaAeroCyan,
    onPrimary = Color(0xFF001E2B),
    primaryContainer = Color(0xFF0E4563),
    onPrimaryContainer = Color(0xFFCFF4FF),
    secondary = VistaAeroEmerald,
    onSecondary = Color(0xFF00291C),
    secondaryContainer = Color(0xFF0D4A3A),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF2D1900),
    tertiaryContainer = Color(0xFF523200),
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = VistaAuroraTop,
    onBackground = VistaOnSurface,
    surface = VistaGlassSurface,
    onSurface = VistaOnSurface,
    surfaceVariant = VistaGlassSurfaceLight,
    onSurfaceVariant = VistaOnSurfaceMuted,
    surfaceContainer = VistaGlassSurface,
    surfaceContainerHigh = VistaGlassSurfaceLight,
    surfaceContainerHighest = Color(0xFF2A4763),
    outline = Color(0xFF5A8FB0),
    outlineVariant = Color(0xFF2E516B),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2E0000)
)
