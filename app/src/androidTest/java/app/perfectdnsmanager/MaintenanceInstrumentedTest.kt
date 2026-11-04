package app.perfectdnsmanager

import android.widget.CompoundButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.data.DnsRewriteRule
import app.perfectdnsmanager.ui.DnsRewriteAdapter
import app.perfectdnsmanager.util.SecureReportBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaintenanceInstrumentedTest {
    @Test fun recycledRuleDoesNotTogglePreviousRow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = DnsRewriteRule(1,"one.example","target.example",true)
                val second = DnsRewriteRule(2,"two.example","target.example",false)
                val changed = mutableListOf<Long>()
                val adapter = DnsRewriteAdapter(mutableListOf(first,second),{changed.add(it.id)},{})
                val holder = adapter.onCreateViewHolder(android.widget.FrameLayout(activity),0)
                adapter.onBindViewHolder(holder,0)
                adapter.onBindViewHolder(holder,1)
                assertTrue(first.isEnabled);assertTrue(changed.isEmpty())
                holder.itemView.findViewById<CompoundButton>(R.id.switchEnableRule).isChecked=true
                assertEquals(listOf(2L),changed)
            }
        }
    }
    @Test fun persistedProfileFieldNamesSurviveOptimization() {
        val json = """{"id":123,"providerName":"Custom","name":"Legacy","type":"DOH","primary":"https://example.com/dns-query","isCustom":true}"""
        val gson = com.google.gson.Gson()
        val profile = gson.fromJson(json, app.perfectdnsmanager.data.DnsProfile::class.java)
        assertEquals("Legacy",profile.name)
        assertEquals(app.perfectdnsmanager.data.DnsType.DOH,profile.type)
        assertEquals("https://example.com/dns-query",profile.primary)
        val output = com.google.gson.JsonParser.parseString(gson.toJson(profile)).asJsonObject
        assertEquals("Legacy",output.get("name").asString)
        assertEquals("DOH",output.get("type").asString)
        assertTrue(output.get("isCustom").asBoolean)
    }

    @Test fun reportRespectsOptionalSectionsAndSnapshot() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot=SecureReportBuilder.Snapshot("PRIVATE_CARRIER","192.0.2.1","2001:db8::1","PRIVATE_DNS","PRIVATE_PROFILE","TEST_KERNEL",null,null,null,null)
        val builder=SecureReportBuilder(context,snapshot)
        val minimal=builder.build(false,false,false,false,false)
        for(secret in listOf("PRIVATE_CARRIER","192.0.2.1","2001:db8::1","PRIVATE_DNS","PRIVATE_PROFILE","TEST_KERNEL"))assertFalse(minimal.contains(secret))
        val device=builder.build(false,false,false,false,true)
        assertTrue(device.contains("TEST_KERNEL"));assertFalse(device.contains("PRIVATE_CARRIER"))
        val network=builder.build(true,false,false,false,false)
        assertTrue(network.contains("PRIVATE_PROFILE"));assertTrue(network.contains("2001:db8::1"));assertFalse(network.contains("TEST_KERNEL"))
    }
}
