// data/ChatRepository.kt
package com.example.myapplication.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(private val context: Context) {
    private val dao = ChatDatabase.getDatabase(context).chatMessageDao()
    private var currentSessionId: String = "nexus_${System.currentTimeMillis()}"

    fun getSessionId(): String = currentSessionId

    fun newSession() {
        currentSessionId = "nexus_${System.currentTimeMillis()}"
    }

    suspend fun addUserMessage(content: String, sessionId: String? = null) {
        val sid = sessionId ?: currentSessionId
        val message = ChatMessageEntity(
            sessionId = sid,
            sender = "USER",
            content = content,
            timestamp = System.currentTimeMillis()
        )
        dao.insert(message)
    }

    suspend fun addAssistantMessage(
        content: String,
        tag: String? = null,
        displayText: String? = null,
        payload: Any? = null,
        sessionId: String? = null
    ) {
        val sid = sessionId ?: currentSessionId
        val payloadJson = payload?.let {
            try {
                com.google.gson.Gson().toJson(it)
            } catch (e: Exception) {
                null
            }
        }
        val message = ChatMessageEntity(
            sessionId = sid,
            sender = "ASSISTANT",
            content = content,
            tag = tag,
            displayText = displayText ?: content,
            payloadJson = payloadJson,
            timestamp = System.currentTimeMillis()
        )
        dao.insert(message)
    }

    fun getMessagesForSession(sessionId: String? = null): Flow<List<ChatMessageEntity>> {
        val sid = sessionId ?: currentSessionId
        return dao.getMessagesForSession(sid)
    }

    suspend fun getLastMessages(sessionId: String? = null, limit: Int = 15): List<ChatMessageEntity> {
        val sid = sessionId ?: currentSessionId
        return dao.getLastMessages(sid, limit)
    }

    suspend fun clearSession(sessionId: String? = null) {
        val sid = sessionId ?: currentSessionId
        dao.clearSession(sid)
    }

    suspend fun cleanupOldMessages(daysToKeep: Int = 30) {
        val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        dao.deleteOldMessages(cutoff)
    }
}