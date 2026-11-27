package com.example.ui.components

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.engine.BoundingBox3D
import com.example.engine.CropBounds
import com.example.engine.MeasurementPin
import com.example.engine.MeasurementUnit
import com.example.engine.ShadingMode
import com.example.engine.SpatialMesh
import com.example.engine.Vec3
import com.example.ui.OrbitCameraState
import com.example.ui.theme.LocalStudioSkinExtras
import com.example.ui.theme.StudioSkinExtras
import com.example.ui.theme.StudioThemeSkin
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private data class ProjectedVertex(
    val sx: Float,
    val sy: Float,
    val camZ: Float,
    val visible: Boolean
)

private class ProjectedCamera(
    private val center: Vec3,
    private val dist: Float,
    private val cosYaw: Float,
    private val sinYaw: Float,
    private val cosPitch: Float,
    private val sinPitch: Float,
    private val focal: Float,
    private val halfW: Float,
    private val halfH: Float,
    private val panX: Float,
    private val panY: Float
) {
    fun project(x: Float, y: Float, z: Float): ProjectedVertex {
        val dx = x - center.x
        val dy = y - center.y
        val dz = z - center.z

        // Rotate around Y (yaw)
        val rx = dx * cosYaw + dz * sinYaw
        val rz1 = -dx * sinYaw + dz * cosYaw
        // Rotate around X (pitch)
        val ry = dy * cosPitch - rz1 * sinPitch
        val rz = dy * sinPitch + rz1 * cosPitch

        val camZ = rz + dist
        if (camZ <= 0.15f) return ProjectedVertex(0f, 0f, camZ, false)

        val inv = focal / camZ
        val sx = halfW + panX + rx * inv
        val sy = halfH + panY - ry * inv
        return ProjectedVertex(sx, sy, camZ, true)
    }

    fun projectVec(v: Vec3): ProjectedVertex = project(v.x, v.y, v.z)
}

