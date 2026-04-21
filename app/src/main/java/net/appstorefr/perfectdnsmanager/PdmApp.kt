package net.appstorefr.perfectdnsmanager

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class PdmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val mode = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getString("theme_mode", "system") ?: "system"
            applyThemeMode(mode)
        } catch (_: Throwable) {
            // Never crash startup on theme init
        }
    }

    companion object {
        fun applyThemeMode(mode: String) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
