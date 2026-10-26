package app.perfectdnsmanager

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.data.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately in a fresh instrumentation process to exercise the disk migration. */
@RunWith(AndroidJUnit4::class)
class RetiredProfilesInstrumentedTest {
    @Test fun upgradeRemovesRetiredDefaultsButKeepsPersonalProfiles() {
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("dns_profiles_v2", Context.MODE_PRIVATE)
        val retired = DnsProfile(id = 1101, providerName = "Surfshark", name = "Standard", type = DnsType.DOH,
            primary = "https://dns.surfsharkdns.com/dns-query")
        val personal = retired.copy(id = 700001, name = "Personal", isCustom = true)
        prefs.edit().putString("profiles", Gson().toJson(listOf(retired, personal))).commit()
        val manager = ProfileManager(ctx)
        try {
            val loaded = manager.loadProfiles()
            assertFalse(loaded.any { it.id == retired.id })
            assertTrue(loaded.contains(personal))
            assertFalse(prefs.getString("profiles", "")!!.contains("\"id\":1101"))
            manager.restoreDefaults()
            assertFalse(manager.loadProfiles().any(ProfileCatalog::isRetiredPreset))
            assertTrue(manager.loadProfiles().contains(personal))
        } finally { manager.resetAll() }
    }
}
