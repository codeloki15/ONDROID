package com.locallink.pro.service.routine

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.locallink.pro.data.db.ExperienceDao
import com.locallink.pro.data.repository.ChatRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Fires a scheduled routine: hands the routine's task to the Automate agent
 * (learned-routine fast path replays it deterministically), then re-enqueues
 * itself for the next day. Plain worker + EntryPoint so we don't need
 * androidx.hilt:hilt-work.
 *
 * If the phone is locked at fire time the pilot can't act on the screen — the
 * run fails gracefully into the chat log; the chain still continues tomorrow.
 */
class RoutineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ID = "routine_id"
        private const val TAG = "RoutineWorker"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun chatRepository(): ChatRepository
        fun experienceDao(): ExperienceDao
        fun scheduler(): RoutineScheduler
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)

        val routine = deps.experienceDao().byId(id)
        if (routine == null || routine.scheduleHour < 0) {
            Log.i(TAG, "routine $id gone or unscheduled — ending chain")
            return Result.success()
        }

        Log.i(TAG, "firing scheduled routine $id: ${routine.taskRaw}")
        runCatching { deps.chatRepository().runAgent(routine.taskRaw) }
            .onFailure { Log.e(TAG, "scheduled run failed", it) }

        // Chain the next occurrence regardless of this run's outcome.
        deps.scheduler().schedule(id, routine.scheduleHour, routine.scheduleMinute)
        return Result.success()
    }
}
