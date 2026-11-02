package app.perfectdnsmanager

import app.perfectdnsmanager.data.*
import org.junit.Assert.*
import org.junit.Test

class SystemDotProfileTest {
    private val presets = DnsProfile.getDefaultPresets()
    @Test fun selectsActualCloudflareInsteadOfSavedVpn() {
        val found = SystemDotProfile.resolve("hostname", "ONE.ONE.ONE.ONE.", presets)!!
        assertEquals(1012L, found.id)
        assertEquals(DnsType.DOT, found.type)
    }
    @Test fun preservesPersonalProfileAndActualAlias() {
        val saved = DnsProfile(providerName="My DNS", name="TV", type=DnsType.DOT,
            primary="my.dns.example", isCustom=true)
        assertEquals(saved, SystemDotProfile.resolve("hostname", saved.primary, listOf(saved)+presets))
        val alias = SystemDotProfile.resolve("hostname", "cloudflare-dns.com", presets)!!
        assertEquals("Cloudflare", alias.providerName)
        assertEquals("cloudflare-dns.com", alias.primary)
        assertTrue(alias.isCustom)
    }
    @Test fun rejectsInactiveInvalidAndImpersonatedProviders() {
        for (mode in listOf(null, "off", "opportunistic", "not-hostname"))
            assertNull(SystemDotProfile.resolve(mode, "one.one.one.one", presets))
        for (host in listOf("", "https://one.one.one.one", "1.1.1.1", "host name"))
            assertNull(SystemDotProfile.resolve("hostname", host, presets))
        val unknown = SystemDotProfile.resolve("hostname", "abc.dns.controld.com.evil.test", presets)!!
        assertEquals("DoT", unknown.providerName)
        assertEquals("abc.dns.controld.com.evil.test", unknown.primary)
    }
}
