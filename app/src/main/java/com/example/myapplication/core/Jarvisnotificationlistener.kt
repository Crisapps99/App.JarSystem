//package com.example.myapplication.service
//
//import android.app.Notification
//import android.app.RemoteInput
//import android.content.Context
//import android.content.Intent
//import android.os.Bundle
//import android.service.notification.NotificationListenerService
//import android.service.notification.StatusBarNotification
//import android.util.Log
//import com.example.myapplication.core.NotificationMemory
//
///**
// * JarvisNotificationListener
// *
// * Escucha todas las notificaciones del panel de Android en tiempo real.
// * Sincroniza NotificationMemory para que JarvisVoiceController pueda
// * leer el estado actual del panel cuando el usuario pregunta.
// *
// * AGREGAR en AndroidManifest.xml dentro de <application>:
// *
// *   <service
// *       android:name=".service.JarvisNotificationListener"
// *       android:label="Nexus Notificaciones"
// *       android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
// *       android:exported="true">
// *       <intent-filter>
// *           <action android:name="android.service.notification.NotificationListenerService" />
// *       </intent-filter>
// *   </service>
// *
// * El usuario activa el permiso en:
// *   Ajustes → Apps → Acceso especial → Acceso a notificaciones → Nexus ✓
// */
//class JarvisNotificationListener : NotificationListenerService() {
//
//    companion object {
//        private const val TAG = "JARVIS_NOTIF"
//
//        /**
//         * ✅ PÚBLICO - Se asigna en onListenerConnected()
//         * MyAccessibilityService llama: instance?.refrescarNotificacionesActivas()
//         */
//        @Volatile
//        public var instance: JarvisNotificationListener? = null
//
//        /** Verifica si el permiso está activado en ajustes del sistema */
//        fun tienePermiso(context: Context): Boolean {
//            val habilitados = android.provider.Settings.Secure.getString(
//                context.contentResolver,
//                "enabled_notification_listeners"
//            ) ?: return false
//            return habilitados.contains(context.packageName)
//        }
//
//        /** Abre ajustes para que el usuario active el permiso */
//        fun abrirAjustesPermiso(context: Context) {
//            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
//                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//            }
//            context.startActivity(intent)
//        }
//    }
//
//    // ────────────────────────────────────────────────────────────────────────
//    // LIFECYCLE
//    // ────────────────────────────────────────────────────────────────────────
//
//    override fun onCreate() {
//        super.onCreate()
//        Log.d(TAG, "✅ JarvisNotificationListener creado")
//    }
//
//    override fun onListenerConnected() {
//        super.onListenerConnected()
//        instance = this
//        Log.d(TAG, "✅ Listener conectado — cargando notificaciones del panel")
//        refrescarNotificacionesActivas()
//    }
//
//    override fun onListenerDisconnected() {
//        super.onListenerDisconnected()
//        instance = null
//        Log.d(TAG, "⚠️ Listener desconectado")
//    }
//
//    override fun onDestroy() {
//        instance = null
//        super.onDestroy()
//        Log.d(TAG, "🔴 JarvisNotificationListener destruido")
//    }
//
//    // ────────────────────────────────────────────────────────────────────────
//    // EVENTOS DEL SISTEMA
//    // ────────────────────────────────────────────────────────────────────────
//
//    /** Se llama cuando llega una nueva notificación al panel */
//    override fun onNotificationPosted(sbn: StatusBarNotification?) {
//        sbn ?: return
//        if (sbn.packageName == packageName) return // ignorar las propias de Nexus
//
//        val extras = sbn.notification.extras
//        val title  = extras.getString(Notification.EXTRA_TITLE) ?: ""
//        val body   = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
//        if (title.isBlank() && body.isBlank()) return // sin contenido visible, ignorar
//
//        Log.d(TAG, "📬 Nueva: [${sbn.packageName}] $title: ${body.take(60)}")
//
//        val item = convertirSbn(sbn)
//        if (item != null) NotificationMemory.addNotification(item)
//    }
//
//    /** Se llama cuando el usuario descarta una notificación del panel */
//    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
//        sbn ?: return
//        Log.d(TAG, "🗑️ Removida: ${sbn.packageName} key=${sbn.key}")
//        NotificationMemory.removeNotification(sbn.key)
//    }
//
//    // ────────────────────────────────────────────────────────────────────────
//    // MÉTODO PRINCIPAL — LEE EL PANEL COMPLETO EN TIEMPO REAL
//    // ────────────────────────────────────────────────────────────────────────
//
//    /**
//     * Lee TODAS las notificaciones visibles en el panel ahora mismo
//     * y sincroniza NotificationMemory con ese estado.
//     */
//    fun refrescarNotificacionesActivas() {
//        try {
//            val activas: Array<StatusBarNotification>? = activeNotifications
//
//            if (activas == null) {
//                Log.w(TAG, "⚠️ activeNotifications es null — ¿falta el permiso?")
//                return
//            }
//
//            Log.d(TAG, "📋 Notificaciones en panel ahora: ${activas.size}")
//
//            // Reconstruir memoria desde cero con el estado actual
//            NotificationMemory.clear()
//
//            // Ordenar más reciente primero
//            val ordenadas = activas.sortedByDescending { it.postTime }
//
//            var cargadas = 0
//            for (sbn in ordenadas) {
//                if (sbn.packageName == packageName) continue // ignorar propias
//
//                val item = convertirSbn(sbn) ?: continue
//                NotificationMemory.addNotification(item)
//                cargadas++
//
//                Log.d(
//                    TAG,
//                    "   ✓ [${item.appName}] ${item.title}: ${item.body.take(50)}" +
//                            if (item.canReply()) " ↩ puede responder" else ""
//                )
//            }
//
//            Log.d(TAG, "✅ $cargadas notificaciones sincronizadas")
//
//        } catch (e: SecurityException) {
//            Log.e(TAG, "❌ Sin permiso para leer notificaciones: ${e.message}")
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error en refrescarNotificacionesActivas: ${e.message}", e)
//        }
//    }
//
//    // ────────────────────────────────────────────────────────────────────────
//    // ACCIONES SOBRE NOTIFICACIONES
//    // ────────────────────────────────────────────────────────────────────────
//
//    /**
//     * Abre la notificación de una app (simula el tap del usuario sobre ella).
//     * Llamado desde MyAccessibilityService case "open_notification".
//     */
//    fun openNotification(context: Context, targetPackage: String) {
//        try {
//            val activas = activeNotifications ?: return
//            val target = activas.firstOrNull {
//                it.packageName.lowercase().contains(targetPackage.lowercase())
//            }
//            if (target != null) {
//                target.notification.contentIntent?.send()
//                Log.d(TAG, "✅ Notificación abierta: $targetPackage")
//            } else {
//                Log.w(TAG, "⚠️ Sin notificación activa de: $targetPackage")
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error abriendo notificación de $targetPackage: ${e.message}")
//        }
//    }
//
//    /**
//     * Responde una notificación con RemoteInput (reply inline sin abrir la app).
//     * Solo funciona en apps con respuesta directa: WhatsApp, Telegram, SMS, etc.
//     * Llamado desde MyAccessibilityService case "reply_notification".
//     */
//    fun replyToNotification(targetPackage: String, textoRespuesta: String) {
//        try {
//            val activas = activeNotifications ?: return
//            val target = activas.firstOrNull {
//                it.packageName.lowercase().contains(targetPackage.lowercase())
//            }
//
//            if (target == null) {
//                Log.w(TAG, "⚠️ Sin notificación respondible de: $targetPackage")
//                return
//            }
//
//            val actions = target.notification.actions
//            if (actions.isNullOrEmpty()) {
//                Log.w(TAG, "⚠️ La notificación de $targetPackage no tiene acciones")
//                return
//            }
//
//            var respondido = false
//            for (action in actions) {
//                // Verificar si esta acción tiene RemoteInputs (campo de texto para responder)
//                val remoteInputs: Array<RemoteInput>? = action.remoteInputs
//                if (remoteInputs.isNullOrEmpty()) continue
//
//                // Construir el Intent con el texto de respuesta
//                val replyIntent = Intent()
//                val bundle = Bundle()
//                bundle.putCharSequence(remoteInputs[0].resultKey, textoRespuesta)
//                RemoteInput.addResultsToIntent(remoteInputs, replyIntent, bundle)
//
//                // Enviar la respuesta directamente a la app
//                action.actionIntent.send(applicationContext, 0, replyIntent)
//
//                respondido = true
//                Log.d(TAG, "✅ Respuesta enviada a $targetPackage: '$textoRespuesta'")
//                break
//            }
//
//            if (!respondido) {
//                Log.w(TAG, "⚠️ Ninguna acción de $targetPackage acepta RemoteInput")
//            }
//
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error respondiendo notificación de $targetPackage: ${e.message}", e)
//        }
//    }
//
//    /**
//     * Descarta todas las notificaciones de una app.
//     */
//    fun descartarDeApp(targetPackage: String) {
//        try {
//            val activas = activeNotifications ?: return
//            var count = 0
//            activas
//                .filter { it.packageName.lowercase().contains(targetPackage.lowercase()) }
//                .forEach { cancelNotification(it.key); count++ }
//            Log.d(TAG, "🗑️ Descartadas $count notificaciones de $targetPackage")
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error descartando: ${e.message}")
//        }
//    }
//
//    /** Descarta TODAS las notificaciones del panel */
//    fun descartarTodas() {
//        try {
//            cancelAllNotifications()
//            NotificationMemory.clear()
//            Log.d(TAG, "🗑️ Todas las notificaciones descartadas")
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error en descartarTodas: ${e.message}")
//        }
//    }
//
//    // ────────────────────────────────────────────────────────────────────────
//    // PRIVADO — Conversión StatusBarNotification → NotificationItem
//    // ────────────────────────────────────────────────────────────────────────
//
//    /**
//     * ✅ Método PRIVADO de instancia
//     * Convierte un StatusBarNotification del sistema al modelo interno NotificationItem.
//     * Retorna NotificationMemory.NotificationItem? (NUNCA Any)
//     */
//    private fun convertirSbn(sbn: StatusBarNotification): NotificationMemory.NotificationItem? {
//        return try {
//            val extras = sbn.notification.extras
//
//            val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: ""
//            val body  = (
//                    extras.getCharSequence(Notification.EXTRA_TEXT)
//                        ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
//                        ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
//                    )?.toString()?.trim() ?: ""
//
//            if (title.isBlank() && body.isBlank()) return null
//
//            val appName = obtenerNombreApp(sbn.packageName)
//
//            // Buscar acción con RemoteInput (respuesta inline)
//            var replyAction: Notification.Action? = null
//            var remoteInputKey: String? = null
//
//            sbn.notification.actions?.forEach { action ->
//                if (replyAction != null) return@forEach // ya tenemos una
//                val remoteInputs: Array<RemoteInput>? = action.remoteInputs
//                if (!remoteInputs.isNullOrEmpty()) {
//                    replyAction    = action
//                    remoteInputKey = remoteInputs[0].resultKey
//                }
//            }
//
//            // ✅ Retorna EXACTAMENTE NotificationMemory.NotificationItem
//            NotificationMemory.NotificationItem(
//                sbnKey         = sbn.key,
//                appName        = appName,
//                packageName    = sbn.packageName,
//                title          = title,
//                body           = body,
//                timestamp      = sbn.postTime,
//                contentIntent  = sbn.notification.contentIntent,
//                replyAction    = replyAction,
//                remoteInputKey = remoteInputKey,
//            )
//
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error convirtiendo SBN ${sbn.key}: ${e.message}")
//            null  // ✅ retorna null, no Any
//        }
//    }
//
//    /**
//     * ✅ Método PRIVADO de instancia
//     * Obtiene el nombre legible de la app desde su packageName.
//     * Ej: "com.whatsapp" → "WhatsApp"
//     */
//    private fun obtenerNombreApp(pkg: String): String {
//        return try {
//            val info = packageManager.getApplicationInfo(pkg, 0)
//            packageManager.getApplicationLabel(info).toString()
//        } catch (e: Exception) {
//            // Fallback: última parte del package capitalizada
//            pkg.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
//        }
//    }
//}