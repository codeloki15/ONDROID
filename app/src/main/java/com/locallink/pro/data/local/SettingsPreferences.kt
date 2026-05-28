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

        // Connection Settings
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive")
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
