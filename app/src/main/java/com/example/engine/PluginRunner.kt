package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var webView: WebView? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<List<RawPluginStream>>>()

    companion object {
        const val DEFAULT_TMDB_KEY = "84698579998638b251ad02e97519ff08"

        val TMDB_HOSTNAME_OVERRIDES = setOf(
            "4khdhub", "4khdhubnew", "hdhub4u", "dahmermovies", "netmirror", "moviebox",
            "movies4u", "uhdmovies", "moviesdrive", "moviesmod"
        )

        val IMDB_HOSTNAME_OVERRIDES = setOf(
            "vegmovies", "vegmovies.mq", "vegmovies.dad", "vegmovies.nl", "vegmovies.ms",
            "vegmovies.com", "vegmovies.org", "vegmovies.tv", "vegmovies.in", "vegmovies.net",
            "vegmovies.io", "vegmovies.me", "vegamovies", "veamovies", "hindmoviez",
            "vidsrc", "vixsrc", "vidfast", "videasy", "vidlink", "vidrock", "cineby",
            "playimdb", "showbox", "peachify", "allmovieland", "xpass", "castle", "fibwatch",
            "dooflix", "zinkmovies", "notorrent", "movieblast", "cinemacity", "embedsu",
            "embed.su", "autoembed", "2embed", "multiembed", "smashystream", "smashy.stream",
            "moviesapi", "superembed", "frembed", "shadowlandschronicles", "embedrise",
            "flicky", "nontongo", "warezcdn", "asiacloud", "vidbinge", "vidora", "vidstream",
            "streamtape", "dl.vidsrc", "player.smashy"
        )

        /**
         * Scans JavaScript code for embedded 32-char TMDB hex API keys.
         */
        fun extractTmdbApiKey(jsCode: String): String? {
            val pattern = Regex("[0-9a-f]{32}", RegexOption.IGNORE_CASE)
            for (match in pattern.findAll(jsCode)) {
                val candidate = match.value.lowercase(Locale.ROOT)
                // Filter out non-keys (all zeros, repeating chars, all same)
                if (candidate.toSet().size >= 8 && candidate != "00000000000000000000000000000000") {
                    return candidate
                }
            }
            return null
        }

        /**
         * Determines whether a provider prefers 'tmdb' or 'imdb' as its primary identifier.
         */
        fun detectIdType(plugin: PluginEntity): String {
            val name = plugin.name.lowercase(Locale.ROOT)
            val id = plugin.id.lowercase(Locale.ROOT)
            val desc = plugin.description.lowercase(Locale.ROOT)
            val js = plugin.jsCode

            // 1. Explicit ID type keywords in description or name
            if (desc.contains("imdb only") || desc.contains("imdb id") || name.contains("imdb")) {
                return "imdb"
            }
            if (desc.contains("tmdb only") || desc.contains("tmdb id") || name.contains("tmdb")) {
                return "tmdb"
            }

            // 2. Hostname / provider name overrides
            for (kw in TMDB_HOSTNAME_OVERRIDES) {
                if (name.contains(kw) || id.contains(kw)) return "tmdb"
            }
            for (kw in IMDB_HOSTNAME_OVERRIDES) {
                if (name.contains(kw) || id.contains(kw)) return "imdb"
            }

            // 3. JS code content scanning
            if (js.contains("external_source=imdb_id") ||
                js.contains("searchwpjson") ||
                js.contains("wp-json") ||
                js.contains("wp/v2/posts")) {
                return "imdb"
            }
            if (js.contains("pengu.uk") ||
                js.contains("cinescrape") ||
                js.contains("/3/movie/") ||
                js.contains("/3/tv/")) {
                return "tmdb"
            }

            // Default standard for Nuvio ecosystem is TMDB
            return "tmdb"
        }
    }

    init {
        mainHandler.post {
            initWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        try {
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
            wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
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
                                    statusText: status === 200 ? 'OK' : 'Status ' + status,
                                    text: async () => body,
                                    json: async () => {
                                        try { return JSON.parse(body); } catch(e) { return {}; }
                                    },
                                    headers: {
                                        get: (k) => headersMap[k.toLowerCase()] || null,
                                        forEach: (cb) => { Object.keys(headersMap).forEach(k => cb(headersMap[k], k)); }
                                    }
                                });
                            };
                            
                            NuvioNative.nativeFetch(reqId, url.toString(), optJson);
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
                            const primaryId = params.primaryId || params.tmdbId || params.imdbId || params.id || "";
                            const altId = params.altId || (primaryId === params.tmdbId ? params.imdbId : params.tmdbId) || "";
                            const mediaType = params.mediaType || params.type || "movie";
                            const season = (params.season !== undefined && params.season !== null) ? Number(params.season) : 1;
                            const episode = (params.episode !== undefined && params.episode !== null) ? Number(params.episode) : 1;

                            // Environmental shims for CommonJS, ES modules, and Browser Global environments
                            let module = { exports: {} };
                            let exports = module.exports;
                            let global = window;
                            window.module = module;
                            window.exports = exports;
                            window.global = window;
                            window.SCRAPER_SETTINGS = window.SCRAPER_SETTINGS || {};

                            // Clean / transform ES module exports if present
                            let cleanedCode = jsCode;
                            if (cleanedCode.includes("export default")) {
                                cleanedCode = cleanedCode.replace(/export\s+default\s+/g, "module.exports = ");
                            }
                            if (/export\s+(async\s+function|function|const|let|var)\s+/.test(cleanedCode)) {
                                cleanedCode = cleanedCode.replace(/export\s+(async\s+function|function|const|let|var)\s+/g, "$1 ");
                            }

                            // Polyfill require for common modules
                            const requireShim = function(modName) {
                                const name = String(modName).toLowerCase();
                                if (name === 'axios') {
                                    const axiosFn = async (cfg) => {
                                        const url = typeof cfg === 'string' ? cfg : cfg.url;
                                        const method = (cfg.method || 'GET').toUpperCase();
                                        const res = await window.fetch(url, {
                                            method: method,
                                            headers: cfg.headers || {},
                                            body: cfg.data ? (typeof cfg.data === 'string' ? cfg.data : JSON.stringify(cfg.data)) : ''
                                        });
                                        const text = await res.text();
                                        let data = text;
                                        try { data = JSON.parse(text); } catch(_) {}
                                        return { data, status: res.status, headers: {}, config: cfg };
                                    };
                                    axiosFn.get = (url, cfg = {}) => axiosFn({ ...cfg, url, method: 'GET' });
                                    axiosFn.post = (url, data, cfg = {}) => axiosFn({ ...cfg, url, data, method: 'POST' });
                                    axiosFn.put = (url, data, cfg = {}) => axiosFn({ ...cfg, url, data, method: 'PUT' });
                                    axiosFn.delete = (url, cfg = {}) => axiosFn({ ...cfg, url, method: 'DELETE' });
                                    axiosFn.create = () => axiosFn;
                                    axiosFn.defaults = { headers: { common: {} } };
                                    return axiosFn;
                                }
                                if (name.includes('rot13')) {
                                    return (str) => String(str).replace(/[a-zA-Z]/g, c => {
                                        const base = c <= 'Z' ? 65 : 97;
                                        return String.fromCharCode(base + (c.charCodeAt(0) - base + 13) % 26);
                                    });
                                }
                                if (name.includes('cheerio')) {
                                    return {
                                        load: (html) => {
                                            const parser = new DOMParser();
                                            const doc = parser.parseFromString(html, 'text/html');
                                            const q = (sel) => {
                                                const nodes = Array.from(doc.querySelectorAll(sel));
                                                return {
                                                    each: (cb) => { nodes.forEach((n, i) => cb(i, n)); },
                                                    map: (cb) => ({ get: () => nodes.map((n, i) => cb(i, n)) }),
                                                    attr: (a) => nodes[0] ? nodes[0].getAttribute(a) : null,
                                                    text: () => nodes.map(n => n.textContent).join(' '),
                                                    html: () => nodes[0] ? nodes[0].innerHTML : '',
                                                    length: nodes.length
                                                };
                                            };
                                            return q;
                                        }
                                    };
                                }
                                // Generic fallback proxy
                                return new Proxy({}, {
                                    get: (_, prop) => () => ({})
                                });
                            };

                            const runnerFn = new Function('module', 'exports', 'global', 'window', 'require', 'params',
                                cleanedCode + '\n' +
                                'const primaryId = params.primaryId || "";\n' +
                                'const altId = params.altId || "";\n' +
                                'const tmdbId = params.tmdbId || "";\n' +
                                'const imdbId = params.imdbId || "";\n' +
                                'const mediaType = params.mediaType || params.type || "movie";\n' +
                                'const season = (params.season !== undefined && params.season !== null) ? Number(params.season) : 1;\n' +
                                'const episode = (params.episode !== undefined && params.episode !== null) ? Number(params.episode) : 1;\n' +
                                '\n' +
                                'let handler = null;\n' +
                                '// 1. Common named exports\n' +
                                'if (typeof getStreams === "function") handler = getStreams;\n' +
                                'else if (module.exports && typeof module.exports.getStreams === "function") handler = module.exports.getStreams;\n' +
                                'else if (module.exports && typeof module.exports === "function") handler = module.exports;\n' +
                                'else if (exports && typeof exports.getStreams === "function") handler = exports.getStreams;\n' +
                                'else if (exports && typeof exports.default === "function") handler = exports.default;\n' +
                                'else if (typeof window.getStreams === "function") handler = window.getStreams;\n' +
                                'else if (typeof global.getStreams === "function") handler = global.getStreams;\n' +
                                'else if (typeof getStream === "function") handler = getStream;\n' +
                                'else if (typeof getSources === "function") handler = getSources;\n' +
                                'else if (typeof extract === "function") handler = extract;\n' +
                                'else if (typeof streams === "function") handler = streams;\n' +
                                'else if (typeof stream === "function") handler = stream;\n' +
                                '\n' +
                                '// 2. Dynamic export inspection for any stream handler\n' +
                                'if (!handler && module.exports && typeof module.exports === "object") {\n' +
                                '    for (const k of Object.keys(module.exports)) {\n' +
                                '        if (typeof module.exports[k] === "function" && /stream/i.test(k)) {\n' +
                                '            handler = module.exports[k];\n' +
                                '            break;\n' +
                                '        }\n' +
                                '    }\n' +
                                '}\n' +
                                'if (!handler && typeof exports === "object") {\n' +
                                '    for (const k of Object.keys(exports)) {\n' +
                                '        if (typeof exports[k] === "function" && /stream/i.test(k)) {\n' +
                                '            handler = exports[k];\n' +
                                '            break;\n' +
                                '        }\n' +
                                '    }\n' +
                                '}\n' +
                                '\n' +
                                'if (!handler) { return []; }\n' +
                                '\n' +
                                'let result = null;\n' +
                                '// Execution Strategy 1: Nuvio Specification with Primary ID -> handler(id, type, season, episode)\n' +
                                'try {\n' +
                                '    const targetId = primaryId || tmdbId || imdbId;\n' +
                                '    result = await handler(targetId, mediaType, season, episode);\n' +
                                '} catch (e1) {\n' +
                                '    console.warn("Primary ID signature failed:", e1);\n' +
                                '}\n' +
                                '\n' +
                                '// Execution Strategy 2: Single params Object -> handler(params)\n' +
                                'if (!result || (Array.isArray(result) && result.length === 0)) {\n' +
                                '    try {\n' +
                                '        const objResult = await handler(params);\n' +
                                '        if (objResult && (Array.isArray(objResult) ? objResult.length > 0 : true)) {\n' +
                                '            result = objResult;\n' +
                                '        }\n' +
                                '    } catch (e2) {}\n' +
                                '}\n' +
                                '\n' +
                                '// Execution Strategy 3: Alternate ID Fallback -> handler(altId, type, season, episode)\n' +
                                'if ((!result || (Array.isArray(result) && result.length === 0)) && altId && altId !== primaryId) {\n' +
                                '    try {\n' +
                                '        const altResult = await handler(altId, mediaType, season, episode);\n' +
                                '        if (altResult && (Array.isArray(altResult) ? altResult.length > 0 : true)) {\n' +
                                '            result = altResult;\n' +
                                '        }\n' +
                                '    } catch (e3) {}\n' +
                                '}\n' +
                                '\n' +
                                '// Execution Strategy 4: Legacy positional -> handler(type, id, season, episode)\n' +
                                'if (!result || (Array.isArray(result) && result.length === 0)) {\n' +
                                '    try {\n' +
                                '        const legacyResult = await handler(mediaType, primaryId, season, episode);\n' +
                                '        if (legacyResult && (Array.isArray(legacyResult) ? legacyResult.length > 0 : true)) {\n' +
                                '            result = legacyResult;\n' +
                                '        }\n' +
                                '    } catch (e4) {}\n' +
                                '}\n' +
                                'return result || [];'
                            );

                            let result = await runnerFn(module, exports, global, window, requireShim, params);
                            if (!Array.isArray(result) && result && typeof result === 'object') {
                                result = result.streams || result.sources || [result];
                            }

                            const rawList = Array.isArray(result) ? result : [];
                            const finalStreams = rawList.map(s => {
                                if (!s || typeof s !== 'object') return null;
                                let streamUrl = s.url || s.file || s.streamUrl || s.link || s.stream || s.externalUrl || '';
                                let infoHash = s.infoHash || s.infohash || s.hash || null;
                                let fileIdx = (s.fileIdx !== undefined && s.fileIdx !== null) ? Number(s.fileIdx) : null;

                                if (!streamUrl && !infoHash) return null;

                                if (streamUrl && (streamUrl.includes("undefined") || streamUrl.endsWith("/null"))) {
                                    return null;
                                }

                                if (streamUrl && streamUrl.startsWith("magnet:")) {
                                    const match = streamUrl.match(/xt=urn:btih:([a-zA-Z0-9]+)/i);
                                    if (match && match[1]) {
                                        infoHash = match[1].toLowerCase();
                                    }
                                }

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

                                let extractedSubs = null;
                                if (Array.isArray(s.subtitles)) {
                                    extractedSubs = s.subtitles.map(sub => ({
                                        id: sub.id || sub.label || sub.lang || sub.language || "eng",
                                        url: sub.url || sub.file || "",
                                        language: sub.language || sub.lang || "eng",
                                        lang: sub.lang || sub.language || "eng"
                                    })).filter(sub => !!sub.url);
                                } else if (Array.isArray(s.subs)) {
                                    extractedSubs = s.subs.map(sub => ({
                                        id: sub.id || sub.label || "eng",
                                        url: sub.url || sub.file || "",
                                        language: sub.language || sub.lang || "eng",
                                        lang: sub.lang || sub.language || "eng"
                                    })).filter(sub => !!sub.url);
                                } else if (Array.isArray(s.tracks)) {
                                    extractedSubs = s.tracks.filter(t => t.kind === 'captions' || t.kind === 'subtitles').map(t => ({
                                        id: t.label || t.id || "eng",
                                        url: t.file || t.url || "",
                                        language: t.language || t.lang || "eng",
                                        lang: t.lang || t.language || "eng"
                                    })).filter(sub => !!sub.url);
                                }

                                return {
                                    name: s.name || s.label || s.server || s.provider || "[Nuvio] Source",
                                    title: s.title || s.name || s.description || s.quality || "Stream Source",
                                    url: streamUrl || null,
                                    infoHash: infoHash,
                                    fileIdx: fileIdx,
                                    quality: s.quality || (s.resolution ? s.resolution + 'p' : '1080p'),
                                    provider: s.provider || s.server || s.name || "Nuvio",
                                    size: s.size || null,
                                    format: s.format || null,
                                    subtitles: (extractedSubs && extractedSubs.length > 0) ? extractedSubs : null,
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
                <h1>Nuvio Scraper JS Runtime</h1>
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

                    var effectiveUrl = url

                    // Automatic TMDB API key parameter injection if accessing TMDB API directly
                    if (effectiveUrl.contains("api.themoviedb.org")) {
                        try {
                            val uri = Uri.parse(effectiveUrl)
                            val hasApiKey = uri.getQueryParameter("api_key") != null
                            if (!hasApiKey) {
                                val separator = if (effectiveUrl.contains("?")) "&" else "?"
                                effectiveUrl = "$effectiveUrl${separator}api_key=$DEFAULT_TMDB_KEY"
                            }
                        } catch (_: Exception) {}
                    }

                    val headersBuilder = okhttp3.Headers.Builder()
                    headersBuilder.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = headersObj.getString(key)
                            headersBuilder.set(key, value)
                        }
                    }

                    val requestBuilder = Request.Builder()
                        .url(effectiveUrl)
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
                        resHeadersObj.put(name.lowercase(Locale.ROOT), response.headers[name])
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
        timeoutMs: Long = 12000L
    ): List<RawPluginStream> {
        val requestId = "req_${UUID.randomUUID().toString().replace("-", "")}"
        val deferred = CompletableDeferred<List<RawPluginStream>>()
        pendingRequests[requestId] = deferred

        val idType = detectIdType(plugin)
        val primaryId = if (idType == "imdb") (imdbId ?: id) else (tmdbId ?: id)
        val altId = if (idType == "imdb") (tmdbId ?: id) else (imdbId ?: id)

        val paramsJson = JSONObject().apply {
            put("type", type)
            put("mediaType", if (type == "movie") "movie" else "tv")
            put("id", id)
            put("primaryId", primaryId)
            put("altId", altId)
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
        return result ?: emptyList()
    }
}
