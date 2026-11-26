package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.engine.MeasurementUnit
import com.example.ui.theme.LocalStudioSkinExtras
import com.example.ui.theme.StudioThemeSkin
import com.example.ui.theme.TelemetryTextStyle
import com.example.ui.theme.VistaAeroBlue
import com.example.ui.theme.VistaAeroCloseRed
import com.example.ui.theme.VistaAeroCyan
import com.example.ui.theme.VistaAuroraBottom
import com.example.ui.theme.VistaAuroraGlow
import com.example.ui.theme.VistaAuroraMid
import com.example.ui.theme.VistaAuroraTop
import com.example.ui.theme.VistaGlassBorder
import com.example.ui.theme.VistaGlassFrame
import com.example.ui.theme.VistaGlassInner
import com.example.ui.theme.Win98DarkShadow
import com.example.ui.theme.Win98DesktopTeal
import com.example.ui.theme.Win98DesktopTealDark
import com.example.ui.theme.Win98Face
import com.example.ui.theme.Win98FaceLight
import com.example.ui.theme.Win98Highlight
import com.example.ui.theme.Win98LightEdge
import com.example.ui.theme.Win98Shadow
import com.example.ui.theme.Win98TitleActiveEnd
import com.example.ui.theme.Win98TitleActiveStart
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RetroMenuEntry(
    val label: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val isDivider: Boolean = false,
    val shortcut: String? = null,
    val onClick: () -> Unit = {}
)

data class RetroMenuCategory(
    val title: String,
    val entries: List<RetroMenuEntry>
)

fun Modifier.win98RaisedBevel(pressed: Boolean = false): Modifier = this.drawWithContent {
    drawContent()
    val p1 = 1.dp.toPx()
    val topOuter = if (pressed) Win98DarkShadow else Win98Highlight
    val topInner = if (pressed) Win98Shadow else Win98LightEdge
    val botInner = if (pressed) Win98LightEdge else Win98Shadow
    val botOuter = if (pressed) Win98Highlight else Win98DarkShadow

    drawLine(topOuter, Offset(0f, p1 * 0.5f), Offset(size.width, p1 * 0.5f), p1)
    drawLine(topOuter, Offset(p1 * 0.5f, 0f), Offset(p1 * 0.5f, size.height), p1)
    drawLine(topInner, Offset(p1, p1 * 1.5f), Offset(size.width - p1, p1 * 1.5f), p1)
    drawLine(topInner, Offset(p1 * 1.5f, p1), Offset(p1 * 1.5f, size.height - p1), p1)
    drawLine(botInner, Offset(p1, size.height - p1 * 1.5f), Offset(size.width - p1, size.height - p1 * 1.5f), p1)
    drawLine(botInner, Offset(size.width - p1 * 1.5f, p1), Offset(size.width - p1 * 1.5f, size.height - p1), p1)
    drawLine(botOuter, Offset(0f, size.height - p1 * 0.5f), Offset(size.width, size.height - p1 * 0.5f), p1)
    drawLine(botOuter, Offset(size.width - p1 * 0.5f, 0f), Offset(size.width - p1 * 0.5f, size.height), p1)
}

fun Modifier.win98SunkenBevel(): Modifier = this.drawWithContent {
    drawContent()
    val p1 = 1.dp.toPx()
    drawLine(Win98Shadow, Offset(0f, p1 * 0.5f), Offset(size.width, p1 * 0.5f), p1)
    drawLine(Win98Shadow, Offset(p1 * 0.5f, 0f), Offset(p1 * 0.5f, size.height), p1)
    drawLine(Win98DarkShadow, Offset(p1, p1 * 1.5f), Offset(size.width - p1, p1 * 1.5f), p1)
    drawLine(Win98DarkShadow, Offset(p1 * 1.5f, p1), Offset(p1 * 1.5f, size.height - p1), p1)
    drawLine(Win98LightEdge, Offset(p1, size.height - p1 * 1.5f), Offset(size.width - p1, size.height - p1 * 1.5f), p1)
    drawLine(Win98LightEdge, Offset(size.width - p1 * 1.5f, p1), Offset(size.width - p1 * 1.5f, size.height - p1), p1)
    drawLine(Win98Highlight, Offset(0f, size.height - p1 * 0.5f), Offset(size.width, size.height - p1 * 0.5f), p1)
    drawLine(Win98Highlight, Offset(size.width - p1 * 0.5f, 0f), Offset(size.width - p1 * 0.5f, size.height), p1)
}

