package com.example.utt_trafficjams.data.model

/**
 * Model đại diện một tin nhắn trong chat.
 * Giữ no-arg constructor để thuận tiện serialize/deserialize.
 */
data class ChatMessage(
    val id: String = "",
    val text: String = "",
    /** "user" hoặc "ai" */
    val sender: String = "user",
    val timestamp: Long = System.currentTimeMillis()
)
