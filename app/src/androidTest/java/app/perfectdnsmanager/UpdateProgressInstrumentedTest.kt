package app.perfectdnsmanager

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.service.UpdateProgressUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateProgressInstrumentedTest {
    // API 21's indeterminate drawable continuously schedules frames, so Espresso
    // never becomes idle. Inspect the real dialog on the main thread instead.
    private fun dialog(ui: UpdateProgressUi): AlertDialog =
        UpdateProgressUi::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(ui) as AlertDialog
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun knownAndUnknownSizeVerificationAndPersistentError() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val activity = inst.startActivitySync(Intent(inst.targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        fun onActivity(action: (SettingsActivity) -> Unit) = inst.runOnMainSync { action(activity) }
        try {
            lateinit var ui: UpdateProgressUi
            onActivity { ui = UpdateProgressUi(it) {}; ui.show(); ui.progress(512 * 1024, 1024 * 1024) }
            // Separate main-thread barriers let posted progress updates execute.
            onActivity {
                val bar = descendants(dialog(ui).window!!.decorView).filterIsInstance<ProgressBar>().single()
                assertFalse(bar.isIndeterminate); assertEquals(50, bar.progress)
                ui.progress(0, 0)
            }
            onActivity {
                assertTrue(descendants(dialog(ui).window!!.decorView).filterIsInstance<ProgressBar>().single().isIndeterminate)
                ui.stage(R.string.update_verifying)
            }
            onActivity {
                assertTrue(descendants(dialog(ui).window!!.decorView).filterIsInstance<TextView>().any { v -> v.text == it.getString(R.string.update_verifying) && v.isShown })
                ui.error(it.getString(R.string.update_download_error))
            }
            onActivity {
                assertTrue(dialog(ui).isShowing)
                assertTrue(descendants(dialog(ui).window!!.decorView).filterIsInstance<TextView>().any { v -> v.text == it.getString(R.string.update_download_error) && v.isShown })
                dialog(ui).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertFalse(dialog(ui).isShowing)
            }
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun explicitCancellationAndActivityDestructionCancelTheTransfer() {
        val inst = InstrumentationRegistry.getInstrumentation()
        var cancelled = false
        lateinit var ui: UpdateProgressUi
        val activity = inst.startActivitySync(Intent(inst.targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        fun onActivity(action: (SettingsActivity) -> Unit) = inst.runOnMainSync { action(activity) }
        onActivity {
            ui = UpdateProgressUi(it) { cancelled = true }; ui.show()
            dialog(ui).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(cancelled); assertTrue(ui.cancelled.get())
            ui.close(); cancelled = false
            ui = UpdateProgressUi(it) { cancelled = true }; ui.show()
        }
        inst.runOnMainSync { activity.finish() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (!ui.cancelled.get() && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(20)
        assertTrue(cancelled); assertTrue(ui.cancelled.get())
    }
}
