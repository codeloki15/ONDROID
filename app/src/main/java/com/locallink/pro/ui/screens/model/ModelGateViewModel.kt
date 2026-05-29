package com.locallink.pro.ui.screens.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.service.llm.ModelManager
import com.locallink.pro.service.llm.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelGateViewModel @Inject constructor(
    private val modelManager: ModelManager,
) : ViewModel() {
    val state: StateFlow<ModelState> = modelManager.state
    init { prepare() }
    fun prepare() = viewModelScope.launch { modelManager.prepare() }
}
