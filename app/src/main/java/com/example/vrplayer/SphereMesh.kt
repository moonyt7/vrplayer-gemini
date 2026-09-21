package com.example.vrplayer

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

enum class GeometryType {
    SPHERE_360,
    DOME_180,
    CINEMA_SCREEN
}

class SphereMesh(radius: Float = 50f) {

    // 1. 360° 全景完整球体
    private val sphereVertexBuffer: FloatBuffer
    private val sphereUvBuffer: FloatBuffer
    private val sphereVertexCount: Int

    // 2. 180° VR180 半球顶
    private val domeVertexBuffer: FloatBuffer
    private val domeUvBuffer: FloatBuffer
    private val domeVertexCount: Int

    // 3. 巨幕影院弧面屏
    private val cinemaVertexBuffer: FloatBuffer
    private val cinemaUvBuffer: FloatBuffer
    private val cinemaVertexCount: Int

    private var program = 0
    private var uMVPMatrixLoc = 0
    private var uSTMatrixLoc = 0
    private var uUVRangeLoc = 0
    private var sTextureLoc = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        uniform vec4 uUVRange; // x=uMin, y=uMax, z=vMin, w=vMax
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            // 1. 根据 VR 格式 (SBS左右眼 / 2D / 3D) 裁切 UV 范围
            vec2 croppedUV = vec2(
                aTexCoord.x * (uUVRange.y - uUVRange.x) + uUVRange.x,
                aTexCoord.y * (uUVRange.w - uUVRange.z) + uUVRange.z
            );
            // 2. 经由 SurfaceTexture 硬件解码矩阵变换
            vec4 transformedUV = uSTMatrix * vec4(croppedUV.x, croppedUV.y, 0.0, 1.0);
            vTexCoord = transformedUV.xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    init {
        // =========================================================================
        // 1. 构建 360° 全景球体 (视点在球心 (0,0,0)，正向面向 -Z 轴前方)
        // 严格消除镜像公式：
        // x =  R * cos(phi) * sin(theta)  --> theta > 0 对应 +X (右侧)
        // y =  R * sin(phi)              --> phi > 0   对应 +Y (上方)
        // z = -R * cos(phi) * cos(theta)  --> theta = 0 对应 -Z (正前方)
        // u 从 0 到 1 对应 theta 从 -PI 到 +PI (视频左侧映射到观影视野左侧，右侧映射到右侧，绝不镜像)
        // =========================================================================
        val rings = 36     // 纬度切片
        val sectors = 48   // 经度切片
        val sphereVerts = mutableListOf<Float>()
        val sphereUvs = mutableListOf<Float>()

        for (r in 0 until rings) {
            val phi1 = (-Math.PI / 2 + Math.PI * r / rings).toFloat()
            val phi2 = (-Math.PI / 2 + Math.PI * (r + 1) / rings).toFloat()

            val v1 = r.toFloat() / rings
            val v2 = (r + 1).toFloat() / rings

            for (s in 0 until sectors) {
                // theta: -PI 到 +PI，中点 theta = 0 严格对应前方 -Z
                val theta1 = (-Math.PI + 2 * Math.PI * s / sectors).toFloat()
                val theta2 = (-Math.PI + 2 * Math.PI * (s + 1) / sectors).toFloat()

                val u1 = s.toFloat() / sectors
                val u2 = (s + 1).toFloat() / sectors

                // 计算四个球顶点 (无镜像：x 为正对应右边，x 为负对应左边)
                val x00 =  radius * cos(phi1) * sin(theta1)
                val y00 =  radius * sin(phi1)
                val z00 = -radius * cos(phi1) * cos(theta1)

                val x10 =  radius * cos(phi2) * sin(theta1)
                val y10 =  radius * sin(phi2)
                val z10 = -radius * cos(phi2) * cos(theta1)

                val x01 =  radius * cos(phi1) * sin(theta2)
                val y01 =  radius * sin(phi1)
                val z01 = -radius * cos(phi1) * cos(theta2)

                val x11 =  radius * cos(phi2) * sin(theta2)
                val y11 =  radius * sin(phi2)
                val z11 = -radius * cos(phi2) * cos(theta2)

                // 从球心观察的逆时针面顺序剖分三角形
                sphereVerts.addAll(listOf(x00, y00, z00, x01, y01, z01, x10, y10, z10))
                sphereUvs.addAll(listOf(u1, v1, u2, v1, u1, v2))

                sphereVerts.addAll(listOf(x10, y10, z10, x01, y01, z01, x11, y11, z11))
                sphereUvs.addAll(listOf(u1, v2, u2, v1, u2, v2))
            }
        }
        sphereVertexCount = sphereVerts.size / 3
        sphereVertexBuffer = createFloatBuffer(sphereVerts.toFloatArray())
        sphereUvBuffer = createFloatBuffer(sphereUvs.toFloatArray())

        // =========================================================================
        // 2. 构建 180° 半球顶 (VR180 格式，视野正前方 180 度半球，左右无镜像)
        // =========================================================================
        val domeVerts = mutableListOf<Float>()
        val domeUvs = mutableListOf<Float>()
        val domeRings = 24
        val domeSectors = 36

        for (r in 0 until domeRings) {
            val phi1 = (-Math.PI / 2 + Math.PI * r / domeRings).toFloat()
            val phi2 = (-Math.PI / 2 + Math.PI * (r + 1) / domeRings).toFloat()
            val v1 = r.toFloat() / domeRings
            val v2 = (r + 1).toFloat() / domeRings

            for (s in 0 until domeSectors) {
                // 180° 前半球范围: -PI/2 到 +PI/2
                val theta1 = (-Math.PI / 2 + Math.PI * s / domeSectors).toFloat()
                val theta2 = (-Math.PI / 2 + Math.PI * (s + 1) / domeSectors).toFloat()

                val u1 = s.toFloat() / domeSectors
                val u2 = (s + 1).toFloat() / domeSectors

                val x00 =  radius * cos(phi1) * sin(theta1)
                val y00 =  radius * sin(phi1)
                val z00 = -radius * cos(phi1) * cos(theta1)

                val x10 =  radius * cos(phi2) * sin(theta1)
                val y10 =  radius * sin(phi2)
                val z10 = -radius * cos(phi2) * cos(theta1)

                val x01 =  radius * cos(phi1) * sin(theta2)
                val y01 =  radius * sin(phi1)
                val z01 = -radius * cos(phi1) * cos(theta2)

                val x11 =  radius * cos(phi2) * sin(theta2)
                val y11 =  radius * sin(phi2)
                val z11 = -radius * cos(phi2) * cos(theta2)

                domeVerts.addAll(listOf(x00, y00, z00, x01, y01, z01, x10, y10, z10))
                domeUvs.addAll(listOf(u1, v1, u2, v1, u1, v2))

                domeVerts.addAll(listOf(x10, y10, z10, x01, y01, z01, x11, y11, z11))
                domeUvs.addAll(listOf(u1, v2, u2, v1, u2, v2))
            }
        }
        domeVertexCount = domeVerts.size / 3
        domeVertexBuffer = createFloatBuffer(domeVerts.toFloatArray())
        domeUvBuffer = createFloatBuffer(domeUvs.toFloatArray())

        // =========================================================================
        // 3. 构建 巨幕影院弧形屏幕 (弧度 80°，距离视点 28m，左右严格无镜像)
        // =========================================================================
        val cinemaVerts = mutableListOf<Float>()
        val cinemaUvs = mutableListOf<Float>()
        val screenCols = 32
        val arcTotal = Math.toRadians(80.0).toFloat()
        val screenDist = 28f
        val halfH = 10f

        for (c in 0 until screenCols) {
            val a1 = -arcTotal / 2 + arcTotal * c / screenCols
            val a2 = -arcTotal / 2 + arcTotal * (c + 1) / screenCols

            val x1 = screenDist * sin(a1)
            val z1 = -screenDist * cos(a1)
            val x2 = screenDist * sin(a2)
            val z2 = -screenDist * cos(a2)

            val u1 = c.toFloat() / screenCols
            val u2 = (c + 1).toFloat() / screenCols

            // 三角形 1
            cinemaVerts.addAll(listOf(x1, halfH, z1, x2, halfH, z2, x1, -halfH, z1))
            cinemaUvs.addAll(listOf(u1, 1f, u2, 1f, u1, 0f))

            // 三角形 2
            cinemaVerts.addAll(listOf(x2, halfH, z2, x2, -halfH, z2, x1, -halfH, z1))
            cinemaUvs.addAll(listOf(u2, 1f, u2, 0f, u1, 0f))
        }
        cinemaVertexCount = cinemaVerts.size / 3
        cinemaVertexBuffer = createFloatBuffer(cinemaVerts.toFloatArray())
        cinemaUvBuffer = createFloatBuffer(cinemaUvs.toFloatArray())
    }

