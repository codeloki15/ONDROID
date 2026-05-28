package com.locallink.pro.domain.model

import java.util.UUID

enum class MessageSender { USER, AI, SYSTEM }

enum class MessageStatus { SENDING, SENT, ERROR }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: MessageSender,
    val timestamp: Long = System.currentTimeMillis(),
    val isVoice: Boolean = false,
    val imageUri: String? = null,
    val status: MessageStatus = MessageStatus.SENT,
)
