package com.locallink.pro.data.repository

import android.graphics.Bitmap
import com.locallink.pro.data.db.MessageDao
import com.locallink.pro.data.db.MessageEntity
import com.locallink.pro.data.db.SessionDao
import com.locallink.pro.data.db.SessionEntity
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import com.locallink.pro.service.llm.LlmEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val llm: LlmEngine,
) {
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isAiResponding = MutableStateFlow(false)
    val isAiResponding: StateFlow<Boolean> = _isAiResponding.asStateFlow()

    fun observeSessions() = sessionDao.observeSessions()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMessages(): Flow<List<Message>> =
        _currentSessionId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else messageDao.observeMessages(id).map { list -> list.map { it.toDomain() } }
        }

    fun newSession() { _currentSessionId.value = null } // session row created lazily on first send

    fun loadSession(id: String) { _currentSessionId.value = id }

    suspend fun deleteSession(id: String) {
        sessionDao.getById(id)?.let { sessionDao.delete(it) }
        if (_currentSessionId.value == id) _currentSessionId.value = null
    }

    /** Persist user msg, stream AI reply, persist assistant msg on completion. */
    suspend fun send(text: String, image: Bitmap? = null, imageUri: String? = null, isVoice: Boolean = false) {
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(text, now)

        messageDao.insert(
            MessageEntity(sessionId = sessionId, role = "user", text = text, imageUri = imageUri, isVoice = isVoice, timestamp = now)
        )
        touchSession(sessionId)

        // History excluding the just-inserted user turn (passed separately as prompt).
        val history = messageDao.getMessages(sessionId)
            .filter { it.role == "user" || it.role == "assistant" }
            .map { it.role to it.text }
            .dropLast(1)

        _isAiResponding.value = true
        _streamingText.value = ""
        val sb = StringBuilder()
        try {
            llm.generateStream(prompt = text, image = image, history = history).collect { chunk ->
                sb.append(chunk)
                _streamingText.value = sb.toString()
            }
            messageDao.insert(
                MessageEntity(sessionId = sessionId, role = "assistant", text = sb.toString(), timestamp = System.currentTimeMillis())
            )
        } catch (e: Exception) {
            messageDao.insert(
                MessageEntity(sessionId = sessionId, role = "system", text = "Error: ${e.message}", timestamp = System.currentTimeMillis())
            )
        } finally {
            _streamingText.value = ""
            _isAiResponding.value = false
            touchSession(sessionId)
        }
    }

    private suspend fun ensureSession(firstText: String, now: Long): String {
        _currentSessionId.value?.let { return it }
        val id = UUID.randomUUID().toString()
        sessionDao.upsert(
            SessionEntity(id = id, title = firstText.take(40).ifBlank { "New chat" }, createdAt = now, updatedAt = now)
        )
        _currentSessionId.value = id
        return id
    }

    private suspend fun touchSession(id: String) {
        sessionDao.getById(id)?.let { sessionDao.upsert(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun clearAll() {
        sessionDao.deleteAll()
        _currentSessionId.value = null
    }

    private fun MessageEntity.toDomain() = Message(
        id = id,
        text = text,
        sender = when (role) {
            "user" -> MessageSender.USER
            "assistant" -> MessageSender.AI
            else -> MessageSender.SYSTEM
        },
        timestamp = timestamp,
        isVoice = isVoice,
        imageUri = imageUri,
    )
}