    private fun createFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(array)
                position(0)
            }
        }
    }

    fun initShader() {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShader)
            GLES20.glLinkProgram(it)
        }

        uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uUVRangeLoc = GLES20.glGetUniformLocation(program, "uUVRange")
        sTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
    }

    fun draw(
        mvpMatrix: FloatArray,
        stMatrix: FloatArray,
        textureId: Int,
        geometry: GeometryType,
        uMin: Float = 0f,
        uMax: Float = 1f,
        vMin: Float = 0f,
        vMax: Float = 1f
    ) {
        GLES20.glUseProgram(program)

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniform4f(uUVRangeLoc, uMin, uMax, vMin, vMax)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(sTextureLoc, 0)

        val vBuf: FloatBuffer
        val uvBuf: FloatBuffer
        val count: Int

        when (geometry) {
            GeometryType.SPHERE_360 -> {
                vBuf = sphereVertexBuffer
                uvBuf = sphereUvBuffer
                count = sphereVertexCount
            }
            GeometryType.DOME_180 -> {
                vBuf = domeVertexBuffer
                uvBuf = domeUvBuffer
                count = domeVertexCount
            }
            GeometryType.CINEMA_SCREEN -> {
                vBuf = cinemaVertexBuffer
                uvBuf = cinemaUvBuffer
                count = cinemaVertexCount
            }
        }

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vBuf)

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
        }
    }
}
