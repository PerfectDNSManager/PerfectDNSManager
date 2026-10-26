package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.widget.CheckBox
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.recyclerview.widget.RecyclerView
import app.perfectdnsmanager.data.DnsType
import app.perfectdnsmanager.data.ProfileManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolFiltersInstrumentedTest {
    @Test fun protocolsFollowSettingsAndDefaultToDoh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".design"))
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val keys = listOf("adb_dot_enabled", "show_doq_dns", "show_standard_dns", "operator_dns_enabled")
        val profiles = ProfileManager(context).loadProfiles()
        try {
            for (mask in 0..15) {
                prefs.edit().apply { keys.forEachIndexed { i, key -> putBoolean(key, mask and (1 shl i) != 0) } }.commit()
                val visible = profiles.filter { DnsProtocolVisibility.isVisible(it, prefs) }
                assertTrue(visible.any { it.type == DnsType.DOH })
                for (profile in profiles) {
                    val expected = (!profile.isOperatorDns || mask and 8 != 0) && when(profile.type) {
                        DnsType.DOH -> true
                        DnsType.DOT -> mask and 1 != 0
                        DnsType.DOQ -> mask and 2 != 0
                        DnsType.DEFAULT -> if (profile.isOperatorDns) mask and 8 != 0 else mask and 4 != 0
                    }
                    assertEquals("mask=$mask profile=${profile.name}", expected, profile in visible)
                }
            }
            prefs.edit().apply { keys.forEach { putBoolean(it, false) } }.commit()
            ActivityScenario.launch<DnsSelectionActivity>(Intent(context, DnsSelectionActivity::class.java)).use { scenario ->
                fun assertCatalog(activity: DnsSelectionActivity) {
                    fun hasCheckbox(view: View): Boolean = view is CheckBox || (view is ViewGroup && (0 until view.childCount).any { hasCheckbox(view.getChildAt(it)) })
                    assertFalse(hasCheckbox(activity.window.decorView))
                    assertTrue(activity.findViewById<RecyclerView>(R.id.rvDnsList).adapter!!.itemCount > 0)
                    assertEquals(setOf(DnsType.DOH), DnsProtocolVisibility.allowedTypes(prefs))
                }
                scenario.onActivity { assertCatalog(it) }
                scenario.recreate()
                scenario.onActivity { assertCatalog(it) }
            }
        } finally {
            prefs.edit().apply { keys.forEach { putBoolean(it, false) } }.commit()
        }
    }
}