@Composable
fun DesktopWallpaperBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val extras = LocalStudioSkinExtras.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                when (extras.skin) {
                    StudioThemeSkin.WINDOWS_98 -> {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Win98DesktopTeal, Win98DesktopTealDark)
                            )
                        )
                    }
                    StudioThemeSkin.WINDOWS_VISTA -> {
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(VistaAuroraTop, VistaAuroraMid, VistaAuroraBottom),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height)
                            )
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(VistaAuroraGlow.copy(alpha = 0.42f), Color.Transparent),
                                center = Offset(size.width * 0.18f, size.height * 0.22f),
                                radius = size.width * 0.72f
                            ),
                            radius = size.width * 0.72f,
                            center = Offset(size.width * 0.18f, size.height * 0.22f)
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(VistaAeroCyan.copy(alpha = 0.30f), Color.Transparent),
                                center = Offset(size.width * 0.85f, size.height * 0.65f),
                                radius = size.width * 0.68f
                            ),
                            radius = size.width * 0.68f,
                            center = Offset(size.width * 0.85f, size.height * 0.65f)
                        )
                    }
                }
            },
        content = content
    )
}

@Composable
fun RetroWindowFrame(
    title: String,
    windowIcon: ImageVector = Icons.Filled.ViewInAr,
    onCloseClick: (() -> Unit)? = null,
    onMinimizeClick: (() -> Unit)? = null,
    onMaximizeClick: (() -> Unit)? = null,
    menuBar: (@Composable () -> Unit)? = null,
    statusBarText: String? = null,
    statusBarRight: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val extras = LocalStudioSkinExtras.current
    val isWin98 = extras.skin == StudioThemeSkin.WINDOWS_98
    val outerShape = if (isWin98) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp)

    val frameModifier = if (isWin98) {
        modifier
            .background(Win98Face)
            .win98RaisedBevel()
            .padding(3.dp)
    } else {
        modifier
            .clip(outerShape)
            .background(VistaGlassFrame)
            .border(1.25.dp, VistaGlassBorder, outerShape)
            .padding(4.dp)
    }

    Column(modifier = frameModifier) {
        RetroTitleBar(
            title = title,
            icon = windowIcon,
            onMinimizeClick = onMinimizeClick,
            onMaximizeClick = onMaximizeClick,
            onCloseClick = onCloseClick
        )

        if (menuBar != null) {
            menuBar()
        }

        val innerClientModifier = if (isWin98) {
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Win98Face)
        } else {
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(VistaGlassInner)
                .border(1.dp, Color(0x557AD8FF), RoundedCornerShape(6.dp))
        }
        Column(modifier = innerClientModifier, content = content)

        if (statusBarText != null || statusBarRight != null) {
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isWin98) Modifier.background(Win98Face).win98SunkenBevel()
                            else Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x990A1B2B))
                                .border(0.75.dp, Color(0x446EC9E8), RoundedCornerShape(4.dp))
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = statusBarText.orEmpty(),
                        style = TelemetryTextStyle,
                        color = if (isWin98) Color.Black else Color(0xFFD5EEFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (statusBarRight != null) {
                    Box(
                        modifier = Modifier
                            .then(
                                if (isWin98) Modifier.background(Win98Face).win98SunkenBevel()
                                else Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0x990A1B2B))
                                    .border(0.75.dp, Color(0x446EC9E8), RoundedCornerShape(4.dp))
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = statusBarRight,
                            style = TelemetryTextStyle,
                            color = if (isWin98) Win98TitleActiveStart else VistaAeroCyan,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetroTitleBar(
    title: String,
    icon: ImageVector,
    onMinimizeClick: (() -> Unit)?,
    onMaximizeClick: (() -> Unit)?,
    onCloseClick: (() -> Unit)?
) {
    val isWin98 = LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .drawBehind {
                if (isWin98) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Win98TitleActiveStart, Win98TitleActiveEnd)
                        )
                    )
                } else {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color(0x8882D8FF), Color(0x661C5880), Color(0xAA0C2840))
                        )
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color(0x4DFFFFFF), Color(0x0DFFFFFF))
                        ),
                        size = Size(size.width, size.height * 0.48f)
                    )
                }
            }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(if (isWin98) 2.dp else 3.dp)) {
                if (onMinimizeClick != null) {
                    WindowCaptionButton(Icons.Filled.Minimize, "Minimize window", false, onMinimizeClick)
                }
                if (onMaximizeClick != null) {
                    WindowCaptionButton(Icons.Filled.CropSquare, "Toggle layout", false, onMaximizeClick)
                }
                if (onCloseClick != null) {
                    WindowCaptionButton(Icons.Filled.Close, "Close window", true, onCloseClick)
                }
            }
        }
    }
}

