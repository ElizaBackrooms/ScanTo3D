package com.example.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalize(): Vec3 {
        val len = length()
        return if (len > 1e-6f) Vec3(x / len, y / len, z / len) else Vec3(0f, 1f, 0f)
    }

    fun distanceTo(o: Vec3): Float = (this - o).length()
}

data class BoundingBox3D(
    val minX: Float = -1f,
    val minY: Float = 0f,
    val minZ: Float = -1f,
    val maxX: Float = 1f,
    val maxY: Float = 2f,
    val maxZ: Float = 1f
) {
    val width: Float get() = (maxX - minX).coerceAtLeast(0.01f)
    val height: Float get() = (maxY - minY).coerceAtLeast(0.01f)
    val depth: Float get() = (maxZ - minZ).coerceAtLeast(0.01f)
    val center: Vec3 get() = Vec3((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f)
    val radius: Float get() = (sqrt(width * width + height * height + depth * depth) * 0.5f).coerceAtLeast(0.5f)
}

/** Normalized 0f..1f bounds relative to the scan's full AABB */
data class CropBounds(
    val minX: Float = 0f,
    val maxX: Float = 1f,
    val minY: Float = 0f,
    val maxY: Float = 1f,
    val minZ: Float = 0f,
    val maxZ: Float = 1f
) {
    val isFull: Boolean
        get() = minX <= 0.001f && maxX >= 0.999f &&
            minY <= 0.001f && maxY >= 0.999f &&
            minZ <= 0.001f && maxZ >= 0.999f

    fun toWorldBox(full: BoundingBox3D): BoundingBox3D = BoundingBox3D(
        minX = full.minX + full.width * minX,
        maxX = full.minX + full.width * maxX,
        minY = full.minY + full.height * minY,
        maxY = full.minY + full.height * maxY,
        minZ = full.minZ + full.depth * minZ,
        maxZ = full.minZ + full.depth * maxZ
    )

    fun serialize(): String = "$minX,$maxX,$minY,$maxY,$minZ,$maxZ"

    companion object {
        fun deserialize(raw: String): CropBounds {
            val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
            return if (parts.size == 6) {
                CropBounds(
                    minX = parts[0].coerceIn(0f, 0.95f),
                    maxX = parts[1].coerceIn(0.05f, 1f),
                    minY = parts[2].coerceIn(0f, 0.95f),
                    maxY = parts[3].coerceIn(0.05f, 1f),
                    minZ = parts[4].coerceIn(0f, 0.95f),
                    maxZ = parts[5].coerceIn(0.05f, 1f)
                )
            } else CropBounds()
        }
    }
}

data class MeasurementPin(
    val id: String,
    val label: String,
    val start: Vec3,
    val end: Vec3
) {
    val distanceMeters: Float get() = start.distanceTo(end)
    val horizontalMeters: Float
        get() {
            val dx = end.x - start.x
            val dz = end.z - start.z
            return sqrt(dx * dx + dz * dz)
        }
    val verticalMeters: Float get() = abs(end.y - start.y)

    companion object {
        fun serializeList(list: List<MeasurementPin>): String =
            list.joinToString(";") { p ->
                "${p.id}|${p.label.replace("|", " ").replace(";", " ")}|${p.start.x}|${p.start.y}|${p.start.z}|${p.end.x}|${p.end.y}|${p.end.z}"
            }

        fun deserializeList(raw: String): List<MeasurementPin> {
            if (raw.isBlank()) return emptyList()
            return raw.split(";").mapNotNull { item ->
                val p = item.split("|")
                if (p.size == 8) {
                    val sx = p[2].toFloatOrNull() ?: return@mapNotNull null
                    val sy = p[3].toFloatOrNull() ?: return@mapNotNull null
                    val sz = p[4].toFloatOrNull() ?: return@mapNotNull null
                    val ex = p[5].toFloatOrNull() ?: return@mapNotNull null
                    val ey = p[6].toFloatOrNull() ?: return@mapNotNull null
                    val ez = p[7].toFloatOrNull() ?: return@mapNotNull null
                    MeasurementPin(p[0], p[1], Vec3(sx, sy, sz), Vec3(ex, ey, ez))
                } else null
            }
        }
    }
}

enum class MeasurementUnit(val id: String, val label: String, val shortSuffix: String) {
    METERS("meters", "Meters", "m"),
    FEET_INCHES("feet_inches", "Feet / In", "ft"),
    BRYCE_UNITS("bryce_units", "Bryce Units", "BU");

    fun format(meters: Float): String = when (this) {
        METERS -> String.format("%.2f m", meters)
        FEET_INCHES -> {
            val totalInches = meters * 39.37008f
            val feet = floor(totalInches / 12f).toInt()
            val inches = totalInches - feet * 12f
            if (feet > 0) String.format("%d' %.1f\"", feet, inches)
            else String.format("%.1f\"", inches)
        }
        BRYCE_UNITS -> {
            // Standard Bryce metric conversion: 1m = 4.096 Bryce Units (1 BU ≈ 24.4cm)
            String.format("%.2f BU", meters * 4.096f)
        }
    }

    companion object {
        fun fromId(id: String?): MeasurementUnit =
            entries.firstOrNull { it.id == id } ?: METERS
    }
}

enum class ShadingMode(val label: String) {
    SHADED("Shaded"),
    WIREFRAME("Wire"),
    POINT_CLOUD("Points"),
    THERMAL_DEPTH("Depth")
}

data class SpatialPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val argb: Int,
    val surfaceType: Byte = SURFACE_WALL // 0=floor, 1=wall, 2=ceiling, 3=object
) {
    companion object {
        const val SURFACE_FLOOR: Byte = 0
        const val SURFACE_WALL: Byte = 1
        const val SURFACE_CEILING: Byte = 2
        const val SURFACE_OBJECT: Byte = 3
    }
}

