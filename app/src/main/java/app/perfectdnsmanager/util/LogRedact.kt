package app.perfectdnsmanager.util

/** Remove personal resolver IDs from DoH paths and DoT/DoQ hostnames. */
fun redactDnsUrl(value: String?): String {
    if (value.isNullOrBlank()) return value ?: ""
    val sanitized = PERSONAL_HOST.replace(value) {
        "${it.groupValues[1]}***${it.groupValues[2]}"
    }
    val schemeIdx = sanitized.indexOf("://")
    if (schemeIdx <= 0) return sanitized
    val afterScheme = schemeIdx + 3
    val cut = listOf('/', '?', '#').map { c ->
        sanitized.indexOf(c, afterScheme).let { if (it < 0) sanitized.length else it }
    }.min()
    return if (cut < sanitized.length) "${sanitized.substring(0, cut)}/…" else sanitized
}

private val PERSONAL_HOST = Regex(
    "^((?:https|quic|tls)://)?[^./:]+(\\.dns\\.(?:nextdns\\.io|controld\\.com))(?=[:/?#]|$)",
    RegexOption.IGNORE_CASE
)
