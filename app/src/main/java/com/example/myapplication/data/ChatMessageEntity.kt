// data/ChatMessageEntity.kt
package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "chat_history")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val sender: String, // "USER" o "ASSISTANT"
    val content: String,
    val tag: String? = null, // "conversation", "action", "search"
    val displayText: String? = null,
    val payloadJson: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)