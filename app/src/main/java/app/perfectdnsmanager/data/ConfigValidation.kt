package app.perfectdnsmanager.data

import app.perfectdnsmanager.util.ProfileValidation
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Validate the entire external document before any preference is changed. */
object ConfigValidation {
    private const val MAX_BYTES = 1024 * 1024
    private val gson = Gson()
    private val settingsKeys = setOf("auto_reconnect_dns", "disable_ipv6", "adb_dot_enabled",
        "operator_dns_enabled", "advanced_features_enabled", "show_doq_dns")

    fun parse(json: String): JsonObject {
        require(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Configuration too large" }
        // Bound nesting before Gson parsing to avoid a deeply nested hostile document.
        var depth = 0; var quoted = false; var escaped = false
        for (c in json) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "Configuration too deeply nested" } }
                '}', ']' -> depth--
            }
        }
        val root = JsonParser.parseString(json).asJsonObject
        fun array(key: String, max: Int, validate: (JsonElement) -> Unit) {
            val value = root.get(key) ?: return
            require(value.isJsonArray && value.asJsonArray.size() <= max) { "Invalid $key" }
            value.asJsonArray.forEach(validate)
        }
        fun text(e: JsonElement?, max: Int = 253): String {
            require(e != null && e.isJsonPrimitive && e.asJsonPrimitive.isString) { "Expected text" }
            return e.asString.also { require(it.isNotBlank() && it.length <= max && it.none { c -> c.isISOControl() }) { "Invalid text" } }
        }
        fun bool(e: JsonElement?) {
            require(e != null && e.isJsonPrimitive && e.asJsonPrimitive.isBoolean) { "Expected boolean" }
        }
        fun profile(e: JsonElement) {
            require(e.isJsonObject) { "Invalid profile" }
            val o = e.asJsonObject
            text(o.get("providerName"), 128); text(o.get("name"), 128)
            val p = gson.fromJson(o, DnsProfile::class.java)
            require(ProfileValidation.isUsable(p)) { "Invalid DNS endpoint" }
            p.description?.let { require(it.length <= 4096) }
            p.testUrl?.let { require(ProfileValidation.isHttpsUrl(it)) { "Invalid test URL" } }
            // Resource IDs are local implementation details, never part of trusted input.
            o.addProperty("descResId", 0); o.remove("descResIdArg")
            o.addProperty("primary", ProfileValidation.normalizeEndpoint(p.primary))
            p.secondary?.let { o.addProperty("secondary", ProfileValidation.normalizeEndpoint(it)) }
            val preset = DnsProfile.getDefaultPresets().find { it.id == p.id }
            o.addProperty("isCustom", preset == null || preset.primary != p.primary || preset.type != p.type || preset.secondary != p.secondary)
        }
        array("profiles", 512, ::profile)
        root.get("selectedProfile")?.let { if (!it.isJsonNull) profile(it) }
        array("rewriteRules", 256) { e ->
            require(e.isJsonObject) { "Invalid rewrite rule" }
            val o = e.asJsonObject
            require(ProfileValidation.isHostname(text(o.get("fromDomain"))) &&
                ProfileValidation.isHostname(text(o.get("toDomain")))) { "Invalid rewrite domain" }
            bool(o.get("isEnabled"))
            o.get("id")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString().toLongOrNull() != null) { "Invalid rule ID" } }
            gson.fromJson(o, DnsRewriteRule::class.java)
        }
        array("nextDnsProfiles", 128) { require(Regex("^[a-zA-Z0-9-]{1,64}$").matches(text(it, 64))) { "Invalid NextDNS ID" } }
        array("excludedApps", 512) { require(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$").matches(text(it))) { "Invalid application ID" } }
        array("testDomains", 128) { e ->
            require(e.isJsonObject) { "Invalid test domain" }
            val o = e.asJsonObject
            require(ProfileValidation.isHostname(text(o.get("domain")))) { "Invalid test domain" }
            bool(o.get("enabled"))
        }
        root.get("settings")?.let { value ->
            require(value.isJsonObject) { "Invalid settings" }
            for ((key, v) in value.asJsonObject.entrySet()) {
                require(key in settingsKeys) { "Unknown setting: $key" }; bool(v)
            }
        }
        root.get("version")?.let { text(it, 64) }
        root.get("exportDate")?.let { text(it, 64) }
        return root
    }
}
