package com.inkyandroid

import android.os.Bundle
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.session.WindowProject
import com.inkyandroid.ui.Commands
import com.inkyandroid.ui.Routes
import com.inkyandroid.ui.TestTimeLimit
import com.inkyandroid.ui.WindowUi
import com.inkyandroid.ui.waitFor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * A window the system ended in the background (the process killed to free memory) and started again
 * from what it saved: it comes back on its project, at the screen it showed, in every layout. A
 * large window's sidebar lists the project's files as routes of their own, which are not there
 * before the project has opened again (the regression of review-ui finding 1: a crash at every
 * such start on DeX and on the tablet in landscape).
 *
 * The activities are the real ones, built by Robolectric; the compose rule only puts their frames
 * on Compose's test clock, so that an animation that runs while the project opens (the progress
 * bar) cannot keep the main looper busy for ever.
 */
@RunWith(AndroidJUnit4::class)
class RestoreAfterProcessDeathTest {

    // a test that hangs fails within its limit instead of holding the machine's shared build lock
    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    @get:Rule
    val compose = createEmptyComposeRule()

    /**
     * The process's settings and session environment belong to the first application they saw;
     * Robolectric gives every test an application (and files) of its own, so they are made again
     * for this one, as a real start of the process would.
     */
    @Before
    fun startAsANewProcess() {
        listOf(com.inkyandroid.session.SessionEnvironment::class.java, com.inkyandroid.settings.AppSettings::class.java).forEach { type ->
            type.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
        }
    }

    private fun holderOf(activity: MainActivity): ProjectSessionHolder =
        ViewModelProvider(activity, ProjectSessionHolder.Factory((activity.application as InkyApplication).sessionEnvironment))[ProjectSessionHolder::class.java]

    private fun uiOf(activity: MainActivity): WindowUi = ViewModelProvider(activity)[WindowState::class.java].ui

    /** Starts the app as the launcher does (the sample project opens), lets [before] act on it, then kills and restores it; the new window. */
    private fun startKillAndRestore(before: (MainActivity) -> Unit = {}): MainActivity {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val holder = holderOf(first.get())
        compose.waitFor { holder.session != null }
        compose.waitForIdle()
        compose.runOnUiThread { before(first.get()) }
        compose.waitForIdle()
        val saved = Bundle()
        first.saveInstanceState(saved)
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).setup(saved)
        val again = holderOf(second.get())
        compose.waitFor { again.session != null || again.project.value is WindowProject.Failed }
        compose.waitForIdle()
        return second.get()
    }

    @Test
    @Config(qualifiers = "en-w1920dp-h1080dp-mdpi")
    fun aDexWindowComesBackOnItsFile() {
        val restored = startKillAndRestore()
        val session = holderOf(restored).session!!
        compose.waitFor { uiOf(restored).route == Routes.file(session.state.value.activePath) }
    }

    @Test
    @Config(qualifiers = "en-w1480dp-h924dp-mdpi")
    fun aTabletOnItsSideComesBackOnItsFile() {
        val restored = startKillAndRestore()
        val session = holderOf(restored).session!!
        compose.waitFor { uiOf(restored).route == Routes.file(session.state.value.activePath) }
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    fun aPhoneComesBackOnTheScreenItShowed() {
        // "Player" (Alt+1): on a phone the Play screen
        val restored = startKillAndRestore { activity -> activity.commands.perform(Commands.SHOW_PLAYER) }
        compose.waitFor { uiOf(restored).route == Routes.PLAY }
        assertEquals(Routes.PLAY, uiOf(restored).route)
    }
}
