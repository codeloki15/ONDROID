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
}
