package app.perfectdnsmanager

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.conscrypt.Conscrypt
import java.security.Security

class PdmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Agent15 must use Android algorithm names for RSA-PSS TLS signatures.
        // Configure before any QUIC handshake; certificate validation stays enabled.
        tech.kwik.agent15.env.PlatformMapping.usePlatformMapping(
            tech.kwik.agent15.env.PlatformMapping.Platform.Android)

        app.perfectdnsmanager.data.Ipv6Settings.initialize(getSharedPreferences("prefs", Context.MODE_PRIVATE))
        // Conscrypt en provider TLS par défaut. Requis pour
        // exportKeyingMaterial() utilisé par le pairing ADB Android 11+
        // (SPAKE2 dérive son secret depuis le code 6-chiffres + keying
        // material du handshake TLS).
        try { Security.insertProviderAt(Conscrypt.newProvider(), 1) } catch (_: Throwable) {}
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
            app.perfectdnsmanager.data.UpdateChannel.initialize(prefs, isBetaBuild())

        } catch (_: Throwable) {
            // Never crash startup on theme init
        }
    }

    companion object {
        /** True si VERSION_NAME contient un suffixe pré-release (ex: 1.1.0-beta.50). */
        fun isBetaBuild(): Boolean = BuildConfig.VERSION_NAME.contains('-')

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
