package com.example.engine

import com.example.data.model.RawPluginStream
import com.example.data.model.StremioBehaviorHints
import com.example.data.model.StremioStreamItem
import com.example.data.model.StreamQuality
import java.util.Locale

object StreamFormatter {

    fun formatAndSortStreams(
        rawStreams: List<RawPluginStream>,
        sortByQuality: Boolean = true,
        groupByQuality: Boolean = true,
        filterOutLowQuality: Boolean = false
    ): List<StremioStreamItem> {
        val parsedList = rawStreams.mapNotNull { raw ->
            val url = raw.url?.trim()
            if (url.isNullOrEmpty()) return@mapNotNull null

            val quality = detectQuality(raw.quality, raw.title, raw.name, url)
            val cleanTitle = buildCleanTitle(raw, quality)
            val cleanName = buildCleanName(raw, quality)

            val behaviorHints = if (!raw.headers.isNullOrEmpty()) {
                StremioBehaviorHints(
                    bingeGroup = "nuvio-${quality.label.lowercase().replace(" ", "-")}",
                    notWebReady = false,
                    proxyHeaders = mapOf("request" to raw.headers)
                )
            } else {
                StremioBehaviorHints(
                    bingeGroup = "nuvio-${quality.label.lowercase().replace(" ", "-")}",
                    notWebReady = false
                )
            }

            ParsedStream(
                item = StremioStreamItem(
                    name = cleanName,
                    title = cleanTitle,
                    url = url,
                    behaviorHints = behaviorHints
                ),
                quality = quality,
                provider = raw.provider ?: "Nuvio",
                order = quality.rank
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
        val provider = raw.provider ?: raw.name?.replace("[Nuvio]", "")?.trim() ?: "Nuvio"
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
