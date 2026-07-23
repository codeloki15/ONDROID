package com.locallink.pro.ui.screens.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.ExperienceDao
import com.locallink.pro.data.db.ExperienceEntity
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.service.routine.RoutineScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
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
) : ViewModel() {

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
