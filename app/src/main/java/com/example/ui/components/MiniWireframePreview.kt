package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import com.example.ui.theme.LocalStudioSkinExtras
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders a crisp isometric 3D wireframe room preview proportional to a scan's real dimensions
 * (width × depth × height) styled with the active studio skin.
 */
@Composable
fun MiniWireframePreview(
    widthMeters: Float,
    depthMeters: Float,
    heightMeters: Float,
    seed: Long = 0L,
    modifier: Modifier = Modifier
) {
    val extras = LocalStudioSkinExtras.current
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(listOf(extras.viewportTop, extras.viewportBottom))
        )

        val maxDim = max(widthMeters, max(depthMeters, heightMeters)).coerceAtLeast(0.5f)
        val nw = (widthMeters / maxDim).coerceIn(0.35f, 1.0f)
        val nd = (depthMeters / maxDim).coerceIn(0.35f, 1.0f)
        val nh = (heightMeters / maxDim).coerceIn(0.30f, 0.95f)

        val yaw = 0.65f + ((seed % 5) * 0.08f)
        val pitch = 0.46f
        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosP = cos(pitch); val sinP = sin(pitch)

        val scale = min(size.width, size.height) * 0.48f
        val cx = size.width * 0.5f
        val cy = size.height * 0.56f

        fun proj(x: Float, y: Float, z: Float): Offset {
            val rx = x * cosY + z * sinY
            val rz1 = -x * sinY + z * cosY
            val ry = y * cosP - rz1 * sinP
            return Offset(cx + rx * scale, cy - ry * scale)
        }

        val hw = nw * 0.65f
        val hd = nd * 0.65f
        val y0 = -nh * 0.42f
        val y1 = nh * 0.58f

        // Subtle metric floor crosslines
        for (k in -2..2) {
            val t = k / 2f
            drawLine(
                extras.gridColor.copy(alpha = 0.35f),
                proj(-hw, y0, t * hd),
                proj(hw, y0, t * hd),
                strokeWidth = 1f
            )
            drawLine(
                extras.gridColor.copy(alpha = 0.35f),
                proj(t * hw, y0, -hd),
                proj(t * hw, y0, hd),
                strokeWidth = 1f
            )
        }

        val corners = arrayOf(
            proj(-hw, y0, -hd), proj(hw, y0, -hd), proj(hw, y0, hd), proj(-hw, y0, hd),
            proj(-hw, y1, -hd), proj(hw, y1, -hd), proj(hw, y1, hd), proj(-hw, y1, hd)
        )
        val edges = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7
        )
        for ((a, b) in edges) {
            drawLine(
                color = extras.wireframeColor.copy(alpha = 0.85f),
                start = corners[a],
                end = corners[b],
                strokeWidth = 2.0f,
                cap = StrokeCap.Round
            )
        }
        // Subtle interior laser scan sweep line
        drawLine(
            color = extras.cropCageColor.copy(alpha = 0.85f),
            start = proj(-hw, (y0 + y1) * 0.5f, -hd),
            end = proj(hw, (y0 + y1) * 0.5f, hd),
            strokeWidth = 1.6f
        )
        for (pt in corners) {
            drawCircle(extras.wireframeColor, radius = 3.2f, center = pt)
        }
    }
}
