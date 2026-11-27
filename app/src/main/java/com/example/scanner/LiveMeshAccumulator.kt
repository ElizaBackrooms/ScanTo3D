package com.example.scanner

import android.graphics.Color as AndroidColor
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor

/**
 * Snapshot of the live growing 3D mesh ready for 60 FPS OpenGL ES rendering
 * and clean binary persistence.
 */
class LiveMeshGlSnapshot(
    val version: Int,
    val vertices: FloatArray,      // vertexCount * 3 (x, y, z)
    val colors: FloatArray,        // vertexCount * 4 (r, g, b, a in 0..1)
    val triIndices: IntArray,      // triCount * 3
    val edgeIndices: IntArray,     // edgeCount * 2 (unique wireframe edges)
    val vertexCount: Int,
    val triangleCount: Int
)

data class LiveMeshStats(
    val vertexCount: Int = 0,
    val triangleCount: Int = 0,
    val coveragePercent: Int = 0,
    val previewPoints: List<SpatialPoint> = emptyList()
)

data class CapturedMeshPatch(
    val vertices: List<SpatialPoint>,
    val localTriangles: IntArray // triples of indices into `vertices`
)

/**
 * Polycam-style real-time vertex-welded 3D surface mesh accumulator.
 *
 * Welds incoming depth-grid patches and ARCore plane polygons into a 5 cm 3D spatial hash grid,
 * running-averaging positions & RGB colors on repeated passes while preserving true surface
 * triangle connectivity and deduplicating shared triangles & wireframe edges.
 */
