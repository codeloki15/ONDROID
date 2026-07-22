package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotPlanTest {
    @Test fun parsesTodosWithChannelsAndInput() {
        val json = """
            {"todos":[
              {"text":"Open Gmail","channel":"pilot","needs_input":false},
              {"text":"Sign in","channel":"pilot","needs_input":true,"input_reason":"your password"},
              {"text":"What is 2+2","channel":"chat"}
            ]}
        """.trimIndent()
        val plan = PlanJson.parse(json)
        assertEquals(3, plan.todos.size)
        assertEquals(Channel.PILOT, plan.todos[0].channel)
        assertTrue(plan.todos[1].needsInput)
        assertEquals("your password", plan.todos[1].inputReason)
        assertFalse(plan.todos[2].needsInput)
        assertEquals(Channel.CHAT, plan.todos[2].channel)
    }

    @Test fun unknownChannelDefaultsToChat() {
        assertEquals(Channel.CHAT, PlanJson.channelOf("banana"))
        // Composio channel is disabled — it now routes to pilot (do it on-screen).
        assertEquals(Channel.PILOT, PlanJson.channelOf("COMPOSIO"))
    }

    @Test fun malformedJsonIsEmptyPlan() {
        assertTrue(PlanJson.parse("not json").todos.isEmpty())
    }
}
