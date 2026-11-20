package ru.ruroot.sockstunnel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val host = findViewById<EditText>(R.id.etHost)
        val port = findViewById<EditText>(R.id.etPort)
        val user = findViewById<EditText>(R.id.etUser)
        val pass = findViewById<EditText>(R.id.etPass)
        val localAuth = findViewById<CheckBox>(R.id.cbLocalAuth)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnDisconnect = findViewById<Button>(R.id.btnDisconnect)
        val status = findViewById<TextView>(R.id.tvStatus)

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        host.setText(prefs.getString("host", "ruroot.ru"))
        port.setText(prefs.getString("port", "9443"))
        user.setText(prefs.getString("user", ""))
        pass.setText(prefs.getString("pass", ""))
        localAuth.isChecked = prefs.getBoolean("localAuth", false)
        refreshButtons(btnConnect, btnDisconnect)

        ProxyService.onStatus = { s ->
            runOnUiThread {
                status.text = s
                refreshButtons(btnConnect, btnDisconnect)
            }
        }
        status.text = ProxyService.lastStatus

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        btnConnect.setOnClickListener {
            val p = prefs.edit()
            p.putString("host", host.text.toString().trim())
            p.putString("port", port.text.toString().trim())
            p.putString("user", user.text.toString())
            p.putString("pass", pass.text.toString())
            p.putBoolean("localAuth", localAuth.isChecked)
            p.apply()

            val svc = Intent(this, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        }

        btnDisconnect.setOnClickListener {
            stopService(Intent(this, ProxyService::class.java))
        }
    }

    private fun refreshButtons(btnConnect: Button, btnDisconnect: Button) {
        val running = ProxyService.isRunning
        btnConnect.isEnabled = !running
        btnDisconnect.isEnabled = running
    }
}
