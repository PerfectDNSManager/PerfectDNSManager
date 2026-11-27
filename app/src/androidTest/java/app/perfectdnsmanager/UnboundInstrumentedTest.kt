package app.perfectdnsmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.service.UnboundBackend
import app.perfectdnsmanager.service.UnboundNative
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xbill.DNS.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UnboundInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun query(name: String, type: Int) = Message.newQuery(org.xbill.DNS.Record.newRecord(Name.fromString(name), type, DClass.IN)).toWire()

    @Test fun authenticatedDotResolvesBothFamiliesAndValidatesDnssec() {
        UnboundBackend.open(context, "unbound://one.one.one.one").use { backend ->
            for (type in listOf(Type.A, Type.AAAA)) {
                val request = query("cloudflare.com.", type)
                val wire = backend.query(request)
                assertNotNull("No authenticated DoT answer for type $type", wire)
                val answer = Message(wire!!)
                assertEquals(Message(request).header.id, answer.header.id)
                assertEquals(Rcode.NOERROR, answer.rcode)
                assertTrue(answer.getSection(Section.ANSWER).any { it.type == type })
                assertTrue("Signed cloudflare.com must validate", answer.header.getFlag(Flags.AD.toInt()))
                assertNotNull(backend.query(request)) // Cached response remains usable.
            }
            val bogus = backend.query(query("dnssec-failed.org.", Type.A))
            assertTrue("Bogus DNSSEC must fail closed", bogus == null || Message(bogus).rcode == Rcode.SERVFAIL)
        }
    }

    @Test fun wrongTlsNameFailsClosedAndClosingPendingQueriesIsSafe() {
        fun asset(name: String): String = File(context.cacheDir, "test-$name").apply {
            context.assets.open("unbound/$name").use { input -> outputStream().use { input.copyTo(it) } }
        }.absolutePath
        val handle = UnboundNative.create(arrayOf("1.1.1.1@853#wrong.invalid"), asset("cacert.pem"), asset("root.key"))
        assertTrue(handle != 0L)
        try {
            val wire = UnboundNative.resolve(handle, "cloudflare.com.", Type.A)
            if (wire != null) {
                val failure = Message(wire)
                assertEquals("Wrong TLS name must return only SERVFAIL: $failure", Rcode.SERVFAIL, failure.rcode)
                assertTrue(failure.getSection(Section.ANSWER).isEmpty())
            }
        }
        finally { UnboundNative.destroy(handle); UnboundNative.destroy(handle) }
        val backend = UnboundBackend.open(context, "unbound://one.one.one.one")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { pool.submit<ByteArray?> { backend.query(query("$it.example.com.", Type.A)) } }
            backend.close(); backend.close()
            futures.forEach { it.get(12, TimeUnit.SECONDS) }
            assertNull(backend.query(query("cloudflare.com.", Type.A)))
        } finally { backend.close(); pool.shutdownNow() }
    }
}
