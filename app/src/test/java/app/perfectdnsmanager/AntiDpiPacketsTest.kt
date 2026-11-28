package app.perfectdnsmanager
import app.perfectdnsmanager.util.AntiDpiPackets
import org.junit.Assert.*
import org.junit.Test
class AntiDpiPacketsTest {
    @Test fun virtualDnsNeverEscapesToSocksEvenForTcpOrMalformedPackets() {
        val p=ByteArray(40);p[0]=0x45;p[16]=192.toByte();p[18]=2;p[19]=2
        for (protocol in listOf(6,17,1)) { p[9]=protocol.toByte();assertTrue(AntiDpiPackets.isVirtualDns(p)) }
        p[19]=3;assertTrue(AntiDpiPackets.isVirtualDns(p))
        p[19]=4;assertFalse(AntiDpiPackets.isVirtualDns(p))
    }
    @Test fun ipv6IsForwardedOnlyWhenAllowedAndInvalidHeadersAreRejected() {
        val p=ByteArray(40);p[0]=0x60
        assertTrue(AntiDpiPackets.canForward(p,true));assertFalse(AntiDpiPackets.canForward(p,false))
        assertFalse(AntiDpiPackets.canForward(p.copyOf(39),true))
        p[0]=0x45;assertTrue(AntiDpiPackets.canForward(p,false))
        p[0]=0x44;assertFalse(AntiDpiPackets.canForward(p,true))
        p[0]=0x4f;assertFalse(AntiDpiPackets.canForward(p,true))
    }
}
