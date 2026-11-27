package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.Button
import android.widget.CheckBox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.data.DnsPrefilterRepository
import app.perfectdnsmanager.data.ConfigManager
import app.perfectdnsmanager.service.DnsVpnService
import app.perfectdnsmanager.util.DnsWire
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xbill.DNS.*
import org.xbill.DNS.Record
import java.net.InetAddress

/** Disposable AVD only. Fixture test requires tools/PrefilterDnsFixture.java on AVD loopback UDP/53. */
@RunWith(AndroidJUnit4::class)
class DnsPrefilterInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = DnsPrefilterRepository(context)
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun waitFor(test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (test()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for VPN/filter state", test())
    }
    private fun stop() {
        context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
        waitFor { !DnsVpnService.isVpnRunning }
    }
    private fun start(endpoint: String) {
        androidx.core.content.ContextCompat.startForegroundService(context,
            Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
                .putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, endpoint))
        waitFor { DnsVpnService.isVpnRunning }
    }
    private fun request(host: String, type: Int = Type.A): Message {
        val q = Message.newQuery(Record.newRecord(Name.fromString("$host."), type, DClass.IN))
        val response = DnsWire.exchange(InetAddress.getByName("192.0.2.2"), q.toWire(), 10000)
        assertNotNull("DNS reply for $host ($type)", response)
        return Message(response!!)
    }
    private fun prepare(): android.app.Activity {
        assumeTrue("Disposable AVD required", Build.HARDWARE in listOf("ranchu", "goldfish"))
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        shell("settings put global private_dns_mode off")
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Android 5 has no ACTIVATE_VPN app-op: exercise its real consent dialog.
        VpnService.prepare(context)?.let { consent ->
            instrumentation.runOnMainSync { activity.startActivityForResult(consent, 991) }
            waitFor {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString() == "com.android.vpndialogs") {
                    fun check(node: android.view.accessibility.AccessibilityNodeInfo) {
                        if (node.isCheckable && !node.isChecked) node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        for (i in 0 until node.childCount) node.getChild(i)?.let(::check)
                    }
                    check(root)
                    root.findAccessibilityNodeInfosByViewId("android:id/button1")
                        .firstOrNull { it.isEnabled }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                VpnService.prepare(context) == null
            }
        }
        val selected = app.perfectdnsmanager.data.DnsProfile(
            id = 999999L, providerName = "Test provider", name = "Personal DoH",
            type = app.perfectdnsmanager.data.DnsType.DOH,
            primary = "https://cloudflare-dns.com/dns-query", isCustom = true)
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putString("selected_profile_json", com.google.gson.Gson().toJson(selected)).commit()
        return activity
    }
    @Test fun fixtureProvesBlockingForwardingLiveExceptionsAndConfigRoundTrip() {
        val activity = prepare()
        try {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val selected = prefs.getString("selected_profile_json", null)
            repository.save(DnsPrefilterRepository.Settings(true, false, "blocked.prefilter.test\nreload.prefilter.test", ""))
            start("127.0.0.1")
            for (type in listOf(Type.A, Type.AAAA, Type.HTTPS)) {
                assertEquals(Rcode.NXDOMAIN, request("never.blocked.prefilter.test", type).rcode)
                assertEquals(Rcode.NOERROR, request("allowed.prefilter.test", type).rcode)
            }
            assertEquals("192.0.2.123", (request("allowed.prefilter.test").getSection(Section.ANSWER)[0] as ARecord).address.hostAddress)
            assertTrue(request("allowed.prefilter.test", Type.AAAA).getSection(Section.ANSWER)[0] is AAAARecord)
            val service = DnsVpnService.instance
            repository.save(repository.settings().copy(allowed = "reload.prefilter.test"))
            waitFor { request("reload.prefilter.test").rcode == Rcode.NOERROR }
            assertSame("No VPN restart needed", service, DnsVpnService.instance)
            val exported = ConfigManager(context).exportConfigSelective()
            repository.save(repository.settings().copy(enabled = false))
            waitFor { request("disabled.blocked.prefilter.test").rcode == Rcode.NOERROR }
            ConfigManager(context).importConfig(exported)
            waitFor { request("restored.blocked.prefilter.test").rcode == Rcode.NXDOMAIN }
            assertEquals(selected, prefs.getString("selected_profile_json", null))
            assertFalse(prefs.getBoolean("disable_ipv6", false))
        } finally { stop(); instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun samePrefilterWorksWithTwoSelectedDohProviders() {
        val activity = prepare()
        try {
            repository.save(DnsPrefilterRepository.Settings(true, true, "blocked.prefilter.test", ""))
            val endpoints = InstrumentationRegistry.getArguments().getString("prefilterDohEndpoint")
                ?.let { listOf(it) } ?: listOf("https://cloudflare-dns.com/dns-query", "https://freedns.controld.com/p0")
            for (endpoint in endpoints) {
                start(endpoint)
                assertEquals(Rcode.NXDOMAIN, request("blocked.prefilter.test").rcode)
                assertEquals(Rcode.NXDOMAIN, request("g.doubleclick.net").rcode)
                for (type in listOf(Type.A, Type.AAAA)) {
                    val reply = request("example.com", type)
                    assertEquals(endpoint, Rcode.NOERROR, reply.rcode)
                    assertTrue(endpoint, reply.getSection(Section.ANSWER).any { it.type == type })
                }
                stop()
            }
        } finally { stop(); instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun unboundVpnPreservesBothFamiliesAndRunsPrefilterBeforeResolution() {
        val activity = prepare()
        try {
            repository.save(DnsPrefilterRepository.Settings(true, false, "blocked.prefilter.test", ""))
            start("unbound://one.one.one.one")
            assertEquals(Rcode.NXDOMAIN, request("blocked.prefilter.test").rcode)
            for (type in listOf(Type.A, Type.AAAA)) {
                val reply = request("cloudflare.com", type)
                assertEquals(Rcode.NOERROR, reply.rcode)
                assertTrue(reply.getSection(Section.ANSWER).any { it.type == type })
                assertTrue(reply.header.getFlag(Flags.AD.toInt()))
            }
            assertFalse(context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("disable_ipv6", false))
            stop()
            assertFalse(DnsVpnService.isVpnRunning)
        } finally { stop(); instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun screenSavesWithoutStartingVpnAndKeepsSelectedProvider() {
        val main = prepare()
        val activity = instrumentation.startActivitySync(Intent(context, DnsPrefilterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val selected = prefs.getString("selected_profile_json", null)
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.runOnMainSync {
                assertEquals(R.id.prefilterBuiltin, activity.currentFocus?.id)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "prefilter-screen.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync {
                assertFalse(activity.findViewById<CheckBox>(R.id.prefilterEnabled).isChecked)
                activity.findViewById<CheckBox>(R.id.prefilterEnabled).performClick()
                activity.findViewById<Button>(R.id.prefilterSave).performClick()
            }
            waitFor { repository.settings().enabled }
            assertFalse(DnsVpnService.isVpnRunning)
            assertEquals(selected, prefs.getString("selected_profile_json", null))
        } finally { instrumentation.runOnMainSync { activity.finish(); main.finish() } }
    }
}
