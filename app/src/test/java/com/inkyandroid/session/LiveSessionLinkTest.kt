package com.inkyandroid.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.live.CompileStatus
import com.inkyandroid.core.live.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The project session with the real live compile session (blade-ink) behind its link: editor → compiler → issues by file. */
@RunWith(AndroidJUnit4::class)
class LiveSessionLinkTest {

    @Test
    fun `the project compiles when it is open, and an error typed into an included file reaches the session by file and line`() = sessionTest {
        onStorage("main.ink", "INCLUDE chapter.ink\nHello.\n-> chapter\n")
        onStorage("chapter.ink", "=== chapter ===\nThe end.\n-> END\n")
        // the ink thread of the test is its one thread, as the app's is one thread
        val session = open(live = LiveSessionLink.factory(dispatcher))
        pass(0)

        val live = checkNotNull(session.live.session)
        assertEquals(CompileStatus.Ok, live.state.value.compile)
        assertEquals(listOf("Hello.", "The end."), live.state.value.transcript.items.filterIsInstance<TranscriptItem.Text>().map { it.text.trim() })
        assertEquals(emptyList<Any>(), session.state.value.issues)

        editor(session, "chapter.ink").type("=== chapter ===\nThe end.\n-> nowhere\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)

        val issue = session.state.value.issuesOf("chapter.ink").single()
        assertEquals(IssueKind.Error, issue.kind)
        assertEquals(3, issue.line)
        assertEquals(mapOf("chapter.ink" to 1), session.state.value.issueCounts)
        assertTrue("the last story that compiled stays", live.state.value.transcript.items.isNotEmpty())
    }

    @Test
    fun `an INCLUDE typed into the main file brings the new file to the compiler`() = sessionTest {
        onStorage("main.ink", "Hello.\n")
        onStorage("chapter.ink", "=== chapter ===\nFrom the chapter.\n-> END\n")
        val session = open(live = LiveSessionLink.factory(dispatcher))
        pass(0)

        editor(session, "main.ink").type("INCLUDE chapter.ink\nHello.\n-> chapter\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)

        val live = checkNotNull(session.live.session)
        assertEquals(CompileStatus.Ok, live.state.value.compile)
        assertEquals(listOf("Hello.", "From the chapter."), live.state.value.transcript.items.filterIsInstance<TranscriptItem.Text>().map { it.text.trim() })
    }
}
