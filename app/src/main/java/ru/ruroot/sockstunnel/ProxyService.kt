package ru.ruroot.sockstunnel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground-сервис: держит локальный SOCKS5 и показывает уведомление,
 * чтобы Android не убивал приложение в фоне.
 */
class ProxyService : Service() {

    private var socks: Socks5Server? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "socks", "Socks Tunnel", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val host = p.getString("host", "ruroot.ru") ?: "ruroot.ru"
        val port = p.getString("port", "9443")?.toIntOrNull() ?: 9443
        val user = p.getString("user", "") ?: ""
        val pass = p.getString("pass", "") ?: ""
        val localAuth = p.getBoolean("localAuth", false)

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, "socks")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Socks Tunnel")
            .setContentText("SOCKS5: 127.0.0.1:5557 → $host:$port")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }

        socks?.stopServer()
        socks = Socks5Server(
            localPort = 5557,
            requireLocalAuth = localAuth,
            localUser = if (localAuth) user else null,
            localPass = if (localAuth) pass else null,
            tunnelHost = host,
            tunnelPort = port,
            tunnelUser = user,
            tunnelPass = pass
        ) { s ->
            lastStatus = s
            onStatus?.invoke(s)
        }
        socks?.start()
        isRunning = true
        return START_STICKY // система перезапустит сервис, если его убьют
    }

    override fun onDestroy() {
        socks?.stopServer()
        isRunning = false
        lastStatus = "Отключено"
        onStatus?.invoke(lastStatus)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var isRunning: Boolean = false
        @Volatile var lastStatus: String = "Отключено"
        var onStatus: ((String) -> Unit)? = null
    }
}
