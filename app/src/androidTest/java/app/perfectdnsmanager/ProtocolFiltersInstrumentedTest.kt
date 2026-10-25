package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProtocolFiltersInstrumentedTest {
    @Test fun filtersKeepConnectionSettingsAndSurviveRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".design")) { "Local design build only" }
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_doq_dns", false).putBoolean("show_standard_dns", false)
            .putBoolean("adb_dot_enabled", false).putBoolean("operator_dns_enabled", false)
            .putBoolean("allow_adblock_profiles", false).putBoolean("disable_ipv6", false).commit()
        ActivityScenario.launch<DnsSelectionActivity>(Intent(context, DnsSelectionActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val bar = activity.findViewById<LinearLayout>(R.id.protocolFilters)
                val doh = bar.getChildAt(0) as CheckBox
                val dot = bar.getChildAt(1) as CheckBox
                val doq = bar.getChildAt(2) as CheckBox
                assertTrue(doh.isChecked); assertFalse(dot.isEnabled); assertFalse(doq.isChecked)
                assertTrue(activity.findViewById<RecyclerView>(R.id.rvDnsList).adapter!!.itemCount > 0)
                doh.performClick()
                assertEquals(0, activity.findViewById<RecyclerView>(R.id.rvDnsList).adapter!!.itemCount)
                assertEquals(activity.getString(R.string.protocol_filter_empty),activity.findViewById<TextView>(R.id.tvFilterResults).text)
                doq.performClick()
                assertTrue(activity.findViewById<RecyclerView>(R.id.rvDnsList).adapter!!.itemCount > 0)
                assertFalse(prefs.getBoolean("show_doq_dns", true))
                assertFalse(prefs.getBoolean("disable_ipv6", true))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val bar = activity.findViewById<LinearLayout>(R.id.protocolFilters)
                assertFalse((bar.getChildAt(0) as CheckBox).isChecked)
                assertTrue((bar.getChildAt(2) as CheckBox).isChecked)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(context.filesDir, "design-catalog.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
    }
}
