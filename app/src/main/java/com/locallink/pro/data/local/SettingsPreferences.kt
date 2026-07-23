package com.locallink.pro.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Which LLM "brain" answers a turn.
 *  - AUTO: cloud (OpenRouter) first, fall back to the on-device model on failure.
 *  - CLOUD_ONLY: only OpenRouter; surface an error if it fails (never use local).
 *  - LOCAL_ONLY: always on-device; never call OpenRouter even if a key is set.
 */
enum class EngineMode { AUTO, CLOUD_ONLY, LOCAL_ONLY }

class SettingsPreferences(private val context: Context) {

    companion object {
        private const val TAG = "SettingsPrefs"

        // TTS Settings
        private val KEY_AUTO_TTS = booleanPreferencesKey("auto_tts")
        private val KEY_TTS_SPEED = floatPreferencesKey("tts_speed")
        private val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val KEY_SELECTED_SPEAKER = intPreferencesKey("selected_speaker")

        // STT Settings
        private val KEY_STT_ENABLED = booleanPreferencesKey("stt_enabled")
        private val KEY_STT_ON_DEVICE = booleanPreferencesKey("stt_on_device")

        // Connection Settings
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive")

        // OpenRouter (cloud LLM) API key + selected model id
        private val KEY_OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
        private val KEY_OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"

        // Hands-free (wake-word) mode
        private val KEY_HANDS_FREE = booleanPreferencesKey("hands_free_enabled")

        // First-run onboarding wizard completed (or explicitly skipped)
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

        // In-call assistant (beta): speak on live calls over speakerphone
        private val KEY_CALL_ASSIST = booleanPreferencesKey("call_assist_enabled")

        // LLM engine mode (cloud / local routing)
        private val KEY_ENGINE_MODE = stringPreferencesKey("engine_mode")

        // Composio (cloud SaaS tools, opt-in beta) — BYO project key
        private val KEY_COMPOSIO_API_KEY = stringPreferencesKey("composio_api_key")
        private val KEY_COMPOSIO_USER_ID = stringPreferencesKey("composio_user_id")
        private val KEY_COMPOSIO_TOOLS = stringPreferencesKey("composio_tool_slugs")
        // Sensible scoped defaults (comma-separated). Keep small to bound the prompt.
        const val DEFAULT_COMPOSIO_TOOLS = "GMAIL_SEND_EMAIL,GMAIL_FETCH_EMAILS,SLACK_SEND_MESSAGE"
    }

    // Application-scoped — survives ViewModel destruction
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class SavedSettings(
        val autoTts: Boolean = true,
        val ttsSpeed: Float = 1.0f,
        val ttsPitch: Float = 1.0f,
        val selectedSpeaker: Int = 0,
        val sttEnabled: Boolean = true,
        val autoReconnect: Boolean = true,
        val keepAlive: Boolean = true
    )

    val savedSettings: Flow<SavedSettings> = context.settingsDataStore.data.map { prefs ->
        SavedSettings(
            autoTts = prefs[KEY_AUTO_TTS] ?: true,
            ttsSpeed = prefs[KEY_TTS_SPEED] ?: 1.0f,
            ttsPitch = prefs[KEY_TTS_PITCH] ?: 1.0f,
            selectedSpeaker = prefs[KEY_SELECTED_SPEAKER] ?: 0,
            sttEnabled = prefs[KEY_STT_ENABLED] ?: true,
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
            keepAlive = prefs[KEY_KEEP_ALIVE] ?: true
        )
    }

