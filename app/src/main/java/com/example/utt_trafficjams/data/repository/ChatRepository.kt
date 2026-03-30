package com.example.utt_trafficjams.data.repository

import com.example.utt_trafficjams.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Repository chat local-only (khong su dung dich vu cloud).
 */
class ChatRepository {

    private val localMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    var isReady = true
        private set

    suspend fun sendMessage(text: String, sender: String): String {
        val now = System.currentTimeMillis()
        val id = now.toString()
        val msg = ChatMessage(id = id, text = text, sender = sender, timestamp = now)
        localMessages.value = localMessages.value + msg
        return id
    }

    fun observeMessages(): Flow<List<ChatMessage>> = localMessages

    suspend fun clearHistory() {
        localMessages.value = emptyList()
    }
}
