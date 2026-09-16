package app.perfectdnsmanager.data

import java.net.URI

/**
 * Personal resolver addresses documented by Control D; no account credentials required.
 *
 * Control D encodes an optional device name differently per protocol:
 *  - DoH : https://dns.controld.com/<resolverId>/<device>
 *  - DoT : <resolverId>-<device>.dns.controld.com
 *  - DoQ : quic://<resolverId>-<device>.dns.controld.com
 * Resolver IDs are alphanumeric, so the first hyphen of a DoT/DoQ label is the
 * device separator. The token returned by [resolverId] uses the DoT label form
 * ("id" or "id-device"); [primary] converts it back for DoH. The previous code
 * reused the label verbatim in the DoH path (/id-device), which is not a valid
 * Control D endpoint, and rejected the documented /id/device DoH form.
 */
object ControlDProfiles {
    private val resolverPattern = Regex("^[A-Za-z0-9]{1,63}$")
    private val devicePattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val tokenPattern = Regex("^[A-Za-z0-9]{1,63}(?:-[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)?$")

    private fun token(resolver: String, device: String?): String? {
        if (!resolverPattern.matches(resolver)) return null
        if (device == null) return resolver
        if (!devicePattern.matches(device)) return null
        return "$resolver-$device".takeIf { it.length <= 63 }
    }

    fun resolverId(input: String): String? = try {
        val value = input.trim()
        if (tokenPattern.matches(value)) value else {
            val uri = URI(if (value.contains("://")) value else "tls://$value")
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase() ?: ""
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) null
            else if (scheme == "https" && host == "dns.controld.com" && uri.port in listOf(-1, 443)) {
                val parts = uri.rawPath.removePrefix("/").removeSuffix("/").split('/')
                when (parts.size) {
                    1 -> token(parts[0], null)
                    2 -> token(parts[0], parts[1])
                    else -> null
                }
            } else if (scheme in listOf("tls", "quic") && host.endsWith(".dns.controld.com") &&
                uri.port in listOf(-1, 853) && uri.rawPath in listOf("", "/")) {
                uri.host.removeSuffix(".").dropLast(".dns.controld.com".length).takeIf { tokenPattern.matches(it) }
            } else null
        }
    } catch (_: Exception) { null }

    fun primary(id: String, type: DnsType): String {
        require(tokenPattern.matches(id)) { "Invalid Control D resolver ID" }
        return when (type) {
            DnsType.DOH -> "https://dns.controld.com/" + id.replaceFirst('-', '/')
            DnsType.DOT -> "$id.dns.controld.com"
            DnsType.DOQ -> "quic://$id.dns.controld.com:853"
            DnsType.DEFAULT -> throw IllegalArgumentException("Personal resolvers require a secure endpoint")
        }
    }
}
