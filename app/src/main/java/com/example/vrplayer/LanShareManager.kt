package com.example.vrplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Collections
import java.util.Properties
import java.util.concurrent.Executors

data class LanDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val isCustom: Boolean = false
)

data class LanAuthConfig(
    val isAnonymous: Boolean = true,
    val username: String = "",
    val password: String = "",
    val port: Int = 445,
    val protocol: String = "SMB",
    val customShare: String = ""
)

data class LanFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val streamUri: String = "",
    val shareName: String = ""
)

/**
 * 局域网 Windows / Samba / NAS 共享管理器
 * 基于 jcifs-ng 与 SMB2/3 协议：
 * 1. 真实 DCE/RPC (NetrShareEnum) 动态枚举 Windows 电脑上所有共享文件夹（完美支持中文与自定义共享名）；
 * 2. 深度适配 Windows 10/11 的 Guest 访客免密机制与账户密码验证；
 * 3. 配合本地 SmbStreamServer 代理，向播放器提供 HTTP 206 范围断点流，保障大视频毫秒级拖动快进快退。
 */
class LanShareManager(private val context: Context) {

    private val scanner = LanDeviceScanner(context)
    private val discoveredDevices = Collections.synchronizedList(mutableListOf<LanDevice>())
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "LanShareManager"

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "mov", "avi", "wmv", "flv", "webm", "ts", "m4v", "iso", "3gp"
        )

        fun isVideoFile(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return VIDEO_EXTENSIONS.contains(ext)
        }

        /**
         * 构建通用的 jcifs-ng CIFSContext 配置环境
         */
        fun buildCifsContext(isAnon: Boolean, username: String, password: String): CIFSContext {
            val prop = Properties().apply {
                setProperty("jcifs.smb.client.enableSMB2", "true")
                setProperty("jcifs.smb.client.disableSMB1", "false")
                setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
                setProperty("jcifs.smb.client.responseTimeout", "10000")
                setProperty("jcifs.smb.client.soTimeout", "12000")
                setProperty("jcifs.smb.client.connTimeout", "6000")
                setProperty("jcifs.smb.client.listSize", "4096")
            }
            val config = PropertyConfiguration(prop)
            val base = BaseContext(config)

            if (isAnon || username.isBlank()) {
                return try {
                    base.withCredentials(NtlmPasswordAuthenticator(null, "Guest", ""))
                } catch (t: Throwable) {
                    try {
                        base.withAnonymousCredentials()
                    } catch (t2: Throwable) {
                        base.withCredentials(NtlmPasswordAuthenticator())
                    }
                }
            }

            val trimmedUser = username.trim()
            val domain: String?
            val actualUser: String
            when {
                trimmedUser.contains("\\") -> {
                    domain = trimmedUser.substringBefore("\\")
                    actualUser = trimmedUser.substringAfter("\\")
                }
                trimmedUser.contains("/") -> {
                    domain = trimmedUser.substringBefore("/")
                    actualUser = trimmedUser.substringAfter("/")
                }
                else -> {
                    domain = null
                    actualUser = trimmedUser
                }
            }
            return base.withCredentials(NtlmPasswordAuthenticator(domain, actualUser, password))
        }
    }

    val devices: List<LanDevice>
        get() = getDiscoveredDevices()

    fun getDiscoveredDevices(): List<LanDevice> {
        synchronized(discoveredDevices) {
            return ArrayList(discoveredDevices)
        }
    }

    fun addOrUpdateDevice(device: LanDevice) {
        synchronized(discoveredDevices) {
            val idx = discoveredDevices.indexOfFirst { it.host == device.host }
            if (idx >= 0) {
                discoveredDevices[idx] = device
            } else {
                discoveredDevices.add(device)
            }
        }
    }

    fun startBackgroundDiscovery(onComplete: (() -> Unit)? = null) {
        executor.execute {
            scanner.scanNetwork { dev ->
                val lanDev = LanDevice(
                    id = "${dev.host}:${dev.port}",
                    name = dev.name,
                    host = dev.host,
                    port = dev.port,
                    protocol = dev.protocol,
                    isCustom = false
                )
                addOrUpdateDevice(lanDev)
            }
            mainHandler.post { onComplete?.invoke() }
        }
    }

    /**
     * 浏览指定设备的真实文件目录
     */
    fun fetchDirectory(
        device: LanDevice,
        auth: LanAuthConfig,
        path: String,
        callback: (success: Boolean, items: List<LanFileItem>, error: String?) -> Unit
    ) {
        executor.execute {
            try {
                fetchRealSmbDirectory(device, auth, path, callback)
            } catch (e: Throwable) {
                Log.e(TAG, "fetchDirectory error: ${e.message}", e)
                mainHandler.post {
                    callback(false, emptyList(), e.localizedMessage ?: "连接设备失败")
                }
            }
        }
    }

    private fun fetchRealSmbDirectory(
        device: LanDevice,
        auth: LanAuthConfig,
        cleanPath: String,
        callback: (success: Boolean, items: List<LanFileItem>, error: String?) -> Unit
    ) {
        val prop = Properties().apply {
            setProperty("jcifs.smb.client.enableSMB2", "true")
            setProperty("jcifs.smb.client.disableSMB1", "false")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.responseTimeout", "10000")
            setProperty("jcifs.smb.client.soTimeout", "12000")
            setProperty("jcifs.smb.client.connTimeout", "6000")
            setProperty("jcifs.smb.client.listSize", "4096")
        }
        val config = PropertyConfiguration(prop)
        val base = BaseContext(config)

        // 构造候选认证上下文列表（若用户勾选了免密/匿名，将自动适配 Windows Guest 与 Samba 匿名多种认证机制）
        val contextsToTry = mutableListOf<CIFSContext>()
        if (auth.isAnonymous || auth.username.isBlank()) {
            try { contextsToTry.add(base.withCredentials(NtlmPasswordAuthenticator(null, "Guest", ""))) } catch (e: Throwable) {}
            try { contextsToTry.add(base.withCredentials(NtlmPasswordAuthenticator(null, "guest", ""))) } catch (e: Throwable) {}
            try { contextsToTry.add(base.withCredentials(NtlmPasswordAuthenticator(null, "", ""))) } catch (e: Throwable) {}
            try { contextsToTry.add(base.withAnonymousCredentials()) } catch (e: Throwable) {}
            try { contextsToTry.add(base.withCredentials(NtlmPasswordAuthenticator())) } catch (e: Throwable) {}
        } else {
            contextsToTry.add(buildCifsContext(false, auth.username, auth.password))
        }

        var activeContext: CIFSContext? = null
        var lastError: Throwable? = null

        // 探测出可用的认证上下文
        for (ctx in contextsToTry) {
            try {
                val testUrl = if (auth.customShare.isNotBlank()) {
                    "smb://${device.host}/${auth.customShare.trim('/', '\\')}/"
                } else {
                    "smb://${device.host}/"
                }
                val testFile = SmbFile(testUrl, ctx)
                testFile.connect()
                activeContext = ctx
                break
            } catch (t: Throwable) {
                lastError = t
            }
        }

        if (activeContext == null) {
            val errorMsg = lastError?.message ?: ""
            val formattedMsg = when {
                errorMsg.contains("Access is denied", true) || errorMsg.contains("STATUS_ACCESS_DENIED", true) -> {
                    if (auth.isAnonymous) {
                        "访问被拒绝 (Access is denied)：该设备未开启免密访客访问。\n\n解决办法：\n1. 请在弹窗中选择【账户密码验证】，输入电脑的系统用户名与密码；\n2. 或者在 Windows【控制面板 -> 网络和共享中心 -> 高级共享设置】中，将【密码保护的共享】设为【关闭】。"
                    } else {
                        "访问被拒绝：当前账号没有权限访问，请在 Windows 共享属性 ->【安全】中添加该用户的读取权限。"
                    }
                }
                errorMsg.contains("Logon failure", true) || errorMsg.contains("STATUS_LOGON_FAILURE", true) || errorMsg.contains("bad user name or password", true) -> {
                    "登录失败：用户名或密码错误。请核对 Windows 用户名与密码（若使用微软在线账号登录，用户名为系统本地用户名或绑定的邮箱）。"
                }
                errorMsg.contains("Connection refused", true) || errorMsg.contains("timed out", true) -> {
                    "无法连接到电脑 (${device.host})：请确认电脑已开机、连接在同一个局域网 Wi-Fi，且防火墙放行了 445 端口。"
                }
                else -> "连接共享服务出错: ${lastError?.localizedMessage ?: errorMsg}"
            }
            mainHandler.post { callback(false, emptyList(), formattedMsg) }
            return
        }

        try {
            val isRoot = cleanPath.isEmpty() || cleanPath == "/"

            if (isRoot) {
                val items = mutableListOf<LanFileItem>()
                val discoveredShareNames = mutableSetOf<String>()

                // 1. 如果用户手动指定了共享名，优先挂载
                if (auth.customShare.isNotBlank()) {
                    val customName = auth.customShare.trim('/', '\\')
                    try {
                        val customFile = SmbFile("smb://${device.host}/$customName/", activeContext)
                        if (customFile.exists() && customFile.isDirectory) {
                            items.add(LanFileItem(
                                name = customName,
                                path = "/$customName",
                                isDirectory = true,
                                shareName = customName
                            ))
                            discoveredShareNames.add(customName.lowercase())
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Custom share $customName check failed: ${e.message}")
                    }
                }

                // 2. 调用 NetrShareEnum (DCE/RPC) 真实动态发现 Windows / Samba 主机上所有公开共享文件夹
                try {
                    val serverFile = SmbFile("smb://${device.host}/", activeContext)
                    val shares = serverFile.listFiles()
                    if (shares != null) {
                        for (sh in shares) {
                            val rawName = sh.name.trimEnd('/')
                            // 过滤系统内置的非文件共享 IPC$ 与打印机共享 PRINT$
                            if (rawName.equals("IPC$", ignoreCase = true) || rawName.equals("PRINT$", ignoreCase = true)) {
                                continue
                            }
                            if (discoveredShareNames.add(rawName.lowercase())) {
                                items.add(LanFileItem(
                                    name = rawName,
                                    path = "/$rawName",
                                    isDirectory = true,
                                    shareName = rawName
                                ))
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "NetShareEnum failed on ${device.host}: ${e.message}")
                }

                // 3. 如果由于 Windows 安全策略禁止了 RPC 共享列表枚举，启动保底探测（探测常见共享名与盘符）
                if (items.isEmpty()) {
                    val fallbackCandidates = listOf(
                        "Users", "Public", "Videos", "Movies", "Shared", "Share", "Media",
                        "Downloads", "VR", "VR_Videos", "3D", "Movie", "Video", "共享", "电影", "视频",
                        "D", "E", "F", "D$", "E$", "C$"
                    )
                    for (cand in fallbackCandidates) {
                        try {
                            val candFile = SmbFile("smb://${device.host}/$cand/", activeContext)
                            if (candFile.exists() && candFile.isDirectory) {
                                if (discoveredShareNames.add(cand.lowercase())) {
                                    items.add(LanFileItem(
                                        name = cand,
                                        path = "/$cand",
                                        isDirectory = true,
                                        shareName = cand
                                    ))
                                }
                            }
                        } catch (e: Throwable) {}
                    }
                }

                if (items.isEmpty()) {
                    mainHandler.post {
                        callback(false, emptyList(), "已连接到主机 (${device.host})，但未找到可用的共享文件夹。\n\n排查步骤：\n1. 请在 Windows 电脑上右键想要共享的文件夹 ->【属性】->【共享】标签页 -> 点击【高级共享】并勾选【共享此文件夹】；\n2. 若您设置了特定共享名，可在连接弹窗的【共享文件夹名称】输入框中填入该名称连接。")
                    }
                    return
                }

                items.sortBy { it.name.lowercase() }
                mainHandler.post { callback(true, items, null) }
            } else {
                // 进入子目录或指定共享文件夹浏览实际视频文件
                val targetUrl = "smb://${device.host}${if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"}/"
                val targetFile = SmbFile(targetUrl, activeContext)
                val fileList = targetFile.listFiles()
                val items = mutableListOf<LanFileItem>()

                if (fileList != null) {
                    for (f in fileList) {
                        val name = f.name.trimEnd('/')
                        if (name == "." || name == "..") continue
                        val isDir = f.isDirectory
                        val isVideo = isDir || isVideoFile(name)

                        if (isVideo) {
                            val itemPath = if (cleanPath.endsWith("/")) "$cleanPath$name" else "$cleanPath/$name"
                            val streamUrl = if (!isDir) {
                                SmbStreamServer.getStreamUrl(
                                    device.host, device.port,
                                    auth.username, auth.password,
                                    auth.isAnonymous,
                                    itemPath.trim('/')
                                )
                            } else ""

                            val shareName = cleanPath.trim('/').substringBefore('/')
                            items.add(LanFileItem(
                                name = name,
                                path = itemPath,
                                isDirectory = isDir,
                                sizeBytes = if (isDir) 0L else f.length(),
                                streamUri = streamUrl,
                                shareName = shareName
                            ))
                        }
                    }
                }

                items.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                mainHandler.post { callback(true, items, null) }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            val errMsg = when {
                msg.contains("Access is denied", true) || msg.contains("STATUS_ACCESS_DENIED", true) -> {
                    if (auth.isAnonymous) {
                        "访问被拒绝 (Access is denied)：该设备未开启免密访客访问。\n\n建议操作：\n1. 请在弹窗中切换为【账户密码验证】，输入 Windows 的电脑登录用户名与密码；\n2. 或者在 Windows【控制面板 -> 网络和共享中心 -> 高级共享设置】中将【密码保护的共享】设为【关闭】。"
                    } else {
                        "访问被拒绝：当前账号没有权限读取该共享文件夹，请在 Windows 文件夹属性 ->【安全】标签页中添加读取权限。"
                    }
                }
                msg.contains("Logon failure", true) || msg.contains("STATUS_LOGON_FAILURE", true) || msg.contains("bad user name or password", true) -> {
                    "登录失败：账号或密码错误。请核对 Windows 用户名与密码（注意：若使用微软在线账号登录，用户名为系统本地用户名或绑定的邮箱）。"
                }
                msg.contains("Connection refused", true) || msg.contains("timed out", true) -> {
                    "无法连接到电脑 (${device.host})：请确认电脑处于开机状态、与手机连接在同一个局域网 Wi-Fi，且防火墙放行了 445 端口。"
                }
                else -> "连接共享服务出错: ${e.localizedMessage ?: msg}"
            }
            mainHandler.post { callback(false, emptyList(), errMsg) }
        }
    }
}
