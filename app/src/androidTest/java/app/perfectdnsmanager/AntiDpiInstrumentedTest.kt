package app.perfectdnsmanager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the separate-UID probe APK and loopback fixtures from tools/antidpi-probe. */
@RunWith(AndroidJUnit4::class)
class AntiDpiInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("prefs",0)
    private fun await(message: String, block: () -> Boolean) {
        val until=SystemClock.elapsedRealtime()+60000
        while(!block() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(100)
        assertTrue(message,block())
    }
    private fun vpnHasDefaultRoute(): Boolean {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true &&
            cm.getLinkProperties(n)?.routes?.any { it.destination.prefixLength==0 && it.destination.address is java.net.Inet4Address }==true }
    }
    private fun probe(blocked: Boolean=false, ipv6Blocked: Boolean=false) {
        var options=if(blocked) "-e dnsOnly true -e rcode 3" else ""
        if (!blocked && InstrumentationRegistry.getArguments().getString("ipv6Fixture")=="true")
            options += " -e ipv6Fixture true -e ipv6Blocked $ipv6Blocked"
        val fd=instrumentation.uiAutomation.executeShellCommand("am instrument -w $options app.perfectdnsmanager.antidpiprobe/.Probe")
        val text=android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        android.util.Log.i("PdmAntiDpiTest", text)
        assertTrue(text,text.contains("ALL PASSED"))
    }
    private fun start(endpoint:String) {
        val intent=Intent("app.perfectdnsmanager.START_VPN").setClassName(context,"app.perfectdnsmanager.service.DnsVpnService")
            .putExtra("dns_primary",endpoint)
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent) else context.startService(intent)
        await("Full VPN becomes active") { vpnHasDefaultRoute() && prefs.getBoolean("vpn_active",false) }
        SystemClock.sleep(500)
    }
    @Test fun fullTransportKeepsDnsFilterAndCanRestartWithoutLeakingWorkers() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antiDpiFixture")=="true")
        val permission=VpnService.prepare(context)
        if(permission!=null) {
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { activity.startActivityForResult(permission,188) }
            val until=SystemClock.elapsedRealtime()+60000
            while(VpnService.prepare(context)!=null && SystemClock.elapsedRealtime()<until)SystemClock.sleep(100)
        }
        assertNull("Grant VPN consent on the disposable AVD",VpnService.prepare(context))
        prefs.edit().putBoolean("dns_prefilter_enabled",false).putBoolean("antidpi_enabled",true).putBoolean("disable_ipv6", InstrumentationRegistry.getArguments().getString("blockIpv6")=="true").commit()
        try {
            start(InstrumentationRegistry.getArguments().getString("endpoint") ?: "https://one.one.one.one/dns-query")
            probe()
            val manager=app.perfectdnsmanager.data.ConfigManager(context)
            val filter="""{"dnsPrefilter":{"enabled":true,"builtin":false,"blocked":"example.com","allowed":""}}"""
            manager.importConfig(filter,true);SystemClock.sleep(300);probe(true)
            manager.importConfig(filter.replace("true","false"),true)
            prefs.edit().putBoolean("antidpi_enabled",false).commit()
            await("Disabling anti-DPI restores DNS-only routes") { !vpnHasDefaultRoute() && prefs.getBoolean("vpn_active",false) }
            prefs.edit().putBoolean("dns_prefilter_enabled",false).putBoolean("antidpi_enabled",true).commit()
            await("Re-enabling anti-DPI restores full routes") { vpnHasDefaultRoute() && prefs.getBoolean("vpn_active",false) }
            SystemClock.sleep(500)
            probe()
            start("unbound://one.one.one.one");probe()
            val am=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val workers=am.runningAppProcesses.filter { it.processName==context.packageName+":antidpi" }
            assertEquals("Exactly one private worker",1,workers.size)
            assertNotEquals(Process.myPid(),workers.single().pid)
            Process.killProcess(workers.single().pid)
            await("Worker death is detected, never reported as connected") { !vpnHasDefaultRoute() && !prefs.getBoolean("vpn_active",false) }
        } finally {
            context.startService(Intent("app.perfectdnsmanager.STOP_VPN").setClassName(context,"app.perfectdnsmanager.service.DnsVpnService"))
            prefs.edit().putBoolean("dns_prefilter_enabled",false).putBoolean("antidpi_enabled",false).putBoolean("disable_ipv6",false).commit()
            await("VPN stopped after test") { !vpnHasDefaultRoute() && !prefs.getBoolean("vpn_active",false) }
        }
    }
    @Test fun ipv6BlockingRemainsExplicitAndDoesNotRemoveDnsAaaa() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("ipv6Fixture")=="true")
        assertNull(VpnService.prepare(context))
        prefs.edit().putBoolean("dns_prefilter_enabled",false).putBoolean("antidpi_enabled",true).putBoolean("disable_ipv6",true).commit()
        try { start(InstrumentationRegistry.getArguments().getString("endpoint") ?: "https://one.one.one.one/dns-query");probe(ipv6Blocked=true) }
        finally {
            context.startService(Intent("app.perfectdnsmanager.STOP_VPN").setClassName(context,"app.perfectdnsmanager.service.DnsVpnService"))
            prefs.edit().putBoolean("dns_prefilter_enabled",false).putBoolean("antidpi_enabled",false).putBoolean("disable_ipv6",false).commit()
            await("VPN stopped after test") { !vpnHasDefaultRoute() && !prefs.getBoolean("vpn_active",false) }
        }
    }

}
