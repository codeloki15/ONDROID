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
/** A learned routine close enough to [ExperienceStore.similar]'s query to be worth showing. */
data class SimilarRoutine(
    val label: String,
    val steps: List<TraceStep>,
    val successCount: Int,
)

@Singleton
class ExperienceStore @Inject constructor(
    private val dao: ExperienceDao,
) {
    companion object {
        /** Below this token overlap the "example" is noise that misleads the planner. */
        private const val MIN_SIMILARITY = 0.34
        private const val MAX_EXAMPLES = 3
        private const val MAX_EXAMPLE_STEPS = 12
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "app", "please", "can", "you", "get", "let",
            "then", "that", "this", "from", "into", "her", "his", "our", "out", "use",
        )
    }

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

    /**
     * Routines that RESEMBLE [task] without matching it well enough to replay.
     *
     * [find] is all-or-nothing: an exact or template hit replays verbatim, and anything else
     * falls through to a planner that starts from zero — so a routine taught yesterday
     * contributes nothing to a similar-but-different task today. These are fed to the planner
     * as worked examples instead, showing it the paths that actually work on THIS phone.
     */
    suspend fun similar(task: String, limit: Int = MAX_EXAMPLES): List<SimilarRoutine> {
        val wanted = keyTokens(TaskNorm.normalize(ExperienceTemplates.baseKey(task)))
        if (wanted.isEmpty()) return emptyList()

        return dao.all()
            .mapNotNull { e ->
                val theirs = keyTokens(e.taskNorm)
                if (theirs.isEmpty()) return@mapNotNull null
                val shared = wanted.count { it in theirs }
                if (shared == 0) return@mapNotNull null
                // Jaccard-style: shared tokens over the larger side, so a long routine doesn't
                // score highly just by containing a short task's words.
                val score = shared.toDouble() / maxOf(wanted.size, theirs.size)
                if (score < MIN_SIMILARITY) null else score to e
            }
            // Best overlap first, then the more proven routine. The final key on id keeps the
            // order stable when those tie — otherwise it falls out of DAO iteration order and
            // the same task can get different examples on different runs.
            .sortedWith(
                compareByDescending<Pair<Double, ExperienceEntity>> { it.first }
                    .thenByDescending { it.second.successCount }
                    .thenBy { it.second.id },
            )
            .take(limit)
            .map { (_, e) ->
                SimilarRoutine(
                    label = e.label.ifBlank { e.taskRaw },
                    steps = TraceStep.listFromJson(e.stepsJson),
                    successCount = e.successCount,
                )
            }
    }

    /**
     * Similar routines rendered for a planner prompt, or "" when nothing is close enough.
     * Kept here so the formatting lives beside the data it describes.
     */
    suspend fun priorRoutinesBlock(task: String): String {
        val examples = runCatching { similar(task) }.getOrDefault(emptyList())
        if (examples.isEmpty()) return ""
        return buildString {
            append("\n\nRoutines already learned on this phone for similar tasks. ")
            append("These are real paths that worked here — reuse their structure where it fits, ")
            append("but follow the CURRENT task, not these:\n")
            for (e in examples) {
                append("\n- \"${e.label}\" (worked ${e.successCount}×): ")
                append(e.steps.take(MAX_EXAMPLE_STEPS).joinToString(" → ") { describeStep(it) })
                if (e.steps.size > MAX_EXAMPLE_STEPS) append(" → …")
            }
        }
    }

    /** One trace step as a short phrase, e.g. `tap "Compose"` or `launch_app gmail`. */
    private fun describeStep(s: TraceStep): String {
        val target = s.targetText ?: s.targetDesc ?: s.targetResId?.substringAfterLast('/')
        return buildString {
            append(s.action)
            target?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it.take(32)).append('"') }
            s.arg?.takeIf { it.isNotBlank() }?.let { append(' ').append(it.take(32)) }
        }
    }

    /** Content words only — drops the filler that every task shares. */
    private fun keyTokens(norm: String): Set<String> =
        norm.split(Regex("[^a-z0-9]+"), )
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

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
