package com.locallink.pro.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.local.EngineMode
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.data.repository.ExperienceStore
import com.locallink.pro.service.llm.OpenRouterClient
import com.locallink.pro.service.llm.OpenRouterModel
import com.locallink.pro.service.voice.SttModelManager
import com.locallink.pro.service.voice.SttModelState
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
    val previewingSpeakerId: Int? = null,
    val handsFree: Boolean = false,
    val callAssist: Boolean = false,
    val apiKey: String = "",
    val selectedModel: String = SettingsPreferences.DEFAULT_MODEL,
    val models: List<OpenRouterModel> = emptyList(),
    val loadingModels: Boolean = false,
    val modelsError: String? = null,
    val freeOnly: Boolean = false,
    val engineMode: EngineMode = EngineMode.AUTO,
    // On-device STT (parakeet)
    val sttOnDevice: Boolean = true,
    val sttModelState: SttModelState = SttModelState.Missing,
    // Learned routines (experience memory)
    val experienceCount: Int = 0,
    // Composio (cloud SaaS tools, beta)
    val composioApiKey: String = "",
    val composioUserId: String = "default",
    val composioTools: String = SettingsPreferences.DEFAULT_COMPOSIO_TOOLS,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val voiceService: VoiceService,
    private val settingsPreferences: SettingsPreferences,
    private val chatRepository: ChatRepository,
    private val openRouter: OpenRouterClient,
    private val sttModels: SttModelManager,
    private val experiences: ExperienceStore,
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

        // Load saved OpenRouter key + model
        viewModelScope.launch {
            settingsPreferences.openRouterApiKey.collect { key ->
                _uiState.update { it.copy(apiKey = key) }
            }
        }
        viewModelScope.launch {
            settingsPreferences.openRouterModel.collect { m ->
                _uiState.update { it.copy(selectedModel = m) }
            }
        }
        // Populate the model list once (catalog is public, no key needed)
        fetchModels()

        viewModelScope.launch {
            settingsPreferences.handsFree.collect { h -> _uiState.update { it.copy(handsFree = h) } }
        }
        viewModelScope.launch {
            settingsPreferences.callAssist.collect { c -> _uiState.update { it.copy(callAssist = c) } }
        }
        viewModelScope.launch {
            settingsPreferences.engineMode.collect { m -> _uiState.update { it.copy(engineMode = m) } }
        }

        // Composio settings
        viewModelScope.launch { settingsPreferences.composioApiKey.collect { k -> _uiState.update { it.copy(composioApiKey = k) } } }
        viewModelScope.launch { settingsPreferences.composioUserId.collect { u -> _uiState.update { it.copy(composioUserId = u) } } }
        viewModelScope.launch { settingsPreferences.composioTools.collect { t -> _uiState.update { it.copy(composioTools = t) } } }

        // On-device STT model + preference
        sttModels.refresh()
        viewModelScope.launch {
            sttModels.state.collect { s -> _uiState.update { it.copy(sttModelState = s) } }
        }
        viewModelScope.launch {
            settingsPreferences.sttOnDevice.collect { on -> _uiState.update { it.copy(sttOnDevice = on) } }
        }
        // Learned routines count
        viewModelScope.launch {
            runCatching { experiences.count() }.onSuccess { c ->
                _uiState.update { it.copy(experienceCount = c) }
            }
        }
    }

    // ─── On-device STT (parakeet) ────────────────────────────────────────
    fun setSttOnDevice(enabled: Boolean) {
        _uiState.update { it.copy(sttOnDevice = enabled) }
        settingsPreferences.setSttOnDevice(enabled)
        voiceService.refreshSttEngine()
    }

    fun downloadSttModel() = sttModels.startDownload()
    fun cancelSttDownload() = sttModels.cancelDownload()
    fun deleteSttModel() = sttModels.deleteModel()

    /** Called when a download completes so the engine hot-loads without an app restart. */
    fun onSttModelReady() = voiceService.refreshSttEngine()

    // ─── Learned routines ────────────────────────────────────────────────
    fun clearExperiences() {
        viewModelScope.launch {
            runCatching { experiences.clear() }
            _uiState.update { it.copy(experienceCount = 0) }
        }
    }

    fun setComposioApiKey(key: String) {
        _uiState.update { it.copy(composioApiKey = key) }
        settingsPreferences.saveComposioApiKey(key)
    }

    fun setComposioUserId(id: String) {
        _uiState.update { it.copy(composioUserId = id) }
        settingsPreferences.saveComposioUserId(id)
    }

    fun setComposioTools(slugs: String) {
        _uiState.update { it.copy(composioTools = slugs) }
        settingsPreferences.saveComposioTools(slugs)
    }

    fun setApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
        settingsPreferences.saveOpenRouterApiKey(key)
    }

    fun selectModel(id: String) {
        _uiState.update { it.copy(selectedModel = id) }
        settingsPreferences.saveOpenRouterModel(id)
    }

    fun toggleFreeOnly() {
        _uiState.update { it.copy(freeOnly = !it.freeOnly) }
    }

    fun setEngineMode(mode: EngineMode) {
        _uiState.update { it.copy(engineMode = mode) }
        settingsPreferences.setEngineMode(mode)
    }

    fun fetchModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingModels = true, modelsError = null) }
            openRouter.fetchModels().fold(
                onSuccess = { list ->
                    // Only tool-capable models (device actions need tools), sorted: free first then name.
                    val toolModels = list.filter { it.toolCapable }
                        .sortedWith(compareByDescending<OpenRouterModel> { it.free }.thenBy { it.name })
                    _uiState.update { it.copy(models = toolModels, loadingModels = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(loadingModels = false, modelsError = e.message ?: "Failed to load models") }
                },
            )
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

    fun setHandsFree(enabled: Boolean) {
        _uiState.update { it.copy(handsFree = enabled) }
        settingsPreferences.setHandsFree(enabled)
    }

    fun setCallAssist(enabled: Boolean) {
        _uiState.update { it.copy(callAssist = enabled) }
        settingsPreferences.setCallAssist(enabled)
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
