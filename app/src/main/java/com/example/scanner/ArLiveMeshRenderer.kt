package com.example.scanner

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 60 FPS OpenGL ES 2.0 real-time 3D mesh painter rendered directly over the live ARCore camera feed.
 *
 * Draws scanned surfaces exactly like Polycam:
 * 1. Semi-transparent real-RGB surface triangles locked in 3D world space (depth-tested)
 * 2. Crisp glowing cyan wireframe mesh lines outlining every captured triangle in true perspective
 */
class ArLiveMeshRenderer {

    private var surfaceProgram = 0
    private var uMvpSurface = -1
    private var aPosSurface = -1
    private var aColSurface = -1

    private var wireProgram = 0
    private var uMvpWire = -1
    private var uColorWire = -1
    private var aPosWire = -1

    private var cachedVersion = -1
    private var vertexBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null

    // GLES 2.0 drawElements reliably supports unsigned short (<= 65535 vertices)
    private var triIndexBuffer: ShortBuffer? = null
    private var triIndexCount = 0

    private var edgeIndexBuffer: ShortBuffer? = null
    private var edgeIndexCount = 0

    fun createOnGlThread() {
        val vsSurface = compileShader(GLES20.GL_VERTEX_SHADER, SURFACE_VS)
        val fsSurface = compileShader(GLES20.GL_FRAGMENT_SHADER, SURFACE_FS)
        surfaceProgram = linkProgram(vsSurface, fsSurface)
        uMvpSurface = GLES20.glGetUniformLocation(surfaceProgram, "u_Mvp")
        aPosSurface = GLES20.glGetAttribLocation(surfaceProgram, "a_Position")
        aColSurface = GLES20.glGetAttribLocation(surfaceProgram, "a_Color")

        val vsWire = compileShader(GLES20.GL_VERTEX_SHADER, WIRE_VS)
        val fsWire = compileShader(GLES20.GL_FRAGMENT_SHADER, WIRE_FS)
        wireProgram = linkProgram(vsWire, fsWire)
        uMvpWire = GLES20.glGetUniformLocation(wireProgram, "u_Mvp")
        uColorWire = GLES20.glGetUniformLocation(wireProgram, "u_Color")
        aPosWire = GLES20.glGetAttribLocation(wireProgram, "a_Position")
    }

    fun draw(vpMatrix: FloatArray, accumulator: LiveMeshAccumulator, showOverlay: Boolean) {
        if (!showOverlay || surfaceProgram == 0 || wireProgram == 0) return

        val curVer = accumulator.version
        if (curVer != cachedVersion) {
            uploadSnapshot(accumulator.snapshotForGl())
            cachedVersion = curVer
        }

        val vBuf = vertexBuffer ?: return
        val cBuf = colorBuffer ?: return
        if (triIndexCount == 0 && edgeIndexCount == 0) return

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        // Pass 1: Semi-transparent colored 3D mesh blanket painted on scanned walls/floor/objects
        if (triIndexCount > 0 && triIndexBuffer != null) {
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glPolygonOffset(1.0f, 1.0f)
            GLES20.glDepthMask(true)

            GLES20.glUseProgram(surfaceProgram)
            GLES20.glUniformMatrix4fv(uMvpSurface, 1, false, vpMatrix, 0)

            vBuf.position(0)
            cBuf.position(0)
            GLES20.glVertexAttribPointer(aPosSurface, 3, GLES20.GL_FLOAT, false, 0, vBuf)
            GLES20.glVertexAttribPointer(aColSurface, 4, GLES20.GL_FLOAT, false, 0, cBuf)
            GLES20.glEnableVertexAttribArray(aPosSurface)
            GLES20.glEnableVertexAttribArray(aColSurface)

            triIndexBuffer!!.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, triIndexCount, GLES20.GL_UNSIGNED_SHORT, triIndexBuffer)

            GLES20.glDisableVertexAttribArray(aPosSurface)
            GLES20.glDisableVertexAttribArray(aColSurface)
            GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        }

