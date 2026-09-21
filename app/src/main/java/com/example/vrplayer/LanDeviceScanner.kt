package com.example.vrplayer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.FileReader
import java.net.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 专业局域网真实电脑与共享设备扫描器
 * 1. 扫描当前 Wi-Fi 网段活跃 IP 与 /proc/net/arp 邻居表
 * 2. 并发探测 TCP 445 (Windows/Samba SMB 共享)、139、5005 (WebDAV)、80/8080 (HTTP/Alist)
 * 3. 发送 RFC 1002 NetBIOS 节点状态查询 (UDP 137)，精准获取真实 Windows 电脑主机名 (如 DESKTOP-XXXXX)
 * 4. 辅助 mDNS (NsdManager) 发现 Mac、NAS、流媒体服务
 */
class LanDeviceScanner(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discoveredIps = ConcurrentHashMap.newKeySet<String>()

    data class DiscoveredDevice(
        val name: String,
        val host: String,
        val port: Int,
        val protocol: String
    )

    fun scanNetwork(onDeviceFound: (DiscoveredDevice) -> Unit) {
        val executor = Executors.newFixedThreadPool(32)
        val localIp = getLocalIpAddress()

        // 1. 优先扫描 /proc/net/arp 邻居表（缓存了局域网内所有与本机通信过的设备）
        val arpIps = getArpIps()
        for (ip in arpIps) {
            if (ip != localIp && discoveredIps.add(ip)) {
                executor.execute {
                    checkAndProbeHost(ip, onDeviceFound)
                }
            }
        }

        // 2. 扫描当前网段 (1 ~ 254)
        if (localIp != null && localIp.contains(".")) {
            val prefix = localIp.substringBeforeLast('.') + "."
            for (i in 1..254) {
                val targetIp = "$prefix$i"
                if (targetIp != localIp && discoveredIps.add(targetIp)) {
                    executor.execute {
                        checkAndProbeHost(targetIp, onDeviceFound)
                    }
                }
            }
        }

        // 3. 辅助启动 mDNS 扫描 (针对 Mac / Linux Avahi / NAS)
        startMdnsScan(onDeviceFound)

        executor.shutdown()
    }

    private fun checkAndProbeHost(ip: String, onDeviceFound: (DiscoveredDevice) -> Unit) {
        // 探测顺序：优先 445 (标准 SMB 共享)，其次 139 (老式 SMB)，再是 5005 (WebDAV)，80/8080 (HTTP/Alist)
        val ports = listOf(445, 139, 5005, 8080, 5244, 80)
        for (port in ports) {
            if (isPortOpen(ip, port, 600)) {
                val protocol = when (port) {
                    445, 139 -> "SMB"
                    5005 -> "WebDAV"
                    else -> "HTTP"
                }

                // 尝试通过 NetBIOS (UDP 137) 获取真实电脑主机名
                val netbiosName = if (port == 445 || port == 139) queryNetbiosName(ip) else null
                val resolvedName = when {
                    !netbiosName.isNullOrEmpty() -> "$netbiosName (Windows/PC 共享)"
                    port == 445 || port == 139 -> "局域网电脑共享 ($ip)"
                    port == 5005 -> "WebDAV 媒体服务 ($ip)"
                    else -> "局域网媒体服务 ($ip)"
                }

                val device = DiscoveredDevice(resolvedName, ip, port, protocol)
                mainHandler.post { onDeviceFound(device) }
                break
            }
        }
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 发送 RFC 1002 NetBIOS Node Status Request (UDP 137)，获取 Windows / Samba 电脑的实际计算机名
     */
    private fun queryNetbiosName(ip: String): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 600

            // RFC 1002 标准查询报文：请求通配符 "*" 的节点状态
            val request = byteArrayOf(
                0x82.toByte(), 0x28.toByte(), // Transaction ID
                0x00, 0x00, // Flags: Query
                0x00, 0x01, // Questions: 1
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x20, // 编码名长度 (32 字节)
                // "CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" 表示 "*"
                0x43, 0x4B, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x00, // 结束符
                0x00, 0x21, // Type: NBSTAT
                0x00, 0x01  // Class: IN
            )

            val address = InetAddress.getByName(ip)
            val packet = DatagramPacket(request, request.size, address, 137)
            socket.send(packet)

            val buffer = ByteArray(1024)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            val data = response.data
            // NetBIOS 响应体包含节点名称表，偏移 56 为名称数量
            if (data.size > 57) {
                val numNames = data[56].toInt() and 0xFF
                var offset = 57
                for (i in 0 until numNames) {
                    if (offset + 18 <= data.size) {
                        val nameBytes = ByteArray(15)
                        System.arraycopy(data, offset, nameBytes, 0, 15)
                        val name = String(nameBytes, Charsets.US_ASCII).trim()
                        val type = data[offset + 15].toInt() and 0xFF
                        val flags = ((data[offset + 16].toInt() and 0xFF) shl 8) or (data[offset + 17].toInt() and 0xFF)
                        val isGroup = (flags and 0x8000) != 0

                        // type == 0x00 且不是群组名，表示当前主机的计算机名称 (Unique Workstation / Server)
                        if (!isGroup && type == 0x00 && name.isNotEmpty() && !name.startsWith("IS~")) {
                            return name
                        }
                        offset += 18
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略超时或无响应
        } finally {
            socket?.close()
        }
        return null
    }

    private fun getArpIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                var isHeader = true
                while (reader.readLine().also { line = it } != null) {
                    if (isHeader) {
                        isHeader = false
                        continue
                    }
                    val parts = line!!.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val flags = parts[2]
                        // flags 0x2 代表可达成功的 ARP 条目
                        if (flags == "0x2" || flags == "2") {
                            ips.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略 ARP 读取失败
        }
        return ips
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val sAddr = addr.hostAddress ?: continue
                        if (sAddr.startsWith("192.168.") || sAddr.startsWith("10.") || sAddr.startsWith("172.")) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun startMdnsScan(onDeviceFound: (DiscoveredDevice) -> Unit) {
        val serviceTypes = listOf("_smb._tcp.", "_webdav._tcp.", "_http._tcp.")
        for (st in serviceTypes) {
            try {
                nsdManager?.discoverServices(st, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val host = resolved.host?.hostAddress ?: return
                                val name = resolved.serviceName ?: "网络设备"
                                val port = resolved.port
                                val proto = if (resolved.serviceType.contains("smb")) "SMB" else "WebDAV"
                                mainHandler.post {
                                    onDeviceFound(DiscoveredDevice(name, host, port, proto))
                                }
                            }
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        })
                    }
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                })
            } catch (e: Exception) {}
        }
    }
}
