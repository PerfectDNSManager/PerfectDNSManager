package app.perfectdnsmanager.util

import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType
import java.net.URI
import java.util.Locale

object ProfileValidation {
    private val HOSTNAME_RE = Regex("^(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+\\.?$")
    private val IPV4_RE = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    fun isHostname(raw: String): Boolean = HOSTNAME_RE.matches(raw) && !IPV4_RE.matches(raw)
    fun isIpv4(raw: String): Boolean = IPV4_RE.matches(raw)
    fun normalizeEndpoint(raw: String): String = if (raw.contains("://"))
        raw.substringBefore("://").lowercase(Locale.ROOT) + "://" + raw.substringAfter("://") else raw
    fun isHttpsUrl(raw: String): Boolean = validUrl(raw, "https")
    private fun validUrl(raw: String, scheme: String): Boolean = try {
        val uri = URI(raw)
        raw.length <= 2048 && uri.scheme.equals(scheme, true) &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.host != null && (isHostname(uri.host) || isIpv4(uri.host)) &&
            (uri.port == -1 || uri.port in 1..65535)
    } catch (_: Exception) { false }
    fun isValidPrimary(type: DnsType, raw: String): Boolean = when (type) {
        DnsType.DOH -> validUrl(raw, "https")
        DnsType.DOQ -> validUrl(raw, "quic")
        DnsType.DOT -> isHostname(raw)
        DnsType.DEFAULT -> isIpv4(raw)
    }
    fun isUsable(profile: DnsProfile?): Boolean {
        if (profile == null) return false
        @Suppress("SENSELESS_COMPARISON")
        if (profile.type == null || profile.primary == null || profile.providerName == null || profile.name == null) return false
        return isValidPrimary(profile.type, profile.primary) &&
            (profile.secondary.isNullOrBlank() || isValidPrimary(profile.type, profile.secondary))
    }
    fun endpointsOf(profile: DnsProfile): String = listOfNotNull(profile.primary, profile.secondary?.takeIf { it.isNotBlank() }).joinToString(", ")
}
