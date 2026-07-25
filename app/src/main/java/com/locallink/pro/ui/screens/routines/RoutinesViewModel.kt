package com.locallink.pro.ui.screens.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.ExperienceDao
import com.locallink.pro.data.db.ExperienceEntity
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.service.routine.RoutineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoutinesViewModel @Inject constructor(
    private val dao: ExperienceDao,
    private val chat: ChatRepository,
    private val scheduler: RoutineScheduler,
    private val experiences: com.locallink.pro.data.repository.ExperienceStore,
    private val recorder: com.locallink.pro.service.pilot.RoutineRecorder,
) : ViewModel() {

    // Observed straight off the injected recorder — stable instances, so the save prompt keeps
    // updating across leaving the app to demonstrate and coming back.
    val recordedSteps = recorder.steps
    val isRecording = recorder.isRecording
    val pendingName = recorder.pendingName

    /**
     * Start capturing what the user does, so a routine can be TAUGHT by demonstration.
     * Returns false when the accessibility service is off — there's nothing to watch with.
     */
    fun startTeaching(name: String): Boolean {
        val svc = com.locallink.pro.service.pilot.OmniAccessibilityService.instance ?: return false
        svc.startRecording(name)
        return true
    }

    /** True when Omni can watch at all — recording needs the accessibility service running. */
    fun canTeach(): Boolean = com.locallink.pro.service.pilot.OmniAccessibilityService.instance != null

    /**
     * Finish teaching and store the demonstration under [name].
     *
     * Saved through the same store the pilot writes to, so a taught routine is replayed, listed
     * and scheduled by the existing machinery — nothing downstream knows the difference.
     */
    fun finishTeaching() = viewModelScope.launch {
        val name = recorder.pendingName.value
        val steps = com.locallink.pro.service.pilot.OmniAccessibilityService.instance
            ?.stopRecording() ?: recorder.stop()
        if (steps.isEmpty()) {
            _toast.tryEmit("Nothing was recorded — no steps to save")
            return@launch
        }
        val label = name.trim().ifBlank { "Taught routine" }
        experiences.save(label, steps)
        recorder.cancel()   // clears the prompt now the routine is stored
        // save() keys on the task text, so the name IS how it gets matched later — "run <name>"
        // finds it through the same fast-replay path a learned routine uses.
        _toast.tryEmit("Saved “$label” with ${steps.size} step${if (steps.size == 1) "" else "s"}")
    }

    fun cancelTeaching() {
        com.locallink.pro.service.pilot.OmniAccessibilityService.instance?.stopRecording()
        recorder.cancel()
    }

    val routines: StateFlow<List<ExperienceEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val toast = _toast.asSharedFlow()

    /** Fire the routine through the normal Automate path (fast-replay picks it up). */
    fun runNow(r: ExperienceEntity) {
        viewModelScope.launch {
            _toast.tryEmit("Started “${r.displayName()}”")
            runCatching { chat.runAgent(r.taskRaw) }
        }
    }

    fun rename(r: ExperienceEntity, label: String) {
        viewModelScope.launch { dao.rename(r.id, label.trim()) }
    }

    fun schedule(r: ExperienceEntity, hour: Int, minute: Int) {
        viewModelScope.launch {
            dao.setSchedule(r.id, hour, minute)
            scheduler.schedule(r.id, hour, minute)
            _toast.tryEmit("Daily at %02d:%02d — “%s”".format(hour, minute, r.displayName()))
        }
    }

    fun unschedule(r: ExperienceEntity) {
        viewModelScope.launch {
            dao.setSchedule(r.id, -1, -1)
            scheduler.cancel(r.id)
        }
    }

    fun delete(r: ExperienceEntity) {
        viewModelScope.launch {
            scheduler.cancel(r.id)
            dao.delete(r.id)
        }
    }
}

fun ExperienceEntity.displayName(): String = label.ifBlank { taskRaw }