        // Pass 2: Glowing Polycam-style cyan 3D wireframe overlay showing exact triangle structure
        if (edgeIndexCount > 0 && edgeIndexBuffer != null) {
            GLES20.glDepthMask(false)
            GLES20.glLineWidth(2.2f)

            GLES20.glUseProgram(wireProgram)
            GLES20.glUniformMatrix4fv(uMvpWire, 1, false, vpMatrix, 0)
            GLES20.glUniform4f(uColorWire, 0.16f, 0.92f, 1.0f, 0.72f)

            vBuf.position(0)
            GLES20.glVertexAttribPointer(aPosWire, 3, GLES20.GL_FLOAT, false, 0, vBuf)
            GLES20.glEnableVertexAttribArray(aPosWire)

            edgeIndexBuffer!!.position(0)
            GLES20.glDrawElements(GLES20.GL_LINES, edgeIndexCount, GLES20.GL_UNSIGNED_SHORT, edgeIndexBuffer)

            GLES20.glDisableVertexAttribArray(aPosWire)
            GLES20.glDepthMask(true)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun uploadSnapshot(snap: LiveMeshGlSnapshot) {
        if (snap.vertexCount == 0) {
            vertexBuffer = null
            colorBuffer = null
            triIndexBuffer = null
            edgeIndexBuffer = null
            triIndexCount = 0
            edgeIndexCount = 0
            return
        }

        val safeVerts = snap.vertexCount.coerceAtMost(65_000)

        vertexBuffer = ByteBuffer
            .allocateDirect(safeVerts * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(snap.vertices, 0, safeVerts * 3)
                position(0)
            }

        colorBuffer = ByteBuffer
            .allocateDirect(safeVerts * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(snap.colors, 0, safeVerts * 4)
                position(0)
            }

        // Filter indices safely under unsigned short limit (65000)
        val validTriCount = snap.triIndices.size
        val triShorts = ShortArray(validTriCount)
        var tWrite = 0
        var t = 0
        while (t + 2 < validTriCount) {
            val a = snap.triIndices[t]
            val b = snap.triIndices[t + 1]
            val c = snap.triIndices[t + 2]
            if (a < safeVerts && b < safeVerts && c < safeVerts) {
                triShorts[tWrite++] = a.toShort()
                triShorts[tWrite++] = b.toShort()
                triShorts[tWrite++] = c.toShort()
            }
            t += 3
        }
        triIndexCount = tWrite
        triIndexBuffer = if (tWrite > 0) {
            ByteBuffer.allocateDirect(tWrite * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(triShorts, 0, tWrite)
                position(0)
            }
        } else null

        val validEdgeCount = snap.edgeIndices.size
        val edgeShorts = ShortArray(validEdgeCount)
        var eWrite = 0
        var e = 0
        while (e + 1 < validEdgeCount) {
            val a = snap.edgeIndices[e]
            val b = snap.edgeIndices[e + 1]
            if (a < safeVerts && b < safeVerts) {
                edgeShorts[eWrite++] = a.toShort()
                edgeShorts[eWrite++] = b.toShort()
            }
            e += 2
        }
        edgeIndexCount = eWrite
        edgeIndexBuffer = if (eWrite > 0) {
            ByteBuffer.allocateDirect(eWrite * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(edgeShorts, 0, eWrite)
                position(0)
            }
        } else null
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun linkProgram(vs: Int, fs: Int): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }

    companion object {
        private const val SURFACE_VS = """
            uniform mat4 u_Mvp;
            attribute vec3 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() {
                gl_Position = u_Mvp * vec4(a_Position, 1.0);
                v_Color = a_Color;
            }
        """

        private const val SURFACE_FS = """
            precision mediump float;
            varying vec4 v_Color;
            void main() {
                gl_FragColor = v_Color;
            }
        """

        private const val WIRE_VS = """
            uniform mat4 u_Mvp;
            attribute vec3 a_Position;
            void main() {
                gl_Position = u_Mvp * vec4(a_Position, 1.0);
            }
        """

        private const val WIRE_FS = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
