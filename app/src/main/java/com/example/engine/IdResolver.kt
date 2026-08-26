package com.example.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolved IDs bundle for cross-service scrapers
 */
data class ResolvedMediaIds(
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val title: String? = null,
    val year: String? = null,
    val type: String = "movie"
)

object IdResolver {
    private const val TAG = "IdResolver"
    private const val DEFAULT_TMDB_KEY = "84698579998638b251ad02e97519ff08"

    private val client = OkHttpClient.Builder()
        .dns(RobustDns)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val cache = ConcurrentHashMap<String, ResolvedMediaIds>()
    private val kitsuCache = ConcurrentHashMap<String, String>()

    /**
     * Resolves a Kitsu anime ID (e.g. "kitsu:12345" or "12345") to an IMDb tt-id using a 3-step fallback chain:
     * 1. ARM API (https://armapi.vercel.app)
     * 2. ani.zip (https://api.ani.zip/mappings)
     * 3. Kitsu API mappings endpoint (https://kitsu.app/api/edge/anime/{id}/mappings)
     */
    suspend fun resolveKitsuId(kitsuId: String): String? = withContext(Dispatchers.IO) {
        val numericId = if (kitsuId.startsWith("kitsu:")) kitsuId.removePrefix("kitsu:") else kitsuId
        kitsuCache[numericId]?.let { return@withContext it }

        val encoded = URLEncoder.encode(numericId, "UTF-8")

        // 1. ARM API
        try {
            val armUrl = "https://armapi.vercel.app/api?kitsu=$encoded"
            val req = Request.Builder().url(armUrl).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrEmpty()) {
                val trimmed = body.trim()
                val imdb = if (trimmed.startsWith("[")) {
                    val arr = JSONArray(trimmed)
                    if (arr.length() > 0) arr.getJSONObject(0).optString("imdb", arr.getJSONObject(0).optString("imdb_id", "")) else ""
                } else {
                    val obj = JSONObject(trimmed)
                    obj.optString("imdb", obj.optString("imdb_id", ""))
                }
                if (imdb.startsWith("tt")) {
                    Log.d(TAG, "Kitsu $numericId -> IMDb $imdb (ARM API)")
                    kitsuCache[numericId] = imdb
                    return@withContext imdb
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ARM lookup for kitsu:$numericId failed: ${e.message}")
        }

        // 2. ani.zip
        try {
            val anizipUrl = "https://api.ani.zip/mappings?kitsu_id=$encoded"
            val req = Request.Builder().url(anizipUrl).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrEmpty()) {
                val obj = JSONObject(body)
                val mappings = obj.optJSONObject("mappings")
                val imdb = mappings?.optString("imdb_id", "") ?: obj.optString("imdb_id", "")
                if (imdb.startsWith("tt")) {
                    Log.d(TAG, "Kitsu $numericId -> IMDb $imdb (ani.zip)")
                    kitsuCache[numericId] = imdb
                    return@withContext imdb
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ani.zip lookup for kitsu:$numericId failed: ${e.message}")
        }

        // 3. Kitsu API mappings endpoint
        try {
            val kitsuUrl = "https://kitsu.app/api/edge/anime/$encoded/mappings"
            val req = Request.Builder()
                .url(kitsuUrl)
                .header("Accept", "application/vnd.api+json")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrEmpty()) {
                val obj = JSONObject(body)
                val data = obj.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val attrs = item.optJSONObject("attributes")
                    val site = attrs?.optString("externalSite", "")
                    val extId = attrs?.optString("externalId", "")
                    if (site == "imdb" && extId != null && extId.startsWith("tt")) {
                        Log.d(TAG, "Kitsu $numericId -> IMDb $extId (Kitsu API)")
                        kitsuCache[numericId] = extId
                        return@withContext extId
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kitsu API lookup for kitsu:$numericId failed: ${e.message}")
        }

        Log.w(TAG, "Could not resolve kitsu:$numericId to IMDb id")
        null
    }

    /**
     * Resolves IMDB ID <-> TMDB ID and canonical metadata so scrapers get both formats.
     */
    suspend fun resolve(
        rawId: String,
        type: String = "movie",
        existingImdbId: String? = null,
        existingTmdbId: String? = null,
        apiKey: String? = null
    ): ResolvedMediaIds = withContext(Dispatchers.IO) {
        val cleanId = rawId.trim()
        val cacheKey = "$type:$cleanId"
        cache[cacheKey]?.let { return@withContext it }

        var imdb = existingImdbId ?: if (cleanId.startsWith("tt")) cleanId else null
        var tmdb = existingTmdbId ?: if (!cleanId.startsWith("tt") && cleanId.all { it.isDigit() }) cleanId else null
        var resolvedTitle: String? = null
        var resolvedYear: String? = null

        val effectiveKey = apiKey ?: DEFAULT_TMDB_KEY

        // 1. If we have IMDB ID but need TMDB ID, query TMDB /find endpoint
        if (!imdb.isNullOrEmpty() && tmdb.isNullOrEmpty()) {
            try {
                val findUrl = "https://api.themoviedb.org/3/find/${URLEncoder.encode(imdb, "UTF-8")}?external_source=imdb_id&api_key=$effectiveKey"
                val req = Request.Builder().url(findUrl).build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrEmpty()) {
                    val obj = JSONObject(body)
                    val isTvType = (type == "tv" || type == "series" || type == "anime")
                    val tvResults = obj.optJSONArray("tv_results")
                    val movieResults = obj.optJSONArray("movie_results")
                    val results = if (isTvType) {
                        if (tvResults != null && tvResults.length() > 0) tvResults else movieResults
                    } else {
                        if (movieResults != null && movieResults.length() > 0) movieResults else tvResults
                    }
                    if (results != null && results.length() > 0) {
                        val first = results.getJSONObject(0)
                        tmdb = first.optString("id", "")
                        resolvedTitle = first.optString("title", first.optString("name", null))
                        resolvedYear = first.optString("release_date", first.optString("first_air_date", null))?.take(4)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TMDB /find failed for $imdb: ${e.message}")
            }
        }

        // 2. Cinemeta fallback lookup for IMDB ID
        if (!imdb.isNullOrEmpty() && (tmdb.isNullOrEmpty() || resolvedTitle == null)) {
            try {
                val cinemetaType = if (type == "movie") "movie" else "series"
                val url = "https://v3-cinemeta.strem.io/meta/$cinemetaType/$imdb.json"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val json = JSONObject(body)
                    val meta = json.optJSONObject("meta")
                    if (meta != null) {
                        if (resolvedTitle == null) resolvedTitle = meta.optString("name", null)
                        if (resolvedYear == null) resolvedYear = meta.optString("year", null)
                        val tmdbFound = meta.optString("moviedb_id", meta.optString("tmdb_id", ""))
                        if (tmdbFound.isNotEmpty() && tmdb.isNullOrEmpty()) {
                            tmdb = tmdbFound
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cinemeta lookup failed for $imdb: ${e.message}")
            }
        }

        // 3. If we have TMDB ID but need IMDB ID or metadata, query TMDB endpoints
        if (!tmdb.isNullOrEmpty() && (imdb.isNullOrEmpty() || resolvedTitle == null || resolvedYear == null)) {
            try {
                val tmdbEndpoint = if (type == "movie") "movie" else "tv"
                
                // Get title, year, and external IDs in one shot or sequentially
                val detailsUrl = "https://api.themoviedb.org/3/$tmdbEndpoint/$tmdb?api_key=$effectiveKey&append_to_response=external_ids"
                val request = Request.Builder().url(detailsUrl).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val json = JSONObject(body)
                    if (resolvedTitle == null) {
                        resolvedTitle = json.optString("title", json.optString("name", null))
                    }
                    if (resolvedYear == null) {
                        val releaseDate = json.optString("release_date", json.optString("first_air_date", null))
                        if (!releaseDate.isNullOrEmpty() && releaseDate.length >= 4) {
                            resolvedYear = releaseDate.take(4)
                        }
                    }
                    val extIds = json.optJSONObject("external_ids")
                    val foundImdb = extIds?.optString("imdb_id", "") ?: json.optString("imdb_id", "")
                    if (foundImdb.startsWith("tt") && imdb.isNullOrEmpty()) {
                        imdb = foundImdb
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TMDB details & external_ids lookup failed for $tmdb: ${e.message}")
            }
        }

        val result = ResolvedMediaIds(
            imdbId = imdb,
            tmdbId = tmdb,
            title = resolvedTitle,
            year = resolvedYear,
            type = type
        )
        cache[cacheKey] = result
        result
    }
}
