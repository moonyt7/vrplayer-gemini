package com.example.vrplayer

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

class VRGLSurfaceView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {
    val renderer = VRRenderer()

    init {
        setEGLContextClientVersion(2)
        // 关键：切换到文件选择器等其他界面时保留 EGL 上下文，防止返回时纹理与 Surface 被系统销毁导致 Native 闪退
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
