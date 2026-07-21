package app.perfectdnsmanager.data

import android.content.Context
import app.perfectdnsmanager.R

data class DnsProfile(
    val id: Long = System.currentTimeMillis(),
    val providerName: String,
    val name: String,
    val type: DnsType,
    val primary: String,
    val secondary: String? = null,
    val primaryV6: String? = null,
    val secondaryV6: String? = null,
    val description: String? = null,
    /**
     * Si non-zero, identifie une string ressource (R.string.dns_desc_*) qui
     * remplace [description] à l'affichage. Permet de localiser les profils
     * built-in sans casser la sérialisation Gson des profils sauvegardés
     * (pour lesquels descResId == 0 et description est utilisée).
     * isp_*  -> nom de l'opérateur passé via [descResIdArg].
     */
    val descResId: Int = 0,
    val descResIdArg: String? = null,
    val isCustom: Boolean = false,
    val testUrl: String? = null,
    val isOperatorDns: Boolean = false
) {
    /**
     * Description localisée. Si [descResId] est défini, on récupère la string
     * et on lui ajoute le suffixe (DoH/DoQ/DoT) selon [type] — ça évite 50+
     * keys séparées pour chaque variante. Si [descResIdArg] est non-null, on
     * formate avec cet argument (utilisé pour les ISP DNS où le nom de
     * l'opérateur passe en %1$s). Fallback sur [description] si descResId=0.
     */
    fun displayDescription(context: Context): String? {
        if (descResId == 0) return description
        val base = if (descResIdArg != null)
            context.getString(descResId, descResIdArg)
        else
            context.getString(descResId)
        // Pas de suffixe sur les types DEFAULT (UDP cleartext) — la variante
        // est implicite par la liste UI.
        val suffix = when (type) {
            DnsType.DOH -> "DoH"
            DnsType.DOT -> "DoT"
            DnsType.DOQ -> "DoQ"
            else -> null
        }
        return if (suffix != null)
            context.getString(R.string.dns_desc_with_type_fmt, base, suffix)
        else base
    }

    companion object {
        data class ProviderRating(val speed: Int, val privacy: Int)

        val providerRatings = mapOf(
            "ControlD"    to ProviderRating(speed = 5, privacy = 5),
            "Mullvad"     to ProviderRating(speed = 5, privacy = 5),
            "NextDNS"     to ProviderRating(speed = 4, privacy = 5),
            "Quad9"       to ProviderRating(speed = 5, privacy = 4),
            "dns.sb"      to ProviderRating(speed = 3, privacy = 5),
            "Surfshark"   to ProviderRating(speed = 3, privacy = 4),
            "AdGuard"     to ProviderRating(speed = 5, privacy = 4),
            "Cloudflare"  to ProviderRating(speed = 5, privacy = 5),
            "FDN"         to ProviderRating(speed = 3, privacy = 5),
            "Yandex"      to ProviderRating(speed = 4, privacy = 3),
            "Google"      to ProviderRating(speed = 5, privacy = 5)
        )
        fun getProviderIcon(providerName: String): Int = when {
            providerName.contains("AdGuard", true) -> R.drawable.ic_adguard
            providerName.contains("Cloudflare", true) -> R.drawable.ic_cloudflare
            providerName.contains("ControlD", true) -> R.drawable.ic_controld
            providerName.contains("dns.sb", true) -> R.drawable.ic_dnssb
            providerName.contains("Google", true) -> R.drawable.ic_google
            providerName.contains("Mullvad", true) -> R.drawable.ic_mullvad
            providerName.contains("NextDNS", true) -> R.drawable.ic_nextdns
            providerName.contains("Quad9", true) -> R.drawable.ic_quad9
            providerName.contains("Surfshark", true) -> R.drawable.ic_surfshark
            providerName.contains("Orange", true) -> R.drawable.ic_orange
            providerName.contains("Free", true) -> R.drawable.ic_free
            providerName.contains("SFR", true) -> R.drawable.ic_sfr
            providerName.contains("Bouygues", true) -> R.drawable.ic_bouygues
            providerName.contains("FDN", true) -> R.drawable.ic_fdn
            providerName.contains("OVH", true) -> R.drawable.ic_ovh
            providerName.contains("Yandex", true) -> R.drawable.ic_yandex
            else -> R.drawable.ic_dns_custom
        }

        /**
         * Liste des profils built-in. Construite une seule fois (lazy) puis
         * réutilisée : [getDefaultPresets] réallouait ~100 objets à chaque appel
         * (appelé souvent : ProfileManager, speedtest, etc.). La liste est
         * immuable ([listOf]) et aucun appelant ne la mute (vérifié), donc on
         * peut partager la même instance sans copie défensive.
         */
        private val defaultPresets: List<DnsProfile> by lazy {
            listOf(

            // ══════════════════════════════════════════════════════
            //  1. ControlD  ★ DoQ
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1230, providerName = "ControlD", name = "Unfiltered", type = DnsType.DOQ,
                primary = "quic://freedns.controld.com/p0", descResId = R.string.dns_desc_unfiltered),
            DnsProfile(id = 1231, providerName = "ControlD", name = "Malware", type = DnsType.DOQ,
                primary = "quic://freedns.controld.com/p1", descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1232, providerName = "ControlD", name = "Ads & Tracking", type = DnsType.DOQ,
                primary = "quic://freedns.controld.com/p2", descResId = R.string.dns_desc_ad_blocker),
            DnsProfile(id = 1033, providerName = "ControlD", name = "Unfiltered", type = DnsType.DOH,
                primary = "https://freedns.controld.com/p0", descResId = R.string.dns_desc_unfiltered),
            DnsProfile(id = 1034, providerName = "ControlD", name = "Malware", type = DnsType.DOH,
                primary = "https://freedns.controld.com/p1", descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1035, providerName = "ControlD", name = "Ads & Tracking", type = DnsType.DOH,
                primary = "https://freedns.controld.com/p2", descResId = R.string.dns_desc_ad_blocker),
            DnsProfile(id = 1036, providerName = "ControlD", name = "Unfiltered", type = DnsType.DEFAULT,
                primary = "76.76.2.0", secondary = "76.76.10.0",
                primaryV6 = "2606:1a40::0", secondaryV6 = "2606:1a40:1::0",
                descResId = R.string.dns_desc_unfiltered),
            DnsProfile(id = 1037, providerName = "ControlD", name = "Malware", type = DnsType.DEFAULT,
                primary = "76.76.2.1", secondary = "76.76.10.1",
                primaryV6 = "2606:1a40::1", secondaryV6 = "2606:1a40:1::1",
                descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1038, providerName = "ControlD", name = "Ads & Tracking", type = DnsType.DEFAULT,
                primary = "76.76.2.2", secondary = "76.76.10.2",
                primaryV6 = "2606:1a40::2", secondaryV6 = "2606:1a40:1::2",
                descResId = R.string.dns_desc_ad_blocker),
            DnsProfile(id = 1030, providerName = "ControlD", name = "Unfiltered", type = DnsType.DOT,
                primary = "p0.freedns.controld.com", descResId = R.string.dns_desc_unfiltered),
            DnsProfile(id = 1031, providerName = "ControlD", name = "Malware", type = DnsType.DOT,
                primary = "p1.freedns.controld.com", descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1032, providerName = "ControlD", name = "Ads & Tracking", type = DnsType.DOT,
                primary = "p2.freedns.controld.com", descResId = R.string.dns_desc_ad_blocker),

            // ══════════════════════════════════════════════════════
            //  2. NextDNS  ★ DoQ
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1220, providerName = "NextDNS", name = "Standard", type = DnsType.DOQ,
                primary = "quic://dns.nextdns.io",
                descResId = R.string.dns_desc_no_custom_profile, testUrl = "https://test.nextdns.io/"),
            DnsProfile(id = 1070, providerName = "NextDNS", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.nextdns.io",
                descResId = R.string.dns_desc_no_custom_profile, testUrl = "https://test.nextdns.io/"),
            DnsProfile(id = 1072, providerName = "NextDNS", name = "Standard", type = DnsType.DEFAULT,
                primary = "45.90.28.0", secondary = "45.90.30.0",
                primaryV6 = "2a07:a8c0::", secondaryV6 = "2a07:a8c1::",
                descResId = R.string.dns_desc_no_custom_profile, testUrl = "https://test.nextdns.io/"),
            DnsProfile(id = 1071, providerName = "NextDNS", name = "Standard", type = DnsType.DOT,
                primary = "dns.nextdns.io",
                descResId = R.string.dns_desc_no_custom_profile, testUrl = "https://test.nextdns.io/"),

            // ══════════════════════════════════════════════════════
            //  3. AdGuard  ★ DoQ — Unfiltered en premier
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1201, providerName = "AdGuard", name = "Unfiltered", type = DnsType.DOQ,
                primary = "quic://unfiltered.adguard-dns.com",
                descResId = R.string.dns_desc_unfiltered, testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1200, providerName = "AdGuard", name = "Standard", type = DnsType.DOQ,
                primary = "quic://dns.adguard-dns.com",
                descResId = R.string.dns_desc_blocks_ads_trackers, testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1202, providerName = "AdGuard", name = "Family", type = DnsType.DOQ,
                primary = "quic://family.adguard-dns.com",
                descResId = R.string.dns_desc_family_protection, testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1000, providerName = "AdGuard", name = "Unfiltered", type = DnsType.DOH,
                primary = "https://unfiltered.adguard-dns.com/dns-query",
                descResId = R.string.dns_desc_unfiltered, testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1001, providerName = "AdGuard", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.adguard-dns.com/dns-query",
                descResId = R.string.dns_desc_blocks_ads_trackers, testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1004, providerName = "AdGuard", name = "Unfiltered", type = DnsType.DEFAULT,
                primary = "94.140.14.140", secondary = "94.140.14.141",
                primaryV6 = "2a10:50c0::1:ff", secondaryV6 = "2a10:50c0::2:ff",
                descResId = R.string.dns_desc_unfiltered),
            DnsProfile(id = 1002, providerName = "AdGuard", name = "Standard", type = DnsType.DEFAULT,
                primary = "94.140.14.14", secondary = "94.140.15.15",
                primaryV6 = "2a10:50c0::ad1:ff", secondaryV6 = "2a10:50c0::ad2:ff",
                descResId = R.string.dns_desc_blocks_ads_trackers, testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1005, providerName = "AdGuard", name = "Family", type = DnsType.DEFAULT,
                primary = "94.140.14.15", secondary = "94.140.15.16",
                primaryV6 = "2a10:50c0::bad1:ff", secondaryV6 = "2a10:50c0::bad2:ff",
                descResId = R.string.dns_desc_family_protection),
            DnsProfile(id = 1006, providerName = "AdGuard", name = "Unfiltered", type = DnsType.DOT,
                primary = "unfiltered.adguard-dns.com",
                descResId = R.string.dns_desc_unfiltered, testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1003, providerName = "AdGuard", name = "Standard", type = DnsType.DOT,
                primary = "dns.adguard-dns.com",
                descResId = R.string.dns_desc_blocks_ads_trackers, testUrl = "https://adguard.com/test.html"),

            // ══════════════════════════════════════════════════════
            //  4. Surfshark  ★ DoQ
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1240, providerName = "Surfshark", name = "Standard", type = DnsType.DOQ,
                primary = "quic://dns.surfsharkdns.com",
                descResId = R.string.dns_desc_no_logs),
            DnsProfile(id = 1101, providerName = "Surfshark", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.surfsharkdns.com/dns-query",
                descResId = R.string.dns_desc_no_logs),
            DnsProfile(id = 1100, providerName = "Surfshark", name = "Standard", type = DnsType.DEFAULT,
                primary = "194.169.169.169",
                primaryV6 = "2a09:a707:169::",
                descResId = R.string.dns_desc_no_logs),
            DnsProfile(id = 1102, providerName = "Surfshark", name = "Standard", type = DnsType.DOT,
                primary = "dns.surfsharkdns.com", descResId = R.string.dns_desc_no_logs),

            // ══════════════════════════════════════════════════════
            //  5. Mullvad  ★ DoQ
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1210, providerName = "Mullvad", name = "Standard", type = DnsType.DOQ,
                primary = "quic://dns.mullvad.net",
                descResId = R.string.dns_desc_privacy),
            DnsProfile(id = 1211, providerName = "Mullvad", name = "Adblock", type = DnsType.DOQ,
                primary = "quic://adblock.dns.mullvad.net",
                descResId = R.string.dns_desc_ad_blocker),
            DnsProfile(id = 1212, providerName = "Mullvad", name = "Base", type = DnsType.DOQ,
                primary = "quic://base.dns.mullvad.net",
                descResId = R.string.dns_desc_ad_malware_blocker),
            DnsProfile(id = 1060, providerName = "Mullvad", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.mullvad.net/dns-query",
                descResId = R.string.dns_desc_privacy),
            DnsProfile(id = 1063, providerName = "Mullvad", name = "Adblock", type = DnsType.DOH,
                primary = "https://adblock.dns.mullvad.net/dns-query",
                descResId = R.string.dns_desc_ad_blocker),
            DnsProfile(id = 1064, providerName = "Mullvad", name = "Base", type = DnsType.DOH,
                primary = "https://base.dns.mullvad.net/dns-query",
                descResId = R.string.dns_desc_ad_malware_blocker),
            DnsProfile(id = 1062, providerName = "Mullvad", name = "Standard", type = DnsType.DOT,
                primary = "dns.mullvad.net", descResId = R.string.dns_desc_privacy),
            DnsProfile(id = 1065, providerName = "Mullvad", name = "Base", type = DnsType.DOT,
                primary = "base.dns.mullvad.net", descResId = R.string.dns_desc_ad_malware_blocker),

            // ══════════════════════════════════════════════════════
            //  6. Cloudflare
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1010, providerName = "Cloudflare", name = "Standard", type = DnsType.DOH,
                primary = "https://one.one.one.one/dns-query",
                descResId = R.string.dns_desc_fast_private2, testUrl = "https://one.one.one.one/help/"),
            DnsProfile(id = 1011, providerName = "Cloudflare", name = "Standard", type = DnsType.DEFAULT,
                primary = "1.1.1.1", secondary = "1.0.0.1",
                primaryV6 = "2606:4700:4700::1111", secondaryV6 = "2606:4700:4700::1001",
                descResId = R.string.dns_desc_fast_private2, testUrl = "https://one.one.one.one/help/"),
            DnsProfile(id = 1012, providerName = "Cloudflare", name = "Standard", type = DnsType.DOT,
                primary = "one.one.one.one",
                descResId = R.string.dns_desc_fast_private2, testUrl = "https://one.one.one.one/help/"),
            DnsProfile(id = 1013, providerName = "Cloudflare", name = "Malware Blocking", type = DnsType.DEFAULT,
                primary = "1.1.1.2", secondary = "1.0.0.2",
                primaryV6 = "2606:4700:4700::1112", secondaryV6 = "2606:4700:4700::1002",
                descResId = R.string.dns_desc_enhanced_security),
            DnsProfile(id = 1014, providerName = "Cloudflare", name = "Family", type = DnsType.DEFAULT,
                primary = "1.1.1.3", secondary = "1.0.0.3",
                primaryV6 = "2606:4700:4700::1113", secondaryV6 = "2606:4700:4700::1003",
                descResId = R.string.dns_desc_adult_blocking),

            // ══════════════════════════════════════════════════════
            //  7. Quad9 — Standard (Anti-Malware) en premier
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1080, providerName = "Quad9", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.quad9.net/dns-query",
                descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1084, providerName = "Quad9", name = "Unsecured", type = DnsType.DOH,
                primary = "https://dns10.quad9.net/dns-query",
                descResId = R.string.dns_desc_unfiltered),
            DnsProfile(id = 1081, providerName = "Quad9", name = "Standard", type = DnsType.DEFAULT,
                primary = "9.9.9.9", secondary = "149.112.112.112",
                primaryV6 = "2620:fe::fe", secondaryV6 = "2620:fe::9",
                descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1082, providerName = "Quad9", name = "Unsecured", type = DnsType.DEFAULT,
                primary = "9.9.9.10", secondary = "149.112.112.10",
                primaryV6 = "2620:fe::10", secondaryV6 = "2620:fe::fe:10",
                descResId = R.string.dns_desc_unfiltered),
            DnsProfile(id = 1083, providerName = "Quad9", name = "Standard", type = DnsType.DOT,
                primary = "dns.quad9.net", descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1085, providerName = "Quad9", name = "Unsecured", type = DnsType.DOT,
                primary = "dns10.quad9.net", descResId = R.string.dns_desc_unfiltered),

            // ══════════════════════════════════════════════════════
            //  8. FDN (French Data Network)
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1315, providerName = "FDN", name = "ns0", type = DnsType.DOH,
                primary = "https://ns0.fdn.fr/dns-query",
                descResId = R.string.dns_desc_neutral_dnssec),
            DnsProfile(id = 1316, providerName = "FDN", name = "ns1", type = DnsType.DOH,
                primary = "https://ns1.fdn.fr/dns-query",
                descResId = R.string.dns_desc_neutral_dnssec),
            DnsProfile(id = 1319, providerName = "FDN", name = "ns0", type = DnsType.DEFAULT,
                primary = "80.67.169.12",
                primaryV6 = "2001:910:800::12",
                descResId = R.string.dns_desc_neutral_dnssec),
            DnsProfile(id = 1320, providerName = "FDN", name = "ns1", type = DnsType.DEFAULT,
                primary = "80.67.169.40",
                primaryV6 = "2001:910:800::40",
                descResId = R.string.dns_desc_neutral_dnssec),
            DnsProfile(id = 1317, providerName = "FDN", name = "ns0", type = DnsType.DOT,
                primary = "ns0.fdn.fr",
                descResId = R.string.dns_desc_neutral_dnssec),
            DnsProfile(id = 1318, providerName = "FDN", name = "ns1", type = DnsType.DOT,
                primary = "ns1.fdn.fr",
                descResId = R.string.dns_desc_neutral_dnssec),

            // ══════════════════════════════════════════════════════
            //  9. dns.sb
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1040, providerName = "dns.sb", name = "Standard", type = DnsType.DOH,
                primary = "https://doh.dns.sb/dns-query", descResId = R.string.dns_desc_no_logs),
            DnsProfile(id = 1041, providerName = "dns.sb", name = "Standard", type = DnsType.DEFAULT,
                primary = "185.222.222.222", secondary = "45.11.45.11",
                primaryV6 = "2a09::", secondaryV6 = "2a11::",
                descResId = R.string.dns_desc_no_logs),
            DnsProfile(id = 1042, providerName = "dns.sb", name = "Standard", type = DnsType.DOT,
                primary = "dot.sb", descResId = R.string.dns_desc_no_logs),

            // ══════════════════════════════════════════════════════
            //  9. Yandex DNS
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1110, providerName = "Yandex", name = "Basic", type = DnsType.DOH,
                primary = "https://common.dot.dns.yandex.net/dns-query",
                descResId = R.string.dns_desc_fast),
            DnsProfile(id = 1111, providerName = "Yandex", name = "Safe", type = DnsType.DOH,
                primary = "https://safe.dot.dns.yandex.net/dns-query",
                descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1112, providerName = "Yandex", name = "Family", type = DnsType.DOH,
                primary = "https://family.dot.dns.yandex.net/dns-query",
                descResId = R.string.dns_desc_family_protection),
            DnsProfile(id = 1113, providerName = "Yandex", name = "Basic", type = DnsType.DEFAULT,
                primary = "77.88.8.8", secondary = "77.88.8.1",
                primaryV6 = "2a02:6b8::feed:0ff", secondaryV6 = "2a02:6b8:0:1::feed:0ff",
                descResId = R.string.dns_desc_fast),
            DnsProfile(id = 1114, providerName = "Yandex", name = "Safe", type = DnsType.DEFAULT,
                primary = "77.88.8.88", secondary = "77.88.8.2",
                primaryV6 = "2a02:6b8::feed:bad", secondaryV6 = "2a02:6b8:0:1::feed:bad",
                descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1115, providerName = "Yandex", name = "Family", type = DnsType.DEFAULT,
                primary = "77.88.8.7", secondary = "77.88.8.3",
                primaryV6 = "2a02:6b8::feed:a11", secondaryV6 = "2a02:6b8:0:1::feed:a11",
                descResId = R.string.dns_desc_family_protection),
            DnsProfile(id = 1116, providerName = "Yandex", name = "Basic", type = DnsType.DOT,
                primary = "common.dot.dns.yandex.net",
                descResId = R.string.dns_desc_fast),
            DnsProfile(id = 1117, providerName = "Yandex", name = "Safe", type = DnsType.DOT,
                primary = "safe.dot.dns.yandex.net",
                descResId = R.string.dns_desc_anti_malware),
            DnsProfile(id = 1118, providerName = "Yandex", name = "Family", type = DnsType.DOT,
                primary = "family.dot.dns.yandex.net",
                descResId = R.string.dns_desc_family_protection),

            // ══════════════════════════════════════════════════════
            //  10. Google  (en dernier)
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1050, providerName = "Google", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.google/dns-query", descResId = R.string.dns_desc_fast),
            DnsProfile(id = 1051, providerName = "Google", name = "Standard", type = DnsType.DEFAULT,
                primary = "8.8.8.8", secondary = "8.8.4.4",
                primaryV6 = "2001:4860:4860::8888", secondaryV6 = "2001:4860:4860::8844",
                descResId = R.string.dns_desc_fast),
            DnsProfile(id = 1052, providerName = "Google", name = "Standard", type = DnsType.DOT,
                primary = "dns.google", descResId = R.string.dns_desc_fast),

            // ══════════════════════════════════════════════════════
            //  DNS Opérateur FR  (cachés sauf toggle dédié)
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 2001, providerName = "🇫🇷 Orange", name = "DNS Orange", type = DnsType.DEFAULT,
                primary = "80.10.246.2", secondary = "80.10.246.129",
                descResId = R.string.dns_desc_isp_fmt, descResIdArg = "Orange", isOperatorDns = true),
            DnsProfile(id = 2002, providerName = "🇫🇷 Free", name = "DNS Free", type = DnsType.DEFAULT,
                primary = "212.27.40.240", secondary = "212.27.40.241",
                descResId = R.string.dns_desc_isp_fmt, descResIdArg = "Free", isOperatorDns = true),
            DnsProfile(id = 2003, providerName = "🇫🇷 SFR", name = "DNS SFR", type = DnsType.DEFAULT,
                primary = "109.0.66.10", secondary = "109.0.66.20",
                descResId = R.string.dns_desc_isp_fmt, descResIdArg = "SFR", isOperatorDns = true),
            DnsProfile(id = 2004, providerName = "🇫🇷 Bouygues", name = "DNS Bouygues", type = DnsType.DEFAULT,
                primary = "194.158.122.10", secondary = "194.158.122.15",
                descResId = R.string.dns_desc_isp_fmt, descResIdArg = "Bouygues Telecom", isOperatorDns = true),
            DnsProfile(id = 2005, providerName = "🇫🇷 OVH", name = "DNS OVH", type = DnsType.DEFAULT,
                primary = "213.186.33.99", secondary = "213.251.128.140",
                descResId = R.string.dns_desc_isp_fmt, descResIdArg = "OVH/OVHcloud", isOperatorDns = true)
            )
        }

        fun getDefaultPresets(): List<DnsProfile> = defaultPresets
    }
}
