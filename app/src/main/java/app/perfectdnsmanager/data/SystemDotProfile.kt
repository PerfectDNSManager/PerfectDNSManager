package app.perfectdnsmanager.data

import app.perfectdnsmanager.util.ProfileValidation
import java.util.Locale

/** Resolve Android's actual strict-mode hostname without changing its endpoint. */
object SystemDotProfile {
    fun resolve(mode: String?, hostname: String, profiles: List<DnsProfile>): DnsProfile? {
        if (mode != "hostname" || !ProfileValidation.isHostname(hostname)) return null
        val host = hostname.trimEnd('.').lowercase(Locale.ROOT)
        profiles.firstOrNull {
            it.type == DnsType.DOT && ProfileValidation.isUsable(it) &&
                it.primary.trimEnd('.').equals(host, ignoreCase = true)
        }?.let { return it }
        val provider = when {
            host in setOf("cloudflare-dns.com", "1dot1dot1dot1.cloudflare-dns.com") -> "Cloudflare"
            host.endsWith(".dns.controld.com") -> "ControlD"
            host.endsWith(".dns.nextdns.io") -> "NextDNS"
            else -> "DoT"
        }
        return DnsProfile(providerName = provider, name = host.take(128),
            type = DnsType.DOT, primary = host, isCustom = true)
    }
}