@Composable
fun Viewport3DCanvas(
    mesh: SpatialMesh,
    fullBounds: BoundingBox3D,
    camera: OrbitCameraState,
    shadingMode: ShadingMode,
    cropBounds: CropBounds,
    showCropCage: Boolean,
    measurements: List<MeasurementPin>,
    pendingMeasureStart: Vec3?,
    measurementUnit: MeasurementUnit,
    isMeasureMode: Boolean,
    onOrbit: (deltaYaw: Float, deltaPitch: Float) -> Unit,
    onPanAndZoom: (deltaPanX: Float, deltaPanY: Float, zoomFactor: Float) -> Unit,
    onDoubleTapReset: () -> Unit,
    onPickMeasurePoint: (Vec3) -> Unit,
    modifier: Modifier = Modifier
) {
    val extras = LocalStudioSkinExtras.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val latestMesh by rememberUpdatedState(mesh)
    val latestBounds by rememberUpdatedState(fullBounds)
    val latestCamera by rememberUpdatedState(camera)
    val latestMeasureMode by rememberUpdatedState(isMeasureMode)
    val latestOnOrbit by rememberUpdatedState(onOrbit)
    val latestOnPanZoom by rememberUpdatedState(onPanAndZoom)
    val latestOnReset by rememberUpdatedState(onDoubleTapReset)
    val latestOnPick by rememberUpdatedState(onPickMeasurePoint)

    val labelPaint = remember(extras.skin) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("viewport_3d_canvas")
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                var lastTapMs = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    var movedTotal = 0f
                    var multiTouchUsed = false

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            multiTouchUsed = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                latestOnPanZoom(panChange.x, panChange.y, zoomChange)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressed.size == 1 && !multiTouchUsed) {
                            val change = pressed[0]
                            val delta = change.position - change.previousPosition
                            movedTotal += delta.getDistance()
                            if (delta != Offset.Zero) {
                                latestOnOrbit(-delta.x * 0.0085f, delta.y * 0.0085f)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // Handle tap / double-tap if finger barely moved
                    if (movedTotal < 18f && !multiTouchUsed && canvasSize.width > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapMs < 300L) {
                            lastTapMs = 0L
                            latestOnReset()
                        } else {
                            lastTapMs = now
                            if (latestMeasureMode) {
                                val picked = pickNearestVertex(
                                    tapPos = startPos,
                                    mesh = latestMesh,
                                    bounds = latestBounds,
                                    camera = latestCamera,
                                    width = canvasSize.width.toFloat(),
                                    height = canvasSize.height.toFloat()
                                )
                                if (picked != null) {
                                    latestOnPick(picked)
                                }
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. Atmospheric CAD / Workstation viewport backdrop
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(extras.viewportTop, extras.viewportBottom)
                )
            )

            val refBounds = if (mesh.vertexCount > 0) fullBounds else BoundingBox3D()
            val cam = buildCamera(refBounds, camera, size.width, size.height)

            // 2. Metric floor reference grid
            drawFloorGrid(cam, refBounds, extras)

            // 3. Project all mesh vertices once per frame
            val vCount = mesh.vertexCount
            if (vCount > 0) {
                val projX = FloatArray(vCount)
                val projY = FloatArray(vCount)
                val projZ = FloatArray(vCount)
                val projVis = BooleanArray(vCount)
                var minZ = Float.MAX_VALUE
                var maxZ = -Float.MAX_VALUE

                for (i in 0 until vCount) {
                    val b = i * 3
                    val p = cam.project(mesh.vertices[b], mesh.vertices[b + 1], mesh.vertices[b + 2])
                    projX[i] = p.sx
                    projY[i] = p.sy
                    projZ[i] = p.camZ
                    projVis[i] = p.visible
                    if (p.visible) {
                        minZ = min(minZ, p.camZ)
                        maxZ = max(maxZ, p.camZ)
                    }
                }
                val zSpan = (maxZ - minZ).coerceAtLeast(0.1f)
                val cropWorld = cropBounds.toWorldBox(fullBounds)

                when (shadingMode) {
                    ShadingMode.SHADED, ShadingMode.THERMAL_DEPTH -> {
                        if (mesh.triangleCount > 0) {
                            drawTrianglesSorted(
                                mesh = mesh,
                                projX = projX,
                                projY = projY,
                                projZ = projZ,
                                projVis = projVis,
                                minZ = minZ,
                                zSpan = zSpan,
                                thermal = shadingMode == ShadingMode.THERMAL_DEPTH,
                                showCropDim = showCropCage && !cropBounds.isFull,
                                cropWorld = cropWorld,
                                extras = extras
                            )
                        } else {
                            drawPointCloud(
                                mesh, projX, projY, projZ, projVis, minZ, zSpan,
                                thermal = shadingMode == ShadingMode.THERMAL_DEPTH,
                                showCropDim = showCropCage && !cropBounds.isFull,
                                cropWorld = cropWorld,
                                extras = extras
                            )
                        }
                    }
                    ShadingMode.WIREFRAME -> {
                        if (mesh.triangleCount > 0) {
                            drawWireframe(
                                mesh, projX, projY, projVis,
                                showCropDim = showCropCage && !cropBounds.isFull,
                                cropWorld = cropWorld,
                                extras = extras
                            )
                        } else {
                            drawPointCloud(
                                mesh, projX, projY, projZ, projVis, minZ, zSpan,
                                thermal = false,
                                showCropDim = showCropCage && !cropBounds.isFull,
                                cropWorld = cropWorld,
                                extras = extras
                            )
                        }
                    }
                    ShadingMode.POINT_CLOUD -> {
                        drawPointCloud(
                            mesh, projX, projY, projZ, projVis, minZ, zSpan,
                            thermal = false,
                            showCropDim = showCropCage && !cropBounds.isFull,
                            cropWorld = cropWorld,
                            extras = extras
                        )
                    }
                }
            }

            // 4. 3D Crop Box Cage
            if (showCropCage) {
                drawCropBoxCage(cam, cropBounds.toWorldBox(fullBounds), extras)
            }

            // 5. 3D Measurements & Pending Pin
            drawMeasurements(cam, measurements, pendingMeasureStart, measurementUnit, extras, labelPaint)

            // 6. Top-Right XYZ Orientation Axis Gizmo
            drawAxisGizmo(camera, extras, labelPaint)
        }
    }
}

