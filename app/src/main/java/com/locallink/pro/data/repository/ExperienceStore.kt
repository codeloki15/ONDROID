package com.locallink.pro.data.repository

import com.locallink.pro.data.db.ExperienceDao
import com.locallink.pro.data.db.ExperienceEntity
import com.locallink.pro.service.pilot.SavedExperience
import com.locallink.pro.service.pilot.TaskNorm
import com.locallink.pro.service.pilot.TraceStep
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the pilot's experience types to Room. Lookup is exact-normalized first,
 * then a strict near-duplicate scan (token Jaccard ≥ 0.9) — determinism over recall.
 */
@Singleton
class ExperienceStore @Inject constructor(
    private val dao: ExperienceDao,
) {
    suspend fun find(task: String): SavedExperience? {
        val norm = TaskNorm.normalize(task)
        val exact = dao.findByNorm(norm)
        val hit = exact ?: dao.all().firstOrNull { TaskNorm.matches(it.taskNorm, task) }
        return hit?.let { SavedExperience(it.id, TraceStep.listFromJson(it.stepsJson), it.successCount) }
    }

    /** Save (or overwrite) the routine for this task; a fresh success replaces stale steps. */
    suspend fun save(task: String, steps: List<TraceStep>) {
        if (steps.isEmpty()) return
        val norm = TaskNorm.normalize(task)
        if (norm.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = dao.findByNorm(norm)
        dao.upsert(
            ExperienceEntity(
                id = existing?.id ?: 0,
                taskNorm = norm,
                taskRaw = task,
                stepsJson = TraceStep.listToJson(steps),
                successCount = (existing?.successCount ?: 0) + 1,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun bump(id: Long) = dao.bumpSuccess(id, System.currentTimeMillis())

    suspend fun count(): Int = dao.all().size

    suspend fun clear() = dao.deleteAll()
}
