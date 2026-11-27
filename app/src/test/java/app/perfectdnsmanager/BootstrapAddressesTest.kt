package app.perfectdnsmanager

import app.perfectdnsmanager.util.BootstrapAddresses
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class BootstrapAddressesTest {
    @Test fun physicalDnsKeepsIpv6AndAlternativeAddressesWithoutPublicUdp() {
        val v6 = InetAddress.getByName("2001:db8::1")
        val a = InetAddress.getByName("192.0.2.1")
        val b = InetAddress.getByName("192.0.2.2")
        val result = BootstrapAddresses.resolve(listOf({ listOf(v6, a, b, a) }),
            listOf({ error("Public UDP must not be consulted") }))
        assertEquals(listOf(v6, a, b), result)
    }
    @Test fun disappearingNetworkAndInvalidAnswersTryNextPhysicalNetwork() {
        val good = InetAddress.getByName("192.0.2.1")
        assertEquals(listOf(good), BootstrapAddresses.resolve(listOf(
            { throw java.net.UnknownHostException() },
            { listOf(InetAddress.getByName("0.0.0.0"), InetAddress.getByName("ff02::1")) },
            { listOf(good) }), listOf({ error("Not needed") })))
    }
    @Test fun publicFallbackOnlyAfterPhysicalFailureAndFailureStaysEmpty() {
        val good = InetAddress.getByName("192.0.2.1")
        assertEquals(listOf(good), BootstrapAddresses.resolve(listOf({ emptyList() }),
            listOf({ throw java.net.UnknownHostException() }, { listOf(good) })))
        assertTrue(BootstrapAddresses.resolve(emptyList(), listOf({ emptyList() })).isEmpty())
    }
}
