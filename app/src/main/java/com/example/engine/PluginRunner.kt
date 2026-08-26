package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.PluginEntity
import com.example.data.model.RawPluginStream
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PluginRunner(private val context: Context) {

    private val TAG = "PluginRunner"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchExecutor = Executors.newFixedThreadPool(8)
    private val executionSemaphore = Semaphore(8)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val streamListAdapter = moshi.adapter<List<RawPluginStream>>(
        Types.newParameterizedType(List::class.java, RawPluginStream::class.java)
    )

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val inMemoryCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val list = cookieStore.getOrPut(host) { mutableListOf() }
            synchronized(list) {
                for (cookie in cookies) {
                    list.removeAll { it.name == cookie.name }
                    list.add(cookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val result = mutableListOf<Cookie>()
            cookieStore[host]?.let { synchronized(it) { result.addAll(it) } }
            val parts = host.split(".")
            if (parts.size > 2) {
                val parentDomain = parts.takeLast(2).joinToString(".")
                if (parentDomain != host) {
                    cookieStore[parentDomain]?.let { synchronized(it) { result.addAll(it) } }
                }
            }
            return result
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .dns(RobustDns)
        .cookieJar(inMemoryCookieJar)
        .connectionPool(ConnectionPool(32, 10, TimeUnit.MINUTES))
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private var webView: WebView? = null
    private var webViewReadyDeferred = CompletableDeferred<Unit>()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<List<RawPluginStream>>>()
    private val pendingPluginNames = ConcurrentHashMap<String, String>()

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

        fun extractTmdbApiKey(jsCode: String): String? {
            val pattern = Regex("[0-9a-f]{32}", RegexOption.IGNORE_CASE)
            for (match in pattern.findAll(jsCode)) {
                val candidate = match.value.lowercase(Locale.ROOT)
                if (candidate.toSet().size >= 8 && candidate != "00000000000000000000000000000000") {
                    return candidate
                }
            }
            return null
        }

        fun detectIdType(plugin: PluginEntity): String {
            val name = plugin.name.lowercase(Locale.ROOT)
            val id = plugin.id.lowercase(Locale.ROOT)
            val desc = plugin.description.lowercase(Locale.ROOT)
            val js = plugin.jsCode

            if (desc.contains("imdb only") || desc.contains("imdb id") || name.contains("imdb")) {
                return "imdb"
            }
            if (desc.contains("tmdb only") || desc.contains("tmdb id") || name.contains("tmdb")) {
                return "tmdb"
            }

            for (kw in TMDB_HOSTNAME_OVERRIDES) {
                if (name.contains(kw) || id.contains(kw)) return "tmdb"
            }
            for (kw in IMDB_HOSTNAME_OVERRIDES) {
                if (name.contains(kw) || id.contains(kw)) return "imdb"
            }

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
        if (webView != null) return
        try {
            AppLogger.info("JS-RUNTIME", "Initializing Runtime", "Spawning background Scraper WebView engine")
            val wv = WebView(context)
            val settings = wv.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkImage = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Scraper JS engine initialized successfully")
                    AppLogger.success("JS-RUNTIME", "Engine Ready", "Scraper JavaScript runtime and shims ready for execution")
                    if (!webViewReadyDeferred.isCompleted) {
                        webViewReadyDeferred.complete(Unit)
                    }
                }
            }

            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val msg = it.message()
                        val level = it.messageLevel()
                        if (level == ConsoleMessage.MessageLevel.ERROR) {
                            AppLogger.error("JS-Console", "Runtime Error", msg, "Line ${it.lineNumber()}")
                        } else if (level == ConsoleMessage.MessageLevel.WARNING) {
                            AppLogger.warn("JS-Console", "Warning", msg)
                        } else {
                            AppLogger.info("JS-Console", "Log", msg)
                        }
                    }
                    return true
                }
            }

            wv.addJavascriptInterface(NuvioNativeBridge(), "NuvioNative")

            val bootstrapHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                <script>
                    function makeHeaders(h) {
                        const normalized = {};
                        Object.keys(h || {}).forEach(k => {
                            normalized[k.toLowerCase()] = String(h[k]);
                        });
                        return {
                            get: (key) => normalized[key.toLowerCase()] || null,
                            has: (key) => key.toLowerCase() in normalized,
                            forEach: (cb) => { Object.keys(normalized).forEach(k => cb(normalized[k], k)); },
                            entries: function* () { for (let k in normalized) yield [k, normalized[k]]; },
                            keys: function* () { for (let k in normalized) yield k; },
                            values: function* () { for (let k in normalized) yield normalized[k]; },
                            raw: () => normalized
                        };
                    }

                    window._fetchCallbacks = {};

                    window.fetch = function(url, options) {
                        return new Promise((resolve, reject) => {
                            const reqId = 'fetch_' + Math.random().toString(36).substring(2) + '_' + Date.now();
                            window._fetchCallbacks[reqId] = {
                                resolve: resolve,
                                reject: reject,
                                url: String(url),
                                ts: Date.now()
                            };

                            let optsObj = {};
                            if (options && typeof options === 'object') {
                                optsObj.method = (options.method || 'GET').toUpperCase();
                                if (options.headers) {
                                    if (typeof options.headers.forEach === 'function') {
                                        optsObj.headers = {};
                                        options.headers.forEach((v, k) => { optsObj.headers[k] = v; });
                                    } else if (typeof options.headers === 'object') {
                                        optsObj.headers = options.headers;
                                    }
                                }
                                if (options.body) {
                                    optsObj.body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                                }
                            }

                            try {
                                NuvioNative.nativeFetch(reqId, String(url), JSON.stringify(optsObj));
                            } catch(e) {
                                delete window._fetchCallbacks[reqId];
                                reject(e);
                            }
                        });
                    };

                    window.onNativeFetchResponse = function(reqId, success, statusCode, body, headersJson) {
                        const cb = window._fetchCallbacks[reqId];
                        if (!cb) return;
                        delete window._fetchCallbacks[reqId];

                        if (!success) {
                            cb.reject(new Error(body || "Network fetch error"));
                            return;
                        }

                        let parsedHeaders = {};
                        try { parsedHeaders = JSON.parse(headersJson || '{}'); } catch(e) {}
                        const headers = makeHeaders(parsedHeaders);

                        const responseObj = {
                            ok: statusCode >= 200 && statusCode < 300,
                            status: statusCode,
                            statusText: statusCode === 200 ? "OK" : "Status " + statusCode,
                            headers: headers,
                            url: cb.url,
                            text: () => Promise.resolve(body),
                            json: () => {
                                try {
                                    return Promise.resolve(JSON.parse(body));
                                } catch(err) {
                                    return Promise.reject(err);
                                }
                            }
                        };

                        cb.resolve(responseObj);
                    };

                    // Global WordArray constructor
                    const WordArray = function(words, sigBytes) {
                        this.words = words || [];
                        this.sigBytes = sigBytes !== undefined ? sigBytes : this.words.length * 4;
                        this.toString = function(encoder) {
                            return (encoder || CryptoJSObj.enc.Hex).stringify(this);
                        };
                        this.clone = function() {
                            return new WordArray(this.words.slice(0), this.sigBytes);
                        };
                        this.concat = function(wordArray) {
                            const thisWords = this.words;
                            const thatWords = wordArray.words;
                            const thisSigBytes = this.sigBytes;
                            const thatSigBytes = wordArray.sigBytes;
                            this.clamp();
                            if (thisSigBytes % 4) {
                                for (let i = 0; i < thatSigBytes; i++) {
                                    const thatByte = (thatWords[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
                                    thisWords[(thisSigBytes + i) >>> 2] |= thatByte << (24 - ((thisSigBytes + i) % 4) * 8);
                                }
                            } else {
                                for (let i = 0; i < thatSigBytes; i += 4) {
                                    thisWords[(thisSigBytes + i) >>> 2] = thatWords[i >>> 2];
                                }
                            }
                            this.sigBytes += thatSigBytes;
                            return this;
                        };
                        this.clamp = function() {
                            const words = this.words;
                            const sigBytes = this.sigBytes;
                            words[sigBytes >>> 2] &= (0xffffffff << (32 - (sigBytes % 4) * 8));
                            words.length = Math.ceil(sigBytes / 4);
                        };
                    };

                    const CryptoJSObj = {
                        lib: {
                            WordArray: {
                                create: function(words, sigBytes) {
                                    return new WordArray(words, sigBytes);
                                },
                                random: function(nBytes) {
                                    const words = [];
                                    for (let i = 0; i < nBytes; i += 4) {
                                        words.push((Math.random() * 0x100000000) | 0);
                                    }
                                    return new WordArray(words, nBytes);
                                }
                            }
                        },
                        enc: {
                            Utf8: {
                                parse: function(str) {
                                    const bytes = new TextEncoder().encode(str);
                                    const words = [];
                                    for (let i = 0; i < bytes.length; i++) {
                                        words[i >>> 2] |= (bytes[i] & 0xff) << (24 - (i % 4) * 8);
                                    }
                                    return new WordArray(words, bytes.length);
                                },
                                stringify: function(wordArray) {
                                    const words = wordArray.words;
                                    const sigBytes = wordArray.sigBytes;
                                    const bytes = new Uint8Array(sigBytes);
                                    for (let i = 0; i < sigBytes; i++) {
                                        bytes[i] = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
                                    }
                                    return new TextDecoder().decode(bytes);
                                }
                            },
                            Hex: {
                                parse: function(hexStr) {
                                    const hexLength = hexStr.length;
                                    const words = [];
                                    for (let i = 0; i < hexLength; i += 2) {
                                        words[i >>> 3] |= parseInt(hexStr.substr(i, 2), 16) << (24 - (i % 8) * 4);
                                    }
                                    return new WordArray(words, hexLength / 2);
                                },
                                stringify: function(wordArray) {
                                    const words = wordArray.words;
                                    const sigBytes = wordArray.sigBytes;
                                    const hexChars = [];
                                    for (let i = 0; i < sigBytes; i++) {
                                        const bite = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
                                        hexChars.push((bite >>> 4).toString(16));
                                        hexChars.push((bite & 0x0f).toString(16));
                                    }
                                    return hexChars.join('');
                                }
                            },
                            Base64: {
                                parse: function(base64Str) {
                                    const bin = atob(base64Str);
                                    const words = [];
                                    for (let i = 0; i < bin.length; i++) {
                                        words[i >>> 2] |= (bin.charCodeAt(i) & 0xff) << (24 - (i % 4) * 8);
                                    }
                                    return new WordArray(words, bin.length);
                                },
                                stringify: function(wordArray) {
                                    const words = wordArray.words;
                                    const sigBytes = wordArray.sigBytes;
                                    let bin = '';
                                    for (let i = 0; i < sigBytes; i++) {
                                        bin += String.fromCharCode((words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff);
                                    }
                                    return btoa(bin);
                                }
                            }
                        },
                        mode: { CBC: {}, ECB: {}, CTR: {} },
                        pad: { Pkcs7: {}, NoPadding: {} },
                        AES: {
                            encrypt: function(message, key, cfg) {
                                return {
                                    toString: function() { return typeof message === 'string' ? message : ""; },
                                    ciphertext: message
                                };
                            },
                            decrypt: function(ciphertext, key, cfg) {
                                if (typeof ciphertext === 'string') {
                                    return CryptoJSObj.enc.Utf8.parse(ciphertext);
                                }
                                return ciphertext || CryptoJSObj.enc.Utf8.parse("");
                            }
                        },
                        MD5: function(str) {
                            return CryptoJSObj.enc.Utf8.parse(String(str));
                        },
                        SHA256: function(str) {
                            return CryptoJSObj.enc.Utf8.parse(String(str));
                        }
                    };

                    window.CryptoJS = CryptoJSObj;

                    // Axios shim
                    window.axios = {
                        get: async (url, config) => {
                            const res = await window.fetch(url, { method: 'GET', headers: config?.headers });
                            const data = await res.json().catch(() => res.text());
                            return { data, status: res.status, headers: res.headers.raw() };
                        },
                        post: async (url, data, config) => {
                            const res = await window.fetch(url, { method: 'POST', body: data, headers: config?.headers });
                            const respData = await res.json().catch(() => res.text());
                            return { data: respData, status: res.status, headers: res.headers.raw() };
                        }
                    };

                    // Cheerio mini parser
                    window.cheerio = {
                        load: function(html) {
                            const parser = new DOMParser();
                            const doc = parser.parseFromString(html || "", 'text/html');
                            const select = function(selector) {
                                const els = Array.from(doc.querySelectorAll(selector || "*"));
                                const wrapper = {
                                    length: els.length,
                                    each: function(cb) { els.forEach((el, i) => cb(i, selectElement(el))); return wrapper; },
                                    map: function(cb) { return els.map((el, i) => cb(i, selectElement(el))); },
                                    attr: function(name) { return els[0]?.getAttribute(name) || null; },
                                    text: function() { return els.map(e => e.textContent || '').join(' ').trim(); },
                                    html: function() { return els[0]?.innerHTML || ''; },
                                    find: function(s) {
                                        const subEls = [];
                                        els.forEach(e => subEls.push(...Array.from(e.querySelectorAll(s))));
                                        return selectElements(subEls);
                                    }
                                };
                                return wrapper;
                            };
                            function selectElement(el) {
                                return {
                                    attr: (name) => el?.getAttribute(name) || null,
                                    text: () => el?.textContent?.trim() || '',
                                    html: () => el?.innerHTML || '',
                                    find: (s) => selectElements(Array.from(el.querySelectorAll(s)))
                                };
                            }
                            function selectElements(els) {
                                return {
                                    length: els.length,
                                    each: (cb) => { els.forEach((e, i) => cb(i, selectElement(e))); },
                                    map: (cb) => els.map((e, i) => cb(i, selectElement(e))),
                                    attr: (name) => els[0]?.getAttribute(name) || null,
                                    text: () => els.map(e => e.textContent || '').join(' ').trim()
                                };
                            }
                            return select;
                        }
                    };

                    function getRequireShim() {
                        return function(name) {
                            if (name === 'crypto-js' || name === 'cryptojs') return window.CryptoJS;
                            if (name === 'cheerio') return window.cheerio;
                            if (name === 'axios') return window.axios;
                            if (name === 'node-fetch' || name === 'fetch') return window.fetch;
                            if (name === 'querystring' || name === 'qs') {
                                return {
                                    stringify: (obj) => new URLSearchParams(obj).toString(),
                                    parse: (str) => Object.fromEntries(new URLSearchParams(str))
                                };
                            }
                            return new Proxy({}, { get: () => () => ({}) });
                        };
                    }

                    window.executePlugin = async function(reqId, jsCode, paramsJson) {
                        try {
                            NuvioNative.logStep("EXEC", "Parse Parameters", "Received request params for " + reqId, "INFO", "");
                            const params = JSON.parse(paramsJson);
                            const primaryId = params.primaryId || params.tmdbId || params.imdbId || params.id || "";
                            const altId = params.altId || (primaryId === params.tmdbId ? params.imdbId : params.tmdbId) || "";
                            const mediaType = params.mediaType || params.type || "movie";
                            const season = (params.season !== undefined && params.season !== null) ? Number(params.season) : 1;
                            const episode = (params.episode !== undefined && params.episode !== null) ? Number(params.episode) : 1;
                            const title = params.title || "";
                            const year = params.year || "";

                            let module = { exports: {} };
                            let exports = module.exports;
                            let global = window;
                            window.module = module;
                            window.exports = exports;
                            window.global = window;
                            window.SCRAPER_SETTINGS = window.SCRAPER_SETTINGS || {};
                            const requireShim = getRequireShim();

                            let cleanedCode = jsCode;
                            if (cleanedCode.includes("export default")) {
                                cleanedCode = cleanedCode.replace(/export\s+default\s+/g, "module.exports = ");
                            }
                            if (/export\s+(async\s+function|function|const|let|var)\s+/.test(cleanedCode)) {
                                cleanedCode = cleanedCode.replace(/export\s+(async\s+function|function|const|let|var)\s+/g, "$1 ");
                            }

                            const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
                            const runnerFn = new AsyncFunction('module', 'exports', 'global', 'window', 'require', 'params', `
                                ${'$'}{cleanedCode}

                                const primaryId = params.primaryId || "";
                                const altId = params.altId || "";
                                const tmdbId = params.tmdbId || "";
                                const imdbId = params.imdbId || "";
                                const rawType = params.type || "movie";
                                const mediaType = (rawType === "movie") ? "movie" : (params.mediaType || "tv");
                                const isMovie = (mediaType === "movie" || rawType === "movie");
                                const season = (params.season !== undefined && params.season !== null) ? Number(params.season) : (isMovie ? null : 1);
                                const episode = (params.episode !== undefined && params.episode !== null) ? Number(params.episode) : (isMovie ? null : 1);
                                const title = params.title || "";
                                const year = params.year || "";

                                let handler = null;
                                if (typeof getStreams === "function") handler = getStreams;
                                else if (typeof scrape === "function") handler = scrape;
                                else if (module && module.exports && typeof module.exports.getStreams === "function") handler = module.exports.getStreams;
                                else if (module && module.exports && typeof module.exports.scrape === "function") handler = module.exports.scrape;
                                else if (module && typeof module.exports === "function") handler = module.exports;
                                else if (exports && typeof exports.getStreams === "function") handler = exports.getStreams;
                                else if (exports && typeof exports.scrape === "function") handler = exports.scrape;
                                else if (exports && typeof exports.default === "function") handler = exports.default;
                                else if (typeof window !== "undefined" && typeof window.getStreams === "function") handler = window.getStreams;
                                else if (typeof window !== "undefined" && typeof window.scrape === "function") handler = window.scrape;
                                else if (typeof getStream === "function") handler = getStream;
                                else if (typeof getSources === "function") handler = getSources;
                                else if (typeof extract === "function") handler = extract;
                                else if (typeof extractStreams === "function") handler = extractStreams;
                                else if (typeof streams === "function") handler = streams;

                                if (!handler && module && module.exports && typeof module.exports === "object") {
                                    for (const k of Object.keys(module.exports)) {
                                        if (typeof module.exports[k] === "function" && /stream|source|extract|scrape/i.test(k)) {
                                            handler = module.exports[k];
                                            break;
                                        }
                                    }
                                }

                                if (!handler) {
                                    NuvioNative.logStep("SCRAPER", "Handler Discovery", "No scraper entry point found (getStreams, scrape, module.exports)", "WARNING", "No entrypoint");
                                    return [];
                                }

                                function hasStreams(r) {
                                    if (!r) return false;
                                    if (Array.isArray(r)) return r.length > 0;
                                    if (typeof r === 'object') {
                                        const list = r.streams || r.sources || r.results || r.data || r.links || r.list || r.items;
                                        if (Array.isArray(list)) return list.length > 0;
                                        return Object.keys(r).length > 0;
                                    }
                                    return false;
                                }

                                let result = null;
                                const targetId = tmdbId || primaryId || imdbId;

                                if (isMovie) {
                                    // 1. 6-arg with Title & Year
                                    try {
                                        const r = await handler(targetId, "movie", 1, 1, title, year);
                                        if (hasStreams(r)) result = r;
                                    } catch(e) {
                                        NuvioNative.logStep("STRATEGY", "6-Arg Call", "Failed: " + e.message, "INFO", "");
                                    }

                                    // 2. 2-arg standard
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(targetId, "movie");
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // 3. Object params
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(params);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // 4. Alternate ID
                                    if (!hasStreams(result) && altId && altId !== targetId) {
                                        try {
                                            const r = await handler(altId, "movie", 1, 1, title, year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // 5. Title & Year
                                    if (!hasStreams(result) && title) {
                                        try {
                                            const r = await handler(title, "movie", year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }
                                } else {
                                    const s = season || 1;
                                    const ep = episode || 1;

                                    // 1. 6-arg with Title & Year
                                    try {
                                        const r = await handler(targetId, "tv", s, ep, title, year);
                                        if (hasStreams(r)) result = r;
                                    } catch(e) {
                                        NuvioNative.logStep("STRATEGY", "Series 6-Arg Call", "Failed: " + e.message, "INFO", "");
                                    }

                                    // 2. 4-arg
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(targetId, "tv", s, ep);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // 3. Object params
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(params);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // 4. By rawType e.g. "series"
                                    if (!hasStreams(result) && rawType !== "tv") {
                                        try {
                                            const r = await handler(targetId, rawType, s, ep, title, year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }
                                }

                                return result || [];
                            `);

                            let result = await runnerFn(module, exports, global, window, requireShim, params);
                            if (!Array.isArray(result) && result && typeof result === 'object') {
                                result = result.streams || result.sources || result.results || result.data || result.links || result.list || result.items || [result];
                            }

                            const rawList = Array.isArray(result) ? result : [];
                            const finalStreams = rawList.map(s => {
                                if (!s) return null;
                                if (typeof s === 'string') {
                                    const u = s.trim();
                                    if (!u || u.includes("undefined") || u.endsWith("/null")) return null;
                                    let hash = null;
                                    if (u.startsWith("magnet:")) {
                                        const m = u.match(/xt=urn:btih:([a-zA-Z0-9]+)/i);
                                        if (m && m[1]) hash = m[1].toLowerCase();
                                    }
                                    return {
                                        name: "[Nuvio] Stream Source",
                                        title: "1080p • Direct Playback",
                                        url: hash ? null : u,
                                        infoHash: hash,
                                        quality: "1080p",
                                        provider: "Nuvio",
                                        isDirect: true
                                    };
                                }
                                if (typeof s !== 'object') return null;

                                let streamUrl = s.url || s.file || s.streamUrl || s.link || s.stream || s.src || s.source || s.video || s.videoUrl || s.playbackUrl || s.directUrl || s.download || s.magnet || s.m3u8 || s.mp4 || s.uri || s.href || s.externalUrl || '';
                                let infoHash = s.infoHash || s.infohash || s.hash || s.torrentHash || s.btih || null;
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

                                const rawQuality = s.quality || s.resolution || s.res || (s.height ? s.height + 'p' : null) || s.label || '1080p';
                                const providerName = s.provider || s.server || s.name || s.source || s.host || s.hoster || "Nuvio";
                                const sizeStr = s.size || s.fileSize || s.formattedSize || null;

                                return {
                                    name: s.name || s.label || s.server || s.provider || ("[Nuvio] " + providerName),
                                    title: s.title || s.name || s.description || (rawQuality + (sizeStr ? " • " + sizeStr : "") + " • Direct Stream"),
                                    url: streamUrl || null,
                                    infoHash: infoHash,
                                    fileIdx: fileIdx,
                                    quality: rawQuality,
                                    provider: providerName,
                                    size: sizeStr,
                                    format: s.format || s.type || null,
                                    subtitles: s.subtitles || s.subs || null,
                                    headers: s.headers || null,
                                    isDirect: s.isDirect !== undefined ? s.isDirect : true
                                };
                            }).filter(s => s !== null);

                            NuvioNative.onPluginResult(reqId, JSON.stringify(finalStreams));
                        } catch(err) {
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
            AppLogger.error("JS-RUNTIME", "WebView Init Failed", e.message ?: "Failed to initialize WebView", e.message)
        }
    }

    private inner class NuvioNativeBridge {
        @JavascriptInterface
        fun logStep(category: String, step: String, details: String, status: String, error: String) {
            val level = when (status.uppercase(Locale.ROOT)) {
                "SUCCESS" -> StepLogLevel.SUCCESS
                "WARNING" -> StepLogLevel.WARNING
                "ERROR" -> StepLogLevel.ERROR
                else -> StepLogLevel.INFO
            }
            AppLogger.log(category, step, details, level, failureReason = if (error.isNotEmpty()) error else null)
        }

        @JavascriptInterface
        fun nativeFetch(requestId: String, url: String, optionsJson: String) {
            val fetchStart = System.currentTimeMillis()
            fetchExecutor.execute {
                try {
                    val opts = JSONObject(optionsJson)
                    val method = opts.optString("method", "GET").uppercase()
                    val headersObj = opts.optJSONObject("headers")
                    val bodyStr = opts.optString("body", "")

                    var effectiveUrl = url

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
                    headersBuilder.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    headersBuilder.add("Accept-Language", "en-US,en;q=0.9")

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
                    val statusCode = response.code
                    val responseBody = response.body?.string() ?: ""
                    val duration = System.currentTimeMillis() - fetchStart

                    if (statusCode in 200..299) {
                        AppLogger.success("NETWORK", "HTTP $method $statusCode", "URL: $effectiveUrl (${responseBody.length} bytes)", durationMs = duration)
                    } else {
                        AppLogger.warn("NETWORK", "HTTP $method $statusCode", "URL: $effectiveUrl (${responseBody.length} bytes)", "HTTP $statusCode")
                    }

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
                    val duration = System.currentTimeMillis() - fetchStart
                    AppLogger.error("NETWORK", "Fetch Failed", "URL: $url", reason = e.message ?: "Connection error")
                    mainHandler.post {
                        val escapedError = JSONObject.quote(e.message ?: "Fetch error")
                        val script = "window.onNativeFetchResponse('$requestId', false, 500, $escapedError, '{}');"
                        webView?.evaluateJavascript(script, null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onPluginResult(requestId: String, resultsJson: String) {
            val pluginName = pendingPluginNames[requestId] ?: "Plugin"
            try {
                val streams = streamListAdapter.fromJson(resultsJson) ?: emptyList()
                if (streams.isEmpty()) {
                    AppLogger.warn(pluginName, "Scraper Finished", "Returned 0 streams for request", "Scraper found no active links on sources")
                } else {
                    AppLogger.success(pluginName, "Streams Extracted", "Found ${streams.size} streams (Qualities: ${streams.mapNotNull { it.quality }.distinct().joinToString(", ")})")
                }
                pendingRequests[requestId]?.complete(streams)
            } catch (e: Exception) {
                AppLogger.error(pluginName, "JSON Parse Error", "Failed to parse scraper results", e.message)
                pendingRequests[requestId]?.complete(emptyList())
            } finally {
                pendingRequests.remove(requestId)
                pendingPluginNames.remove(requestId)
            }
        }

        @JavascriptInterface
        fun onPluginError(requestId: String, errorMsg: String) {
            val pluginName = pendingPluginNames[requestId] ?: "Plugin"
            AppLogger.error(pluginName, "Scraper Failed", "Execution error: $errorMsg", reason = errorMsg)
            pendingRequests[requestId]?.complete(emptyList())
            pendingRequests.remove(requestId)
            pendingPluginNames.remove(requestId)
        }

        @JavascriptInterface
        fun log(message: String) {
            AppLogger.info("JS-LOG", "Scraper Log", message)
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
        title: String? = null,
        year: String? = null,
        timeoutMs: Long = 0L
    ): List<RawPluginStream> = executionSemaphore.withPermit {
        val requestId = "req_${UUID.randomUUID().toString().replace("-", "")}"
        val deferred = CompletableDeferred<List<RawPluginStream>>()
        pendingRequests[requestId] = deferred
        pendingPluginNames[requestId] = plugin.name

        val idType = detectIdType(plugin)
        val primaryId = if (idType == "imdb") (imdbId ?: id) else (tmdbId ?: id)
        val altId = if (idType == "imdb") (tmdbId ?: id) else (imdbId ?: id)

        val paramsJson = JSONObject().apply {
            put("type", type)
            put("mediaType", if (type == "movie") "movie" else "tv")
            put("id", id)
            put("primaryId", primaryId)
            put("altId", altId)
            if (season != null) {
                put("season", season)
                put("s", season)
            }
            if (episode != null) {
                put("episode", episode)
                put("ep", episode)
            }
            if (tmdbId != null) put("tmdbId", tmdbId)
            if (imdbId != null) put("imdbId", imdbId)
            if (kitsuId != null) put("kitsuId", kitsuId)
            if (title != null) put("title", title)
            if (year != null) put("year", year)
        }.toString()

        AppLogger.info(
            plugin.name,
            "Starting Scraper Execution",
            "Target: [ID: $primaryId, Alt: $altId, Type: $type, S: ${season ?: 1}, Ep: ${episode ?: 1}, Title: '${title ?: ""}', Year: '${year ?: ""}']"
        )

        withContext(Dispatchers.Main) {
            if (webView == null) {
                initWebView()
            }
        }

        // Ensure WebView runtime is fully loaded before executing JS
        if (!webViewReadyDeferred.isCompleted) {
            withTimeoutOrNull(8000L) {
                webViewReadyDeferred.await()
            }
        }

        withContext(Dispatchers.Main) {
            val escapedCode = JSONObject.quote(plugin.jsCode)
            val escapedParams = JSONObject.quote(paramsJson)
            val jsCall = "if (window.executePlugin) { window.executePlugin('$requestId', $escapedCode, $escapedParams); } else { NuvioNative.onPluginError('$requestId', 'JS Runtime not initialized'); }"
            webView?.evaluateJavascript(jsCall, null)
        }

        // When timeoutMs <= 0, run until JS scraper completely finishes execution (with 5-min safeguard)
        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else 300_000L
        val result = withTimeoutOrNull(effectiveTimeout) {
            deferred.await()
        }

        if (result == null) {
            AppLogger.error(plugin.name, "Execution Timeout", "Scraper exceeded timeout of ${effectiveTimeout}ms", "Scraper took too long to complete")
        }

        pendingRequests.remove(requestId)
        pendingPluginNames.remove(requestId)
        return@withPermit result ?: emptyList()
    }

    fun trimMemory() {
        mainHandler.post {
            webView?.freeMemory()
        }
    }
}
