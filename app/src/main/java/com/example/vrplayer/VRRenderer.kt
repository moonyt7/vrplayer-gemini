package com.example.vrplayer

import android.graphics.SurfaceTexture
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class VRVideoFormat(val title: String) {
    MONO_360("360° 单目全景"),
    SBS_360("360° 左右 3D"),
    TB_360("360° 上下 3D"),
    SBS_180("180° 左右 3D"),
    TB_180("180° 上下 3D"),
    CINEMA_3D_SBS("3D 左右巨幕"),
    CINEMA_3D_TB("3D 上下巨幕"),
    CINEMA_2D("2D 弧面巨幕")
}

data class UVBounds(val uMin: Float, val uMax: Float, val vMin: Float, val vMax: Float)

class VRRenderer : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var surfaceCreateCallback: ((Surface) -> Unit)? = null

    private val sphereMesh = SphereMesh(50f)

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    // 虚拟世界与地球坐标系映射矩阵 (OpenGL虚拟世界: +X=东/右, +Y=天空, -Z=北/前方; Android地球坐标系: +X=东, +Y=北, +Z=天空)
    // 列主序矩阵:
    private val worldToEarth = floatArrayOf(
        1f,  0f,  0f, 0f,
        0f,  0f,  1f, 0f,
        0f, -1f,  0f, 0f,
        0f,  0f,  0f, 1f
    )

    // 传感器矩阵与归中校准矩阵
    private val sensorLock = Any()
    private val recenterBaseMatrix = FloatArray(16)
    private val headViewMatrix = FloatArray(16)
    @Volatile private var needsRecenter = true

    private var vrFormat: VRVideoFormat = VRVideoFormat.MONO_360
    private var fov = 75f
    private var isStereo = false
    private var isEyesSwapped = false
    private var ipdPixelOffset = 0
    private var manualYaw = 0f
    private var manualPitch = 0f

    private val frameAvailable = AtomicBoolean(false)
    private var viewportWidth = 1920
    private var viewportHeight = 1080

    init {
        Matrix.setIdentityM(recenterBaseMatrix, 0)
        Matrix.setIdentityM(headViewMatrix, 0)
        Matrix.setIdentityM(stMatrix, 0)
    }

    fun setSurfaceCreateCallback(callback: (Surface) -> Unit) {
        this.surfaceCreateCallback = callback
        videoSurface?.let { callback(it) }
    }

    fun setVRFormat(format: VRVideoFormat) {
        this.vrFormat = format
    }

    fun getVRFormat(): VRVideoFormat = vrFormat

    fun setStereoMode(stereo: Boolean) {
        this.isStereo = stereo
    }

    fun isStereoMode(): Boolean = isStereo

    fun setEyesSwapped(swapped: Boolean) {
        this.isEyesSwapped = swapped
    }

    fun isEyesSwapped(): Boolean = isEyesSwapped

    fun setIpdOffset(offset: Int) {
        this.ipdPixelOffset = offset
    }

    fun getIpdOffset(): Int = ipdPixelOffset

    // 手势滑动平移：正向跟随手指滑动方向
    fun addManualPan(dx: Float, dy: Float) {
        manualYaw += dx
        manualPitch = (manualPitch + dy).coerceIn(-85f, 85f)
    }

    fun adjustFov(scaleFactor: Float) {
        fov = (fov / scaleFactor).coerceIn(45f, 110f)
    }

    fun getFov(): Float = fov

    // 核心：视角复位重置。立即将当前佩戴朝向作为 0° 正前方视线，清除所有倾斜和偏移
    fun resetOrientation() {
        manualYaw = 0f
        manualPitch = 0f
        synchronized(sensorLock) {
            Matrix.setIdentityM(headViewMatrix, 0)
            needsRecenter = true
        }
    }

    fun updateSensorMatrix(rawMatrix: FloatArray, displayRotation: Int) {
        // 根据显示屏旋转角度映射 Android 传感器坐标轴 (官方标准 SensorManager.remapCoordinateSystem)
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_0 -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            else -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        }

        val remappedMatrix = FloatArray(16)
        SensorManager.remapCoordinateSystem(rawMatrix, axisX, axisY, remappedMatrix)

        // Android SensorManager 的 remappedMatrix 为行主序矩阵 (Row-Major)，其数组在 OpenGL 列主序 (Column-Major) 下读取时，
        // 自动等价于其正交转置 R^T (即从地球坐标系变换至相机视口坐标系)。
        // 故与 worldToEarth 相乘后，直接得出观察矩阵 currentW (从 OpenGL 虚拟世界变换至相机视口坐标系):
        val currentW = FloatArray(16)
        Matrix.multiplyMM(currentW, 0, remappedMatrix, 0, worldToEarth, 0)

        synchronized(sensorLock) {
            if (needsRecenter) {
                // 计算当前视线在世界水平地面 (X-Z平面) 上的朝向角 (Yaw)
                // 在 OpenGL 列主序矩阵 currentW 中，相机观察坐标系的 -Z (前视方向) 在世界空间的水平分量:
                val lx = -currentW[2]
                val lz = -currentW[10]
                val recenterYawRad = Math.atan2(lx.toDouble(), (-lz).toDouble())
                val recenterYawDeg = Math.toDegrees(recenterYawRad).toFloat()

                // 构建水平朝向重置矩阵：绕世界 Y 轴旋转 -recenterYawDeg
                // 仅重置水平偏航角(Yaw)，使当前朝向平滑对齐 0° 正前方视线，
                // 同时严格保留物理重力水平基准(Pitch/Roll)，彻底消除地平线倾斜与轴向串扰
                Matrix.setRotateM(recenterBaseMatrix, 0, -recenterYawDeg, 0f, 1f, 0f)
                needsRecenter = false
            }

            // 计算相对视点变换矩阵：V_head = currentW * recenterBaseMatrix
            // 将重置校准后的虚拟视频世界坐标映射到当前观察者的相机视口坐标：
            Matrix.multiplyMM(headViewMatrix, 0, currentW, 0, recenterBaseMatrix, 0)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val mainHandler = Handler(Looper.getMainLooper())
        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener(this@VRRenderer, mainHandler)
        }

        val surf = Surface(surfaceTexture)
        videoSurface = surf
        surfaceCreateCallback?.invoke(surf)

        sphereMesh.initShader()
        Matrix.setIdentityM(stMatrix, 0)
        resetOrientation()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    /**
     * 计算指定视眼（左眼/右眼）在当前 VR 格式下的 UV 映射裁切范围
     * 支持 左右 3D (SBS)、上下 3D (Top-Bottom / Over-Under)、360°/180° 以及眼序对调
     */
    private fun getEyeUV(isLeftEye: Boolean): UVBounds {
        val effectiveIsLeft = if (isEyesSwapped) !isLeftEye else isLeftEye

        return when (vrFormat) {
            VRVideoFormat.SBS_360, VRVideoFormat.SBS_180, VRVideoFormat.CINEMA_3D_SBS -> {
                // 左右 3D 格式 (SBS)：左半部为左眼，右半部为右眼
                if (effectiveIsLeft) {
                    UVBounds(0f, 0.5f, 0f, 1f)
                } else {
                    UVBounds(0.5f, 1f, 0f, 1f)
                }
            }
            VRVideoFormat.TB_360, VRVideoFormat.TB_180, VRVideoFormat.CINEMA_3D_TB -> {
                // 上下 3D 格式 (Top-Bottom / Over-Under)：
                // 工业标准：上半部为左眼 (V: 0.5 ~ 1.0)，下半部为右眼 (V: 0.0 ~ 0.5)
                if (effectiveIsLeft) {
                    UVBounds(0f, 1f, 0.5f, 1f)
                } else {
                    UVBounds(0f, 1f, 0f, 0.5f)
                }
            }
            VRVideoFormat.MONO_360, VRVideoFormat.CINEMA_2D -> {
                // 2D 单目 / 全景：双眼均使用完整画面
                UVBounds(0f, 1f, 0f, 1f)
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable.compareAndSet(true, false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
            } catch (e: Exception) {
                // 容错处理
            }
        }

        // 1. 每一帧起始，必须先关闭 Scissor Test 并确保完整清屏整个画面为纯黑
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        // 综合计算观察矩阵 (手势拖拽平移 + 归中校准后的陀螺仪姿态)
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.rotateM(viewMatrix, 0, -manualPitch, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, -manualYaw, 0f, 1f, 0f)

        synchronized(sensorLock) {
            Matrix.multiplyMM(viewMatrix, 0, viewMatrix, 0, headViewMatrix, 0)
        }

        val geometry = when (vrFormat) {
            VRVideoFormat.MONO_360, VRVideoFormat.SBS_360, VRVideoFormat.TB_360 -> GeometryType.SPHERE_360
            VRVideoFormat.SBS_180, VRVideoFormat.TB_180 -> GeometryType.DOME_180
            VRVideoFormat.CINEMA_3D_SBS, VRVideoFormat.CINEMA_3D_TB, VRVideoFormat.CINEMA_2D -> GeometryType.CINEMA_SCREEN
        }

        if (!isStereo) {
            // 单屏非分屏模式：渲染主视眼（左眼）画面，自动消除 3D 左右/上下黑边与重影挤压
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            val aspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1)
            Matrix.perspectiveM(projectionMatrix, 0, fov, aspect, 0.1f, 500f)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            val uv = getEyeUV(isLeftEye = true)
            sphereMesh.draw(mvpMatrix, stMatrix, textureId, geometry, uv.uMin, uv.uMax, uv.vMin, uv.vMax)
        } else {
            // VR 双目分屏模式：左右眼分别应用 Scissor 严格裁剪与纯黑背景填充，彻底杜绝画面重叠残留
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            val halfW = viewportWidth / 2
            val aspect = halfW.toFloat() / viewportHeight.coerceAtLeast(1)
            Matrix.perspectiveM(projectionMatrix, 0, fov, aspect, 0.1f, 500f)

            // 左眼视口
            GLES20.glScissor(0, 0, halfW, viewportHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glViewport(-ipdPixelOffset, 0, halfW, viewportHeight)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            val leftUV = getEyeUV(isLeftEye = true)
            sphereMesh.draw(mvpMatrix, stMatrix, textureId, geometry, leftUV.uMin, leftUV.uMax, leftUV.vMin, leftUV.vMax)

            // 右眼视口
            GLES20.glScissor(halfW, 0, halfW, viewportHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glViewport(halfW + ipdPixelOffset, 0, halfW, viewportHeight)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            val rightUV = getEyeUV(isLeftEye = false)
            sphereMesh.draw(mvpMatrix, stMatrix, textureId, geometry, rightUV.uMin, rightUV.uMax, rightUV.vMin, rightUV.vMax)

            // 双眼绘制完毕后关闭 Scissor Test，防止影响下一帧的全局清屏
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable.set(true)
    }
}
