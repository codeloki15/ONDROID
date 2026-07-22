package com.locallink.pro.ui.screens.model

import androidx.lifecycle.ViewModel
import com.locallink.pro.service.llm.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ModelGateViewModel @Inject constructor() : ViewModel() {
    // Cloud-only app: always pass the gate (no on-device model required). Chat surfaces a clear
    // "add an API key" message if no OpenRouter key is set, so the user can still reach Settings.
    val state: StateFlow<ModelState> = MutableStateFlow<ModelState>(ModelState.Ready).asStateFlow()

    fun prepare() {}
}