private fun buildCamera(
    bounds: BoundingBox3D,
    camera: OrbitCameraState,
    width: Float,
    height: Float
): ProjectedCamera {
    val radius = bounds.radius.coerceAtLeast(1.2f)
    val dist = (radius * 2.75f / camera.zoom).coerceIn(0.65f, 35f)
    val focal = min(width, height) * 0.92f
    return ProjectedCamera(
        center = bounds.center,
        dist = dist,
        cosYaw = cos(camera.yawRad),
        sinYaw = sin(camera.yawRad),
        cosPitch = cos(camera.pitchRad),
        sinPitch = sin(camera.pitchRad),
        focal = focal,
        halfW = width * 0.5f,
        halfH = height * 0.5f,
        panX = camera.panX,
        panY = camera.panY
    )
}

private fun pickNearestVertex(
    tapPos: Offset,
    mesh: SpatialMesh,
    bounds: BoundingBox3D,
    camera: OrbitCameraState,
    width: Float,
    height: Float
): Vec3? {
    if (mesh.vertexCount == 0) return null
    val cam = buildCamera(bounds, camera, width, height)
    var bestIdx = -1
    var bestScore = Float.MAX_VALUE
    val maxTapRadiusPx = 96f
    val maxTapRadiusSq = maxTapRadiusPx * maxTapRadiusPx

    for (i in 0 until mesh.vertexCount) {
        val b = i * 3
        val p = cam.project(mesh.vertices[b], mesh.vertices[b + 1], mesh.vertices[b + 2])
        if (!p.visible) continue
        val dx = p.sx - tapPos.x
        val dy = p.sy - tapPos.y
        val dSq = dx * dx + dy * dy
        if (dSq < maxTapRadiusSq) {
            // Bias slightly toward closer vertices when overlapping
            val score = dSq + p.camZ * 14f
            if (score < bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
    }
    return if (bestIdx >= 0) mesh.vertexAt(bestIdx) else null
}

private fun DrawScope.drawBlueprintBackdropGrid(extras: StudioSkinExtras) {
    val step = 28.dp.toPx()
    val lineColor = extras.gridColor.copy(alpha = 0.14f)
    var x = 0f
    while (x < size.width) {
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawFloorGrid(cam: ProjectedCamera, bounds: BoundingBox3D, extras: StudioSkinExtras) {
    val halfExtent = max(bounds.width, bounds.depth).coerceAtLeast(3.0f) * 0.75f
    val gridSteps = 8
    val floorY = bounds.minY
    val cx = bounds.center.x
    val cz = bounds.center.z

    for (i in -gridSteps..gridSteps) {
        val frac = i.toFloat() / gridSteps
        val offset = frac * halfExtent
        val isMajor = i == 0
        val alpha = if (isMajor) 0.55f else 0.22f
        val stroke = if (isMajor) 1.6f else 1.0f

        val p1 = cam.project(cx - halfExtent, floorY, cz + offset)
        val p2 = cam.project(cx + halfExtent, floorY, cz + offset)
        if (p1.visible && p2.visible) {
            drawLine(
                color = if (isMajor) extras.gridAxisX.copy(alpha = 0.6f) else extras.gridColor.copy(alpha = alpha),
                start = Offset(p1.sx, p1.sy),
                end = Offset(p2.sx, p2.sy),
                strokeWidth = stroke
            )
        }

        val p3 = cam.project(cx + offset, floorY, cz - halfExtent)
        val p4 = cam.project(cx + offset, floorY, cz + halfExtent)
        if (p3.visible && p4.visible) {
            drawLine(
                color = if (isMajor) extras.gridAxisZ.copy(alpha = 0.6f) else extras.gridColor.copy(alpha = alpha),
                start = Offset(p3.sx, p3.sy),
                end = Offset(p4.sx, p4.sy),
                strokeWidth = stroke
            )
        }
    }
}

private fun isInsideBox(mesh: SpatialMesh, idx: Int, box: BoundingBox3D): Boolean {
    val b = idx * 3
    val x = mesh.vertices[b]
    val y = mesh.vertices[b + 1]
    val z = mesh.vertices[b + 2]
    return x in box.minX..box.maxX && y in box.minY..box.maxY && z in box.minZ..box.maxZ
}

private fun thermalColor(t: Float, extras: StudioSkinExtras): Color {
    val clamped = t.coerceIn(0f, 1f)
    return if (clamped < 0.5f) {
        lerp(extras.thermalNear, extras.thermalMid, clamped * 2f)
    } else {
        lerp(extras.thermalMid, extras.thermalFar, (clamped - 0.5f) * 2f)
    }
}

private fun DrawScope.drawTrianglesSorted(
    mesh: SpatialMesh,
    projX: FloatArray,
    projY: FloatArray,
    projZ: FloatArray,
    projVis: BooleanArray,
    minZ: Float,
    zSpan: Float,
    thermal: Boolean,
    showCropDim: Boolean,
    cropWorld: BoundingBox3D,
    extras: StudioSkinExtras
) {
    val triCount = mesh.triangleCount
    // Keep up to 10,000 contiguous visible triangles (avoids Swiss-cheese skipping)
    val maxDrawn = 10_000
    val order = ArrayList<Int>(min(triCount, maxDrawn))
    val depths = FloatArray(triCount)

    // First collect all on-screen visible triangles
    val stride = if (triCount > maxDrawn * 2) (triCount / maxDrawn).coerceAtLeast(1) else 1
    var t = 0
    while (t < triCount) {
        val base = t * 3
        val i0 = mesh.indices[base]
        val i1 = mesh.indices[base + 1]
        val i2 = mesh.indices[base + 2]
        if (projVis[i0] && projVis[i1] && projVis[i2]) {
            val x0 = projX[i0]; val y0 = projY[i0]
            val x1 = projX[i1]; val y1 = projY[i1]
            val x2 = projX[i2]; val y2 = projY[i2]
            if (!(max(x0, max(x1, x2)) < -30f || min(x0, min(x1, x2)) > size.width + 30f ||
                    max(y0, max(y1, y2)) < -30f || min(y0, min(y1, y2)) > size.height + 30f)
            ) {
                depths[t] = (projZ[i0] + projZ[i1] + projZ[i2]) * 0.3333f
                order.add(t)
                if (order.size >= maxDrawn) break
            }
        }
        t += stride
    }
    order.sortWith { a, b -> depths[b].compareTo(depths[a]) } // painter's back-to-front

    val lightDir = Vec3(0.45f, 0.78f, 0.43f).normalize()
    val path = Path()
    val edgeAlpha = if (thermal) 0.16f else 0.06f

    for (triIdx in order) {
        val base = triIdx * 3
        val i0 = mesh.indices[base]
        val i1 = mesh.indices[base + 1]
        val i2 = mesh.indices[base + 2]

        val x0 = projX[i0]; val y0 = projY[i0]
        val x1 = projX[i1]; val y1 = projY[i1]
        val x2 = projX[i2]; val y2 = projY[i2]

        val nx = (mesh.normals[i0 * 3] + mesh.normals[i1 * 3] + mesh.normals[i2 * 3]) * 0.3333f
        val ny = (mesh.normals[i0 * 3 + 1] + mesh.normals[i1 * 3 + 1] + mesh.normals[i2 * 3 + 1]) * 0.3333f
        val nz = (mesh.normals[i0 * 3 + 2] + mesh.normals[i1 * 3 + 2] + mesh.normals[i2 * 3 + 2]) * 0.3333f
        val ndotl = abs(nx * lightDir.x + ny * lightDir.y + nz * lightDir.z)
        val shade = (0.58f + 0.42f * ndotl).coerceIn(0.48f, 1.0f)

        val inside = !showCropDim || (isInsideBox(mesh, i0, cropWorld) && isInsideBox(mesh, i1, cropWorld) && isInsideBox(mesh, i2, cropWorld))

        val fillColor = if (thermal) {
            val normD = ((depths[triIdx] - minZ) / zSpan).coerceIn(0f, 1f)
            thermalColor(normD, extras).copy(alpha = if (inside) 0.88f else 0.16f)
        } else {
            val c0 = mesh.colors[i0]
            val c1 = mesh.colors[i1]
            val c2 = mesh.colors[i2]
            val r = (((AndroidColor.red(c0) + AndroidColor.red(c1) + AndroidColor.red(c2)) / 3f) * shade).roundToInt().coerceIn(0, 255)
            val g = (((AndroidColor.green(c0) + AndroidColor.green(c1) + AndroidColor.green(c2)) / 3f) * shade).roundToInt().coerceIn(0, 255)
            val b = (((AndroidColor.blue(c0) + AndroidColor.blue(c1) + AndroidColor.blue(c2)) / 3f) * shade).roundToInt().coerceIn(0, 255)
            Color(r, g, b, if (inside) 250 else 42)
        }

        path.reset()
        path.moveTo(x0, y0)
        path.lineTo(x1, y1)
        path.lineTo(x2, y2)
        path.close()
        drawPath(path, color = fillColor)
        if (inside) {
            drawPath(
                path,
                color = extras.wireframeColor.copy(alpha = edgeAlpha),
                style = Stroke(width = 0.65f)
            )
        }
    }
}

private fun DrawScope.drawWireframe(
    mesh: SpatialMesh,
    projX: FloatArray,
    projY: FloatArray,
    projVis: BooleanArray,
    showCropDim: Boolean,
    cropWorld: BoundingBox3D,
    extras: StudioSkinExtras
) {
    val triCount = mesh.triangleCount
    val stride = (triCount / 4000).coerceAtLeast(1)
    var t = 0
    while (t < triCount) {
        val base = t * 3
        val i0 = mesh.indices[base]
        val i1 = mesh.indices[base + 1]
        val i2 = mesh.indices[base + 2]
        if (projVis[i0] && projVis[i1] && projVis[i2]) {
            val inside = !showCropDim || (isInsideBox(mesh, i0, cropWorld) && isInsideBox(mesh, i1, cropWorld) && isInsideBox(mesh, i2, cropWorld))
            val color = extras.wireframeColor.copy(alpha = if (inside) 0.82f else 0.12f)
            val p0 = Offset(projX[i0], projY[i0])
            val p1 = Offset(projX[i1], projY[i1])
            val p2 = Offset(projX[i2], projY[i2])
            drawLine(color, p0, p1, strokeWidth = 1.25f)
            drawLine(color, p1, p2, strokeWidth = 1.25f)
            drawLine(color, p2, p0, strokeWidth = 1.25f)
        }
        t += stride
    }
}

private fun DrawScope.drawPointCloud(
    mesh: SpatialMesh,
    projX: FloatArray,
    projY: FloatArray,
    projZ: FloatArray,
    projVis: BooleanArray,
    minZ: Float,
    zSpan: Float,
    thermal: Boolean,
    showCropDim: Boolean,
    cropWorld: BoundingBox3D,
    extras: StudioSkinExtras
) {
    val vCount = mesh.vertexCount
    val stride = (vCount / 6500).coerceAtLeast(1)
    var i = 0
    while (i < vCount) {
        if (projVis[i]) {
            val sx = projX[i]
            val sy = projY[i]
            if (sx >= -10f && sx <= size.width + 10f && sy >= -10f && sy <= size.height + 10f) {
                val inside = !showCropDim || isInsideBox(mesh, i, cropWorld)
                val normD = ((projZ[i] - minZ) / zSpan).coerceIn(0f, 1f)
                val dotRadius = (4.8f - normD * 2.2f).coerceIn(2.0f, 5.0f)
                val col = if (thermal) {
                    thermalColor(normD, extras).copy(alpha = if (inside) 0.95f else 0.16f)
                } else {
                    val argb = mesh.colors[i]
                    Color(
                        AndroidColor.red(argb),
                        AndroidColor.green(argb),
                        AndroidColor.blue(argb),
                        if (inside) 245 else 42
                    )
                }
                drawCircle(color = col, radius = dotRadius, center = Offset(sx, sy))
            }
        }
        i += stride
    }
}

private fun DrawScope.drawCropBoxCage(cam: ProjectedCamera, box: BoundingBox3D, extras: StudioSkinExtras) {
    val corners = arrayOf(
        Vec3(box.minX, box.minY, box.minZ),
        Vec3(box.maxX, box.minY, box.minZ),
        Vec3(box.maxX, box.minY, box.maxZ),
        Vec3(box.minX, box.minY, box.maxZ),
        Vec3(box.minX, box.maxY, box.minZ),
        Vec3(box.maxX, box.maxY, box.minZ),
        Vec3(box.maxX, box.maxY, box.maxZ),
        Vec3(box.minX, box.maxY, box.maxZ)
    )
    val proj = corners.map { cam.projectVec(it) }
    val edges = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 0,
        4 to 5, 5 to 6, 6 to 7, 7 to 4,
        0 to 4, 1 to 5, 2 to 6, 3 to 7
    )
    for ((a, b) in edges) {
        val pa = proj[a]; val pb = proj[b]
        if (pa.visible && pb.visible) {
            drawLine(
                color = extras.cropCageColor.copy(alpha = 0.25f),
                start = Offset(pa.sx, pa.sy),
                end = Offset(pb.sx, pb.sy),
                strokeWidth = 6.5f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = extras.cropCageColor,
                start = Offset(pa.sx, pa.sy),
                end = Offset(pb.sx, pb.sy),
                strokeWidth = 2.4f,
                cap = StrokeCap.Round
            )
        }
    }
    for (p in proj) {
        if (p.visible) {
            drawCircle(color = extras.cropCageColor, radius = 5.5f, center = Offset(p.sx, p.sy))
            drawCircle(color = Color.White, radius = 2.2f, center = Offset(p.sx, p.sy))
        }
    }
}

private fun DrawScope.drawMeasurements(
    cam: ProjectedCamera,
    measurements: List<MeasurementPin>,
    pendingStart: Vec3?,
    unit: MeasurementUnit,
    extras: StudioSkinExtras,
    labelPaint: Paint
) {
    val accent = extras.measurementColor
    val pillBg = Color(0xE809111B)
    val pillText = Color.White

    if (pendingStart != null) {
        val p = cam.projectVec(pendingStart)
        if (p.visible) {
            drawCircle(accent.copy(alpha = 0.35f), radius = 16f, center = Offset(p.sx, p.sy))
            drawCircle(accent, radius = 7f, center = Offset(p.sx, p.sy))
            drawCircle(Color.White, radius = 3f, center = Offset(p.sx, p.sy))
        }
    }

    for (pin in measurements) {
        val pa = cam.projectVec(pin.start)
        val pb = cam.projectVec(pin.end)
        if (!pa.visible || !pb.visible) continue

        drawLine(
            color = accent.copy(alpha = 0.28f),
            start = Offset(pa.sx, pa.sy),
            end = Offset(pb.sx, pb.sy),
            strokeWidth = 8f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = accent,
            start = Offset(pa.sx, pa.sy),
            end = Offset(pb.sx, pb.sy),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )
        drawCircle(accent, radius = 7f, center = Offset(pa.sx, pa.sy))
        drawCircle(Color.White, radius = 2.8f, center = Offset(pa.sx, pa.sy))
        drawCircle(accent, radius = 7f, center = Offset(pb.sx, pb.sy))
        drawCircle(Color.White, radius = 2.8f, center = Offset(pb.sx, pb.sy))

        val midX = (pa.sx + pb.sx) * 0.5f
        val midY = (pa.sy + pb.sy) * 0.5f
        val text = "${pin.label} ${unit.format(pin.distanceMeters)}"
        val textW = labelPaint.measureText(text)
        val padH = 14f
        val boxW = textW + padH * 2f
        val boxH = 40f

        drawRoundRect(
            color = pillBg,
            topLeft = Offset(midX - boxW * 0.5f, midY - boxH * 0.5f),
            size = Size(boxW, boxH),
            cornerRadius = CornerRadius(12f, 12f)
        )
        drawRoundRect(
            color = accent,
            topLeft = Offset(midX - boxW * 0.5f, midY - boxH * 0.5f),
            size = Size(boxW, boxH),
            cornerRadius = CornerRadius(12f, 12f),
            style = Stroke(width = 2f)
        )
        labelPaint.color = pillText.toArgb()
        drawContext.canvas.nativeCanvas.drawText(text, midX, midY + 9f, labelPaint)
    }
}

private fun DrawScope.drawAxisGizmo(
    camera: OrbitCameraState,
    extras: StudioSkinExtras,
    labelPaint: Paint
) {
    val gizmoR = 30.dp.toPx()
    val cx = size.width - gizmoR - 16.dp.toPx()
    val cy = gizmoR + 16.dp.toPx()

    val bg = Color(0xBB0B141E)
    drawCircle(color = bg, radius = gizmoR + 6.dp.toPx(), center = Offset(cx, cy))
    drawCircle(
        color = extras.cardBorder.copy(alpha = 0.7f),
        radius = gizmoR + 6.dp.toPx(),
        center = Offset(cx, cy),
        style = Stroke(width = 1.2.dp.toPx())
    )

    val cosY = cos(camera.yawRad)
    val sinY = sin(camera.yawRad)
    val cosP = cos(camera.pitchRad)
    val sinP = sin(camera.pitchRad)

    data class AxisVec(val label: String, val x: Float, val y: Float, val z: Float, val color: Color)

    val axes = listOf(
        AxisVec("X", 1f, 0f, 0f, extras.gridAxisX),
        AxisVec("Y", 0f, 1f, 0f, extras.gridAxisY),
        AxisVec("Z", 0f, 0f, 1f, extras.gridAxisZ)
    ).map { a ->
        val rx = a.x * cosY + a.z * sinY
        val rz1 = -a.x * sinY + a.z * cosY
        val ry = a.y * cosP - rz1 * sinP
        val rz = a.y * sinP + rz1 * cosP
        Triple(a, Offset(cx + rx * gizmoR * 0.78f, cy - ry * gizmoR * 0.78f), rz)
    }.sortedBy { it.third }

    val savedSize = labelPaint.textSize
    labelPaint.textSize = 22f
    for ((axis, end, _) in axes) {
        drawLine(axis.color, Offset(cx, cy), end, strokeWidth = 2.6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(axis.color, radius = 4.dp.toPx(), center = end)
        labelPaint.color = axis.color.toArgb()
        val labelPos = Offset(
            cx + (end.x - cx) * 1.28f,
            cy + (end.y - cy) * 1.28f + 7f
        )
        drawContext.canvas.nativeCanvas.drawText(axis.label, labelPos.x, labelPos.y, labelPaint)
    }
    labelPaint.textSize = savedSize
}
