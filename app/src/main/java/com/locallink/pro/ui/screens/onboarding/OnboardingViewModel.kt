package com.locallink.pro.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.service.pilot.OmniAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val step: Int = 0,                  // 0=AI key, 1=mic, 2=Composio, 3=accessibility
    val openRouterKey: String = "",
    val composioKey: String = "",
    val micGranted: Boolean = false,
    val a11yEnabled: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsPreferences,
) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUiState())
    val ui: StateFlow<OnboardingUiState> = _ui.asStateFlow()

    /** null while the DataStore read is in flight — the nav gate waits on a real value. */
    val done: StateFlow<Boolean?> = settings.onboardingDone
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Prefill from existing prefs (re-runs of the wizard shouldn't lose keys).
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    openRouterKey = settings.loadOpenRouterApiKey(),
                    composioKey = settings.loadComposioApiKey(),
                )
            }
        }
    }

    fun setStep(s: Int) = _ui.update { it.copy(step = s.coerceIn(0, 3)) }
    fun next() = setStep(_ui.value.step + 1)
    fun back() = setStep(_ui.value.step - 1)

    fun setOpenRouterKey(k: String) {
        _ui.update { it.copy(openRouterKey = k) }
        settings.saveOpenRouterApiKey(k)
    }

    fun setComposioKey(k: String) {
        _ui.update { it.copy(composioKey = k) }
        settings.saveComposioApiKey(k)
    }

    fun setMicGranted(granted: Boolean) = _ui.update { it.copy(micGranted = granted) }

    /** Called on screen resume — the user may have just toggled the service in Settings. */
    fun refreshA11y() = _ui.update { it.copy(a11yEnabled = OmniAccessibilityService.instance != null) }

    fun finish() = settings.setOnboardingDone(true)
}
