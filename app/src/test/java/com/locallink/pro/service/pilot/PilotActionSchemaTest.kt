package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotActionSchemaTest {
    @Test fun everyAdvertisedToolIsParseable() {
        val tools = PilotActionSchema.toolsJson()
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertEquals(PilotActionParser.ALLOWED, names)
    }

    @Test fun tapToolDeclaresIntegerId() {
        val tools = PilotActionSchema.toolsJson()
        val tap = (0 until tools.length()).map { tools.getJSONObject(it) }
            .first { it.getJSONObject("function").getString("name") == "tap" }
        val props = tap.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertEquals("integer", props.getJSONObject("id").getString("type"))
    }

    @Test fun systemPromptForbidsRawCoordinates() {
        assertTrue(PilotActionSchema.SYSTEM.contains("id", ignoreCase = true))
        assertTrue(PilotActionSchema.SYSTEM.lowercase().contains("one action"))
    }

    @Test fun systemPromptRequiresQuestionsToBeAnsweredInDone() {
        // A run that navigates to the price and then reports "Done" has failed the task: the
        // user reads the reply, not the screen. This is the single most common way an
        // otherwise-correct automation is useless.
        val prompt = PilotActionSchema.SYSTEM.lowercase()
        assertTrue("prompt must demand the answer inside done(result)", prompt.contains("done(result)"))
        assertTrue("prompt must reject a bare Done for a question", prompt.contains("failed task"))
    }
}
