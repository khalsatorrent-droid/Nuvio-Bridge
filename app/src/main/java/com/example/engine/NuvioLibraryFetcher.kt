package com.example.engine

import android.util.Log
import com.example.data.model.LibraryRepoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class NuvioLibraryFetcher {

    private val TAG = "NuvioLibraryFetcher"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        const val LIBRARY_URL = "https://nuvio-plugin-library.vercel.app/"

        val CURATED_LIBRARY_REPOS = listOf(
            LibraryRepoItem(
                id = "nuvio-yoruix",
                name = "Yoruix Nuvio Providers",
                author = "yoruix",
                description = "Official Nuvio providers repository with 4K UHD, 1080p FHD, multi-stream failover, and multi-language audio tracks.",
                manifestUrl = "https://raw.githubusercontent.com/yoruix/nuvio-providers/refs/heads/main/manifest.json",
                tags = listOf("4K UHD", "1080p FHD", "Multi-Audio", "Fast CDN"),
                isVerified = true,
                estimatedProviders = 8,
                badge = "Featured"
            ),
            LibraryRepoItem(
                id = "nuvio-all-in-one",
                name = "All-in-One Nuvio",
                author = "D3adlyRocket",
                description = "Comprehensive all-in-one provider collection with VidSrc, Embed.su, MoviesAPI, AnimePahe, and high-speed multi-host sources.",
                manifestUrl = "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json",
                tags = listOf("All-in-One", "Movies", "TV Shows", "Anime"),
                isVerified = true,
                estimatedProviders = 12,
                badge = "Top Rated"
            ),
            LibraryRepoItem(
                id = "nuvio-sharn25",
                name = "Sharn25 Provider Hub",
                author = "Sharn25",
                description = "High performance fast streaming provider hub with direct MP4/HLS streams, subtitle integration, and low latency.",
                manifestUrl = "https://raw.githubusercontent.com/Sharn25/nuvio-plugins/main/manifest.json",
                tags = listOf("Fast HTTP", "Subtitles", "Direct CDN"),
                isVerified = true,
                estimatedProviders = 6,
                badge = "Popular"
            ),
            LibraryRepoItem(
                id = "nuvio-official",
                name = "Nuvio Built-in Core Scrapers",
                author = "Nuvio Core",
                description = "Essential core scrapers with automatic CDN failover, multi-server resolvers, and fallback stream extractors.",
                manifestUrl = "https://nuvioplugins.com/index.html",
                tags = listOf("Core Scrapers", "Failover", "Stable"),
                isVerified = true,
                estimatedProviders = 6,
                badge = "Official"
            ),
            LibraryRepoItem(
                id = "nuvio-anime-hub",
                name = "Nuvio Anime & Global Hub",
                author = "Community",
                description = "Dedicated provider list for international anime streaming, dual audio releases, and multi-resolution direct links.",
                manifestUrl = "https://raw.githubusercontent.com/yoruix/nuvio-providers/refs/heads/main/manifest.json",
                tags = listOf("Anime", "Sub/Dub", "1080p"),
                isVerified = true,
                estimatedProviders = 5,
                badge = "Community"
            )
        )
    }

    suspend fun fetchActiveLibraryRepos(): List<LibraryRepoItem> = withContext(Dispatchers.IO) {
        val discoveredList = mutableListOf<LibraryRepoItem>()
        try {
            val request = Request.Builder()
                .url(LIBRARY_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string()

            if (!html.isNullOrEmpty()) {
                discoveredList.addAll(parseLibraryHtml(html))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not scrape live library page directly (${e.message}), utilizing curated catalog", e)
        }

        // Merge discovered with curated list (avoiding duplicate URLs)
        val combined = mutableListOf<LibraryRepoItem>()
        val existingUrls = mutableSetOf<String>()

        for (item in discoveredList) {
            if (existingUrls.add(item.manifestUrl.lowercase().trim())) {
                combined.add(item)
            }
        }

        for (item in CURATED_LIBRARY_REPOS) {
            if (existingUrls.add(item.manifestUrl.lowercase().trim())) {
                combined.add(item)
            }
        }

        return@withContext combined
    }

    private fun parseLibraryHtml(html: String): List<LibraryRepoItem> {
        val list = mutableListOf<LibraryRepoItem>()
        try {
            // Find raw github manifest links or json links in the HTML
            val pattern = Pattern.compile("https?://raw\\.githubusercontent\\.com/[^\"'\\s<>]+manifest\\.json", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(html)
            var count = 1
            val seen = mutableSetOf<String>()

            while (matcher.find()) {
                val url = matcher.group()
                if (seen.add(url)) {
                    val segments = url.split("/")
                    val author = if (segments.size >= 4) segments[3] else "Community"
                    val repoName = if (segments.size >= 5) segments[4].replace("-", " ").replace("_", " ") else "Nuvio Provider $count"
                    list.add(
                        LibraryRepoItem(
                            id = "discovered_$count",
                            name = "$repoName (${author})",
                            author = author,
                            description = "Discovered provider repository from Nuvio Plugin Library",
                            manifestUrl = url,
                            homepageUrl = LIBRARY_URL,
                            tags = listOf("Discovered", "Live Link"),
                            isVerified = true,
                            estimatedProviders = 4,
                            badge = "Active"
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing library HTML", e)
        }
        return list
    }
}
