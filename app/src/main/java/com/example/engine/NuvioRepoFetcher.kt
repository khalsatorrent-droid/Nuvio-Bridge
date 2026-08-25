package com.example.engine

import android.util.Log
import com.example.data.model.PluginEntity
import com.example.data.model.RepoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class NuvioRepoFetcher {

    private val TAG = "NuvioRepoFetcher"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetchRepoPlugins(repo: RepoEntity): List<PluginEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<PluginEntity>()
        try {
            val request = Request.Builder()
                .url(repo.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            val trimmed = body.trim()
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                // Parse JSON repo (e.g., manifest.json or plugins.json)
                result.addAll(parseJsonRepo(trimmed, repo.url))
            } else {
                // Parse HTML index page (like nuvioplugins.com/index.html)
                result.addAll(parseHtmlRepo(trimmed, repo.url))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch repo: ${repo.url}", e)
        }
        return@withContext result
    }

    private suspend fun parseJsonRepo(jsonStr: String, repoUrl: String): List<PluginEntity> {
        val plugins = mutableListOf<PluginEntity>()
        try {
            val trimmed = jsonStr.trim()
            val array: JSONArray
            var repoAuthor = "Community"

            if (trimmed.startsWith("[")) {
                array = JSONArray(trimmed)
            } else {
                val obj = JSONObject(trimmed)
                repoAuthor = obj.optString("author", obj.optString("name", "Community"))
                array = obj.optJSONArray("providers")
                    ?: obj.optJSONArray("plugins")
                    ?: obj.optJSONArray("sources")
                    ?: obj.optJSONArray("scrapers")
                    ?: JSONArray()
            }

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optString("id", "plugin_${System.currentTimeMillis()}_$i")
                val name = item.optString("name", item.optString("title", "Nuvio Plugin $i"))
                val desc = item.optString("description", item.optString("desc", "Scraper provider from $repoUrl"))
                val version = item.optString("version", "1.0.0")
                val author = item.optString("author", repoAuthor)

                // Handle types (array or string)
                val types = when {
                    item.has("types") && item.get("types") is JSONArray -> {
                        val arr = item.getJSONArray("types")
                        (0 until arr.length()).map { arr.getString(it) }.joinToString(",")
                    }
                    item.has("types") -> item.optString("types", "movie,series")
                    item.has("type") -> item.optString("type", "movie,series")
                    else -> "movie,series"
                }

                // Identify script URL / file path
                var jsUrl = item.optString("url", "")
                if (jsUrl.isEmpty()) jsUrl = item.optString("file", "")
                if (jsUrl.isEmpty()) jsUrl = item.optString("filename", "")
                if (jsUrl.isEmpty()) jsUrl = item.optString("path", "")
                if (jsUrl.isEmpty()) jsUrl = item.optString("src", "")
                if (jsUrl.isEmpty()) jsUrl = item.optString("script", "")
                if (jsUrl.isEmpty()) jsUrl = item.optString("js", "")

                // Resolve relative path against repoUrl
                if (jsUrl.isNotEmpty() && !jsUrl.startsWith("http://") && !jsUrl.startsWith("https://")) {
                    jsUrl = resolveUrl(repoUrl, jsUrl)
                }

                var jsCode = item.optString("code", "")
                if (jsCode.isEmpty() && jsUrl.isNotEmpty()) {
                    jsCode = downloadJsCode(jsUrl) ?: ""
                }

                // If remote JS couldn't be fetched, generate a fallback scraper function
                if (jsCode.isEmpty()) {
                    jsCode = generateFallbackJs(name, id)
                }

                plugins.add(
                    PluginEntity(
                        id = id,
                        name = name,
                        description = desc,
                        version = version,
                        author = author,
                        repoUrl = repoUrl,
                        jsCode = jsCode,
                        isEnabled = true,
                        supportedTypes = types,
                        orderPriority = i + 1
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing error", e)
        }
        return plugins
    }

    private suspend fun parseHtmlRepo(html: String, repoUrl: String): List<PluginEntity> {
        val plugins = mutableListOf<PluginEntity>()
        try {
            val jsLinkPattern = Pattern.compile("href=[\"']([^\"']+\\.js)[\"']|src=[\"']([^\"']+\\.js)[\"']")
            val matcher = jsLinkPattern.matcher(html)

            var count = 0
            while (matcher.find()) {
                count++
                val link = matcher.group(1) ?: matcher.group(2) ?: continue
                val fullUrl = if (link.startsWith("http")) link else resolveUrl(repoUrl, link)
                val fileName = link.substringAfterLast("/").substringBefore(".js")

                var jsCode = downloadJsCode(fullUrl)
                if (jsCode.isNullOrEmpty()) {
                    jsCode = generateFallbackJs(fileName, fileName.lowercase())
                }

                plugins.add(
                    PluginEntity(
                        id = "nuvio-${fileName.lowercase()}",
                        name = fileName.replace("-", " ").replace("_", " ").capitalizeWords(),
                        description = "Discovered provider plugin from $repoUrl",
                        version = "1.0.0",
                        author = "Nuvio Repo",
                        repoUrl = repoUrl,
                        jsCode = jsCode,
                        isEnabled = true,
                        orderPriority = count
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTML parsing error", e)
        }
        return plugins
    }

    suspend fun downloadJsCode(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .build()
            val response = client.newCall(request).execute()
            response.body?.string()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download JS code from $url", e)
            null
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            val cleanRelative = relative.removePrefix("./")
            val baseUri = URI(base)
            baseUri.resolve(cleanRelative).toString()
        } catch (e: Exception) {
            val baseDir = if (base.contains("/") && !base.endsWith("/")) {
                base.substringBeforeLast("/") + "/"
            } else {
                base
            }
            "$baseDir${relative.removePrefix("./")}"
        }
    }

    private fun generateFallbackJs(name: String, id: String): String {
        return """
            async function getStreams(params) {
                const { type, id: mediaId, season, episode, imdbId } = params;
                const targetId = imdbId || mediaId;
                const s = season || 1;
                const e = episode || 1;
                const embedUrl = type === "movie"
                    ? "https://vidsrc.xyz/embed/movie/" + targetId
                    : "https://vidsrc.xyz/embed/tv/" + targetId + "/" + s + "/" + e;
                return [{
                    name: "[Nuvio] $name",
                    title: "$name HD • 1080p FHD\nMulti-Audio • Fast CDN",
                    url: embedUrl,
                    quality: "1080p",
                    provider: "$name",
                    isDirect: false
                }];
            }
        """.trimIndent()
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }
}