@Composable
private fun WindowCaptionButton(
    icon: ImageVector,
    contentDescription: String,
    isClose: Boolean,
    onClick: () -> Unit
) {
    val isWin98 = LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    if (isWin98) {
        Box(
            modifier = Modifier
                .size(width = 26.dp, height = 24.dp)
                .background(Win98Face)
                .win98RaisedBevel(pressed = pressed)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = contentDescription, tint = Color.Black, modifier = Modifier.size(14.dp))
        }
    } else {
        val bgTop = if (isClose) Color(0xFFEE7B6E) else Color(0x888ED8FF)
        val bgBot = if (isClose) VistaAeroCloseRed else Color(0xAA173D5C)
        Box(
            modifier = Modifier
                .size(width = if (isClose) 36.dp else 28.dp, height = 22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.verticalGradient(listOf(bgTop, bgBot)))
                .border(0.8.dp, Color(0x99D8F6FF), RoundedCornerShape(4.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(13.dp))
        }
    }
}

/**
 * Authentic Windows 98 / Vista Pull-Down Menu Bar (`File`, `Edit`, `View`, `Tools`, `Export`)
 * where every menu opens a genuine classic beveled / Aero glass dropdown list!
 */
@Composable
fun RetroDropdownMenuBar(
    menus: List<RetroMenuCategory>,
    modifier: Modifier = Modifier
) {
    val isWin98 = LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98
    var openMenuIndex by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isWin98) Win98Face else Color(0xCC142B40))
            .drawBehind {
                drawLine(
                    color = if (isWin98) Win98Shadow else Color(0x556CC8EE),
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width, size.height - 1f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        menus.forEachIndexed { index, menu ->
            val expanded = openMenuIndex == index
            Box {
                Box(
                    modifier = Modifier
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(if (isWin98) 0.dp else 4.dp))
                        .background(
                            when {
                                expanded && isWin98 -> Win98TitleActiveStart
                                expanded && !isWin98 -> Color(0x6648D1FF)
                                else -> Color.Transparent
                            }
                        )
                        .clickable { openMenuIndex = if (expanded) null else index }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("menubar_${menu.title.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = menu.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (expanded) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            expanded -> Color.White
                            isWin98 -> Color.Black
                            else -> Color(0xFFE6F5FF)
                        }
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { openMenuIndex = null },
                    modifier = Modifier
                        .widthIn(min = 210.dp)
                        .then(
                            if (isWin98) Modifier.background(Win98Face).win98RaisedBevel().padding(2.dp)
                            else Modifier
                                .background(Color(0xF50E2233))
                                .border(1.dp, VistaGlassBorder, RoundedCornerShape(6.dp))
                                .padding(2.dp)
                        )
                ) {
                    menu.entries.forEach { entry ->
                        if (entry.isDivider) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp),
                                color = if (isWin98) Win98Shadow else Color(0x446EC9E8)
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 40.dp)
                                    .background(
                                        if (entry.checked && isWin98) Win98LightEdge
                                        else if (entry.checked) Color(0x3348D1FF)
                                        else Color.Transparent
                                    )
                                    .clickable(enabled = entry.enabled) {
                                        openMenuIndex = null
                                        entry.onClick()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.CenterStart) {
                                    if (entry.checked) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = if (isWin98) Win98TitleActiveStart else VistaAeroCyan,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (entry.checked) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        !entry.enabled -> if (isWin98) Win98Shadow else Color(0xFF5D7A90)
                                        isWin98 -> Color.Black
                                        else -> Color.White
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (entry.shortcut != null) {
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = entry.shortcut,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isWin98) Color(0xFF555555) else Color(0xFF8AB4CF)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Authentic Windows 98 / Vista ComboBox Dropdown Control:
 * Sunken value field + raised beveled/glass ▾ button opening a styled dropdown list.
 */
@Composable
fun <T> RetroComboBox(
    label: String? = null,
    selectedText: String,
    options: List<T>,
    optionLabel: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    testTagName: String = "retro_combobox"
) {
    val isWin98 = LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isWin98) Color(0xFF222222) else Color(0xFFAAD0E8)
            )
        }
        Box {
            val fieldModifier = if (isWin98) {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .background(Color.White)
                    .win98SunkenBevel()
                    .clickable { expanded = true }
                    .padding(2.dp)
                    .testTag(testTagName)
            } else {
                val shape = RoundedCornerShape(6.dp)
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clip(shape)
                    .background(Color(0xDD0D1E2E))
                    .border(1.dp, if (expanded) VistaAeroCyan else Color(0x775DAED4), shape)
                    .clickable { expanded = true }
                    .padding(2.dp)
                    .testTag(testTagName)
            }

            Row(
                modifier = fieldModifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isWin98) Color.Black else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 36.dp)
                        .then(
                            if (isWin98) Modifier.background(Win98Face).win98RaisedBevel(pressed = expanded)
                            else Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Brush.verticalGradient(listOf(Color(0x994A7DA3), Color(0xCC183750))))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Open dropdown",
                        tint = if (isWin98) Color.Black else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .widthIn(min = 190.dp)
                    .then(
                        if (isWin98) Modifier.background(Color.White).win98RaisedBevel().padding(2.dp)
                        else Modifier
                            .background(Color(0xF50E2233))
                            .border(1.dp, VistaGlassBorder, RoundedCornerShape(6.dp))
                            .padding(2.dp)
                    )
            ) {
                options.forEach { item ->
                    val chosen = isSelected(item)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 42.dp)
                            .background(
                                when {
                                    chosen && isWin98 -> Win98TitleActiveStart
                                    chosen && !isWin98 -> Color(0x6648D1FF)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable {
                                expanded = false
                                onSelect(item)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = optionLabel(item),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                chosen -> Color.White
                                isWin98 -> Color.Black
                                else -> Color.White
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (chosen) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RetroButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    active: Boolean = false,
    minHeight: Dp = 44.dp,
    content: @Composable RowScope.() -> Unit
) {
    val isWin98 = LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depressed = pressed || active

    if (isWin98) {
        val bgColor = when {
            !enabled -> Win98FaceLight
            accent -> Win98TitleActiveStart
            active -> Win98LightEdge
            else -> Win98Face
        }
        val contentColor = when {
            !enabled -> Win98Shadow
            accent -> Color.White
            else -> Color.Black
        }
        Box(
            modifier = modifier
                .heightIn(min = minHeight)
                .background(bgColor)
                .win98RaisedBevel(pressed = depressed)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides contentColor) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
            }
        }
    } else {
        val shape = RoundedCornerShape(6.dp)
        val brush = when {
            !enabled -> Brush.verticalGradient(listOf(Color(0x5522384C), Color(0x55142330)))
            accent -> Brush.verticalGradient(listOf(Color(0xFF66E0FF), VistaAeroBlue, Color(0xFF0E4D8A)))
            depressed -> Brush.verticalGradient(listOf(Color(0xCC2C84B5), Color(0xDD154A6E)))
            else -> Brush.verticalGradient(listOf(Color(0x993E6A8E), Color(0xCC1E3A54), Color(0xDD112436)))
        }
        val borderColor = if (accent || active) VistaAeroCyan else Color(0x887AC8E8)
        Box(
            modifier = modifier
                .heightIn(min = minHeight)
                .clip(shape)
                .background(brush)
                .border(1.dp, borderColor, shape)
                .drawWithContent {
                    drawContent()
                    drawRect(Color(0x28FFFFFF), topLeft = Offset.Zero, size = Size(size.width, size.height * 0.45f))
                }
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (enabled) Color.White else Color(0xFF6A8AA3)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
            }
        }
    }
}

@Composable
fun RetroSunkenWell(
    modifier: Modifier = Modifier,
    darkWell: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val isWin98 = LocalStudioSkinExtras.current.skin == StudioThemeSkin.WINDOWS_98
    if (isWin98) {
        Box(
            modifier = modifier
                .background(if (darkWell) Color(0xFF0E141B) else Color.White)
                .win98SunkenBevel()
                .padding(2.dp),
            content = content
        )
    } else {
        val shape = RoundedCornerShape(6.dp)
        Box(
            modifier = modifier
                .clip(shape)
                .background(if (darkWell) Color(0xEE08131E) else Color(0xCC0D1E2E))
                .border(1.dp, Color(0x6658A8CC), shape)
                .padding(2.dp),
            content = content
        )
    }
}

/**
 * Iconic Windows 98 / Vista Bottom Taskbar.
 * Hosts the ONE single theme toggle button (`98 / Vista`) in the System Tray so switching is always
 * 1 tap from any screen without cluttering the window body or menus.
 */
@Composable
fun RetroTaskbar(
    activeWindowTag: String,
    activeWindowTitle: String,
    currentSkin: StudioThemeSkin,
    onStartClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onScanClick: () -> Unit,
    onToggleSkin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isWin98 = currentSkin == StudioThemeSkin.WINDOWS_98
    var clockText by remember { mutableStateOf(formatTrayClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = formatTrayClock()
            delay(15_000L)
        }
    }

    val barModifier = if (isWin98) {
        modifier
            .fillMaxWidth()
            .background(Win98Face)
            .drawBehind {
                drawLine(Win98Highlight, Offset(0f, 0f), Offset(size.width, 0f), 1.5.dp.toPx())
                drawLine(Win98LightEdge, Offset(0f, 1.5.dp.toPx()), Offset(size.width, 1.5.dp.toPx()), 1.dp.toPx())
            }
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 5.dp)
    } else {
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xEE18354D), Color(0xF5091622), Color(0xFA050C14))
                )
            )
            .drawBehind {
                drawLine(Color(0x997CE2FF), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    }

    Row(
        modifier = barModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (isWin98) {
            RetroButton(
                onClick = onStartClick,
                minHeight = 40.dp,
                modifier = Modifier.testTag("taskbar_start_button")
            ) {
                Win98FlagIcon(modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Start", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF9DF7FF), VistaAeroCyan, VistaAeroBlue, Color(0xFF07223B))
                        )
                    )
                    .border(1.5.dp, Color(0xCCBDF4FF), CircleShape)
                    .clickable(onClick = onStartClick)
                    .testTag("taskbar_start_button"),
                contentAlignment = Alignment.Center
            ) {
                Win98FlagIcon(modifier = Modifier.size(18.dp))
            }
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskbarWindowPill(
                label = "Scans",
                icon = Icons.Filled.Folder,
                active = activeWindowTag == "library",
                isWin98 = isWin98,
                onClick = onLibraryClick,
                modifier = Modifier.weight(0.95f).testTag("taskbar_library_btn")
            )
            TaskbarWindowPill(
                label = if (activeWindowTag == "studio") activeWindowTitle else "Scanner",
                icon = if (activeWindowTag == "studio") Icons.Filled.ViewInAr else Icons.Filled.Radar,
                active = activeWindowTag == "scanner" || activeWindowTag == "studio",
                isWin98 = isWin98,
                onClick = { if (activeWindowTag != "studio") onScanClick() },
                modifier = Modifier.weight(1.05f).testTag("taskbar_active_btn")
            )
        }

        // Single dedicated Theme Switcher Button (Win98 <-> Vista)
        RetroButton(
            onClick = onToggleSkin,
            accent = !isWin98,
            minHeight = 40.dp,
            modifier = Modifier.testTag("theme_switch_button")
        ) {
            Text(
                text = if (isWin98) "Vista" else "Win98",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // System Tray Clock
        Box(
            modifier = Modifier
                .heightIn(min = 38.dp)
                .then(
                    if (isWin98) Modifier.background(Win98Face).win98SunkenBevel()
                    else Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x88081420))
                        .border(0.8.dp, Color(0x5564C4EA), RoundedCornerShape(6.dp))
                )
                .padding(horizontal = 7.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = clockText,
                style = TelemetryTextStyle,
                color = if (isWin98) Color.Black else Color.White
            )
        }
    }
}

