package app.perfectdnsmanager.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ConfigManager(private val context: Context) {

    private val gson = Gson()

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
    }

    data class ImportResult(
        val profileCount: Int,
        val rewriteRuleCount: Int,
        val nextDnsProfileCount: Int,
        val hasSelectedProfile: Boolean,
        val settingsRestored: Boolean
    )

    // ══════════════════════════════════════════════════════════════
    //  Export
    // ══════════════════════════════════════════════════════════════

    fun exportConfigSelective(
        includeProfiles: Boolean = true,
        includeSelectedProfile: Boolean = true,
        includeNextDnsProfiles: Boolean = true,
        includeRewriteRules: Boolean = true,
        includeSettings: Boolean = true
    ): String {
        val root = JsonObject()

        root.addProperty("version", getAppVersion())
        root.addProperty("exportDate", iso8601Now())

        val profileManager = ProfileManager(context)
        val profiles = profileManager.loadProfiles()

        if (includeProfiles) {
            root.add("profiles", gson.toJsonTree(profiles))
        }

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        if (includeSelectedProfile) {
            val selectedProfileJson = prefs.getString("selected_profile_json", null)
            if (selectedProfileJson != null) {
                root.add("selectedProfile", JsonParser.parseString(selectedProfileJson))
            }
        }

        if (includeRewriteRules) {
            val rewriteRepo = DnsRewriteRepository(context)
            val rewriteRules = rewriteRepo.getAllRules()
            root.add("rewriteRules", gson.toJsonTree(rewriteRules))
        }

        if (includeNextDnsProfiles) {
            val nextDnsPrefs = context.getSharedPreferences("nextdns_profiles", Context.MODE_PRIVATE)
            val nextDnsIds = nextDnsPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()
            root.add("nextDnsProfiles", gson.toJsonTree(nextDnsIds.sorted()))
        }

        if (includeSettings) {
            val settings = JsonObject()
            settings.addProperty("auto_reconnect_dns", prefs.getBoolean("auto_reconnect_dns", false))
            settings.addProperty("disable_ipv6", prefs.getBoolean("disable_ipv6", false))
            settings.addProperty("adb_dot_enabled", prefs.getBoolean("adb_dot_enabled", false))
            settings.addProperty("operator_dns_enabled", prefs.getBoolean("operator_dns_enabled", false))
            settings.addProperty("advanced_features_enabled", prefs.getBoolean("advanced_features_enabled", false))
            settings.addProperty("show_doq_dns", prefs.getBoolean("show_doq_dns", false))
            root.add("settings", settings)
        }

        // Test domains
        val testDomainsJson = prefs.getString("test_domains_json", null)
        if (testDomainsJson != null) {
            root.add("testDomains", JsonParser.parseString(testDomainsJson))
        }

        // Excluded apps (split tunneling)
        val excludedAppsJson = prefs.getString("excluded_apps_json", null)
        if (excludedAppsJson != null) {
            root.add("excludedApps", JsonParser.parseString(excludedAppsJson))
        }

        return gson.newBuilder().setPrettyPrinting().create().toJson(root)
    }

    fun exportConfig(): String {
        val root = JsonObject()

        // Version and date
        root.addProperty("version", getAppVersion())
        root.addProperty("exportDate", iso8601Now())

        // Profiles from ProfileManager (SharedPrefs "dns_profiles_v2", key "profiles")
        val profileManager = ProfileManager(context)
        val profiles = profileManager.loadProfiles()
        root.add("profiles", gson.toJsonTree(profiles))

        // Selected profile from SharedPrefs "prefs"
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val selectedProfileJson = prefs.getString("selected_profile_json", null)
        if (selectedProfileJson != null) {
            root.add("selectedProfile", JsonParser.parseString(selectedProfileJson))
        } else {
            root.add("selectedProfile", null)
        }

        // Rewrite rules from DnsRewriteRepository (SharedPrefs "dns_rewrite_rules", key "rules")
        val rewriteRepo = DnsRewriteRepository(context)
        val rewriteRules = rewriteRepo.getAllRules()
        root.add("rewriteRules", gson.toJsonTree(rewriteRules))

        // NextDNS profile IDs from SharedPrefs "nextdns_profiles", key "profile_ids"
        val nextDnsPrefs = context.getSharedPreferences("nextdns_profiles", Context.MODE_PRIVATE)
        val nextDnsIds = nextDnsPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()
        root.add("nextDnsProfiles", gson.toJsonTree(nextDnsIds.sorted()))

        // Settings from SharedPrefs "prefs"
        val settings = JsonObject()
        settings.addProperty("auto_reconnect_dns", prefs.getBoolean("auto_reconnect_dns", false))
        settings.addProperty("disable_ipv6", prefs.getBoolean("disable_ipv6", false))
        settings.addProperty("adb_dot_enabled", prefs.getBoolean("adb_dot_enabled", false))
        settings.addProperty("operator_dns_enabled", prefs.getBoolean("operator_dns_enabled", false))
        settings.addProperty("advanced_features_enabled", prefs.getBoolean("advanced_features_enabled", false))
        root.add("settings", settings)

        // Test domains
        val testDomainsJson = prefs.getString("test_domains_json", null)
        if (testDomainsJson != null) {
            root.add("testDomains", JsonParser.parseString(testDomainsJson))
        }

        // Excluded apps (split tunneling)
        val excludedAppsJson = prefs.getString("excluded_apps_json", null)
        if (excludedAppsJson != null) {
            root.add("excludedApps", JsonParser.parseString(excludedAppsJson))
        }

        return gson.newBuilder().setPrettyPrinting().create().toJson(root)
    }

    // ══════════════════════════════════════════════════════════════
    //  Import
    // ══════════════════════════════════════════════════════════════

    fun importConfig(json: String, importSettings: Boolean = true): ImportResult {
        val root = JsonParser.parseString(json).asJsonObject

        // ── Profiles ──
        var profileCount = 0
        if (root.has("profiles") && !root.get("profiles").isJsonNull) {
            val profileType = object : TypeToken<List<DnsProfile>>() {}.type
            val profiles: List<DnsProfile> = gson.fromJson(root.get("profiles"), profileType)
            profileCount = profiles.size

            // Save via ProfileManager (SharedPrefs "dns_profiles_v2", key "profiles")
            val profileManager = ProfileManager(context)
            profileManager.saveProfiles(profiles)
        }

        // ── Selected profile ──
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val hasSelectedProfile: Boolean
        if (root.has("selectedProfile") && !root.get("selectedProfile").isJsonNull) {
            val selectedProfileElement = root.get("selectedProfile")
            prefs.edit().putString("selected_profile_json", gson.toJson(selectedProfileElement)).apply()
            hasSelectedProfile = true
        } else {
            prefs.edit().remove("selected_profile_json").apply()
            hasSelectedProfile = false
        }

        // ── Rewrite rules ──
        var rewriteRuleCount = 0
        if (root.has("rewriteRules") && !root.get("rewriteRules").isJsonNull) {
            val ruleType = object : TypeToken<List<DnsRewriteRule>>() {}.type
            val rules: List<DnsRewriteRule> = gson.fromJson(root.get("rewriteRules"), ruleType)
            rewriteRuleCount = rules.size

            // Save directly to SharedPrefs "dns_rewrite_rules", key "rules"
            val rewritePrefs = context.getSharedPreferences("dns_rewrite_rules", Context.MODE_PRIVATE)
            rewritePrefs.edit().putString("rules", gson.toJson(rules)).apply()
        }

        // ── NextDNS profile IDs ──
        var nextDnsProfileCount = 0
        if (root.has("nextDnsProfiles") && !root.get("nextDnsProfiles").isJsonNull) {
            val idsType = object : TypeToken<List<String>>() {}.type
            val ids: List<String> = gson.fromJson(root.get("nextDnsProfiles"), idsType)
            nextDnsProfileCount = ids.size

            val nextDnsPrefs = context.getSharedPreferences("nextdns_profiles", Context.MODE_PRIVATE)
            nextDnsPrefs.edit().putStringSet("profile_ids", ids.toSet()).apply()
        }

        // ── Test domains ──
        if (root.has("testDomains") && !root.get("testDomains").isJsonNull) {
            prefs.edit().putString("test_domains_json", root.get("testDomains").toString()).apply()
        }

        // ── Excluded apps (split tunneling) ──
        if (root.has("excludedApps") && !root.get("excludedApps").isJsonNull) {
            prefs.edit().putString("excluded_apps_json", root.get("excludedApps").toString()).apply()
        }

        // ── Settings ──
        var settingsRestored = false
        if (importSettings && root.has("settings") && !root.get("settings").isJsonNull) {
            val settings = root.getAsJsonObject("settings")
            val editor = prefs.edit()

            if (settings.has("auto_reconnect_dns")) {
                editor.putBoolean("auto_reconnect_dns", settings.get("auto_reconnect_dns").asBoolean)
            }
            if (settings.has("disable_ipv6")) {
                editor.putBoolean("disable_ipv6", settings.get("disable_ipv6").asBoolean)
            }
            if (settings.has("adb_dot_enabled")) {
                editor.putBoolean("adb_dot_enabled", settings.get("adb_dot_enabled").asBoolean)
            }
            if (settings.has("operator_dns_enabled")) {
                editor.putBoolean("operator_dns_enabled", settings.get("operator_dns_enabled").asBoolean)
            }
            if (settings.has("advanced_features_enabled")) {
                editor.putBoolean("advanced_features_enabled", settings.get("advanced_features_enabled").asBoolean)
            }
            if (settings.has("show_doq_dns")) {
                editor.putBoolean("show_doq_dns", settings.get("show_doq_dns").asBoolean)
            }

            editor.apply()
            settingsRestored = true
        }

        return ImportResult(
            profileCount = profileCount,
            rewriteRuleCount = rewriteRuleCount,
            nextDnsProfileCount = nextDnsProfileCount,
            hasSelectedProfile = hasSelectedProfile,
            settingsRestored = settingsRestored
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  Summary (human-readable preview of a config JSON)
    // ══════════════════════════════════════════════════════════════

    fun getConfigSummary(json: String): String {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val sb = StringBuilder()

            // Version
            val version = if (root.has("version")) root.get("version").asString else "unknown"
            sb.appendLine("Version: $version")

            // Export date
            if (root.has("exportDate") && !root.get("exportDate").isJsonNull) {
                sb.appendLine("Date: ${root.get("exportDate").asString}")
            }

            // Profiles
            if (root.has("profiles") && !root.get("profiles").isJsonNull) {
                val profileArray = root.getAsJsonArray("profiles")
                val total = profileArray.size()
                val customCount = profileArray.count { element ->
                    try {
                        element.asJsonObject.get("isCustom")?.asBoolean == true
                    } catch (_: Exception) {
                        false
                    }
                }
                sb.appendLine("Profiles: $total ($customCount custom)")
            } else {
                sb.appendLine("Profiles: 0")
            }

            // Selected profile
            if (root.has("selectedProfile") && !root.get("selectedProfile").isJsonNull) {
                try {
                    val sp = root.getAsJsonObject("selectedProfile")
                    val provider = sp.get("providerName")?.asString ?: ""
                    val name = sp.get("name")?.asString ?: ""
                    sb.appendLine("Selected profile: $provider - $name")
                } catch (_: Exception) {
                    sb.appendLine("Selected profile: yes")
                }
            } else {
                sb.appendLine("Selected profile: none")
            }

            // Rewrite rules
            if (root.has("rewriteRules") && !root.get("rewriteRules").isJsonNull) {
                val rulesArray = root.getAsJsonArray("rewriteRules")
                val enabledCount = rulesArray.count { element ->
                    try {
                        element.asJsonObject.get("isEnabled")?.asBoolean == true
                    } catch (_: Exception) {
                        false
                    }
                }
                sb.appendLine("Rewrite rules: ${rulesArray.size()} ($enabledCount enabled)")
            } else {
                sb.appendLine("Rewrite rules: 0")
            }

            // NextDNS profiles
            if (root.has("nextDnsProfiles") && !root.get("nextDnsProfiles").isJsonNull) {
                val nextDnsArray = root.getAsJsonArray("nextDnsProfiles")
                if (nextDnsArray.size() > 0) {
                    val ids = nextDnsArray.map { it.asString }
                    sb.appendLine("NextDNS profiles: ${ids.joinToString(", ")}")
                } else {
                    sb.appendLine("NextDNS profiles: none")
                }
            }

            // Test domains
            if (root.has("testDomains") && !root.get("testDomains").isJsonNull) {
                val domainsArray = root.getAsJsonArray("testDomains")
                val enabledCount = domainsArray.count { element ->
                    try {
                        element.asJsonObject.get("enabled")?.asBoolean == true
                    } catch (_: Exception) {
                        false
                    }
                }
                sb.appendLine("Test domains: ${domainsArray.size()} ($enabledCount enabled)")
            }

            // Excluded apps (split tunneling)
            if (root.has("excludedApps") && !root.get("excludedApps").isJsonNull) {
                val appsArray = root.getAsJsonArray("excludedApps")
                if (appsArray.size() > 0) {
                    sb.appendLine("Excluded apps (split tunnel): ${appsArray.size()}")
                }
            }

            // Settings
            if (root.has("settings") && !root.get("settings").isJsonNull) {
                val settings = root.getAsJsonObject("settings")
                val enabledSettings = settings.entrySet()
                    .filter { it.value.asBoolean }
                    .map { it.key.replace("_", " ") }
                if (enabledSettings.isNotEmpty()) {
                    sb.appendLine("Settings enabled: ${enabledSettings.joinToString(", ")}")
                } else {
                    sb.appendLine("Settings: all disabled")
                }
            }

            sb.toString().trimEnd()
        } catch (e: Exception) {
            "Invalid configuration file: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════

    private fun iso8601Now(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
