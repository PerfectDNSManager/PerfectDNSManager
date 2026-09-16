package app.perfectdnsmanager

import app.perfectdnsmanager.data.ConfigValidation
import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType
import app.perfectdnsmanager.util.ProfileValidation
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Régressions de migration 2.3.2 → 2.4.0 trouvées en revue. */
class MigrationTest {
    @Test fun migratesLegacyControlDDoqPresets() {
        // 2.3.2 stockait les presets ControlD DoQ avec l'ID dans le chemin.
        for (id in listOf("p0", "p1", "p2")) {
            val legacy = "quic://freedns.controld.com/$id"
            assertEquals("quic://$id.freedns.controld.com", ProfileValidation.normalizeEndpoint(legacy))
            assertTrue(legacy, ProfileValidation.isValidPrimary(DnsType.DOQ, legacy))
        }
        assertEquals("quic://abc123.dns.controld.com",
            ProfileValidation.normalizeEndpoint("quic://dns.controld.com/abc123/"))
    }

    @Test fun selected232ControlDDoqProfileStaysUsable() {
        val selected = DnsProfile(id = 1230, providerName = "ControlD", name = "Unfiltered",
            type = DnsType.DOQ, primary = "quic://freedns.controld.com/p0")
        assertTrue(ProfileValidation.isUsable(selected))
    }

    @Test fun oneInvalidProfileDoesNotRejectWholeBackup() {
        val good = DnsProfile(id = 1001, providerName = "Cloudflare", name = "Standard",
            type = DnsType.DOH, primary = "https://cloudflare-dns.com/dns-query")
        val legacy = DnsProfile(id = 1230, providerName = "ControlD", name = "Unfiltered",
            type = DnsType.DOQ, primary = "quic://freedns.controld.com/p0")
        val broken = DnsProfile(id = 90001, providerName = "Custom", name = "Broken",
            type = DnsType.DOH, primary = "javascript:alert(1)", isCustom = true)
        val json = Gson().toJson(mapOf("profiles" to listOf(good, legacy, broken)))
        val root = ConfigValidation.parse(json)
        assertEquals(2, root.getAsJsonArray("profiles").size())
        assertEquals(1, root.get(ConfigValidation.REJECTED_PROFILES_KEY).asInt)
    }

    @Test fun rejectsNumericTopLevelDomain() {
        org.junit.Assert.assertFalse(ProfileValidation.isHostname("1.1.1"))
        org.junit.Assert.assertFalse(ProfileValidation.isValidPrimary(DnsType.DOH, "https://1.1.1"))
        assertTrue(ProfileValidation.isHostname("dns.quad9.net"))
        assertTrue(ProfileValidation.isValidPrimary(DnsType.DOH, "https://1.1.1.1/dns-query"))
    }
}
