// ARCHIVO: app/src/main/java/com/example/myapplication/activity/ActionExecutor.kt
// REEMPLAZA tu ActionExecutor existente con este
package com.example.myapplication.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.myapplication.core.ContactsManager
import com.example.myapplication.core.MessagingManager

object ActionExecutor {

    // Callback para cuando el usuario confirme o rechace
    // Se setea desde MyAccessibilityService
    var onConfirmacionPendiente: ((confirmado: Boolean) -> Unit)? = null

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

        Log.d("JARVIS_ACTION", "📱 Abriendo chat con ${contact.name} ($numero)")
        Log.d("JARVIS_ACTION", "📱 Mensaje preparado: '$message'")

        // Abrir WhatsApp con el mensaje pre-escrito en el campo de texto
        val url = "https://api.whatsapp.com/send?phone=$numero&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)

        // Después de 3s WhatsApp ya cargó el chat con el mensaje escrito
        // → pedir confirmación por voz EN LUGAR de enviar automáticamente
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            _pedirConfirmacionEnvio(context, contact.name, message)
        }, 3000)
    }

    // REEMPLAZA _pedirConfirmacionEnvio completo:
    private fun _pedirConfirmacionEnvio(context: Context, nombreContacto: String, mensaje: String) {
        // Guard: evitar doble disparo
        if (onConfirmacionPendiente != null) {
            Log.w("JARVIS_ACTION", "⚠️ Confirmación ya pendiente — ignorando duplicado")
            return
        }

        Log.d("JARVIS_ACTION", "❓ Pidiendo confirmación de envío")

        onConfirmacionPendiente = { confirmado ->
            if (confirmado) {
                Log.d("JARVIS_ACTION", "✅ Usuario confirmó → enviando mensaje")
                _tapEnviarWhatsApp(context)
            } else {
                Log.d("JARVIS_ACTION", "❌ Usuario canceló → saliendo de WhatsApp")
                _salirDeWhatsApp(context)
            }
            onConfirmacionPendiente = null
        }

        val intentConfirm = Intent("JARVIS.PEDIR_CONFIRMACION").apply {
            putExtra("pregunta", "¿Enviar mensaje a $nombreContacto?")  // ← más corta
            putExtra("tipo", "envio_mensaje")
            setPackage(context.packageName)
        }
        context.sendBroadcast(intentConfirm)
    }

    private fun _tapEnviarWhatsApp(context: Context) {
        val accion = com.example.myapplication.api.ActionDto(
            tipo = "ocr_tap",
            params = mapOf("texto" to "Enviar")
        )
        val intent = Intent("JARVIS.EXECUTE_ACTIONS").apply {
            putExtra("actions_json", com.google.gson.Gson().toJson(listOf(accion)))
            putExtra("texto_original", "enviar whatsapp confirmado")
            putExtra("intencion_original", "send_whatsapp_confirmed")
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.d("JARVIS_ACTION", "📤 Tap Enviar ejecutado")
    }

    private fun _salirDeWhatsApp(context: Context) {
        val accion = com.example.myapplication.api.ActionDto(
            tipo = "global_action",
            params = mapOf("action" to "back")
        )
        val intent = Intent("JARVIS.EXECUTE_ACTIONS").apply {
            putExtra("actions_json", com.google.gson.Gson().toJson(listOf(accion)))
            putExtra("texto_original", "cancelar envio whatsapp")
            putExtra("intencion_original", "cancel_send")
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.d("JARVIS_ACTION", "↩️ Saliendo de WhatsApp")
    }

    // ── El resto de métodos sin cambios ───────────────────────────────────────
    fun openApp(context: Context, packageName: String) {
        if (MessagingManager.isAppInstalled(context, packageName)) {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                ?.let { context.startActivity(it) }
        } else {
            MessagingManager.openPlayStore(context, packageName)
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