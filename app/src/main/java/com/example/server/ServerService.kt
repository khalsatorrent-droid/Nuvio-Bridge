package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NuvioApp
import com.example.data.repository.PluginRepository
import com.example.engine.PluginRunner
import com.example.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerService : Service() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var repository: PluginRepository
    private lateinit var pluginRunner: PluginRunner
    private var server: StremioHttpServer? = null

    inner class LocalBinder : Binder() {
        fun getService(): ServerService = this@ServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val app = application as NuvioApp
        repository = app.repository
        pluginRunner = app.pluginRunner

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NuvioServer::WakeLock")

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NuvioServer::WifiLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val port = intent?.getIntExtra(EXTRA_PORT, 8585) ?: 8585

        when (action) {
            ACTION_START -> {
                startServer(port)
            }
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun startServer(port: Int = 8585, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (server == null) {
            server = StremioHttpServer(
                context = this,
                repository = repository,
                pluginRunner = pluginRunner,
                port = port
            )
        }
        server?.port = port
        server?.sortByQuality = currentSortByQuality
        server?.groupByQuality = currentGroupByQuality
        server?.filterOutLowQuality = currentFilterOutLowQuality
        server?.requestTimeoutSec = currentTimeoutSec

        val notification = buildNotification(port)
        startForeground(NOTIFICATION_ID, notification)

        try {
            if (wakeLock?.isHeld == false) wakeLock?.acquire(24 * 60 * 60 * 1000L)
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (_: Exception) {}

        server?.start { success, err ->
            _isServerRunningFlow.value = success
            if (success) {
                _serverPortFlow.value = port
            }
            onComplete?.invoke(success, err)
        }
    }

    fun stopServer() {
        server?.stop()
        _isServerRunningFlow.value = false

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    fun getServer(): StremioHttpServer? = server

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nuvio Server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of local Stremio Addon server"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ip = NetworkUtils.getLocalIpAddress(this)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ Nuvio Stremio Server Active")
            .setContentText("Listening on port $port ($ip:$port)")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "nuvio_server_channel"
        const val NOTIFICATION_ID = 8585
        const val ACTION_START = "com.example.server.START"
        const val ACTION_STOP = "com.example.server.STOP"
        const val EXTRA_PORT = "extra_port"

        var currentSortByQuality: Boolean = true
        var currentGroupByQuality: Boolean = true
        var currentFilterOutLowQuality: Boolean = false
        var currentTimeoutSec: Int = 0 // 0 = Unlimited

        private val _isServerRunningFlow = MutableStateFlow(false)
        val isServerRunningFlow: StateFlow<Boolean> = _isServerRunningFlow.asStateFlow()

        private val _serverPortFlow = MutableStateFlow(8585)
        val serverPortFlow: StateFlow<Int> = _serverPortFlow.asStateFlow()
    }
}
