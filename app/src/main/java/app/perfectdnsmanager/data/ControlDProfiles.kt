package app.perfectdnsmanager.data

import java.net.URI

/** Personal resolver addresses documented by Control D; no account credentials required. */
object ControlDProfiles {
    private val idPattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    fun resolverId(input: String): String? = try {
        val value = input.trim()
        if (idPattern.matches(value)) value else {
            val uri = URI(if (value.contains("://")) value else "tls://$value")
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase() ?: ""
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) null
            else if (scheme == "https" && host == "dns.controld.com" && uri.port in listOf(-1, 443)) {
                uri.rawPath.removePrefix("/").removeSuffix("/").takeIf { idPattern.matches(it) }
            } else if (scheme in listOf("tls", "quic") && host.endsWith(".dns.controld.com") &&
                uri.port in listOf(-1, 853) && uri.rawPath in listOf("", "/")) {
                uri.host.removeSuffix(".").dropLast(".dns.controld.com".length).takeIf { idPattern.matches(it) }
            } else null
        }
    } catch (_: Exception) { null }

    fun primary(id: String, type: DnsType): String {
        require(idPattern.matches(id)) { "Invalid Control D resolver ID" }
        return when (type) {
            DnsType.DOH -> "https://dns.controld.com/$id"
            DnsType.DOT -> "$id.dns.controld.com"
            DnsType.DOQ -> "quic://$id.dns.controld.com:853"
            DnsType.DEFAULT -> throw IllegalArgumentException("Personal resolvers require a secure endpoint")
        }
    }
}
