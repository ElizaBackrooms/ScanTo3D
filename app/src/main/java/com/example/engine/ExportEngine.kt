package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ExportEngine {

    fun export(
        mesh: SpatialMesh,
        format: ExportFormat,
        options: ExportOptions,
        scanTitle: String,
        output: OutputStream
    ) {
        when (format) {
            ExportFormat.OBJ -> writeObj(mesh, options, scanTitle, output)
            ExportFormat.GLB -> writeGlb(mesh, options, scanTitle, output)
            ExportFormat.PLY -> writePly(mesh, options, output)
            ExportFormat.HEIGHTMAP_PNG -> writeHeightmapPng(mesh, options, output)
            ExportFormat.STL -> writeStl(mesh, options, scanTitle, output)
        }
    }

    fun estimateSizeBytes(mesh: SpatialMesh, format: ExportFormat, options: ExportOptions): Long {
        val v = mesh.vertexCount.toLong()
        val t = mesh.triangleCount.toLong()
        return when (format) {
            ExportFormat.OBJ -> 256L + v * 68L + t * 28L
            ExportFormat.GLB -> 1200L + v * 40L + t * 12L
            ExportFormat.PLY -> 320L + v * 64L + t * 22L
            ExportFormat.HEIGHTMAP_PNG -> (options.heightmapResolution.toLong() * options.heightmapResolution / 3L).coerceAtLeast(16_384L)
            ExportFormat.STL -> 84L + t * 50L
        }
    }

    fun formatByteSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    private fun transformPosition(x: Float, y: Float, z: Float, mesh: SpatialMesh, options: ExportOptions): Vec3 {
        val cx = if (options.centerOrigin) mesh.bounds.center.x else 0f
        val cy = if (options.centerOrigin) mesh.bounds.minY else 0f
        val cz = if (options.centerOrigin) mesh.bounds.center.z else 0f
        val sx = (x - cx) * options.scaleFactor
        val sy = (y - cy) * options.scaleFactor
        val sz = (z - cz) * options.scaleFactor
        return when (options.upAxis) {
            UpAxis.Y_UP -> Vec3(sx, sy, sz)
            // Standard Blender Z-Up conversion from Right-Handed Y-Up: (X, -Z, Y)
            UpAxis.Z_UP -> Vec3(sx, -sz, sy)
        }
    }

    private fun transformNormal(nx: Float, ny: Float, nz: Float, options: ExportOptions): Vec3 =
        when (options.upAxis) {
            UpAxis.Y_UP -> Vec3(nx, ny, nz).normalize()
            UpAxis.Z_UP -> Vec3(nx, -nz, ny).normalize()
        }

    /**
     * 1. Wavefront OBJ (.obj) — Compatible with Bryce 3D & Blender.
     * Writes v (with optional vertex RGB for Blender), vn, object/material groups, and f v//vn faces.
     */
    fun writeObj(mesh: SpatialMesh, options: ExportOptions, scanTitle: String, output: OutputStream) {
        val safeObj = scanTitle.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "RoomScan" }
        val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
        writer.write("# LidarForge 3D — Wavefront OBJ Export\n")
        writer.write("# Preset: ${options.preset.label} | Axis: ${options.upAxis.label} | Scale: ${options.scaleFactor}\n")
        writer.write("# Vertices: ${mesh.vertexCount} | Triangles: ${mesh.triangleCount}\n")
        writer.write("mtllib $safeObj.mtl\n")
        writer.write("o $safeObj\n")

        val vCount = mesh.vertexCount
        for (i in 0 until vCount) {
            val b = i * 3
            val p = transformPosition(mesh.vertices[b], mesh.vertices[b + 1], mesh.vertices[b + 2], mesh, options)
            if (options.includeVertexColors) {
                val col = mesh.colors[i]
                val r = AndroidColor.red(col) / 255f
                val g = AndroidColor.green(col) / 255f
                val bl = AndroidColor.blue(col) / 255f
                writer.write(String.format(Locale.US, "v %.5f %.5f %.5f %.4f %.4f %.4f\n", p.x, p.y, p.z, r, g, bl))
            } else {
                writer.write(String.format(Locale.US, "v %.5f %.5f %.5f\n", p.x, p.y, p.z))
            }
        }
        for (i in 0 until vCount) {
            val b = i * 3
            val n = transformNormal(mesh.normals[b], mesh.normals[b + 1], mesh.normals[b + 2], options)
            writer.write(String.format(Locale.US, "vn %.5f %.5f %.5f\n", n.x, n.y, n.z))
        }

        // Group faces by surface type for clean selection in Bryce 3D and Blender
        val groups = mapOf(
            SpatialPoint.SURFACE_FLOOR to "room_floor",
            SpatialPoint.SURFACE_WALL to "room_walls",
            SpatialPoint.SURFACE_CEILING to "room_ceiling",
            SpatialPoint.SURFACE_OBJECT to "room_objects"
        )
        for ((surfaceByte, groupName) in groups) {
            var headerWritten = false
            var t = 0
            while (t + 2 < mesh.indices.size) {
                val i0 = mesh.indices[t]
                val i1 = mesh.indices[t + 1]
                val i2 = mesh.indices[t + 2]
                if (mesh.surfaceTypes.getOrElse(i0) { SpatialPoint.SURFACE_WALL } == surfaceByte) {
                    if (!headerWritten) {
                        writer.write("g $groupName\n")
                        writer.write("usemtl mat_$groupName\n")
                        headerWritten = true
                    }
                    val a = i0 + 1
                    val b = i1 + 1
                    val c = i2 + 1
                    writer.write("f $a//$a $b//$b $c//$c\n")
                }
                t += 3
            }
        }
        writer.flush()
    }

    /** Companion MTL generator for Wavefront OBJ bundles */
    fun writeMtl(output: OutputStream) {
        val mtl = """
            # LidarForge 3D Material Library
            newmtl mat_room_floor
            Kd 0.36 0.45 0.54
            Ks 0.08 0.08 0.08
            Ns 24.0
            d 1.0

            newmtl mat_room_walls
            Kd 0.66 0.73 0.80
            Ks 0.05 0.05 0.05
            Ns 12.0
            d 1.0

            newmtl mat_room_ceiling
            Kd 0.48 0.54 0.62
            Ks 0.04 0.04 0.04
            Ns 10.0
            d 1.0

            newmtl mat_room_objects
            Kd 0.85 0.62 0.25
            Ks 0.15 0.15 0.15
            Ns 32.0
            d 1.0
        """.trimIndent() + "\n"
        output.write(mtl.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /**
     * 2. Binary glTF 2.0 (.glb) — Single-file binary asset for Blender & modern 3D engines.
     * Packs POSITION (VEC3 f32), NORMAL (VEC3 f32), COLOR_0 (VEC4 f32), and UNSIGNED_INT indices.
     */
    fun writeGlb(mesh: SpatialMesh, options: ExportOptions, scanTitle: String, output: OutputStream) {
        val vCount = mesh.vertexCount
        val iCount = mesh.indices.size
        if (vCount == 0) return

        val posBytes = vCount * 12
        val normBytes = vCount * 12
        val colBytes = vCount * 16
        val idxBytes = iCount * 4
        val rawBinLen = posBytes + normBytes + colBytes + idxBytes
        val paddedBinLen = (rawBinLen + 3) and 3.inv()

        val binBuf = ByteBuffer.allocate(paddedBinLen).order(ByteOrder.LITTLE_ENDIAN)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (i in 0 until vCount) {
            val b = i * 3
            val p = transformPosition(mesh.vertices[b], mesh.vertices[b + 1], mesh.vertices[b + 2], mesh, options)
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
            minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
            binBuf.putFloat(p.x)
            binBuf.putFloat(p.y)
            binBuf.putFloat(p.z)
        }
        for (i in 0 until vCount) {
            val b = i * 3
            val n = transformNormal(mesh.normals[b], mesh.normals[b + 1], mesh.normals[b + 2], options)
            binBuf.putFloat(n.x)
            binBuf.putFloat(n.y)
            binBuf.putFloat(n.z)
        }
        for (i in 0 until vCount) {
            val col = mesh.colors[i]
            binBuf.putFloat(AndroidColor.red(col) / 255f)
            binBuf.putFloat(AndroidColor.green(col) / 255f)
            binBuf.putFloat(AndroidColor.blue(col) / 255f)
            binBuf.putFloat(1.0f)
        }
        for (idx in mesh.indices) {
            binBuf.putInt(idx)
        }
        while (binBuf.position() < paddedBinLen) {
            binBuf.put(0)
        }

        val safeName = scanTitle.replace("\"", "'").ifBlank { "LidarForgeRoom" }
        val mode = if (iCount >= 3) 4 else 0 // 4 = TRIANGLES, 0 = POINTS
        val indicesAttr = if (iCount >= 3) ""","indices":3""" else ""
        val indicesAccessor = if (iCount >= 3) {
            """,{"bufferView":3,"componentType":5125,"count":$iCount,"type":"SCALAR"}"""
        } else ""
        val indicesBufferView = if (iCount >= 3) {
            """,{"buffer":0,"byteOffset":${posBytes + normBytes + colBytes},"byteLength":$idxBytes,"target":34963}"""
        } else ""

        val jsonStr = String.format(
            Locale.US,
            """{"asset":{"version":"2.0","generator":"LidarForge 3D Android"},"scene":0,"scenes":[{"name":"%s","nodes":[0]}],"nodes":[{"name":"%s","mesh":0}],"materials":[{"name":"ScannedRoomMat","pbrMetallicRoughness":{"baseColorFactor":[1.0,1.0,1.0,1.0],"metallicFactor":0.05,"roughnessFactor":0.85},"doubleSided":true}],"meshes":[{"name":"%s","primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"COLOR_0":2}%s,"material":0,"mode":%d}]}],"accessors":[{"bufferView":0,"componentType":5126,"count":%d,"type":"VEC3","min":[%.5f,%.5f,%.5f],"max":[%.5f,%.5f,%.5f]},{"bufferView":1,"componentType":5126,"count":%d,"type":"VEC3"},{"bufferView":2,"componentType":5126,"count":%d,"type":"VEC4"}%s],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":%d,"target":34962},{"buffer":0,"byteOffset":%d,"byteLength":%d,"target":34962},{"buffer":0,"byteOffset":%d,"byteLength":%d,"target":34962}%s],"buffers":[{"byteLength":%d}]}""",
            safeName, safeName, safeName,
            indicesAttr, mode,
            vCount, minX, minY, minZ, maxX, maxY, maxZ,
            vCount, vCount, indicesAccessor,
            posBytes, posBytes, normBytes, posBytes + normBytes, colBytes, indicesBufferView,
            paddedBinLen
        )

        val jsonRaw = jsonStr.toByteArray(Charsets.UTF_8)
        val paddedJsonLen = (jsonRaw.size + 3) and 3.inv()
        val totalLen = 12 + 8 + paddedJsonLen + 8 + paddedBinLen

        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x46546C67) // "glTF"
        header.putInt(2)          // version 2
        header.putInt(totalLen)
        header.putInt(paddedJsonLen)
        header.putInt(0x4E4F534A) // "JSON"
        output.write(header.array())
        output.write(jsonRaw)
        repeat(paddedJsonLen - jsonRaw.size) { output.write(0x20) } // space padding for JSON chunk

        val binHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        binHeader.putInt(paddedBinLen)
        binHeader.putInt(0x004E4942) // "BIN\0"
        output.write(binHeader.array())
        output.write(binBuf.array())
        output.flush()
    }

    /**
     * 3. Stanford PLY (.ply) — Point cloud & triangulated mesh with RGB vertex colors.
     */
    fun writePly(mesh: SpatialMesh, options: ExportOptions, output: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
        val vCount = mesh.vertexCount
        val fCount = mesh.triangleCount
        writer.write("ply\n")
        writer.write("format ascii 1.0\n")
        writer.write("comment Generated by LidarForge 3D (${options.preset.label} / ${options.upAxis.label})\n")
        writer.write("element vertex $vCount\n")
        writer.write("property float x\n")
        writer.write("property float y\n")
        writer.write("property float z\n")
        writer.write("property float nx\n")
        writer.write("property float ny\n")
        writer.write("property float nz\n")
        writer.write("property uchar red\n")
        writer.write("property uchar green\n")
        writer.write("property uchar blue\n")
        if (fCount > 0) {
            writer.write("element face $fCount\n")
            writer.write("property list uchar int vertex_indices\n")
        }
        writer.write("end_header\n")

        for (i in 0 until vCount) {
            val b = i * 3
            val p = transformPosition(mesh.vertices[b], mesh.vertices[b + 1], mesh.vertices[b + 2], mesh, options)
            val n = transformNormal(mesh.normals[b], mesh.normals[b + 1], mesh.normals[b + 2], options)
            val col = mesh.colors[i]
            writer.write(
                String.format(
                    Locale.US,
                    "%.5f %.5f %.5f %.4f %.4f %.4f %d %d %d\n",
                    p.x, p.y, p.z, n.x, n.y, n.z,
                    AndroidColor.red(col), AndroidColor.green(col), AndroidColor.blue(col)
                )
            )
        }
        var t = 0
        while (t + 2 < mesh.indices.size) {
            writer.write("3 ${mesh.indices[t]} ${mesh.indices[t + 1]} ${mesh.indices[t + 2]}\n")
            t += 3
        }
        writer.flush()
    }

    /**
     * 4. Bryce 3D Terrain Elevation Heightmap (.png)
     * Orthographic top-down elevation projection normalized to 0..255 grayscale with hole fill & smoothing.
     */
    fun writeHeightmapPng(mesh: SpatialMesh, options: ExportOptions, output: OutputStream) {
        val size = options.heightmapResolution.coerceIn(128, 1024)
        val grid = FloatArray(size * size) { Float.NEGATIVE_INFINITY }

        val b = mesh.bounds
        val spanX = max(b.width, b.depth).coerceAtLeast(0.05f)
        val pad = spanX * 0.06f
        val worldMinX = b.center.x - (spanX * 0.5f + pad)
        val worldMinZ = b.center.z - (spanX * 0.5f + pad)
        val totalSpan = spanX + pad * 2f
        val minY = b.minY
        val heightSpan = b.height.coerceAtLeast(0.05f)

        // Rasterize vertices + triangle interior samples for solid elevation coverage
        fun splat(wx: Float, wy: Float, wz: Float) {
            val u = ((wx - worldMinX) / totalSpan * (size - 1)).roundToInt().coerceIn(0, size - 1)
            val v = ((wz - worldMinZ) / totalSpan * (size - 1)).roundToInt().coerceIn(0, size - 1)
            val normH = ((wy - minY) / heightSpan).coerceIn(0f, 1f)
            val idx = v * size + u
            if (normH > grid[idx]) grid[idx] = normH
        }

        for (i in 0 until mesh.vertexCount) {
            val o = i * 3
            splat(mesh.vertices[o], mesh.vertices[o + 1], mesh.vertices[o + 2])
        }

        // Rasterize triangle barycentric samples so walls & elevated objects form continuous ridges
        var t = 0
        while (t + 2 < mesh.indices.size) {
            val v0 = mesh.vertexAt(mesh.indices[t])
            val v1 = mesh.vertexAt(mesh.indices[t + 1])
            val v2 = mesh.vertexAt(mesh.indices[t + 2])
            val steps = 8
            for (a in 0..steps) {
                val fa = a.toFloat() / steps
                for (c in 0..(steps - a)) {
                    val fc = c.toFloat() / steps
                    val fb = 1f - fa - fc
                    splat(
                        v0.x * fa + v1.x * fb + v2.x * fc,
                        v0.y * fa + v1.y * fb + v2.y * fc,
                        v0.z * fa + v1.z * fb + v2.z * fc
                    )
                }
            }
            t += 3
        }

        // Multi-pass dilation + box smoothing to fill tiny pixel gaps for Bryce terrain import
        var current = grid
        repeat(3) {
            val next = current.copyOf()
            for (y in 1 until size - 1) {
                val row = y * size
                for (x in 1 until size - 1) {
                    val idx = row + x
                    if (current[idx] == Float.NEGATIVE_INFINITY) {
                        var sum = 0f
                        var cnt = 0
                        for (dy in -1..1) {
                            val r = (y + dy) * size
                            for (dx in -1..1) {
                                val v = current[r + x + dx]
                                if (v != Float.NEGATIVE_INFINITY) {
                                    sum += v
                                    cnt++
                                }
                            }
                        }
                        if (cnt > 0) next[idx] = sum / cnt
                    }
                }
            }
            current = next
        }

        // Final gentle 3x3 smoothing pass for clean Bryce terrain contours
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var sum = 0f
                var cnt = 0
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, size - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, size - 1)
                        val v = current[yy * size + xx]
                        if (v != Float.NEGATIVE_INFINITY) {
                            sum += v
                            cnt++
                        }
                    }
                }
                val lum = if (cnt > 0) ((sum / cnt) * 255f).roundToInt().coerceIn(0, 255) else 0
                pixels[y * size + x] = AndroidColor.rgb(lum, lum, lum)
            }
        }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.flush()
    }

    /**
     * 5. Binary STL (.stl) — Standard 80-byte header + uint32 count + 50-byte facets.
     */
    fun writeStl(mesh: SpatialMesh, options: ExportOptions, scanTitle: String, output: OutputStream) {
        val triCount = mesh.triangleCount
        val header = ByteArray(80)
        val label = "LidarForge 3D STL | $scanTitle | ${options.preset.label}".toByteArray(Charsets.US_ASCII)
        System.arraycopy(label, 0, header, 0, min(label.size, 80))
        output.write(header)

        val countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        countBuf.putInt(triCount)
        output.write(countBuf.array())

        val facetBuf = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        var t = 0
        while (t + 2 < mesh.indices.size) {
            val i0 = mesh.indices[t] * 3
            val i1 = mesh.indices[t + 1] * 3
            val i2 = mesh.indices[t + 2] * 3

            val p0 = transformPosition(mesh.vertices[i0], mesh.vertices[i0 + 1], mesh.vertices[i0 + 2], mesh, options)
            val p1 = transformPosition(mesh.vertices[i1], mesh.vertices[i1 + 1], mesh.vertices[i1 + 2], mesh, options)
            val p2 = transformPosition(mesh.vertices[i2], mesh.vertices[i2 + 1], mesh.vertices[i2 + 2], mesh, options)
            val fn = (p1 - p0).cross(p2 - p0).normalize()

            facetBuf.clear()
            facetBuf.putFloat(fn.x); facetBuf.putFloat(fn.y); facetBuf.putFloat(fn.z)
            facetBuf.putFloat(p0.x); facetBuf.putFloat(p0.y); facetBuf.putFloat(p0.z)
            facetBuf.putFloat(p1.x); facetBuf.putFloat(p1.y); facetBuf.putFloat(p1.z)
            facetBuf.putFloat(p2.x); facetBuf.putFloat(p2.y); facetBuf.putFloat(p2.z)
            facetBuf.putShort(0)
            output.write(facetBuf.array())
            t += 3
        }
        output.flush()
    }
}
