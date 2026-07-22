package com.locallink.pro.service.pilot

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotPlannerTest {
    @Test fun planSourceReturnsParsedPlan() = runTest {
        val fake = PlanSource { task, _ ->
            PlanJson.parse("""{"todos":[{"text":"$task","channel":"pilot"}]}""")
        }
        val plan = fake.plan("change wallpaper", "")
        assertEquals(1, plan.todos.size)
        assertEquals(Channel.PILOT, plan.todos[0].channel)
        assertEquals("change wallpaper", plan.todos[0].text)
    }

    @Test fun systemPromptNamesAllThreeChannels() {
        val p = PLANNER_SYSTEM.lowercase()
        assertTrue(p.contains("chat") && p.contains("composio") && p.contains("pilot"))
        assertTrue(p.contains("needs_input"))
    }
}
