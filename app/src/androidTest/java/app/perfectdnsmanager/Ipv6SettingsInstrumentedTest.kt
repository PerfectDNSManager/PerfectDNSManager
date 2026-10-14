package app.perfectdnsmanager

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.data.Ipv6Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ipv6SettingsInstrumentedTest {
    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("ipv6_settings_regression_test", Context.MODE_PRIVATE)

    @Before fun clearBefore() { prefs.edit().clear().commit() }
    @After fun clearAfter() { prefs.edit().clear().commit() }

    @Test fun freshInstallDoesNotBlockIpv6() {
        Ipv6Settings.initialize(prefs)
        assertFalse(prefs.getBoolean("disable_ipv6", true))
    }

    @Test fun legacyAutomaticBlockingIsRemoved() {
        prefs.edit().putBoolean("disable_ipv6", true).commit()
        Ipv6Settings.initialize(prefs)
        assertFalse(prefs.getBoolean("disable_ipv6", true))
    }

    @Test fun legacyDisabledBlockingStaysDisabled() {
        prefs.edit().putBoolean("disable_ipv6", false).commit()
        Ipv6Settings.initialize(prefs)
        assertFalse(prefs.getBoolean("disable_ipv6", true))
    }

    @Test fun explicitChoiceAfterMigrationSurvivesRestart() {
        Ipv6Settings.initialize(prefs)
        prefs.edit().putBoolean("disable_ipv6", true).commit()
        Ipv6Settings.initialize(prefs)
        assertTrue(prefs.getBoolean("disable_ipv6", false))
        prefs.edit().putBoolean("disable_ipv6", false).commit()
        Ipv6Settings.initialize(prefs)
        assertFalse(prefs.getBoolean("disable_ipv6", true))
    }
}
