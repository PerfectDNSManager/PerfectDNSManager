package app.perfectdnsmanager

import app.perfectdnsmanager.data.*
import org.junit.Assert.*
import org.junit.Test

class BenchmarkProfilesTest {
    private val base = DnsProfile(id=7, providerName="Example", name="Standard", type=DnsType.DOH,
        primary="https://dns.example.com/dns-query")
    @Test fun keepsDifferentProfilesAndPersonalImportsButDeduplicatesEndpoints() {
        val personal = base.copy(id=8, name="Personal", primary="https://dns.example.com/personal", isCustom=true)
        assertEquals(listOf(base, personal), BenchmarkProfiles.select(listOf(base, personal,
            base.copy(id=9, providerName="Alias")), false, false, false))
    }
    @Test fun respectsRestrictionsAndNeverStartsUnboundForBenchmark() {
        val profiles = listOf(base, base.copy(isAdblock=true, primary="https://dns.example.com/ads"),
            base.copy(isOperatorDns=true, primary="https://dns.example.com/isp"),
            base.copy(type=DnsType.UNBOUND, primary="unbound://dns.example.com"),
            base.copy(type=DnsType.DOT, primary="dns.example.com"))
        assertEquals(listOf(base), BenchmarkProfiles.select(profiles, false, false, false))
        assertEquals(4, BenchmarkProfiles.select(profiles, true, true, true).size)
    }
}
