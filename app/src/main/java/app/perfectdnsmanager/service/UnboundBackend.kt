package app.perfectdnsmanager.service

import android.content.Context
import app.perfectdnsmanager.util.DnsMessages
import app.perfectdnsmanager.util.ProfileValidation
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import org.xbill.DNS.*

/** Loaded only when an Unbound profile is actually started. */
internal object UnboundNative {
    init { System.loadLibrary("pdm_unbound") }
    external fun create(addresses: Array<String>, ca: String, anchors: String): Long
    external fun resolve(handle: Long, name: String, type: Int): ByteArray?
    external fun destroy(handle: Long)
}

class UnboundBackend private constructor(handle: Long) : Closeable {
    private val nativeHandle = AtomicLong(handle)
    fun query(query: ByteArray): ByteArray? = try {
        require(query.size in 12..DnsMessages.MAX_BYTES)
        val request = Message(query)
        require(request.header.opcode == Opcode.QUERY && !request.header.getFlag(Flags.QR.toInt()) &&
            request.header.getCount(Section.QUESTION) == 1 && request.question.dClass == DClass.IN && request.tsig == null)
        val current = nativeHandle.get()
        if (current == 0L) null else {
            val result = UnboundNative.resolve(current, request.question.name.toString(), request.question.type)
            result?.let {
                val response = Message(it)
                require(response.header.getFlag(Flags.QR.toInt()) && response.question == request.question)
                response.header.id = request.header.id
                response.removeAllRecords(Section.QUESTION)
                response.addRecord(request.question, Section.QUESTION)
                if (request.header.getFlag(Flags.RD.toInt())) response.header.setFlag(Flags.RD.toInt())
                else response.header.unsetFlag(Flags.RD.toInt())
                response.toWire(DnsMessages.MAX_BYTES)
            }
        }
    } catch (_: Exception) { null }

    override fun close() { nativeHandle.getAndSet(0L).takeIf { it != 0L }?.let(UnboundNative::destroy) }

    companion object {
        /** Run before establishing the VPN; only the provider hostname uses system bootstrap DNS. */
        fun open(context: Context, endpoint: String): UnboundBackend {
            require(ProfileValidation.isValidPrimary(app.perfectdnsmanager.data.DnsType.UNBOUND, endpoint))
            val uri = URI(endpoint)
            val host = uri.host
            val port = if (uri.port == -1) 853 else uri.port
            val addresses = InetAddress.getAllByName(host).filter { !it.isAnyLocalAddress && !it.isMulticastAddress }
                .take(8).map { "${it.hostAddress!!.substringBefore('%')}@$port#$host" }.toTypedArray()
            val dir = File(context.filesDir, "unbound").apply { mkdirs() }
            fun asset(name: String): File {
                val bytes = context.assets.open("unbound/$name").use { it.readBytes() }
                val file = File(dir, name)
                if (!file.exists() || !file.readBytes().contentEquals(bytes)) {
                    val atomic = android.util.AtomicFile(file)
                    val output = atomic.startWrite()
                    try { output.write(bytes); atomic.finishWrite(output) }
                    catch (error: Exception) { atomic.failWrite(output); throw error }
                }
                return file
            }
            val handle = try {
                UnboundNative.create(addresses, asset("cacert.pem").absolutePath, asset("root.key").absolutePath)
            } catch (error: LinkageError) {
                throw IllegalStateException("Unbound is unavailable on this device", error)
            }
            check(handle != 0L) { "Unbound initialization failed" }
            return UnboundBackend(handle)
        }
    }
}
