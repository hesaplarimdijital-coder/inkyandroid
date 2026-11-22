package com.inkyandroid.ui

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.settings.ProjectSettings
import com.inkyandroid.core.snippets.Accelerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The keys a project's settings file gives its own snippets (upstream's custom snippet accelerators). */
@RunWith(AndroidJUnit4::class)
class SnippetKeysTest {

    private fun press(code: Int, meta: Int = 0) = androidx.compose.ui.input.key.KeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))

    @Test
    fun electronsNamesBecomeTheKitsChords() {
        val chord = Accelerator.parse("CmdOrCtrl+Shift+1")!!.keyChord()!!
        assertTrue(chord.matches(press(KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        assertFalse(chord.matches(press(KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON)))
        assertTrue(Accelerator.parse("Alt+F5")!!.keyChord()!!.matches(press(KeyEvent.KEYCODE_F5, KeyEvent.META_ALT_ON)))
        assertEquals('-', Accelerator.parse("Ctrl+-")!!.keyChord()!!.char)
        assertNull(Accelerator.parse("Ctrl+MediaPlayPause")?.keyChord())
    }

    @Test
    fun onlyKeysThatCannotBeTypedTextBecomeSnippetKeys() {
        val settings = ProjectSettings.parse(
            """
            { "customInkSnippets": [
                { "name": "Camera", "ink": ">>> CAMERA", "accelerator": "CmdOrCtrl+Shift+1" },
                { "name": "Plain", "ink": "x", "accelerator": "K" },
                { "name": "Group", "submenu": [ { "name": "Walk", "ink": ">>> WALK", "shortcut": "F6" } ] }
            ] }
            """.trimIndent(),
        )
        val keys = snippetKeys(settings.customInkSnippets)
        assertEquals(listOf("Camera", "Walk"), keys.map { it.snippet.name })
    }
}
