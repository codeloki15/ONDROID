package com.locallink.pro.service.pilot

import com.locallink.pro.service.pilot.OpenRouterPilotReflector.Companion.verdictOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verdict parsing, which is where reflection silently died on its first device run.
 *
 * `max_tokens` was 4 — plenty for the single letter the prompt asks for. The default model
 * reasons before answering, spent the whole budget doing that, and returned a null `content`
 * every time. Every verdict came back MATCHED, so the feature looked like it was working while
 * being structurally incapable of ever intervening. The lesson is in these cases: safe defaults
 * hide the failure, so the parser has to be exercised directly.
 */
class OpenRouterPilotReflectorTest {

    @Test fun aBareLetterIsTheVerdict() {
        assertEquals(Reflection.WRONG_PAGE, verdictOf("B"))
        assertEquals(Reflection.MATCHED, verdictOf("A"))
        assertEquals(Reflection.MATCHED, verdictOf("  A\n"))
    }

    @Test fun anEmptyOrNullAnswerNeverIntervenes() {
        // org.json hands back the literal string "null" for a JSON null, which is exactly what
        // the truncated reasoning responses produced.
        assertEquals(Reflection.MATCHED, verdictOf("null"))
        assertEquals(Reflection.MATCHED, verdictOf(""))
        assertEquals(Reflection.MATCHED, verdictOf("   "))
    }

    @Test fun aModelThatReasonsOutLoudIsStillUnderstood() {
        assertEquals(
            Reflection.WRONG_PAGE,
            verdictOf("The screen shows a promo interstitial, not the orders list. Answer: B"),
        )
        assertEquals(
            Reflection.MATCHED,
            verdictOf("This is the search results page, which is on the way. Answer: A"),
        )
    }

    @Test fun theLastLetterWinsSoNegationsResolveCorrectly() {
        // "not B, it is A" must not be read as B just because B appears.
        assertEquals(Reflection.MATCHED, verdictOf("This is not B, it is A"))
    }

    @Test fun proseWithoutAVerdictLeavesTheRunAlone() {
        assertEquals(Reflection.MATCHED, verdictOf("I cannot determine that from this."))
        assertEquals(Reflection.MATCHED, verdictOf("Sorry, I can't help with that request."))
    }

    @Test fun aLetterInsideAWordIsNotAVerdict() {
        // Word boundaries matter: "Amazon" and "Back" must not be mined for verdicts.
        assertEquals(Reflection.MATCHED, verdictOf("Amazon Basics Back to results"))
    }
}
