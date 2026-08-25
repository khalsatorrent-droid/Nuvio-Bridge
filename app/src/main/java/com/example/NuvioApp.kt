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

    private fun precreateWebViewCacheDirs() {
        try {
            val cacheBase = cacheDir
            val dataBase = filesDir?.parentFile ?: return
            val dirs = listOf(
                java.io.File(cacheBase, "WebView/Default/HTTP Cache/Code Cache/js"),
                java.io.File(cacheBase, "WebView/Default/HTTP Cache/Code Cache/wasm"),
                java.io.File(cacheBase, "WebView/Default/HTTP Cache/index-dir"),
                java.io.File(cacheBase, "WebView/Default/HTTP Cache"),
                java.io.File(dataBase, "app_webview/Default/HTTP Cache/Code Cache/js"),
                java.io.File(dataBase, "app_webview/Default/HTTP Cache/Code Cache/wasm"),
                java.io.File(dataBase, "app_webview/Default/HTTP Cache/index-dir"),
                java.io.File(dataBase, "app_webview/Default/HTTP Cache")
            )
            for (dir in dirs) {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
        } catch (e: Exception) {
            // Non-fatal directory initialization
        }
    }
}