class LiveMeshAccumulator(
    private val weldVoxelMeters: Float = 0.05f,
    private val maxVertices: Int = 36_000,
    private val maxTriangles: Int = 72_000
) {
    private val lock = Any()
    private val invVoxel = 1f / weldVoxelMeters

    private val vertexPos = ArrayList<Float>(16_384) // x, y, z
    private val vertexRgb = ArrayList<Int>(8_192)    // packed ARGB
    private val vertexType = ArrayList<Byte>(8_192)
    private val vertexHits = ArrayList<Int>(8_192)
    private val voxelToVertex = HashMap<Long, Int>(8_192)

    private val triIndices = ArrayList<Int>(32_768)
    private val seenTriangles = HashSet<Long>(16_384)

    private val edgeIndices = ArrayList<Int>(32_768)
    private val seenEdges = HashSet<Long>(16_384)

    private val coveredSectors = BooleanArray(24)

    // Undo history stores counts of vertices/tris/edges added per patch
    private data class UndoEntry(
        val addedVoxelKeys: List<Long>,
        val addedTriKeys: List<Long>,
        val addedEdgeKeys: List<Long>,
        val prevVertCount: Int,
        val prevTriIdxCount: Int,
        val prevEdgeIdxCount: Int
    )
    private val undoStack = ArrayDeque<UndoEntry>()

    @Volatile
    var version: Int = 0
        private set

    fun ingestPatch(patch: CapturedMeshPatch, cornerCount: Int = 0): LiveMeshStats {
        if (patch.vertices.isEmpty()) return currentStats(cornerCount)

        synchronized(lock) {
            val prevVertCount = vertexRgb.size
            val prevTriIdxCount = triIndices.size
            val prevEdgeIdxCount = edgeIndices.size
            val addedVoxels = ArrayList<Long>()
            val addedTris = ArrayList<Long>()
            val addedEdges = ArrayList<Long>()

            val localToGlobal = IntArray(patch.vertices.size) { -1 }

            // 1. Weld vertices into 5 cm spatial hash grid
            for (i in patch.vertices.indices) {
                val p = patch.vertices[i]
                val gx = floor(p.x * invVoxel).toInt()
                val gy = floor(p.y * invVoxel).toInt()
                val gz = floor(p.z * invVoxel).toInt()
                val vKey = packVoxelKey(gx, gy, gz)

                val existing = voxelToVertex[vKey]
                if (existing != null && existing < vertexRgb.size) {
                    val hits = (vertexHits[existing] + 1).coerceAtMost(10)
                    vertexHits[existing] = hits
                    val alpha = 1f / hits
                    val invA = 1f - alpha
                    val b = existing * 3
                    vertexPos[b] = vertexPos[b] * invA + p.x * alpha
                    vertexPos[b + 1] = vertexPos[b + 1] * invA + p.y * alpha
                    vertexPos[b + 2] = vertexPos[b + 2] * invA + p.z * alpha

                    val prevCol = vertexRgb[existing]
                    val nr = (AndroidColor.red(prevCol) * invA + AndroidColor.red(p.argb) * alpha).toInt().coerceIn(0, 255)
                    val ng = (AndroidColor.green(prevCol) * invA + AndroidColor.green(p.argb) * alpha).toInt().coerceIn(0, 255)
                    val nb = (AndroidColor.blue(prevCol) * invA + AndroidColor.blue(p.argb) * alpha).toInt().coerceIn(0, 255)
                    vertexRgb[existing] = AndroidColor.rgb(nr, ng, nb)
                    localToGlobal[i] = existing
                } else if (vertexRgb.size < maxVertices) {
                    val idx = vertexRgb.size
                    vertexPos.add(p.x)
                    vertexPos.add(p.y)
                    vertexPos.add(p.z)
                    vertexRgb.add(p.argb)
                    vertexType.add(p.surfaceType)
                    vertexHits.add(1)
                    voxelToVertex[vKey] = idx
                    addedVoxels.add(vKey)
                    localToGlobal[i] = idx

                    val angle = atan2(p.z.toDouble(), p.x.toDouble()).toFloat()
                    val sector = (((angle + PI.toFloat()) / (2f * PI.toFloat())) * coveredSectors.size)
                        .toInt().coerceIn(0, coveredSectors.lastIndex)
                    coveredSectors[sector] = true
                }
            }

            // 2. Remap & deduplicate triangles + unique wireframe edges
            var t = 0
            val localTris = patch.localTriangles
            while (t + 2 < localTris.size && (triIndices.size / 3) < maxTriangles) {
                val la = localTris[t]
                val lb = localTris[t + 1]
                val lc = localTris[t + 2]
                t += 3
                if (la !in localToGlobal.indices || lb !in localToGlobal.indices || lc !in localToGlobal.indices) continue
                val ga = localToGlobal[la]
                val gb = localToGlobal[lb]
                val gc = localToGlobal[lc]
                if (ga < 0 || gb < 0 || gc < 0) continue
                if (ga == gb || gb == gc || gc == ga) continue

                val triKey = packTriKey(ga, gb, gc)
                if (seenTriangles.add(triKey)) {
                    triIndices.add(ga)
                    triIndices.add(gb)
                    triIndices.add(gc)
                    addedTris.add(triKey)

                    addUniqueEdge(ga, gb, addedEdges)
                    addUniqueEdge(gb, gc, addedEdges)
                    addUniqueEdge(gc, ga, addedEdges)
                }
            }

            if (addedVoxels.isNotEmpty() || addedTris.isNotEmpty()) {
                undoStack.addLast(
                    UndoEntry(
                        addedVoxelKeys = addedVoxels,
                        addedTriKeys = addedTris,
                        addedEdgeKeys = addedEdges,
                        prevVertCount = prevVertCount,
                        prevTriIdxCount = prevTriIdxCount,
                        prevEdgeIdxCount = prevEdgeIdxCount
                    )
                )
                if (undoStack.size > 40) undoStack.removeFirst()
                version++
            }

            return buildStatsLocked(cornerCount)
        }
    }

    fun markCornerSector(worldPoint: Vec3) {
        synchronized(lock) {
            val angle = atan2(worldPoint.z.toDouble(), worldPoint.x.toDouble()).toFloat()
            val sector = (((angle + PI.toFloat()) / (2f * PI.toFloat())) * coveredSectors.size)
                .toInt().coerceIn(0, coveredSectors.lastIndex)
            coveredSectors[sector] = true
        }
    }

    fun undoLastPatch(cornerCount: Int = 0): LiveMeshStats {
        synchronized(lock) {
            if (undoStack.isEmpty()) return buildStatsLocked(cornerCount)
            val entry = undoStack.removeLast()
            for (k in entry.addedVoxelKeys) voxelToVertex.remove(k)
            for (k in entry.addedTriKeys) seenTriangles.remove(k)
            for (k in entry.addedEdgeKeys) seenEdges.remove(k)

            while (vertexRgb.size > entry.prevVertCount) {
                vertexRgb.removeAt(vertexRgb.lastIndex)
                vertexType.removeAt(vertexType.lastIndex)
                vertexHits.removeAt(vertexHits.lastIndex)
                repeat(3) { if (vertexPos.isNotEmpty()) vertexPos.removeAt(vertexPos.lastIndex) }
            }
            while (triIndices.size > entry.prevTriIdxCount) {
                triIndices.removeAt(triIndices.lastIndex)
            }
            while (edgeIndices.size > entry.prevEdgeIdxCount) {
                edgeIndices.removeAt(edgeIndices.lastIndex)
            }
            version++
            return buildStatsLocked(cornerCount)
        }
    }

    fun clear() {
        synchronized(lock) {
            vertexPos.clear()
            vertexRgb.clear()
            vertexType.clear()
            vertexHits.clear()
            voxelToVertex.clear()
            triIndices.clear()
            seenTriangles.clear()
            edgeIndices.clear()
            seenEdges.clear()
            undoStack.clear()
            coveredSectors.fill(false)
            version++
        }
    }

    fun hasUndoHistory(): Boolean = synchronized(lock) { undoStack.isNotEmpty() }

    fun currentStats(cornerCount: Int = 0): LiveMeshStats = synchronized(lock) {
        buildStatsLocked(cornerCount)
    }

    /** Builds a clean GPU-ready snapshot for 60 FPS live OpenGL ES mesh painting. */
    fun snapshotForGl(): LiveMeshGlSnapshot = synchronized(lock) {
        val vCount = vertexRgb.size
        val verts = FloatArray(vCount * 3)
        for (i in 0 until verts.size) {
            verts[i] = vertexPos[i]
        }
        val cols = FloatArray(vCount * 4)
        for (i in 0 until vCount) {
            val argb = vertexRgb[i]
            val o = i * 4
            cols[o] = AndroidColor.red(argb) / 255f
            cols[o + 1] = AndroidColor.green(argb) / 255f
            cols[o + 2] = AndroidColor.blue(argb) / 255f
            cols[o + 3] = 0.42f // translucent live surface blanket like Polycam
        }
        val tris = IntArray(triIndices.size) { triIndices[it] }
        val edges = IntArray(edgeIndices.size) { edgeIndices[it] }
        LiveMeshGlSnapshot(
            version = version,
            vertices = verts,
            colors = cols,
            triIndices = tris,
            edgeIndices = edges,
            vertexCount = vCount,
            triangleCount = tris.size / 3
        )
    }

    /** Exports welded vertices & real surface triangle indices for saving & Studio editing. */
    fun exportPointsAndTriangles(): Pair<List<SpatialPoint>, IntArray> = synchronized(lock) {
        val vCount = vertexRgb.size
        val pts = ArrayList<SpatialPoint>(vCount)
        for (i in 0 until vCount) {
            val b = i * 3
            pts.add(
                SpatialPoint(
                    x = vertexPos[b],
                    y = vertexPos[b + 1],
                    z = vertexPos[b + 2],
                    argb = vertexRgb[i],
                    surfaceType = vertexType[i]
                )
            )
        }
        val tris = IntArray(triIndices.size) { triIndices[it] }
        pts to tris
    }

    private fun buildStatsLocked(cornerCount: Int): LiveMeshStats {
        val vCount = vertexRgb.size
        val triCount = triIndices.size / 3
        val stride = (vCount / 260).coerceAtLeast(1)
        val preview = ArrayList<SpatialPoint>(260)
        var i = 0
        while (i < vCount) {
            val b = i * 3
            preview.add(SpatialPoint(vertexPos[b], vertexPos[b + 1], vertexPos[b + 2], vertexRgb[i], vertexType[i]))
            i += stride
        }
        val sectorRatio = coveredSectors.count { it }.toFloat() / coveredSectors.size
        val cornerBoost = (cornerCount * 0.10f).coerceAtMost(0.40f)
        val coverage = ((sectorRatio * 0.85f + cornerBoost) * 100f).toInt().coerceIn(0, 100)
        return LiveMeshStats(
            vertexCount = vCount,
            triangleCount = triCount,
            coveragePercent = coverage,
            previewPoints = preview
        )
    }

    private fun addUniqueEdge(u: Int, v: Int, addedEdges: ArrayList<Long>) {
        val lo = if (u < v) u else v
        val hi = if (u < v) v else u
        val key = (lo.toLong() shl 24) or hi.toLong()
        if (seenEdges.add(key)) {
            edgeIndices.add(lo)
            edgeIndices.add(hi)
            addedEdges.add(key)
        }
    }

    private fun packVoxelKey(x: Int, y: Int, z: Int): Long {
        val lx = (x + 1048576).toLong() and 0x1FFFFFL
        val ly = (y + 1048576).toLong() and 0x1FFFFFL
        val lz = (z + 1048576).toLong() and 0x1FFFFFL
        return (lx shl 42) or (ly shl 21) or lz
    }

    private fun packTriKey(a: Int, b: Int, c: Int): Long {
        var i0 = a; var i1 = b; var i2 = c
        if (i0 > i1) { val t = i0; i0 = i1; i1 = t }
        if (i1 > i2) { val t = i1; i1 = i2; i2 = t }
        if (i0 > i1) { val t = i0; i0 = i1; i1 = t }
        return (i0.toLong() shl 40) or (i1.toLong() shl 20) or i2.toLong()
    }
}
