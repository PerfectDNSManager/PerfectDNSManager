package app.perfectdnsmanager

import app.perfectdnsmanager.util.DnsMessages
import org.junit.Assert.*
import org.junit.Test
import org.xbill.DNS.*
import org.xbill.DNS.Record
import java.net.InetAddress

class DnsMessagesTest {
    private fun query(name: String = "short.example.", type: Int = Type.A): Message =
        Message.newQuery(Record.newRecord(Name.fromString(name), type, DClass.IN))
    private fun response(q: Message): Message = Message(q.toWire()).apply {
        header.setFlag(Flags.QR.toInt())
        addRecord(ARecord(q.question.name, DClass.IN, 60, InetAddress.getByName("192.0.2.10")), Section.ANSWER)
    }
    @Test fun rejectsWrongIdQuestionTypeAndQueryPackets() {
        val q = query(); val r = response(q)
        assertTrue(DnsMessages.matches(q.toWire(), r.toWire()))
        r.header.id = (q.header.id + 1) and 65535
        assertFalse(DnsMessages.matches(q.toWire(), r.toWire()))
        val wrong = response(query("other.example.")); wrong.header.id = q.header.id
        assertFalse(DnsMessages.matches(q.toWire(), wrong.toWire()))
        val wrongType = response(query(type = Type.AAAA)); wrongType.header.id = q.header.id
        assertFalse(DnsMessages.matches(q.toWire(), wrongType.toWire()))
        assertFalse(DnsMessages.matches(q.toWire(), q.toWire()))
        assertFalse(DnsMessages.matches(q.toWire(), byteArrayOf(1, 2)))
    }
    @Test fun rewriteRetainsEdnsAndRebuildsCompressedCnames() {
        val original = query()
        original.addRecord(OPTRecord(1232, 0, 0), Section.ADDITIONAL)
        val rewritten = Message(DnsMessages.rewriteQuery(original.toWire(), "much-longer.destination.example")!!)
        assertNotNull(rewritten.opt)
        val upstream = response(rewritten)
        upstream.header.setFlag(Flags.AD.toInt())
        val target = Name.fromString("final.destination.example.")
        upstream.removeAllRecords(Section.ANSWER)
        upstream.addRecord(CNAMERecord(rewritten.question.name, DClass.IN, 60, target), Section.ANSWER)
        upstream.addRecord(ARecord(target, DClass.IN, 60, InetAddress.getByName("192.0.2.10")), Section.ANSWER)
        val result = Message(DnsMessages.restoreResponse(upstream.toWire(), original.toWire())!!)
        assertEquals(original.question, result.question)
        assertFalse(result.header.getFlag(Flags.AD.toInt()))
        assertEquals(3, result.header.getCount(Section.ANSWER))
        val answers = result.getSectionArray(Section.ANSWER)
        assertEquals(rewritten.question.name, (answers[0] as CNAMERecord).target)
        assertEquals(target, (answers[1] as CNAMERecord).target)
        assertEquals(target, answers[2].name)
        assertNotNull(result.opt)
    }
    @Test fun rejectsTruncatedFrames() {
        try { DnsMessages.readFrame(byteArrayOf(0, 20, 1).inputStream()); fail() } catch (_: java.io.EOFException) {}
        try { DnsMessages.readFrame(byteArrayOf(0, 1, 0).inputStream()); fail() } catch (_: IllegalArgumentException) {}
    }
}
