package com.locallink.pro.data.repository

import com.locallink.pro.data.db.ExperienceDao
import com.locallink.pro.data.db.ExperienceEntity
import com.locallink.pro.service.pilot.ExperienceTemplates
import com.locallink.pro.service.pilot.SavedExperience
import com.locallink.pro.service.pilot.TaskNorm
import com.locallink.pro.service.pilot.TraceStep
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the pilot's experience types to Room.
 *
 * Two kinds of learned routine:
 *  - EXACT: keyed by the normalized task; replays verbatim.
 *  - TEMPLATE: the trace's typed text came from the task, so it is stored {q}-slotted
 *    with the remaining task words as the shape ("play {q} on youtube"). Any new task
 *    with the same shape matches, and its leftover words fill the slot — this is what
 *    lets "play Believer on YouTube" reuse the routine learned from a different song.
 */
@Singleton
class ExperienceStore @Inject constructor(
    private val dao: ExperienceDao,
) {
    suspend fun find(task: String): SavedExperience? {
        val base = ExperienceTemplates.baseKey(task)
        val norm = TaskNorm.normalize(base)

        // 1) Exact routine (stable key, run-specific suffixes stripped).
        dao.findByNorm(norm)?.let {
            return SavedExperience(it.id, TraceStep.listFromJson(it.stepsJson), it.successCount)
        }
        val all = dao.all()
        all.firstOrNull { it.slotResidual.isBlank() && TaskNorm.matches(it.taskNorm, base) }?.let {
            return SavedExperience(it.id, TraceStep.listFromJson(it.stepsJson), it.successCount)
        }

        // 2) Parameterized template — most specific (longest residual) first. The FULL task
        //    (including any user answer) provides the slot words.
        val templates = all.filter { it.slotResidual.isNotBlank() }
            .sortedByDescending { it.slotResidual.length }
        for (t in templates) {
            val q = ExperienceTemplates.unify(t.slotResidual.split(' '), task) ?: continue
            val steps = ExperienceTemplates.instantiate(TraceStep.listFromJson(t.stepsJson), q)
            return SavedExperience(t.id, steps, t.successCount)
        }
        return null
    }

    /** Save (or overwrite) the routine; parameterizable traces are stored as templates. */
    suspend fun save(task: String, steps: List<TraceStep>) {
        if (steps.isEmpty()) return
        val base = ExperienceTemplates.baseKey(task)
        val now = System.currentTimeMillis()

        val template = ExperienceTemplates.generalize(task, steps)
        if (template != null) {
            val residual = template.residualTokens.joinToString(" ")
            val key = "tpl $residual"
            val existing = dao.findByNorm(key)
            dao.upsert(
                ExperienceEntity(
                    id = existing?.id ?: 0,
                    taskNorm = key,
                    taskRaw = base,
                    stepsJson = TraceStep.listToJson(template.steps),
                    successCount = (existing?.successCount ?: 0) + 1,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    slotResidual = residual,
                ),
            )
            return
        }

        val norm = TaskNorm.normalize(base)
        if (norm.isBlank()) return
        val existing = dao.findByNorm(norm)
        dao.upsert(
            ExperienceEntity(
                id = existing?.id ?: 0,
                taskNorm = norm,
                taskRaw = base,
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
