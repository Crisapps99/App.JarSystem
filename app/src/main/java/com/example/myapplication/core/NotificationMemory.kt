package com.example.myapplication.core

import android.app.Notification
import android.app.PendingIntent

/**
 * Almacén en memoria de las notificaciones recientes.
 * JarvisNotificationListener escribe aquí.
 * JarvisVoiceController lee toContextText() para enviarlo al servidor.
 * MyAccessibilityService llama openNotification() / replyToNotification() al ejecutar acciones.
 */
object NotificationMemory {

    data class NotificationItem(
        val sbnKey        : String,
        val appName       : String,
        val packageName   : String,
        val title         : String,
        val body          : String,
        val timestamp     : Long,
        // Referencias para abrir / responder
        val contentIntent : PendingIntent?,
        val replyAction   : Notification.Action?,
        val remoteInputKey: String?,
    ) {
        fun canReply() = replyAction != null && remoteInputKey != null
    }

    private val _list = mutableListOf<NotificationItem>()
    private const val MAX = 50
    val all: List<NotificationItem> get() = _list.toList()
    private val notifications = mutableMapOf<String, NotificationItem>()
    // ── Escribir ───────────────────────────────────────────────────

    fun addNotification(item: NotificationItem) {
        notifications[item.sbnKey] = item
    }

    fun getNotifications(): List<NotificationItem> {
        return notifications.values.toList()
    }
    fun removeNotification(sbnKey: String) {
        _list.removeAll { it.sbnKey == sbnKey }
    }

    fun clear() = _list.clear()

    // ── Leer ───────────────────────────────────────────────────────

    /**
     * Última notificación de una app dada (por packageName o por nombre parcial).
     * getLatestFromApp("com.whatsapp") → último mensaje de WhatsApp
     * getLatestFromApp("whatsapp")     → igual, búsqueda parcial
     */
    fun getLatestFromApp(query: String): NotificationItem? {
        val q = query.lowercase()
        return _list.firstOrNull {
            it.packageName.lowercase().contains(q) ||
                    it.appName.lowercase().contains(q)
        }
    }

    fun getFromApp(query: String): List<NotificationItem> {
        val q = query.lowercase()
        return _list.filter {
            it.packageName.lowercase().contains(q) ||
                    it.appName.lowercase().contains(q)
        }
    }

    /**
     * Texto de contexto que se envía al servidor en cada request.
     * Incluye si la notificación admite respuesta inline.
     *
     * Ejemplo:
     *  - [WhatsApp] María Cristina: Hola, ¿vienes? (hace 2min) [puede responder]
     *  - [Telegram] Canal Noticias: Nuevo artículo (hace 5min)
     */
    fun toContextText(): String {
        if (_list.isEmpty()) return "Sin notificaciones recientes."
        return _list.take(15).joinToString("\n") { notif ->
            val diff = System.currentTimeMillis() - notif.timestamp
            val tiempo = when {
                diff < 60_000        -> "hace ${diff / 1000}s"
                diff < 3_600_000     -> "hace ${diff / 60_000}min"
                else                 -> "hace ${diff / 3_600_000}h"
            }
            val replyTag = if (notif.canReply()) " [puede responder]" else ""
            "- [${notif.appName}] ${notif.title}: ${notif.body.take(80)} ($tiempo)$replyTag"
        }
    }

    /**
     * Resumen corto para respuesta por voz.
     * "Tienes 3 notificaciones. WhatsApp: María Cristina. Telegram: 2 mensajes. Gmail: Factura."
     */
    fun toVoiceSummary(): String {
        if (_list.isEmpty()) return "No tienes notificaciones pendientes."
        val grouped = _list.groupBy { it.appName }
        val resumen = grouped.entries.take(5).joinToString(". ") { (app, items) ->
            val ultimo = items.first()
            if (items.size == 1) "$app: ${ultimo.title}"
            else "$app: ${items.size} notificaciones"
        }
        return "Tienes ${_list.size} notificaciones. $resumen."
    }

    /**
     * Lista de apps que tienen notificaciones RESPONDIBLES (para el servidor).
     * El servidor incluye esto para que LLaMA sepa a qué apps puede responder.
     */
    fun getRespondableApps(): List<String> =
        _list.filter { it.canReply() }
            .map { it.appName }
            .distinct()
}
