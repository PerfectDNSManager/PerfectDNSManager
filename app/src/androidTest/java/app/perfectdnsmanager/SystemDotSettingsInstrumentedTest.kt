package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.service.AdbDnsManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemDotSettingsInstrumentedTest {
    @Test fun externalDotIsDisplayedWithoutSavedProfileAndRefreshesOnResume() {
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        prefs.edit().remove("selected_profile_json").putBoolean("auto_reconnect_dns", true).commit()
        try {
            shell("settings put global private_dns_specifier dns.google")
            shell("settings put global private_dns_mode hostname")
            ActivityScenario.launch<SettingsActivity>(Intent(ctx, SettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { a ->
                    assertEquals(a.getString(R.string.auto_reconnect_system_dot, "dns.google"),
                        a.findViewById<TextView>(R.id.tvAutoReconnectDns).text.toString())
                    assertFalse(a.findViewById<SwitchCompat>(R.id.switchAutoReconnect).isEnabled)
                    assertNull(prefs.getString("selected_profile_json", null))
                    assertTrue(prefs.getBoolean("auto_reconnect_dns", false))
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                shell("settings put global private_dns_mode off")
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { a ->
                    assertTrue(a.findViewById<SwitchCompat>(R.id.switchAutoReconnect).isEnabled)
                    assertEquals(a.getString(R.string.auto_reconnect_no_dns),
                        a.findViewById<TextView>(R.id.tvAutoReconnectDns).text.toString())
                }
            }
        } finally {
            shell("settings put global private_dns_mode off")
            shell("settings delete global private_dns_specifier")
        }
    }
}
