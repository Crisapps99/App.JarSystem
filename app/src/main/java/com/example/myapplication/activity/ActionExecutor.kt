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
import com.example.myapplication.core.integrations.ContactsManager
import com.example.myapplication.core.integrations.MessagingManager
import com.example.myapplication.core.integrations.YoutubeController
import com.example.myapplication.core.integrations.YoutubeController.YouTubeCache
import com.google.gson.Gson
import kotlinx.coroutines.launch
import android.provider.ContactsContract


object ActionExecutor {

    private val YOUTUBE_API_KEY = "AIzaSyDbkFoz0-6cj2AR8cXGJVci2RPK_0oAxos"
    // ── Callback de confirmación (lo setea MyAccessibilityService) ────────────
    var onConfirmacionPendiente: ((confirmado: Boolean) -> Unit)? = null

    var pendingWhatsappContact: String = ""
    var pendingWhatsappMessage: String = ""

    // ── Flag interno para evitar doble disparo ────────────────────────────────
    private var confirmacionEnCurso = false
    private var pendingMessageText: String = ""
    private var pendingNavName: String = ""
    private var pendingNavLat: Double = 0.0
    private var pendingNavLng: Double = 0.0

//    fun showPlaceAndConfirmNavigation(
//        context: Context,
//        name: String,
//        address: String,
//        lat: Double,
//        lng: Double,
//        placeId: String = ""
//    ) {
//        // 1. Abrir Maps en el punto exacto (solo vista, no navegación)
//        val uri = "geo:0,0?q=${lat},${lng}(${Uri.encode(name)})"
//        val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
//            setPackage("com.google.android.apps.maps")
//            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//        }
//        try {
//            context.startActivity(mapIntent)
//        } catch (e: Exception) {
//            // Fallback: abrir en navegador
//            val webUri = "https://www.google.com/maps/search/?api=1&query=${lat},${lng}"
//            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
//        }
//
//        // 2. Esperar 1.5s para que cargue Maps y luego pedir confirmación
//        Handler(Looper.getMainLooper()).postDelayed({
//            pedirConfirmacionNavegacion(context, name, lat, lng)
//        }, 1500)
//    }

    private fun pedirConfirmacionNavegacion(
        context: Context,
        name: String,
        lat: Double,
        lng: Double
    ) {
        // Guardar datos temporalmente
        pendingNavName = name
        pendingNavLat = lat
        pendingNavLng = lng

        // Registrar callback (reutilizamos el mismo onConfirmacionPendiente, pero con una bandera)
        // Para no interferir con el de mensajes, podemos usar un callback exclusivo para navegación.
        // Si prefieres mantener un solo callback, añade un flag "tipoConfirmacion", pero por simplicidad
        // vamos a crear un callback separado para navegación.
        onConfirmacionPendiente = null  // limpia cualquier callback anterior

        onConfirmacionPendiente = { confirmado ->
            if (confirmado) {
                // Iniciar navegación por voz
                val navUri = "google.navigation:q=${pendingNavLat},${pendingNavLng}&mode=d"
                val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse(navUri)).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(navIntent)

                val doneIntent = Intent("JARVIS.NAV_STARTED").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(doneIntent)
            } else {
                // Se queda en el mapa, no hace nada
                val cancelIntent = Intent("JARVIS.NAV_CANCELLED").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(cancelIntent)
            }
            pendingNavName = ""; pendingNavLat = 0.0; pendingNavLng = 0.0
        }

