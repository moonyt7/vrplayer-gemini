package com.example.vrplayer

import android.net.Uri
import androidx.media3.common.MimeTypes

data class ParsedNetworkStream(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val playlistBody: String? = null
)

object NetworkStreamParser {

    private val URL_REGEX = Regex(
        """https?://[^\s"'<>\\]+""",
        RegexOption.IGNORE_CASE
    )
    private val HEADER_LINE_REGEX = Regex(
        """^\s*(Referer|Origin|Cookie|User-Agent|Authorization|Range|Host)\s*[:=]\s*(.+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val CURL_HEADER_REGEX = Regex(
        """(?:-H|--header)\s+['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val FFMPEG_I_REGEX = Regex(
        """-i\s+['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val VIDEO_HINTS = listOf(
        ".m3u8", ".mpd", ".mp4", ".m4s", ".m4v", ".flv", ".ts", ".webm",
        ".mov", ".mkv", ".m3u", "hls", "playlist"
    )

    fun parse(raw: String): ParsedNetworkStream? {
        val text = raw.trim().replace("\r\n", "\n").replace('\r', '\n')
        if (text.isEmpty()) return null

        parseJsonPayload(text)?.let { return it }

        if (text.startsWith("#EXTM3U", ignoreCase = true)) {
            val mediaUrl = extractBestUrl(text)
            return ParsedNetworkStream(
                url = mediaUrl ?: "playlist.m3u8",
                headers = extractHeaders(text),
                mimeType = MimeTypes.APPLICATION_M3U8,
                playlistBody = text
            )
        }

        val headers = extractHeaders(text).toMutableMap()
        val url = extractUrl(text) ?: return null
        inferBrowserHeaders(url, headers)
        return ParsedNetworkStream(
            url = url,
            headers = headers,
            mimeType = inferMimeType(url, text)
        )
    }

    fun inferMimeType(url: String, extraText: String = ""): String? {
        val haystack = "$url $extraText".lowercase()
        return when {
            haystack.contains(".m3u8") || haystack.contains("application/vnd.apple.mpegurl") ||
                haystack.contains("application/x-mpegurl") || haystack.contains("/hls") -> MimeTypes.APPLICATION_M3U8
            haystack.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            haystack.contains(".mp4") || haystack.contains(".m4v") -> MimeTypes.VIDEO_MP4
            haystack.contains(".webm") -> MimeTypes.VIDEO_WEBM
            haystack.contains(".mkv") -> MimeTypes.VIDEO_MATROSKA
            haystack.contains("rtsp://") -> MimeTypes.APPLICATION_RTSP
            else -> null
        }
    }

    fun looksLikeHls(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("playlist") && (lower.contains("index") || lower.contains("stream"))
    }

    private fun parseJsonPayload(text: String): ParsedNetworkStream? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("http", ignoreCase = true)) {
            return null
        }
        val url = Regex(
            """"(?:url|uri|src|link|requestUrl)"\s*:\s*"(https?://[^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }
            ?: extractBestUrl(trimmed)
            ?: return null

        val headers = linkedMapOf<String, String>()
        Regex(
            """"(Referer|Origin|Cookie|User-Agent|Authorization)"\s*:\s*"([^"]*)"""",
            RegexOption.IGNORE_CASE
        ).findAll(trimmed).forEach { match ->
            putHeader(headers, match.groupValues[1], match.groupValues[2].replace("\\/", "/"))
        }
        inferBrowserHeaders(url, headers)
        return ParsedNetworkStream(
            url = url,
            headers = headers,
            mimeType = inferMimeType(url, trimmed)
        )
    }

    private fun extractUrl(text: String): String? {
        FFMPEG_I_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { candidate ->
            if (candidate.startsWith("http", ignoreCase = true)) {
                return sanitizeUrl(candidate)
            }
        }

        val quoted = Regex("""['"](https?://[^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        pickBestUrl(quoted)?.let { return it }

        return extractBestUrl(text)
    }

    private fun extractBestUrl(text: String): String? {
        val urls = URL_REGEX.findAll(text)
            .map { sanitizeUrl(it.value) }
            .filter { it.isNotEmpty() }
            .toList()
        return pickBestUrl(urls)
    }

    private fun pickBestUrl(urls: List<String>): String? {
        if (urls.isEmpty()) return null
        return urls.firstOrNull { url ->
            val lower = url.lowercase()
            VIDEO_HINTS.any { lower.contains(it) }
        } ?: urls.firstOrNull { url ->
            val host = Uri.parse(url).host.orEmpty().lowercase()
            host.isNotEmpty() && !host.contains("google") && !host.contains("gstatic")
        } ?: urls.first()
    }

    private fun sanitizeUrl(raw: String): String {
        return raw.trim()
            .trimEnd('\\', '"', '\'', '>', ')', ']', ',', ';')
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
    }

    private fun extractHeaders(text: String): MutableMap<String, String> {
        val headers = linkedMapOf<String, String>()

        CURL_HEADER_REGEX.findAll(text).forEach { match ->
            val line = match.groupValues[1]
            val idx = line.indexOf(':')
            if (idx > 0) {
                putHeader(headers, line.substring(0, idx), line.substring(idx + 1))
            }
        }

        Regex(
            """-headers\s+['"](.+?)['"]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(text)?.groupValues?.getOrNull(1)?.let { block ->
            block.split("\\r\\n", "\r\n", "\n", "\\n").forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    putHeader(headers, line.substring(0, idx), line.substring(idx + 1))
                }
            }
        }

        text.lineSequence().forEach { line ->
            HEADER_LINE_REGEX.find(line)?.let { match ->
                putHeader(headers, match.groupValues[1], match.groupValues[2])
            }
        }

        Regex(
            """-user_agent\s+['"](.+?)['"]""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.getOrNull(1)?.let {
            headers.putIfAbsent("User-Agent", it.trim())
        }

        return headers
    }

    private fun putHeader(headers: MutableMap<String, String>, rawName: String, rawValue: String) {
        val name = rawName.trim()
        val value = rawValue.trim().trimEnd(',', ';')
        if (name.isEmpty() || value.isEmpty()) return
        val canonical = when (name.lowercase()) {
            "referer", "referrer" -> "Referer"
            "user-agent", "user_agent", "useragent" -> "User-Agent"
            "origin" -> "Origin"
            "cookie" -> "Cookie"
            "authorization" -> "Authorization"
            else -> name
        }
        headers[canonical] = value
    }

    private fun inferBrowserHeaders(url: String, headers: MutableMap<String, String>) {
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = DEFAULT_UA
        }
        headers.putIfAbsent("Accept", "*/*")
        headers.putIfAbsent("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

        val referer = headers["Referer"]
        if (!referer.isNullOrBlank() && !headers.containsKey("Origin")) {
            try {
                val refUri = Uri.parse(referer)
                val scheme = refUri.scheme
                val host = refUri.host
                if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                    headers["Origin"] = "$scheme://$host"
                }
            } catch (_: Throwable) {
            }
        }

        // 没有页面 Referer 时不要用 CDN 自身域名当 Referer，否则猫抓直链容易 403
        if (referer.isNullOrBlank()) {
            val pageReferer = guessPageReferer(url)
            if (pageReferer != null) {
                headers["Referer"] = pageReferer
                if (!headers.containsKey("Origin")) {
                    val pageUri = Uri.parse(pageReferer)
                    val scheme = pageUri.scheme
                    val host = pageUri.host
                    if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                        headers["Origin"] = "$scheme://$host"
                    }
                }
            }
        }
    }

    private fun guessPageReferer(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return null
            when {
                host.contains("bilivideo") || host.contains("bilibili") -> "https://www.bilibili.com/"
                host.contains("iqiyi") || host.contains("qiyi") -> "https://www.iqiyi.com/"
                host.contains("youku") -> "https://v.youku.com/"
                host.contains("qq.com") || host.contains("gtimg") || host.contains("v.smtcdns") -> "https://v.qq.com/"
                host.contains("mgtv") -> "https://www.mgtv.com/"
                host.contains("huya") -> "https://www.huya.com/"
                host.contains("douyu") -> "https://www.douyu.com/"
                host.contains("ixigua") || host.contains("toutiao") -> "https://www.ixigua.com/"
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
