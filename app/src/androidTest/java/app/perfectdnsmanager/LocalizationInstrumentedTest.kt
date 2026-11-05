package app.perfectdnsmanager

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.perfectdnsmanager.data.ConfigManager
import app.perfectdnsmanager.util.SecureReportBuilder
import app.perfectdnsmanager.util.SpeedTester
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
    private val languages = listOf("fr", "en", "de", "es", "it", "pt-BR", "ru", "zh-CN", "ja", "ar", "hi", "bn")
    private fun context(language: String): android.content.Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(config)
    }

    @Test fun compiledTranslationsRetainFormattingAndVisibleQuotes() {
        val format = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?([a-zA-Z%])")
        for (language in languages) {
            val ctx = context(language)
            for (field in R.string::class.java.fields) {
                val id = field.getInt(null)
                val value = ctx.getString(id)
                val args = mutableMapOf<Int, Any>()
                var next = 1
                for (match in format.findAll(value)) {
                    val type = match.groupValues[2].lowercase()
                    if (type == "%" || type == "n") continue
                    val index = match.groupValues[1].toIntOrNull() ?: next
                    next = index + 1
                    args[index] = when (type) {
                        "d", "o", "x" -> 3
                        "e", "f", "g", "a" -> 1.5
                        "c" -> 'X'
                        else -> "EXAMPLE"
                    }
                }
                if (args.isNotEmpty()) {
                    val values = (1..args.keys.max()).map { args[it] ?: "EXAMPLE" }.toTypedArray()
                    try { ctx.getString(id, *values) }
                    catch (error: Exception) { throw AssertionError("$language/${field.name}", error) }
                }
            }
            for (field in R.plurals::class.java.fields) {
                for (count in listOf(0, 1, 2, 3, 11, 100)) {
                    val args: Array<Any> = if (field.name == "domain_tester_unblocks_fmt") arrayOf("EXAMPLE") else arrayOf(count)
                    assertTrue(ctx.resources.getQuantityString(field.getInt(null), count, *args).isNotBlank())
                }
            }
            val conjunction = ctx.getString(R.string.domain_tester_join_and)
            assertTrue("$language conjunction spaces", conjunction.startsWith(" ") && conjunction.endsWith(" "))
            val search = ctx.getString(R.string.no_search_results_fmt, "EXAMPLE")
            assertTrue("$language visible search term", search.contains("EXAMPLE"))
            if (language == "en" || language == "es") assertTrue(search.contains("\"EXAMPLE\""))
            assertFalse(ctx.getString(R.string.import_password_label).contains("6"))
        }
    }

    @Test fun reportsAndImportPreviewUseRequestedLanguage() {
        for (language in languages) {
            val ctx = context(language)
            val snapshot = SecureReportBuilder.Snapshot(null, null, "", "", "", "", null, null, null, SpeedTester.SpeedResult(1.0, 2.0, 3))
            val report = SecureReportBuilder(ctx, snapshot).build(false, true, false, false, false)
            assertTrue(report.contains(ctx.getString(R.string.version_label)))
            assertTrue(report.contains(ctx.getString(R.string.speed_download_label)))
            assertTrue(report.contains(ctx.getString(R.string.speed_upload_label)))
            val config = ConfigManager(ctx).getConfigSummary("""{"version":"1","profiles":[],"rewriteRules":[],"testDomains":[],"settings":{"disable_ipv6":true}}""")
            assertTrue("$language: $config", config.contains(ctx.getString(R.string.config_profiles_summary, 0, 0)))
            assertTrue(config.contains(ctx.getString(R.string.disable_ipv6_label)))
        }
    }
}
