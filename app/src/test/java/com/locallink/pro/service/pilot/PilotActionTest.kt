package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotActionTest {
    @Test fun parsesTap() {
        val a = PilotActionParser.parse("tap", """{"id": 12}""")
        assertEquals(PilotAction.Tap(12), a)
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