@Composable
private fun TaskbarWindowPill(
    label: String,
    icon: ImageVector,
    active: Boolean,
    isWin98: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isWin98) {
        Box(
            modifier = modifier
                .heightIn(min = 40.dp)
                .background(if (active) Win98LightEdge else Win98Face)
                .win98RaisedBevel(pressed = active)
                .clickable(onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else {
        val shape = RoundedCornerShape(6.dp)
        Box(
            modifier = modifier
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(
                    if (active) Brush.verticalGradient(listOf(Color(0x9958CFFF), Color(0xAA175780)))
                    else Brush.verticalGradient(listOf(Color(0x552B4F6E), Color(0x77102436)))
                )
                .border(1.dp, if (active) VistaAeroCyan else Color(0x446CC8EE), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun Win98FlagIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val halfW = size.width * 0.46f
        val halfH = size.height * 0.46f
        val gap = size.width * 0.08f
        drawRect(Color(0xFFE53935), Offset(0f, 0f), Size(halfW, halfH))
        drawRect(Color(0xFF43A047), Offset(halfW + gap, 0f), Size(halfW, halfH))
        drawRect(Color(0xFF1E88E5), Offset(0f, halfH + gap), Size(halfW, halfH))
        drawRect(Color(0xFFFDD835), Offset(halfW + gap, halfH + gap), Size(halfW, halfH))
    }
}

/**
 * Classic Start Menu focused on workstation navigation, hardware diagnostics, and measurement units.
 */
@Composable
fun RetroStartMenuOverlay(
    currentSkin: StudioThemeSkin,
    currentUnit: MeasurementUnit,
    onDismiss: () -> Unit,
    onNewScan: () -> Unit,
    onGoLibrary: () -> Unit,
    onOpenSensorsInfo: (() -> Unit)? = null,
    onSelectUnit: (MeasurementUnit) -> Unit
) {
    val isWin98 = currentSkin == StudioThemeSkin.WINDOWS_98
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x55000000))
            .clickable(onClick = onDismiss)
            .navigationBarsPadding()
            .padding(start = 8.dp, bottom = 58.dp, end = 48.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        val menuShape = if (isWin98) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp)
        Row(
            modifier = Modifier
                .width(276.dp)
                .then(
                    if (isWin98) Modifier.background(Win98Face).win98RaisedBevel().padding(3.dp)
                    else Modifier
                        .clip(menuShape)
                        .background(Color(0xF0102538))
                        .border(1.5.dp, VistaGlassBorder, menuShape)
                        .padding(6.dp)
                )
                .clickable(enabled = false) {}
                .testTag("retro_start_menu")
        ) {
            if (isWin98) {
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(236.dp)
                        .background(Brush.verticalGradient(listOf(Win98TitleActiveStart, Win98TitleActiveEnd))),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text("98", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "LidarForge 3D Programs",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isWin98) Win98TitleActiveStart else VistaAeroCyan,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )

                StartMenuRow(Icons.Filled.CameraAlt, "New Room Scan...", isWin98) {
                    onDismiss(); onNewScan()
                }
                StartMenuRow(Icons.Filled.Folder, "Room Scans Explorer", isWin98) {
                    onDismiss(); onGoLibrary()
                }
                if (onOpenSensorsInfo != null) {
                    StartMenuRow(Icons.Filled.Sensors, "Sensor Diagnostics...", isWin98) {
                        onDismiss(); onOpenSensorsInfo()
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = if (isWin98) Win98Shadow else Color(0x446EC9E8)
                )

                Text(
                    text = "Display Units",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isWin98) Color(0xFF404040) else Color(0xFF8EC5E0),
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MeasurementUnit.entries.forEach { unit ->
                        RetroButton(
                            onClick = { onSelectUnit(unit); onDismiss() },
                            accent = unit == currentUnit,
                            minHeight = 38.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(unit.shortSuffix, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartMenuRow(
    icon: ImageVector,
    label: String,
    isWin98: Boolean,
    onClick: () -> Unit
) {
    val fg = if (isWin98) Color.Black else Color(0xFFEAF6FF)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(if (isWin98) 0.dp else 5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = fg)
    }
}

private fun formatTrayClock(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
