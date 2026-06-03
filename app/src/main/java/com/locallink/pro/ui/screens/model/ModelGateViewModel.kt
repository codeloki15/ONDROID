package com.locallink.pro.ui.screens.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.service.llm.ModelManager
import com.locallink.pro.service.llm.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelGateViewModel @Inject constructor(
    private val modelManager: ModelManager,
    settings: SettingsPreferences,
) : ViewModel() {
    // Ready if a local model is present OR a cloud (OpenRouter) key is set.
    val state: StateFlow<ModelState> =
        combine(modelManager.state, settings.openRouterApiKey) { s, key ->
            if (key.isNotBlank()) ModelState.Ready else s
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelState.Checking)

    init { prepare() }
    fun prepare() = viewModelScope.launch { modelManager.prepare() }
}
