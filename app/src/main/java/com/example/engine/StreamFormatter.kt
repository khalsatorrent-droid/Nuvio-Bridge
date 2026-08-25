package com.example.engine

import android.net.Uri
import com.example.data.model.RawPluginStream
import com.example.data.model.StremioBehaviorHints
import com.example.data.model.StremioStreamItem
import com.example.data.model.StreamQuality
import java.util.Locale

object StreamFormatter {

    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Known session-token query param names whose values vary per-request but identify the same stream.
     */
    private val SESSION_TOKEN_PARAMS = setOf(
        "token", "key", "sig", "signature", "auth", "hash", "nonce", "session",
        "access_token", "authtoken",
        "t", "ts", "timestamp", "expires", "expiry", "exp", "e",
        "hdnea", "hdntl",
        "x-amz-security-token", "x-amz-credential", "x-amz-date",
        "x-amz-expires", "x-amz-signature", "x-amz-signed-headers", "x-amz-algorithm",
        "_", "ott", "wmsauthsign"
    )

    /**
     * Computes canonical deduplication key for streams matching nuvio-bridge specification.
     */
    fun computeDeduplicationKey(rawUrl: String): String {
        try {
            val uri = Uri.parse(rawUrl)
            val path = uri.path ?: ""
            val isHls = path.endsWith(".m3u8", ignoreCase = true) || path.endsWith(".ts", ignoreCase = true)

            val queryParamNames = uri.queryParameterNames
            val hasSessionParam = queryParamNames.any { it.lowercase(Locale.ROOT) in SESSION_TOKEN_PARAMS }

            if (isHls || hasSessionParam) {
                val host = uri.host ?: ""
                val cleanParams = mutableListOf<String>()
                for (name in queryParamNames) {
                    if (name.lowercase(Locale.ROOT) !in SESSION_TOKEN_PARAMS) {
                        val v = uri.getQueryParameter(name)
                        if (v != null) cleanParams.add("$name=$v") else cleanParams.add(name)
                    }
                }
                val queryPart = if (cleanParams.isNotEmpty()) "?${cleanParams.joinToString("&")}" else ""
                if (isHls) {
                    return "https://$host$path$queryPart"
                }
                val scheme = uri.scheme ?: "https"
                return "$scheme://$host$path$queryPart"
            }
            return rawUrl
        } catch (_: Exception) {
            return rawUrl
        }
    }

    fun formatAndSortStreams(
        rawStreams: List<RawPluginStream>,
        sortByQuality: Boolean = true,
        groupByQuality: Boolean = true,
        filterOutLowQuality: Boolean = false
    ): List<StremioStreamItem> {
        val seenDedupKeys = HashSet<String>()
        val parsedList = mutableListOf<ParsedStream>()

        for (raw in rawStreams) {
            val url = raw.url?.trim()
            val infoHash = raw.infoHash?.trim()?.lowercase(Locale.ROOT)
            val fileIdx = raw.fileIdx

            if (url.isNullOrEmpty() && infoHash.isNullOrEmpty()) continue

            // Deduplicate by infoHash or canonical stream URL key
            val dedupKey = if (!infoHash.isNullOrEmpty()) {
                "hash:$infoHash"
            } else if (!url.isNullOrEmpty()) {
                "url:${computeDeduplicationKey(url)}"
            } else continue

            if (seenDedupKeys.contains(dedupKey)) continue
            seenDedupKeys.add(dedupKey)

            val quality = detectQuality(raw.quality, raw.title, raw.name, url ?: "")
            val cleanTitle = buildCleanTitle(raw, quality)
            val cleanName = buildCleanName(raw, quality)

            val behaviorHints = if (!url.isNullOrEmpty()) {
                val compliantHeaders = buildCompliantHeaders(raw.headers, url)
                StremioBehaviorHints(
                    bingeGroup = "nuvio-${quality.label.lowercase().replace(" ", "-")}",
                    notWebReady = false,
                    proxyHeaders = mapOf("request" to compliantHeaders),
                    headers = compliantHeaders
                )
            } else {
                StremioBehaviorHints(
                    bingeGroup = "nuvio-${quality.label.lowercase().replace(" ", "-")}",
                    notWebReady = false
                )
            }

            val parsedSubtitles = raw.subtitles?.mapNotNull { sub ->
                val subUrl = sub.url?.trim()
                if (!subUrl.isNullOrEmpty()) {
                    com.example.data.model.StremioSubtitle(
                        id = sub.id ?: sub.language ?: sub.lang ?: "eng",
                        url = subUrl,
                        lang = sub.lang ?: sub.language ?: "eng"
                    )
                } else null
            }

            parsedList.add(
                ParsedStream(
                    item = StremioStreamItem(
                        name = cleanName,
                        title = cleanTitle,
                        url = if (!url.isNullOrEmpty()) url else null,
                        infoHash = if (!infoHash.isNullOrEmpty()) infoHash else null,
                        fileIdx = fileIdx,
                        subtitles = if (!parsedSubtitles.isNullOrEmpty()) parsedSubtitles else null,
                        behaviorHints = behaviorHints
                    ),
                    quality = quality,
                    provider = raw.provider ?: "Nuvio",
                    order = quality.rank
                )
            )
        }

        val filtered = if (filterOutLowQuality) {
            parsedList.filter { it.quality.rank >= StreamQuality.HD_720P.rank }
        } else {
            parsedList
        }

        val sorted = if (sortByQuality) {
            filtered.sortedWith(
                compareByDescending<ParsedStream> { it.quality.rank }
                    .thenBy { it.provider }
            )
        } else {
            filtered
        }

        return sorted.map { it.item }
    }

