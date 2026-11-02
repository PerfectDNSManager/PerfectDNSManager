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
import app.perfectdnsmanager.service.UpdateProgressUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateProgressInstrumentedTest {
    @Test fun knownAndUnknownSizeVerificationAndPersistentError() {
        val inst=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch<SettingsActivity>(Intent(inst.targetContext,SettingsActivity::class.java)).use { scenario ->
            lateinit var ui: UpdateProgressUi
            scenario.onActivity { ui=UpdateProgressUi(it){};ui.show();ui.progress(512*1024,1024*1024) }
            onView(isAssignableFrom(ProgressBar::class.java)).inRoot(isDialog()).check { view,_ ->
                val bar=view as ProgressBar; assertFalse(bar.isIndeterminate);assertEquals(50,bar.progress)
            }
            scenario.onActivity { ui.progress(0,0) }
            onView(isAssignableFrom(ProgressBar::class.java)).inRoot(isDialog()).check { view,_ -> assertTrue((view as ProgressBar).isIndeterminate) }
            scenario.onActivity { ui.stage(R.string.update_verifying) }
            onView(withText(R.string.update_verifying)).inRoot(isDialog()).check(matches(isDisplayed()))
            scenario.onActivity { ui.error(it.getString(R.string.update_download_error)) }
            onView(withText(R.string.update_download_error)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        }
    }
    @Test fun explicitCancellationAndActivityDestructionCancelTheTransfer() {
        val inst=InstrumentationRegistry.getInstrumentation()
        var cancelled=false;lateinit var ui:UpdateProgressUi
        val scenario=ActivityScenario.launch<SettingsActivity>(Intent(inst.targetContext,SettingsActivity::class.java))
        scenario.onActivity { ui=UpdateProgressUi(it){cancelled=true};ui.show() }
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        assertTrue(cancelled);assertTrue(ui.cancelled.get())
        scenario.onActivity { ui.close();cancelled=false;ui=UpdateProgressUi(it){cancelled=true};ui.show() }
        scenario.close();assertTrue(cancelled);assertTrue(ui.cancelled.get())
    }
}
