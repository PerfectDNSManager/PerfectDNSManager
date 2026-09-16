package app.perfectdnsmanager.util

import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType
import java.net.URI
import java.util.Locale

object ProfileValidation {
    private val HOSTNAME_RE = Regex("^(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+\\.?$")
    private val IPV4_RE = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val LEGACY_DOQ_PATHS = listOf(
        Regex("^quic://dns\\.nextdns\\.io/([A-Za-z0-9-]{1,63})/?$") to "dns.nextdns.io",
        Regex("^quic://freedns\\.controld\\.com/([A-Za-z0-9-]{1,63})/?$") to "freedns.controld.com",
        Regex("^quic://dns\\.controld\\.com/([A-Za-z0-9-]{1,63})/?$") to "dns.controld.com",
    )
    // Le dernier label (TLD) doit contenir une lettre : aucun TLD n'est
    // numérique, et sans ce contrôle « 1.1.1 » passait pour un nom d'hôte.
    fun isHostname(raw: String): Boolean = HOSTNAME_RE.matches(raw) && !IPV4_RE.matches(raw) &&
        raw.trimEnd('.').substringAfterLast('.').any { it.isLetter() }
    fun isIpv4(raw: String): Boolean = IPV4_RE.matches(raw)
    fun normalizeEndpoint(raw: String): String {
        val value = if (raw.contains("://")) raw.substringBefore("://").lowercase(Locale.ROOT) + "://" + raw.substringAfter("://") else raw
        // Migrate historic DoQ profile URLs: QUIC has no HTTP path to carry an ID,
        // so the ID moves into the hostname. 2.3.2 shipped ControlD presets as
        // quic://freedns.controld.com/p0 — without this migration a user who had
        // one selected could no longer start the VPN after updating.
        for ((re, host) in LEGACY_DOQ_PATHS) {
            val m = re.matchEntire(value) ?: continue
            return "quic://${m.groupValues[1]}.$host"
        }
        return value
    }
    fun isIpv6(raw: String): Boolean = raw.length <= 45 && raw.contains(':') &&
        raw.all { it in "0123456789abcdefABCDEF:." } &&
        runCatching { java.net.InetAddress.getByName(raw) is java.net.Inet6Address }.getOrDefault(false)
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
        DnsType.DOQ -> try {
            val normalized = normalizeEndpoint(raw)
            val uri = URI(normalized)
            validUrl(normalized, "quic") && uri.rawPath in listOf("", "/") && uri.rawQuery == null
        } catch (_: Exception) { false }
        DnsType.DOT -> isHostname(raw)
        DnsType.DEFAULT -> isIpv4(raw)
    }
    fun isUsable(profile: DnsProfile?): Boolean {
        if (profile == null) return false
        @Suppress("SENSELESS_COMPARISON")
        if (profile.type == null || profile.primary == null || profile.providerName == null || profile.name == null) return false
        return profile.name.isNotBlank() && profile.name.length <= 128 &&
            profile.providerName.isNotBlank() && profile.providerName.length <= 128 &&
            listOfNotNull(profile.primaryV6, profile.secondaryV6).all { it.isBlank() || isIpv6(it) } &&
            isValidPrimary(profile.type, profile.primary) &&
            (profile.secondary.isNullOrBlank() || isValidPrimary(profile.type, profile.secondary))
    }
    fun endpointsOf(profile: DnsProfile): String = listOfNotNull(profile.primary, profile.secondary?.takeIf { it.isNotBlank() }).joinToString(", ")
}
