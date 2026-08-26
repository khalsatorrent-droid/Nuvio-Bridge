package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.repository.PluginRepository
import com.example.engine.PluginRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NuvioApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: PluginRepository
        private set

    lateinit var pluginRunner: PluginRunner
        private set

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        precreateWebViewCacheDirs()

        database = AppDatabase.getInstance(this)
        repository = PluginRepository(database)
        pluginRunner = PluginRunner(this)

        appScope.launch {
            repository.initializeDefaultsIfNeeded()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::pluginRunner.isInitialized) {
            pluginRunner.trimMemory()
        }
        if (level >= TRIM_MEMORY_MODERATE) {
            System.gc()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::pluginRunner.isInitialized) {
            pluginRunner.trimMemory()
        }
        System.gc()
    }

    private fun precreateWebViewCacheDirs() {
        try {
            val appDataDir = applicationInfo.dataDir
            val baseDirs = listOfNotNull(
                cacheDir,
                codeCacheDir,
                filesDir,
                if (appDataDir != null) java.io.File(appDataDir, "cache") else null,
                if (appDataDir != null) java.io.File(appDataDir, "app_webview") else null
            )

            val subPaths = listOf(
                "WebView/Default/HTTP Cache/Code Cache/js",
                "WebView/Default/HTTP Cache/Code Cache/wasm",
                "WebView/Default/HTTP Cache/index-dir",
                "WebView/Default/HTTP Cache",
                "WebView/Default/Code Cache/js",
                "WebView/Default/Code Cache/wasm",
                "Default/HTTP Cache/Code Cache/js",
                "Default/HTTP Cache/Code Cache/wasm",
                "Default/HTTP Cache/index-dir",
                "Default/HTTP Cache",
                "app_webview/Default/HTTP Cache/Code Cache/js",
                "app_webview/Default/HTTP Cache/Code Cache/wasm",
                "app_webview/Default/HTTP Cache/index-dir",
                "app_webview/Default/HTTP Cache",
                "org.chromium.android_webview/Default/HTTP Cache/Code Cache/js",
                "org.chromium.android_webview/Default/HTTP Cache/Code Cache/wasm"
            )

            for (base in baseDirs) {
                for (sub in subPaths) {
                    val target = java.io.File(base, sub)
                    if (!target.exists()) {
                        target.mkdirs()
                    }
                }
            }
        } catch (e: Exception) {
            // Non-fatal initialization
        }
    }
}
