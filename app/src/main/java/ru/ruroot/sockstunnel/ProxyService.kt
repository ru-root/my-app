package ru.ruroot.sockstunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class ProxyService : Service() {

    companion object {
        const val ACTION_START = "ru.ruroot.sockstunnel.START"
        const val ACTION_STOP = "ru.ruroot.sockstunnel.STOP"
        const val ACTION_STATUS = "ru.ruroot.sockstunnel.STATUS"
        const val EXTRA_STATUS = "status"

        const val PREFS_NAME = "settings"
        const val KEY_SERVER_HOST = "server_host"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_TUNNEL_USER = "tunnel_user"
        const val KEY_TUNNEL_PASS = "tunnel_pass"
        const val KEY_LOCAL_AUTH = "local_auth"
        const val KEY_LOCAL_PORT = "local_port"

        private const val CHANNEL_ID = "socks_tunnel"
        private const val NOTIF_ID = 1
        private const val TAG = "ProxyService"
    }

    private var socks5: Socks5Server? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Подключение…", android.R.drawable.stat_notify_sync))
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (started) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val localPort = prefs.getInt(KEY_LOCAL_PORT, 5557)
        val server = Socks5Server(
            listenPort = localPort,
            serverHost = prefs.getString(KEY_SERVER_HOST, "ruroot.ru") ?: "ruroot.ru",
            serverPort = prefs.getInt(KEY_SERVER_PORT, 9443),
            proxyUser = prefs.getString(KEY_TUNNEL_USER, "") ?: "",
            proxyPass = prefs.getString(KEY_TUNNEL_PASS, "") ?: "",
            localAuthRequired = prefs.getBoolean(KEY_LOCAL_AUTH, false)
        )
        if (server.start()) {
            socks5 = server
            started = true
            val msg = "SOCKS5 работает: 127.0.0.1:$localPort"
            Log.i(TAG, msg)
            sendStatus(msg)
            updateNotification("SOCKS5: 127.0.0.1:$localPort", R.drawable.ic_tunnel)
        } else {
            started = false
            Log.e(TAG, "failed to bind port $localPort")
            sendStatus("Не удалось открыть локальный порт $localPort")
            stopSelf()
        }
    }

    private fun stopServer() {
        socks5?.stop()
        socks5 = null
        started = false
        Log.i(TAG, "stopped")
        sendStatus("Остановлено")
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun sendStatus(text: String) {
        val i = Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, text)
        sendBroadcast(i)
    }

    private fun buildNotification(text: String, iconRes: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Прокси-туннель", NotificationManager.IMPORTANCE_LOW)
                    )
                }
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder
            .setContentTitle("SOCKS5-туннель")
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, iconRes: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, iconRes))
    }
}
