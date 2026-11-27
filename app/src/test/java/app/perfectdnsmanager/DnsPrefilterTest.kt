package app.perfectdnsmanager

import app.perfectdnsmanager.data.ConfigValidation
import app.perfectdnsmanager.data.DnsPrefilter
import app.perfectdnsmanager.util.DnsMessages
import org.junit.Assert.*
import org.junit.Test
import org.xbill.DNS.*
import org.xbill.DNS.Record
import java.io.File

class DnsPrefilterTest {
    private fun query(host: String, type: Int = Type.A) =
        Message.newQuery(Record.newRecord(Name.fromString("$host."), type, DClass.IN)).toWire()
    private val filter = DnsPrefilter(true, listOf("ads.example"), setOf("tracking.example"), setOf("safe.ads.example"))

    @Test fun suffixMatchingAndExceptionsHaveDomainBoundaries() {
        for (host in listOf("ads.example", "child.ads.example", "ADS.EXAMPLE.", "tracking.example")) assertTrue(host, filter.blocks(host))
        for (host in listOf("badads.example", "ads.example.org", "safe.ads.example", "a.safe.ads.example", "example.org")) assertFalse(host, filter.blocks(host))
        assertFalse(DnsPrefilter().blocks("ads.example"))
        assertFalse(DnsPrefilter(true, blocked = setOf("ads.example"), allowed = setOf("example")).blocks("ads.example"))
    }
    @Test fun onlyBlockedQueriesAreAnsweredAndAllRecordTypesAreCovered() {
        for (type in listOf(Type.A, Type.AAAA, Type.HTTPS, Type.SVCB, Type.TXT)) {
            val q = query("ads.example", type)
            val r = filter.response(q)!!
            assertTrue(DnsMessages.matches(q, r))
            val m = Message(r)
            assertEquals(Rcode.NXDOMAIN, m.rcode)
            assertEquals(0, m.header.getCount(Section.ANSWER))
            assertFalse(m.header.getFlag(Flags.AD.toInt()))
            assertTrue(m.header.getFlag(Flags.RD.toInt()))
            assertNull(filter.response(query("example.org", type)))
            assertNull(DnsPrefilter().response(q))
        }
    }
    @Test fun rewritingCannotBypassBlockingAndKeepsOriginalQuestion() {
        val blocked = query("ads.example")
        val good = query("example.org")
        assertTrue(DnsMessages.matches(blocked, filter.response(blocked, good)!!))
        assertTrue(DnsMessages.matches(good, filter.response(good, blocked)!!))
        assertNull(filter.response(good, query("safe.ads.example")))
    }
    @Test fun malformedOrNonQueryMessagesDoNotCrashOrBecomeAnswers() {
        for (q in listOf(byteArrayOf(), ByteArray(12), ByteArray(8193), query("ads.example").dropLast(2).toByteArray())) assertNull(filter.response(q))
        val q = Message(query("ads.example"))
        q.header.setFlag(Flags.QR.toInt()); assertNull(filter.response(q.toWire()))
        q.header.unsetFlag(Flags.QR.toInt()); q.header.opcode = Opcode.UPDATE; assertNull(filter.response(q.toWire()))
        q.header.opcode = Opcode.QUERY
        q.addRecord(Record.newRecord(Name.fromString("other.example."), Type.A, DClass.IN), Section.QUESTION)
        assertNull(filter.response(q.toWire()))
    }
    @Test fun manualRulesAreBoundedAndNormalized() {
        assertEquals(setOf("xn--bcher-kva.example", "ads.example"), DnsPrefilter.parseDomains(" bücher.example\nADS.EXAMPLE.\nads.example\n"))
        for (text in listOf("com", "127.0.0.1", "::1", "https://ads.example", "*.example.org", "ads..example", "-bad.example", "a".repeat(64)+".org", "ads.example\u0000", "ads.example\n".repeat(1001), "a".repeat(32769))) {
            assertTrue(text.take(30), runCatching { DnsPrefilter.parseDomains(text) }.isFailure)
        }
    }
    @Test fun importValidatesFilterBeforeAcceptingDocument() {
        assertNotNull(ConfigValidation.parse("{}"))
        assertNotNull(ConfigValidation.parse("""{"dnsPrefilter":{"enabled":true,"builtin":false,"blocked":"ads.example","allowed":""}}"""))
        for (bad in listOf("""{"enabled":"true","builtin":true,"blocked":"","allowed":""}""", """{"enabled":true,"builtin":true,"blocked":"https://example.org","allowed":""}""", "{}")) {
            assertTrue(runCatching { ConfigValidation.parse("{\"dnsPrefilter\":$bad}") }.isFailure)
        }
    }
    @Test fun bundledSnapshotContainsValidDomainsAndBlocksKnownAds() {
        val file = File("src/main/assets/prefilter/hagezi-light.txt")
        val raw = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(raw, raw.map(DnsPrefilter::normalizeDomain))
        assertEquals(raw.size, raw.toSet().size)
        val domains = raw.sorted()
        assertEquals(81810, domains.size)
        val policy = DnsPrefilter(true, domains)
        assertTrue(policy.blocks("g.doubleclick.net"))
        assertFalse(policy.blocks("example.org"))
        assertFalse(policy.blocks("cloudflare-dns.com"))
    }
}
