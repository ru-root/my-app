package ru.ruroot.sockstunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_SERVER_HOST = "server_host"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_TUNNEL_USER = "tunnel_user"
        const val KEY_TUNNEL_PASS = "tunnel_pass"
        const val KEY_LOCAL_AUTH = "local_auth"
        const val KEY_LOCAL_PORT = "local_port"
    }

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etLocalPort: EditText
    private lateinit var cbLocalAuth: CheckBox
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private var running = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(ProxyService.EXTRA_STATUS) ?: return
            tvStatus.text = text
            running = text.startsWith("SOCKS5 работает")
            updateButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        buildUi()
        loadSettings()
        updateButtons()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, IntentFilter(ProxyService.ACTION_STATUS), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, IntentFilter(ProxyService.ACTION_STATUS))
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        etHost = EditText(this).apply { hint = "Сервер"; setText("ruroot.ru"); setSingleLine() }
        etPort = EditText(this).apply { hint = "Порт"; setText("9443"); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine() }
        etUser = EditText(this).apply { hint = "Логин (3proxy)"; setSingleLine() }
        etPass = EditText(this).apply {
            hint = "Пароль (3proxy)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        etLocalPort = EditText(this).apply { hint = "Локальный SOCKS5 порт"; setText("5557"); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine() }
        cbLocalAuth = CheckBox(this).apply { text = "Требовать логин/пароль для локального SOCKS5 (127.0.0.1)" }
        tvStatus = TextView(this).apply { text = "Остановлено" }
        btnStart = Button(this).apply { text = "Подключить" }
        btnStop = Button(this).apply { text = "Отключить" }

        root.addView(etHost)
        root.addView(etPort)
        root.addView(etUser)
        root.addView(etPass)
        root.addView(etLocalPort)
        root.addView(cbLocalAuth)
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(tvStatus)
        setContentView(root)

        btnStart.setOnClickListener { startProxy() }
        btnStop.setOnClickListener { stopProxy() }
    }

    private fun loadSettings() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        etHost.setText(p.getString(KEY_SERVER_HOST, "ruroot.ru") ?: "ruroot.ru")
        etPort.setText(p.getInt(KEY_SERVER_PORT, 9443).toString())
        etUser.setText(p.getString(KEY_TUNNEL_USER, "") ?: "")
        etPass.setText(p.getString(KEY_TUNNEL_PASS, "") ?: "")
        etLocalPort.setText(p.getInt(KEY_LOCAL_PORT, 5557).toString())
        cbLocalAuth.isChecked = p.getBoolean(KEY_LOCAL_AUTH, false)
    }

    private fun saveSettings() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        p.putString(KEY_SERVER_HOST, etHost.text.toString().trim().ifEmpty { "ruroot.ru" })
        p.putInt(KEY_SERVER_PORT, etPort.text.toString().trim().toIntOrNull() ?: 9443)
        p.putString(KEY_TUNNEL_USER, etUser.text.toString().trim())
        p.putString(KEY_TUNNEL_PASS, etPass.text.toString())
        p.putInt(KEY_LOCAL_PORT, etLocalPort.text.toString().trim().toIntOrNull() ?: 5557)
        p.putBoolean(KEY_LOCAL_AUTH, cbLocalAuth.isChecked)
        p.apply()
    }

   private fun startProxy() {
    saveSettings()
    val i = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(i)
    } else {
        startService(i)
    }
    tvStatus.text = "Подключение…"
    updateButtons()
}


    private fun stopProxy() {
        val i = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
        startService(i)
        running = false
        tvStatus.text = "Останавливаю…"
        updateButtons()
    }

    private fun updateButtons() {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }
}
