package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotActionTest {
    @Test fun parsesTap() {
        val a = PilotActionParser.parse("tap", """{"id": 12}""")
        assertEquals(PilotAction.Tap(12), a)
    }

    @Test fun parsesPressEnter() {
        // Typing only fills a field; this is what actually runs a search.
        assertEquals(PilotAction.PressEnter(7), PilotActionParser.parse("press_enter", """{"id": 7}"""))
    }

    @Test fun pressEnterWithoutAnIdIsRejected() {
        // Must not silently become a no-op action the model believes succeeded.
        assertTrue(PilotActionParser.parse("press_enter", """{}""") is PilotAction.Invalid)
    }

    @Test fun parsesFind() {
        assertEquals(
            PilotAction.FindText("Lavazza Gusto Crema"),
            PilotActionParser.parse("find", """{"text":"Lavazza Gusto Crema"}"""),
        )
    }

    @Test fun findWithoutTextIsRejected() {
        // A blank find would scroll to the end of the list and report success on nothing.
        assertTrue(PilotActionParser.parse("find", """{"text":""}""") is PilotAction.Invalid)
        assertTrue(PilotActionParser.parse("find", """{}""") is PilotAction.Invalid)
    }

    @Test fun parsesDoneAndAsk() {
        assertEquals(PilotAction.Done("found it"),
            PilotActionParser.parse("done", """{"result":"found it"}"""))
        assertEquals(PilotAction.Ask("which Divya?"),
            PilotActionParser.parse("ask", """{"question":"which Divya?"}"""))
    }

    @Test fun unknownToolIsInvalid() {
        val a = PilotActionParser.parse("COMPOSIO_SEARCH_WEB", "{}")
        assertTrue(a is PilotAction.Invalid)
        assertTrue((a as PilotAction.Invalid).reason.contains("COMPOSIO_SEARCH_WEB"))
    }

    @Test fun tapWithoutIdIsInvalid() {
        val a = PilotActionParser.parse("tap", """{"foo":1}""")
        assertTrue(a is PilotAction.Invalid)
    }
}
