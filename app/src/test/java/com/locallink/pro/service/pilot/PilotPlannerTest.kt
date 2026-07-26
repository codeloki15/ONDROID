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

    @Test fun systemPromptNamesChatAndPilotChannels() {
        val p = PLANNER_SYSTEM.lowercase()
        assertTrue(p.contains("chat") && p.contains("pilot"))
        assertTrue(p.contains("needs_input"))
    }

    @Test fun systemPromptForbidsEnumeratingAnEndlessList() {
        // "Make a witty reply to every post on my LinkedIn wall" was planned as
        //   1. Open LinkedIn and locate ALL posts. List them with their content.  [pilot]
        //   2. For each post found, generate a witty reply and post it.           [pilot]
        // Step 1 cannot terminate on a feed, so the run spent 31 steps scrolling and step 2 never
        // ran. The planner was following this prompt exactly — it used to say "todo 1 = list the
        // items" with "reply to every Y" as the worked example.
        val p = PLANNER_SYSTEM.lowercase()
        assertTrue("must distinguish lists that end from feeds", p.contains("does not end"))
        assertTrue("must forbid enumerating a feed first", p.contains("never write a todo that enumerates"))
        assertTrue("must require a bound", p.contains("bounded") || p.contains("first 3"))
    }
}
