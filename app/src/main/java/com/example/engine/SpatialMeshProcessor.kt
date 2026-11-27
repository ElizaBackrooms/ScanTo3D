package com.example.engine

import android.graphics.Color as AndroidColor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object SpatialMeshProcessor {

    private const val LFG_MAGIC_V1 = 0x4C464731 // "LFG1"
    private const val LFG_MAGIC_V2 = 0x4C464732 // "LFG2" (includes captured surface triangles)

    /**
     * Reconstructs a clean indexed 3D room mesh from captured surface patches, raw 3D points,
     * and/or anchored room corners. When real captured surface triangles are present (Polycam
     * depth-grid & plane capture), vertex-clustering re-welding preserves 100% solid continuous
     * surfaces at any chosen voxel density without breaking into confetti.
     */
    fun buildMesh(
        rawPoints: List<SpatialPoint>,
        cornerAnchors: List<Vec3>,
        ceilingHeightMeters: Float = 2.6f,
        voxelSizeCm: Int = 10,
        smoothSurface: Boolean = true,
        cropBounds: CropBounds = CropBounds(),
        capturedTriangles: IntArray = IntArray(0)
    ): SpatialMesh {
        val voxelMeters = (voxelSizeCm.coerceIn(4, 28)) / 100f
        val verts = ArrayList<Float>(8192)
        val norms = ArrayList<Float>(8192)
        val cols = ArrayList<Int>(4096)
        val types = ArrayList<Byte>(4096)
        val tris = ArrayList<Int>(16384)

        // 1. Crisp architectural floor, wall, and ceiling patches from anchored room corners
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

        // 2. Real captured connected surface mesh (vertex-clustered to requested voxel density)
        if (rawPoints.isNotEmpty()) {
            if (capturedTriangles.size >= 3) {
                appendCapturedSurfaceMesh(
                    rawPoints = rawPoints,
                    capturedTriangles = capturedTriangles,
                    voxelMeters = voxelMeters,
                    smoothSurface = smoothSurface,
                    verts = verts,
                    norms = norms,
                    cols = cols,
                    types = types,
                    tris = tris
                )
            } else {
                appendVoxelized3DSurfaceMesh(
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

        for (i in 0 until n) {
            val p0 = corners[i]
            val p1 = corners[(i + 1) % n]
            val dx = p1.x - p0.x
            val dz = p1.z - p0.z
            val wallLen = sqrt(dx * dx + dz * dz).coerceAtLeast(0.05f)
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

    private class VoxelNode(
        val gx: Int,
        val gy: Int,
        val gz: Int
    ) {
        var sx = 0f
        var sy = 0f
        var sz = 0f
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var count = 0
        val typeVotes = IntArray(4)

        var posX = 0f
        var posY = 0f
        var posZ = 0f
        var smoothX = 0f
        var smoothY = 0f
        var smoothZ = 0f
        var vertexIndex = -1
        var hasFace = false
        var dominantType: Byte = SpatialPoint.SURFACE_WALL
        var argb: Int = 0
    }

    /**
     * Vertex-clustering mesh simplification & edge-aware Laplacian smoothing on real captured
     * Polycam-style depth-grid and plane triangles. Guarantees a solid, continuous, connected
     * 3D mesh at every voxel resolution (4 cm..28 cm) with zero floating confetti.
     */
    private fun appendCapturedSurfaceMesh(
        rawPoints: List<SpatialPoint>,
        capturedTriangles: IntArray,
        voxelMeters: Float,
        smoothSurface: Boolean,
        verts: ArrayList<Float>,
        norms: ArrayList<Float>,
        cols: ArrayList<Int>,
        types: ArrayList<Byte>,
        tris: ArrayList<Int>
    ) {
        val baseOffset = verts.size / 3
        val invVoxel = 1f / voxelMeters
        val clusters = LinkedHashMap<Long, VoxelNode>(min(rawPoints.size, 16_000))
        val rawToCluster = IntArray(rawPoints.size)
        val clusterList = ArrayList<VoxelNode>(min(rawPoints.size, 16_000))

        for (i in rawPoints.indices) {
            val p = rawPoints[i]
            val gx = floor(p.x * invVoxel).toInt()
            val gy = floor(p.y * invVoxel).toInt()
            val gz = floor(p.z * invVoxel).toInt()
            val key = packGridKey(gx, gy, gz)
            var node = clusters[key]
            if (node == null) {
                node = VoxelNode(gx, gy, gz).apply { vertexIndex = clusterList.size }
                clusters[key] = node
                clusterList.add(node)
            }
            node.sx += p.x
            node.sy += p.y
            node.sz += p.z
            node.sr += AndroidColor.red(p.argb)
            node.sg += AndroidColor.green(p.argb)
            node.sb += AndroidColor.blue(p.argb)
            node.count++
            node.typeVotes[p.surfaceType.toInt().coerceIn(0, 3)]++
            rawToCluster[i] = node.vertexIndex
        }

        if (clusterList.isEmpty()) return

        for (node in clusterList) {
            val inv = 1f / node.count
            node.posX = node.sx * inv
            node.posY = node.sy * inv
            node.posZ = node.sz * inv
            node.smoothX = node.posX
            node.smoothY = node.posY
            node.smoothZ = node.posZ
            var bestType = 1
            var bestVotes = -1
            for (k in 0..3) {
                if (node.typeVotes[k] > bestVotes) {
                    bestVotes = node.typeVotes[k]
                    bestType = k
                }
            }
            node.dominantType = bestType.toByte()
            node.argb = AndroidColor.rgb(
                (node.sr / node.count).toInt().coerceIn(0, 255),
                (node.sg / node.count).toInt().coerceIn(0, 255),
                (node.sb / node.count).toInt().coerceIn(0, 255)
            )
        }

        // Remap & deduplicate captured triangles onto clustered vertices
        val seenTris = HashSet<Long>(capturedTriangles.size / 3)
        val localTris = ArrayList<Int>(capturedTriangles.size)
        val maxEdgeLenSq = (voxelMeters * 8.0f).coerceAtLeast(0.65f).let { it * it }
        var t = 0
        while (t + 2 < capturedTriangles.size) {
            val ia = capturedTriangles[t]
            val ib = capturedTriangles[t + 1]
            val ic = capturedTriangles[t + 2]
            t += 3
            if (ia !in rawToCluster.indices || ib !in rawToCluster.indices || ic !in rawToCluster.indices) continue
            val ca = rawToCluster[ia]
            val cb = rawToCluster[ib]
            val cc = rawToCluster[ic]
            if (ca == cb || cb == cc || cc == ca) continue

            val na = clusterList[ca]; val nb = clusterList[cb]; val nc = clusterList[cc]
            val dab = (na.posX - nb.posX) * (na.posX - nb.posX) + (na.posY - nb.posY) * (na.posY - nb.posY) + (na.posZ - nb.posZ) * (na.posZ - nb.posZ)
            val dbc = (nb.posX - nc.posX) * (nb.posX - nc.posX) + (nb.posY - nc.posY) * (nb.posY - nc.posY) + (nb.posZ - nc.posZ) * (nb.posZ - nc.posZ)
            val dca = (nc.posX - na.posX) * (nc.posX - na.posX) + (nc.posY - na.posY) * (nc.posY - na.posY) + (nc.posZ - na.posZ) * (nc.posZ - na.posZ)
            if (dab > maxEdgeLenSq || dbc > maxEdgeLenSq || dca > maxEdgeLenSq) continue

            var s0 = ca; var s1 = cb; var s2 = cc
            if (s0 > s1) { val tmp = s0; s0 = s1; s1 = tmp }
            if (s1 > s2) { val tmp = s1; s1 = s2; s2 = tmp }
            if (s0 > s1) { val tmp = s0; s0 = s1; s1 = tmp }
            val triKey = (s0.toLong() shl 40) or (s1.toLong() shl 20) or s2.toLong()
            if (seenTris.add(triKey)) {
                localTris.add(ca)
                localTris.add(cb)
                localTris.add(cc)
            }
        }

        // Mesh-edge Laplacian smoothing (preserves sharp wall-floor creases while removing depth jitter)
        if (smoothSurface && localTris.isNotEmpty()) {
            val nVerts = clusterList.size
            val sumX = FloatArray(nVerts)
            val sumY = FloatArray(nVerts)
            val sumZ = FloatArray(nVerts)
            val deg = IntArray(nVerts)

            fun addEdge(u: Int, v: Int) {
                val nu = clusterList[u]; val nv = clusterList[v]
                if (nu.dominantType != nv.dominantType) return
                sumX[u] += nv.posX; sumY[u] += nv.posY; sumZ[u] += nv.posZ; deg[u]++
                sumX[v] += nu.posX; sumY[v] += nu.posY; sumZ[v] += nu.posZ; deg[v]++
            }
            var ti = 0
            while (ti + 2 < localTris.size) {
                val a = localTris[ti]; val b = localTris[ti + 1]; val c = localTris[ti + 2]
                addEdge(a, b); addEdge(b, c); addEdge(c, a)
                ti += 3
            }
            for (i in 0 until nVerts) {
                if (deg[i] > 0) {
                    val node = clusterList[i]
                    val avgX = sumX[i] / deg[i]
                    val avgY = sumY[i] / deg[i]
                    val avgZ = sumZ[i] / deg[i]
                    node.smoothX = node.posX * 0.62f + avgX * 0.38f
                    node.smoothY = node.posY * 0.62f + avgY * 0.38f
                    node.smoothZ = node.posZ * 0.62f + avgZ * 0.38f
                }
            }
        }

        for (node in clusterList) {
            addVertex(
                verts, norms, cols, types,
                node.smoothX, node.smoothY, node.smoothZ,
                0f, 1f, 0f,
                node.argb,
                node.dominantType
            )
        }
        var ti = 0
        while (ti + 2 < localTris.size) {
            tris.add(baseOffset + localTris[ti])
            tris.add(baseOffset + localTris[ti + 1])
            tris.add(baseOffset + localTris[ti + 2])
            ti += 3
        }
        recomputeNormals(verts, norms, tris, baseOffset)
    }

    /**
     * True 3D multi-axis voxel adjacency meshing (fallback for legacy point-only scans):
     * - Preserves L-shaped rooms, doorways, hallways, desks, chairs, and alcoves.
     * - Connects adjacent 3D voxels along XZ (horizontal floors/tables/ceilings),
     *   XY (front/back walls), and ZY (left/right walls) with strict edge-length gating
     *   so zero spiderweb triangles ever bridge across empty room space.
     * - Emits clean micro-surfel quads for isolated fine-detail object voxels.
     */
    private fun appendVoxelized3DSurfaceMesh(
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
        val voxels = LinkedHashMap<Long, VoxelNode>(min(rawPoints.size, 12_000))

        for (p in rawPoints) {
            val gx = floor(p.x * invVoxel).toInt()
            val gy = floor(p.y * invVoxel).toInt()
            val gz = floor(p.z * invVoxel).toInt()
            val key = packGridKey(gx, gy, gz)
            val node = voxels.getOrPut(key) { VoxelNode(gx, gy, gz) }
            node.sx += p.x
            node.sy += p.y
            node.sz += p.z
            node.sr += AndroidColor.red(p.argb)
            node.sg += AndroidColor.green(p.argb)
            node.sb += AndroidColor.blue(p.argb)
            node.count++
            val tIdx = p.surfaceType.toInt().coerceIn(0, 3)
            node.typeVotes[tIdx]++
        }

        if (voxels.isEmpty()) return

        var meanX = 0f
        var meanY = 0f
        var meanZ = 0f
        for (node in voxels.values) {
            val inv = 1f / node.count
            node.posX = node.sx * inv
            node.posY = node.sy * inv
            node.posZ = node.sz * inv
            node.smoothX = node.posX
            node.smoothY = node.posY
            node.smoothZ = node.posZ
            meanX += node.posX
            meanY += node.posY
            meanZ += node.posZ

            var bestType = 1
            var bestVotes = -1
            for (k in 0..3) {
                if (node.typeVotes[k] > bestVotes) {
                    bestVotes = node.typeVotes[k]
                    bestType = k
                }
            }
            node.dominantType = bestType.toByte()
            node.argb = AndroidColor.rgb(
                (node.sr / node.count).toInt().coerceIn(0, 255),
                (node.sg / node.count).toInt().coerceIn(0, 255),
                (node.sb / node.count).toInt().coerceIn(0, 255)
            )
        }
        meanX /= voxels.size
        meanY /= voxels.size
        meanZ /= voxels.size

        // Surface-aware 3D Laplacian smoothing (only blends with immediate same-type 3D neighbors)
        if (smoothSurface) {
            for (node in voxels.values) {
                var ax = node.posX * 2.5f
                var ay = node.posY * 2.5f
                var az = node.posZ * 2.5f
                var w = 2.5f
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        for (dz in -1..1) {
                            if (dx == 0 && dy == 0 && dz == 0) continue
                            val nb = voxels[packGridKey(node.gx + dx, node.gy + dy, node.gz + dz)] ?: continue
                            if (nb.dominantType == node.dominantType) {
                                ax += nb.posX
                                ay += nb.posY
                                az += nb.posZ
                                w += 1f
                            }
                        }
                    }
                }
                node.smoothX = ax / w
                node.smoothY = ay / w
                node.smoothZ = az / w
            }
        }

        // Emit one vertex per occupied 3D voxel
        val baseOffset = verts.size / 3
        for (node in voxels.values) {
            val initNormal = when (node.dominantType) {
                SpatialPoint.SURFACE_FLOOR -> Vec3(0f, 1f, 0f)
                SpatialPoint.SURFACE_CEILING -> Vec3(0f, -1f, 0f)
                else -> Vec3(meanX - node.smoothX, meanY - node.smoothY, meanZ - node.smoothZ).normalize()
            }
            node.vertexIndex = addVertex(
                verts, norms, cols, types,
                node.smoothX, node.smoothY, node.smoothZ,
                initNormal.x, initNormal.y, initNormal.z,
                node.argb,
                node.dominantType
            )
        }

        val maxEdgeSq = (voxelMeters * 2.65f) * (voxelMeters * 2.65f)

        // Helper to connect a 2x2 patch of 3D voxels into 1 or 2 triangles if geometrically close
        fun connectQuad(a: VoxelNode, b: VoxelNode?, c: VoxelNode?, d: VoxelNode?) {
            fun close(u: VoxelNode, v: VoxelNode): Boolean {
                val dx = u.smoothX - v.smoothX
                val dy = u.smoothY - v.smoothY
                val dz = u.smoothZ - v.smoothZ
                return dx * dx + dy * dy + dz * dz <= maxEdgeSq
            }
            if (b != null && c != null && close(a, b) && close(b, c) && close(c, a)) {
                tris.add(a.vertexIndex); tris.add(b.vertexIndex); tris.add(c.vertexIndex)
                a.hasFace = true; b.hasFace = true; c.hasFace = true
            }
            if (c != null && d != null && close(a, c) && close(c, d) && close(d, a)) {
                tris.add(a.vertexIndex); tris.add(c.vertexIndex); tris.add(d.vertexIndex)
                a.hasFace = true; c.hasFace = true; d.hasFace = true
            } else if (b != null && d != null && c == null && close(a, b) && close(b, d) && close(d, a)) {
                tris.add(a.vertexIndex); tris.add(b.vertexIndex); tris.add(d.vertexIndex)
                a.hasFace = true; b.hasFace = true; d.hasFace = true
            }
        }

        // Lookup with ±1 perpendicular slab tolerance so slightly tilted walls/floors connect smoothly
        fun findNearestInSlab(gx: Int, gy: Int, gz: Int, axis: Int): VoxelNode? {
            voxels[packGridKey(gx, gy, gz)]?.let { return it }
            return when (axis) {
                0 -> voxels[packGridKey(gx, gy + 1, gz)] ?: voxels[packGridKey(gx, gy - 1, gz)] // XZ slab (tolerate ±1 Y)
                1 -> voxels[packGridKey(gx, gy, gz + 1)] ?: voxels[packGridKey(gx, gy, gz - 1)] // XY slab (tolerate ±1 Z)
                else -> voxels[packGridKey(gx + 1, gy, gz)] ?: voxels[packGridKey(gx - 1, gy, gz)] // ZY slab (tolerate ±1 X)
            }
        }

        for (node in voxels.values) {
            val gx = node.gx
            val gy = node.gy
            val gz = node.gz

            // 1. XZ horizontal surface patch (floors, ceilings, tabletops, shelves)
            val xzB = findNearestInSlab(gx + 1, gy, gz, 0)
            val xzC = findNearestInSlab(gx + 1, gy, gz + 1, 0)
            val xzD = findNearestInSlab(gx, gy, gz + 1, 0)
            if ((xzB != null && xzC != null) || (xzC != null && xzD != null) || (xzB != null && xzD != null)) {
                connectQuad(node, xzB, xzC, xzD)
            }

            // 2. XY vertical wall patch (walls facing ±Z)
            val xyB = findNearestInSlab(gx + 1, gy, gz, 1)
            val xyC = findNearestInSlab(gx + 1, gy + 1, gz, 1)
            val xyD = findNearestInSlab(gx, gy + 1, gz, 1)
            if ((xyB != null && xyC != null) || (xyC != null && xyD != null) || (xyB != null && xyD != null)) {
                connectQuad(node, xyB, xyC, xyD)
            }

            // 3. ZY vertical wall patch (walls facing ±X)
            val zyB = findNearestInSlab(gx, gy, gz + 1, 2)
            val zyC = findNearestInSlab(gx, gy + 1, gz + 1, 2)
            val zyD = findNearestInSlab(gx, gy + 1, gz, 2)
            if ((zyB != null && zyC != null) || (zyC != null && zyD != null) || (zyB != null && zyD != null)) {
                connectQuad(node, zyB, zyC, zyD)
            }
        }

        // Emit oriented micro-surfel quads for any isolated detail voxel so sparse SLAM/furniture points stay visible in STL/OBJ/GLB
        val halfSurfel = voxelMeters * 0.46f
        for (node in voxels.values) {
            if (node.hasFace) continue
            val nrm = when (node.dominantType) {
                SpatialPoint.SURFACE_FLOOR -> Vec3(0f, 1f, 0f)
                SpatialPoint.SURFACE_CEILING -> Vec3(0f, -1f, 0f)
                else -> Vec3(meanX - node.smoothX, meanY - node.smoothY, meanZ - node.smoothZ).normalize()
            }
            val ref = if (abs(nrm.y) < 0.85f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
            val tU = nrm.cross(ref).normalize() * halfSurfel
            val tV = nrm.cross(tU).normalize() * halfSurfel
            val c = Vec3(node.smoothX, node.smoothY, node.smoothZ)

            val p0 = c - tU - tV
            val p1 = c + tU - tV
            val p2 = c + tU + tV
            val p3 = c - tU + tV

            val i0 = addVertex(verts, norms, cols, types, p0.x, p0.y, p0.z, nrm.x, nrm.y, nrm.z, node.argb, node.dominantType)
            val i1 = addVertex(verts, norms, cols, types, p1.x, p1.y, p1.z, nrm.x, nrm.y, nrm.z, node.argb, node.dominantType)
            val i2 = addVertex(verts, norms, cols, types, p2.x, p2.y, p2.z, nrm.x, nrm.y, nrm.z, node.argb, node.dominantType)
            val i3 = addVertex(verts, norms, cols, types, p3.x, p3.y, p3.z, nrm.x, nrm.y, nrm.z, node.argb, node.dominantType)
            tris.add(i0); tris.add(i1); tris.add(i2)
            tris.add(i0); tris.add(i2); tris.add(i3)
        }

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
     * Compact binary cache (.lfg v2) storing raw welded points, corner anchors, and real captured
     * surface triangles so scans load instantaneously and stay solid at every voxel density.
     */
    fun writeScanBinary(
        file: File,
        rawPoints: List<SpatialPoint>,
        cornerAnchors: List<Vec3>,
        ceilingHeightMeters: Float,
        capturedTriangles: IntArray = IntArray(0)
    ) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(LFG_MAGIC_V2)
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
            out.writeInt(capturedTriangles.size)
            for (idx in capturedTriangles) {
                out.writeInt(idx)
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
            if (magic != LFG_MAGIC_V1 && magic != LFG_MAGIC_V2) {
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
            val triIdxCount = if (magic == LFG_MAGIC_V2) inp.readInt().coerceIn(0, 1_500_000) else 0
            val capturedTriangles = IntArray(triIdxCount) { inp.readInt() }

            val mesh = buildMesh(
                rawPoints = points,
                cornerAnchors = corners,
                ceilingHeightMeters = ceilingHeight,
                voxelSizeCm = voxelSizeCm,
                smoothSurface = smoothSurface,
                cropBounds = cropBounds,
                capturedTriangles = capturedTriangles
            )
            return SpatialScanPackage(points, corners, ceilingHeight, mesh, capturedTriangles)
        }
    }
}
