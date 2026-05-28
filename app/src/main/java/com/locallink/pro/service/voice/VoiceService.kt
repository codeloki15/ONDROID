package com.locallink.pro.service.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.locallink.pro.data.local.SettingsPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kokoroTts: KokoroTtsService,
    private val settingsPreferences: SettingsPreferences
) {
    companion object {
        private const val TAG = "VoiceService"
    }

    // Main thread handler — SpeechRecognizer MUST run on main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // Coroutine scope for loading settings
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // STT State
    private var speechRecognizer: SpeechRecognizer? = null
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _sttEnabled = MutableStateFlow(true)
    val sttEnabled: StateFlow<Boolean> = _sttEnabled.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult.asStateFlow()

    private val _finalResult = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val finalResult = _finalResult.asSharedFlow()

    private val _sttError = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sttError = _sttError.asSharedFlow()

    // TTS State — delegated to Kokoro with Android TTS as fallback
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    val isSpeaking: StateFlow<Boolean> = kokoroTts.isSpeaking

    // Persisted settings (survive ViewModel recreation)
    private val _autoTts = MutableStateFlow(true)
    val autoTts: StateFlow<Boolean> = _autoTts.asStateFlow()

    private val _ttsSpeed = MutableStateFlow(1.0f)
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    private val _ttsPitch = MutableStateFlow(1.0f)
    val ttsPitch: StateFlow<Float> = _ttsPitch.asStateFlow()

    private val _selectedSpeaker = MutableStateFlow(0)
    val selectedSpeaker: StateFlow<Int> = _selectedSpeaker.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    private val _previewingSpeakerId = MutableStateFlow<Int?>(null)
    val previewingSpeakerId: StateFlow<Int?> = _previewingSpeakerId.asStateFlow()

    fun initialize() {
        kokoroTts.initialize()
        initializeAndroidTtsFallback()
        loadSavedSettings()
    }

    private fun loadSavedSettings() {
        scope.launch {
            try {
                val settings = settingsPreferences.load()
                Log.d(TAG, "Loading saved settings: $settings")

                // Apply loaded settings
                _autoTts.value = settings.autoTts
                _ttsSpeed.value = settings.ttsSpeed
                _ttsPitch.value = settings.ttsPitch
                _selectedSpeaker.value = settings.selectedSpeaker
                _sttEnabled.value = settings.sttEnabled

                // Apply to Kokoro TTS
                kokoroTts.setSpeed(settings.ttsSpeed)
                kokoroTts.setSpeakerId(settings.selectedSpeaker)

                // Apply to Android TTS fallback
                androidTts?.setSpeechRate(settings.ttsSpeed.coerceIn(0.5f, 2.0f))
                androidTts?.setPitch(settings.ttsPitch.coerceIn(0.5f, 2.0f))

                Log.d(TAG, "Settings loaded and applied successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings", e)
            }
        }
    }

    private fun initializeAndroidTtsFallback() {
        androidTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTts?.language = Locale.US
                androidTts?.setSpeechRate(1.0f)
                androidTts?.setPitch(1.0f)
                androidTtsReady = true
                Log.d(TAG, "Android TTS fallback initialized")
            } else {
                Log.e(TAG, "Android TTS fallback failed with status: $status")
            }
        }
    }

    fun startListening() {
        if (_isListening.value) return
        if (!_sttEnabled.value) {
            _sttError.tryEmit("Speech-to-text is disabled in settings")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            _sttError.tryEmit("Speech recognition not available")
            return
        }

        // SpeechRecognizer must be created and used on the main thread
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Ready for speech")
                        _isListening.value = true
                        _partialResult.value = ""
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Speech began")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "Speech ended")
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing audio permission"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            else -> "Unknown error ($error)"
                        }
                        _sttError.tryEmit(errorMsg)
                        Log.e(TAG, "STT error: $errorMsg (code=$error)")
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d(TAG, "Final result: $text")
                        if (text.isNotBlank()) {
                            _finalResult.tryEmit(text)
                        }
                        _partialResult.value = ""
                        _isListening.value = false
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull() ?: ""
                        _partialResult.value = partial
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                speechRecognizer?.startListening(intent)
                Log.d(TAG, "Started listening")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition", e)
                _isListening.value = false
                _sttError.tryEmit("Failed to start: ${e.message}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition", e)
            }
            _isListening.value = false
        }
    }

    fun speak(text: String) {
        // Strip markdown before speaking to avoid TTS saying "star star" etc.
        val cleanText = stripMarkdown(text)

        // Use Kokoro if ready, fall back to Android TTS
        if (kokoroTts.isReady.value) {
            Log.d(TAG, "Speaking with Kokoro TTS")
            kokoroTts.speak(cleanText)
        } else if (androidTtsReady) {
            Log.d(TAG, "Kokoro not ready, using Android TTS fallback")
            val utteranceId = UUID.randomUUID().toString()
            androidTts?.speak(cleanText, TextToSpeech.QUEUE_ADD, null, utteranceId)
        } else {
            Log.w(TAG, "No TTS engine ready")
        }
    }

    /**
     * Strip markdown formatting from text to make it TTS-friendly.
     * Removes **, __, *, _, `, #, -, etc. while keeping the actual content.
     */
    private fun stripMarkdown(text: String): String {
        return text
            // Bold: **text** or __text__ → text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            // Italic: *text* or _text_ → text (but not URLs or emails)
            .replace(Regex("(?<!\\w)\\*(.+?)\\*(?!\\w)"), "$1")
            .replace(Regex("(?<!\\w)_(.+?)_(?!\\w)"), "$1")
            // Inline code: `code` → code
            .replace(Regex("`(.+?)`"), "$1")
            // Code blocks: ```code``` → code
            .replace(Regex("```[\\s\\S]*?```"), "")
            // Headers: # Header → Header
            .replace(Regex("^#+\\s+", RegexOption.MULTILINE), "")
            // Unordered lists: - item or * item → item
            .replace(Regex("^[-*]\\s+", RegexOption.MULTILINE), "")
            // Ordered lists: 1. item → item
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            // Links: [text](url) → text
            .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")
            // Images: ![alt](url) → alt
            .replace(Regex("!\\[(.+?)\\]\\(.+?\\)"), "$1")
            // Strikethrough: ~~text~~ → text
            .replace(Regex("~~(.+?)~~"), "$1")
            // Blockquotes: > text → text
            .replace(Regex("^>\\s+", RegexOption.MULTILINE), "")
            // Horizontal rules: --- or *** → (remove)
            .replace(Regex("^[-*_]{3,}$", RegexOption.MULTILINE), "")
            // Multiple newlines → single space
            .replace(Regex("\\n{2,}"), " ")
            // Clean up extra whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun stopSpeaking() {
        kokoroTts.stopSpeaking()
        androidTts?.stop()
    }

    fun setAutoTts(enabled: Boolean) {
        _autoTts.value = enabled
    }

    fun setTtsSpeed(speed: Float) {
        _ttsSpeed.value = speed
        kokoroTts.setSpeed(speed)
        androidTts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
    }

    fun setTtsPitch(pitch: Float) {
        _ttsPitch.value = pitch
        androidTts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    fun setSttEnabled(enabled: Boolean) {
        _sttEnabled.value = enabled
        if (!enabled) stopListening()
    }

    fun setSpeakerId(id: Int) {
        _selectedSpeaker.value = id
        kokoroTts.setSpeakerId(id)
    }

    fun getNumSpeakers(): Int {
        return kokoroTts.getNumSpeakers()
    }

    /**
     * Preview a voice by speaking its personality-matched phrase.
     * Temporarily switches to the preview speaker, speaks the phrase, then returns to the selected speaker.
     */
    fun previewSpeaker(speakerId: Int) {
        if (_isPreviewPlaying.value) {
            Log.d(TAG, "Preview already playing, stopping current preview")
            stopPreview()
        }

        // Get the preview phrase for this speaker
        val previewText = VoicePreviewPhrases.getPhraseForSpeaker(speakerId)

        // Store current speaker to restore later
        val currentSpeaker = _selectedSpeaker.value

        Log.d(TAG, "Previewing speaker $speakerId: $previewText")

        _isPreviewPlaying.value = true
        _previewingSpeakerId.value = speakerId

        // Temporarily switch to preview speaker
        kokoroTts.setSpeakerId(speakerId)

        // Speak the preview phrase
        if (kokoroTts.isReady.value) {
            kokoroTts.speak(previewText)

            // Monitor when speaking ends to restore state
            // Note: This is a simple approach; in production you might want to use a callback
            mainHandler.postDelayed({
                if (!kokoroTts.isSpeaking.value) {
                    _isPreviewPlaying.value = false
                    _previewingSpeakerId.value = null
                    // Restore the original speaker selection
                    kokoroTts.setSpeakerId(currentSpeaker)
                    Log.d(TAG, "Preview completed, restored speaker $currentSpeaker")
                }
            }, 100) // Check after 100ms delay
        } else {
            Log.w(TAG, "Kokoro TTS not ready for preview")
            _isPreviewPlaying.value = false
            _previewingSpeakerId.value = null
        }
    }

    /**
     * Stop the current voice preview.
     */
    fun stopPreview() {
        if (_isPreviewPlaying.value) {
            kokoroTts.stopSpeaking()
            _isPreviewPlaying.value = false
            _previewingSpeakerId.value = null
            // Restore original speaker
            kokoroTts.setSpeakerId(_selectedSpeaker.value)
            Log.d(TAG, "Preview stopped")
        }
    }

    fun shutdown() {
        stopListening()
        stopPreview()
        kokoroTts.shutdown()
        androidTts?.shutdown()
        androidTts = null
        androidTtsReady = false
    }
}
