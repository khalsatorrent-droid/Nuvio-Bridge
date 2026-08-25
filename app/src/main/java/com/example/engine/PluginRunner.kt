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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PluginRunner(private val context: Context) {

    private val TAG = "PluginRunner"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchExecutor = Executors.newFixedThreadPool(16)
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
                    // Custom Headers shim with full Iterable & Map compatibility
                    function makeHeaders(headersMap) {
                        const h = headersMap || {};
                        const normalized = {};
                        Object.keys(h).forEach(k => {
                            normalized[k.toLowerCase()] = String(h[k]);
                        });
                        return {
                            get: (k) => normalized[String(k).toLowerCase()] || null,
                            has: (k) => normalized[String(k).toLowerCase()] !== undefined,
                            set: (k, v) => { normalized[String(k).toLowerCase()] = String(v); },
                            append: (k, v) => {
                                const lk = String(k).toLowerCase();
                                normalized[lk] = normalized[lk] ? (normalized[lk] + ', ' + v) : String(v);
                            },
                            delete: (k) => { delete normalized[String(k).toLowerCase()]; },
                            forEach: (cb) => { Object.keys(normalized).forEach(k => cb(normalized[k], k)); },
                            entries: function* () { for (let k in normalized) yield [k, normalized[k]]; },
                            keys: function* () { for (let k in normalized) yield k; },
                            values: function* () { for (let k in normalized) yield normalized[k]; },
                            [Symbol.iterator]: function* () { for (let k in normalized) yield [k, normalized[k]]; }
                        };
                    }

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
                                const headersObj = makeHeaders(headersMap);
                                resolve({
                                    ok: status >= 200 && status < 300,
                                    status: status,
                                    statusText: status === 200 ? 'OK' : 'Status ' + status,
                                    text: async () => body,
                                    json: async () => {
                                        try { return JSON.parse(body); } catch(e) { return {}; }
                                    },
                                    headers: headersObj
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

                    // Polyfill console to forward to native Logcat
                    const _origConsole = window.console || {};
                    window.console = {
                        ..._origConsole,
                        log: function(...args) {
                            try { NuvioNative.log("[LOG] " + args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' ')); } catch(_) {}
                            if (_origConsole.log) _origConsole.log(...args);
                        },
                        warn: function(...args) {
                            try { NuvioNative.log("[WARN] " + args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' ')); } catch(_) {}
                            if (_origConsole.warn) _origConsole.warn(...args);
                        },
                        error: function(...args) {
                            try { NuvioNative.log("[ERROR] " + args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' ')); } catch(_) {}
                            if (_origConsole.error) _origConsole.error(...args);
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
                                        let url = typeof cfg === 'string' ? cfg : (cfg && cfg.url ? cfg.url : '');
                                        const method = (typeof cfg === 'object' && cfg && cfg.method ? cfg.method : 'GET').toUpperCase();
                                        
                                        // Serialize query parameters if cfg.params is present
                                        if (typeof cfg === 'object' && cfg && cfg.params && typeof cfg.params === 'object') {
                                            const queryParams = new URLSearchParams();
                                            Object.entries(cfg.params).forEach(([k, v]) => {
                                                if (v !== undefined && v !== null) queryParams.append(k, String(v));
                                            });
                                            const qs = queryParams.toString();
                                            if (qs) {
                                                url += (url.includes('?') ? '&' : '?') + qs;
                                            }
                                        }

                                        const res = await window.fetch(url, {
                                            method: method,
                                            headers: (typeof cfg === 'object' && cfg ? cfg.headers : {}) || {},
                                            body: (typeof cfg === 'object' && cfg && cfg.data) ? (typeof cfg.data === 'string' ? cfg.data : JSON.stringify(cfg.data)) : ''
                                        });
                                        const text = await res.text();
                                        let data = text;
                                        try { data = JSON.parse(text); } catch(_) {}
                                        return { data, status: res.status, statusText: res.statusText, headers: res.headers, config: cfg };
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
                                if (name.includes('cheerio') || name.includes('jquery') || name === '$') {
                                    const wrapNodes = (nodes) => {
                                        const arr = Array.isArray(nodes) ? nodes : [nodes].filter(Boolean);
                                        const wrapper = {
                                            length: arr.length,
                                            first: () => wrapNodes(arr.slice(0, 1)),
                                            last: () => wrapNodes(arr.slice(-1)),
                                            eq: (idx) => wrapNodes(idx >= 0 ? arr.slice(idx, idx + 1) : arr.slice(idx, idx + 1 || undefined)),
                                            get: (idx) => idx !== undefined ? arr[idx] : arr,
                                            toArray: () => arr,
                                            find: (subSel) => {
                                                const found = [];
                                                arr.forEach(n => {
                                                    try {
                                                        if (n && n.querySelectorAll) {
                                                            found.push(...Array.from(n.querySelectorAll(subSel)));
                                                        }
                                                    } catch(_) {}
                                                });
                                                return wrapNodes(found);
                                            },
                                            children: (subSel) => {
                                                const kids = [];
                                                arr.forEach(n => {
                                                    if (n && n.children) {
                                                        Array.from(n.children).forEach(c => {
                                                            if (!subSel || c.matches(subSel)) kids.push(c);
                                                        });
                                                    }
                                                });
                                                return wrapNodes(kids);
                                            },
                                            parent: () => wrapNodes(arr.map(n => n.parentElement).filter(Boolean)),
                                            attr: (attrName, val) => {
                                                if (val !== undefined) {
                                                    arr.forEach(n => n.setAttribute && n.setAttribute(attrName, String(val)));
                                                    return wrapper;
                                                }
                                                return arr[0] && arr[0].getAttribute ? arr[0].getAttribute(attrName) : null;
                                            },
                                            prop: (propName) => arr[0] ? arr[0][propName] : undefined,
                                            val: (v) => {
                                                if (v !== undefined) {
                                                    arr.forEach(n => { if (n) n.value = v; });
                                                    return wrapper;
                                                }
                                                return arr[0] ? arr[0].value : undefined;
                                            },
                                            data: (k) => {
                                                const el = arr[0];
                                                if (!el) return undefined;
                                                return (el.dataset && el.dataset[k]) || el.getAttribute('data-' + k);
                                            },
                                            text: () => arr.map(n => n.textContent || '').join(' ').trim(),
                                            html: () => arr[0] ? arr[0].innerHTML : '',
                                            each: (cb) => { arr.forEach((n, i) => cb.call(wrapNodes(n), i, n)); return wrapper; },
                                            map: (cb) => ({ get: () => arr.map((n, i) => cb.call(wrapNodes(n), i, n)) }),
                                            filter: (fn) => typeof fn === 'string' ? wrapNodes(arr.filter(n => { try { return n.matches(fn); } catch(_) { return false; } })) : wrapNodes(arr.filter((n, i) => fn.call(n, i, n))),
                                            not: (fn) => typeof fn === 'string' ? wrapNodes(arr.filter(n => { try { return !n.matches(fn); } catch(_) { return true; } })) : wrapNodes(arr.filter((n, i) => !fn.call(n, i, n)))
                                        };
                                        // Index access wrapper[0], wrapper[1]
                                        arr.forEach((n, i) => { wrapper[i] = n; });
                                        return wrapper;
                                    };

                                    const cheerioObj = {
                                        load: (html) => {
                                            const parser = new DOMParser();
                                            const doc = parser.parseFromString(String(html || ''), 'text/html');
                                            const q = (sel) => {
                                                if (!sel) return wrapNodes([]);
                                                if (typeof sel === 'object') return wrapNodes(sel);
                                                const s = String(sel).trim();
                                                if (s.startsWith('<')) {
                                                    const tmp = doc.createElement('div');
                                                    tmp.innerHTML = s;
                                                    return wrapNodes(Array.from(tmp.children));
                                                }
                                                try {
                                                    return wrapNodes(Array.from(doc.querySelectorAll(s)));
                                                } catch(_) {
                                                    return wrapNodes([]);
                                                }
                                            };
                                            q.html = () => doc.body ? doc.body.innerHTML : '';
                                            q.text = () => doc.body ? doc.body.textContent : '';
                                            q.root = () => wrapNodes(doc.documentElement || doc.body);
                                            return q;
                                        }
                                    };
                                    return cheerioObj;
                                }
                                if (name.includes('crypto')) {
                                    return window.CryptoJS;
                                }
                                // Generic fallback proxy
                                return new Proxy({}, {
                                    get: (_, prop) => () => ({})
                                });
                            };

                            // Comprehensive CryptoJS shim
                            const WordArray = function(words, sigBytes) {
                                this.words = words || [];
                                this.sigBytes = sigBytes !== undefined ? sigBytes : this.words.length * 4;
                                this.toString = function(encoder) {
                                    return (encoder || window.CryptoJS.enc.Hex).stringify(this);
                                };
                            };

                            const CryptoJSObj = {
                                lib: {
                                    WordArray: {
                                        create: (words, sigBytes) => new WordArray(words, sigBytes)
                                    }
                                },
                                enc: {
                                    Hex: {
                                        stringify: (wordArray) => {
                                            const words = wordArray.words || [];
                                            const sigBytes = wordArray.sigBytes !== undefined ? wordArray.sigBytes : words.length * 4;
                                            let hexChars = [];
                                            for (let i = 0; i < sigBytes; i++) {
                                                const bite = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
                                                hexChars.push((bite >>> 4).toString(16));
                                                hexChars.push((bite & 0x0f).toString(16));
                                            }
                                            return hexChars.join('');
                                        },
                                        parse: (hexStr) => {
                                            const hex = String(hexStr || '').replace(/\s+/g, '');
                                            const words = [];
                                            for (let i = 0; i < hex.length; i += 2) {
                                                words[i >>> 3] |= parseInt(hex.substr(i, 2), 16) << (24 - (i % 8) * 4);
                                            }
                                            return new WordArray(words, Math.floor(hex.length / 2));
                                        }
                                    },
                                    Utf8: {
                                        stringify: (wordArray) => {
                                            try {
                                                const hex = window.CryptoJS.enc.Hex.stringify(wordArray);
                                                const bytes = [];
                                                for (let c = 0; c < hex.length; c += 2) bytes.push(parseInt(hex.substr(c, 2), 16));
                                                return new TextDecoder().decode(new Uint8Array(bytes));
                                            } catch(_) { return ''; }
                                        },
                                        parse: (utf8Str) => {
                                            const str = String(utf8Str || '');
                                            const encoded = new TextEncoder().encode(str);
                                            const words = [];
                                            for (let i = 0; i < encoded.length; i++) {
                                                words[i >>> 2] |= encoded[i] << (24 - (i % 4) * 8);
                                            }
                                            return new WordArray(words, encoded.length);
                                        }
                                    },
                                    Base64: {
                                        stringify: (wordArray) => {
                                            const hex = window.CryptoJS.enc.Hex.stringify(wordArray);
                                            let str = '';
                                            for (let i = 0; i < hex.length; i += 2) {
                                                str += String.fromCharCode(parseInt(hex.substr(i, 2), 16));
                                            }
                                            return btoa(str);
                                        },
                                        parse: (b64Str) => {
                                            try {
                                                const bin = atob(String(b64Str || ''));
                                                const words = [];
                                                for (let i = 0; i < bin.length; i++) {
                                                    words[i >>> 2] |= bin.charCodeAt(i) << (24 - (i % 4) * 8);
                                                }
                                                return new WordArray(words, bin.length);
                                            } catch(_) { return new WordArray([], 0); }
                                        }
                                    },
                                    Latin1: {
                                        stringify: (wordArray) => {
                                            const words = wordArray.words || [];
                                            const sigBytes = wordArray.sigBytes !== undefined ? wordArray.sigBytes : words.length * 4;
                                            let str = '';
                                            for (let i = 0; i < sigBytes; i++) {
                                                str += String.fromCharCode((words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff);
                                            }
                                            return str;
                                        },
                                        parse: (latin1Str) => {
                                            const str = String(latin1Str || '');
                                            const words = [];
                                            for (let i = 0; i < str.length; i++) {
                                                words[i >>> 2] |= (str.charCodeAt(i) & 0xff) << (24 - (i % 4) * 8);
                                            }
                                            return new WordArray(words, str.length);
                                        }
                                    }
                                },
                                mode: { CBC: {}, ECB: {}, CTR: {} },
                                pad: { Pkcs7: {}, NoPadding: {} },
                                AES: {
                                    encrypt: (msg, key, cfg) => ({ toString: () => '' }),
                                    decrypt: (cipher, key, cfg) => ({
                                        toString: (encoder) => {
                                            try {
                                                if (typeof cipher === 'string') {
                                                    return atob(cipher);
                                                }
                                                return "";
                                            } catch(_) { return ""; }
                                        }
                                    })
                                },
                                MD5: (str) => ({ toString: () => "" }),
                                SHA256: (str) => ({ toString: () => "" })
                            };

                            window.CryptoJS = CryptoJSObj;
                            globalThis.CryptoJS = CryptoJSObj;

                            window.axios = requireShim('axios');
                            globalThis.axios = window.axios;

                            const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
                            const runnerFn = new AsyncFunction('module', 'exports', 'global', 'window', 'require', 'params', `
                                ${'$'}{cleanedCode}

                                const primaryId = params.primaryId || "";
                                const altId = params.altId || "";
                                const tmdbId = params.tmdbId || "";
                                const imdbId = params.imdbId || "";
                                const mediaType = params.mediaType || params.type || "movie";
                                const season = (params.season !== undefined && params.season !== null) ? Number(params.season) : 1;
                                const episode = (params.episode !== undefined && params.episode !== null) ? Number(params.episode) : 1;

                                let handler = null;
                                // 1. Common named exports
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
                                else if (typeof global !== "undefined" && typeof global.getStreams === "function") handler = global.getStreams;
                                else if (typeof global !== "undefined" && typeof global.scrape === "function") handler = global.scrape;
                                else if (typeof getStream === "function") handler = getStream;
                                else if (typeof getSources === "function") handler = getSources;
                                else if (typeof extract === "function") handler = extract;
                                else if (typeof streams === "function") handler = streams;
                                else if (typeof stream === "function") handler = stream;

                                // 2. Dynamic export inspection
                                if (!handler && module && module.exports && typeof module.exports === "object") {
                                    for (const k of Object.keys(module.exports)) {
                                        if (typeof module.exports[k] === "function" && /stream|source|extract/i.test(k)) {
                                            handler = module.exports[k];
                                            break;
                                        }
                                    }
                                }
                                if (!handler && typeof exports === "object") {
                                    for (const k of Object.keys(exports)) {
                                        if (typeof exports[k] === "function" && /stream|source|extract/i.test(k)) {
                                            handler = exports[k];
                                            break;
                                        }
                                    }
                                }

                                if (!handler) {
                                    console.warn("[Runner] No stream handler function found in plugin code");
                                    return [];
                                }

                                let result = null;
                                // Execution Strategy 1: Standard Nuvio 2-argument call -> handler(id, type)
                                try {
                                    const rawId = primaryId || imdbId || tmdbId;
                                    const r0 = await handler(rawId, mediaType);
                                    if (r0 && (Array.isArray(r0) ? r0.length > 0 : (typeof r0 === 'object' && Object.keys(r0).length > 0))) {
                                        result = r0;
                                    }
                                } catch (e0) {}

                                // Execution Strategy 2: Nuvio 4-parameter specification -> handler(targetId, mediaType, season, episode)
                                if (!result || (Array.isArray(result) && result.length === 0)) {
                                    try {
                                        const targetId = tmdbId || primaryId || imdbId;
                                        const s = (mediaType === "movie") ? null : season;
                                        const ep = (mediaType === "movie") ? null : episode;
                                        const r1 = await handler(targetId, mediaType, s, ep);
                                        if (r1 && (Array.isArray(r1) ? r1.length > 0 : true)) {
                                            result = r1;
                                        }
                                    } catch (e1) {
                                        console.warn("[Runner] Strategy handler(id, type, s, ep) failed: " + e1.message);
                                    }
                                }

                                // Execution Strategy 2: Single params Object -> handler(params)
                                if (!result || (Array.isArray(result) && result.length === 0)) {
                                    try {
                                        const r2 = await handler(params);
                                        if (r2 && (Array.isArray(r2) ? r2.length > 0 : true)) {
                                            result = r2;
                                        }
                                    } catch (e2) {
                                        console.warn("[Runner] Strategy 2 handler(params) failed: " + e2.message);
                                    }
                                }

                                // Execution Strategy 3: Alternate ID -> handler(altId, mediaType, season, episode)
                                if ((!result || (Array.isArray(result) && result.length === 0)) && altId && altId !== primaryId) {
                                    try {
                                        const s = (mediaType === "movie") ? null : season;
                                        const ep = (mediaType === "movie") ? null : episode;
                                        const r3 = await handler(altId, mediaType, s, ep);
                                        if (r3 && (Array.isArray(r3) ? r3.length > 0 : true)) {
                                            result = r3;
                                        }
                                    } catch (e3) {
                                        console.warn("[Runner] Strategy 3 handler(altId, type, s, ep) failed: " + e3.message);
                                    }
                                }

                                // Execution Strategy 4: Numeric s/ep -> handler(primaryId, mediaType, season, episode)
                                if (!result || (Array.isArray(result) && result.length === 0)) {
                                    try {
                                        const r4 = await handler(primaryId || tmdbId || imdbId, mediaType, season || 1, episode || 1);
                                        if (r4 && (Array.isArray(r4) ? r4.length > 0 : true)) {
                                            result = r4;
                                        }
                                    } catch (e4) {}
                                }

                                // Execution Strategy 5: Positional mediaType first -> handler(mediaType, primaryId, season, episode)
                                if (!result || (Array.isArray(result) && result.length === 0)) {
                                    try {
                                        const r5 = await handler(mediaType, primaryId || tmdbId || imdbId, season || 1, episode || 1);
                                        if (r5 && (Array.isArray(r5) ? r5.length > 0 : true)) {
                                            result = r5;
                                        }
                                    } catch (e5) {}
                                }

                                return result || [];
                            `);

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
            fetchExecutor.execute {
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
                    val statusCode = response.code
                    val responseBody = response.body?.use { body ->
                        val source = body.source()
                        val maxBytes = 4L * 1024L * 1024L // 4MB safe buffer limit
                        source.request(maxBytes)
                        val buffer = source.buffer
                        val byteCount = minOf(buffer.size, maxBytes)
                        buffer.clone().readString(byteCount, Charsets.UTF_8)
                    } ?: ""

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
            }
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
        title: String? = null,
        year: String? = null,
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
            if (title != null) put("title", title)
            if (year != null) put("year", year)
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
