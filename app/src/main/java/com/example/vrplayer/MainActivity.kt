package com.example.vrplayer

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.example.vrplayer.databinding.ActivityMainBinding
import java.io.File
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var lanShareManager: LanShareManager

    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideControls() }

    private val hideHudRunnable = Runnable {
        binding.gestureHud.animate().alpha(0f).setDuration(250).withEndAction {
            binding.gestureHud.visibility = View.GONE
        }.start()
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updatePlaybackProgress()
            mainHandler.postDelayed(this, 500)
        }
    }

    private var isUIVisible = true
    private var isStereo = false
    private var isViewLocked = false
    private var isGyroEnabled = true
    private var isKeepAwake = true
    private var playbackSpeedIndex = 1
    private val speedList = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private var hasInitializedPlayback = false
    private var initialTouchBrightness = 0.5f
    private var initialTouchVolume = 0
    private var initialTouchPosition = 0L

    // 核心安全机制：使用现代 Storage Access Framework 打开本地视频，防闪退
    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        handleSelectedVideo(uri)
    }

    // 备用选择器
    private val getContentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        handleSelectedVideo(uri)
    }

    // 存储权限申请（用于兼容部分老旧手机直接读取文件）
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        launchSystemFilePicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isKeepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        hideSystemUI()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initPlayer()
        initSensors()
        initGestures()
        initUIControls()

        // 静默启动局域网后台探测，不弹窗、不产生 HUD 骚扰
        lanShareManager = LanShareManager(this)
        lanShareManager.startBackgroundDiscovery()

        resetAutoHideTimer()
    }

    private fun initPlayer() {
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            setMediaCodecSelector(MediaCodecSelector.DEFAULT)
            setEnableDecoderFallback(true)
        }

        player = ExoPlayer.Builder(this, renderersFactory).build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    binding.btnPlayPause.text = if (isPlaying) "⏸ 暂停" else "▶ 播放"
                } catch (e: Throwable) {}
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                try {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> showHud("正在缓冲视频...")
                        Player.STATE_READY -> {
                            binding.tvTimeTotal.text = formatDuration(player.duration)
                            // 硬件解码就绪提示停留时间减半 (750ms)
                            showHud("▶ 硬件解码就绪", 750L)
                        }
                        Player.STATE_ENDED -> showHud("视频播放完毕")
                    }
                } catch (e: Throwable) {}
            }

            override fun onRenderedFirstFrame() {
                runOnUiThread {
                    try {
                        showHud("▶ 画面硬解就绪，正在渲染", 750L)
                    } catch (e: Throwable) {}
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    runOnUiThread {
                        try {
                            binding.tvVideoTitle.text = "${binding.tvVideoTitle.text} (${videoSize.width}x${videoSize.height})"
                        } catch (e: Throwable) {}
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val errorMsg = error.message ?: "解码或网络错误"
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "视频无法解码: $errorMsg", Toast.LENGTH_LONG).show()
                    showHud("⚠ 播放失败，请尝试其他格式")
                }
            }
        })

        binding.vrSurfaceView.renderer.setSurfaceCreateCallback { surface ->
            runOnUiThread {
                player.setVideoSurface(surface)
                // 启动时不自动播放样片，保持待机状态静候用户自选片源
            }
        }
    }

    private fun playUri(uri: Uri, title: String) {
        try {
            // 关键：切换视频前先停止旧视频硬解管线，防止 MediaCodec 状态竞争导致 Native 闪退
            player.stop()
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            binding.tvVideoTitle.text = title
        } catch (e: Throwable) {
            Toast.makeText(this, "播放器加载异常: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedVideo(uri: Uri?) {
        if (uri == null) return
        try {
            // 1. 若为 content:// 协议，持久化读取权限，防止 ExoPlayer 异步线程读取时抛出 SecurityException 闪退
            if (uri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // 部分第三方文件管理器若不支持 persistable 则忽略
                }
            }

            // 2. 安全查询文件名（严格区分 content 与 file 协议，防止 IllegalArgumentException 闪退）
            val fileName = queryFileName(uri)

            // 3. 切换播放本地视频
            playUri(uri, fileName)
            autoDetectAndApplyVRFormat(fileName)
            showHud("正在硬件解码本地视频: $fileName")
        } catch (e: Throwable) {
            Toast.makeText(this, "打开视频失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryFileName(uri: Uri): String {
        return try {
            if (uri.scheme == "content") {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) cursor.getString(idx) else null
                    } else null
                }
            } else if (uri.scheme == "file") {
                uri.path?.let { File(it).name }
            } else null
        } catch (e: Throwable) {
            null
        } ?: uri.lastPathSegment ?: "本地视频"
    }

    private fun checkStoragePermissionAndPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            launchSystemFilePicker()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun launchSystemFilePicker() {
        try {
            openDocumentLauncher.launch(arrayOf("video/*"))
        } catch (e: Exception) {
            try {
                getContentLauncher.launch("video/*")
            } catch (e2: Exception) {
                Toast.makeText(this, "系统未找到文件管理器，建议使用网络地址播放", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    private fun initGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleUIVisibility()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (player.isPlaying) {
                    player.pause()
                    showHud("⏸ 暂停")
                } else {
                    player.play()
                    showHud("▶ 播放")
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                val screenW = (binding.vrSurfaceView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
                val screenH = (binding.vrSurfaceView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()

                val startX = e1.x
                val startY = e1.y

                // 1. 画面下方 1/4 (startY >= screenH * 0.75f): 进度拖动区域
                if (startY >= screenH * 0.75f) {
                    val deltaX = e2.x - startX
                    if (abs(deltaX) > 10f) {
                        val seekSec = ((deltaX / (screenW * 0.75f)) * 90f).toInt()
                        val duration = player.duration.coerceAtLeast(0L)
                        val targetPos = (initialTouchPosition + seekSec * 1000L).coerceIn(0L, duration)
                        player.seekTo(targetPos)
                        val sign = if (seekSec >= 0) "+$seekSec" else "$seekSec"
                        showHud("⏩ 进度: ${sign}s (${formatDuration(targetPos)} / ${formatDuration(duration)})")
                        resetAutoHideTimer()
                    }
                    return true
                }

                // 2. 画面左侧 1/4 (startX <= screenW * 0.25f, 处于上方 3/4 区域): 亮度调节区域
                if (startX <= screenW * 0.25f) {
                    val deltaY = e1.y - e2.y // 手指上滑为正(增亮)，下滑为负(变暗)
                    val newBrightness = (initialTouchBrightness + deltaY / (screenH * 0.6f)).coerceIn(0.05f, 1.0f)
                    val lp = window.attributes
                    lp.screenBrightness = newBrightness
                    window.attributes = lp
                    showHud("🔆 亮度: ${(newBrightness * 100).toInt()}%")
                    resetAutoHideTimer()
                    return true
                }

                // 3. 画面右侧 1/4 (startX >= screenW * 0.75f, 处于上方 3/4 区域): 音量调节区域
                if (startX >= screenW * 0.75f) {
                    val deltaY = e1.y - e2.y // 手指上滑为正(加音量)，下滑为负(减音量)
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val newVol = (initialTouchVolume + (deltaY / (screenH * 0.6f) * maxVol).toInt()).coerceIn(0, maxVol)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                    val pct = (newVol.toFloat() / maxVol * 100).toInt()
                    showHud("🔊 音量: $pct%")
                    binding.btnMute.text = "🔊 $pct%"
                    resetAutoHideTimer()
                    return true
                }

                // 4. 中间剩下其他区域 (0.25 * screenW < startX < 0.75 * screenW 且 startY < screenH * 0.75f): 画面视角调整
                if (!isViewLocked) {
                    binding.vrSurfaceView.renderer.addManualPan(distanceX * 0.1f, distanceY * 0.1f)
                }
                return true
            }
        })

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                binding.vrSurfaceView.renderer.adjustFov(scaleFactor)
                val curFov = binding.vrSurfaceView.renderer.getFov().toInt()
                showHud("🔍 视场角 (FOV): $curFov°")
                resetAutoHideTimer()
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val lp = window.attributes
            initialTouchBrightness = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
            initialTouchVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            initialTouchPosition = player.currentPosition
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun initUIControls() {
        binding.tvVideoTitle.text = "VR 播放器 (待机中，请点击片源选择)"

        binding.btnSource.setOnClickListener {
            resetAutoHideTimer()
            showSourceDialog()
        }

        binding.btnVRFormat.setOnClickListener {
            resetAutoHideTimer()
            showVRFormatDialog()
        }

        binding.btnToggleSplit.setOnClickListener {
            resetAutoHideTimer()
            isStereo = !isStereo
            binding.vrSurfaceView.renderer.setStereoMode(isStereo)
            binding.btnToggleSplit.text = if (isStereo) "🥽 双目分屏" else "📱 单屏模式"
            binding.btnToggleSplit.setTextColor(if (isStereo) getColor(R.color.cyan_accent) else getColor(R.color.white))

            binding.crosshairOverlay.visibility = if (isStereo && binding.cbCrosshair.isChecked) View.VISIBLE else View.GONE
            showHud(if (isStereo) "已开启 VR 双目分屏" else "已切换至单屏模式")
        }

        binding.btnToggleGyro.setOnClickListener {
            resetAutoHideTimer()
            isGyroEnabled = !isGyroEnabled
            binding.btnToggleGyro.text = if (isGyroEnabled) "🧭 陀螺仪: 开启" else "🧭 陀螺仪: 关闭"
            binding.btnToggleGyro.setTextColor(if (isGyroEnabled) getColor(R.color.green_accent) else getColor(R.color.text_muted))
            showHud(if (isGyroEnabled) "陀螺仪体感已开启" else "陀螺仪体感已暂停")
        }

        binding.btnLockView.setOnClickListener {
            resetAutoHideTimer()
            isViewLocked = !isViewLocked
            binding.btnLockView.text = if (isViewLocked) "🔒 视角已锁定" else "🔓 视角跟随"
            binding.btnLockView.setTextColor(if (isViewLocked) getColor(R.color.red_accent) else getColor(R.color.white))
            showHud(if (isViewLocked) "视角锁定（避免头部晃动）" else "视角跟随（恢复头部感应）")
        }

        binding.btnRecenter.setOnClickListener {
            resetAutoHideTimer()
            binding.vrSurfaceView.renderer.resetOrientation()
            showHud("🎯 视角已正向归中复位")
        }

        binding.btnToggleIpd.setOnClickListener {
            resetAutoHideTimer()
            val isShown = binding.ipdCardLayout.visibility == View.VISIBLE
            binding.ipdCardLayout.visibility = if (isShown) View.GONE else View.VISIBLE
        }

        binding.btnKeepAwake.setOnClickListener {
            resetAutoHideTimer()
            isKeepAwake = !isKeepAwake
            if (isKeepAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.btnKeepAwake.text = "💡 屏幕常亮: 开"
                binding.btnKeepAwake.setTextColor(getColor(R.color.white))
                showHud("已保持屏幕常亮")
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.btnKeepAwake.text = "💡 屏幕常亮: 关"
                binding.btnKeepAwake.setTextColor(getColor(R.color.text_muted))
                showHud("常亮已关闭")
            }
        }

        binding.btnCloseIpd.setOnClickListener {
            binding.ipdCardLayout.visibility = View.GONE
        }

        binding.sbIpd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ipdOffset = progress - 30
                binding.vrSurfaceView.renderer.setIpdOffset(ipdOffset)
                val estimatedMm = 64 + ipdOffset / 3
                binding.tvIpdValue.text = "$estimatedMm mm (偏移: ${ipdOffset}px)"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
        })

        binding.btnIpdMinus.setOnClickListener {
            resetAutoHideTimer()
            binding.sbIpd.progress = (binding.sbIpd.progress - 3).coerceAtLeast(0)
        }

        binding.btnIpdPlus.setOnClickListener {
            resetAutoHideTimer()
            binding.sbIpd.progress = (binding.sbIpd.progress + 3).coerceAtMost(60)
        }

        binding.btnIpdPreset58.setOnClickListener {
            resetAutoHideTimer()
            binding.sbIpd.progress = 12
        }

        binding.btnIpdPreset64.setOnClickListener {
            resetAutoHideTimer()
            binding.sbIpd.progress = 30
        }

        binding.btnIpdPreset70.setOnClickListener {
            resetAutoHideTimer()
            binding.sbIpd.progress = 48
        }

        binding.cbCrosshair.setOnCheckedChangeListener { _, isChecked ->
            resetAutoHideTimer()
            binding.crosshairOverlay.visibility = if (isChecked && isStereo) View.VISIBLE else View.GONE
            showHud(if (isChecked) "对准准星与中缝线已开启" else "对准准星已关闭")
        }

        binding.btnPlayPause.setOnClickListener {
            resetAutoHideTimer()
            if (player.isPlaying) {
                player.pause()
                showHud("⏸ 暂停")
            } else {
                player.play()
                showHud("▶ 播放")
            }
        }

        binding.btnRewind.setOnClickListener {
            resetAutoHideTimer()
            val target = (player.currentPosition - 10000).coerceAtLeast(0)
            player.seekTo(target)
            showHud("⏪ 快退 10秒 (${formatDuration(target)})")
        }

        binding.btnFastForward.setOnClickListener {
            resetAutoHideTimer()
            val target = (player.currentPosition + 10000).coerceAtMost(player.duration)
            player.seekTo(target)
            showHud("⏩ 快进 10秒 (${formatDuration(target)})")
        }

        binding.btnSpeed.setOnClickListener {
            resetAutoHideTimer()
            playbackSpeedIndex = (playbackSpeedIndex + 1) % speedList.size
            val speed = speedList[playbackSpeedIndex]
            player.setPlaybackSpeed(speed)
            binding.btnSpeed.text = "${speed}x"
            showHud("播放倍速: ${speed}x")
        }

        binding.btnMute.setOnClickListener {
            resetAutoHideTimer()
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curVol > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                binding.btnMute.text = "🔇 静音"
                showHud("🔇 已静音")
            } else {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val resumeVol = maxVol / 2
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, resumeVol, 0)
                binding.btnMute.text = "🔊 50%"
                showHud("🔊 已取消静音")
            }
        }

        binding.sbTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    val targetPos = (player.duration * (progress / 1000f)).toLong()
                    binding.tvTimeCurrent.text = formatDuration(targetPos)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                mainHandler.removeCallbacks(autoHideRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null && player.duration > 0) {
                    val targetPos = (player.duration * (seekBar.progress / 1000f)).toLong()
                    player.seekTo(targetPos)
                }
                resetAutoHideTimer()
            }
        })
    }

    private fun showSourceDialog() {
        val options = arrayOf(
            "📁 本地视频文件 (存储选择器)",
            "🌐 网络串流地址 (HTTP / HTTPS / RTSP / HLS)",
            "🖥️ 局域网共享设备 (SMB / WebDAV / NAS / PC)"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("选择视频片源")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkStoragePermissionAndPick()
                    1 -> showNetworkUrlDialog()
                    2 -> showLanDevicesDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNetworkUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/video.mp4 或 rtsp://..."
            setTextColor(getColor(R.color.white))
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("输入网络串流地址")
            .setMessage("支持标准 HTTP / HTTPS / RTSP / HLS 视频网络直链")
            .setView(input)
            .setPositiveButton("立即播放") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    playUri(Uri.parse(url), "网络视频: $url")
                    autoDetectAndApplyVRFormat(url)
                    showHud("正在缓冲硬解网络视频")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLanDevicesDialog() {
        val devices = lanShareManager.devices
        val displayItems = mutableListOf<String>()
        if (devices.isEmpty()) {
            displayItems.add("⏳ 正在后台深度扫描局域网电脑 (扫描445/NetBIOS/ARP)...")
        } else {
            for (d in devices) {
                displayItems.add("🖥️ ${d.name}\n   📍 ${d.host}:${d.port} · ${d.protocol}")
            }
        }
        displayItems.add("➕ 手动输入设备 IP / 地址")
        displayItems.add("🔄 重新扫描局域网")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("局域网设备列表")
            .setItems(displayItems.toTypedArray()) { _, which ->
                if (devices.isEmpty()) {
                    if (which == 1) {
                        showManualLanDeviceDialog()
                    } else {
                        showHud("正在重新扫描当前网段...")
                        lanShareManager.startBackgroundDiscovery {
                            showHud("扫描已完成")
                        }
                        mainHandler.postDelayed({ showLanDevicesDialog() }, 800)
                    }
                    return@setItems
                }

                if (which < devices.size) {
                    showLanAuthDialog(devices[which])
                } else if (which == devices.size) {
                    showManualLanDeviceDialog()
                } else {
                    showHud("正在深度扫描局域网 (端口445/NetBIOS)...")
                    lanShareManager.startBackgroundDiscovery {
                        showHud("局域网设备已刷新")
                    }
                    mainHandler.postDelayed({ showLanDevicesDialog() }, 800)
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showManualLanDeviceDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 20)
        }

        val etName = EditText(this).apply {
            hint = "设备名称 (如: 我的电脑 / NAS)"
            setTextColor(getColor(R.color.white))
        }
        val etHost = EditText(this).apply {
            hint = "局域网 IP (如: 192.168.1.100)"
            setTextColor(getColor(R.color.white))
        }
        val etPort = EditText(this).apply {
            hint = "端口号 (Windows 共享默认为 445)"
            setText("445")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(getColor(R.color.white))
        }

        layout.addView(etName)
        layout.addView(etHost)
        layout.addView(etPort)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("手动指定局域网设备")
            .setView(layout)
            .setPositiveButton("下一步：连接设置") { _, _ ->
                val host = etHost.text.toString().trim()
                if (host.isNotEmpty()) {
                    val name = etName.text.toString().trim().ifEmpty { "局域网设备 ($host)" }
                    val port = etPort.text.toString().trim().toIntOrNull() ?: 445
                    val newDevice = LanDevice(
                        id = "$host:$port",
                        name = name,
                        host = host,
                        port = port,
                        protocol = if (port == 445 || port == 139) "SMB" else "WebDAV / HTTP",
                        isCustom = true
                    )
                    lanShareManager.addOrUpdateDevice(newDevice)
                    showLanAuthDialog(newDevice)
                } else {
                    Toast.makeText(this, "请输入正确的设备 IP 地址", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLanAuthDialog(device: LanDevice) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 20)
        }

        val tvInfo = TextView(this).apply {
            text = "目标设备: ${device.name}\n网络地址: ${device.host}:${device.port} (${device.protocol})"
            setTextColor(getColor(R.color.white))
            setPadding(0, 0, 0, 20)
        }

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val rbAnonymous = RadioButton(this).apply {
            id = View.generateViewId()
            text = "匿名 / 来宾连接 (Guest 免密)"
            setTextColor(getColor(R.color.white))
            isChecked = true
        }
        val rbAuth = RadioButton(this).apply {
            id = View.generateViewId()
            text = "账户密码验证 (输入 Windows 用户名与密码)"
            setTextColor(getColor(R.color.white))
        }
        radioGroup.addView(rbAnonymous)
        radioGroup.addView(rbAuth)

        val etUser = EditText(this).apply {
            hint = "Windows / 共享用户名"
            setTextColor(getColor(R.color.white))
            visibility = View.GONE
        }
        val etPass = EditText(this).apply {
            hint = "共享密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(getColor(R.color.white))
            visibility = View.GONE
        }
        val etShare = EditText(this).apply {
            hint = "共享文件夹名称 (可选，如 Videos 或 Share，留空自动检测)"
            setTextColor(getColor(R.color.white))
            setPadding(0, 16, 0, 10)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isAuth = (checkedId == rbAuth.id)
            etUser.visibility = if (isAuth) View.VISIBLE else View.GONE
            etPass.visibility = if (isAuth) View.VISIBLE else View.GONE
        }

        layout.addView(tvInfo)
        layout.addView(radioGroup)
        layout.addView(etUser)
        layout.addView(etPass)
        layout.addView(etShare)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("连接局域网共享")
            .setView(layout)
            .setPositiveButton("连接并列出文件") { _, _ ->
                val isAnon = rbAnonymous.isChecked
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                val customShare = etShare.text.toString().trim()
                val authConfig = LanAuthConfig(
                    isAnonymous = isAnon,
                    username = user,
                    password = pass,
                    port = device.port,
                    protocol = device.protocol,
                    customShare = customShare
                )

                showHud("正在连接 ${device.name}...")
                val startPath = if (customShare.isNotEmpty()) "/$customShare" else "/"
                lanShareManager.fetchDirectory(device, authConfig, startPath) { success, items, error ->
                    if (success) {
                        showLanFileExplorer(device, authConfig, startPath, items)
                    } else {
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("连接共享失败")
                            .setMessage(error ?: "未知错误")
                            .setPositiveButton("重新配置") { _, _ -> showLanAuthDialog(device) }
                            .setNegativeButton("返回", null)
                            .show()
                        showHud("连接失败: $error")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLanFileExplorer(
        device: LanDevice,
        auth: LanAuthConfig,
        currentPath: String,
        items: List<LanFileItem>
    ) {
        val displayList = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // 非根目录时，提供返回上一层
        if (currentPath != "/" && currentPath.isNotEmpty()) {
            displayList.add("📁 .. [返回上一层目录]")
            actions.add {
                val parentPath = currentPath.substringBeforeLast('/', "").ifEmpty { "/" }
                showHud("正在读取目录: $parentPath")
                lanShareManager.fetchDirectory(device, auth, parentPath) { success, newItems, _ ->
                    if (success) {
                        showLanFileExplorer(device, auth, parentPath, newItems)
                    }
                }
            }
        }

        for (item in items) {
            if (item.isDirectory) {
                displayList.add("📁 ${item.name}")
                actions.add {
                    val nextPath = item.path
                    showHud("进入文件夹: ${item.name}")
                    lanShareManager.fetchDirectory(device, auth, nextPath) { success, newItems, error ->
                        if (success) {
                            showLanFileExplorer(device, auth, nextPath, newItems)
                        } else {
                            Toast.makeText(this, "进入目录失败: $error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                val sizeStr = formatFileSize(item.sizeBytes)
                displayList.add("🎬 ${item.name} $sizeStr")
                actions.add {
                    val streamUri = Uri.parse(item.streamUri)
                    playUri(streamUri, "局域网: ${item.name}")
                    autoDetectAndApplyVRFormat(item.name)
                    showHud("▶ 正在播放: ${item.name}")
                }
            }
        }

        if (displayList.isEmpty()) {
            displayList.add("（此目录下暂无共享视频文件）")
            actions.add {}
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("📁 ${device.name}: $currentPath")
            .setItems(displayList.toTypedArray()) { _, which ->
                if (which < actions.size) {
                    actions[which].invoke()
                }
            }
            .setNegativeButton("退出浏览", null)
            .show()
    }

    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return ""
        val mb = sizeBytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format("(%.1f GB)", mb / 1024.0)
        } else {
            String.format("(%.0f MB)", mb)
        }
    }

    private fun autoDetectAndApplyVRFormat(name: String) {
        val lower = name.lowercase()
        val detectedFormat = when {
            lower.contains("360") && (lower.contains("tb") || lower.contains("tab") || lower.contains("ou") || lower.contains("top-bottom") || lower.contains("上下")) -> VRVideoFormat.TB_360
            lower.contains("360") && lower.contains("sbs") -> VRVideoFormat.SBS_360
            lower.contains("360") -> VRVideoFormat.MONO_360
            lower.contains("180") && (lower.contains("tb") || lower.contains("tab") || lower.contains("ou") || lower.contains("top-bottom") || lower.contains("上下")) -> VRVideoFormat.TB_180
            lower.contains("tb") || lower.contains("tab") || lower.contains("ou") || lower.contains("top-bottom") || lower.contains("上下") -> VRVideoFormat.CINEMA_3D_TB
            lower.contains("sbs") || lower.contains("180") -> VRVideoFormat.SBS_180
            lower.contains("3d") -> VRVideoFormat.CINEMA_3D_SBS
            else -> VRVideoFormat.CINEMA_2D
        }
        binding.vrSurfaceView.renderer.setVRFormat(detectedFormat)
        updateVRFormatButtonText(detectedFormat)
    }

    private fun showVRFormatDialog() {
        val formats = VRVideoFormat.values()
        val formatNames = formats.map { it.title }.toTypedArray()
        val curIndex = formats.indexOf(binding.vrSurfaceView.renderer.getVRFormat())

        val isSwapped = binding.vrSurfaceView.renderer.isEyesSwapped()
        val swapButtonText = if (isSwapped) "👁️ 恢复默认眼序 (已对调)" else "🔄 左右/上下眼对调"

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("选择 VR 画面格式")
            .setSingleChoiceItems(formatNames, curIndex) { dialog, which ->
                val selectedFormat = formats[which]
                binding.vrSurfaceView.renderer.setVRFormat(selectedFormat)
                updateVRFormatButtonText(selectedFormat)
                showHud("已切换画质格式: ${selectedFormat.title}")
                dialog.dismiss()
            }
            .setNeutralButton(swapButtonText) { _, _ ->
                val newSwapped = !binding.vrSurfaceView.renderer.isEyesSwapped()
                binding.vrSurfaceView.renderer.setEyesSwapped(newSwapped)
                showHud(if (newSwapped) "已开启：左右/上下眼对调" else "已恢复：正常眼序")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateVRFormatButtonText(format: VRVideoFormat) {
        binding.btnVRFormat.text = "🌐 ${format.title}"
    }

    private fun updatePlaybackProgress() {
        if (player.duration > 0) {
            val progress = ((player.currentPosition.toFloat() / player.duration) * 1000).toInt()
            binding.sbTimeline.progress = progress
            binding.tvTimeCurrent.text = formatDuration(player.currentPosition)
            binding.tvTimeTotal.text = formatDuration(player.duration)
        }
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0 || millis == androidx.media3.common.C.TIME_UNSET) return "00:00"
        val totalSec = millis / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hour = totalSec / 3600
        val sMin = min.toString().padStart(2, '0')
        val sSec = sec.toString().padStart(2, '0')
        return if (hour > 0) {
            val sHour = hour.toString().padStart(2, '0')
            "$sHour:$sMin:$sSec"
        } else {
            "$sMin:$sSec"
        }
    }

    private fun showHud(text: String, durationMs: Long = 1200L) {
        binding.tvGestureHud.text = text
        binding.gestureHud.visibility = View.VISIBLE
        binding.gestureHud.alpha = 1f

        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, durationMs)
    }

    private fun toggleUIVisibility() {
        isUIVisible = !isUIVisible
        if (isUIVisible) {
            showControls()
        } else {
            hideControls()
        }
    }

    private fun showControls() {
        isUIVisible = true
        binding.controlsOverlay.visibility = View.VISIBLE
        binding.controlsOverlay.animate().alpha(1f).setDuration(200).start()
        resetAutoHideTimer()
    }

    private fun hideControls() {
        isUIVisible = false
        binding.controlsOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            binding.controlsOverlay.visibility = View.GONE
        }.start()
        hideSystemUI()
    }

    private fun resetAutoHideTimer() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, 4000)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isGyroEnabled || isViewLocked) return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            val rotation = display?.rotation ?: Surface.ROTATION_90
            binding.vrSurfaceView.renderer.updateSensorMatrix(rotationMatrix, rotation)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        binding.vrSurfaceView.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        mainHandler.post(updateProgressRunnable)
        if (isKeepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.vrSurfaceView.onPause()
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.removeCallbacks(hideHudRunnable)
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
