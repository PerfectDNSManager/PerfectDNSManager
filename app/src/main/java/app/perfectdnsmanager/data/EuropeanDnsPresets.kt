package app.perfectdnsmanager.data

import app.perfectdnsmanager.R

// Official endpoints verified 2026-11-27: dnsforge.de and joindns4.eu/for-public.
internal object EuropeanDnsPresets {
    val profiles: List<DnsProfile> = listOf(
        DnsProfile(id = 1900, providerName = "DNSForge", name = "Standard", type = DnsType.DOH, primary = "https://blank.dnsforge.de/dns-query", isAdblock = false, descResId = R.string.dns_desc_unfiltered),
        DnsProfile(id = 1901, providerName = "DNSForge", name = "Standard", type = DnsType.DOT, primary = "blank.dnsforge.de", isAdblock = false, descResId = R.string.dns_desc_unfiltered),
        DnsProfile(id = 1902, providerName = "DNSForge", name = "Standard", type = DnsType.DEFAULT, primary = "138.199.149.249", isAdblock = false, secondary = "78.47.71.194", primaryV6 = "2a01:4f8:c17:7aa5::249", secondaryV6 = "2a01:4f8:c013:aae9::194", descResId = R.string.dns_desc_unfiltered),
        DnsProfile(id = 1903, providerName = "DNSForge", name = "Standard", type = DnsType.DOQ, primary = "quic://blank.dnsforge.de:853", isAdblock = false, descResId = R.string.dns_desc_unfiltered),
        DnsProfile(id = 1910, providerName = "DNSForge", name = "Normal", type = DnsType.DOH, primary = "https://dnsforge.de/dns-query", isAdblock = true, descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1911, providerName = "DNSForge", name = "Normal", type = DnsType.DOT, primary = "dnsforge.de", isAdblock = true, descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1912, providerName = "DNSForge", name = "Normal", type = DnsType.DEFAULT, primary = "49.12.67.122", isAdblock = true, secondary = "91.99.154.175", primaryV6 = "2a01:4f8:c013:29d::122", secondaryV6 = "2a01:4f8:c010:8c35::175", descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1913, providerName = "DNSForge", name = "Normal", type = DnsType.DOQ, primary = "quic://dnsforge.de:853", isAdblock = true, descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1920, providerName = "DNSForge", name = "Clean", type = DnsType.DOH, primary = "https://clean.dnsforge.de/dns-query", isAdblock = true, descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1921, providerName = "DNSForge", name = "Clean", type = DnsType.DOT, primary = "clean.dnsforge.de", isAdblock = true, descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1922, providerName = "DNSForge", name = "Clean", type = DnsType.DEFAULT, primary = "49.12.223.2", isAdblock = true, secondary = "49.12.43.208", primaryV6 = "2a01:4f8:c17:4fbc::2", secondaryV6 = "2a01:4f8:c012:ed89::208", descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1923, providerName = "DNSForge", name = "Clean", type = DnsType.DOQ, primary = "quic://clean.dnsforge.de:853", isAdblock = true, descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1930, providerName = "DNSForge", name = "Hard", type = DnsType.DOH, primary = "https://hard.dnsforge.de/dns-query", isAdblock = true, descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1931, providerName = "DNSForge", name = "Hard", type = DnsType.DOT, primary = "hard.dnsforge.de", isAdblock = true, descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1932, providerName = "DNSForge", name = "Hard", type = DnsType.DEFAULT, primary = "49.12.222.213", isAdblock = true, secondary = "88.198.122.154", primaryV6 = "2a01:4f8:c17:2c61::213", secondaryV6 = "2a01:4f8:c013:5ec0::154", descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1933, providerName = "DNSForge", name = "Hard", type = DnsType.DOQ, primary = "quic://hard.dnsforge.de:853", isAdblock = true, descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1940, providerName = "DNS4EU", name = "Standard", type = DnsType.DOH, primary = "https://unfiltered.joindns4.eu/dns-query", isAdblock = false, descResId = R.string.dns_desc_unfiltered),
        DnsProfile(id = 1941, providerName = "DNS4EU", name = "Standard", type = DnsType.DOT, primary = "unfiltered.joindns4.eu", isAdblock = false, descResId = R.string.dns_desc_unfiltered),
        DnsProfile(id = 1942, providerName = "DNS4EU", name = "Standard", type = DnsType.DEFAULT, primary = "86.54.11.100", isAdblock = false, secondary = "86.54.11.200", primaryV6 = "2a13:1001::86:54:11:100", secondaryV6 = "2a13:1001::86:54:11:200", descResId = R.string.dns_desc_unfiltered),
        DnsProfile(id = 1950, providerName = "DNS4EU", name = "Protective", type = DnsType.DOH, primary = "https://protective.joindns4.eu/dns-query", isAdblock = false, descResId = R.string.dns_desc_anti_malware),
        DnsProfile(id = 1951, providerName = "DNS4EU", name = "Protective", type = DnsType.DOT, primary = "protective.joindns4.eu", isAdblock = false, descResId = R.string.dns_desc_anti_malware),
        DnsProfile(id = 1952, providerName = "DNS4EU", name = "Protective", type = DnsType.DEFAULT, primary = "86.54.11.1", isAdblock = false, secondary = "86.54.11.201", primaryV6 = "2a13:1001::86:54:11:1", secondaryV6 = "2a13:1001::86:54:11:201", descResId = R.string.dns_desc_anti_malware),
        DnsProfile(id = 1960, providerName = "DNS4EU", name = "Child", type = DnsType.DOH, primary = "https://child.joindns4.eu/dns-query", isAdblock = false, descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1961, providerName = "DNS4EU", name = "Child", type = DnsType.DOT, primary = "child.joindns4.eu", isAdblock = false, descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1962, providerName = "DNS4EU", name = "Child", type = DnsType.DEFAULT, primary = "86.54.11.12", isAdblock = false, secondary = "86.54.11.212", primaryV6 = "2a13:1001::86:54:11:12", secondaryV6 = "2a13:1001::86:54:11:212", descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1970, providerName = "DNS4EU", name = "Adblock", type = DnsType.DOH, primary = "https://noads.joindns4.eu/dns-query", isAdblock = true, descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1971, providerName = "DNS4EU", name = "Adblock", type = DnsType.DOT, primary = "noads.joindns4.eu", isAdblock = true, descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1972, providerName = "DNS4EU", name = "Adblock", type = DnsType.DEFAULT, primary = "86.54.11.13", isAdblock = true, secondary = "86.54.11.213", primaryV6 = "2a13:1001::86:54:11:13", secondaryV6 = "2a13:1001::86:54:11:213", descResId = R.string.dns_desc_ad_blocker),
        DnsProfile(id = 1980, providerName = "DNS4EU", name = "Child + Adblock", type = DnsType.DOH, primary = "https://child-noads.joindns4.eu/dns-query", isAdblock = true, descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1981, providerName = "DNS4EU", name = "Child + Adblock", type = DnsType.DOT, primary = "child-noads.joindns4.eu", isAdblock = true, descResId = R.string.dns_desc_family_protection),
        DnsProfile(id = 1982, providerName = "DNS4EU", name = "Child + Adblock", type = DnsType.DEFAULT, primary = "86.54.11.11", isAdblock = true, secondary = "86.54.11.211", primaryV6 = "2a13:1001::86:54:11:11", secondaryV6 = "2a13:1001::86:54:11:211", descResId = R.string.dns_desc_family_protection),
    )
}
