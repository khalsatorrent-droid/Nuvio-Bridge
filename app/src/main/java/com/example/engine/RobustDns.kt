package com.example.engine

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * High-resilience DNS resolver with multi-provider DNS-over-HTTPS (DoH) fallback.
 * Bypasses local ISP blocks, corporate firewalls, and container DNS omissions
 * for streaming / scraping hostnames.
 */
object RobustDns : Dns {
    private const val TAG = "RobustDns"

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour

    // Dedicated raw OkHttp client using direct IPs to query DoH without recursive DNS
    private val rawClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase()

        // 1. Check in-memory cache
        val cached = cache[cleanHost]
        if (cached != null && (System.currentTimeMillis() - cached.first) < CACHE_TTL_MS) {
            return cached.second
        }

        // 2. Try System DNS first
        try {
            val systemAddresses = Dns.SYSTEM.lookup(cleanHost)
            if (systemAddresses.isNotEmpty()) {
                cache[cleanHost] = Pair(System.currentTimeMillis(), systemAddresses)
                return systemAddresses
            }
        } catch (e: Exception) {
            Log.d(TAG, "System DNS failed for $cleanHost, invoking DoH fallback: ${e.message}")
        }

        // 3. Fallback 1: Cloudflare DoH (1.1.1.1)
        val cfAddresses = queryDoH("https://1.1.1.1/dns-query?name=$cleanHost&type=A", cleanHost)
        if (cfAddresses.isNotEmpty()) {
            Log.i(TAG, "Resolved $cleanHost -> ${cfAddresses.first().hostAddress} via Cloudflare DoH")
            cache[cleanHost] = Pair(System.currentTimeMillis(), cfAddresses)
            return cfAddresses
        }

        // 4. Fallback 2: Google DoH (8.8.8.8)
        val googleAddresses = queryDoH("https://dns.google/resolve?name=$cleanHost&type=A", cleanHost)
        if (googleAddresses.isNotEmpty()) {
            Log.i(TAG, "Resolved $cleanHost -> ${googleAddresses.first().hostAddress} via Google DoH")
            cache[cleanHost] = Pair(System.currentTimeMillis(), googleAddresses)
            return googleAddresses
        }

        // 5. Fallback 3: Quad9 DoH (9.9.9.9)
        val quad9Addresses = queryDoH("https://9.9.9.9/dns-query?name=$cleanHost&type=A", cleanHost)
        if (quad9Addresses.isNotEmpty()) {
            Log.i(TAG, "Resolved $cleanHost -> ${quad9Addresses.first().hostAddress} via Quad9 DoH")
            cache[cleanHost] = Pair(System.currentTimeMillis(), quad9Addresses)
            return quad9Addresses
        }

        throw UnknownHostException("Unable to resolve host \"$cleanHost\": All DNS resolvers (System, Cloudflare, Google, Quad9) failed")
    }

    private fun queryDoH(urlStr: String, hostname: String): List<InetAddress> {
        return try {
            val request = Request.Builder()
                .url(urlStr)
                .header("Accept", "application/dns-json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Nuvio/2.3.0")
                .build()

            val response = rawClient.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrEmpty()) {
                return emptyList()
            }

            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            val results = mutableListOf<InetAddress>()

            for (i in 0 until answers.length()) {
                val ans = answers.getJSONObject(i)
                val type = ans.optInt("type", 1) // 1 = A (IPv4)
                val ipStr = ans.optString("data", "").trim()
                if (type == 1 && ipStr.isNotEmpty()) {
                    try {
                        val inet = InetAddress.getByAddress(hostname, InetAddress.getByName(ipStr).address)
                        results.add(inet)
                    } catch (_: Exception) {}
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }
}
