package app.perfectdnsmanager

import app.perfectdnsmanager.data.ConfigValidation
import app.perfectdnsmanager.data.DnsType
import app.perfectdnsmanager.util.ProfileValidation
import org.junit.Assert.*
import org.junit.Test

class ConfigValidationTest {
    private fun reject(json: String) {
        try { ConfigValidation.parse(json); fail("accepted invalid input") } catch (_: IllegalArgumentException) {}
    }
    @Test fun rejectsNullRulesAndWrongShapes() {
        reject("""{"rewriteRules":[null]}""")
        reject("""{"rewriteRules":[{"id":{},"fromDomain":"a.example","toDomain":"b.example","isEnabled":true}]}""")
        reject("""{"rewriteRules":{}}""")
        reject("""{"rewriteRules":[{"fromDomain":"valid.example","toDomain":null,"isEnabled":true}]}""")
        reject("""{"excludedApps":[null]}""")
        reject("""{"settings":{"disable_ipv6":"false"}}""")
    }
    @Test fun removesResourceIdsAndNormalizesScheme() {
        val root = ConfigValidation.parse("""{"selectedProfile":{"id":12345,"providerName":"test","name":"test","type":"DOH","primary":"HTTPS://dns.example/dns-query","descResId":2147483647}}""")
        val p = root.getAsJsonObject("selectedProfile")
        assertEquals(0, p.get("descResId").asInt)
        assertEquals("https://dns.example/dns-query", p.get("primary").asString)
        assertTrue(p.get("isCustom").asBoolean)
        assertFalse(root.has("profiles"))
    }
    @Test fun rejectsOversizeAndDeepDocuments() {
        reject("[".repeat(17) + "0" + "]".repeat(17))
        reject(" ".repeat(1024 * 1024 + 1))
    }
    @Test fun validatesFullUrls() {
        assertFalse(ProfileValidation.isValidPrimary(DnsType.DOH, "https://good.example:443@evil.example/x"))
        assertFalse(ProfileValidation.isValidPrimary(DnsType.DOH, "https://dns.example:99999/x"))
        assertFalse(ProfileValidation.isValidPrimary(DnsType.DOH, "https://dns.example/#secret"))
        assertTrue(ProfileValidation.isValidPrimary(DnsType.DOH, "https://1.1.1.1/dns-query"))
    }
    @Test fun migratesLegacyNextDnsDoqAndRejectsIgnoredPaths() {
        val old = "quic://dns.nextdns.io/abc123"
        assertEquals("quic://abc123.dns.nextdns.io", ProfileValidation.normalizeEndpoint(old))
        assertTrue(ProfileValidation.isValidPrimary(DnsType.DOQ, old))
        assertFalse(ProfileValidation.isValidPrimary(DnsType.DOQ, "quic://dns.example/ignored"))
        assertFalse(ProfileValidation.isValidPrimary(DnsType.DOQ, "quic://dns.example?profile=ignored"))
        val p = ConfigValidation.parse("""{"selectedProfile":{"id":12345,"providerName":"NextDNS","name":"Personal","type":"DOQ","primary":"quic://dns.nextdns.io/abc123"}}""").getAsJsonObject("selectedProfile")
        assertEquals("quic://abc123.dns.nextdns.io", p.get("primary").asString)
    }
    @Test fun rejectsInvalidSecondaryAndIpv6() {
        val base = app.perfectdnsmanager.data.DnsProfile(providerName="test",name="test",type=DnsType.DEFAULT,primary="1.1.1.1")
        assertFalse(ProfileValidation.isUsable(base.copy(secondary="999.1.1.1")))
        assertFalse(ProfileValidation.isUsable(base.copy(primaryV6="not-an-ip")))
        assertFalse(ProfileValidation.isUsable(base.copy(primaryV6="fe80::1%eth0")))
        assertTrue(ProfileValidation.isUsable(base.copy(primaryV6="2606:4700:4700::1111")))
        assertFalse(ProfileValidation.isUsable(base.copy(name="x".repeat(129))))
    }
}
