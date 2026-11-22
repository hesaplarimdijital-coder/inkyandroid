package com.inkyandroid.settings

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun settings() = AppSettings(context.getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE))

    @Test
    fun `the defaults are autosave on and every editor and player option on`() {
        val settings = settings()
        assertTrue(settings.autosave)
        assertTrue(settings.softWrap)
        assertTrue(settings.lineNumbers)
        assertTrue(settings.autoComplete)
        assertTrue(settings.keyboardSuggestions)
        assertTrue(settings.playAnimation)
        assertTrue(settings.tagsVisible)
        assertEquals(InspectorTab.Player, settings.inspectorTab)
        assertFalse(settings.inspectorCollapsed)
        assertEquals(0.5f, settings.inspectorShare)
        assertNull(settings.lastSession)
    }

    @Test
    fun `every setting survives a restart`() {
        settings().apply {
            updateAutosave(false)
            updateSoftWrap(false)
            updateLineNumbers(false)
            updateAutoComplete(false)
            updateKeyboardSuggestions(false)
            updatePlayAnimation(false)
            updateTagsVisible(false)
            updateInspectorTab(InspectorTab.Outline)
            updateInspectorCollapsed(true)
            updateInspectorShare(0.3f)
        }

        val restarted = settings()

        assertFalse(restarted.autosave)
        assertFalse(restarted.softWrap)
        assertFalse(restarted.lineNumbers)
        assertFalse(restarted.autoComplete)
        assertFalse(restarted.keyboardSuggestions)
        assertFalse(restarted.playAnimation)
        assertFalse(restarted.tagsVisible)
        assertEquals(InspectorTab.Outline, restarted.inspectorTab)
        assertTrue(restarted.inspectorCollapsed)
        assertEquals(0.3f, restarted.inspectorShare)
    }

    @Test
    fun `a change is seen by whoever observes the setting`() = runTest {
        val settings = settings()
        settings.updateAutosave(false)
        assertFalse(snapshotFlow { settings.autosave }.first())
        settings.updateInspectorTab(InspectorTab.Issues)
        assertEquals(InspectorTab.Issues, snapshotFlow { settings.inspectorTab }.first())
    }

    @Test
    fun `the inspector's share stays where both panes have room`() {
        val settings = settings()
        settings.updateInspectorShare(0.01f)
        assertEquals(AppSettings.MIN_INSPECTOR_SHARE, settings.inspectorShare)
        settings.updateInspectorShare(7f)
        assertEquals(AppSettings.MAX_INSPECTOR_SHARE, settings().inspectorShare)
    }

    @Test
    fun `an inspector tab this version does not know reads as the player`() {
        context.getSharedPreferences(AppSettings.FILE, Context.MODE_PRIVATE).edit().putString("inspector_tab", "Timeline").commit()
        assertEquals(InspectorTab.Player, settings().inspectorTab)
    }

    @Test
    fun `the last session survives a restart with its places`() {
        val session = LastSession(
            project = """{"v":1,"kind":"saf","tree":"content://x/tree/primary%3ADocuments","main":"hikâye.ink"}""",
            activeFile = "bölümler/bir.ink",
            places = mapOf("hikâye.ink" to FilePlace(3, 0, 1), "bölümler/bir.ink" to FilePlace(120, 14, 96)),
        )
        settings().updateLastSession(session)

        assertEquals(session, settings().lastSession)

        settings().updateLastSession(null)
        assertNull(settings().lastSession)
    }

    @Test
    fun `a last session without an active file means the main file`() {
        settings().updateLastSession(LastSession("ref"))
        assertEquals(LastSession("ref", null, emptyMap()), settings().lastSession)
    }

    @Test
    fun `a damaged last session is no last session`() {
        assertNull(LastSession.decode(null))
        assertNull(LastSession.decode(""))
        assertNull(LastSession.decode("not json"))
        assertNull(LastSession.decode("""{"active":"a.ink"}"""))
        assertEquals(
            "what can be read is read",
            LastSession("ref", null, mapOf("a.ink" to FilePlace(0, 0, 0))),
            LastSession.decode("""{"project":"ref","places":{"a.ink":{"row":-4},"b.ink":7}}"""),
        )
    }

    @Test
    fun `one instance serves the whole process`() {
        assertSame(AppSettings.of(context), AppSettings.of(context))
    }
}
