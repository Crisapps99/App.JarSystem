package com.example.myapplication.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.myapplication.core.NotificationMemory

/**
 * Servicio que escucha TODAS las notificaciones del sistema.
 *
 * Para activarlo el usuario debe ir a:
 *   Ajustes → Aplicaciones → Acceso especial → Acceso a notificaciones → activar esta app
 *
 * Capacidades:
 *  - Captura notificaciones y las guarda en NotificationMemory
 *  - openNotification(context, packageName) → abre la app desde la notificación
 *  - replyToNotification(packageName, texto) → responde inline (WhatsApp, Telegram, SMS, etc.)
 */
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NOTIF_LISTENER"
        private var instance: JarvisNotificationListener? = null

        // ── Abrir notificación de una app específica ───────────────
        fun openNotification(context: Context, packageName: String): Boolean {
            val notif = NotificationMemory.getLatestFromApp(packageName)
            if (notif == null) {
                Log.w(TAG, "No hay notificación de $packageName")
                return false
            }
            return try {
                notif.contentIntent?.send()
                Log.d(TAG, "Notificación abierta: ${notif.appName}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error abriendo notificación: ${e.message}")
                // Fallback: abrir la app directamente
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launchIntent != null) context.startActivity(launchIntent)
                false
            }
        }

        // ── Responder inline a la última notificación de una app ──
        fun replyToNotification(packageName: String, replyText: String): Boolean {
            val notif = NotificationMemory.getLatestFromApp(packageName) ?: run {
                Log.w(TAG, "Sin notificación de $packageName para responder")
                return false
            }

            val action = notif.replyAction ?: run {
                Log.w(TAG, "La notificación de ${notif.appName} no tiene acción de respuesta")
                return false
            }

            val remoteInput = notif.remoteInputKey ?: run {
                Log.w(TAG, "La notificación de ${notif.appName} no tiene RemoteInput")
                return false
            }

            return try {
                val bundle = Bundle().apply {
                    putCharSequence(remoteInput, replyText)
                }
                val fillIntent = Intent().apply {
                    RemoteInput.addResultsToIntent(
                        arrayOf(RemoteInput.Builder(remoteInput).build()),
                        this,
                        bundle,
                    )
                }
                // Si instance es null, el servicio no está corriendo.
                instance?.let { serviceContext ->
                    action.actionIntent.send(serviceContext, 0, fillIntent)
                    Log.d(TAG, "Respuesta enviada a ${notif.appName}: '$replyText'")
                    true
                } ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Error al responder: ${e.message}")
                false
            }
        }

        fun isAvailable() = instance != null
    }

    // ── Ciclo de vida del servicio ────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "NotificationListener conectado")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Captura de notificaciones entrantes ───────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Ignorar notificaciones propias
        // Dentro de onNotificationPosted
        if (sbn.packageName == "com.android.systemui") return // Ignorar spam de carga

        val notification  = sbn.notification ?: return
        val extras        = notification.extras ?: return

        val appName   = getAppName(sbn.packageName)
        val title     = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body      = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        // Solo guardar si tiene contenido útil
        if (title.isBlank() && body.isBlank()) return

        // Buscar acción de respuesta inline (WhatsApp, Telegram, Messenger, SMS…)
        val replyAction = findReplyAction(notification)
        val remoteInputKey = replyAction?.remoteInputs?.firstOrNull()?.resultKey

        val item = NotificationMemory.NotificationItem(
            sbnKey        = sbn.key,
            appName       = appName,
            packageName   = sbn.packageName,
            title         = title,
            body          = body,
            timestamp     = sbn.postTime,
            contentIntent = notification.contentIntent,
            replyAction   = replyAction,
            remoteInputKey= remoteInputKey,
        )

        NotificationMemory.addNotification(item)
        Log.d(TAG, "[$appName] $title: ${body.take(50)}" +
                if (replyAction != null) " [respuesta inline disponible]" else "")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationMemory.removeNotification(sbn.key)
    }

    // ── Utilidades ────────────────────────────────────────────────

    private fun getAppName(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    /**
     * Busca la acción de respuesta directa en la notificación.
     * Compatible con WhatsApp, Telegram, Messenger, SMS, Gmail.
     */
    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null

        return actions.firstOrNull { action: Notification.Action ->
            val hasRemoteInput = action.remoteInputs?.isNotEmpty() == true
            val title = action.title?.toString()?.lowercase() ?: ""
            hasRemoteInput && (
                    title.contains("responder") ||
                            title.contains("reply") ||
                            title.contains("contestar") ||
                            title.contains("answer")
                    )
        } ?: actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
    }
}
