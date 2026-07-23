package com.locallink.pro.service.routine

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daily routine scheduling on WorkManager. Each scheduled routine is a self-chaining
 * one-time work (wall-clock anchored: the worker re-enqueues itself for the next day),
 * which stays accurate where a 24h PeriodicWorkRequest drifts. WorkManager persists
 * the queue across reboots.
 */
@Singleton
class RoutineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun name(id: Long) = "routine_$id"

    /** (Re)schedule the next firing of routine [id] at the next occurrence of h:m. */
    fun schedule(id: Long, hour: Int, minute: Int) {
        val delayMs = untilNext(hour, minute)
        val req = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(RoutineWorker.KEY_ID to id))
            .addTag("routine")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(name(id))
    }

    /** ms until the next wall-clock occurrence of hour:minute (today if still ahead). */
    private fun untilNext(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }
}
