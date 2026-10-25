package app.perfectdnsmanager

import app.perfectdnsmanager.util.redactDnsUrl
import org.junit.Assert.*
import org.junit.Test

class LogRedactTest {
    @Test fun masksPersonalIdsAcrossProtocols() {
        for (value in listOf("https://dns.nextdns.io/secret123", "https://dns.controld.com/secret123/tv", "quic://secret123.dns.nextdns.io", "quic://secret123.dns.controld.com:853", "secret123.dns.controld.com"))
            assertFalse(value, redactDnsUrl(value).contains("secret123"))
    }
    @Test fun preservesUsefulPublicEndpointAndHidesQuery() {
        assertEquals("1.1.1.1", redactDnsUrl("1.1.1.1"))
        assertEquals("https://dns.google/…", redactDnsUrl("https://dns.google/dns-query?secret=123"))
        assertEquals("", redactDnsUrl(null))
    }
}
