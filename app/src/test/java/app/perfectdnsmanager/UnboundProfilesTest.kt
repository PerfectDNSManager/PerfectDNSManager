package app.perfectdnsmanager
import app.perfectdnsmanager.data.*
import app.perfectdnsmanager.util.ProfileValidation
import app.perfectdnsmanager.util.redactDnsUrl
import org.junit.Assert.*
import org.junit.Test
class UnboundProfilesTest {
    @Test fun requiresAuthenticatedHostnameWithoutUrlCredentialsOrDnsPaths() {
        for (endpoint in listOf("unbound://one.one.one.one", "unbound://dns.example.com:8853"))
            assertTrue(ProfileValidation.isValidPrimary(DnsType.UNBOUND, endpoint))
        for (endpoint in listOf("unbound://1.1.1.1", "https://dns.example.com", "unbound://user@dns.example.com", "unbound://dns.example.com/dns-query", "unbound://dns.example.com?x=1", "unbound://dns.example.com#other", "unbound://dns.example.com:0"))
            assertFalse(endpoint, ProfileValidation.isValidPrimary(DnsType.UNBOUND, endpoint))
    }
    @Test fun eachDotPresetKeepsProviderAndFilteringPolicy() {
        val profiles = DnsProfile.getDefaultPresets()
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
        for (dot in profiles.filter { it.type == DnsType.DOT }) {
            val local = profiles.single { it.id == dot.id + 100000 }
            assertEquals(DnsType.UNBOUND, local.type)
            assertEquals(dot.providerName, local.providerName)
            assertEquals(dot.isAdblock, local.isAdblock)
            assertEquals("unbound://${dot.primary}", local.primary)
            assertTrue(ProfileValidation.isUsable(local))
        }
    }
    @Test fun autonomousModeIsExplicitAndSurvivesConfigValidation() {
        val p = DnsProfile.getDefaultPresets().single { it.id == UnboundMode.RECURSIVE_PRESET_ID }
        assertEquals("Unbound", p.providerName)
        assertTrue(ProfileValidation.isUsable(p))
        assertTrue(UnboundMode.isRecursive(p.primary))
        assertFalse(p.isAdblock)
        assertFalse(p.isCustom)
        assertFalse(ProfileValidation.isUsable(p.copy(secondary = "unbound://one.one.one.one")))
        assertFalse(ProfileValidation.isUsable(p.copy(primary = "unbound://one.one.one.one", secondary = UnboundMode.RECURSIVE_ENDPOINT)))
        val document = "{\"profiles\":[" + com.google.gson.Gson().toJson(p) + "]}"
        val imported = ConfigValidation.parse(document).getAsJsonArray("profiles").single().asJsonObject
        assertEquals(UnboundMode.RECURSIVE_ENDPOINT, imported.get("primary").asString)
        assertFalse(imported.get("isCustom").asBoolean)
        for (bad in listOf("unbound://recursive/", "unbound://recursive:853", "unbound://recursive?forward=1", "unbound://user@recursive", "unbound://recursive#x"))
            assertFalse(bad, ProfileValidation.isValidPrimary(DnsType.UNBOUND, bad))
        assertFalse(UnboundMode.isRecursive("unbound://recursive.example.com"))
        assertTrue(BenchmarkProfiles.select(listOf(p), true, true, true).isEmpty())
    }
    @Test fun personalIdsAreRedactedForLocalDotToo() {
        assertEquals("unbound://***.dns.nextdns.io", redactDnsUrl("unbound://secret.dns.nextdns.io"))
        assertEquals("unbound://***.dns.controld.com", redactDnsUrl("unbound://secret.dns.controld.com"))
    }
}
