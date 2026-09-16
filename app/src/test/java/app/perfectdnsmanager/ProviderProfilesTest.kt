package app.perfectdnsmanager

import app.perfectdnsmanager.data.*
import app.perfectdnsmanager.util.ProfileValidation
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ProviderProfilesTest {
    @Test fun acceptsPersonalControlDAddresses() {
        for (input in listOf("abc12345", " https://dns.controld.com/abc12345/ ",
            "abc12345.dns.controld.com", "tls://abc12345.dns.controld.com:853",
            "quic://abc12345.dns.controld.com:853")) {
            assertEquals("abc12345", ControlDProfiles.resolverId(input))
        }
        assertEquals("AbC123", ControlDProfiles.resolverId("https://dns.controld.com/AbC123"))
    }
    @Test fun keepsControlDDeviceNameAcrossProtocols() {
        // Formats documentés par Control D : /id/appareil en DoH, id-appareil en DoT/DoQ.
        for (input in listOf("https://dns.controld.com/abc12345/living-tv",
            "abc12345-living-tv.dns.controld.com", "quic://abc12345-living-tv.dns.controld.com",
            "abc12345-living-tv")) {
            assertEquals(input, "abc12345-living-tv", ControlDProfiles.resolverId(input))
        }
        assertEquals("https://dns.controld.com/abc12345/living-tv",
            ControlDProfiles.primary("abc12345-living-tv", DnsType.DOH))
        assertEquals("abc12345-living-tv.dns.controld.com",
            ControlDProfiles.primary("abc12345-living-tv", DnsType.DOT))
    }
    @Test fun rejectsWrongHostsAndAmbiguousInputs() {
        for (input in listOf("", "bad id", "-abc", "a".repeat(64),
            "https://dns.controld.com.evil.test/abc12345", "http://dns.controld.com/abc12345",
            "https://user@dns.controld.com/abc12345", "https://dns.controld.com/abc12345?x=1",
            "https://dns.controld.com/abc12345#x", "https://dns.controld.com/a/b/c",
            "quic://abc12345.dns.controld.com:443", "https://dns.controld.com/%61bc12345")) {
            assertNull(input, ControlDProfiles.resolverId(input))
        }
    }
    @Test fun personalProfilesSurviveExportImportValidation() {
        for (type in listOf(DnsType.DOH, DnsType.DOT, DnsType.DOQ)) {
            val profile = DnsProfile(id = 50001, providerName = "ControlD", name = "My TV",
                type = type, primary = ControlDProfiles.primary("abc12345", type), isCustom = true)
            assertTrue(ProfileValidation.isUsable(profile))
            val json = Gson().toJson(mapOf("profiles" to listOf(profile), "selectedProfile" to profile))
            val imported = ConfigValidation.parse(json).getAsJsonArray("profiles")[0].asJsonObject
            assertEquals(profile.primary, imported.get("primary").asString)
            assertTrue(imported.get("isCustom").asBoolean)
        }
        assertEquals("quic://abc12345.dns.controld.com:853", ControlDProfiles.primary("abc12345", DnsType.DOQ))
    }
    @Test fun xdpIncludesOptionalAdblockResolvers() {
        val all = DnsProfile.getDefaultPresets()
        assertEquals(all.size, all.map { it.id }.distinct().size)
        val xdp = all.filter { it.providerName == "xdp.es" }
        assertEquals(8, xdp.size)
        assertEquals(4, xdp.count { it.isAdblock })
        assertTrue(xdp.all(ProfileValidation::isUsable))
        assertEquals("https://lite.xdp.es/dns-query", xdp.single { it.name == "Standard" && it.type == DnsType.DOH }.primary)
    }
    @Test fun adblockSettingIsIndependentOfVariants() {
        val presets = DnsProfile.getDefaultPresets()
        for (variants in listOf(false, true)) {
            val hidden = ProfileCatalog.visible(presets, false, variants)
            assertFalse(hidden.any { it.isAdblock })
            val shown = ProfileCatalog.visible(presets, true, variants)
            assertEquals(presets.filter { it.isAdblock }.map { it.id }.toSet(), shown.filter { it.isAdblock }.map { it.id }.toSet())
            assertTrue(shown.any { it.providerName == "xdp.es" && it.name == "Standard" })
        }
        assertTrue(presets.all(ProfileValidation::isUsable))
        val custom = DnsProfile(providerName = "ControlD", name = "My profile", type = DnsType.DOH,
            primary = "https://dns.controld.com/abc123", isCustom = true)
        assertTrue(ProfileCatalog.visible(presets + custom, false, false).contains(custom))
        val imported = ConfigValidation.parse("""{"settings":{"allow_adblock_profiles":true}}""")
        assertTrue(imported.getAsJsonObject("settings").get("allow_adblock_profiles").asBoolean)
    }
}
