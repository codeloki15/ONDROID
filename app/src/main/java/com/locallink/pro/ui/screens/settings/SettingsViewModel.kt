package com.locallink.pro.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.service.voice.VoiceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val autoTts: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val sttEnabled: Boolean = true,
    val selectedSpeaker: Int = 0,
    val numSpeakers: Int = 0,
    val isPreviewPlaying: Boolean = false,
    val previewingSpeakerId: Int? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val voiceService: VoiceService,
    private val settingsPreferences: SettingsPreferences,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _saveSuccess = MutableSharedFlow<Boolean>()
    val saveSuccess = _saveSuccess.asSharedFlow()

    init {
        // Sync all persisted settings from VoiceService (singleton)
        viewModelScope.launch {
            voiceService.sttEnabled.collect { enabled ->
                _uiState.update { it.copy(sttEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            voiceService.autoTts.collect { enabled ->
                _uiState.update { it.copy(autoTts = enabled) }
            }
        }
        viewModelScope.launch {
            voiceService.ttsSpeed.collect { speed ->
                _uiState.update { it.copy(ttsSpeed = speed) }
            }
        }
        viewModelScope.launch {
            voiceService.ttsPitch.collect { pitch ->
                _uiState.update { it.copy(ttsPitch = pitch) }
            }
        }
        viewModelScope.launch {
            voiceService.selectedSpeaker.collect { id ->
                _uiState.update { it.copy(selectedSpeaker = id) }
            }
        }
        viewModelScope.launch {
            voiceService.isPreviewPlaying.collect { isPlaying ->
                _uiState.update { it.copy(isPreviewPlaying = isPlaying) }
            }
        }
        viewModelScope.launch {
            voiceService.previewingSpeakerId.collect { speakerId ->
                _uiState.update { it.copy(previewingSpeakerId = speakerId) }
            }
        }

        // Load speaker count (TTS may still be initializing)
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            val count = voiceService.getNumSpeakers()
            _uiState.update { it.copy(numSpeakers = count) }
        }
    }

    fun toggleAutoTts(enabled: Boolean) {
        voiceService.setAutoTts(enabled)
    }

    fun setTtsSpeed(speed: Float) {
        voiceService.setTtsSpeed(speed)
    }

    fun setTtsPitch(pitch: Float) {
        voiceService.setTtsPitch(pitch)
    }

    fun toggleSttEnabled(enabled: Boolean) {
        voiceService.setSttEnabled(enabled)
    }

    fun clearAllChats() {
        viewModelScope.launch { chatRepository.clearAll() }
    }

    fun selectSpeaker(speakerId: Int) {
        voiceService.setSpeakerId(speakerId)
    }

    fun previewSpeaker(speakerId: Int) {
        voiceService.previewSpeaker(speakerId)
    }

    fun stopPreview() {
        voiceService.stopPreview()
    }

    fun saveSettings() {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val settings = SettingsPreferences.SavedSettings(
                    autoTts = currentState.autoTts,
                    ttsSpeed = currentState.ttsSpeed,
                    ttsPitch = currentState.ttsPitch,
                    selectedSpeaker = currentState.selectedSpeaker,
                    sttEnabled = currentState.sttEnabled
                )
                settingsPreferences.save(settings)
                _saveSuccess.emit(true)
            } catch (e: Exception) {
                _saveSuccess.emit(false)
            }
        }
    }
}
