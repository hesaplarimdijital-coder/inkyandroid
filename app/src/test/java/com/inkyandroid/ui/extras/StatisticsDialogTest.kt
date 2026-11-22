package com.inkyandroid.ui.extras

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.testPrefs
import desktopkit.DesktopKitRoot
import desktopkit.theme.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Word count and more": counting, the numbers in upstream's groups, a failure, closing. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = Devices.PHONE)
class StatisticsDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val numbers = StoryStatistics(words = 12_345, knots = 41, stitches = 17, functions = 3, choices = 120, gathers = 60, diverts = 210)

    @Test
    fun showsCountingThenTheNumbers() {
        val answer = CompletableDeferred<StoryStatistics>()
        var dismissed = false
        compose.setContent { DesktopKitRoot(testPrefs(ThemeMode.Light)) { StatisticsDialog({ answer.await() }, { dismissed = true }) } }
        compose.onNodeWithTag(StatisticsTags.BUSY).assertIsDisplayed()
        answer.complete(numbers)
        compose.waitForIdle()
        compose.onNodeWithTag(StatisticsTags.NUMBERS).assertIsDisplayed()
        // Turkish grouping of thousands
        compose.onNodeWithText("12.345").assertIsDisplayed()
        compose.onNodeWithText("Düğümler").assertIsDisplayed()
        compose.onNodeWithText("210").assertIsDisplayed()
        compose.onNodeWithText("Yönlendirmelere END ve DONE da dahildir", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kapat").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun aFailureIsShownInTheDialog() {
        compose.setContent { DesktopKitRoot(testPrefs(ThemeMode.Dark)) { StatisticsDialog({ error("the ink could not be parsed") }, {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag(StatisticsTags.FAILED).assertIsDisplayed()
        compose.onNodeWithText("İstatistikler hesaplanamadı: the ink could not be parsed").assertIsDisplayed()
    }
}
