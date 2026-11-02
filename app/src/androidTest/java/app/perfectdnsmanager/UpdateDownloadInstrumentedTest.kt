package app.perfectdnsmanager

import android.content.Intent
import android.widget.ProgressBar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import app.perfectdnsmanager.service.UpdateManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class UpdateDownloadInstrumentedTest {
    @Test fun actualDownloaderReportsProgressCancelsCallAndRejectsInvalidApk() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val cancelled=AtomicBoolean(false)
        fun start(activity: SettingsActivity, slow: Boolean) {
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                var remaining=if(slow)1024L*1024 else 4L
                val source=object: Source {
                    override fun read(sink:Buffer,byteCount:Long):Long {
                        if(chain.call().isCanceled()){cancelled.set(true);throw java.io.IOException("test cancelled")}
                        if(remaining==0L)return -1
                        if(slow)Thread.sleep(30)
                        val n=minOf(byteCount,remaining,4096L).toInt();sink.write(ByteArray(n));remaining-=n;return n.toLong()
                    }
                    override fun timeout()=Timeout.NONE
                    override fun close(){if(chain.call().isCanceled())cancelled.set(true)}
                }.buffer()
                val body=object:ResponseBody(){
                    override fun contentType()="application/vnd.android.package-archive".toMediaType()
                    override fun contentLength()=if(slow)1024L*1024 else 4L
                    override fun source()=source
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            }.build()
            val manager=UpdateManager(activity)
            // Test-only transport replacement: production HTTPS/host/signature checks are unchanged.
            UpdateManager::class.java.getDeclaredField("http\$delegate").apply { isAccessible=true;set(manager,lazyOf(client)) }
            UpdateManager::class.java.declaredMethods.first { it.name=="downloadAndInstallUpdate" }.apply {
                isAccessible=true;invoke(manager,"https://github.com/PerfectDNSManager/PerfectDNSManager/releases/download/test/test.apk",0L,null)
            }
        }
        fun awaitCondition(check:()->Unit) {
            val until=android.os.SystemClock.elapsedRealtime()+10000
            var error:Throwable?=null
            while(android.os.SystemClock.elapsedRealtime()<until){try{check();return}catch(e:Throwable){error=e};android.os.SystemClock.sleep(100)}
            throw AssertionError("Update state timed out",error)
        }
        ActivityScenario.launch<SettingsActivity>(Intent(inst.targetContext,SettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { start(it,true) }
            awaitCondition {
                onView(isAssignableFrom(ProgressBar::class.java)).inRoot(isDialog()).check { view,_ ->
                    val bar=view as ProgressBar;assertFalse(bar.isIndeterminate);assertTrue(bar.progress>0)
                }
            }
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            awaitCondition { assertTrue(cancelled.get());assertTrue(java.io.File(inst.targetContext.cacheDir,"updates").listFiles().orEmpty().isEmpty()) }
            inst.waitForIdleSync()
            scenario.onActivity { start(it,false) }
            awaitCondition { onView(withText(R.string.update_signature_mismatch)).inRoot(isDialog()).check(matches(isDisplayed())) }
            assertTrue(java.io.File(inst.targetContext.cacheDir,"updates").listFiles().orEmpty().isEmpty())
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        }
    }
}
