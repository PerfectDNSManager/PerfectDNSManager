package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.service.UpdateManager
import net.appstorefr.perfectdnsmanager.util.LocaleHelper

class AboutActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var updateManager: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        updateManager = UpdateManager(this)

        val btnBack: Button = findViewById(R.id.btnBack)
        val tvVersion: TextView = findViewById(R.id.tvVersion)
        val btnCheckForUpdate: Button = findViewById(R.id.btnCheckForUpdate)

        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        tvVersion.text = getString(R.string.version, currentVersion)

        btnBack.requestFocus()
        btnBack.setOnClickListener { finish() }

        findViewById<Button>(R.id.btnSupportAbout).setOnClickListener {
            startActivity(android.content.Intent(this, SupportActivity::class.java))
        }

        btnCheckForUpdate.setOnClickListener {
            Toast.makeText(this, getString(R.string.checking_for_updates), Toast.LENGTH_SHORT).show()
            updateManager.checkForUpdateGitHub(
                "appstorefr/PerfectDNSManager",
                currentVersion
            )
        }
    }
}