        // Enviar broadcast para que el sistema de voz pregunte
        val confirmIntent = Intent("JARVIS.PEDIR_CONFIRMACION").apply {
            putExtra("pregunta", "¿Inicio viaje a $name?")
            putExtra("tipo", "navigation")
            setPackage(context.packageName)
        }
        context.sendBroadcast(confirmIntent)
        Log.d("JARVIS_ACTION", "❓ Confirmación de navegación solicitada")
    }
    // ─────────────────────────────────────────────────────────────────────────
    // WHATSAPP - VERSIÓN MEJORADA CON CONFIRMACIÓN DE VOZ
    // ─────────────────────────────────────────────────────────────────────────
    // ActionExecutor.kt
    // ActionExecutor.kt - MEJORAR sendWhatsAppMessage
    fun sendWhatsAppMessage(context: Context, contactName: String, message: String) {
        Log.d("JARVIS_ACTION", "📱 sendWhatsAppMessage: contacto='$contactName', mensaje='$message'")

        // 1. Buscar contacto
        var contact = ContactsManager.findContact(context, contactName)
        if (contact == null) {
            val allContacts = ContactsManager.getAllContacts(context)
            contact = allContacts.firstOrNull { it.name.lowercase().contains(contactName.lowercase()) }
            if (contact == null) {
                val cleanedName = contactName.replace(Regex("^mi\\s+", RegexOption.IGNORE_CASE), "")
                if (cleanedName != contactName) {
                    contact = allContacts.firstOrNull { it.name.lowercase().contains(cleanedName.lowercase()) }
                }
            }
        }

        if (contact == null) {
            Log.w("JARVIS_ACTION", "⚠️ Contacto '$contactName' no encontrado. Abriendo WhatsApp.")
            openApp(context, "com.whatsapp")
            return
        }

        // Formatear número
        var numero = contact.phoneNumber.replace(Regex("[^0-9]"), "")
        if (numero.startsWith("0")) numero = "593" + numero.substring(1)
        if (numero.length == 10) numero = "593" + numero.substring(1)

        pendingWhatsappContact = contact.name
        pendingWhatsappMessage = message

        // ✅ NUEVO: Usar el método directo de WhatsApp con Intent
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://wa.me/$numero?text=${Uri.encode(message)}")
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(intent)
            Log.d("JARVIS_ACTION", "✅ Intent enviado para chat de: $numero")

            // ✅ Esperar a que WhatsApp cargue y escribir el mensaje con Accessibility
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("JARVIS_ACTION", "✏️ Escribiendo mensaje con Accessibility...")

                // Enviar broadcast para escribir el mensaje y presionar enviar
                val writeIntent = Intent("JARVIS.WRITE_MESSAGE_AND_SEND").apply {
                    putExtra("mensaje", message)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(writeIntent)

                // También el método antiguo como fallback
                val sendIntent = Intent("JARVIS.SEND_CURRENT_MESSAGE").apply {
                    setPackage(context.packageName)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    context.sendBroadcast(sendIntent)
                }, 3000)

            }, 2500)

        } catch (e: Exception) {
            Log.e("JARVIS_ACTION", "❌ Error al abrir chat: ${e.message}")
            openApp(context, "com.whatsapp")
        }
    }
    // ActionExecutor.kt - Agregar esta función
