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
        fun assertIpv6Flow(block: Boolean) {
            // Connectivity callbacks and per-UID route installation are asynchronous.
            val deadline = SystemClock.elapsedRealtime() + 10000
            var actual = ipv6Works()
            while (actual != !block && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
                actual = ipv6Works()
            }
            assertEquals("IPv6 data flow with disable_ipv6=$block", !block, actual)
        }
        var settingsActivity: android.app.Activity? = null
        try {
            stopVpn()
            assertTrue("IPv6 baseline unavailable; start the host probe", ipv6Works())
            for (block in listOf(false, true, false)) {
                prefs.edit().putBoolean("disable_ipv6", block).commit()
                androidx.core.content.ContextCompat.startForegroundService(context,
                    Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
                        .putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, "https://one.one.one.one/dns-query"))
                waitForVpn(true)
                assertIpv6Flow(block)
                stopVpn()
            }
            // Exercise the actual settings listener while Control D is connected.
            // A stopped/restarted service alone cannot detect a stale live tunnel.
            val profile = app.perfectdnsmanager.data.DnsProfile.getDefaultPresets().first {
                it.providerName == "ControlD" && it.type == app.perfectdnsmanager.data.DnsType.DOH && it.name == "Unfiltered"
            }
            prefs.edit().putString("selected_profile_json", com.google.gson.Gson().toJson(profile)).commit()
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
                    .putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, profile.primary))
            waitForVpn(true)
            assertTrue("Control D baseline IPv6", ipv6Works())
            val settings = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settingsActivity = settings
            for (block in listOf(true, false, true, false)) {
                val oldNetwork = cm.activeNetwork
                instrumentation.runOnMainSync {
                    settings.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchDisableIpv6).isChecked = block
                }
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (cm.activeNetwork != oldNetwork && DnsVpnService.isVpnRunning &&
                        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) break
                    SystemClock.sleep(100)
                }
                assertNotEquals("Settings must rebuild the active VPN", oldNetwork, cm.activeNetwork)
                assertIpv6Flow(block)
            }
        } finally {
            settingsActivity?.let { instrumentation.runOnMainSync { it.finish() } }
            stopVpn()
            prefs.edit().putBoolean("disable_ipv6", false).commit()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
