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
    @Test fun personalIdsAreRedactedForLocalDotToo() {
        assertEquals("unbound://***.dns.nextdns.io", redactDnsUrl("unbound://secret.dns.nextdns.io"))
        assertEquals("unbound://***.dns.controld.com", redactDnsUrl("unbound://secret.dns.controld.com"))
    }
}
