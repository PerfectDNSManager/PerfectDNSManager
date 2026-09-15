package app.perfectdnsmanager.data

object ProfileCatalog {
    fun visible(profiles: List<DnsProfile>, allowAdblock: Boolean, showVariants: Boolean): List<DnsProfile> {
        val eligible = profiles.filter { allowAdblock || it.isCustom || !it.isAdblock }
        if (showVariants) return eligible
        val seen = mutableSetOf<Pair<String, DnsType>>()
        return eligible.sortedBy {
            if (it.name.lowercase() in listOf("unfiltered", "unsecured", "standard", "basic") || it.name.startsWith("ns")) 0 else 1
        }.filter {
            it.isCustom || it.isOperatorDns || it.isAdblock || seen.add(it.providerName to it.type)
        }
    }
}