class SpatialMesh(
    val vertices: FloatArray, // length = vertexCount * 3 (x, y, z)
    val normals: FloatArray,  // length = vertexCount * 3 (nx, ny, nz)
    val colors: IntArray,     // length = vertexCount (ARGB)
    val surfaceTypes: ByteArray, // length = vertexCount
    val indices: IntArray,    // length = triangleCount * 3
    val bounds: BoundingBox3D
) {
    val vertexCount: Int get() = vertices.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun vertexAt(index: Int): Vec3 {
        val base = index * 3
        return Vec3(vertices[base], vertices[base + 1], vertices[base + 2])
    }

    companion object {
        fun empty() = SpatialMesh(
            vertices = FloatArray(0),
            normals = FloatArray(0),
            colors = IntArray(0),
            surfaceTypes = ByteArray(0),
            indices = IntArray(0),
            bounds = BoundingBox3D()
        )
    }
}

data class SpatialScanPackage(
    val rawPoints: List<SpatialPoint>,
    val cornerAnchors: List<Vec3>,
    val ceilingHeightMeters: Float,
    val mesh: SpatialMesh
)

enum class ExportFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
    val title: String,
    val targetBadge: String,
    val description: String
) {
    OBJ(
        id = "obj",
        extension = "obj",
        mimeType = "text/plain",
        title = "Wavefront OBJ",
        targetBadge = "Bryce 3D • Blender",
        description = "Clean indexed mesh with normals & material groups"
    ),
    GLB(
        id = "glb",
        extension = "glb",
        mimeType = "model/gltf-binary",
        title = "glTF 2.0 Binary",
        targetBadge = "Blender Pipeline",
        description = "Single-file binary mesh with vertex colors & PBR normals"
    ),
    PLY(
        id = "ply",
        extension = "ply",
        mimeType = "application/octet-stream",
        title = "Stanford PLY",
        targetBadge = "Point Cloud • Mesh",
        description = "Vertex-colored point cloud & triangulated faces"
    ),
    HEIGHTMAP_PNG(
        id = "heightmap_png",
        extension = "png",
        mimeType = "image/png",
        title = "Terrain Heightmap",
        targetBadge = "Bryce Terrain Editor",
        description = "512×512 normalized elevation grayscale PNG"
    ),
    STL(
        id = "stl",
        extension = "stl",
        mimeType = "application/sla",
        title = "Binary STL",
        targetBadge = "Solid Mesh • Sculpt",
        description = "Watertight binary facet mesh for sculpting & CAD"
    )
}

enum class UpAxis(val label: String) {
    Y_UP("Y-Up (Bryce)"),
    Z_UP("Z-Up (Blender)")
}

enum class ExportPreset(
    val id: String,
    val label: String,
    val upAxis: UpAxis,
    val scaleFactor: Float,
    val centerOrigin: Boolean,
    val includeVertexColors: Boolean,
    val recommendedFormats: List<ExportFormat>
) {
    BRYCE_3D(
        id = "bryce_3d",
        label = "Bryce 3D",
        upAxis = UpAxis.Y_UP,
        scaleFactor = 4.096f,
        centerOrigin = true,
        includeVertexColors = true,
        recommendedFormats = listOf(ExportFormat.OBJ, ExportFormat.HEIGHTMAP_PNG)
    ),
    BLENDER(
        id = "blender",
        label = "Blender",
        upAxis = UpAxis.Z_UP,
        scaleFactor = 1.0f,
        centerOrigin = true,
        includeVertexColors = true,
        recommendedFormats = listOf(ExportFormat.GLB, ExportFormat.OBJ, ExportFormat.PLY)
    ),
    CUSTOM(
        id = "custom",
        label = "Custom",
        upAxis = UpAxis.Y_UP,
        scaleFactor = 1.0f,
        centerOrigin = true,
        includeVertexColors = true,
        recommendedFormats = ExportFormat.entries
    );

    companion object {
        fun fromId(id: String?): ExportPreset =
            entries.firstOrNull { it.id == id } ?: BRYCE_3D
    }
}

data class ExportOptions(
    val preset: ExportPreset = ExportPreset.BRYCE_3D,
    val upAxis: UpAxis = preset.upAxis,
    val scaleFactor: Float = preset.scaleFactor,
    val centerOrigin: Boolean = preset.centerOrigin,
    val includeVertexColors: Boolean = preset.includeVertexColors,
    val heightmapResolution: Int = 512
)
