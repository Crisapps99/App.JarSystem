// ARCHIVO: app/src/main/java/com/example/myapplication/activity/ActionExecutor.kt
package com.example.myapplication.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.api.ActionDto
import com.example.myapplication.core.ContactsManager
import com.example.myapplication.core.MessagingManager
import com.google.gson.Gson

object ActionExecutor {

    // ── Callback de confirmación (lo setea MyAccessibilityService) ────────────
    var onConfirmacionPendiente: ((confirmado: Boolean) -> Unit)? = null

    // ── Flag interno para evitar doble disparo ────────────────────────────────
    private var confirmacionEnCurso = false

    // ─────────────────────────────────────────────────────────────────────────
    // WHATSAPP
    // ─────────────────────────────────────────────────────────────────────────
    fun sendWhatsAppMessage(context: Context, contactName: String, message: String) {
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

        // Abrir WhatsApp con el mensaje pre-escrito (NO lo envía todavía)
        val url = "https://api.whatsapp.com/send?phone=$numero&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)

        // Esperar que WhatsApp cargue el chat (~3s) y luego:
        // 1. Ocultar el orbe
        // 2. Pedir confirmación por voz
        Handler(Looper.getMainLooper()).postDelayed({
            _ocultarOrbeYPedirConfirmacion(
                context     = context,
                appPackage  = "com.whatsapp",
                nombreContacto = contact.name,
                mensaje     = message,
                onConfirmado   = { _tapEnviarPorTexto(context, "Enviar") },
                onCancelado    = { _cancelarEnvio(context) }
            )
        }, 3000)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TELEGRAM
    // ─────────────────────────────────────────────────────────────────────────
    fun sendTelegramMessage(context: Context, contactName: String, message: String) {
        // Telegram no tiene deep-link con texto pre-escrito tan directo,
        // así que abrimos la app y dejamos que el AccessibilityService
        // escriba el texto via type_text. Cuando termina de escribir,
        // llama a pedirConfirmacionGeneral().
        openApp(context, "org.telegram.messenger")
        // El AccessibilityService maneja el resto con OCR + type_text
        // y al terminar llama pedirConfirmacionGeneral() directamente.
        Log.d("JARVIS_ACTION", "📱 Telegram abierto para $contactName")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODO GENÉRICO — Llamar desde AccessibilityService para CUALQUIER app
    // después de que el texto ya está escrito en el campo.
    //
    // Uso en MyAccessibilityService, cuando termines de escribir el mensaje:
    //
    //   ActionExecutor.pedirConfirmacionGeneral(
    //       context        = this,
    //       appPackage     = currentPackage,       // ej. "org.telegram.messenger"
    //       nombreContacto = "Mamá",
    //       onConfirmado   = { tapEnviarButton() },
    //       onCancelado    = { borrarTextoYVolver() }
    //   )
    // ─────────────────────────────────────────────────────────────────────────
    fun pedirConfirmacionGeneral(
        context: Context,
        appPackage: String,
        nombreContacto: String,
        onConfirmado: () -> Unit,
        onCancelado: () -> Unit
    ) {
        _ocultarOrbeYPedirConfirmacion(
            context        = context,
            appPackage     = appPackage,
            nombreContacto = nombreContacto,
            mensaje        = "",   // ya está escrito en pantalla
            onConfirmado   = onConfirmado,
            onCancelado    = onCancelado
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NÚCLEO: ocultar orbe + registrar callback + disparar pregunta de voz
    // ─────────────────────────────────────────────────────────────────────────
    private fun _ocultarOrbeYPedirConfirmacion(
        context: Context,
        appPackage: String,
        nombreContacto: String,
        mensaje: String,
        onConfirmado: () -> Unit,
        onCancelado: () -> Unit
    ) {
        // ── Guard: evitar doble disparo ───────────────────────────────────────
        if (confirmacionEnCurso || onConfirmacionPendiente != null) {
            Log.w("JARVIS_ACTION", "⚠️ Confirmación ya en curso — ignorando duplicado")
            return
        }
        confirmacionEnCurso = true

        Log.d("JARVIS_ACTION", "🙈 Ocultando orbe para $appPackage")

        // ── 1. Ocultar orbe para que NO toque nada en pantalla ────────────────
        val hideIntent = Intent("JARVIS.MESSAGE_READY_TO_SEND").apply {
            putExtra("package", appPackage)
            setPackage(context.packageName)
        }
        context.sendBroadcast(hideIntent)

        // ── 2. Registrar callback ─────────────────────────────────────────────
        onConfirmacionPendiente = { confirmado ->
            confirmacionEnCurso = false
            onConfirmacionPendiente = null

            if (confirmado) {
                Log.d("JARVIS_ACTION", "✅ Confirmado → ejecutando envío")
                onConfirmado()

                // Avisar al controlador: restaurar orbe
                val sentIntent = Intent("JARVIS.MESSAGE_SENT").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(sentIntent)

            } else {
                Log.d("JARVIS_ACTION", "❌ Cancelado → borrando y saliendo")
                onCancelado()

                // Avisar al controlador: restaurar orbe
                val cancelIntent = Intent("JARVIS.MESSAGE_CANCELLED").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(cancelIntent)
            }
        }

        // ── 3. Pedir confirmación de voz ──────────────────────────────────────
        // Pequeño delay para que el orbe ya esté oculto antes de que el TTS hable
        Handler(Looper.getMainLooper()).postDelayed({
            val confirmIntent = Intent("JARVIS.PEDIR_CONFIRMACION").apply {
                putExtra("pregunta", "¿Enviar mensaje a $nombreContacto?")
                putExtra("package", appPackage)
                setPackage(context.packageName)
            }
            context.sendBroadcast(confirmIntent)
            Log.d("JARVIS_ACTION", "❓ Confirmación solicitada para $nombreContacto")
        }, 300) // 300ms para que el broadcast de ocultar orbe llegue primero
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS DE ACCIÓN
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Busca el botón de enviar por texto/content-description.
     * Funciona para WhatsApp, Telegram, Messenger, SMS nativo, etc.
     * El AccessibilityService hará el tap en el primer nodo que coincida.
     */
    private fun _tapEnviarPorTexto(context: Context, vararg textosBuscar: String = arrayOf("Enviar", "Send", "send")) {
        // Intentamos con cada texto candidato en orden
        // El AccessibilityService en ocr_tap ya busca por coincidencia parcial
        val accion = ActionDto(
            tipo = "tap_send_button",  // tipo especial que maneja MyAccessibilityService
            params = mapOf(
                "candidatos" to textosBuscar.joinToString(","),
                // Fallback: si no encuentra el botón, usa ENTER
                "fallback" to "enter"
            )
        )
        _ejecutarAccion(context, listOf(accion), "confirmar_envio")
    }

    private fun _cancelarEnvio(context: Context) {
        // 1. Borrar el texto del campo (select all + delete)
        // 2. Volver a la pantalla anterior
        val acciones = listOf(
            ActionDto(tipo = "clear_text_field", params = emptyMap()),
            ActionDto(tipo = "global_action", params = mapOf("action" to "back"))
        )
        _ejecutarAccion(context, acciones, "cancelar_envio")
    }

    private fun _ejecutarAccion(context: Context, acciones: List<ActionDto>, intencion: String) {
        val intent = Intent("JARVIS.EXECUTE_ACTIONS").apply {
            putExtra("actions_json", Gson().toJson(acciones))
            putExtra("texto_original", intencion)
            putExtra("intencion_original", intencion)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODOS SIN CAMBIOS
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

}