package app.perfectdnsmanager.util

import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section

/** Parse and serialize complete DNS messages, including compression and EDNS. */
object DnsMessages {
    const val MAX_BYTES = 8192

    fun readFrame(input: java.io.InputStream): ByteArray {
        val data = java.io.DataInputStream(input)
        val size = data.readUnsignedShort()
        require(size in 12..MAX_BYTES) { "Invalid DNS frame length" }
        return ByteArray(size).also { data.readFully(it) }
    }

    fun matches(query: ByteArray, response: ByteArray): Boolean = try {
        if (query.size < 12 || response.size !in 12..MAX_BYTES) false else {
            val q = Message(query); val r = Message(response)
            !q.header.getFlag(Flags.QR.toInt()) && r.header.getFlag(Flags.QR.toInt()) &&
                q.header.id == r.header.id && q.header.opcode == r.header.opcode &&
                q.header.getCount(Section.QUESTION) == 1 && r.header.getCount(Section.QUESTION) == 1 &&
                q.question == r.question
        }
    } catch (_: Exception) { false }

    fun rewriteQuery(query: ByteArray, domain: String): ByteArray? = try {
        val m = Message(query)
        require(m.header.getCount(Section.QUESTION) == 1 && m.tsig == null && !m.isSigned)
        val q = m.question
        m.removeAllRecords(Section.QUESTION)
        m.addRecord(Record.newRecord(Name.fromString(domain, Name.root), q.type, q.dClass), Section.QUESTION)
        m.toWire(MAX_BYTES)
    } catch (_: Exception) { null }

    fun restoreResponse(response: ByteArray, originalQuery: ByteArray): ByteArray? = try {
        val m = Message(response); val q = Message(originalQuery).question
        require(m.header.getCount(Section.QUESTION) == 1 && m.tsig == null && !m.isSigned)
        val target = m.question.name
        m.removeAllRecords(Section.QUESTION); m.addRecord(q, Section.QUESTION)
        m.header.unsetFlag(Flags.AD.toInt()) // Local aliases have not been DNSSEC authenticated.
        if (target != q.name) {
            val answers = m.getSectionArray(Section.ANSWER)
            m.removeAllRecords(Section.ANSWER)
            m.addRecord(CNAMERecord(q.name, q.dClass, 60, target), Section.ANSWER)
            answers.forEach { m.addRecord(it, Section.ANSWER) }
        }
        m.toWire(MAX_BYTES)
    } catch (_: Exception) { null }
}
