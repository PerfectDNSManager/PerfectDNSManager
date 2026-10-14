package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.service.DnsVpnService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.io.IOException

/** Requires the documented IPv6 probe on the emulator host's [::1]:18089. */
@RunWith(AndroidJUnit4::class)
class Ipv6RoutingInstrumentedTest {
    @Test fun ipv6TrafficPassesWithBlockingOffAndStopsWithBlockingOn() {
        assumeTrue("Disposable emulator required", Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        shell("settings put global private_dns_mode off")
        assertNull(VpnService.prepare(context))
        prefs.edit().remove("selected_profile_json").remove("excluded_apps_json").commit()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun waitForVpn(enabled: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < deadline) {
                val active = cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (active == enabled && DnsVpnService.isVpnRunning == enabled) return
                SystemClock.sleep(100)
            }
            fail("VPN did not reach expected state: $enabled")
        }
        fun stopVpn() {
            context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
            waitForVpn(false)
        }
        fun ipv6Works(): Boolean = try {
            Socket().use { socket ->
                socket.soTimeout = 3000
                // fec0::2 is the emulator's IPv6 alias for the host loopback.
                // This is an unprotected app socket, subject to PDM VPN routing.
                socket.connect(InetSocketAddress("fec0::2", 18089), 3000)
                socket.getInputStream().bufferedReader().readLine() == "PDM_IPV6_OK"
            }
        } catch (_: IOException) { false }
        try {
            stopVpn()
            assertTrue("IPv6 baseline unavailable; start the host probe", ipv6Works())
            for (block in listOf(false, true, false)) {
                prefs.edit().putBoolean("disable_ipv6", block).commit()
                androidx.core.content.ContextCompat.startForegroundService(context,
                    Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
                        .putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, "https://one.one.one.one/dns-query"))
                waitForVpn(true)
                assertEquals("IPv6 data flow with disable_ipv6=$block", !block, ipv6Works())
                stopVpn()
            }
        } finally {
            stopVpn()
            prefs.edit().putBoolean("disable_ipv6", false).commit()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
