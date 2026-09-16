package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.service.DnsVpnService
import app.perfectdnsmanager.util.DnsMessages
import app.perfectdnsmanager.util.DnsWire
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Live-network regression test: run only on a disposable AVD, never a user's phone. */
@RunWith(AndroidJUnit4::class)
class DohVpnInstrumentedTest {
    @Test fun dnsAndHttpsWorkThroughDohVpn() {
        assumeTrue("Disposable emulator required", Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val endpoint = InstrumentationRegistry.getArguments().getString("dohEndpoint")
            ?: "https://one.one.one.one/dns-query"
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        shell("settings put global private_dns_mode off")
        assertNull("VPN authorization", VpnService.prepare(context))
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("disable_ipv6", false).remove("excluded_apps_json").commit()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val start = Intent(context, DnsVpnService::class.java)
            .setAction(DnsVpnService.ACTION_START)
            .putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, endpoint)
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, start)
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (!DnsVpnService.isVpnRunning && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("VPN did not start", DnsVpnService.isVpnRunning)
            // Explicit VPN DNS address: proves the app forwarded DNS, even if the
            // system resolver already has cached answers or shell UID bypasses VPN.
            for (host in listOf("example.com", "www.iana.org")) {
                val query = DnsWire.buildQuery(host, 0x1234)
                val response = DnsWire.exchange(InetAddress.getByName("192.0.2.2"), query, 20000)
                assertNotNull("No DNS answer through $endpoint for $host", response)
                assertTrue(DnsMessages.matches(query, response!!))
                assertNotNull(DnsWire.parseAnswerIp(response, response.size, expectedHost = host))
            }
            val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
            try {
                client.newCall(Request.Builder().url("https://example.com/").build()).execute().use {
                    assertTrue("HTTPS ${it.code} with VPN enabled", it.isSuccessful)
                    assertTrue(it.body!!.string().contains("Example Domain"))
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        } finally {
            context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
