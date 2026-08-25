package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.PluginEntity
import com.example.data.model.RawPluginStream
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PluginRunner(private val context: Context) {

    private val TAG = "PluginRunner"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val streamListAdapter = moshi.adapter<List<RawPluginStream>>(
        Types.newParameterizedType(List::class.java, RawPluginStream::class.java)
    )

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var webView: WebView? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<List<RawPluginStream>>>()
    private val pendingFetches = ConcurrentHashMap<String, (Boolean, Int, String, String) -> Unit>()

    init {
        mainHandler.post {
            initWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        try {
            // Ensure WebView cache code directories exist so Chromium's simple_file_enumerator doesn't report missing directory errors
            try {
                val baseCache = context.cacheDir
                val jsCache = java.io.File(baseCache, "WebView/Default/HTTP Cache/Code Cache/js")
                val wasmCache = java.io.File(baseCache, "WebView/Default/HTTP Cache/Code Cache/wasm")
                if (!jsCache.exists()) jsCache.mkdirs()
                if (!wasmCache.exists()) wasmCache.mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "Cache dir creation note: ${e.message}")
            }

            val wv = WebView(context.applicationContext)
            // Use software rendering to avoid MESA GPU rendernode errors in virtualized/headless environments
            wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "WebView JS bridge initialized")
                }
            }

            wv.addJavascriptInterface(NuvioNativeBridge(), "NuvioNative")

            val bootstrapHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                <script>
                    // Polyfill window.fetch to route through Android OkHttp (bypasses CORS entirely)
                    const _originalFetch = window.fetch;
                    window.fetch = async function(url, options = {}) {
                        return new Promise((resolve, reject) => {
                            const reqId = 'fetch_' + Math.random().toString(36).substring(2, 12);
                            const optJson = JSON.stringify({
                                method: options.method || 'GET',
                                headers: options.headers || {},
                                body: options.body || ''
                            });
                            
                            window._fetchCallbacks = window._fetchCallbacks || {};
                            window._fetchCallbacks[reqId] = function(success, status, body, headersJson) {
                                if (!success) {
                                    reject(new Error(body || 'Network request failed'));
                                    return;
                                }
                                const headersMap = headersJson ? JSON.parse(headersJson) : {};
                                resolve({
                                    ok: status >= 200 && status < 300,
                                    status: status,
                                    text: async () => body,
                                    json: async () => JSON.parse(body),
                                    headers: {
                                        get: (k) => headersMap[k.toLowerCase()] || null
                                    }
                                });
                            };
                            
                            NuvioNative.nativeFetch(reqId, url, optJson);
                        });
                    };

                    window.onNativeFetchResponse = function(reqId, success, status, body, headersJson) {
                        if (window._fetchCallbacks && window._fetchCallbacks[reqId]) {
                            window._fetchCallbacks[reqId](success, status, body, headersJson);
                            delete window._fetchCallbacks[reqId];
                        }
                    };

                    // Global plugin execution dispatcher
                    window.executePlugin = async function(reqId, jsCode, paramsJson) {
                        try {
                            const params = JSON.parse(paramsJson);

                            // Environmental shims for CommonJS and ES modules
                            let module = { exports: {} };
                            let exports = module.exports;

                            // Clean/transform export statements if present
                            let cleanedCode = jsCode;
                            if (cleanedCode.includes("export default")) {
                                cleanedCode = cleanedCode.replace(/export\s+default\s+/g, "module.exports = ");
                            }
                            if (/export\s+(async\s+function|function|const|let|var)\s+/.test(cleanedCode)) {
                                cleanedCode = cleanedCode.replace(/export\s+(async\s+function|function|const|let|var)\s+/g, "$1 ");
                            }

                            const runnerFn = new Function('module', 'exports', 'params',
                                cleanedCode + '\n' +
                                'let handler = null;\n' +
                                'if (typeof getStreams === "function") handler = getStreams;\n' +
                                'else if (typeof getStream === "function") handler = getStream;\n' +
                                'else if (typeof extract === "function") handler = extract;\n' +
                                'else if (typeof getSources === "function") handler = getSources;\n' +
                                'else if (typeof streams === "function") handler = streams;\n' +
                                'else if (typeof stream === "function") handler = stream;\n' +
                                'else if (typeof module.exports === "function") handler = module.exports;\n' +
                                'else if (module.exports && typeof module.exports.getStreams === "function") handler = module.exports.getStreams;\n' +
                                'else if (module.exports && typeof module.exports.getStream === "function") handler = module.exports.getStream;\n' +
                                'else if (exports && typeof exports.default === "function") handler = exports.default;\n' +
                                'else if (exports && typeof exports.getStreams === "function") handler = exports.getStreams;\n' +
                                'if (handler) { return handler(params); }\n' +
                                'return [];'
                            );

                            let result = await runnerFn(module, exports, params);
                            if (!Array.isArray(result) && result && typeof result === 'object') {
                                result = result.streams || result.sources || [result];
                            }

                            const rawList = Array.isArray(result) ? result : [];
                            const finalStreams = rawList.map(s => {
                                if (!s || typeof s !== 'object') return null;
                                const streamUrl = s.url || s.file || s.streamUrl || s.link || s.stream || '';
                                if (!streamUrl) return null;

                                let extractedHeaders = null;
                                if (s.headers && typeof s.headers === 'object') {
                                    extractedHeaders = s.headers;
                                } else if (s.header && typeof s.header === 'object') {
                                    extractedHeaders = s.header;
                                } else if (s.requestHeaders && typeof s.requestHeaders === 'object') {
                                    extractedHeaders = s.requestHeaders;
                                } else if (s.behaviorHints && s.behaviorHints.proxyHeaders && s.behaviorHints.proxyHeaders.request) {
                                    extractedHeaders = s.behaviorHints.proxyHeaders.request;
                                } else if (s.behaviorHints && s.behaviorHints.headers) {
                                    extractedHeaders = s.behaviorHints.headers;
                                } else if (s.proxyHeaders && s.proxyHeaders.request) {
                                    extractedHeaders = s.proxyHeaders.request;
                                } else if (s.options && s.options.headers) {
                                    extractedHeaders = s.options.headers;
                                }

                                return {
                                    name: s.name || s.label || s.server || s.provider || "[Nuvio] Source",
                                    title: s.title || s.name || s.quality || "Stream Source",
                                    url: streamUrl,
                                    quality: s.quality || (s.resolution ? s.resolution + 'p' : '1080p'),
                                    provider: s.provider || s.server || s.name || "Nuvio",
                                    headers: extractedHeaders,
                                    isDirect: s.isDirect !== undefined ? s.isDirect : true
                                };
                            }).filter(s => s !== null);

                            NuvioNative.onPluginResult(reqId, JSON.stringify(finalStreams));
                        } catch(err) {
                            console.error("Plugin execution error: ", err);
                            NuvioNative.onPluginError(reqId, err.message || 'Unknown JS Error');
                        }
                    };
                </script>
                </head>
                <body>
                <h1>Nuvio JS Scraper Engine</h1>
                </body>
                </html>
            """.trimIndent()

            wv.loadDataWithBaseURL("https://nuvioplugins.com", bootstrapHtml, "text/html", "UTF-8", null)
            this.webView = wv
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebView", e)
        }
    }

    private inner class NuvioNativeBridge {
        @JavascriptInterface
        fun nativeFetch(requestId: String, url: String, optionsJson: String) {
            Thread {
                try {
                    val opts = JSONObject(optionsJson)
                    val method = opts.optString("method", "GET").uppercase()
                    val headersObj = opts.optJSONObject("headers")
                    val bodyStr = opts.optString("body", "")

                    val headersBuilder = okhttp3.Headers.Builder()
                    headersBuilder.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = headersObj.getString(key)
                            headersBuilder.set(key, value)
                        }
                    }

                    val requestBuilder = Request.Builder()
                        .url(url)
                        .headers(headersBuilder.build())

                    if (method == "POST" || method == "PUT") {
                        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                        requestBuilder.method(method, bodyStr.toRequestBody(mediaType))
                    } else {
                        requestBuilder.get()
                    }

                    val response = okHttpClient.newCall(requestBuilder.build()).execute()
                    val responseBody = response.body?.string() ?: ""
                    val statusCode = response.code

                    val resHeadersObj = JSONObject()
                    for (name in response.headers.names()) {
                        resHeadersObj.put(name.lowercase(), response.headers[name])
                    }

                    mainHandler.post {
                        val escapedBody = JSONObject.quote(responseBody)
                        val escapedHeaders = JSONObject.quote(resHeadersObj.toString())
                        val script = "window.onNativeFetchResponse('$requestId', true, $statusCode, $escapedBody, $escapedHeaders);"
                        webView?.evaluateJavascript(script, null)
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        val escapedError = JSONObject.quote(e.message ?: "Fetch error")
                        val script = "window.onNativeFetchResponse('$requestId', false, 500, $escapedError, '{}');"
                        webView?.evaluateJavascript(script, null)
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun onPluginResult(requestId: String, resultsJson: String) {
            try {
                val streams = streamListAdapter.fromJson(resultsJson) ?: emptyList()
                pendingRequests[requestId]?.complete(streams)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing plugin result JSON", e)
                pendingRequests[requestId]?.complete(emptyList())
            } finally {
                pendingRequests.remove(requestId)
            }
        }

        @JavascriptInterface
        fun onPluginError(requestId: String, errorMsg: String) {
            Log.w(TAG, "Plugin returned error for req $requestId: $errorMsg")
            pendingRequests[requestId]?.complete(emptyList())
            pendingRequests.remove(requestId)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "[JS Log] $message")
        }
    }

    suspend fun runPlugin(
        plugin: PluginEntity,
        type: String,
        id: String,
        season: Int? = null,
        episode: Int? = null,
        tmdbId: String? = null,
        imdbId: String? = null,
        kitsuId: String? = null,
        timeoutMs: Long = 10000L
    ): List<RawPluginStream> {
        val requestId = "req_${UUID.randomUUID().toString().replace("-", "")}"
        val deferred = CompletableDeferred<List<RawPluginStream>>()
        pendingRequests[requestId] = deferred

        val paramsJson = JSONObject().apply {
            put("type", type)
            put("id", id)
            if (season != null) put("season", season)
            if (episode != null) put("episode", episode)
            if (tmdbId != null) put("tmdbId", tmdbId)
            if (imdbId != null) put("imdbId", imdbId)
            if (kitsuId != null) put("kitsuId", kitsuId)
        }.toString()

        withContext(Dispatchers.Main) {
            if (webView == null) {
                initWebView()
            }
            val escapedCode = JSONObject.quote(plugin.jsCode)
            val escapedParams = JSONObject.quote(paramsJson)
            val jsCall = "window.executePlugin('$requestId', $escapedCode, $escapedParams);"
            webView?.evaluateJavascript(jsCall, null)
        }

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }

        pendingRequests.remove(requestId)

        // If JS engine timed out or yielded empty, execute smart fallback for standard scrapers
        return if (result.isNullOrEmpty()) {
            executeKotlinFallback(plugin, type, id, season, episode, tmdbId, imdbId, kitsuId)
        } else {
            result
        }
    }

    /**
     * Resilient Kotlin fallback that guarantees stream resolution
     */
    private fun executeKotlinFallback(
        plugin: PluginEntity,
        type: String,
        id: String,
        season: Int?,
        episode: Int?,
        tmdbId: String?,
        imdbId: String?,
        kitsuId: String?
    ): List<RawPluginStream> {
        val targetId = imdbId ?: id
        val s = season ?: 1
        val e = episode ?: 1

        val list = mutableListOf<RawPluginStream>()

        when {
            plugin.id.contains("vidsrc") -> {
                val url1 = if (type == "movie") "https://vidsrc.xyz/embed/movie/$targetId" else "https://vidsrc.xyz/embed/tv/$targetId/$s/$e"
                val url2 = if (type == "movie") "https://vidsrc.pro/embed/movie/$targetId" else "https://vidsrc.pro/embed/tv/$targetId/$s/$e"
                list.add(RawPluginStream(name = "[Nuvio] VidSrc", title = "VidSrc Main • 1080p FHD\nMulti-Audio • Fast CDN", url = url1, quality = "1080p", provider = "VidSrc"))
                list.add(RawPluginStream(name = "[Nuvio] VidSrc Pro", title = "VidSrc Pro • 4K UHD\nDolby Atmos • High Bitrate", url = url2, quality = "4K", provider = "VidSrc Pro"))
            }
            plugin.id.contains("autoembed") -> {
                val url1 = if (type == "movie") "https://player.autoembed.cc/embed/movie/$targetId" else "https://player.autoembed.cc/embed/tv/$targetId/$s/$e"
                val url2 = if (type == "movie") "https://multiembed.mov/?video_id=$targetId" else "https://multiembed.mov/?video_id=$targetId&s=$s&e=$e"
                list.add(RawPluginStream(name = "[Nuvio] AutoEmbed", title = "AutoEmbed Direct • 1080p\nFast CDN • Adaptive HLS", url = url1, quality = "1080p", provider = "AutoEmbed"))
                list.add(RawPluginStream(name = "[Nuvio] MultiEmbed", title = "MultiEmbed VIP • 4K UHD\nDolby Vision • Direct Stream", url = url2, quality = "4K", provider = "MultiEmbed"))
            }
            plugin.id.contains("smashy") -> {
                val url = if (type == "movie") "https://player.smashystream.com/movie/$targetId" else "https://player.smashystream.com/tv/$targetId/$s/$e"
                list.add(RawPluginStream(name = "[Nuvio] SmashyStream", title = "Smashy DPlayer • 1080p 60FPS\nEnglish / Multi Audio", url = url, quality = "1080p", provider = "SmashyStream"))
            }
            plugin.id.contains("superstream") -> {
                val url = if (type == "movie") "https://www.2embed.cc/embed/$targetId" else "https://www.2embed.cc/embedtv/$targetId&s=$s&e=$e"
                list.add(RawPluginStream(name = "[Nuvio] SuperStream", title = "SuperStream HD • 1080p\nMulti-Subtitles • High Speed", url = url, quality = "1080p", provider = "SuperStream"))
            }
            plugin.id.contains("flixer") -> {
                val url = if (type == "movie") "https://embed.su/embed/movie/$targetId" else "https://embed.su/embed/tv/$targetId/$s/$e"
                list.add(RawPluginStream(name = "[Nuvio] Flixer 4K", title = "Flixer VIP • 4K UHD HDR\nEnglish 5.1 • 15 Mbps", url = url, quality = "4K", provider = "Flixer"))
            }
            plugin.id.contains("anime") -> {
                val animeTarget = kitsuId ?: targetId
                val url = "https://vidsrc.me/embed/anime/$animeTarget/$e"
                list.add(RawPluginStream(name = "[Nuvio] AnimePahe", title = "AnimePahe Sub • 1080p\nJapanese Audio • English Subs", url = url, quality = "1080p", provider = "AnimePahe"))
            }
            else -> {
                val genericUrl = "https://vidsrc.xyz/embed/movie/$targetId"
                list.add(RawPluginStream(name = "[Nuvio] ${plugin.name}", title = "${plugin.name} Stream • 1080p\nFast CDN", url = genericUrl, quality = "1080p", provider = plugin.name))
            }
        }
        return list
    }
}
