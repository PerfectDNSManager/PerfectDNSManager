package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderNavigationInstrumentedTest {
    @Test fun dpadKeepsColumnAcrossScrolledRowsAndCanExitToToolbar() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        check(ctx.packageName.endsWith(".design"))
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val keys = listOf("allow_adblock_profiles", "show_doq_dns", "adb_dot_enabled", "show_standard_dns", "operator_dns_enabled")
        val previous = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        prefs.edit().apply { keys.forEach { putBoolean(it, true) } }.commit()
        try {
            ActivityScenario.launch<DnsSelectionActivity>(Intent(ctx, DnsSelectionActivity::class.java)).use { scenario ->
                inst.waitForIdleSync()
                var count = 0
                scenario.onActivity { a ->
                    val list = a.findViewById<RecyclerView>(R.id.rvDnsList)
                    count = list.adapter!!.itemCount
                    assertTrue(count > list.childCount) // Exercise recycling, not only visible rows.
                    assertTrue(list.layoutManager!!.findViewByPosition(0)!!.findViewById<View>(R.id.layoutProviderButton).requestFocus())
                }
                fun key(code: Int) { inst.sendKeyDownUpSync(code); inst.waitForIdleSync() }
                fun checkFocus(position: Int, id: Int) {
                    scenario.onActivity { a ->
                        val focus = a.currentFocus!!
                        assertEquals("row=$position", id, focus.id)
                        val list = a.findViewById<RecyclerView>(R.id.rvDnsList)
                        assertEquals(position, list.getChildAdapterPosition(list.findContainingItemView(focus)!!))
                    }
                }
                for (position in 0 until count) {
                    checkFocus(position, R.id.layoutProviderButton)
                    key(KeyEvent.KEYCODE_DPAD_RIGHT); checkFocus(position, R.id.tvType)
                    key(KeyEvent.KEYCODE_DPAD_LEFT); checkFocus(position, R.id.layoutProviderButton)
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                }
                checkFocus(count - 1, R.id.layoutProviderButton)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                for (position in count - 1 downTo 0) {
                    checkFocus(position, R.id.tvType)
                    if (position > 0) key(KeyEvent.KEYCODE_DPAD_UP)
                }
                key(KeyEvent.KEYCODE_DPAD_UP)
                scenario.onActivity { assertEquals(R.id.btnAddProfile, it.currentFocus!!.id) }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                scenario.onActivity { assertEquals(R.id.btnSpeedtest, it.currentFocus!!.id) }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                checkFocus(0, R.id.layoutProviderButton)
                key(KeyEvent.KEYCODE_DPAD_UP)
                scenario.onActivity { assertEquals(R.id.btnSpeedtest, it.currentFocus!!.id) }
            }
        } finally {
            prefs.edit().apply { previous.forEach { (key,value) -> if(value == null) remove(key) else putBoolean(key,value) } }.commit()
        }
    }
}
