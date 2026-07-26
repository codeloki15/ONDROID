package com.locallink.pro.service.pilot

import com.locallink.pro.service.pilot.OpenRouterPilotReasoner.Companion.omissionNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capped-element note, which once cost a whole run.
 *
 * "Reply to every LinkedIn post" produced 31 consecutive Scroll actions and not one tap. The
 * cause was in this string: it told the model that elements were missing and to scroll for them.
 * On an infinite feed, scrolling loads more elements, so the omission never clears and the advice
 * re-fires forever.
 */
class OpenRouterPilotReasonerTest {

    @Test fun nothingIsSaidWhenEverythingIsListed() {
        assertEquals("", omissionNote(shown = 40, total = 40))
        assertEquals("", omissionNote(shown = 80, total = 12))
    }

    @Test fun theCapNeverRecommendsScrolling() {
        // The exact regression: on a feed, "scroll" is the one instruction that cannot work.
        val note = omissionNote(shown = 80, total = 109).lowercase()
        assertFalse("must not tell the model to scroll for capped elements: $note",
            Regex("""\bscroll(ing)? (will|to|for|down|up)\b""").containsMatchIn(note) &&
                !note.contains("will not reveal"))
        assertTrue("must say scrolling does not help", note.contains("will not reveal"))
    }

    @Test fun theCapIsExplainedAsACapNotAsMissingContentBelow() {
        val note = omissionNote(shown = 80, total = 109)
        assertTrue("should say it is capped", note.lowercase().contains("capped"))
        assertTrue("should carry both counts", note.contains("109") && note.contains("80"))
    }

    @Test fun findIsOfferedAsTheWayThrough() {
        assertTrue(omissionNote(shown = 80, total = 96).contains("find(text)"))
    }
}
