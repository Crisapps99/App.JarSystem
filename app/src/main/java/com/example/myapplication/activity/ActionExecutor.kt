// ARCHIVO: app/src/main/java/com/example/myapplication/activity/ActionExecutor.kt
package com.example.myapplication.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapplication.api.ActionDto
import com.example.myapplication.core.ContactsManager
import com.example.myapplication.core.MessagingManager
import com.google.gson.Gson

object ActionExecutor {

    // ── Callback de confirmación (lo setea MyAccessibilityService) ────────────
    var onConfirmacionPendiente: ((confirmado: Boolean) -> Unit)? = null

    // ── Flag interno para evitar doble disparo ────────────────────────────────
    private var confirmacionEnCurso = false
    private var pendingMessageText: String = ""

    // ─────────────────────────────────────────────────────────────────────────
    // WHATSAPP - VERSIÓN MEJORADA CON CONFIRMACIÓN DE VOZ
    // ─────────────────────────────────────────────────────────────────────────
    fun sendWhatsAppMessage(context: Context, contactName: String, message: String) {
        Log.d("JARVIS_ACTION", "📱 sendWhatsAppMessage: contacto='$contactName', mensaje='$message'")

        val contact = ContactsManager.findContact(context, contactName)

        if (contact == null) {
            Log.w("JARVIS_ACTION", "⚠️ Contacto '$contactName' no encontrado")
            openApp(context, "com.whatsapp")
            return
        }

        var numero = contact.phoneNumber.replace(Regex("[^0-9+]"), "")
        if (numero.startsWith("0") && !numero.startsWith("00")) {
            numero = "593" + numero.substring(1)
        }
        numero = numero.replace("+", "")

        Log.d("JARVIS_ACTION", "📱 Abriendo WhatsApp con ${contact.name} ($numero)")

        pendingMessageText = message

        // Abrir WhatsApp con el mensaje pre-escrito (NO lo envía todavía)
        val url = "https://api.whatsapp.com/send?phone=$numero&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)

        // Esperar que WhatsApp cargue el chat (~3s) y luego pedir confirmación
        Handler(Looper.getMainLooper()).postDelayed({
            _pedirConfirmacionEnvio(
                context = context,
                appPackage = "com.whatsapp",
                nombreContacto = contact.name,
                mensaje = message
            )
        }, 3500) // Aumentado a 3.5 segundos para asegurar carga
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TELEGRAM
    // ─────────────────────────────────────────────────────────────────────────
    fun sendTelegramMessage(context: Context, contactName: String, message: String) {
        Log.d("JARVIS_ACTION", "📱 sendTelegramMessage: contacto='$contactName'")

        val contact = ContactsManager.findContact(context, contactName)
        val nombreMostrar = contact?.name ?: contactName

        pendingMessageText = message
        openApp(context, "org.telegram.messenger")

        // Esperar a que Telegram cargue y luego escribir el mensaje
        Handler(Looper.getMainLooper()).postDelayed({
            _pedirConfirmacionEnvio(
                context = context,
                appPackage = "org.telegram.messenger",
                nombreContacto = nombreMostrar,
                mensaje = message
            )
        }, 2000)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NÚCLEO: Pedir confirmación de envío por voz
    // ─────────────────────────────────────────────────────────────────────────
    private fun _pedirConfirmacionEnvio(
        context: Context,
        appPackage: String,
        nombreContacto: String,
        mensaje: String
    ) {
        // ── Guard: evitar doble disparo ───────────────────────────────────────
        if (confirmacionEnCurso || onConfirmacionPendiente != null) {
            Log.w("JARVIS_ACTION", "⚠️ Confirmación ya en curso — ignorando duplicado")
            return
        }
        confirmacionEnCurso = true

        Log.d("JARVIS_ACTION", "🙈 Preparando confirmación para $appPackage")
        Log.d("JARVIS_ACTION", "   Contacto: $nombreContacto")
        Log.d("JARVIS_ACTION", "   Mensaje: ${mensaje.take(50)}...")

        // ── 1. Ocultar orbe para que NO toque nada en pantalla ────────────────
        val hideIntent = Intent("JARVIS.MESSAGE_READY_TO_SEND").apply {
            putExtra("package", appPackage)
            setPackage(context.packageName)
        }
        context.sendBroadcast(hideIntent)

        // ── 2. Registrar callback ─────────────────────────────────────────────
        onConfirmacionPendiente = { confirmado ->
            confirmacionEnCurso = false
            val callbackActual = onConfirmacionPendiente
            onConfirmacionPendiente = null

            if (confirmado) {
                Log.d("JARVIS_ACTION", "✅ Confirmado → enviando mensaje")
                _ejecutarTapEnviar(context)

                // Avisar al controlador: restaurar orbe
                val sentIntent = Intent("JARVIS.MESSAGE_SENT").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(sentIntent)
            } else {
                Log.d("JARVIS_ACTION", "❌ Cancelado → limpiando y volviendo a inicio")
                _cancelarEnvioYHome(context)

                // Avisar al controlador: restaurar orbe
                val cancelIntent = Intent("JARVIS.MESSAGE_CANCELLED").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(cancelIntent)
            }
        }

        // ── 3. Pedir confirmación de voz ──────────────────────────────────────
        Handler(Looper.getMainLooper()).postDelayed({
            val confirmIntent = Intent("JARVIS.PEDIR_CONFIRMACION").apply {
                putExtra("pregunta", "¿Enviar mensaje a $nombreContacto?")
                putExtra("package", appPackage)
                putExtra("mensaje", mensaje)
                setPackage(context.packageName)
            }
            context.sendBroadcast(confirmIntent)
            Log.d("JARVIS_ACTION", "❓ Confirmación solicitada para $nombreContacto")
        }, 500) // 500ms para que el broadcast de ocultar orbe llegue primero
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EJECUTAR TAP EN BOTÓN DE ENVIAR
    // ─────────────────────────────────────────────────────────────────────────
    private fun _ejecutarTapEnviar(context: Context) {
        // Intentar diferentes textos comunes para el botón de enviar
        val candidatos = listOf("Enviar", "Send", "send", "Enviar mensaje", "✓", "→", "▶")

        val accion = ActionDto(
            tipo = "tap_send_button",
            params = mapOf(
                "candidatos" to candidatos.joinToString(","),
                "fallback" to "enter"
            )
        )
        _ejecutarAccion(context, listOf(accion), "confirmar_envio")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CANCELAR ENVÍO Y VOLVER AL HOME
    // ─────────────────────────────────────────────────────────────────────────
    private fun _cancelarEnvioYHome(context: Context) {
        // 1. Limpiar el campo de texto (seleccionar todo y borrar)
        // 2. Volver atrás hasta salir de la app (múltiples veces)
        // 3. Ir al home
        val acciones = listOf(
            ActionDto(tipo = "clear_text_field", params = emptyMap()),
            ActionDto(tipo = "global_action", params = mapOf("action" to "back")),
            ActionDto(tipo = "global_action", params = mapOf("action" to "back")),
            ActionDto(tipo = "global_action", params = mapOf("action" to "home"))
        )
        _ejecutarAccion(context, acciones, "cancelar_envio_y_home")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODO GENÉRICO PARA ACCIONES SIMPLES
    // ─────────────────────────────────────────────────────────────────────────
    private fun _ejecutarAccion(context: Context, acciones: List<ActionDto>, intencion: String) {
        val intent = Intent("JARVIS.EXECUTE_ACTIONS").apply {
            putExtra("actions_json", Gson().toJson(acciones))
            putExtra("texto_original", intencion)
            putExtra("intencion_original", intencion)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.d("JARVIS_ACTION", "📡 Broadcast enviado: $intencion")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODOS EXISTENTES (sin cambios)
    // ─────────────────────────────────────────────────────────────────────────

    fun openApp(context: Context, packageName: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d("ACTION_EXEC", "✅ App abierta: $packageName")
            } else {
                Log.e("ACTION_EXEC", "❌ Intent es null para: $packageName")
            }
        } catch (e: Exception) {
            Log.e("ACTION_EXEC", "❌ Error abriendo app: ${e.message}", e)
        }
    }

    fun sendSms(context: Context, contactName: String, message: String) {
        val contact = ContactsManager.findContact(context, contactName)
        if (contact != null) MessagingManager.openSmsChat(context, contact.phoneNumber, message)
        else Log.w("JARVIS_ACTION", "⚠️ Contacto '$contactName' no encontrado para SMS")
    }

    fun callContact(context: Context, contactName: String) {
        val emergencias = listOf("911", "emergencia", "emergencias", "policía",
            "policia", "bomberos", "ambulancia", "auxilio")
        if (emergencias.any { contactName.lowercase().contains(it) }) {
            ContactsManager.makeCall(context, "911")
            return
        }
        val contact = ContactsManager.findContact(context, contactName)
        if (contact != null) {
            ContactsManager.makeCall(context, contact.phoneNumber)
        } else {
            val intent = Intent(Intent.ACTION_DIAL).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
        }
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = "") {
        Log.d("ActionExecutor", "⏰ Configurando alarma: $hour:$minute - '$label'")

        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            context.startActivity(intent)
            Log.d("ActionExecutor", "✅ Intent de alarma enviado")

            val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                val hora12 = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
                val ampm = if (hour >= 12) "pm" else "am"
                putExtra("texto", "Alarma configurada para las $hora12:${String.format("%02d", minute)} $ampm")
                setPackage(context.packageName)
            }
            context.sendBroadcast(speakIntent)

        } catch (e: Exception) {
            Log.e("ActionExecutor", " Error configurando alarma: ${e.message}")
            try {
                val clockIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.deskclock")
                clockIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            } catch (e2: Exception) {
                Log.e("ActionExecutor", "❌ Fallback falló: ${e2.message}")
            }
        }
    }
    /**
     * Reproduce música automáticamente en Spotify o YouTube Music.
     * @param query Búsqueda (canción, artista, etc.)
     * @param appPackage Paquete de la app (spotify o youtube music)
     */
    /**
     * Reproduce música automáticamente en Spotify o YouTube Music.
     * @param query Búsqueda (canción, artista, etc.)
     * @param appPackage Paquete de la app (spotify o youtube music)
     */
    fun playMusic(context: Context, query: String, appPackage: String = "com.spotify.music") {
        Log.d("ActionExecutor", "🎵 playMusic: query='$query', app='$appPackage'")

        // ✅ Declarar encodedQuery UNA SOLA VEZ al inicio
        val encodedQuery = Uri.encode(query)

        try {
            when {
                appPackage.contains("spotify") -> {
                    // Método 1: Deep link de Spotify que reproduce automáticamente
                    val spotifyUri = "https://open.spotify.com/search/$encodedQuery"

                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).apply {
                        setPackage("com.spotify.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Forzar reproducción automática
                        putExtra("autoplay", true)
                    }

                    context.startActivity(intent)
                    Log.d("ActionExecutor", "✅ Spotify abierto: búsqueda '$query' con autoplay")
                }

                appPackage.contains("youtube") -> {
                    // Método 2: YouTube Music con reproducción automática
                    // ✅ encodedQuery ya está declarado, no lo declares de nuevo

                    // Intentar primero con MEDIA_PLAY_FROM_SEARCH
                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        putExtra(android.app.SearchManager.QUERY, query)
                        putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*")
                        setPackage("com.google.android.apps.youtube.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    try {
                        context.startActivity(intent)
                        Log.d("ActionExecutor", "✅ YouTube Music: reproduciendo '$query'")
                    } catch (e: Exception) {
                        // Fallback: abrir YouTube normal
                        val fallbackIntent = Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery&autoplay=1")).apply {
                            setPackage("com.google.android.youtube")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallbackIntent)
                        Log.d("ActionExecutor", "✅ Fallback a YouTube: buscando '$query'")
                    }
                }

                else -> {
                    // Método genérico
                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        putExtra(android.app.SearchManager.QUERY, query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e("ActionExecutor", "❌ Error reproduciendo música: ${e.message}")
            // Último recurso: abrir navegador con búsqueda
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}+música")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
    /**
     * Toma una foto usando la cámara del dispositivo.
     * @param frontal true para usar la cámara frontal (selfie)
     * @param portrait true para modo retrato
     */
    fun takePhoto(context: Context, frontal: Boolean = false, portrait: Boolean = false) {
        Log.d("ActionExecutor", "📸 takePhoto: frontal=$frontal, portrait=$portrait")

        try {
            // Verificar permiso
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

                Log.e("ActionExecutor", "❌ Permiso de cámara no concedido")

                // Abrir ajustes
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }

            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (frontal) {
                    putExtra("android.intent.extras.CAMERA_FACING", 1)
                }
                if (portrait) {
                    putExtra("portrait_mode", true)
                }
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.d("ActionExecutor", "✅ Cámara abierta para tomar foto")
            } else {
                Log.e("ActionExecutor", "❌ No hay app de cámara")
            }

        } catch (e: Exception) {
            Log.e("ActionExecutor", "❌ Error: ${e.message}")
        }
    }
    /**
     * Reproduce video automáticamente en YouTube.
     */
    /**
     * Reproduce video automáticamente en YouTube.
     */
    fun playVideo(context: Context, query: String) {
        Log.d("ActionExecutor", "🎬 playVideo: query='$query'")

        if (query.isBlank()) {
            Log.w("ActionExecutor", "⚠️ Query vacía, abriendo YouTube")
            openApp(context, "com.google.android.youtube")
            return
        }

        // ✅ encodedQuery declarado FUERA del try-catch
        val encodedQuery = Uri.encode(query)

        try {
            // Intentar abrir YouTube con autoplay
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery&autoplay=1")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("ActionExecutor", "✅ YouTube: '$query' con autoplay")

        } catch (e: Exception) {
            Log.e("ActionExecutor", "❌ Error abriendo YouTube: ${e.message}")
            // Fallback: abrir en navegador
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }

    fun setAlarmAtTimestamp(context: Context, timestampMillis: Long, label: String = "") {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = timestampMillis
        }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        setAlarm(context, hour, minute, label)
    }
}