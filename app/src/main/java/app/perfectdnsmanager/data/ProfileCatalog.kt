package app.perfectdnsmanager.data

object ProfileCatalog {
    val providerOrder = listOf("Cloudflare", "ControlD", "dns.sb", "NextDNS", "AdGuard",
        "xdp.es", "Digitale Gesellschaft", "FDN", "Yandex", "Google")
    private val retiredPresetIds = setOf(1080L, 1084L, 1081L, 1082L, 1083L, 1085L, 1240L, 1101L, 1100L, 1102L, 1210L, 1211L, 1212L, 1060L, 1063L, 1064L, 1062L, 1065L, 1620L, 1621L, 1622L, 1623L, 1624L, 1625L, 1626L)
    fun isRetiredPreset(profile: DnsProfile): Boolean = !profile.isCustom && profile.id in retiredPresetIds

    fun visible(profiles: List<DnsProfile>, allowAdblock: Boolean, showVariants: Boolean): List<DnsProfile> {
        val eligible = profiles.filter { !isRetiredPreset(it) && (allowAdblock || it.isCustom || !it.isAdblock) }
        if (showVariants) return eligible
        val seen = mutableSetOf<Pair<String, DnsType>>()
        return eligible.sortedBy {
            if (it.name.lowercase() in listOf("unfiltered", "unsecured", "standard", "basic") || it.name.startsWith("ns")) 0 else 1
        }.filter {
            it.isCustom || it.isOperatorDns || it.isAdblock || seen.add(it.providerName to it.type)
        }
    }
}
