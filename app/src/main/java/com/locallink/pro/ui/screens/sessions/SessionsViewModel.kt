package com.locallink.pro.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.SessionEntity
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.service.pilot.OmniAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One actionable setup problem to surface on the home screen (worst first).
 * ColorOS & friends silently disable the accessibility service on every reinstall,
 * so [A11Y_OFF] fires often — the banner replaces a buried error in the chat.
 */
enum class SetupHealth { OK, NO_AI_KEY, A11Y_OFF }

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repo: ChatRepository,
    private val settings: SettingsPreferences,
) : ViewModel() {
    val sessions: StateFlow<List<SessionEntity>> =
        repo.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _health = MutableStateFlow(SetupHealth.OK)
    val health: StateFlow<SetupHealth> = _health.asStateFlow()

    /** Re-evaluated on every screen resume — both states change outside the app. */
    fun refreshHealth() {
        viewModelScope.launch {
            _health.value = when {
                settings.loadOpenRouterApiKey().isBlank() -> SetupHealth.NO_AI_KEY
                OmniAccessibilityService.instance == null -> SetupHealth.A11Y_OFF
                else -> SetupHealth.OK
            }
        }
    }

    fun delete(id: String) = viewModelScope.launch { repo.deleteSession(id) }
}
