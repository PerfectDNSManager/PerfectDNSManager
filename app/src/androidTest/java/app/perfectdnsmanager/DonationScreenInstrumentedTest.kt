package app.perfectdnsmanager

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DonationScreenInstrumentedTest {
    @Test fun sharedDonationPageLoadsWithRestrictedWebView() {
        // Explicit network smoke test, never creates an order or sends funds.
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDonationSmoke") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch<SupportActivity>(Intent(inst.targetContext,SupportActivity::class.java)).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { a ->
                assertNotNull(a.findViewById<ImageButton>(R.id.supportPageQr).drawable)
                web = a.findViewById<FrameLayout>(R.id.supportWebContainer).getChildAt(0) as WebView
                assertTrue(web.settings.javaScriptEnabled)
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW,web.settings.mixedContentMode)
                assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web))
            }
            val deadline = android.os.SystemClock.elapsedRealtime()+60000
            var options = "0"
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                val latch=CountDownLatch(1)
                inst.runOnMainSync {
                    web.evaluateJavascript("document.querySelectorAll('#asset option').length") { options=it;latch.countDown() }
                }
                assertTrue(latch.await(5,TimeUnit.SECONDS))
                if((options.toIntOrNull() ?: 0)>0)break
                android.os.SystemClock.sleep(500)
            }
            assertTrue("Live donation asset selector did not load",(options.toIntOrNull() ?: 0)>0)
            scenario.onActivity { assertTrue(web.url?.startsWith(BuildConfig.PDM_BASE_URL+"/support.html?")==true) }
        }
    }
}