    // ─── OpenRouter API key + model (cloud LLM) ─────────────────────────────
    val openRouterApiKey: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_OPENROUTER_API_KEY] ?: ""
    }

    val openRouterModel: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_OPENROUTER_MODEL] ?: DEFAULT_MODEL
    }

    suspend fun loadOpenRouterApiKey(): String = openRouterApiKey.first()
    suspend fun loadOpenRouterModel(): String = openRouterModel.first()

    fun saveOpenRouterApiKey(key: String) {
        scope.launch {
            withContext(NonCancellable) {
                context.settingsDataStore.edit { it[KEY_OPENROUTER_API_KEY] = key.trim() }
            }
        }
    }

    fun saveOpenRouterModel(model: String) {
        scope.launch {
            withContext(NonCancellable) {
                context.settingsDataStore.edit { it[KEY_OPENROUTER_MODEL] = model.trim() }
            }
        }
    }

    // ─── Composio (cloud SaaS tools, opt-in beta) ───────────────────────────
    val composioApiKey: Flow<String> = context.settingsDataStore.data.map { it[KEY_COMPOSIO_API_KEY] ?: "" }
    val composioUserId: Flow<String> = context.settingsDataStore.data.map { it[KEY_COMPOSIO_USER_ID] ?: "default" }
    val composioTools: Flow<String> = context.settingsDataStore.data.map { it[KEY_COMPOSIO_TOOLS] ?: DEFAULT_COMPOSIO_TOOLS }

    val handsFree: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_HANDS_FREE] ?: false }
    suspend fun loadHandsFree(): Boolean = handsFree.first()
    fun setHandsFree(enabled: Boolean) = editAsync { it[KEY_HANDS_FREE] = enabled }

    // In-call assistant (beta)
    val callAssist: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_CALL_ASSIST] ?: false }
    suspend fun loadCallAssist(): Boolean = callAssist.first()
    fun setCallAssist(enabled: Boolean) = editAsync { it[KEY_CALL_ASSIST] = enabled }

    // First-run onboarding
    val onboardingDone: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_ONBOARDING_DONE] ?: false }
    suspend fun loadOnboardingDone(): Boolean = onboardingDone.first()
    fun setOnboardingDone(done: Boolean) = editAsync { it[KEY_ONBOARDING_DONE] = done }

    // On-device STT (parakeet). Default ON — used automatically once the model is downloaded.
    val sttOnDevice: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_STT_ON_DEVICE] ?: true }
    suspend fun loadSttOnDevice(): Boolean = sttOnDevice.first()
    fun setSttOnDevice(enabled: Boolean) = editAsync { it[KEY_STT_ON_DEVICE] = enabled }

    // ─── LLM engine mode (cloud / local routing) ────────────────────────────
    val engineMode: Flow<EngineMode> = context.settingsDataStore.data.map { prefs ->
        runCatching { EngineMode.valueOf(prefs[KEY_ENGINE_MODE] ?: EngineMode.AUTO.name) }
            .getOrDefault(EngineMode.AUTO)
    }
    suspend fun loadEngineMode(): EngineMode = engineMode.first()
    fun setEngineMode(mode: EngineMode) = editAsync { it[KEY_ENGINE_MODE] = mode.name }

    suspend fun loadComposioApiKey(): String = composioApiKey.first()
    suspend fun loadComposioUserId(): String = composioUserId.first()
    suspend fun loadComposioTools(): String = composioTools.first()

    fun saveComposioApiKey(key: String) = editAsync { it[KEY_COMPOSIO_API_KEY] = key.trim() }
    fun saveComposioUserId(id: String) = editAsync { it[KEY_COMPOSIO_USER_ID] = id.trim().ifBlank { "default" } }
    fun saveComposioTools(slugs: String) = editAsync { it[KEY_COMPOSIO_TOOLS] = slugs.trim() }

    private fun editAsync(block: (MutablePreferences) -> Unit) {
        scope.launch { withContext(NonCancellable) { context.settingsDataStore.edit(block) } }
    }

    suspend fun load(): SavedSettings {
        return savedSettings.first()
    }

    suspend fun save(settings: SavedSettings) {
        withContext(NonCancellable) {
            context.settingsDataStore.edit { prefs ->
                prefs[KEY_AUTO_TTS] = settings.autoTts
                prefs[KEY_TTS_SPEED] = settings.ttsSpeed
                prefs[KEY_TTS_PITCH] = settings.ttsPitch
                prefs[KEY_SELECTED_SPEAKER] = settings.selectedSpeaker
                prefs[KEY_STT_ENABLED] = settings.sttEnabled
                prefs[KEY_AUTO_RECONNECT] = settings.autoReconnect
                prefs[KEY_KEEP_ALIVE] = settings.keepAlive
            }
        }
        Log.d(TAG, "Settings saved: speaker=${settings.selectedSpeaker}, autoTts=${settings.autoTts}")
    }

    /**
     * Fire-and-forget save that uses an application-scoped coroutine.
     * Guaranteed to complete even if the calling ViewModel is destroyed.
     */
    fun saveAsync(settings: SavedSettings) {
        scope.launch {
            try {
                save(settings)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save settings", e)
            }
        }
    }
}
