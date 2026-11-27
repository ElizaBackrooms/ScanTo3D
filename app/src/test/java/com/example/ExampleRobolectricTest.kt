package com.example

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.engine.CropBounds
import com.example.engine.ExportEngine
import com.example.engine.ExportFormat
import com.example.engine.ExportOptions
import com.example.engine.ExportPreset
import com.example.engine.SpatialMeshProcessor
import com.example.engine.SpatialPoint
import com.example.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun readAppNameFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LidarForge 3D", appName)
    }

    @Test
    fun meshProcessingCropAndAllFiveExportsWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val corners = listOf(
            Vec3(-2f, 0f, -1.5f),
            Vec3(2f, 0f, -1.5f),
            Vec3(2f, 0f, 1.5f),
            Vec3(-2f, 0f, 1.5f)
        )
        val rawPoints = listOf(
            SpatialPoint(0f, 0.5f, -1.4f, Color.rgb(180, 190, 200), SpatialPoint.SURFACE_WALL),
            SpatialPoint(0.2f, 0.8f, -1.4f, Color.rgb(175, 185, 195), SpatialPoint.SURFACE_WALL),
            SpatialPoint(0.1f, 0.0f, 0.2f, Color.rgb(90, 110, 130), SpatialPoint.SURFACE_FLOOR)
        )

        // Binary round-trip test
        val tmpFile = File(context.cacheDir, "test_room.lfg")
        SpatialMeshProcessor.writeScanBinary(tmpFile, rawPoints, corners, 2.6f)
        val pkg = SpatialMeshProcessor.readScanBinary(tmpFile, voxelSizeCm = 12, smoothSurface = true)
        tmpFile.delete()

        assertTrue("Expected vertices > 0", pkg.mesh.vertexCount > 20)
        assertTrue("Expected triangles > 0", pkg.mesh.triangleCount > 20)

        // Crop box slicing reduces vertex count
        val cropped = SpatialMeshProcessor.applyCrop(pkg.mesh, CropBounds(0.1f, 0.9f, 0f, 0.8f, 0.1f, 0.9f))
        assertTrue("Cropped mesh should retain vertices", cropped.vertexCount > 0)
        assertTrue("Cropped mesh should have fewer vertices", cropped.vertexCount < pkg.mesh.vertexCount)

        // 1. OBJ (Bryce 3D Y-Up)
        val objOut = ByteArrayOutputStream()
        ExportEngine.export(pkg.mesh, ExportFormat.OBJ, ExportOptions(preset = ExportPreset.BRYCE_3D), "Studio Room", objOut)
        val objStr = objOut.toString(Charsets.UTF_8.name())
        assertTrue(objStr.contains("v "))
        assertTrue(objStr.contains("vn "))
        assertTrue(objStr.contains("f "))

        // 2. GLB 2.0 (Blender Z-Up)
        val glbOut = ByteArrayOutputStream()
        ExportEngine.export(pkg.mesh, ExportFormat.GLB, ExportOptions(preset = ExportPreset.BLENDER), "Studio Room", glbOut)
        val glbBytes = glbOut.toByteArray()
        assertTrue(glbBytes.size > 64)
        val glbHeader = ByteBuffer.wrap(glbBytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, glbHeader.int) // "glTF"
        assertEquals(2, glbHeader.int)          // version 2
        assertEquals(glbBytes.size, glbHeader.int)

        // 3. PLY
        val plyOut = ByteArrayOutputStream()
        ExportEngine.export(pkg.mesh, ExportFormat.PLY, ExportOptions(preset = ExportPreset.BLENDER), "Studio Room", plyOut)
        val plyStr = plyOut.toString(Charsets.UTF_8.name())
        assertTrue(plyStr.startsWith("ply\n"))
        assertTrue(plyStr.contains("element vertex ${pkg.mesh.vertexCount}"))

        // 4. Bryce Terrain Heightmap PNG
        val pngOut = ByteArrayOutputStream()
        ExportEngine.export(pkg.mesh, ExportFormat.HEIGHTMAP_PNG, ExportOptions(preset = ExportPreset.BRYCE_3D, heightmapResolution = 128), "Studio Room", pngOut)
        val pngBytes = pngOut.toByteArray()
        assertTrue(pngBytes.size > 100)
        // PNG magic signature: 0x89 0x50 0x4E 0x47
        assertEquals(0x89.toByte(), pngBytes[0])
        assertEquals(0x50.toByte(), pngBytes[1])
        assertEquals(0x4E.toByte(), pngBytes[2])
        assertEquals(0x47.toByte(), pngBytes[3])

        // 5. Binary STL
        val stlOut = ByteArrayOutputStream()
        ExportEngine.export(pkg.mesh, ExportFormat.STL, ExportOptions(preset = ExportPreset.BLENDER), "Studio Room", stlOut)
        val stlBytes = stlOut.toByteArray()
        assertEquals(84 + pkg.mesh.triangleCount * 50, stlBytes.size)
    }

    @Test
    fun liveMeshAccumulatorWeldsConnectedSurfacesAndPersistsLfg2() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accumulator = com.example.scanner.LiveMeshAccumulator(weldVoxelMeters = 0.05f)

        // Simulate a 4x4 connected wall depth patch (9 quads = 18 triangles)
        val verts = ArrayList<SpatialPoint>()
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                verts.add(
                    SpatialPoint(
                        x = c * 0.20f,
                        y = r * 0.20f,
                        z = -2.0f,
                        argb = Color.rgb(180 + c * 10, 170 + r * 10, 160),
                        surfaceType = SpatialPoint.SURFACE_WALL
                    )
                )
            }
        }
        val tris = ArrayList<Int>()
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                val i00 = r * 4 + c
                val i10 = r * 4 + (c + 1)
                val i11 = (r + 1) * 4 + (c + 1)
                val i01 = (r + 1) * 4 + c
                tris.add(i00); tris.add(i11); tris.add(i10)
                tris.add(i00); tris.add(i01); tris.add(i11)
            }
        }

        val stats = accumulator.ingestPatch(com.example.scanner.CapturedMeshPatch(verts, tris.toIntArray()))
        assertEquals(16, stats.vertexCount)
        assertEquals(18, stats.triangleCount)

        // Second overlapping pass over same wall should weld/refine, NOT duplicate triangles
        val stats2 = accumulator.ingestPatch(com.example.scanner.CapturedMeshPatch(verts, tris.toIntArray()))
        assertEquals(16, stats2.vertexCount)
        assertEquals(18, stats2.triangleCount)

        // GL snapshot for 60fps OpenGL live mesh painting must have matching triangles & edges
        val glSnap = accumulator.snapshotForGl()
        assertEquals(16, glSnap.vertexCount)
        assertEquals(18, glSnap.triangleCount)
        assertTrue(glSnap.edgeIndices.size >= 30)

        // LFG2 binary round-trip preserves real surface triangles into Studio & exports
        val (pts, capturedTris) = accumulator.exportPointsAndTriangles()
        val tmp = File(context.cacheDir, "test_polycam_wall.lfg")
        SpatialMeshProcessor.writeScanBinary(tmp, pts, emptyList(), 2.6f, capturedTris)
        val loaded = SpatialMeshProcessor.readScanBinary(tmp, voxelSizeCm = 6, smoothSurface = true)
        tmp.delete()

        assertEquals(18, loaded.capturedTriangles.size / 3)
        assertEquals(16, loaded.mesh.vertexCount)
        assertEquals(18, loaded.mesh.triangleCount)
    }
}
