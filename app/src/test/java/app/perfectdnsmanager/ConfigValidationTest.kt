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
}
