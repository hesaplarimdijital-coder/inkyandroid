package com.inkyandroid

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real activity, as the launcher starts it on a phone. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr-w411dp-h891dp-xxhdpi")
class MainActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** A first start: the shell at its only screen, which says that the app is being set up. */
    @Test
    fun startsAtTheStoryAndSaysTheAppIsBeingSetUp() {
        compose.onNode(hasText("Hikâye") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("Inky kuruluyor").assertExists()
    }
}