// ActionExecutor.kt

    /**
     * Abre la app de Uber con origen y destino precargados.
     * Si el deep link falla, abre la versión web.
     */
    fun openUber(
        context: Context,
        pickupLat: Double,
        pickupLng: Double,
        dropoffLat: Double,
        dropoffLng: Double,
        dropoffName: String,
        deeplink: String = ""
    ) {
        Log.d("ActionExecutor", "🚗 Abriendo Uber: $dropoffName ($dropoffLat, $dropoffLng)")

        try {
            // 1. Intentar con el deep link recibido del servidor
            if (deeplink.isNotBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deeplink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d("ActionExecutor", "✅ Uber abierto con deep link")
                return
            }

            // 2. Si no vino deep link, construirlo localmente con el client_id
            // (debes tener tu client_id de Uber Developer)
            val clientId = "-bgm1W1aZztFFGNDFPnAcvVNhLlRxHNQ" // ⚠️ Reemplazar con el real
            val params = mapOf(
                "client_id" to clientId,
                "action" to "setPickup",
                "pickup[latitude]" to pickupLat.toString(),
                "pickup[longitude]" to pickupLng.toString(),
                "dropoff[latitude]" to dropoffLat.toString(),
                "dropoff[longitude]" to dropoffLng.toString(),
                "dropoff[nickname]" to dropoffName
            )
            val query = params.map { "${it.key}=${Uri.encode(it.value)}" }.joinToString("&")
            val fallbackDeeplink = "uber://?$query"

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackDeeplink)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d("ActionExecutor", "✅ Uber abierto con fallback deep link")

        } catch (e: Exception) {
            // 3. Si no está instalada la app, abrir versión web
            Log.e("ActionExecutor", "❌ Error abriendo Uber: ${e.message}")
            try {
                val webUrl = "https://m.uber.com/ul/?client_id=${Uri.encode("TU_CLIENT_ID_DE_UBER")}" +
                        "&action=setPickup&pickup[latitude]=$pickupLat&pickup[longitude]=$pickupLng" +
                        "&dropoff[latitude]=$dropoffLat&dropoff[longitude]=$dropoffLng&dropoff[nickname]=${Uri.encode(dropoffName)}"
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                Log.d("ActionExecutor", "✅ Uber abierto en navegador (fallback)")
            } catch (e2: Exception) {
                Log.e("ActionExecutor", "❌ Fallback web también falló: ${e2.message}")
            }
        }
    }
    fun callWhatsApp(context: Context, contactName: String) {
        Log.d("JARVIS_ACTION", "📞 callWhatsApp: contacto='$contactName'")

        // ─── 1. Buscar el contacto localmente ───────────────────────────────────
        var contact = ContactsManager.findContact(context, contactName)
        if (contact == null) {
            val allContacts = ContactsManager.getAllContacts(context)
            contact = allContacts.firstOrNull { it.name.lowercase().contains(contactName.lowercase()) }
            if (contact == null) {
                val cleanedName = contactName.replace(Regex("^mi\\s+", RegexOption.IGNORE_CASE), "")
                if (cleanedName != contactName) {
                    contact = allContacts.firstOrNull { it.name.lowercase().contains(cleanedName.lowercase()) }
                }
            }
        }

        if (contact == null) {
            Log.w("JARVIS_ACTION", "⚠️ Contacto '$contactName' no encontrado")
            speakText(context, "No encontré el contacto $contactName")
            return
        }

        // ─── 2. Limpiar el número para buscar en la base de datos de Android ────
        val numeroLimpio = contact.phoneNumber.replace(Regex("[^0-9]"), "")
        if (numeroLimpio.isEmpty()) {
            Log.w("JARVIS_ACTION", "⚠️ El contacto no tiene un número telefónico válido.")
            speakText(context, "${contact.name} no tiene un número válido.")
            return
        }

        // Tip: Extraemos los últimos 9 dígitos para asegurar compatibilidad en Ecuador
        // (así coincide si está guardado con o sin el +593)
        val subNumero = if (numeroLimpio.length >= 9) numeroLimpio.substring(numeroLimpio.length - 9) else numeroLimpio
        Log.d("JARVIS_ACTION", "📱 Buscando registro de llamada WhatsApp para: ...$subNumero")

        // ─── 3. MÉTODO DEFINITIVO: Buscar el Row ID de WhatsApp VoIP ───────────
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} LIKE ?",
                arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "%$subNumero%"),
                null
            )

            if (cursor != null && cursor.moveToFirst()) {
                val dataId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Data._ID))
                cursor.close()

                // Crear el Intent nativo que WhatsApp sí reconoce
                val whatsappCallIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse("content://com.android.contacts/data/$dataId"),
                        "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
                    )
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(whatsappCallIntent)
                Log.d("JARVIS_ACTION", "✅ Llamada directa de WhatsApp iniciada con éxito.")
                speakText(context, "Llamando a ${contact.name} por WhatsApp")
                return
            }
            cursor?.close()
            Log.w("JARVIS_ACTION", "⚠️ No se encontró una cuenta de WhatsApp vinculada para este número en Contactos.")
        } catch (e: Exception) {
            Log.e("JARVIS_ACTION", "❌ Error al interactuar con el ContentResolver: ${e.message}")
        }

        // ─── 4. FALLBACK FINAL: Si el método nativo falla, abrir el chat ───────
        try {
            val chatIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$numeroLimpio")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chatIntent)
            Log.d("JARVIS_ACTION", "✅ Chat de WhatsApp abierto como alternativa.")
            speakText(context, "No pude iniciar la llamada directa. Te abro el chat de ${contact.name}")
        } catch (e: Exception) {
            Log.e("JARVIS_ACTION", "❌ Error crítico en el fallback: ${e.message}")
            openApp(context, "com.whatsapp")
        }
    }
    // ─────────────────────────────────────────────────────────────────────────
