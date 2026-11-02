package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.data.DnsProfile
import app.perfectdnsmanager.data.DnsType
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemDotMainInstrumentedTest {
    @Test fun externalDotOverridesStaleSelectionAndSurvivesDisable() {
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val stale = DnsProfile.getDefaultPresets().first { it.type == DnsType.DOH }
        prefs.edit().putString("selected_profile_json",Gson().toJson(stale)).putBoolean("vpn_active",false).commit()
        try {
            shell("pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS")
            shell("settings put global private_dns_specifier one.one.one.one")
            shell("settings put global private_dns_mode hostname")
            ActivityScenario.launch<MainActivity>(Intent(ctx,MainActivity::class.java)).use { scenario ->
                scenario.onActivity { a ->
                    val selected=Gson().fromJson(prefs.getString("selected_profile_json",null),DnsProfile::class.java)
                    assertEquals("one.one.one.one",selected.primary)
                    assertEquals(DnsType.DOT,selected.type)
                    assertTrue(a.findViewById<TextView>(R.id.tvSelectDns).text.contains("Cloudflare"))
                    assertTrue(a.findViewById<TextView>(R.id.tvSelectDns).text.contains("DoT"))
                }
                // Exercise the actual one-click controls, not only Settings writes.
                scenario.onActivity { it.findViewById<android.view.View>(R.id.rowActivationToggle).performClick() }
                fun awaitMode(expected: String) {
                    val until = android.os.SystemClock.elapsedRealtime() + 5000
                    while (android.provider.Settings.Global.getString(ctx.contentResolver,"private_dns_mode") != expected &&
                        android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(50)
                    assertEquals(expected, android.provider.Settings.Global.getString(ctx.contentResolver,"private_dns_mode"))
                    inst.waitForIdleSync()
                }
                awaitMode("off")
                scenario.onActivity { it.findViewById<android.view.View>(R.id.rowActivationToggle).performClick() }
                awaitMode("hostname")
                assertEquals("one.one.one.one",android.provider.Settings.Global.getString(ctx.contentResolver,"private_dns_specifier"))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                shell("settings put global private_dns_mode off")
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity {
                    val selected=Gson().fromJson(prefs.getString("selected_profile_json",null),DnsProfile::class.java)
                    assertEquals("one.one.one.one",selected.primary)
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                shell("settings put global private_dns_specifier private.example.org")
                shell("settings put global private_dns_mode hostname")
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { a ->
                    val selected=Gson().fromJson(prefs.getString("selected_profile_json",null),DnsProfile::class.java)
                    assertEquals("private.example.org",selected.primary)
                    assertTrue(selected.isCustom)
                    assertTrue(a.findViewById<TextView>(R.id.tvSelectDns).text.contains("private.example.org"))
                }
            }
        } finally {
            ctx.stopService(Intent(ctx,app.perfectdnsmanager.service.DotMonitorService::class.java))
            shell("settings put global private_dns_mode off")
            shell("settings delete global private_dns_specifier")
        }
    }
}
