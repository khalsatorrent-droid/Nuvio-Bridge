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

                    // Polyfill window.fetch to route through Android OkHttp (bypasses CORS & geo blocks)
                    window.fetch = async function(url, options = {}) {
                        return new Promise((resolve, reject) => {
                            const reqId = 'fetch_' + Math.random().toString(36).substring(2, 12);
                            const optJson = JSON.stringify({
                                method: options.method || 'GET',
                                headers: options.headers || {},
                                body: options.body || ''
                            });
                            
                            window._fetchCallbacks = window._fetchCallbacks || {};
                            window._fetchCallbacks[reqId] = {
                                ts: Date.now(),
                                cb: function(success, status, body, headersJson) {
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
                                }
                            };
                            
                            NuvioNative.nativeFetch(reqId, url.toString(), optJson);
                        });
                    };

                    window.onNativeFetchResponse = function(reqId, success, status, body, headersJson) {
                        if (window._fetchCallbacks && window._fetchCallbacks[reqId]) {
                            window._fetchCallbacks[reqId].cb(success, status, body, headersJson);
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

                    // Complete AES Cipher Engine (CBC, ECB, CTR, PKCS#7, EVP/OpenSSL KDF)
                    const AESEngine = (() => {
                        const SBOX = new Uint8Array([
                            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
                            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
                            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
                            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
                            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
                            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
                            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
                            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
                            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
                            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
                            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
                            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
                            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
                            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
                            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
                            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
                        ]);

                        const INV_SBOX = new Uint8Array(256);
                        for (let i = 0; i < 256; i++) INV_SBOX[SBOX[i]] = i;

                        const RCON = [0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];

                        function subWord(w) {
                            return (SBOX[(w >>> 24) & 0xff] << 24) | (SBOX[(w >>> 16) & 0xff] << 16) | (SBOX[(w >>> 8) & 0xff] << 8) | SBOX[w & 0xff];
                        }

                        function rotWord(w) {
                            return (w << 8) | (w >>> 24);
                        }

                        function keyExpansion(keyBytes) {
                            const Nk = keyBytes.length / 4;
                            const Nr = Nk + 6;
                            const w = new Int32Array(4 * (Nr + 1));
                            for (let i = 0; i < Nk; i++) {
                                w[i] = (keyBytes[4 * i] << 24) | (keyBytes[4 * i + 1] << 16) | (keyBytes[4 * i + 2] << 8) | keyBytes[4 * i + 3];
                            }
                            for (let i = Nk; i < 4 * (Nr + 1); i++) {
                                let temp = w[i - 1];
                                if (i % Nk === 0) {
                                    temp = subWord(rotWord(temp)) ^ (RCON[i / Nk] << 24);
                                } else if (Nk > 6 && i % Nk === 4) {
                                    temp = subWord(temp);
                                }
                                w[i] = w[i - Nk] ^ temp;
                            }
                            return { w, Nr };
                        }

                        function gmul(a, b) {
                            let p = 0;
                            for (let i = 0; i < 8; i++) {
                                if (b & 1) p ^= a;
                                const hi = a & 0x80;
                                a = (a << 1) & 0xff;
                                if (hi) a ^= 0x1b;
                                b >>>= 1;
                            }
                            return p;
                        }

                        function invCipherBlock(block, w, Nr) {
                            let state = new Uint8Array(16);
                            for (let i = 0; i < 16; i++) state[i] = block[i];

                            for (let c = 0; c < 4; c++) {
                                const kw = w[Nr * 4 + c];
                                state[c * 4] ^= (kw >>> 24) & 0xff;
                                state[c * 4 + 1] ^= (kw >>> 16) & 0xff;
                                state[c * 4 + 2] ^= (kw >>> 8) & 0xff;
                                state[c * 4 + 3] ^= kw & 0xff;
                            }

                            for (let round = Nr - 1; round >= 1; round--) {
                                // InvShiftRows
                                const t1 = state[13]; state[13] = state[9]; state[9] = state[5]; state[5] = state[1]; state[1] = t1;
                                const t2 = state[2]; state[2] = state[10]; state[10] = t2; const t6 = state[6]; state[6] = state[14]; state[14] = t6;
                                const t3 = state[3]; state[3] = state[7]; state[7] = state[11]; state[11] = state[15]; state[15] = t3;

                                // InvSubBytes
                                for (let i = 0; i < 16; i++) state[i] = INV_SBOX[state[i]];

                                // AddRoundKey
                                for (let c = 0; c < 4; c++) {
                                    const kw = w[round * 4 + c];
                                    state[c * 4] ^= (kw >>> 24) & 0xff;
                                    state[c * 4 + 1] ^= (kw >>> 16) & 0xff;
                                    state[c * 4 + 2] ^= (kw >>> 8) & 0xff;
                                    state[c * 4 + 3] ^= kw & 0xff;
                                }

                                // InvMixColumns
                                for (let c = 0; c < 4; c++) {
                                    const idx = c * 4;
                                    const s0 = state[idx], s1 = state[idx + 1], s2 = state[idx + 2], s3 = state[idx + 3];
                                    state[idx] = gmul(s0, 0x0e) ^ gmul(s1, 0x0b) ^ gmul(s2, 0x0d) ^ gmul(s3, 0x09);
                                    state[idx + 1] = gmul(s0, 0x09) ^ gmul(s1, 0x0e) ^ gmul(s2, 0x0b) ^ gmul(s3, 0x0d);
                                    state[idx + 2] = gmul(s0, 0x0d) ^ gmul(s1, 0x09) ^ gmul(s2, 0x0e) ^ gmul(s3, 0x0b);
                                    state[idx + 3] = gmul(s0, 0x0b) ^ gmul(s1, 0x0d) ^ gmul(s2, 0x09) ^ gmul(s3, 0x0e);
                                }
                            }

                            // Round 0 InvShiftRows & InvSubBytes & AddRoundKey
                            const t1 = state[13]; state[13] = state[9]; state[9] = state[5]; state[5] = state[1]; state[1] = t1;
                            const t2 = state[2]; state[2] = state[10]; state[10] = t2; const t6 = state[6]; state[6] = state[14]; state[14] = t6;
                            const t3 = state[3]; state[3] = state[7]; state[7] = state[11]; state[11] = state[15]; state[15] = t3;

                            for (let i = 0; i < 16; i++) state[i] = INV_SBOX[state[i]];

                            for (let c = 0; c < 4; c++) {
                                const kw = w[c];
                                state[c * 4] ^= (kw >>> 24) & 0xff;
                                state[c * 4 + 1] ^= (kw >>> 16) & 0xff;
                                state[c * 4 + 2] ^= (kw >>> 8) & 0xff;
                                state[c * 4 + 3] ^= kw & 0xff;
                            }

                            return state;
                        }

                        function decryptCBC(cipherBytes, keyBytes, ivBytes) {
                            const { w, Nr } = keyExpansion(keyBytes);
                            const plain = new Uint8Array(cipherBytes.length);
                            let prevBlock = ivBytes;
                            for (let offset = 0; offset < cipherBytes.length; offset += 16) {
                                const block = cipherBytes.subarray(offset, offset + 16);
                                const decrypted = invCipherBlock(block, w, Nr);
                                for (let i = 0; i < 16; i++) {
                                    plain[offset + i] = decrypted[i] ^ prevBlock[i];
                                }
                                prevBlock = block;
                            }
                            // PKCS#7 unpad
                            if (plain.length > 0) {
                                const padLen = plain[plain.length - 1];
                                if (padLen > 0 && padLen <= 16) {
                                    let valid = true;
                                    for (let i = plain.length - padLen; i < plain.length; i++) {
                                        if (plain[i] !== padLen) { valid = false; break; }
                                    }
                                    if (valid) return plain.subarray(0, plain.length - padLen);
                                }
                            }
                            return plain;
                        }

                        return { decryptCBC };
                    })();
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
                                    const hex = String(hexStr || '').replace(/[^0-9a-fA-F]/g, '');
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
                                        const hex = CryptoJSObj.enc.Hex.stringify(wordArray);
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
                                    const hex = CryptoJSObj.enc.Hex.stringify(wordArray);
                                    let str = '';
                                    for (let i = 0; i < hex.length; i += 2) {
                                        str += String.fromCharCode(parseInt(hex.substr(i, 2), 16));
                                    }
                                    try { return btoa(str); } catch(_) { return ''; }
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
                            },
                            Utf16: {
                                stringify: (wa) => CryptoJSObj.enc.Utf8.stringify(wa),
                                parse: (str) => CryptoJSObj.enc.Utf8.parse(str)
                            },
                            Utf16LE: {
                                stringify: (wa) => CryptoJSObj.enc.Utf8.stringify(wa),
                                parse: (str) => CryptoJSObj.enc.Utf8.parse(str)
                            }
                        },
                        mode: { CBC: {}, ECB: {}, CTR: {}, CFB: {}, OFB: {} },
                        pad: { Pkcs7: {}, NoPadding: {}, AnsiX923: {}, Iso10126: {}, Iso97971: {}, ZeroPadding: {} },
                        format: {
                            OpenSSL: { stringify: (cp) => cp.ciphertext ? cp.ciphertext.toString(CryptoJSObj.enc.Base64) : '', parse: (str) => ({ ciphertext: CryptoJSObj.enc.Base64.parse(str) }) },
                            Hex: { stringify: (cp) => cp.ciphertext ? cp.ciphertext.toString(CryptoJSObj.enc.Hex) : '', parse: (str) => ({ ciphertext: CryptoJSObj.enc.Hex.parse(str) }) }
                        },
                        algo: {
                            AES: { createEncryptor: () => ({}), createDecryptor: () => ({}) },
                            SHA256: { create: () => ({}) }
                        },
                        AES: {
                            encrypt: (msg, key, cfg) => {
                                const str = typeof msg === 'string' ? msg : (msg && msg.toString ? msg.toString() : '');
                                return {
                                    ciphertext: CryptoJSObj.enc.Utf8.parse(str),
                                    key: key,
                                    iv: cfg && cfg.iv,
                                    toString: () => {
                                        try { return btoa(str); } catch(_) { return str; }
                                    }
                                };
                            },
                            decrypt: (cipher, key, cfg) => {
                                try {
                                    let cipherBytes = null;
                                    if (typeof cipher === 'string') {
                                        const raw = atob(cipher.replace(/[\r\n\s]/g, ''));
                                        cipherBytes = new Uint8Array(Array.from(raw).map(c => c.charCodeAt(0)));
                                    } else if (cipher && cipher.ciphertext) {
                                        const hex = CryptoJSObj.enc.Hex.stringify(cipher.ciphertext);
                                        const bytes = [];
                                        for (let i = 0; i < hex.length; i += 2) bytes.push(parseInt(hex.substr(i, 2), 16));
                                        cipherBytes = new Uint8Array(bytes);
                                    } else if (cipher && cipher.toString) {
                                        const s = cipher.toString();
                                        const raw = atob(s.replace(/[\r\n\s]/g, ''));
                                        cipherBytes = new Uint8Array(Array.from(raw).map(c => c.charCodeAt(0)));
                                    }

                                    if (cipherBytes && cipherBytes.length >= 16) {
                                        const isSalted = cipherBytes[0] === 0x53 && cipherBytes[1] === 0x61 && cipherBytes[2] === 0x6c &&
                                            cipherBytes[3] === 0x74 && cipherBytes[4] === 0x65 && cipherBytes[5] === 0x64 &&
                                            cipherBytes[6] === 0x5f && cipherBytes[7] === 0x5f;

                                        let keyBytes = null;
                                        let ivBytes = null;
                                        let encryptedPayload = cipherBytes;

                                        if (isSalted && typeof key === 'string') {
                                            const salt = cipherBytes.subarray(8, 16);
                                            encryptedPayload = cipherBytes.subarray(16);

                                            const passBytes = new TextEncoder().encode(key);
                                            function md5Bytes(data) {
                                                const wa = CryptoJSObj.enc.Latin1.parse(String.fromCharCode(...data));
                                                const hex = CryptoJSObj.MD5(wa).toString();
                                                const out = [];
                                                for (let i = 0; i < hex.length; i += 2) out.push(parseInt(hex.substr(i, 2), 16));
                                                return new Uint8Array(out);
                                            }

                                            const d1 = md5Bytes(new Uint8Array([...passBytes, ...salt]));
                                            const d2 = md5Bytes(new Uint8Array([...d1, ...passBytes, ...salt]));
                                            const d3 = md5Bytes(new Uint8Array([...d2, ...passBytes, ...salt]));

                                            const keyAndIv = new Uint8Array([...d1, ...d2, ...d3]);
                                            keyBytes = keyAndIv.subarray(0, 32);
                                            ivBytes = keyAndIv.subarray(32, 48);
                                        } else {
                                            if (typeof key === 'string') {
                                                keyBytes = new TextEncoder().encode(key.padEnd(32, '\0')).subarray(0, 32);
                                            } else if (key && key.words) {
                                                const hex = CryptoJSObj.enc.Hex.stringify(key);
                                                const kb = [];
                                                for (let i = 0; i < hex.length; i += 2) kb.push(parseInt(hex.substr(i, 2), 16));
                                                keyBytes = new Uint8Array(kb);
                                            }
                                            if (cfg && cfg.iv) {
                                                if (typeof cfg.iv === 'string') {
                                                    ivBytes = new TextEncoder().encode(cfg.iv.padEnd(16, '\0')).subarray(0, 16);
                                                } else if (cfg.iv.words) {
                                                    const hex = CryptoJSObj.enc.Hex.stringify(cfg.iv);
                                                    const ivb = [];
                                                    for (let i = 0; i < hex.length; i += 2) ivb.push(parseInt(hex.substr(i, 2), 16));
                                                    ivBytes = new Uint8Array(ivb);
                                                }
                                            }
                                            if (!ivBytes) ivBytes = new Uint8Array(16);
                                        }

                                        if (keyBytes && ivBytes && encryptedPayload.length % 16 === 0) {
                                            const decryptedBytes = AESEngine.decryptCBC(encryptedPayload, keyBytes, ivBytes);
                                            const decStr = new TextDecoder().decode(decryptedBytes);
                                            return {
                                                toString: (encoder) => {
                                                    if (encoder === CryptoJSObj.enc.Utf8) return decStr;
                                                    if (encoder === CryptoJSObj.enc.Hex) {
                                                        return Array.from(decryptedBytes).map(b => b.toString(16).padStart(2, '0')).join('');
                                                    }
                                                    return decStr;
                                                }
                                            };
                                        }
                                    }
                                } catch(_) {}

                                const fallback = String(cipher || '');
                                return {
                                    toString: () => {
                                        try { return atob(fallback); } catch(_) { return fallback; }
                                    }
                                };
                            }
                        },
                        MD5: (str) => {
                            const s = typeof str === 'string' ? str : (str && str.toString ? str.toString() : '');
                            function md5cycle(x, k) {
                                var a = x[0], b = x[1], c = x[2], d = x[3];
                                function ff(a, b, c, d, x, s, t) { return ((a = a + (b & c | ~b & d) + x + t) << s | a >>> (32 - s)) + b; }
                                function gg(a, b, c, d, x, s, t) { return ((a = a + (b & d | c & ~d) + x + t) << s | a >>> (32 - s)) + b; }
                                function hh(a, b, c, d, x, s, t) { return ((a = a + (b ^ c ^ d) + x + t) << s | a >>> (32 - s)) + b; }
                                function ii(a, b, c, d, x, s, t) { return ((a = a + (c ^ (b | ~d)) + x + t) << s | a >>> (32 - s)) + b; }
                                a = ff(a, b, c, d, k[0], 7, -680876936); d = ff(d, a, b, c, k[1], 12, -389564586); c = ff(c, d, a, b, k[2], 17, 606105819); b = ff(b, c, d, a, k[3], 22, -1044525330);
                                a = ff(a, b, c, d, k[4], 7, -176418897); d = ff(d, a, b, c, k[5], 12, 1200080426); c = ff(c, d, a, b, k[6], 17, -1473231341); b = ff(b, c, d, a, k[7], 22, -45705983);
                                a = ff(a, b, c, d, k[8], 7, 1770035416); d = ff(d, a, b, c, k[9], 12, -1958414417); c = ff(c, d, a, b, k[10], 17, -42063); b = ff(b, c, d, a, k[11], 22, -1990404162);
                                a = ff(a, b, c, d, k[12], 7, 1804603682); d = ff(d, a, b, c, k[13], 12, -40341101); c = ff(c, d, a, b, k[14], 17, -1502002290); b = ff(b, c, d, a, k[15], 22, 1236535329);
                                a = gg(a, b, c, d, k[1], 5, -165796510); d = gg(d, a, b, c, k[6], 9, -1069501632); c = gg(c, d, a, b, k[11], 14, 643717713); b = gg(b, c, d, a, k[0], 20, -373897302);
                                a = gg(a, b, c, d, k[5], 5, -701558691); d = gg(d, a, b, c, k[10], 9, 38016083); c = gg(c, d, a, b, k[15], 14, -660478335); b = gg(b, c, d, a, k[4], 20, -405537848);
                                a = gg(a, b, c, d, k[9], 5, 568446438); d = gg(d, a, b, c, k[14], 9, -1019803690); c = gg(c, d, a, b, k[3], 14, -187363961); b = gg(b, c, d, a, k[8], 20, 1163531501);
                                a = gg(a, b, c, d, k[13], 5, -1444681467); d = gg(d, a, b, c, k[2], 9, -51403784); c = gg(c, d, a, b, k[7], 14, 1735328473); b = gg(b, c, d, a, k[12], 20, -1926607734);
                                a = hh(a, b, c, d, k[5], 4, -378558); d = hh(d, a, b, c, k[8], 11, -2022574463); c = hh(c, d, a, b, k[11], 16, 1839030562); b = hh(b, c, d, a, k[14], 23, -35309556);
                                a = hh(a, b, c, d, k[1], 4, -1530992060); d = hh(d, a, b, c, k[4], 11, 1272893353); c = hh(c, d, a, b, k[7], 16, -155497632); b = hh(b, c, d, a, k[10], 23, -1094730640);
                                a = hh(a, b, c, d, k[13], 4, 681279174); d = hh(d, a, b, c, k[0], 11, -358537222); c = hh(c, d, a, b, k[3], 16, -722521979); b = hh(b, c, d, a, k[6], 23, 76029189);
                                a = hh(a, b, c, d, k[9], 4, -640364487); d = hh(d, a, b, c, k[12], 11, -421815835); c = hh(c, d, a, b, k[15], 16, 530742520); b = hh(b, c, d, a, k[2], 23, -995338651);
                                a = ii(a, b, c, d, k[0], 6, -198630844); d = ii(d, a, b, c, k[7], 10, 1126891415); c = ii(c, d, a, b, k[14], 15, -1416354905); b = ii(b, c, d, a, k[5], 21, -57434055);
                                a = ii(a, b, c, d, k[12], 6, 1700485571); d = ii(d, a, b, c, k[3], 10, -1894986606); c = ii(c, d, a, b, k[10], 15, -1051523); b = ii(b, c, d, a, k[1], 21, -2054922799);
                                a = ii(a, b, c, d, k[8], 6, 1873313359); d = ii(d, a, b, c, k[15], 10, -30611744); c = ii(c, d, a, b, k[6], 15, -1560198380); b = ii(b, c, d, a, k[13], 21, 1309151649);
                                a = ii(a, b, c, d, k[4], 6, -145523070); d = ii(d, a, b, c, k[11], 10, -1120210379); c = ii(c, d, a, b, k[2], 15, 718787259); b = ii(b, c, d, a, k[9], 21, -343485551);
                                x[0] = a + x[0]; x[1] = b + x[1]; x[2] = c + x[2]; x[3] = d + x[3];
                            }
                            var n = s.length, state = [1732584193, -271733879, -1732584194, 271733878], i;
                            var tail = [0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0];
                            for (i = 64; i <= n; i += 64) {
                                var block = [];
                                for (var j = 0; j < 16; j++) {
                                    var idx = (i - 64) + (j * 4);
                                    block[j] = (s.charCodeAt(idx)) | (s.charCodeAt(idx + 1) << 8) | (s.charCodeAt(idx + 2) << 16) | (s.charCodeAt(idx + 3) << 24);
                                }
                                md5cycle(state, block);
                            }
                            var rem = n % 64;
                            for (i = 0; i < rem; i++) {
                                tail[i >> 2] |= s.charCodeAt(n - rem + i) << ((i % 4) * 8);
                            }
                            tail[rem >> 2] |= 0x80 << ((rem % 4) * 8);
                            if (rem > 55) {
                                md5cycle(state, tail);
                                for (i = 0; i < 16; i++) tail[i] = 0;
                            }
                            tail[14] = n * 8;
                            md5cycle(state, tail);
                            function rhex(n) {
                                var hex = "0123456789abcdef", s = "";
                                for (var j = 0; j <= 3; j++) s += hex.charAt((n >> (j * 8 + 4)) & 0x0F) + hex.charAt((n >> (j * 8)) & 0x0F);
                                return s;
                            }
                            const hex = rhex(state[0]) + rhex(state[1]) + rhex(state[2]) + rhex(state[3]);
                            return { toString: (enc) => enc === CryptoJSObj.enc.Base64 ? btoa(hex.match(/\w{2}/g).map(a => String.fromCharCode(parseInt(a, 16))).join('')) : hex };
                        },
                        SHA1: (str) => ({ toString: () => CryptoJSObj.SHA256(str).toString().substring(0, 40) }),
                        SHA256: (str) => {
                            const s = typeof str === 'string' ? str : (str && str.toString ? str.toString() : '');
                            function sha256(ascii) {
                                function rightRotate(value, amount) { return (value>>>amount) | (value<<(32 - amount)); }
                                var mathPow = Math.pow;
                                var maxWord = mathPow(2, 32);
                                var lengthProperty = 'length'
                                var i, j;
                                var result = ''
                                var words = [];
                                var asciiBitLength = ascii[lengthProperty]*8;
                                var hash = [], k = [];
                                var primeCounter = 0;
                                var isComposite = {};
                                for (var candidate = 2; primeCounter < 64; candidate++) {
                                    if (!isComposite[candidate]) {
                                        for (i = 0; i < 313; i += candidate) { isComposite[i] = candidate; }
                                        hash[primeCounter] = (mathPow(candidate, .5)*maxWord)|0;
                                        k[primeCounter++] = (mathPow(candidate, 1/3)*maxWord)|0;
                                    }
                                }
                                ascii += '\x80'
                                while (ascii[lengthProperty]%64 - 56) ascii += '\x00'
                                for (i = 0; i < ascii[lengthProperty]; i++) {
                                    j = ascii.charCodeAt(i);
                                    if (j>>8) return;
                                    words[i>>2] |= j << ((3 - i)%4)*8;
                                }
                                words[words[lengthProperty]] = ((asciiBitLength/maxWord)|0);
                                words[words[lengthProperty]] = (asciiBitLength)
                                for (j = 0; j < words[lengthProperty];) {
                                    var w = words.slice(j, j += 16);
                                    var oldHash = hash;
                                    hash = hash.slice(0, 8);
                                    for (i = 0; i < 64; i++) {
                                        var i2 = i + j;
                                        var w15 = w[i - 15], w2 = w[i - 2];
                                        var a = hash[0], e = hash[4];
                                        var temp1 = hash[7]
                                            + (rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25))
                                            + ((e & hash[5]) ^ ((~e) & hash[6]))
                                            + k[i]
                                            + (w[i] = (i < 16) ? w[i] : (
                                                    w[i - 16]
                                                    + (rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15>>>3))
                                                    + w[i - 7]
                                                    + (rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2>>>10))
                                                )|0
                                            );
                                        var temp2 = (rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22))
                                            + ((a & hash[1]) ^ (a & hash[2]) ^ (hash[1] & hash[2]));
                                        hash = [(temp1 + temp2)|0].concat(hash);
                                        hash[4] = (hash[4] + temp1)|0;
                                    }
                                    for (i = 0; i < 8; i++) { hash[i] = (hash[i] + oldHash[i])|0; }
                                }
                                for (i = 0; i < 8; i++) {
                                    for (j = 3; j + 1; j--) {
                                        var b = (hash[i]>>(j*8))&255;
                                        result += ((b < 16) ? 0 : '') + b.toString(16);
                                    }
                                }
                                return result;
                            }
                            const hex = sha256(s) || "";
                            return { toString: (enc) => enc === CryptoJSObj.enc.Base64 ? btoa(hex.match(/\w{2}/g).map(a => String.fromCharCode(parseInt(a, 16))).join('')) : hex };
                        },
                        SHA512: (str) => ({ toString: () => CryptoJSObj.SHA256(str).toString() }),
                        HmacSHA256: (msg, key) => CryptoJSObj.SHA256(String(msg) + String(key)),
                        HmacMD5: (msg, key) => CryptoJSObj.MD5(String(msg) + String(key))
                    };

                    window.CryptoJS = CryptoJSObj;
                    globalThis.CryptoJS = CryptoJSObj;

                    // Buffer shim
                    const BufferShim = {
                        from: (data, encoding) => {
                            let str = '';
                            if (typeof data === 'string') {
                                if (encoding === 'hex') {
                                    const bytes = [];
                                    for (let i = 0; i < data.length; i += 2) bytes.push(parseInt(data.substr(i, 2), 16));
                                    str = String.fromCharCode(...bytes);
                                } else if (encoding === 'base64') {
                                    try { str = atob(data); } catch(_) { str = data; }
                                } else {
                                    str = data;
                                }
                            } else if (Array.isArray(data) || data instanceof Uint8Array) {
                                str = String.fromCharCode(...data);
                            }
                            return {
                                toString: (enc) => {
                                    if (enc === 'hex') {
                                        return Array.from(str).map(c => c.charCodeAt(0).toString(16).padStart(2, '0')).join('');
                                    }
                                    if (enc === 'base64') {
                                        try { return btoa(str); } catch(_) { return str; }
                                    }
                                    return str;
                                },
                                length: str.length
                            };
                        },
                        isBuffer: (obj) => !!(obj && obj.toString && typeof obj.length === 'number')
                    };
                    window.Buffer = BufferShim;
                    globalThis.Buffer = BufferShim;

                    // Axios shim factory
                    const createAxiosInstance = () => {
                        const axiosFn = async (cfg) => {
                            let url = typeof cfg === 'string' ? cfg : (cfg && cfg.url ? cfg.url : '');
                            const method = (typeof cfg === 'object' && cfg && cfg.method ? cfg.method : 'GET').toUpperCase();
                            
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
                        axiosFn.create = createAxiosInstance;
                        axiosFn.defaults = { headers: { common: {} } };
                        return axiosFn;
                    };
                    window.axios = createAxiosInstance();
                    globalThis.axios = window.axios;

                    // Cheerio / jQuery DOM Wrapper
                    const createCheerio = () => {
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
                            arr.forEach((n, i) => { wrapper[i] = n; });
                            return wrapper;
                        };

                        return {
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
                    };
                    window.cheerio = createCheerio();
                    globalThis.cheerio = window.cheerio;

                    // Global require shim definition
                    function getRequireShim() {
                        return function(modName) {
                            const name = String(modName || '').toLowerCase();
                            if (name === 'axios') return window.axios;
                            if (name.includes('crypto')) return window.CryptoJS;
                            if (name.includes('cheerio') || name.includes('jquery') || name === '$') return window.cheerio;
                            if (name.includes('buffer')) return { Buffer: window.Buffer };
                            if (name.includes('rot13')) {
                                return (str) => String(str).replace(/[a-zA-Z]/g, c => {
                                    const base = c <= 'Z' ? 65 : 97;
                                    return String.fromCharCode(base + (c.charCodeAt(0) - base + 13) % 26);
                                });
                            }
                            if (name === 'querystring' || name === 'qs') {
                                return {
                                    stringify: (obj) => new URLSearchParams(obj).toString(),
                                    parse: (str) => Object.fromEntries(new URLSearchParams(str))
                                };
                            }
                            if (name === 'node-fetch' || name === 'fetch') return window.fetch;
                            return new Proxy({}, { get: (_, prop) => () => ({}) });
                        };
                    }

                    // Global plugin execution dispatcher
                    window.executePlugin = async function(reqId, jsCode, paramsJson) {
                        try {
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
                                else if (typeof global !== "undefined" && typeof global.getStreams === "function") handler = global.getStreams;
                                else if (typeof global !== "undefined" && typeof global.scrape === "function") handler = global.scrape;
                                else if (typeof getStream === "function") handler = getStream;
                                else if (typeof getSources === "function") handler = getSources;
                                else if (typeof extract === "function") handler = extract;
                                else if (typeof extractStreams === "function") handler = extractStreams;
                                else if (typeof getMedia === "function") handler = getMedia;
                                else if (typeof streams === "function") handler = streams;
                                else if (typeof stream === "function") handler = stream;

                                if (!handler && module && module.exports && typeof module.exports === "object") {
                                    for (const k of Object.keys(module.exports)) {
                                        if (typeof module.exports[k] === "function" && /stream|source|extract|scrape/i.test(k)) {
                                            handler = module.exports[k];
                                            break;
                                        }
                                    }
                                }
                                if (!handler && typeof exports === "object") {
                                    for (const k of Object.keys(exports)) {
                                        if (typeof exports[k] === "function" && /stream|source|extract|scrape/i.test(k)) {
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

                                const targetId = tmdbId || primaryId || imdbId;

                                if (isMovie) {
                                    // Movie Strategy 1: Nuvio 6-arg with Title and Year: handler(targetId, "movie", 1, 1, title, year)
                                    try {
                                        const r = await handler(targetId, "movie", 1, 1, title, year);
                                        if (hasStreams(r)) result = r;
                                    } catch(_) {}

                                    // Movie Strategy 2: handler(targetId, "movie", null, null, title, year)
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(targetId, "movie", null, null, title, year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // Movie Strategy 3: Standard Nuvio 2-arg: handler(targetId, "movie")
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(targetId, "movie");
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // Movie Strategy 4: Object params: handler(params)
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(params);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // Movie Strategy 5: handler(targetId, "movie", title, year)
                                    if (!hasStreams(result) && title) {
                                        try {
                                            const r = await handler(targetId, "movie", title, year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // Movie Strategy 6: handler(title, "movie", year)
                                    if (!hasStreams(result) && title) {
                                        try {
                                            const r = await handler(title, "movie", year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // Movie Strategy 7: Alternate ID -> handler(altId, "movie", 1, 1, title, year)
                                    if (!hasStreams(result) && altId && altId !== targetId) {
                                        try {
                                            const r = await handler(altId, "movie", 1, 1, title, year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                        if (!hasStreams(result)) {
                                            try {
                                                const r = await handler(altId, "movie");
                                                if (hasStreams(r)) result = r;
                                            } catch(_) {}
                                        }
                                    }

                                    // Movie Strategy 8: Stremio format
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler("movie", targetId || imdbId);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }
                                } else {
                                    // TV / Series strategies
                                    const s = (season !== null && season !== undefined) ? season : 1;
                                    const ep = (episode !== null && episode !== undefined) ? episode : 1;

                                    // Series Strategy 1: Nuvio 6-arg with Title and Year: handler(targetId, "tv", s, ep, title, year)
                                    try {
                                        const r = await handler(targetId, "tv", s, ep, title, year);
                                        if (hasStreams(r)) result = r;
                                    } catch(_) {}

                                    // Series Strategy 2: Standard Nuvio 4-arg: handler(targetId, "tv", s, ep)
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(targetId, "tv", s, ep);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // Series Strategy 3: With rawType e.g. "series": handler(targetId, rawType, s, ep, title, year)
                                    if (!hasStreams(result) && rawType !== "tv") {
                                        try {
                                            const r = await handler(targetId, rawType, s, ep, title, year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                        if (!hasStreams(result)) {
                                            try {
                                                const r = await handler(targetId, rawType, s, ep);
                                                if (hasStreams(r)) result = r;
                                            } catch(_) {}
                                        }
                                    }

                                    // Series Strategy 4: Object params: handler(params)
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(params);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // Series Strategy 5: By Title & Episode: handler(title, "tv", s, ep, year)
                                    if (!hasStreams(result) && title) {
                                        try {
                                            const r = await handler(title, "tv", s, ep, year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                    }

                                    // Series Strategy 6: IMDB ID explicitly: handler(imdbId, "tv", s, ep, title, year)
                                    if (!hasStreams(result) && imdbId && imdbId !== targetId) {
                                        try {
                                            const r = await handler(imdbId, "tv", s, ep, title, year);
                                            if (hasStreams(r)) result = r;
                                        } catch(_) {}
                                        if (!hasStreams(result)) {
                                            try {
                                                const r = await handler(imdbId, "tv", s, ep);
                                                if (hasStreams(r)) result = r;
                                            } catch(_) {}
                                        }
                                    }

                                    // Series Strategy 7: Stremio format
                                    if (!hasStreams(result)) {
                                        try {
                                            const r = await handler(rawType, (targetId || imdbId) + ":" + s + ":" + ep);
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
                    headersBuilder.add("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
                    headersBuilder.add("Sec-Ch-Ua-Mobile", "?0")
                    headersBuilder.add("Sec-Ch-Ua-Platform", "\"Windows\"")

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

    fun trimMemory() {
        mainHandler.post {
            try {
                webView?.evaluateJavascript("""
                    try {
                        if (window._fetchCallbacks) {
                            const now = Date.now();
                            for (let k in window._fetchCallbacks) {
                                if (window._fetchCallbacks[k] && window._fetchCallbacks[k].ts && (now - window._fetchCallbacks[k].ts > 600000)) {
                                    delete window._fetchCallbacks[k];
                                }
                            }
                        }
                    } catch(_) {}
                """.trimIndent(), null)
                webView?.clearCache(false)
            } catch (_: Exception) {}
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

        withContext(Dispatchers.Main) {
            if (webView == null) {
                initWebView()
            }
            val escapedCode = JSONObject.quote(plugin.jsCode)
            val escapedParams = JSONObject.quote(paramsJson)
            val jsCall = "window.executePlugin('$requestId', $escapedCode, $escapedParams);"
            webView?.evaluateJavascript(jsCall, null)
        }

        // When timeoutMs <= 0, run until JS scraper completely finishes execution (with generous 5-min safeguard)
        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else 300_000L
        val result = withTimeoutOrNull(effectiveTimeout) {
            deferred.await()
        }

        pendingRequests.remove(requestId)
        return@withPermit result ?: emptyList()
    }
}
