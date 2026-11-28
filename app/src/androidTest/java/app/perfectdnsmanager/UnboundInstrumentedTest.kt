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

    @Test fun standaloneRecursionValidatesDnssecAndDoesNotNeedProviderBootstrap() {
        // Blocking IPv6 transport must not strip AAAA records from answers.
        val prefs = context.getSharedPreferences("prefs", 0)
        val previous = prefs.getBoolean("disable_ipv6", false)
        prefs.edit().putBoolean("disable_ipv6", true).commit()
        try {
            UnboundBackend.open(context, "unbound://recursive").use { backend ->
                for (type in listOf(Type.A, Type.AAAA)) {
                    val request = query("cloudflare.com.", type)
                    val answer = Message(requireNotNull(backend.query(request)))
                    assertEquals(Rcode.NOERROR, answer.rcode)
                    assertTrue(answer.getSection(Section.ANSWER).any { it.type == type })
                    assertTrue(answer.header.getFlag(Flags.AD.toInt()))
                }
                val bogus = backend.query(query("dnssec-failed.org.", Type.A))
                assertTrue(bogus == null || Message(bogus).rcode == Rcode.SERVFAIL)
            }
        } finally { prefs.edit().putBoolean("disable_ipv6", previous).commit() }
    }

    @Test fun controlledRootReferralReachesSeparateAuthoritativeServer() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("recursiveFixture") == "true")
        // Unsigned, isolated hierarchy: DNSSEC is tested separately with the production anchors.
        // Production UnboundBackend always uses the bundled IANA anchors, never this file.
        val anchors = File(context.cacheDir, "unsigned-recursion-fixture.key").apply { writeText("") }
        val handle = UnboundNative.create(emptyArray(), "", anchors.absolutePath, true, false)
        assertTrue(handle != 0L)
        try {
            for (type in listOf(Type.A, Type.AAAA)) {
                val response = Message(requireNotNull(UnboundNative.resolve(handle, "www.pdmproof.", type)))
                assertEquals(Rcode.NOERROR, response.rcode)
                assertFalse(response.header.getFlag(Flags.AD.toInt()))
                val address = response.getSection(Section.ANSWER).first { it.type == type }
                if (type == Type.A) assertEquals("203.0.113.80", (address as ARecord).address.hostAddress)
                else assertEquals(java.net.InetAddress.getByName("2001:db8::80"), (address as AAAARecord).address)
            }
        } finally { UnboundNative.destroy(handle); anchors.delete() }
    }

    @Test fun nativeModesNeverFallBackToRecursionImplicitly() {
        assertEquals(0L, UnboundNative.create(emptyArray(), "", "", false, true))
        assertEquals(0L, UnboundNative.create(arrayOf("1.1.1.1@853#one.one.one.one"), "", "", true, true))
    }

    @Test fun wrongTlsNameFailsClosedAndClosingPendingQueriesIsSafe() {
        fun asset(name: String): String = File(context.cacheDir, "test-$name").apply {
            context.assets.open("unbound/$name").use { input -> outputStream().use { input.copyTo(it) } }
        }.absolutePath
        val handle = UnboundNative.create(arrayOf("1.1.1.1@853#wrong.invalid"), asset("cacert.pem"), asset("root.key"), false, true)
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
