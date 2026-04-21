package net.appstorefr.perfectdnsmanager

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class PdmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            // Première installation : forcer dark. Les utilisateurs existants (avec
            // la clé déjà présente, même valeur "system") gardent leur préférence.
            val mode = if (prefs.contains("theme_mode")) {
                prefs.getString("theme_mode", "dark") ?: "dark"
            } else {
                prefs.edit().putString("theme_mode", "dark").apply()
                "dark"
            }
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
