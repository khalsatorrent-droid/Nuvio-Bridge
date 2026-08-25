package com.example.server

import android.content.Context
import android.util.Log
import com.example.data.model.RawPluginStream
import com.example.data.model.ServerLogEntity
import com.example.data.model.StremioManifest
import com.example.data.model.StremioStreamResponse
import com.example.data.repository.PluginRepository
import com.example.engine.IdResolver
import com.example.engine.PluginRunner
import com.example.engine.StreamFormatter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

class StremioHttpServer(
    private val context: Context,
    private val repository: PluginRepository,
    private val pluginRunner: PluginRunner,
    var port: Int = 8585
) {
    private val TAG = "StremioHttpServer"
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + Job())

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val manifestAdapter = moshi.adapter(StremioManifest::class.java)
    private val streamResponseAdapter = moshi.adapter(StremioStreamResponse::class.java)

    var sortByQuality: Boolean = true
    var groupByQuality: Boolean = true
    var filterOutLowQuality: Boolean = false
    var requestTimeoutSec: Int = 25

    fun start(onStarted: ((Boolean, String?) -> Unit)? = null) {
        if (isRunning.get()) {
            onStarted?.invoke(true, null)
            return
        }

        serverJob = serverScope.launch {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                isRunning.set(true)
                Log.i(TAG, "Stremio Addon Server started on port $port")
                withContext(Dispatchers.Main) {
                    onStarted?.invoke(true, null)
                }

                while (isActive && isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        serverScope.launch {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.w(TAG, "Socket accept exception: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port", e)
                isRunning.set(false)
                withContext(Dispatchers.Main) {
                    onStarted?.invoke(false, e.message)
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverJob?.cancel()
        serverSocket = null
        Log.i(TAG, "Stremio Addon Server stopped")
    }

    fun isServerRunning(): Boolean = isRunning.get()

    private suspend fun handleClient(socket: Socket) {
        val startTime = System.currentTimeMillis()
        var method = "GET"
        var path = "/"
        var statusCode = 200
        var streamsFound = 0
        val clientIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"

        try {
            socket.soTimeout = 20000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine()
            if (requestLine.isNullOrEmpty()) {
                socket.close()
                return
            }

            val parts = requestLine.split(" ")
            method = parts.getOrNull(0) ?: "GET"
            val rawPath = parts.getOrNull(1) ?: "/"
            path = URLDecoder.decode(rawPath, "UTF-8")

            // Read remaining headers
            var headerLine: String? = reader.readLine()
            val headers = mutableMapOf<String, String>()
            while (!headerLine.isNullOrEmpty()) {
                val idx = headerLine.indexOf(":")
                if (idx > 0) {
                    headers[headerLine.substring(0, idx).trim().lowercase()] = headerLine.substring(idx + 1).trim()
                }
                headerLine = reader.readLine()
            }

            val hostHeader = headers["host"] ?: "127.0.0.1:$port"

            if (method.equals("OPTIONS", ignoreCase = true)) {
                sendCorsResponse(out)
                statusCode = 204
            } else if (path == "/manifest.json") {
                val manifest = StremioManifest(
                    name = "Nuvio Local ($port)",
                    description = "Local Nuvio Stream Scraper Addon with Smart Quality Sorting"
                )
                val json = manifestAdapter.toJson(manifest)
                sendJsonResponse(out, 200, json)
                statusCode = 200
            } else if (path.startsWith("/stream/")) {
                val streamResult = handleStreamRequest(path)
                streamsFound = streamResult.streams.size
                val json = streamResponseAdapter.toJson(streamResult)
                sendJsonResponse(out, 200, json)
                statusCode = 200
            } else if (path == "/health" || path == "/status") {
                val enabledPlugins = repository.getEnabledPlugins()
                val json = """{"status":"running","port":$port,"activePlugins":${enabledPlugins.size},"uptimeMs":${System.currentTimeMillis() - startTime}}"""
                sendJsonResponse(out, 200, json)
                statusCode = 200
            } else {
                val html = generateWebDashboardHtml(hostHeader)
                sendHtmlResponse(out, 200, html)
                statusCode = 200
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: $path", e)
            statusCode = 500
        } finally {
            val duration = System.currentTimeMillis() - startTime
            try {
                socket.close()
            } catch (_: Exception) {}

            // Save log in background
            repository.logServerRequest(
                ServerLogEntity(
                    timestamp = System.currentTimeMillis(),
                    method = method,
                    path = path,
                    status = statusCode,
                    durationMs = duration,
                    streamsFound = streamsFound,
                    clientIp = clientIp
                )
            )
        }
    }

    private val responseCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, StremioStreamResponse>>()
    private val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    suspend fun handleStreamRequest(path: String): StremioStreamResponse {
        val clean = path.removePrefix("/stream/").removeSuffix(".json")
        val segments = clean.split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return StremioStreamResponse(emptyList())
        }

        val type = segments[0].lowercase() // movie, series, anime, tv
        // Join remaining path segments with colon so both /stream/series/tt123:1:2 and /stream/series/tt123/1/2 work identically
        val rawId = segments.drop(1).joinToString(":")

        // Check response cache
        val cacheKey = "$type:$rawId:$sortByQuality:$groupByQuality:$filterOutLowQuality"
        val cached = responseCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            Log.d(TAG, "Serving cached stream results for $cacheKey (${cached.second.streams.size} streams)")
            return cached.second
        }

        var mainId = rawId
        var season: Int? = null
        var episode: Int? = null
        var imdbId: String? = null
        var tmdbId: String? = null
        var kitsuId: String? = null

        val parts = rawId.split(":").filter { it.isNotEmpty() }

        if (rawId.startsWith("kitsu:")) {
            kitsuId = parts.getOrNull(1)
            if (parts.size >= 4) {
                // kitsu:1234:2:5 (season 2, ep 5)
                season = parts.getOrNull(2)?.toIntOrNull()
                episode = parts.getOrNull(3)?.toIntOrNull()
            } else {
                // kitsu:1234:5 (season 1, ep 5)
                season = 1
                episode = parts.getOrNull(2)?.toIntOrNull()
            }
            mainId = kitsuId ?: rawId

            // Resolve kitsuId to IMDb tt-id using 3-step fallback chain
            if (!kitsuId.isNullOrEmpty()) {
                val resolvedFromKitsu = IdResolver.resolveKitsuId(kitsuId)
                if (!resolvedFromKitsu.isNullOrEmpty()) {
                    imdbId = resolvedFromKitsu
                }
            }
        } else if (rawId.startsWith("tmdb:")) {
            tmdbId = parts.getOrNull(1)
            if (parts.size >= 4) {
                season = parts.getOrNull(2)?.toIntOrNull()
                episode = parts.getOrNull(3)?.toIntOrNull()
            } else if (parts.size == 3) {
                season = 1
                episode = parts.getOrNull(2)?.toIntOrNull()
            }
            mainId = tmdbId ?: rawId
        } else if (parts.size >= 3) {
            // e.g. tt0903747:2:5
            val first = parts[0]
            if (first.startsWith("tt")) {
                imdbId = first
            } else if (first.all { it.isDigit() }) {
                tmdbId = first
            }
            season = parts[1].toIntOrNull()
            episode = parts[2].toIntOrNull()
            mainId = first
        } else if (parts.size == 2) {
            // e.g. tt0903747:5 or 12345:5
            val first = parts[0]
            val second = parts[1]
            if (first.startsWith("tt")) imdbId = first
            else if (first.all { it.isDigit() }) tmdbId = first
            mainId = first
            season = 1
            episode = second.toIntOrNull()
        } else {
            imdbId = if (rawId.startsWith("tt")) rawId else null
            tmdbId = if (!rawId.startsWith("tt") && rawId.all { it.isDigit() }) rawId else null
            mainId = rawId
        }

        // For non-movies (series, anime, tv), ensure season and episode are never omitted
        if (type != "movie") {
            if (season == null && episode != null) season = 1
            if (episode == null && season != null) episode = 1
            if (season == null && episode == null) {
                season = 1
                episode = 1
            }
        }

        Log.i(TAG, "Stream request parsed: type=$type id=$mainId season=$season episode=$episode imdbId=$imdbId tmdbId=$tmdbId kitsuId=$kitsuId")

        // Auto resolve dual IDs so providers expecting tmdbId (Nuvio standard) or imdbId receive both
        val resolvedIds = IdResolver.resolve(
            rawId = mainId,
            type = type,
            existingImdbId = imdbId,
            existingTmdbId = tmdbId
        )

        val finalImdbId = resolvedIds.imdbId ?: imdbId
        val finalTmdbId = resolvedIds.tmdbId ?: tmdbId

        val enabledPlugins = repository.getEnabledPlugins().filter { plugin ->
            val types = plugin.supportedTypes.split(",").map { it.trim().lowercase() }
            types.isEmpty() || types.contains(type.lowercase()) || types.contains("all")
        }

        if (enabledPlugins.isEmpty()) {
            return StremioStreamResponse(emptyList())
        }

        // Concurrently run all enabled plugins with timeout
        val timeoutMs = (requestTimeoutSec * 1000L).coerceAtLeast(4000L)
        val deferredResults = enabledPlugins.map { plugin ->
            serverScope.async {
                try {
                    pluginRunner.runPlugin(
                        plugin = plugin,
                        type = type,
                        id = mainId,
                        season = season,
                        episode = episode,
                        tmdbId = finalTmdbId,
                        imdbId = finalImdbId,
                        kitsuId = kitsuId,
                        title = resolvedIds.title,
                        year = resolvedIds.year,
                        timeoutMs = timeoutMs
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing plugin ${plugin.id}", e)
                    emptyList<RawPluginStream>()
                }
            }
        }

        val allRawStreams = deferredResults.awaitAll().flatten()
        val formattedStreams = StreamFormatter.formatAndSortStreams(
            rawStreams = allRawStreams,
            sortByQuality = sortByQuality,
            groupByQuality = groupByQuality,
            filterOutLowQuality = filterOutLowQuality
        )

        val response = StremioStreamResponse(streams = formattedStreams)
        if (formattedStreams.isNotEmpty()) {
            responseCache[cacheKey] = Pair(System.currentTimeMillis(), response)
        }
        return response
    }

    private fun sendCorsResponse(out: OutputStream) {
        val response = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Connection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sendJsonResponse(out: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $status OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Cache-Control: max-age=1800, public\r\n" +
                "Connection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendHtmlResponse(out: OutputStream, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $status OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun generateWebDashboardHtml(host: String): String {
        val manifestUrl = "http://$host/manifest.json"
        val stremioDeeplink = "stremio://$host/manifest.json"
        val stremioWebUrl = "https://web.stremio.com/#/addons?addon=" + java.net.URLEncoder.encode(manifestUrl, "UTF-8")

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Nuvio Stremio Server</title>
                <style>
                    :root {
                        --bg: #0b0914;
                        --card: #151226;
                        --card-border: #2c254b;
                        --primary: #7c4dff;
                        --accent: #00e5ff;
                        --text: #f0edf9;
                        --text-dim: #9b94b8;
                        --success: #00e676;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
                    body { background: var(--bg); color: var(--text); padding: 24px 16px; display: flex; justify-content: center; }
                    .container { max-width: 680px; width: 100%; }
                    .header { text-align: center; margin-bottom: 28px; }
                    .logo { font-size: 32px; font-weight: 800; background: linear-gradient(135deg, #7c4dff, #00e5ff); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
                    .subtitle { color: var(--text-dim); margin-top: 6px; font-size: 14px; }
                    .badge { display: inline-flex; align-items: center; gap: 6px; background: rgba(0, 230, 118, 0.15); color: var(--success); padding: 4px 12px; border-radius: 999px; font-size: 13px; font-weight: 600; margin-top: 12px; border: 1px solid rgba(0, 230, 118, 0.3); }
                    .pulse { width: 8px; height: 8px; background: var(--success); border-radius: 50%; box-shadow: 0 0 8px var(--success); }
                    .card { background: var(--card); border: 1px solid var(--card-border); border-radius: 16px; padding: 20px; margin-bottom: 20px; }
                    .btn { display: inline-block; width: 100%; padding: 14px; text-align: center; border-radius: 12px; font-weight: 700; font-size: 15px; text-decoration: none; cursor: pointer; border: none; transition: transform 0.1s, opacity 0.2s; margin-top: 10px; }
                    .btn-primary { background: linear-gradient(135deg, #7c4dff, #536dfe); color: #fff; box-shadow: 0 4px 16px rgba(124, 77, 255, 0.4); }
                    .btn-secondary { background: rgba(255, 255, 255, 0.08); color: var(--text); border: 1px solid var(--card-border); }
                    .url-box { background: rgba(0,0,0,0.3); padding: 12px; border-radius: 8px; border: 1px dashed var(--card-border); font-family: monospace; font-size: 13px; color: var(--accent); word-break: break-all; margin: 12px 0; }
                    .features { list-style: none; }
                    .features li { display: flex; align-items: center; gap: 10px; padding: 8px 0; color: var(--text-dim); font-size: 14px; border-bottom: 1px solid rgba(255,255,255,0.04); }
                    .features li span { color: var(--accent); font-weight: bold; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <div class="logo">⚡ NUVIO SERVER</div>
                        <div class="subtitle">Local Stremio Addon Engine</div>
                        <div class="badge"><div class="pulse"></div> Server Running on Port $port</div>
                    </div>

                    <div class="card">
                        <h3 style="margin-bottom: 8px;">Install on Stremio</h3>
                        <p style="color: var(--text-dim); font-size: 13px;">Add this local addon to your Stremio app to stream movies & TV shows with real Nuvio scrapers.</p>
                        <div class="url-box">$manifestUrl</div>
                        <a href="$stremioDeeplink" class="btn btn-primary">⚡ Install Directly in Stremio App</a>
                        <a href="$stremioWebUrl" target="_blank" class="btn btn-secondary">🌐 Open in Stremio Web</a>
                    </div>

                    <div class="card">
                        <h3 style="margin-bottom: 12px;">Active Capabilities</h3>
                        <ul class="features">
                            <li><span>✓</span> Port $port with zero-CORS Native Bridge</li>
                            <li><span>✓</span> Automatic 4K, 1080p, 720p Quality Detection</li>
                            <li><span>✓</span> Direct Multi-Plugin Scraper Aggregation</li>
                            <li><span>✓</span> Supports TMDB, IMDB (tt) & Kitsu IDs</li>
                        </ul>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
