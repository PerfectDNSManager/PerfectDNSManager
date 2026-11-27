package app.perfectdnsmanager.data

import android.content.Context
import com.google.gson.JsonObject

class DnsPrefilterRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    data class Settings(val enabled: Boolean, val builtin: Boolean, val blocked: String, val allowed: String)
    fun settings() = Settings(prefs.getBoolean(PREFIX + "enabled", false),
        prefs.getBoolean(PREFIX + "builtin", true), prefs.getString(PREFIX + "blocked", "") ?: "",
        prefs.getString(PREFIX + "allowed", "") ?: "")

    fun save(settings: Settings) {
        val blocked = DnsPrefilter.parseDomains(settings.blocked).joinToString("\n")
        val allowed = DnsPrefilter.parseDomains(settings.allowed).joinToString("\n")
        check(prefs.edit().putBoolean(PREFIX + "enabled", settings.enabled)
            .putBoolean(PREFIX + "builtin", settings.builtin)
            .putString(PREFIX + "blocked", blocked).putString(PREFIX + "allowed", allowed).commit())
    }

    /** Call on a worker thread, before accepting VPN traffic. */
    fun load(): DnsPrefilter {
        val settings = settings()
        if (!settings.enabled) return DnsPrefilter()
        val bundled = if (settings.builtin) bundledDomains(context) else emptyList()
        return DnsPrefilter(true, bundled, DnsPrefilter.parseDomains(settings.blocked),
            DnsPrefilter.parseDomains(settings.allowed))
    }

    fun export(): JsonObject = settings().let { settings -> JsonObject().apply {
        addProperty("enabled", settings.enabled); addProperty("builtin", settings.builtin)
        addProperty("blocked", settings.blocked); addProperty("allowed", settings.allowed)
    } }

    companion object {
        const val PREFIX = "dns_prefilter_"
        @Volatile private var cachedDomains: List<String>? = null
        @Synchronized private fun bundledDomains(context: Context): List<String> {
            cachedDomains?.let { return it }
            return context.assets.open("prefilter/hagezi-light.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }
                    // Trusted ASCII asset, validated by the snapshot unit test. Avoid
                    // 80k IDN conversions on older boxes at the first VPN startup.
                    .toList().sorted()
            }.also { cachedDomains = it }
        }
    }
}
