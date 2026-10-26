package app.perfectdnsmanager

import android.content.Context
import android.content.Intent
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.data.UpdateChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateChannelInstrumentedTest {
    @Test fun optOutSurvivesBetaInitializationAndSettingsRecreation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("beta_updates_enabled", true).putBoolean("beta_channel_forced", true).commit()
        UpdateChannel.initialize(prefs, true)
        ActivityScenario.launch<SettingsActivity>(Intent(ctx, SettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(a.findViewById<android.view.View>(R.id.rowBetaUpdates).isClickable)
                a.findViewById<android.view.View>(R.id.rowBetaUpdates).performClick()
                assertFalse(a.findViewById<SwitchCompat>(R.id.switchBetaUpdates).isChecked)
                assertFalse(prefs.getBoolean("beta_updates_enabled", true))
                assertEquals(android.view.View.VISIBLE, a.findViewById<android.view.View>(R.id.btnReturnStable).visibility)
            }
            UpdateChannel.initialize(prefs, true)
            scenario.recreate()
            scenario.onActivity { a -> assertFalse(a.findViewById<SwitchCompat>(R.id.switchBetaUpdates).isChecked) }
            UpdateChannel.initialize(prefs, false)
            assertFalse(prefs.getBoolean("beta_updates_enabled", true))
        }
    }

    @Test fun defaultsAndLegacyForcedFlagMigrateWithoutOverwritingChoice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val p = ctx.getSharedPreferences("channel_regression", Context.MODE_PRIVATE)
        try {
            p.edit().clear().commit(); UpdateChannel.initialize(p, true)
            assertTrue(p.getBoolean("beta_updates_enabled", false))
            p.edit().putBoolean("beta_updates_enabled", false).commit(); UpdateChannel.initialize(p, true)
            assertFalse(p.getBoolean("beta_updates_enabled", true))
            p.edit().putBoolean("beta_updates_enabled", true).putBoolean("beta_channel_forced", true).commit()
            UpdateChannel.initialize(p, false)
            assertFalse(p.getBoolean("beta_updates_enabled", true))
            assertFalse(p.contains("beta_channel_forced"))
            p.edit().putBoolean("beta_updates_enabled", true).commit(); UpdateChannel.initialize(p, false)
            assertTrue(p.getBoolean("beta_updates_enabled", false))
        } finally { p.edit().clear().commit() }
    }
}
