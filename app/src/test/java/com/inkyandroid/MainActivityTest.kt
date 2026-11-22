package com.inkyandroid

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.ui.awaitSession
import com.inkyandroid.ui.workspace.editorStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** A first start: the sample project is open at the editor, ready to write in, and its story plays. */
    @Test
    fun aFirstStartOpensTheSampleProjectAtTheEditor() {
        compose.onNode(hasText("Yaz") and isSelectable()).assertIsSelected()
        val holder = compose.activity.let { activity ->
            androidx.lifecycle.ViewModelProvider(activity, com.inkyandroid.session.ProjectSessionHolder.Factory((activity.application as InkyApplication).sessionEnvironment))[com.inkyandroid.session.ProjectSessionHolder::class.java]
        }
        val session = compose.awaitSession(holder)
        assertEquals("İlk hikâye.ink", session.state.value.activePath)
        assertTrue(editorStateOf(session.activeDocument.buffer).text.startsWith("Once upon a time"))
    }
}
