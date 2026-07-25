package com.locallink.pro.data.repository

import com.locallink.pro.data.db.ExperienceDao
import com.locallink.pro.data.db.ExperienceEntity
import com.locallink.pro.service.pilot.TraceStep
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceStoreTest {

    private class FakeDao : ExperienceDao {
        val rows = HashMap<Long, ExperienceEntity>()
        var nextId = 1L
        override suspend fun upsert(e: ExperienceEntity): Long {
            val id = if (e.id != 0L) e.id else nextId++
            rows[id] = e.copy(id = id)
            return id
        }
        override suspend fun findByNorm(norm: String) = rows.values.firstOrNull { it.taskNorm == norm }
        override suspend fun byId(id: Long) = rows[id]
        override suspend fun all() = rows.values.toList()
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(rows.values.toList())
        override suspend fun bumpSuccess(id: Long, now: Long) {
            rows[id]?.let { rows[id] = it.copy(successCount = it.successCount + 1, updatedAt = now) }
        }
        override suspend fun rename(id: Long, label: String) {
            rows[id]?.let { rows[id] = it.copy(label = label) }
        }
        override suspend fun setSchedule(id: Long, hour: Int, minute: Int) {
            rows[id]?.let { rows[id] = it.copy(scheduleHour = hour, scheduleMinute = minute) }
        }
        override suspend fun delete(id: Long) { rows.remove(id) }
        override suspend fun deleteAll() = rows.clear()
    }

    private val playSteps = listOf(
        TraceStep("launch_app", "youtube"),
        TraceStep("type", arg = "Believer Imagine Dragons", targetResId = "yt:id/query"),
        TraceStep("tap", targetText = "Believer - Imagine Dragons"),
    )

    @Test fun learnedSongRoutineReplaysForADifferentSong() = runTest {
        val store = ExperienceStore(FakeDao())
        store.save("Play Believer by Imagine Dragons on YouTube", playSteps)

        val hit = store.find("Play Shape of You by Ed Sheeran on YouTube")
        assertNotNull("template should match a different song", hit)
        // The typed query is re-parameterized to the NEW song.
        assertEquals("shape of you ed sheeran", hit!!.steps[1].arg)
        // Un-related tasks stay unmatched.
        assertNull(store.find("Read my emails"))
    }

    @Test fun exactRoutinesStillRoundTrip() = runTest {
        val store = ExperienceStore(FakeDao())
        val steps = listOf(TraceStep("launch_app", "settings"), TraceStep("tap", targetText = "Battery"))
        store.save("Open battery settings", steps)
        val hit = store.find("open battery settings")
        assertNotNull(hit)
        assertEquals("Battery", hit!!.steps[1].targetText)
    }

    @Test fun progressSuffixDoesNotPolluteTheKey() = runTest {
        val store = ExperienceStore(FakeDao())
        val steps = listOf(TraceStep("launch_app", "settings"), TraceStep("tap", targetText = "Battery"))
        store.save("Check battery\n[Progress so far: opened settings]", steps)
        assertNotNull("same task with different run context must match",
            store.find("Check battery\n[Progress so far: something else entirely]"))
    }

    // ── Near-miss recall: routines that don't replay should still inform the planner ──

    @Test fun aNearMissRoutineIsOfferedAsAnExample() = runTest {
        val store = ExperienceStore(FakeDao())
        store.save("Open battery settings", listOf(
            TraceStep("launch_app", "settings"),
            TraceStep("tap", targetText = "Battery"),
        ))

        // Different enough that find() won't replay it...
        assertNull(store.find("Open battery saver settings and enable it"))
        // ...but close enough to be worth showing the planner.
        val similar = store.similar("Open battery saver settings and enable it")
        assertEquals(1, similar.size)
        assertEquals("Open battery settings", similar[0].label)
    }

    @Test fun unrelatedRoutinesAreNotOfferedAsExamples() = runTest {
        val store = ExperienceStore(FakeDao())
        store.save("Open battery settings", listOf(TraceStep("launch_app", "settings")))
        assertEquals(emptyList<SimilarRoutine>(), store.similar("Send a birthday message to Priya"))
    }

    @Test fun theClosestRoutineIsOfferedFirst() = runTest {
        val store = ExperienceStore(FakeDao())
        store.save("Play music on Spotify", listOf(TraceStep("launch_app", "spotify")))
        store.save("Play music on YouTube", listOf(TraceStep("launch_app", "youtube")))

        // Shares three content words with the Spotify routine, two with the YouTube one.
        assertEquals("Play music on Spotify", store.similar("Play music on Spotify again").first().label)
    }

    @Test fun equalOverlapPrefersTheMoreProvenRoutine() = runTest {
        val store = ExperienceStore(FakeDao())
        store.save("Play music on YouTube", listOf(TraceStep("launch_app", "youtube")))
        store.save("Play music on Spotify", listOf(TraceStep("launch_app", "spotify")))
        // Both overlap "Play music on Pandora" equally — the one that has worked more often wins.
        val spotify = store.find("Play music on Spotify")!!
        repeat(3) { store.bump(spotify.id) }

        assertEquals("Play music on Spotify", store.similar("Play music on Pandora").first().label)
    }

    @Test fun promptBlockIsEmptyWhenNothingIsClose() = runTest {
        val store = ExperienceStore(FakeDao())
        store.save("Open battery settings", listOf(TraceStep("launch_app", "settings")))
        assertEquals("", store.priorRoutinesBlock("Send a birthday message to Priya"))
    }

    @Test fun promptBlockNamesTheRoutineAndItsSteps() = runTest {
        val store = ExperienceStore(FakeDao())
        store.save("Open battery settings", listOf(
            TraceStep("launch_app", "settings"),
            TraceStep("tap", targetText = "Battery"),
        ))
        val block = store.priorRoutinesBlock("Open battery saver settings and enable it")
        assertTrue("should name the routine: $block", block.contains("Open battery settings"))
        assertTrue("should show its steps: $block", block.contains("Battery"))
    }
}