// SPEAK TEXT (envía un mensaje de voz al controlador)
// ─────────────────────────────────────────────────────────────────────────
    private fun speakText(context: Context, texto: String) {
        val intent = Intent("JARVIS.SPEAK_TEXT").apply {
            putExtra("texto", texto)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
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
    fun controlYoutube(context: Context, comando: String, valor: Int = 0) {
        Log.d("JARVIS_ACTION", "🎬 Control YouTube: $comando ($valor)")

        when (comando) {
            "pausar" -> YoutubeController.pausar(context)
            "play" -> YoutubeController.reproducir(context)
            "siguiente" -> YoutubeController.siguienteCancion(context)
            "anterior" -> YoutubeController.anteriorCancion(context)
            "adelantar" -> YoutubeController.saltarTiempo(context, valor)
            "retroceder" -> YoutubeController.saltarTiempo(context, -valor)
            "pantalla_completa" -> togglePantallaCompletaYouTube(context)
            "salir_pantalla_completa" -> salirPantallaCompletaYouTube(context)

        }
    }

    private fun togglePantallaCompletaYouTube(context: Context) {
        val acciones = listOf(
            ActionDto(
                tipo = "tap_by_id",
                params = mapOf("id" to "com.google.android.youtube:id/fullscreen_button")
            )
        )
        _ejecutarAccion(context, acciones, "pantalla_completa")
    }

    fun salirPantallaCompletaYouTube(context: Context) {
        val acciones = listOf(
            ActionDto(
                tipo = "tap_send_button",
                params = mapOf(
                    "candidatos" to "Salir de pantalla completa,Exit fullscreen,Minimize,Minimizar",
                    "fallback" to "back"
                )
            )
        )
        _ejecutarAccion(context, acciones, "salir_pantalla_completa")
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

    // ActionExecutor.kt - Agregar esta función
    fun openAppsInSplitScreen(context: Context, packageNames: List<String>) {
        if (packageNames.size < 2) {
            Log.e("ActionExecutor", "❌ Se requieren 2 apps, recibidas: ${packageNames.size}")
            return
        }

        val pkg1 = packageNames[0]
        val pkg2 = packageNames[1]

        Log.d("ActionExecutor", "🚀 Solicitando split screen: $pkg1 + $pkg2")

        // ✅ Verificar que las apps existen
        val pm = context.packageManager
        val intent1 = pm.getLaunchIntentForPackage(pkg1)
        val intent2 = pm.getLaunchIntentForPackage(pkg2)

        if (intent1 == null) {
            Log.e("ActionExecutor", "❌ App no instalada: $pkg1")
        }
        if (intent2 == null) {
            Log.e("ActionExecutor", "❌ App no instalada: $pkg2")
        }

        if (intent1 == null || intent2 == null) {
            // Intentar abrir las que existan
            if (intent1 != null) {
                intent1.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent1)
            }
            if (intent2 != null) {
                intent2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent2)
            }
            return
        }

        // Usar el mismo mecanismo de acciones en lugar de broadcast directo
        val acciones = listOf(
            ActionDto(
                tipo = "open_apps_in_split_screen",
                params = mapOf("apps" to listOf(pkg1, pkg2))
            )
        )
        _ejecutarAccion(context, acciones, "split_screen")
    }
    private fun abrirAppsSecuencialmente(context: Context, packageNames: List<String>) {
        try {
            val pm = context.packageManager
            for (pkg in packageNames) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.d("ActionExecutor", "✅ Abriendo app: $pkg")
                    Thread.sleep(500)
                }
            }
            Log.d("ActionExecutor", "⚠️ Fallback: apps abiertas secuencialmente")
        } catch (e: Exception) {
            Log.e("ActionExecutor", "❌ Fallback falló: ${e.message}")
        }
    }

    // ActionExecutor.kt - Agregar esta función
    fun getPackageNameFromAppName(appName: String, context: Context): String? {
        val packageManager = context.packageManager
        val appNameLower = appName.lowercase().trim()

        // 🔥 Mapa de nombres comunes a paquetes (más completo)
        val appMap = mapOf(
            "facebook" to "com.facebook.katana",
            "face" to "com.facebook.katana",
            "fb" to "com.facebook.katana",
            "whatsapp" to "com.whatsapp",
            "wa" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "ig" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "netflix" to "com.netflix.mediaclient",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "play store" to "com.android.vending",
            "google" to "com.google.android.googlequicksearchbox"
        )

        // 1. Buscar en el mapa (búsqueda exacta o parcial)
        for ((key, pkg) in appMap) {
            if (appNameLower == key || appNameLower.contains(key) || key.contains(appNameLower)) {
                Log.d("ActionExecutor", "✅ Mapa: '$appName' → $pkg")
                return pkg
            }
        }

        // 2. Buscar en apps instaladas
        try {
            val installedApps = packageManager.getInstalledApplications(0)
            for (app in installedApps) {
                val appLabel = packageManager.getApplicationLabel(app).toString().lowercase()
                if (appLabel.contains(appNameLower) || appNameLower.contains(appLabel)) {
                    Log.d("ActionExecutor", "✅ Instalada: '$appName' → ${app.packageName}")
                    return app.packageName
                }
            }
        } catch (e: Exception) {
            Log.e("ActionExecutor", "Error buscando apps: ${e.message}")
        }

        Log.w("ActionExecutor", "❌ No se encontró app: '$appName'")
        return null
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
     * @param appPackage Paquete de la app (por defecto com.spotify.music)
     */
    fun playMusic(context: Context, query: String, packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://open.spotify.com/search/$query")
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // fallback: abrir con navegador
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$query"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
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
    fun navigateTo(context: Context, lat: Double, lng: Double, name: String) {
        val uri = "google.navigation:q=${lat},${lng}&mode=d"
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(navIntent)
            Log.d("ActionExecutor", "🚗 Navegación iniciada hacia $name ($lat, $lng)")
        } catch (e: Exception) {
            // Fallback: abrir con URL web
            val webUri = "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving&dir_action=navigate"
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
    fun playVideo(context: Context, query: String) {
        val cachedId = YouTubeCache.get(query)
        if (cachedId != null) {
            reproducirPorId(context, cachedId)
            return
        }
        Log.d("ActionExecutor", "🎬 playVideo: query='$query'")
        if (query.isBlank()) {
            openApp(context, "com.google.android.youtube")
            return
        }

        // Buscar en background y reproducir directamente por ID
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val videoId = buscarVideoIdEnYouTube(query)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (videoId != null) {
                        Log.d("ActionExecutor", "✅ Video ID encontrado: $videoId")
                        reproducirPorId(context, videoId)
                    } else {
                        Log.w("ActionExecutor", "⚠️ No se encontró ID, usando fallback")
                        reproducirFallback(context, query)
                    }
                }
            }
        }
    }

    private suspend fun buscarVideoIdEnYouTube(query: String): String? {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val url = "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet" +
                    "&q=${android.net.Uri.encode(query)}" +
                    "&type=video" +
                    "&maxResults=1" +
                    "&key=$YOUTUBE_API_KEY"

            val request = okhttp3.Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            Log.d("ActionExecutor", "📡 Código de respuesta YouTube: $responseCode")

            if (!response.isSuccessful) {
                Log.e("ActionExecutor", "❌ YouTube API error: ${response.code}")
                return null
            }

            if (responseBody.isNullOrBlank()) {
                Log.e("ActionExecutor", "❌ El cuerpo de la respuesta está vacío")
                return null
            }
            // IMPRIMIR EL JSON COMPLETO PARA ANÁLISIS
            Log.d("ActionExecutor", "📦 JSON RECIBIDO DE YOUTUBE: $responseBody")

            val json = org.json.JSONObject(responseBody)
            val items = json.optJSONArray("items")
            if (items != null && items.length() > 0) {
                val videoId = items.getJSONObject(0).getJSONObject("id").getString("videoId")
                Log.d("ActionExecutor", "🎯 Video ID: $videoId para query: '$query'")
                videoId
            } else {
                Log.w("ActionExecutor", "⚠️ Sin resultados para: '$query'")
                null
            }
        } catch (e: Exception) {
            Log.e("ActionExecutor", "❌ Error YouTube API: ${e.message}")
            null
        }
    }

    private fun reproducirPorId(context: Context, videoId: String) {
        // vnd.youtube: hace que YouTube reproduzca directamente sin mostrar lista
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$videoId")).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(intent)
            Log.d("ActionExecutor", "✅ Reproduciendo video: $videoId")
        } catch (e: Exception) {
            // Si falla vnd.youtube, intentar con https
            val fallbackIntent = Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId")
            ).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(fallbackIntent)
        }
    }

    private fun reproducirFallback(context: Context, query: String) {
        val intent = Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("https://www.youtube.com/results?search_query=${android.net.Uri.encode(query)}")
        ).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
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