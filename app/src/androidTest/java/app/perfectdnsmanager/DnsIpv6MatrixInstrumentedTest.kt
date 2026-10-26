package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType
import app.perfectdnsmanager.service.DnsVpnService
import app.perfectdnsmanager.util.DnsMessages
import app.perfectdnsmanager.util.DnsWire
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Live catalogue diagnostic, opt-in; network failures are recorded separately from VPN routing. */
@RunWith(AndroidJUnit4::class)
class DnsIpv6MatrixInstrumentedTest {
    @Test fun catalogueIpv6Matrix() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ipv6Matrix") == "true")
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        shell("settings put global private_dns_mode off")
        assertNull(android.net.VpnService.prepare(ctx))
        fun waitVpn(on: Boolean) {
            val end = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < end) {
                val active = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (active == on && DnsVpnService.isVpnRunning == on) return
                SystemClock.sleep(50)
            }
            error("VPN transition timeout: $on")
        }
        fun stop() { ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)); waitVpn(false) }
        fun probe(): Boolean = try {
            Socket().use { s -> s.soTimeout = 2000; s.connect(InetSocketAddress("fec0::2", 18089), 2000)
                s.getInputStream().bufferedReader().readLine() == "PDM_IPV6_OK" }
        } catch (_: Exception) { false }
        fun systemV6(): String = try { InetAddress.getAllByName("ipv6.google.com").filterIsInstance<Inet6Address>().joinToString { it.hostAddress ?: "" } }
            catch (e: Exception) { "ERROR:${e.javaClass.simpleName}" }
        val out = File(ctx.filesDir, "ipv6-matrix.jsonl")
        out.writeText("")
        stop()
        assertTrue("Host IPv6 probe required", probe())
        out.appendText(JSONObject().put("baselineIpv6", true).put("systemAAAA", systemV6()).toString()+"\n")
        prefs.edit().putBoolean("disable_ipv6", false).remove("excluded_apps_json").commit()
        val provider = InstrumentationRegistry.getArguments().getString("matrixProvider")
        val profiles = (DnsProfile.getDefaultPresets() + DnsProfile(providerName = "Regression", name = "Unreachable local DoH", type = DnsType.DOH, primary = "https://127.0.0.1:9/dns-query")).filter { it.type != DnsType.DOT && (provider == null || it.providerName == provider) }
        val routingFailures = mutableListOf<String>()
        try {
            for (p in profiles) {
                val row = JSONObject().put("provider", p.providerName).put("name", p.name).put("type", p.type.name).put("endpoint", p.primary)
                try {
                    androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, DnsVpnService::class.java)
                        .setAction(DnsVpnService.ACTION_START).putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, p.primary))
                    waitVpn(true)
                    val works = probe(); row.put("ipv6Socket", works)
                    if (!works) routingFailures.add(p.primary)
                    // DNS over IPv4 must still carry AAAA records. No system cache can answer this explicit query.
                    for (type in listOf(1, 28)) {
                        val q = DnsWire.buildQuery("example.com", 0x1200+type)
                        q[q.size-3] = type.toByte()
                        val r = DnsWire.exchange(InetAddress.getByName("192.0.2.2"), q, 14000)
                        row.put(if(type == 1) "A" else "AAAA", if(r == null) "TIMEOUT" else
                            "valid=${DnsMessages.matches(q,r)},rcode=${r[3].toInt() and 15},records=${org.xbill.DNS.Message(r).getSectionArray(org.xbill.DNS.Section.ANSWER).count { it.type == type }}")
                    }
                    row.put("systemAAAA", if (row.optString("AAAA").contains("rcode=0,records=")) systemV6() else "SKIPPED: upstream failure")
                    if (p.providerName == "Regression") {
                        assertEquals("valid=true,rcode=2,records=0", row.getString("A"))
                        assertEquals("valid=true,rcode=2,records=0", row.getString("AAAA"))
                    }
                } catch (e: Exception) { row.put("error", e.javaClass.simpleName+":"+e.message); routingFailures.add(p.primary+":"+e.javaClass.simpleName) }
                finally { stop() }
                out.appendText(row.toString()+"\n")
                android.util.Log.i("PdmIpv6Matrix", row.toString())
            }
        } finally { stop(); prefs.edit().putBoolean("disable_ipv6", false).commit() }
        assertTrue("IPv6 routing failures: $routingFailures", routingFailures.isEmpty())
    }
}