    /**
     * Builds compliant headers as per Stremio Addon Protocol and player specifications.
     */
    fun buildCompliantHeaders(
        pluginHeaders: Map<String, String>?,
        streamUrl: String
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        // 1. Copy plugin-supplied headers with case-normalization
        if (pluginHeaders != null) {
            for ((key, value) in pluginHeaders) {
                if (key.isNotBlank() && value.isNotBlank()) {
                    val normalizedKey = normalizeHeaderKey(key)
                    headers[normalizedKey] = value
                }
            }
        }

        // 2. Ensure User-Agent is present
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = DEFAULT_USER_AGENT
        }

        // 3. Ensure Referer and Origin are present if not specified
        try {
            val uri = Uri.parse(streamUrl)
            val host = uri.host
            val scheme = uri.scheme ?: "https"
            if (!host.isNullOrBlank()) {
                val origin = "$scheme://$host"
                if (!headers.containsKey("Referer")) {
                    headers["Referer"] = "$origin/"
                }
                if (!headers.containsKey("Origin")) {
                    headers["Origin"] = origin
                }
            }
        } catch (_: Exception) {}

        // 4. Ensure streaming optimization headers
        if (!headers.containsKey("Accept")) {
            headers["Accept"] = "*/*"
        }
        if (!headers.containsKey("Accept-Language")) {
            headers["Accept-Language"] = "en-US,en;q=0.9"
        }
        if (!headers.containsKey("Sec-Fetch-Dest")) {
            headers["Sec-Fetch-Dest"] = "video"
        }
        if (!headers.containsKey("Sec-Fetch-Mode")) {
            headers["Sec-Fetch-Mode"] = "cors"
        }
        if (!headers.containsKey("Sec-Fetch-Site")) {
            headers["Sec-Fetch-Site"] = "cross-site"
        }

        return headers
    }

    private fun normalizeHeaderKey(key: String): String {
        return when (key.lowercase(Locale.ROOT)) {
            "user-agent", "useragent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            "origin" -> "Origin"
            "accept" -> "Accept"
            "accept-language" -> "Accept-Language"
            "accept-encoding" -> "Accept-Encoding"
            "range" -> "Range"
            "cookie" -> "Cookie"
            "authorization" -> "Authorization"
            "connection" -> "Connection"
            "sec-fetch-dest" -> "Sec-Fetch-Dest"
            "sec-fetch-mode" -> "Sec-Fetch-Mode"
            "sec-fetch-site" -> "Sec-Fetch-Site"
            else -> key
        }
    }

    private data class ParsedStream(
        val item: StremioStreamItem,
        val quality: StreamQuality,
        val provider: String,
        val order: Int
    )

    fun detectQuality(vararg sources: String?): StreamQuality {
        val combined = sources.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)

        return when {
            combined.contains("4k") || combined.contains("2160p") || combined.contains("uhd") -> StreamQuality.UHD_4K
            combined.contains("1080p") || combined.contains("fhd") || combined.contains("full hd") -> StreamQuality.FHD_1080P
            combined.contains("720p") || combined.contains("hd") -> StreamQuality.HD_720P
            combined.contains("480p") || combined.contains("sd") -> StreamQuality.SD_480P
            combined.contains("360p") -> StreamQuality.SD_360P
            else -> StreamQuality.FHD_1080P // Default high quality
        }
    }

    private fun buildCleanName(raw: RawPluginStream, quality: StreamQuality): String {
        val rawName = raw.name?.trim() ?: ""
        if (rawName.contains("|") || rawName.contains("•") || rawName.startsWith("[")) {
            return rawName
        }
        val provider = raw.provider ?: rawName.ifEmpty { "Nuvio" }
        val qualityBadge = when (quality) {
            StreamQuality.UHD_4K -> "4K UHD"
            StreamQuality.FHD_1080P -> "1080p"
            StreamQuality.HD_720P -> "720p"
            StreamQuality.SD_480P -> "480p"
            StreamQuality.SD_360P -> "360p"
            StreamQuality.UNKNOWN -> "Auto"
        }
        return "[Nuvio] $provider\n$qualityBadge"
    }

    private fun buildCleanTitle(raw: RawPluginStream, quality: StreamQuality): String {
        val originalTitle = raw.title ?: "Stream Source"
        val extraTags = mutableListOf<String>()

        val lower = originalTitle.lowercase(Locale.ROOT)
        if (lower.contains("hdr") || lower.contains("hdr10")) extraTags.add("HDR")
        if (lower.contains("dolby vision") || lower.contains("dovi")) extraTags.add("DV")
        if (lower.contains("atmos") || lower.contains("5.1") || lower.contains("7.1")) extraTags.add("Surround")
        if (lower.contains("multi") || lower.contains("dual audio")) extraTags.add("Multi-Audio")
        if (lower.contains("sub") || lower.contains("subtitle")) extraTags.add("Multi-Subs")
        if (lower.contains("60fps")) extraTags.add("60 FPS")

        val tagLine = if (extraTags.isNotEmpty()) {
            "⚡ " + extraTags.joinToString(" • ")
        } else {
            "⚡ Fast CDN • Direct Stream"
        }

        return if (originalTitle.contains("\n")) {
            originalTitle
        } else {
            "$originalTitle\n${quality.label} • $tagLine"
        }
    }
}
