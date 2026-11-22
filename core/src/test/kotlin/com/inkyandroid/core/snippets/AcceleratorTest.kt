package com.inkyandroid.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcceleratorTest {

    @Test
    fun `Electron accelerators become Android modifiers and a key`() {
        assertEquals(Accelerator("K", ctrl = true, shift = true), Accelerator.parse("CmdOrCtrl+Shift+K"))
        assertEquals(Accelerator("K", ctrl = true), Accelerator.parse("Command+k"))
        assertEquals(Accelerator("F5", alt = true), Accelerator.parse("Option+f5"))
        assertEquals(Accelerator("1", meta = true), Accelerator.parse("Super+1"))
        assertEquals(Accelerator("Plus", ctrl = true), Accelerator.parse("Ctrl+Plus"))
        assertEquals(Accelerator("Plus", ctrl = true), Accelerator.parse("Ctrl++"))
        assertEquals(Accelerator("Space", alt = true), Accelerator.parse(" alt + space "))
        assertEquals(Accelerator("/", ctrl = true), Accelerator.parse("CommandOrControl+/"))
        assertEquals(Accelerator("num5"), Accelerator.parse("num5"))
        assertEquals(Accelerator("PageDown", shift = true), Accelerator.parse("Shift+pagedown"))
    }

    @Test
    fun `not accelerators`() {
        for (text in listOf("", "Ctrl", "Ctrl+Shift", "Ctrl+A+B", "Hyper+A", "Ctrl+F25", "Ctrl+ä", "Ctrl+Nope")) {
            assertNull(text, Accelerator.parse(text))
        }
    }
}
