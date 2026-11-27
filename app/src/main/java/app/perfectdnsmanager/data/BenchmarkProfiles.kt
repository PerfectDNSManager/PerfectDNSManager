package app.perfectdnsmanager.data

import app.perfectdnsmanager.util.ProfileValidation

object BenchmarkProfiles {
    fun select(profiles: List<DnsProfile>, allowAdblock: Boolean, allowOperator: Boolean, allowDot: Boolean): List<DnsProfile> =
        profiles.filter {
            ProfileValidation.isUsable(it) && !ProfileCatalog.isRetiredPreset(it) &&
                (allowAdblock || !it.isAdblock) && (allowOperator || !it.isOperatorDns) &&
                it.type != DnsType.UNBOUND && (allowDot || it.type != DnsType.DOT)
        }.distinctBy { it.type to ProfileValidation.normalizeEndpoint(it.primary) }
}
