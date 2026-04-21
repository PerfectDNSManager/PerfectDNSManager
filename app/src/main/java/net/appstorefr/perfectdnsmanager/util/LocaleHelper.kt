package net.appstorefr.perfectdnsmanager.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {

    fun applyLocale(context: Context): Context {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val langCode = prefs.getString("language", null) ?: return context

        val locale = when (langCode) {
            "zh" -> Locale("zh", "CN")
            "pt" -> Locale("pt", "BR")
            else -> Locale(langCode)
        }
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }
}
