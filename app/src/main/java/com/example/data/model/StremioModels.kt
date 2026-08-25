package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class StremioManifest(
    @Json(name = "id") val id: String = "org.stremio.nuvio.local",
    @Json(name = "version") val version: String = "1.2.0",
    @Json(name = "name") val name: String = "Nuvio Local Addon",
    @Json(name = "description") val description: String = "Local Stremio Addon server powered by Nuvio plugins with quality sorting",
    @Json(name = "logo") val logo: String = "https://images.unsplash.com/photo-1574375927938-d5a98e8ffe85?w=256&h=256&fit=crop",
    @Json(name = "background") val background: String = "https://images.unsplash.com/photo-1518770660439-4636190af475?w=1280&h=720&fit=crop",
    @Json(name = "resources") val resources: List<String> = listOf("stream"),
    @Json(name = "types") val types: List<String> = listOf("movie", "series", "anime"),
    @Json(name = "idPrefixes") val idPrefixes: List<String> = listOf("tt", "kitsu", "tmdb"),
    @Json(name = "catalogs") val catalogs: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StremioStreamResponse(
    @Json(name = "streams") val streams: List<StremioStreamItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StremioStreamItem(
    @Json(name = "name") val name: String,
    @Json(name = "title") val title: String,
    @Json(name = "url") val url: String? = null,
    @Json(name = "infoHash") val infoHash: String? = null,
    @Json(name = "fileIdx") val fileIdx: Int? = null,
    @Json(name = "behaviorHints") val behaviorHints: StremioBehaviorHints? = null
)

@JsonClass(generateAdapter = true)
data class StremioBehaviorHints(
    @Json(name = "bingeGroup") val bingeGroup: String? = null,
    @Json(name = "notWebReady") val notWebReady: Boolean? = null,
    @Json(name = "proxyHeaders") val proxyHeaders: Map<String, Map<String, String>>? = null
)

/**
 * Raw stream item emitted by Nuvio scraper plugins
 */
@JsonClass(generateAdapter = true)
data class RawPluginStream(
    @Json(name = "name") val name: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "url") val url: String? = null,
    @Json(name = "quality") val quality: String? = null,
    @Json(name = "resolution") val resolution: String? = null,
    @Json(name = "provider") val provider: String? = null,
    @Json(name = "size") val size: String? = null,
    @Json(name = "format") val format: String? = null,
    @Json(name = "headers") val headers: Map<String, String>? = null,
    @Json(name = "isDirect") val isDirect: Boolean? = true
)

enum class StreamQuality(val rank: Int, val label: String) {
    UHD_4K(5, "4K UHD"),
    FHD_1080P(4, "1080p FHD"),
    HD_720P(3, "720p HD"),
    SD_480P(2, "480p SD"),
    SD_360P(1, "360p"),
    UNKNOWN(0, "Auto")
}
