package com.locallink.pro.data.repository

import com.locallink.pro.data.db.ExperienceDao
import com.locallink.pro.data.db.ExperienceEntity
import com.locallink.pro.service.pilot.TraceStep
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        override suspend fun all() = rows.values.toList()
        override suspend fun bumpSuccess(id: Long, now: Long) {
            rows[id]?.let { rows[id] = it.copy(successCount = it.successCount + 1, updatedAt = now) }
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
}
