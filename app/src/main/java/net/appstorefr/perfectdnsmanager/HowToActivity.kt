package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.util.LocaleHelper

class HowToActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_howto)

        val btnBack: Button = findViewById(R.id.btnBack)
        val btnOpenDevOptions: Button = findViewById(R.id.btnOpenDevOptions)
        val tvAdbStatus: TextView = findViewById(R.id.tvAdbStatus)

        btnBack.requestFocus()
        btnBack.setOnClickListener { finish() }

        // Vérifier si le débogage ADB est actif
        val adbEnabled = Settings.Global.getInt(
            contentResolver, Settings.Global.ADB_ENABLED, 0
        ) == 1

        if (adbEnabled) {
            tvAdbStatus.text = getString(R.string.howto_adb_active)
            tvAdbStatus.setTextColor(getColor(android.R.color.holo_green_light))
            btnOpenDevOptions.text = getString(R.string.howto_check_dev_options)
        } else {
            tvAdbStatus.text = getString(R.string.howto_adb_inactive)
            tvAdbStatus.setTextColor(getColor(android.R.color.holo_red_light))
            btnOpenDevOptions.text = getString(R.string.howto_open_dev_options)
        }

        btnOpenDevOptions.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                } catch (e2: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }
}
