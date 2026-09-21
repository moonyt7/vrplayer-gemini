package com.example.vrplayer

import android.util.Log
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * 本地 SMB 实时媒体流网关 (Local SMB HTTP Stream Proxy)
 * 将 Windows / Samba 局域网共享视频直接转译为标准 HTTP 206 Partial Content 范围字节流，
 * 使得 ExoPlayer / 硬件解码器可像播放标准网络视频一样秒开与拖动快进快退，零临时文件缓存。
 */
object SmbStreamServer {

    private const val TAG = "SmbStreamServer"
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var listeningPort = 0

    fun start(): Int {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            return listeningPort
        }
        try {
            serverSocket = ServerSocket(0) // 动态绑定本地可用端口
            listeningPort = serverSocket!!.localPort
            executor.execute {
                while (serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        executor.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            Log.i(TAG, "SmbStreamServer running on port $listeningPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SmbStreamServer: ${e.message}")
        }
        return listeningPort
    }

    fun getStreamUrl(host: String, port: Int, user: String, pass: String, isAnon: Boolean, fullSmbPath: String): String {
        val serverPort = start()
        val encPath = java.net.URLEncoder.encode(fullSmbPath, "UTF-8")
        val encUser = java.net.URLEncoder.encode(user, "UTF-8")
        val encPass = java.net.URLEncoder.encode(pass, "UTF-8")
        val anonFlag = if (isAnon) "1" else "0"
        return "http://127.0.0.1:$serverPort/stream?host=$host&port=$port&user=$encUser&pass=$encPass&anon=$anonFlag&path=$encPath"
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = input.readLine() ?: return socket.close()
            val parts = requestLine.split(" ")
            if (parts.size < 2) return socket.close()

            val uriStr = parts[1]
            if (!uriStr.startsWith("/stream?")) {
                sendHttpError(output, 404, "Not Found")
                return socket.close()
            }

            // 解析 Range 请求头
            var rangeHeader: String? = null
            var line: String?
            while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line!!.substring(6).trim()
                }
            }

            // 解析参数
            val params = parseQuery(uriStr.substringAfter("/stream?"))
            val host = params["host"] ?: ""
            val user = params["user"] ?: ""
            val pass = params["pass"] ?: ""
            val isAnon = params["anon"] == "1"
            val rawPath = params["path"] ?: ""
            val cleanPath = rawPath.trim('/')

            val cifsContext = LanShareManager.buildCifsContext(isAnon, user, pass)
            val smbUrl = "smb://$host/$cleanPath"
            val smbFile = SmbFile(smbUrl, cifsContext)

            val fileLength = smbFile.length()
            if (fileLength <= 0L) {
                sendHttpError(output, 404, "File Not Found or Empty")
                return socket.close()
            }

            var startPos = 0L
            var endPos = fileLength - 1

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeVal = rangeHeader.substring(6).trim()
                val dashIndex = rangeVal.indexOf('-')
                if (dashIndex >= 0) {
                    val startStr = rangeVal.substring(0, dashIndex).trim()
                    val endStr = rangeVal.substring(dashIndex + 1).trim()
                    if (startStr.isNotEmpty()) startPos = startStr.toLong()
                    if (endStr.isNotEmpty()) endPos = endStr.toLong()
                }
            }

            val contentLength = (endPos - startPos) + 1

            // 发送 HTTP 206 Partial Content 或 200 OK 响应
            val header = StringBuilder()
            if (rangeHeader != null) {
                header.append("HTTP/1.1 206 Partial Content\r\n")
                header.append("Content-Range: bytes $startPos-$endPos/$fileLength\r\n")
            } else {
                header.append("HTTP/1.1 200 OK\r\n")
            }
            header.append("Content-Type: video/mp4\r\n")
            header.append("Accept-Ranges: bytes\r\n")
            header.append("Content-Length: $contentLength\r\n")
            header.append("Connection: close\r\n\r\n")

            output.write(header.toString().toByteArray(Charsets.US_ASCII))
            output.flush()

            val raf = SmbRandomAccessFile(smbFile, "r")
            raf.seek(startPos)

            val buffer = ByteArray(64 * 1024)
            var bytesRemaining = contentLength

            while (bytesRemaining > 0 && !socket.isClosed) {
                val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                output.write(buffer, 0, read)
                bytesRemaining -= read
            }
            output.flush()
            raf.close()
        } catch (e: Exception) {
            // 播放器快速切换进度时 Socket 被关闭属于正常现象
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun sendHttpError(output: OutputStream, code: Int, msg: String) {
        val resp = "HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(resp.toByteArray(Charsets.US_ASCII))
        output.flush()
    }
}
