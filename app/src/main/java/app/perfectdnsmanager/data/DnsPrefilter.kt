package app.perfectdnsmanager.data

import app.perfectdnsmanager.util.DnsMessages
import java.net.IDN
import java.util.Locale
import org.xbill.DNS.*

/** Immutable policy. No network, disk access, or browsing history on the query path. */
class DnsPrefilter(
    private val enabled: Boolean = false,
    private val bundled: List<String> = emptyList(),
    private val blocked: Set<String> = emptySet(),
    private val allowed: Set<String> = emptySet()
) {
    fun blocks(name: String): Boolean {
        if (!enabled) return false
        val domain = name.trimEnd('.').lowercase(Locale.ROOT)
        fun matches(test: (String) -> Boolean): Boolean {
            var suffix = domain
            while (true) {
                if (test(suffix)) return true
                val dot = suffix.indexOf('.')
                if (dot < 0) return false
                suffix = suffix.substring(dot + 1)
            }
        }
        if (matches { it in allowed }) return false
        return matches { it in blocked || bundled.binarySearch(it) >= 0 }
    }

    /** Block both the original name and a locally rewritten destination. */
    fun response(query: ByteArray, rewritten: ByteArray? = null): ByteArray? {
        if (!enabled) return null
        return try {
            require(query.size in 12..DnsMessages.MAX_BYTES)
            val request = Message(query)
            require(!request.header.getFlag(Flags.QR.toInt()) &&
                request.header.opcode == Opcode.QUERY && request.header.getCount(Section.QUESTION) == 1)
            val question = request.question
            if (question.dClass != DClass.IN) return null
            val originalBlocked = blocks(question.name.toString())
            val targetBlocked = rewritten?.let {
                require(it.size in 12..DnsMessages.MAX_BYTES)
                blocks(Message(it).question.name.toString())
            } ?: false
            if (!originalBlocked && !targetBlocked) return null
            val response = Message(request.header.id)
            response.header.setFlag(Flags.QR.toInt())
            response.header.setFlag(Flags.RA.toInt())
            if (request.header.getFlag(Flags.RD.toInt())) response.header.setFlag(Flags.RD.toInt())
            // Local policy is not DNSSEC authenticated. No addresses, AD, or upstream response.
            response.header.rcode = Rcode.NXDOMAIN
            response.addRecord(question, Section.QUESTION)
            response.toWire(DnsMessages.MAX_BYTES)
        } catch (_: Exception) { null }
    }

    companion object {
        const val MAX_TEXT = 32768
        const val MAX_DOMAINS = 1000
        private val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        fun normalizeDomain(value: String): String {
            val trimmed = value.trim().removeSuffix(".")
            val domain = (if (trimmed.all { it.code < 128 }) trimmed
                else IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES)).lowercase(Locale.ROOT)
            require(domain.length in 3..253 && domain.contains('.') &&
                domain.split('.').all { label.matches(it) } && domain.any { it in 'a'..'z' })
            return domain
        }
        fun parseDomains(text: String): Set<String> {
            require(text.length <= MAX_TEXT)
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            require(lines.size <= MAX_DOMAINS)
            return lines.map(::normalizeDomain).toSet()
        }
    }
}
