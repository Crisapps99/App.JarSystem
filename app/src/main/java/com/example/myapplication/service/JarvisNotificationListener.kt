package com.example.myapplication.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.myapplication.core.NotificationMemory
import com.google.android.gms.common.config.GservicesValue.isInitialized

class JarvisNotificationListener : NotificationListenerService() {
    private var isInitialized = false
    companion object {
        private const val TAG = "JARVIS_NOTIF"

        @Volatile
        var instance: JarvisNotificationListener? = null

        fun tienePermiso(context: Context): Boolean {
            val habilitados = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return habilitados.contains(context.packageName)
        }

        fun abrirAjustesPermiso(context: Context) {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (isInitialized) {
            Log.d("JARVIS_NOTIF", "Ya estaba conectado, ignorando re-inicialización")
            return
        }

        isInitialized = true
        Log.d("JARVIS_NOTIF", "Conectado — cargando panel")
        instance = this
        Log.d(TAG, " Listener conectado — cargando panel")
        refrescarNotificacionesActivas()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d(TAG, " Listener desconectado")
    }

    // --- EVENTOS ---

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return

        //  FIX: Usar getCharSequence para evitar ClassCastException (SpannableString)
        val item = convertirSbn(sbn) ?: return

        Log.d(TAG, "📬 Nueva: [${item.appName}] ${item.title}: ${item.body.take(60)}")
        NotificationMemory.addNotification(item)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationMemory.removeNotification(sbn.key)
    }

    // --- LÓGICA DE SINCRONIZACIÓN ---

    fun refrescarNotificacionesActivas() {
        try {
            val activas = activeNotifications ?: return
            NotificationMemory.clear()

            val ordenadas = activas.sortedByDescending { it.postTime }
            var cargadas = 0

            for (sbn in ordenadas) {
                if (sbn.packageName == packageName) continue
                val item = convertirSbn(sbn) ?: continue
                NotificationMemory.addNotification(item)
                cargadas++
            }
            Log.d(TAG, " $cargadas notificaciones sincronizadas")
        } catch (e: Exception) {
            Log.e(TAG, " Error refrescando: ${e.message}")
        }
    }

    // --- CONVERSIÓN SEGURA ---

    private fun convertirSbn(sbn: StatusBarNotification): NotificationMemory.NotificationItem? {
        return try {
            val extras = sbn.notification.extras

            // Extracción universal segura (Acepta String y SpannableString)
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

            // Intentar obtener el cuerpo del mensaje priorizando el texto largo
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
                    )?.toString()?.trim() ?: ""

            if (title.isBlank() && body.isBlank()) return null

            val appName = obtenerNombreApp(sbn.packageName)

            // Buscar si permite respuesta directa (WhatsApp, Messenger, etc.)
            var replyAction: Notification.Action? = null
            var remoteInputKey: String? = null

            sbn.notification.actions?.forEach { action ->
                action.remoteInputs?.let { inputs ->
                    if (inputs.isNotEmpty()) {
                        replyAction = action
                        remoteInputKey = inputs[0].resultKey
                    }
                }
            }

            NotificationMemory.NotificationItem(
                sbnKey = sbn.key,
                appName = appName,
                packageName = sbn.packageName,
                title = title,
                body = body,
                timestamp = sbn.postTime,
                contentIntent = sbn.notification.contentIntent,
                replyAction = replyAction,
                remoteInputKey = remoteInputKey
            )
        } catch (e: Exception) {
            Log.e(TAG, " Error en convertirSbn: ${e.message}")
            null
        }
    }
    /**
     * Responde una notificación con RemoteInput (reply inline sin abrir la app).
     * Solo funciona en apps con respuesta directa: WhatsApp, Messenger, Telegram, etc.
     */
    fun replyToNotification(targetPackage: String, textoRespuesta: String) {
        try {
            val activas = activeNotifications ?: return
            // Buscamos la notificación que coincida con el paquete (ej: "com.whatsapp")
            val target = activas.firstOrNull {
                it.packageName.contains(targetPackage, ignoreCase = true)
            }

            if (target == null) {
                Log.w(TAG, "️ No se encontró notificación respondible para: $targetPackage")
                return
            }

            val actions = target.notification.actions
            if (actions.isNullOrEmpty()) {
                Log.w(TAG, " La notificación no tiene acciones de respuesta")
                return
            }

            var respondido = false
            for (action in actions) {
                // Buscamos si la acción tiene RemoteInputs (el campo para escribir)
                val remoteInputs = action.remoteInputs
                if (remoteInputs.isNullOrEmpty()) continue

                // Creamos el "puente" para enviar el texto
                val replyIntent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(remoteInputs[0].resultKey, textoRespuesta)
                android.app.RemoteInput.addResultsToIntent(remoteInputs, replyIntent, bundle)

                // Enviamos la respuesta "en las sombras"
                action.actionIntent.send(applicationContext, 0, replyIntent)

                respondido = true
                Log.d(TAG, " Respuesta enviada a $targetPackage: '$textoRespuesta'")
                break
            }

            if (!respondido) {
                Log.w(TAG, "⚠ Ninguna acción de esta app permite respuesta directa")
            }

        } catch (e: Exception) {
            Log.e(TAG, " Error en replyToNotification: ${e.message}")
        }
    }
    private fun obtenerNombreApp(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
        }
    }

    // --- ACCIONES ADICIONALES ---

    fun openNotification(context: Context, targetPackage: String) {
        activeNotifications?.firstOrNull { it.packageName.contains(targetPackage, true) }?.let {
            it.notification.contentIntent?.send()
        }
    }

    fun descartarTodas() {
        cancelAllNotifications()
        NotificationMemory.clear()
    }
}