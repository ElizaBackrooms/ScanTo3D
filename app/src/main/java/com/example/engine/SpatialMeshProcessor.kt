package com.example.engine

import android.graphics.Color as AndroidColor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object SpatialMeshProcessor {

    private const val LFG_MAGIC = 0x4C464731 // "LFG1"

    /**
     * Reconstructs a clean indexed 3D room mesh from captured spatial points and/or anchored room corners.
     * Supports configurable voxel resolution (4 cm .. 24 cm) and optional Laplacian smoothing.
     */
    fun buildMesh(
        rawPoints: List<SpatialPoint>,
        cornerAnchors: List<Vec3>,
        ceilingHeightMeters: Float = 2.6f,
        voxelSizeCm: Int = 10,
        smoothSurface: Boolean = true,
        cropBounds: CropBounds = CropBounds()
    ): SpatialMesh {
        val voxelMeters = (voxelSizeCm.coerceIn(4, 28)) / 100f
        val verts = ArrayList<Float>(4096)
        val norms = ArrayList<Float>(4096)
        val cols = ArrayList<Int>(2048)
        val types = ArrayList<Byte>(2048)
        val tris = ArrayList<Int>(8192)

        // 1. Build crisp architectural floor, wall, and ceiling patches from any anchored room corners
        if (cornerAnchors.size >= 3) {
            appendArchitecturalShell(
                corners = cornerAnchors,
                ceilingHeight = ceilingHeightMeters.coerceIn(1.8f, 6.0f),
                subdivStep = voxelMeters.coerceAtLeast(0.18f),
                verts = verts,
                norms = norms,
                cols = cols,
                types = types,
                tris = tris
            )
        }

        // 2. Voxel-fuse captured camera/depth points and triangulate local surface neighborhoods
        if (rawPoints.isNotEmpty()) {
            appendVoxelizedPointMesh(
                rawPoints = rawPoints,
                voxelMeters = voxelMeters,
                smoothSurface = smoothSurface,
                verts = verts,
                norms = norms,
                cols = cols,
                types = types,
                tris = tris
            )
        }

        if (verts.isEmpty()) {
            return SpatialMesh.empty()
        }

        val fullMesh = SpatialMesh(
            vertices = verts.toFloatArray(),
            normals = norms.toFloatArray(),
            colors = cols.toIntArray(),
            surfaceTypes = types.toByteArray(),
            indices = tris.toIntArray(),
            bounds = computeBounds(verts)
        )

        return if (cropBounds.isFull) fullMesh else applyCrop(fullMesh, cropBounds)
    }

    /**
     * Non-destructively slices any SpatialMesh to the normalized CropBounds cage,
     * keeping vertices inside the box and remapping valid triangles cleanly.
     */
    fun applyCrop(mesh: SpatialMesh, cropBounds: CropBounds): SpatialMesh {
        if (mesh.vertexCount == 0 || cropBounds.isFull) return mesh

        val worldBox = cropBounds.toWorldBox(mesh.bounds)
        val vCount = mesh.vertexCount
        val remap = IntArray(vCount) { -1 }

        val newVerts = ArrayList<Float>(vCount * 3)
        val newNorms = ArrayList<Float>(vCount * 3)
        val newCols = ArrayList<Int>(vCount)
        val newTypes = ArrayList<Byte>(vCount)

        var nextIndex = 0
        for (i in 0 until vCount) {
            val base = i * 3
            val x = mesh.vertices[base]
            val y = mesh.vertices[base + 1]
            val z = mesh.vertices[base + 2]
            if (x >= worldBox.minX - 1e-4f && x <= worldBox.maxX + 1e-4f &&
                y >= worldBox.minY - 1e-4f && y <= worldBox.maxY + 1e-4f &&
                z >= worldBox.minZ - 1e-4f && z <= worldBox.maxZ + 1e-4f
            ) {
                remap[i] = nextIndex++
                newVerts.add(x)
                newVerts.add(y)
                newVerts.add(z)
                newNorms.add(mesh.normals[base])
                newNorms.add(mesh.normals[base + 1])
                newNorms.add(mesh.normals[base + 2])
                newCols.add(mesh.colors[i])
                newTypes.add(mesh.surfaceTypes[i])
            }
        }

        if (nextIndex == 0) return SpatialMesh.empty()

        val newTris = ArrayList<Int>(mesh.indices.size)
        var t = 0
        while (t + 2 < mesh.indices.size) {
            val a = remap[mesh.indices[t]]
            val b = remap[mesh.indices[t + 1]]
            val c = remap[mesh.indices[t + 2]]
            if (a >= 0 && b >= 0 && c >= 0) {
                newTris.add(a)
                newTris.add(b)
                newTris.add(c)
            }
            t += 3
        }

        return SpatialMesh(
            vertices = newVerts.toFloatArray(),
            normals = newNorms.toFloatArray(),
            colors = newCols.toIntArray(),
            surfaceTypes = newTypes.toByteArray(),
            indices = newTris.toIntArray(),
            bounds = computeBounds(newVerts)
        )
    }

    private fun appendArchitecturalShell(
        corners: List<Vec3>,
        ceilingHeight: Float,
        subdivStep: Float,
        verts: ArrayList<Float>,
        norms: ArrayList<Float>,
        cols: ArrayList<Int>,
        types: ArrayList<Byte>,
        tris: ArrayList<Int>
    ) {
        val floorColor = AndroidColor.rgb(92, 114, 138)
        val wallColorA = AndroidColor.rgb(168, 186, 206)
        val wallColorB = AndroidColor.rgb(148, 170, 194)
        val ceilingColor = AndroidColor.rgb(112, 130, 150)

        val n = corners.size
        var cx = 0f
        var cz = 0f
        var floorY = 0f
        for (c in corners) {
            cx += c.x
            cz += c.z
            floorY += c.y
        }
        cx /= n
        cz /= n
        floorY /= n
        val ceilY = floorY + ceilingHeight

        // Floor fan subdivided concentrically for clean density & Bryce heightmap fidelity
        val floorCenterIdx = addVertex(verts, norms, cols, types, cx, floorY, cz, 0f, 1f, 0f, floorColor, SpatialPoint.SURFACE_FLOOR)
        val ringCount = 3
        val ringIndices = Array(ringCount) { IntArray(n) }
        for (r in 1..ringCount) {
            val frac = r.toFloat() / ringCount
            for (i in 0 until n) {
                val px = cx + (corners[i].x - cx) * frac
                val pz = cz + (corners[i].z - cz) * frac
                ringIndices[r - 1][i] = addVertex(
                    verts, norms, cols, types,
                    px, floorY, pz, 0f, 1f, 0f, floorColor, SpatialPoint.SURFACE_FLOOR
                )
            }
        }
        for (i in 0 until n) {
            val next = (i + 1) % n
            tris.add(floorCenterIdx); tris.add(ringIndices[0][i]); tris.add(ringIndices[0][next])
            for (r in 1 until ringCount) {
                val a = ringIndices[r - 1][i]
                val b = ringIndices[r - 1][next]
                val c = ringIndices[r][next]
                val d = ringIndices[r][i]
                tris.add(a); tris.add(d); tris.add(c)
                tris.add(a); tris.add(c); tris.add(b)
            }
        }

        // Walls between consecutive anchored corners
        for (i in 0 until n) {
            val p0 = corners[i]
            val p1 = corners[(i + 1) % n]
            val dx = p1.x - p0.x
            val dz = p1.z - p0.z
            val wallLen = sqrt(dx * dx + dz * dz).coerceAtLeast(0.05f)
            // Inward wall normal pointing toward room center
            var nx = -dz / wallLen
            var nz = dx / wallLen
            val midX = (p0.x + p1.x) * 0.5f
            val midZ = (p0.z + p1.z) * 0.5f
            if (nx * (cx - midX) + nz * (cz - midZ) < 0f) {
                nx = -nx
                nz = -nz
            }

            val colsU = (wallLen / subdivStep).roundToInt().coerceIn(2, 16)
            val rowsV = (ceilingHeight / subdivStep).roundToInt().coerceIn(2, 12)
            val wallTint = if (i % 2 == 0) wallColorA else wallColorB

            val grid = Array(rowsV + 1) { IntArray(colsU + 1) }
            for (v in 0..rowsV) {
                val vf = v.toFloat() / rowsV
                val wy = floorY + ceilingHeight * vf
                // Slight vertical shading gradient for architectural legibility
                val shade = (0.86f + 0.14f * vf)
                val col = scaleColor(wallTint, shade)
                for (u in 0..colsU) {
                    val uf = u.toFloat() / colsU
                    val wx = p0.x + dx * uf
                    val wz = p0.z + dz * uf
                    grid[v][u] = addVertex(
                        verts, norms, cols, types,
                        wx, wy, wz, nx, 0f, nz, col, SpatialPoint.SURFACE_WALL
                    )
                }
            }
            for (v in 0 until rowsV) {
                for (u in 0 until colsU) {
                    val a = grid[v][u]
                    val b = grid[v][u + 1]
                    val c = grid[v + 1][u + 1]
                    val d = grid[v + 1][u]
                    tris.add(a); tris.add(b); tris.add(c)
                    tris.add(a); tris.add(c); tris.add(d)
                }
            }
        }

        // Open-top rim strip (subtle ceiling border frame so interior stays clearly visible in 3D orbit)
        val borderInset = 0.18f
        val outerCeil = IntArray(n)
        val innerCeil = IntArray(n)
        for (i in 0 until n) {
            val c = corners[i]
            outerCeil[i] = addVertex(verts, norms, cols, types, c.x, ceilY, c.z, 0f, -1f, 0f, ceilingColor, SpatialPoint.SURFACE_CEILING)
            val ix = c.x + (cx - c.x) * borderInset
            val iz = c.z + (cz - c.z) * borderInset
            innerCeil[i] = addVertex(verts, norms, cols, types, ix, ceilY, iz, 0f, -1f, 0f, ceilingColor, SpatialPoint.SURFACE_CEILING)
        }
        for (i in 0 until n) {
            val next = (i + 1) % n
            tris.add(outerCeil[i]); tris.add(outerCeil[next]); tris.add(innerCeil[next])
            tris.add(outerCeil[i]); tris.add(innerCeil[next]); tris.add(innerCeil[i])
        }
    }

    private class VoxelAccum {
        var sx = 0f
        var sy = 0f
        var sz = 0f
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var count = 0
        var surfaceType: Byte = SpatialPoint.SURFACE_WALL
    }

    private fun appendVoxelizedPointMesh(
        rawPoints: List<SpatialPoint>,
        voxelMeters: Float,
        smoothSurface: Boolean,
        verts: ArrayList<Float>,
        norms: ArrayList<Float>,
        cols: ArrayList<Int>,
        types: ArrayList<Byte>,
        tris: ArrayList<Int>
    ) {
        val invVoxel = 1f / voxelMeters
        val voxels = LinkedHashMap<Long, VoxelAccum>(min(rawPoints.size, 8192))

        for (p in rawPoints) {
            val gx = floor(p.x * invVoxel).toInt()
            val gy = floor(p.y * invVoxel).toInt()
            val gz = floor(p.z * invVoxel).toInt()
            val key = packGridKey(gx, gy, gz)
            val acc = voxels.getOrPut(key) { VoxelAccum() }
            acc.sx += p.x
            acc.sy += p.y
            acc.sz += p.z
            acc.sr += AndroidColor.red(p.argb)
            acc.sg += AndroidColor.green(p.argb)
            acc.sb += AndroidColor.blue(p.argb)
            acc.count++
            acc.surfaceType = p.surfaceType
        }

        if (voxels.isEmpty()) return

        // Compute centroid for inward room normal estimation
        var meanX = 0f
        var meanY = 0f
        var meanZ = 0f
        val baseOffset = verts.size / 3
        val voxelPoints = ArrayList<Vec3>(voxels.size)
        val voxelCols = ArrayList<Int>(voxels.size)
        val voxelTypes = ArrayList<Byte>(voxels.size)

        for (acc in voxels.values) {
            val inv = 1f / acc.count
            val vx = acc.sx * inv
            val vy = acc.sy * inv
            val vz = acc.sz * inv
            meanX += vx
            meanY += vy
            meanZ += vz
            voxelPoints.add(Vec3(vx, vy, vz))
            voxelCols.add(
                AndroidColor.rgb(
                    (acc.sr / acc.count).toInt().coerceIn(0, 255),
                    (acc.sg / acc.count).toInt().coerceIn(0, 255),
                    (acc.sb / acc.count).toInt().coerceIn(0, 255)
                )
            )
            voxelTypes.add(acc.surfaceType)
        }
        meanX /= voxelPoints.size
        meanY /= voxelPoints.size
        meanZ /= voxelPoints.size

        // Bin points onto a panoramic spherical/cylindrical scan grid around centroid for clean fast triangulation
        val azBins = (64f / (voxelMeters * 8f)).roundToInt().coerceIn(24, 96)
        val elBins = (32f / (voxelMeters * 8f)).roundToInt().coerceIn(12, 48)
        val gridCellIdx = IntArray(azBins * elBins) { -1 }
        val gridCellDist = FloatArray(azBins * elBins) { Float.MAX_VALUE }

        // Optional light spatial smoothing pass using cylindrical neighbors
        val finalPos = voxelPoints.toMutableList()
        val cellAccumPos = Array<Vec3?>(azBins * elBins) { null }
        val cellAccumCount = IntArray(azBins * elBins)

        for (i in voxelPoints.indices) {
            val pt = voxelPoints[i]
            val dx = pt.x - meanX
            val dy = pt.y - meanY
            val dz = pt.z - meanZ
            val az = atan2(dz.toDouble(), dx.toDouble()).toFloat() // -PI..PI
            val horiz = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
            val el = atan2(dy.toDouble(), horiz.toDouble()).toFloat() // -PI/2..PI/2

            val u = (((az + PI.toFloat()) / (2f * PI.toFloat())) * azBins).toInt().coerceIn(0, azBins - 1)
            val v = (((el + (PI.toFloat() * 0.5f)) / PI.toFloat()) * elBins).toInt().coerceIn(0, elBins - 1)
            val cell = v * azBins + u
            val dist = sqrt(dx * dx + dy * dy + dz * dz)

            cellAccumPos[cell] = (cellAccumPos[cell] ?: Vec3(0f, 0f, 0f)) + pt
            cellAccumCount[cell]++

            if (dist < gridCellDist[cell]) {
                gridCellDist[cell] = dist
                gridCellIdx[cell] = i
            }
        }

        if (smoothSurface) {
            for (v in 1 until elBins - 1) {
                for (u in 0 until azBins) {
                    val idx = gridCellIdx[v * azBins + u]
                    if (idx < 0) continue
                    var sx = finalPos[idx].x * 2f
                    var sy = finalPos[idx].y * 2f
                    var sz = finalPos[idx].z * 2f
                    var w = 2f
                    val neighbors = intArrayOf(
                        v * azBins + ((u + 1) % azBins),
                        v * azBins + ((u - 1 + azBins) % azBins),
                        (v + 1) * azBins + u,
                        (v - 1) * azBins + u
                    )
                    for (nc in neighbors) {
                        val ni = gridCellIdx[nc]
                        if (ni >= 0) {
                            val np = voxelPoints[ni]
                            if (np.distanceTo(voxelPoints[idx]) < voxelMeters * 4.5f) {
                                sx += np.x
                                sy += np.y
                                sz += np.z
                                w += 1f
                            }
                        }
                    }
                    finalPos[idx] = Vec3(sx / w, sy / w, sz / w)
                }
            }
        }

        // Emit vertices
        for (i in finalPos.indices) {
            val pt = finalPos[i]
            val toCenter = Vec3(meanX - pt.x, meanY - pt.y, meanZ - pt.z).normalize()
            addVertex(
                verts, norms, cols, types,
                pt.x, pt.y, pt.z,
                toCenter.x, toCenter.y, toCenter.z,
                voxelCols[i],
                voxelTypes[i]
            )
        }

        // Connect adjacent occupied panoramic bins into coherent surface triangles
        val maxEdgeLen = (voxelMeters * 5.2f).coerceIn(0.45f, 1.35f)
        for (v in 0 until elBins - 1) {
            for (u in 0 until azBins) {
                val uNext = (u + 1) % azBins
                val a = gridCellIdx[v * azBins + u]
                val b = gridCellIdx[v * azBins + uNext]
                val c = gridCellIdx[(v + 1) * azBins + uNext]
                val d = gridCellIdx[(v + 1) * azBins + u]

                if (a >= 0 && b >= 0 && c >= 0) {
                    if (finalPos[a].distanceTo(finalPos[b]) <= maxEdgeLen &&
                        finalPos[b].distanceTo(finalPos[c]) <= maxEdgeLen &&
                        finalPos[c].distanceTo(finalPos[a]) <= maxEdgeLen
                    ) {
                        tris.add(baseOffset + a)
                        tris.add(baseOffset + b)
                        tris.add(baseOffset + c)
                    }
                }
                if (a >= 0 && c >= 0 && d >= 0) {
                    if (finalPos[a].distanceTo(finalPos[c]) <= maxEdgeLen &&
                        finalPos[c].distanceTo(finalPos[d]) <= maxEdgeLen &&
                        finalPos[d].distanceTo(finalPos[a]) <= maxEdgeLen
                    ) {
                        tris.add(baseOffset + a)
                        tris.add(baseOffset + c)
                        tris.add(baseOffset + d)
                    }
                }
            }
        }

        // Recompute smooth vertex normals from triangulated faces where available
        recomputeNormals(verts, norms, tris, baseOffset)
    }

    private fun recomputeNormals(
        verts: ArrayList<Float>,
        norms: ArrayList<Float>,
        tris: ArrayList<Int>,
        startVertex: Int
    ) {
        val totalVerts = verts.size / 3
        if (totalVerts <= startVertex || tris.isEmpty()) return
        val acc = FloatArray((totalVerts - startVertex) * 3)
        var t = 0
        while (t + 2 < tris.size) {
            val i0 = tris[t]
            val i1 = tris[t + 1]
            val i2 = tris[t + 2]
            if (i0 >= startVertex && i1 >= startVertex && i2 >= startVertex) {
                val ax = verts[i0 * 3]; val ay = verts[i0 * 3 + 1]; val az = verts[i0 * 3 + 2]
                val bx = verts[i1 * 3]; val by = verts[i1 * 3 + 1]; val bz = verts[i1 * 3 + 2]
                val cx = verts[i2 * 3]; val cy = verts[i2 * 3 + 1]; val cz = verts[i2 * 3 + 2]
                val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
                val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
                val nx = e1y * e2z - e1z * e2y
                val ny = e1z * e2x - e1x * e2z
                val nz = e1x * e2y - e1y * e2x
                for (vi in intArrayOf(i0, i1, i2)) {
                    val o = (vi - startVertex) * 3
                    acc[o] += nx
                    acc[o + 1] += ny
                    acc[o + 2] += nz
                }
            }
            t += 3
        }
        for (vi in startVertex until totalVerts) {
            val o = (vi - startVertex) * 3
            val nx = acc[o]; val ny = acc[o + 1]; val nz = acc[o + 2]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-5f) {
                norms[vi * 3] = nx / len
                norms[vi * 3 + 1] = ny / len
                norms[vi * 3 + 2] = nz / len
            }
        }
    }

    private fun addVertex(
        verts: ArrayList<Float>,
        norms: ArrayList<Float>,
        cols: ArrayList<Int>,
        types: ArrayList<Byte>,
        x: Float,
        y: Float,
        z: Float,
        nx: Float,
        ny: Float,
        nz: Float,
        argb: Int,
        surfaceType: Byte
    ): Int {
        val idx = verts.size / 3
        verts.add(x); verts.add(y); verts.add(z)
        norms.add(nx); norms.add(ny); norms.add(nz)
        cols.add(argb)
        types.add(surfaceType)
        return idx
    }

    private fun packGridKey(x: Int, y: Int, z: Int): Long {
        val lx = (x + 1048576).toLong() and 0x1FFFFFL
        val ly = (y + 1048576).toLong() and 0x1FFFFFL
        val lz = (z + 1048576).toLong() and 0x1FFFFFL
        return (lx shl 42) or (ly shl 21) or lz
    }

    private fun scaleColor(argb: Int, factor: Float): Int {
        val r = (AndroidColor.red(argb) * factor).roundToInt().coerceIn(0, 255)
        val g = (AndroidColor.green(argb) * factor).roundToInt().coerceIn(0, 255)
        val b = (AndroidColor.blue(argb) * factor).roundToInt().coerceIn(0, 255)
        return AndroidColor.rgb(r, g, b)
    }

    private fun computeBounds(verts: ArrayList<Float>): BoundingBox3D {
        if (verts.isEmpty()) return BoundingBox3D()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < verts.size) {
            val x = verts[i]; val y = verts[i + 1]; val z = verts[i + 2]
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            minZ = min(minZ, z); maxZ = max(maxZ, z)
            i += 3
        }
        return BoundingBox3D(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * Compact binary cache (.lfg) storing raw points & corner anchors so scans load instantaneously
     * and can be non-destructively re-meshed or cropped at any density.
     */
    fun writeScanBinary(
        file: File,
        rawPoints: List<SpatialPoint>,
        cornerAnchors: List<Vec3>,
        ceilingHeightMeters: Float
    ) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(LFG_MAGIC)
            out.writeFloat(ceilingHeightMeters)
            out.writeInt(cornerAnchors.size)
            for (c in cornerAnchors) {
                out.writeFloat(c.x)
                out.writeFloat(c.y)
                out.writeFloat(c.z)
            }
            out.writeInt(rawPoints.size)
            for (p in rawPoints) {
                out.writeFloat(p.x)
                out.writeFloat(p.y)
                out.writeFloat(p.z)
                out.writeInt(p.argb)
                out.writeByte(p.surfaceType.toInt())
            }
        }
    }

    fun readScanBinary(
        file: File,
        voxelSizeCm: Int = 10,
        smoothSurface: Boolean = true,
        cropBounds: CropBounds = CropBounds()
    ): SpatialScanPackage {
        if (!file.exists()) {
            return SpatialScanPackage(emptyList(), emptyList(), 2.6f, SpatialMesh.empty())
        }
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
            val magic = inp.readInt()
            if (magic != LFG_MAGIC) {
                return SpatialScanPackage(emptyList(), emptyList(), 2.6f, SpatialMesh.empty())
            }
            val ceilingHeight = inp.readFloat()
            val cornerCount = inp.readInt().coerceIn(0, 1000)
            val corners = ArrayList<Vec3>(cornerCount)
            repeat(cornerCount) {
                corners.add(Vec3(inp.readFloat(), inp.readFloat(), inp.readFloat()))
            }
            val pointCount = inp.readInt().coerceIn(0, 500_000)
            val points = ArrayList<SpatialPoint>(pointCount)
            repeat(pointCount) {
                points.add(
                    SpatialPoint(
                        x = inp.readFloat(),
                        y = inp.readFloat(),
                        z = inp.readFloat(),
                        argb = inp.readInt(),
                        surfaceType = inp.readByte()
                    )
                )
            }
            val mesh = buildMesh(
                rawPoints = points,
                cornerAnchors = corners,
                ceilingHeightMeters = ceilingHeight,
                voxelSizeCm = voxelSizeCm,
                smoothSurface = smoothSurface,
                cropBounds = cropBounds
            )
            return SpatialScanPackage(points, corners, ceilingHeight, mesh)
        }
    }
}
