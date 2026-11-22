package com.inkyandroid.ui.extras

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.ui.extras.settings.Licence
import com.inkyandroid.ui.extras.settings.THIRD_PARTIES
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every licence of the work of others the app ships is there to read. */
@RunWith(AndroidJUnit4::class)
class LicencesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    @Test
    fun everyLicenceIsThereToRead() {
        val expected = mapOf(
            Licence.MitInky to "Copyright (c) 2016 inkle Ltd.",
            Licence.MitInk to "inkle Ltd.",
            Licence.MitInkjs to "inkjs contributors",
            Licence.MitBladeInk to "Rafael Garcia",
            Licence.Apache2 to "Apache License",
            Licence.Bsd2 to "Atlassian Pty Ltd",
            Licence.Ofl to "SIL OPEN FONT LICENSE",
        )
        assertTrue(expected.keys == Licence.entries.toSet())
        for ((licence, words) in expected) assertTrue(licence.name, text(licence.text).contains(words))
        for (licence in Licence.entries) assertTrue(licence.name, THIRD_PARTIES.any { it.licence == licence })
        // the kit asks for its NOTICE and the fonts' licence to be shown by the apps that ship it
        val kit = THIRD_PARTIES.first { it.what == "desktop-kit" }
        assertTrue(text(kit.notice!!).contains("IBM Plex"))
    }
}
