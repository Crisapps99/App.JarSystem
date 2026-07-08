package com.example.myapplication.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.activity.ActionExecutor
import com.example.myapplication.api.ActionDto
import com.example.myapplication.api.ReporteFeedback
import com.example.myapplication.api.RetrofitClient
import com.example.myapplication.core.memory.NotificationMemory
import com.example.myapplication.model.ScreenElement
import com.example.myapplication.model.ScreenSnapshot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import com.example.myapplication.service.JarvisNotificationListener
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.example.myapplication.util.PermissionHelper
import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Handler
import java.io.File

class MyAccessibilityService : AccessibilityService() {

    private val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var textoUltimaOrden: String = ""
    private var intencionUltimaOrden: String = "desconocida"

    private var lastSnapshot: ScreenSnapshot? = null
    private var lastSnapshotTime: Long = 0
    private val CACHE_DURATION = 2000L
    private var snapshotJob: Job? = null
    private var lastFingerprint: String = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ultimoTextoEscrito: String = ""


    companion object {
        var instance: MyAccessibilityService? = null
        private const val TAG = "JARVIS_ACCESSIBILITY"
    }
    // Este receiver ya lo tienes en tu código (verifícalo)
    private val writeMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "JARVIS.WRITE_MESSAGE_AND_SEND") {
                val mensaje = intent.getStringExtra("mensaje") ?: ""
                Log.d(TAG, " Escribiendo mensaje: '$mensaje'")
                escribirMensajeYEnviar(mensaje)
            }
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        // Dentro de onServiceConnected
        val filterSend = IntentFilter().apply {
            addAction("JARVIS.SEND_CURRENT_MESSAGE")
            addAction("JARVIS.START_SPLIT_SCREEN")
        }
        registerReceiver(sendMessageReceiver, filterSend, RECEIVER_NOT_EXPORTED)
        instance = this
        instance = this
        val filterSplit = IntentFilter("JARVIS.TRIGGER_SPLIT_SCREEN")
        registerReceiver(splitScreenReceiver, filterSplit, RECEIVER_NOT_EXPORTED)

// También registra para "JARVIS.START_SPLIT_SCREEN" por si ActionExecutor lo envía
        val filterStart = IntentFilter("JARVIS.START_SPLIT_SCREEN")
        registerReceiver(splitScreenReceiver, filterStart, RECEIVER_NOT_EXPORTED)
        // FIX CRÍTICO: Registra el BroadcastReceiver aquí
        try {
            val filter = IntentFilter(ACTION_EXECUTE).apply{
                addAction("JARVIS.YOUTUBE_AUTOCLICK")
            }
            registerReceiver(actionsReceiver, filter, RECEIVER_NOT_EXPORTED)
            Log.i(TAG, " BroadcastReceiver registrado para: $ACTION_EXECUTE")
        } catch (e: Exception) {
            Log.e(TAG, " Error registrando BroadcastReceiver: ${e.message}", e)
        }
        verificarPermisosCamara()
        val filterWrite = IntentFilter("JARVIS.WRITE_MESSAGE_AND_SEND")
        registerReceiver(writeMessageReceiver, filterWrite, RECEIVER_NOT_EXPORTED)
        Log.d(TAG, " AccessibilityService conectado y listo")

        val filterCall = IntentFilter("JARVIS.PRESS_CALL_BUTTON")
        registerReceiver(callButtonReceiver, filterCall, RECEIVER_NOT_EXPORTED)
    }
    // En MyAccessibilityService.kt - Añadir este método
    fun tapEnNodoPorCoordenadas(x: Float, y: Float): Boolean {
        Log.d(TAG, " tapEnNodoPorCoordenadas: ($x, $y)")

        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, " No hay ventana activa")
            return false
        }

        // Buscar nodo en esas coordenadas
        fun encontrarNodoEnCoordenadas(nodo: AccessibilityNodeInfo?, x: Float, y: Float): AccessibilityNodeInfo? {
            if (nodo == null) return null

            val bounds = Rect()
            nodo.getBoundsInScreen(bounds)

            // Si el nodo contiene las coordenadas y es clickable
            if (bounds.contains(x.toInt(), y.toInt()) && nodo.isClickable && nodo.isEnabled) {
                return nodo
            }

            // Buscar en hijos
            for (i in 0 until nodo.childCount) {
                val resultado = encontrarNodoEnCoordenadas(nodo.getChild(i), x, y)
                if (resultado != null) return resultado
            }

            return null
        }

        val nodo = encontrarNodoEnCoordenadas(root, x, y)
        if (nodo != null) {
            Log.d(TAG, " Nodo encontrado: ${nodo.className}, clickable=${nodo.isClickable}, enabled=${nodo.isEnabled}")

            // Buscar ancestro clickable
            var nodoClick = nodo
            while (nodoClick != null && !nodoClick.isClickable) {
                nodoClick = nodoClick.parent
            }

            if (nodoClick != null && nodoClick.isClickable && nodoClick.isEnabled) {
                // ✅ Usar ACTION_CLICK
                val exito = nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, " ACTION_CLICK en nodo: $exito")

                // Si falla, intentar con ACTION_TAP
                if (!exito) {
                    val bounds = Rect()
                    nodoClick.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        tapCoordenadas(mapOf("x" to bounds.centerX().toFloat(), "y" to bounds.centerY().toFloat()))
                        Log.d(TAG, " Fallback: tap en coordenadas del nodo")
                        return true
                    }
                }
                return exito
            } else {
                // Fallback: tap en coordenadas del nodo
                val bounds = Rect()
                nodo.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    tapCoordenadas(mapOf("x" to bounds.centerX().toFloat(), "y" to bounds.centerY().toFloat()))
                    Log.d(TAG, " Fallback: tap en coordenadas del nodo (no clickable)")
                    return true
                }
            }
        } else {
            Log.w(TAG, " No se encontró nodo en coordenadas ($x, $y)")
        }
        return false
    }
    private fun presionarBotonLlamadaWhatsApp() {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed

            // Candidatos de texto/descripción para llamada DE VOZ (excluyendo video)
            val candidatosVoz = listOf(
                "Llamada de voz", "Voice call", "Audio call", "Llamar por voz"
            )

            var botonEncontrado: AccessibilityNodeInfo? = null

            // 1. Buscar candidatos específicos de voz primero
            for (candidato in candidatosVoz) {
                botonEncontrado = encontrarNodoPorTexto(root, candidato)
                if (botonEncontrado != null) {
                    Log.d(TAG, " Botón llamada de voz encontrado por texto: '$candidato'")
                    break
                }
            }

            // 2. Si no hay match específico, buscar por IDs de WhatsApp (voz, no video)
            if (botonEncontrado == null) {
                val ids = listOf(
                    "com.whatsapp:id/call_button",
                    "com.whatsapp:id/voice_call",
                    "com.whatsapp:id/action_call",
                    "com.whatsapp:id/menuitem_voice_call"
                )
                for (id in ids) {
                    val nodos = root.findAccessibilityNodeInfosByViewId(id)
                    if (nodos.isNotEmpty()) {
                        botonEncontrado = nodos[0]
                        Log.d(TAG, " Botón llamada encontrado por ID: $id")
                        break
                    }
                }
            }

            // 3. Último recurso: buscar "llamada"/"llamar"/"call" EXCLUYENDO nodos con "video"
            if (botonEncontrado == null) {
                fun buscarVozExcluyendoVideo(nodo: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (nodo == null) return null
                    val texto = (nodo.text?.toString() ?: nodo.contentDescription?.toString() ?: "").lowercase()
                    val esCandidatoVoz = (texto.contains("llamada") || texto.contains("llamar") || texto == "call") &&
                            !texto.contains("video")
                    if (esCandidatoVoz && (nodo.isClickable || nodo.parent?.isClickable == true)) {
                        return nodo
                    }
                    for (i in 0 until nodo.childCount) {
                        val resultado = buscarVozExcluyendoVideo(nodo.getChild(i))
                        if (resultado != null) return resultado
                    }
                    return null
                }
                botonEncontrado = buscarVozExcluyendoVideo(root)
                if (botonEncontrado != null) {
                    Log.d(TAG, " Botón llamada de voz encontrado (excluyendo video)")
                }
            }

            if (botonEncontrado != null) {
                hacerClicEnNodoOAncestros(botonEncontrado)
                Log.d(TAG, " Botón de llamada de voz presionado")
            } else {
                Log.w(TAG, " No se encontró botón de llamada de voz")
            }
        }, 1500) // Esperar a que cargue el chat
    }
    private val sendMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "JARVIS.SEND_CURRENT_MESSAGE") {
                Log.d(TAG, " Recibido SEND_CURRENT_MESSAGE, presionando botón enviar")
                presionarBotonEnviar()
            }
        }
    }
    // MyAccessibilityService.kt - NUEVA FUNCIÓN
    private fun escribirMensajeYEnviar(mensaje: String) {
        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                Log.e(TAG, " Sin ventana activa")
                return@postDelayed
            }

            // 1. Buscar campo de texto (WhatsApp)
            var campoTexto: AccessibilityNodeInfo? = null

            // Buscar por IDs de WhatsApp
            val idsCampo = listOf(
                "com.whatsapp:id/entry",
                "com.whatsapp:id/entry_input",
                "com.whatsapp:id/message_entry",
                "com.whatsapp:id/conversation_entry"
            )
            for (id in idsCampo) {
                val nodos = root.findAccessibilityNodeInfosByViewId(id)
                if (nodos.isNotEmpty()) {
                    campoTexto = nodos[0]
                    Log.d(TAG, " Campo encontrado por ID: $id")
                    break
                }
            }

            // Si no por ID, buscar por clase EditText
            if (campoTexto == null) {
                fun buscarEditText(nodo: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (nodo == null) return null
                    val className = nodo.className?.toString() ?: ""
                    if (className.contains("EditText") && nodo.isEditable) {
                        return nodo
                    }
                    for (i in 0 until nodo.childCount) {
                        val resultado = buscarEditText(nodo.getChild(i))
                        if (resultado != null) return resultado
                    }
                    return null
                }
                campoTexto = buscarEditText(root)
            }

            if (campoTexto != null) {
                // 2. Dar foco al campo
                campoTexto.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // 3. Escribir el mensaje
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, mensaje)
                campoTexto.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, " Mensaje escrito: '$mensaje'")

                // 4. Presionar el botón "Enviar"
                handler.postDelayed({
                    presionarBotonEnviar()
                }, 500)

            } else {
                Log.e(TAG, " No se encontró campo de texto")
            }
        }, 1500) // Esperar a que cargue el chat
    }
    private fun seleccionarModoPantallaDividida(
        paquete2: String
    ) {

        val root = rootInActiveWindow

        if (root == null) {
            Log.e(TAG, " rootInActiveWindow NULL")
            return
        }

        val textos = listOf(
            "Pantalla dividida",
            "Split screen",
            "Abrir en vista de pantalla dividida",
            "Open in split screen view"
        )

        var encontrado = false

        for (texto in textos) {

            val nodos = root.findAccessibilityNodeInfosByText(texto)

            if (nodos.isNotEmpty()) {

                var nodo = nodos[0]

                while (nodo.parent != null && !nodo.isClickable) {
                    nodo = nodo.parent
                }

                nodo.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )

                encontrado = true

                Log.d(TAG, " Botón Split Screen encontrado")

                break
            }
        }

        if (!encontrado) {
            Log.e(TAG, " No se encontró botón Split Screen")
            return
        }

        Handler(mainLooper).postDelayed({

            abrirSegundaAppSplit(paquete2)

        }, 2000)
    }
    private fun abrirSegundaAppSplit(
        paquete2: String
    ) {

        try {

            Log.d(TAG, " Abriendo segunda app")

            val intent = packageManager
                .getLaunchIntentForPackage(paquete2)

            if (intent == null) {
                Log.e(TAG, " No existe app: $paquete2")
                return
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            startActivity(intent)

            Log.d(TAG, " Split Screen completado")

        } catch (e: Exception) {

            Log.e(
                TAG,
                " Error abriendo segunda app: ${e.message}"
            )
        }
    }
    // MyAccessibilityService.kt - MEJORAR presionarBotonEnviar()
    private fun presionarBotonEnviar() {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed

            // Candidatos de texto para el botón
            val candidatos = listOf("Enviar", "Send", "send", "Enviar mensaje", "", "→", "▶", "")

            // IDs de WhatsApp
            val ids = listOf(
                "com.whatsapp:id/send",
                "com.whatsapp:id/conversation_send_button",
                "com.whatsapp:id/entry_send"
            )

            var nodoEncontrado: AccessibilityNodeInfo? = null

            // 1. Buscar por texto
            for (candidato in candidatos) {
                nodoEncontrado = encontrarNodoPorTexto(root, candidato)
                if (nodoEncontrado != null) {
                    Log.d(TAG, " Botón por texto: '$candidato'")
                    break
                }
            }

            // 2. Buscar por ID
            if (nodoEncontrado == null) {
                for (id in ids) {
                    val nodos = root.findAccessibilityNodeInfosByViewId(id)
                    if (nodos.isNotEmpty()) {
                        nodoEncontrado = nodos[0]
                        Log.d(TAG, " Botón por ID: $id")
                        break
                    }
                }
            }

            // 3. Buscar por descripción
            if (nodoEncontrado == null) {
                fun buscarPorDesc(nodo: AccessibilityNodeInfo?): Boolean {
                    if (nodo == null) return false
                    val desc = nodo.contentDescription?.toString()?.lowercase() ?: ""
                    if (desc.contains("enviar") || desc.contains("send") ||
                        desc.contains("enviar mensaje")) {
                        if (nodo.isClickable) {
                            nodoEncontrado = nodo
                            Log.d(TAG, " Botón por descripción: '$desc'")
                            return true
                        }
                    }
                    for (i in 0 until nodo.childCount) {
                        if (buscarPorDesc(nodo.getChild(i))) return true
                    }
                    return false
                }
                buscarPorDesc(root)
            }

            // 4. Si encontramos el botón, hacer clic
            if (nodoEncontrado != null) {
                var nodoClick = nodoEncontrado
                while (nodoClick != null && !nodoClick.isClickable) {
                    nodoClick = nodoClick.parent
                }
                if (nodoClick != null && nodoClick.isClickable) {
                    nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, " Botón enviar presionado")
                } else {
                    // Fallback: tap en coordenadas
                    val bounds = Rect()
                    nodoEncontrado.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
                        Log.d(TAG, " Tap en coordenadas del botón")
                    }
                }
            } else {
                // 5. Último recurso: tap en zona inferior derecha
                Log.w(TAG, " No se encontró botón enviar, tap en zona inferior derecha")
                val metrics = resources.displayMetrics
                val x = metrics.widthPixels * 0.85f
                val y = metrics.heightPixels * 0.9f
                tapCoordenadas(mapOf("x" to x, "y" to y))

                // También intentar con ENTER (teclado)
                performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, " BACK presionado (fallback)")
            }
        }, 500) // Esperar a que el mensaje esté escrito
    }
    private val actionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "╔═══════════════════════════════════════════════════════════")
            Log.d(TAG, "║  BROADCAST RECIBIDO")
            Log.d(TAG, "╠═══════════════════════════════════════════════════════════")
            Log.d(TAG, "║ Action: ${intent?.action}")

            if (intent?.action != ACTION_EXECUTE) {
                Log.w(TAG, "║  Action no coincide. Esperado: $ACTION_EXECUTE")
                Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
                return
            }

            val json = intent.getStringExtra("actions_json")
            Log.d(TAG, "║ JSON recibido: ${json?.take(200)}...")

            if (json == null) {
                Log.e(TAG, "║  JSON es NULL - No hay acciones para ejecutar")
                Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
                return
            }

            textoUltimaOrden = intent.getStringExtra("texto_original") ?: "orden sin texto"
            intencionUltimaOrden = intent.getStringExtra("intencion_original") ?: "desconocida"

            Log.d(TAG, "║ Texto original: '$textoUltimaOrden'")
            Log.d(TAG, "║ Intención: '$intencionUltimaOrden'")
            Log.d(TAG, "║ → Pasando a ejecutarAcciones()...")
            Log.d(TAG, "╚═══════════════════════════════════════════════════════════")

            handler.post { ejecutarAcciones(json) }
        }
    }

    fun captureNow() {
        serviceScope.launch(Dispatchers.Main) {
            var root = rootInActiveWindow
            var intentos = 0

            while (root == null && intentos < 5) {
                delay(200)
                root = rootInActiveWindow
                intentos++
            }

            if (root == null) {
                Log.e(TAG, " [FALLO CRÍTICO] No se puede obtener rootInActiveWindow tras $intentos reintentos.")
                return@launch
            }

            Log.d(TAG, " Captura manual iniciada sobre: ${root.packageName}")
            actualizarSnapDePantalla(force = true)

            com.example.myapplication.core.memory.ScreenMemory.lastSnapshot = lastSnapshot
            com.example.myapplication.core.memory.ScreenMemory.lastSeenTexts = lastSnapshot?.toContextList() ?: emptyList()
        }
    }
    private val callButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "JARVIS.PRESS_CALL_BUTTON") {
                Log.d(TAG, "📞 Intentando presionar botón de llamada en WhatsApp")
                presionarBotonLlamadaWhatsApp()
            }
        }
    }
    fun captureCurrentScreenNow(callback: ((ScreenSnapshot?) -> Unit)? = null) {
        serviceScope.launch(Dispatchers.Main) {
            // Esperar a que la ventana esté lista
            var root = rootInActiveWindow
            var intentos = 0

            // Reintentar hasta 5 veces con delays
            while (root == null && intentos < 8) {
                delay(2500)
                root = rootInActiveWindow
                intentos++
                Log.d(TAG, " Reintentando captura... intento $intentos")
            }

            if (root == null) {
                Log.e(TAG, " No se pudo obtener rootInActiveWindow después de $intentos intentos")
                callback?.invoke(null)
                return@launch
            }

            Log.d(TAG, " Captura manual iniciada sobre: ${root.packageName}")
            actualizarSnapDePantalla(force = true)
            // Esperar a que se complete el escaneo
            delay(500)

            // Asegurar que el snapshot se guarde en ScreenMemory
            if (lastSnapshot != null) {
                com.example.myapplication.core.memory.ScreenMemory.lastSnapshot = lastSnapshot
                com.example.myapplication.core.memory.ScreenMemory.lastSeenTexts = lastSnapshot?.toContextList() ?: emptyList()
                com.example.myapplication.core.memory.ScreenMemory.lastUpdateTimestamp = System.currentTimeMillis()
            }
            Log.d(TAG, " Captura completada: ${lastSnapshot?.elements?.size ?: 0} elementos")
            callback?.invoke(lastSnapshot)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val eventosClave = listOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        if (eventType in eventosClave) {
            snapshotJob?.cancel()
            snapshotJob = serviceScope.launch {
                delay(1000L)
                if (rootInActiveWindow != null) {
                    actualizarSnapDePantalla()
                    com.example.myapplication.core.memory.ScreenMemory.lastSnapshot = lastSnapshot
                    com.example.myapplication.core.memory.ScreenMemory.lastSeenTexts = lastSnapshot?.toContextList() ?: emptyList()
                    Log.d(TAG, " Snapshot actualizado")
                    handler.post { diagnosticarScreenMemory() }
                } else {
                    Log.w(TAG, " rootInActiveWindow es NULL")
                }
            }
        }
    }
    private fun intentarReproducirPrimerVideo(root: AccessibilityNodeInfo) {
        Log.d(TAG, " Buscando primer video para reproducir...")

        // Lista de IDs comunes de contenedores de video en YouTube
        val idsVideo = listOf(
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/video_info_view",
            "com.google.android.youtube:id/dismissible"
        )

        for (id in idsVideo) {
            val nodos = root.findAccessibilityNodeInfosByViewId(id)
            if (nodos.isNotEmpty()) {
                val primerVideo = nodos[0]
                if (hacerClicEnNodoOAncestros(primerVideo)) {
                    Log.d(TAG, " Reproducción iniciada mediante ID: $id")
                    return
                }
            }
        }

        // Fallback: buscar por descripción larga (típico de miniaturas de YouTube)
        fun buscarPorDescripcion(nodo: AccessibilityNodeInfo?): Boolean {
            if (nodo == null) return false

            // Los videos suelen tener descripciones de más de 40 caracteres (título + canal + vistas)
            val desc = nodo.contentDescription?.toString() ?: ""
            if (nodo.isClickable && desc.length > 40) {
                nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }

            for (i in 0 until nodo.childCount) {
                if (buscarPorDescripcion(nodo.getChild(i))) return true
            }
            return false
        }

        if (buscarPorDescripcion(root)) {
            Log.d(TAG, " Reproducción iniciada por descripción")
        } else {
            Log.w(TAG, " No se encontró un video clickable")
        }
    }

    // Función auxiliar para asegurar el click
    private fun hacerClicEnNodoOAncestros(nodo: AccessibilityNodeInfo?): Boolean {
        var temp = nodo
        while (temp != null) {
            if (temp.isClickable) {
                return temp.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            temp = temp.parent
        }
        return false
    }
    private fun diagnosticarScreenMemory() {
        val memoria = com.example.myapplication.core.memory.ScreenMemory.lastSnapshot

        Log.d("DIAGNOSIS", "════════════════════════════════════════")
        Log.d("DIAGNOSIS", " ESTADO DE SCREENMEMORY")
        Log.d("DIAGNOSIS", "════════════════════════════════════════")

        if (memoria == null) {
            Log.e("DIAGNOSIS", " ScreenMemory.lastSnapshot es NULL")
        } else {
            Log.d("DIAGNOSIS", " ScreenMemory.lastSnapshot existe")
            Log.d("DIAGNOSIS", "    App: ${memoria.packageName}")
            Log.d("DIAGNOSIS", "    Elementos totales: ${memoria.totalElements}")
            Log.d("DIAGNOSIS", "     Clickables: ${memoria.clickableElements}")
        }

        Log.d("DIAGNOSIS", "════════════════════════════════════════")
    }

    override fun onInterrupt() {}


    private fun ejecutarAcciones(json: String) {
        Log.d(TAG, "╔═══════════════════════════════════════════════════════════")
        Log.d(TAG, "║  EJECUTAR_ACCIONES - Inicio")
        Log.d(TAG, "╠═══════════════════════════════════════════════════════════")
        Log.d(TAG, "║ JSON recibido: ${json.take(300)}...")

        handler.removeCallbacks(stepRunnable)
        val listType = object : TypeToken<List<ActionDto>>() {}.type

        try {
            currentActions = Gson().fromJson(json, listType)
            Log.d(TAG, "║  JSON deserializado exitosamente")
            Log.d(TAG, "║ Acciones parseadas: ${currentActions?.size}")

            if (currentActions != null) {
                currentActions!!.forEachIndexed { idx, action ->
                    Log.d(TAG, "║   [$idx] tipo='${action.tipo}' params=${action.params}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "║  Error deserializando JSON: ${e.message}", e)
            Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
            return
        }

        currentIndex = 0
        retryCount = 0

        if (currentActions.isNullOrEmpty()) {
            Log.w(TAG, "║  currentActions es null o vacío — actualizando snapshot")
            Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
            actualizarSnapDePantalla()
        } else {
            Log.d(TAG, "║ ▶ Iniciando ejecución secuencial...")
            Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
            handler.post(stepRunnable)
        }
    }

    private var currentActions: List<ActionDto>? = null
    private var currentIndex = 0
    private var retryCount = 0
// En MyAccessibilityService.kt

    private fun iniciarPantallaDivididaUnificado(pkg1: String, pkg2: String) {
        Log.d(TAG, " Iniciando Split Screen unificado: $pkg1 + $pkg2")

        // 1. Lanzar la primera app y asegurar que esté en primer plano
        val intent1 = packageManager.getLaunchIntentForPackage(pkg1)
        if (intent1 == null) {
            Log.e(TAG, " App '$pkg1' no encontrada")
            return
        }
        intent1.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent1)

        // 2. Esperar a que la primera app se dibuje
        Handler(mainLooper).postDelayed({
            Log.d(TAG, " Activando split screen (GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)")
            val exito = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            if (!exito) {
                Log.e(TAG, " El sistema no aceptó el comando de split screen. ¿Accesibilidad activa?")
                return@postDelayed
            }

            // 3. Esperar a que el sistema prepare el espacio adyacente
            Handler(mainLooper).postDelayed({
                Log.d(TAG, " Lanzando segunda app: $pkg2")
                val intent2 = packageManager.getLaunchIntentForPackage(pkg2)
                if (intent2 == null) {
                    Log.e(TAG, " App '$pkg2' no encontrada")
                    return@postDelayed
                }
                // Flags críticos para que se acople en el espacio libre
                intent2.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                startActivity(intent2)
                Log.d(TAG, " Segunda app lanzada en el espacio dividido")
            }, 800) // Ajustable según dispositivo
        }, 1200) // Espera suficiente para que la primera app esté activa
    }
    private val stepRunnable = object : Runnable {
        override fun run() {
            val actions = currentActions ?: return
            if (currentIndex >= actions.size) {
                Log.d(TAG, " Todas las acciones completadas (${currentIndex}/${actions.size})")
                currentActions = null
                return
            }
            val accion = actions[currentIndex++]
            var waitTime = 1000L
            var exitoAccion = true
            var detalleError: String? = null

            Log.d(TAG, "┌─────────────────────────────────────────────────")
            Log.d(TAG, "│  Acción [${currentIndex}/${actions.size}]: ${accion.tipo}")
            Log.d(TAG, "│  Params: ${accion.params}")

            when (accion.tipo) {
                "open_app" -> {
                    val pkg = accion.params?.get("package") as? String ?: ""
                    Log.d(TAG, "│  open_app")
                    Log.d(TAG, "│    package='$pkg'")

                    if (pkg.isBlank()) {
                        Log.e(TAG, "│  Package está vacío!")
                        exitoAccion = false
                        detalleError = "Package vacío"
                    } else {
                        try {
                            Log.d(TAG, "│    → Llamando ActionExecutor.openApp()...")
                            ActionExecutor.openApp(this@MyAccessibilityService, pkg)
                            Log.d(TAG, "│     ActionExecutor.openApp() devolvió")
                            waitTime = 2500L
                        } catch (e: Exception) {
                            Log.e(TAG, "│  Exception en ActionExecutor.openApp(): ${e.message}", e)
                            exitoAccion = false
                            detalleError = "Exception: ${e.message}"
                        }
                    }

                    handler.postDelayed({
                        Log.d(TAG, "│ [POST-DELAY] Actualizando snapshot...")
                        lastFingerprint = ""
                        actualizarSnapDePantalla()
                    }, 2000L)
                }
                "youtube_control" -> {
                    val comando = accion.params?.get("comando") as? String ?: ""
                    val valor = (accion.params?.get("valor") as? Number)?.toInt() ?: 0
                    Log.d(TAG, "│  youtube_control: comando='$comando', valor=$valor")

                    when (comando) {
                        "pausar" -> ActionExecutor.controlYoutube(this@MyAccessibilityService, "pausar")
                        "play" -> ActionExecutor.controlYoutube(this@MyAccessibilityService, "play")
                        "siguiente" -> ActionExecutor.controlYoutube(this@MyAccessibilityService, "siguiente")
                        "anterior" -> ActionExecutor.controlYoutube(this@MyAccessibilityService, "anterior")
                        "adelantar" -> ActionExecutor.controlYoutube(this@MyAccessibilityService, "adelantar", valor)
                        "retroceder" -> ActionExecutor.controlYoutube(this@MyAccessibilityService, "retroceder", valor)
                        "pantalla_completa" -> ActionExecutor.controlYoutube(this@MyAccessibilityService, "pantalla_completa")
                        "salir_pantalla_completa" -> ActionExecutor.controlYoutube(this@MyAccessibilityService, "salir_pantalla_completa")
                        else -> Log.w(TAG, "  Comando YouTube desconocido: $comando")
                    }
                    waitTime = 500L
                    exitoAccion = true
                }
                "set_alarm" -> {
                    val hour = (accion.params?.get("hour") as? Number)?.toInt() ?: 7
                    val minute = (accion.params?.get("minute") as? Number)?.toInt() ?: 0
                    val label = accion.params?.get("label") as? String ?: ""

                    Log.d(TAG, "│  set_alarm: $hour:$minute '$label'")

                    ActionExecutor.setAlarm(this@MyAccessibilityService, hour, minute, label)
                    waitTime = 2000L
                }
                "open_apps_in_split_screen" -> {
                    val appsArray = accion.params?.get("apps") as? List<*>
                    if (appsArray != null && appsArray.size >= 2) {
                        val pkg1 = appsArray[0].toString()
                        val pkg2 = appsArray[1].toString()
                        // Usar el método unificado en lugar de iniciarPantallaDividida
                        iniciarPantallaDivididaUnificado(pkg1, pkg2)
                        waitTime = 1500L
                        exitoAccion = true
                    } else {
                        exitoAccion = false
                        detalleError = "Parámetros inválidos para split screen"
                    }
                }
                "android_intent" -> {
                    val intentAction = accion.action ?: accion.params?.get("action") as? String ?: "android.intent.action.VIEW"
                    val androidIntent = Intent(intentAction).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val dataUri = accion.data ?: accion.params?.get("data") as? String
                        if (!dataUri.isNullOrBlank()) data = Uri.parse(dataUri)
                        val pkg = accion.pkg ?: accion.params?.get("package") as? String
                        if (!pkg.isNullOrBlank()) setPackage(pkg)
                        val comp = accion.component ?: accion.params?.get("component") as? String
                        if (!comp.isNullOrBlank()) {
                            val parts = comp.split("/")
                            if (parts.size == 2) setClassName(parts[0], parts[1])
                        }
                        val mime = accion.mimeType ?: accion.params?.get("mime_type") as? String
                        if (!mime.isNullOrBlank()) type = mime
                        val extras = accion.extras ?: @Suppress("UNCHECKED_CAST") (accion.params?.get("extras") as? Map<String, Any>)
                        extras?.forEach { (key, value) ->
                            when (value) {
                                is String  -> putExtra(key, value)
                                is Int     -> putExtra(key, value)
                                is Boolean -> putExtra(key, value)
                                is Double  -> putExtra(key, value.toString())
                                else       -> putExtra(key, value.toString())
                            }
                        }
                    }
                    try {
                        Log.d(TAG, "│  android_intent: $intentAction")
                        this@MyAccessibilityService.startActivity(androidIntent)
                        Log.d(TAG, "│  Intent lanzado")
                        waitTime = 300L
                    } catch (e: Exception) {
                        Log.e(TAG, "│  Intent falló: ${e.message}")
                        val pkg = accion.pkg ?: accion.params?.get("package") as? String
                        if (!pkg.isNullOrBlank()) {
                            Log.d(TAG, "│    Intentando fallback: openApp($pkg)")
                            ActionExecutor.openApp(this@MyAccessibilityService, pkg)
                            waitTime = 500L
                        } else {
                            exitoAccion = false
                            detalleError = "Intent falló: ${e.message}"
                        }
                    }
                }

                "adjust_volume" -> {
                    val direction = accion.params?.get("direction") as? String ?: "up"
                    val percent = when (val raw = accion.params?.get("percent")) {
                        is Number -> raw.toInt().coerceIn(0, 100)
                        is String -> raw.toIntOrNull()?.coerceIn(0, 100)
                        else -> null
                    }
                    val steps = when (val raw = accion.params?.get("steps")) {
                        is Number -> raw.toInt().coerceIn(1, 15)
                        is String -> raw.toIntOrNull()?.coerceIn(1, 15) ?: 1
                        else -> 1
                    }
                    val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    val streamType = android.media.AudioManager.STREAM_MUSIC

                    if (percent != null) {
                        val maxVol = audioManager.getStreamMaxVolume(streamType)
                        val target = (maxVol * percent / 100.0).toInt()
                        audioManager.setStreamVolume(streamType, target, android.media.AudioManager.FLAG_SHOW_UI)
                        Log.d(TAG, "│  adjust_volume: percent=$percent% → nivel=$target/$maxVol")
                    } else {
                        val adjustFlag = if (direction == "up") android.media.AudioManager.ADJUST_RAISE
                        else android.media.AudioManager.ADJUST_LOWER
                        repeat(steps) { i ->
                            audioManager.adjustStreamVolume(streamType, adjustFlag,
                                if (i == 0) android.media.AudioManager.FLAG_SHOW_UI else 0)
                        }
                        Log.d(TAG, "│  adjust_volume: direction=$direction steps=$steps")
                    }
                    waitTime = 200L
                }

                "toggle_bluetooth" -> {
                    val shouldEnable = accion.params?.get("state") as? String ?: "toggle"
                    Log.d(TAG, "│  toggle_bluetooth: state='$shouldEnable'")

                    try {
                        //  FIX: Usar Context explícitamente
                        // Verificación de permiso para evitar SecurityException
                        if (ContextCompat.checkSelfPermission(this@MyAccessibilityService, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Log.e(TAG, " Falta permiso BLUETOOTH_CONNECT")
                            return@run
                        }
                        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()

                        if (bluetoothAdapter != null) {
                            val isEnabled = bluetoothAdapter.isEnabled
                            val respuesta: String

                            when (shouldEnable) {
                                "on" -> {
                                    if (!isEnabled) {
                                        bluetoothAdapter.enable()
                                        Log.d(TAG, "│  Bluetooth ENCENDIDO")
                                        respuesta = "Bluetooth encendido."
                                    } else {
                                        Log.d(TAG, "│  Bluetooth ya está encendido")
                                        respuesta = "Bluetooth ya estaba encendido."
                                    }
                                }
                                "off" -> {
                                    if (isEnabled) {
                                        bluetoothAdapter.disable()
                                        Log.d(TAG, "│  Bluetooth APAGADO")
                                        respuesta = "Bluetooth apagado."
                                    } else {
                                        Log.d(TAG, "│  Bluetooth ya está apagado")
                                        respuesta = "Bluetooth ya estaba apagado."
                                    }
                                }
                                "toggle" -> {
                                    if (isEnabled) {
                                        bluetoothAdapter.disable()
                                        Log.d(TAG, "│  Bluetooth APAGADO (toggle)")
                                        respuesta = "Bluetooth apagado."
                                    } else {
                                        bluetoothAdapter.enable()
                                        Log.d(TAG, "│  Bluetooth ENCENDIDO (toggle)")
                                        respuesta = "Bluetooth encendido."
                                    }
                                }
                                else -> {
                                    respuesta = "Estado Bluetooth desconocido."
                                }
                            }

                            // Notificar al usuario por voz
                            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", respuesta)
                                setPackage(packageName)
                            }


                            exitoAccion = true
                            waitTime = 500L

                        } else {
                            Log.e(TAG, "│  BluetoothAdapter no disponible")
                            exitoAccion = false
                            detalleError = "Bluetooth no disponible en este dispositivo"
                            waitTime = 300L
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error toggling Bluetooth: ${e.message}", e)
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "toggle_wifi" -> {
                    val shouldEnable = accion.params?.get("state") as? String ?: "toggle"
                    Log.d(TAG, "│  toggle_wifi: state='$shouldEnable'")

                    try {
                        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

                        val isEnabled = wifiManager.isWifiEnabled
                        val respuesta: String

                        when (shouldEnable) {
                            "on" -> {
                                if (!isEnabled) {
                                    @Suppress("DEPRECATION")
                                    wifiManager.isWifiEnabled = true
                                    Log.d(TAG, "│  WiFi ENCENDIDO")
                                    respuesta = "WiFi encendido."
                                } else {
                                    Log.d(TAG, "│  WiFi ya está encendido")
                                    respuesta = "WiFi ya estaba encendido."
                                }
                            }
                            "off" -> {
                                if (isEnabled) {
                                    @Suppress("DEPRECATION")
                                    wifiManager.isWifiEnabled = false
                                    Log.d(TAG, "│  WiFi APAGADO")
                                    respuesta = "WiFi apagado."
                                } else {
                                    Log.d(TAG, "│  WiFi ya está apagado")
                                    respuesta = "WiFi ya estaba apagado."
                                }
                            }
                            "toggle" -> {
                                @Suppress("DEPRECATION")
                                if (isEnabled) {
                                    wifiManager.isWifiEnabled = false
                                    Log.d(TAG, "│  WiFi APAGADO (toggle)")
                                    respuesta = "WiFi apagado."
                                } else {
                                    wifiManager.isWifiEnabled = true
                                    Log.d(TAG, "│  WiFi ENCENDIDO (toggle)")
                                    respuesta = "WiFi encendido."
                                }
                            }
                            else -> {
                                respuesta = "Estado WiFi desconocido."
                            }
                        }

                        val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", respuesta)
                            setPackage(packageName)
                        }
                        sendBroadcast(intent)

                        exitoAccion = true
                        waitTime = 500L

                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error toggling WiFi: ${e.message}", e)
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "toggle_flashlight" -> {
                    val shouldEnable = accion.params?.get("state") as? String ?: "on"
                    Log.d(TAG, "│  toggle_flashlight: state='$shouldEnable'")

                    try {
                        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val cameraId = cameraManager.cameraIdList[0]

                        val respuesta: String

                        when (shouldEnable) {
                            "on" -> {
                                cameraManager.setTorchMode(cameraId, true)
                                Log.d(TAG, "│  Linterna ENCENDIDA")
                                respuesta = "Linterna encendida."
                            }
                            "off" -> {
                                cameraManager.setTorchMode(cameraId, false)
                                Log.d(TAG, "│ Linterna APAGADA")
                                respuesta = "Linterna apagada."
                            }
                            else -> {
                                respuesta = "Estado linterna desconocido."
                            }
                        }

                        val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", respuesta)
                            setPackage(packageName)
                        }
                        sendBroadcast(intent)

                        exitoAccion = true
                        waitTime = 500L

                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error toggling flashlight: ${e.message}", e)
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "open_camera" -> {
                    val modo = accion.params?.get("mode") as? String ?: "photo"
                    val frontal = (accion.params?.get("frontal") as? Boolean) ?: false
                    Log.d(TAG, "│  open_camera: modo='$modo' frontal=$frontal")

                    try {
                        //  NUEVO: Verificar permiso ANTES de abrir cámara
                        if (!PermissionHelper.hasCameraPermission(this@MyAccessibilityService)) {
                            Log.e(TAG, "│  Permiso de cámara denegado")
                            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Necesito permiso de cámara. Ve a Ajustes > Aplicaciones > Permisos > Otorga cámara")
                                setPackage(packageName)
                            }
                            sendBroadcast(intent)
                            exitoAccion = false
                            detalleError = "Permiso de cámara denegado"
                            waitTime = 300L
                            return@run
                        }

                        val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            when (modo) {
                                "portrait" -> {
                                    putExtra("portrait_mode", true)
                                    Log.d(TAG, "│    Intentando modo retrato")
                                }
                                "frontal" -> {
                                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                                    Log.d(TAG, "│    Intentando cámara frontal")
                                }
                            }
                        }
                        startActivity(intent)
                        Log.d(TAG, "│  Cámara abierta (foto)")
                        exitoAccion = true
                        waitTime = 2500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error abriendo cámara: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "take_photo_auto" -> {
                    val frontal = accion.params?.get("frontal") as? Boolean ?: false
                    val portrait = accion.params?.get("portrait") as? Boolean ?: false
                    val delay = (accion.params?.get("delay_seconds") as? Number)?.toInt() ?: 0  //  0 = sin temporizador

                    Log.d(TAG, "│  take_photo_auto: frontal=$frontal, portrait=$portrait, delay=$delay")

                    try {
                        // 1. Verificar permiso de cámara
                        if (ContextCompat.checkSelfPermission(this@MyAccessibilityService, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                            val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Necesito permiso de cámara. Ve a ajustes y actívalo.")
                                setPackage(packageName)
                            }
                            sendBroadcast(speakIntent)
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            exitoAccion = false
                            waitTime = 300L
                            return@run
                        }

                        // ════════════════════════════════════════════════════════════════
                        // 2. ABRIR CÁMARA - SIN TEMPORIZADOR
                        // ════════════════════════════════════════════════════════════════
                        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                            //  NO poner extras de temporizador

                            //  Cámara FRONTAL o TRASERA
                            if (frontal) {
                                putExtra("android.intent.extras.CAMERA_FACING", 1)
                                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                                putExtra("CAMERA_FACING", 1)
                                putExtra("front_camera", true)
                                putExtra("isFrontCamera", true)
                                putExtra("front", true)
                                Log.d(TAG, "│  Cámara FRONTAL seleccionada")
                            } else {
                                Log.d(TAG, "│  Cámara TRASERA seleccionada")
                            }

                            if (portrait) {
                                putExtra("portrait_mode", true)
                                putExtra("MODE", "PORTRAIT")
                            }

                            //  IMPORTANTE: NO poner EXTRA_OUTPUT para que Samsung guarde automáticamente
                        }

                        if (intent.resolveActivity(packageManager) == null) {
                            Log.e(TAG, "│  No hay app de cámara")
                            exitoAccion = false
                            waitTime = 300L
                            return@run
                        }

                        // 3. ABRIR LA CÁMARA
                        startActivity(intent)
                        Log.d(TAG, "│  Cámara abierta (sin temporizador)")

                        // 4. NOTIFICACIÓN POR VOZ
                        val mensaje = if (frontal) "Tomando selfie" else "Tomando foto"
                        val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", mensaje)
                            setPackage(packageName)
                        }
                        sendBroadcast(speakIntent)

                        // ════════════════════════════════════════════════════════════════
                        // 5. FORZAR CÁMARA FRONTAL (si se pidió)
                        // ════════════════════════════════════════════════════════════════
                        if (frontal) {
                            // Esperar a que la cámara cargue y forzar cambio a frontal
                            handler.postDelayed({
                                Log.d(TAG, " Forzando cambio a cámara frontal...")
                                presionarBotonCambiarCamara()
                            }, 1500) // 1.5 segundos para que cargue
                        }

                        // ════════════════════════════════════════════════════════════════
                        // 6. CAPTURAR FOTO MANUALMENTE (sin temporizador)
                        // ════════════════════════════════════════════════════════════════
                        // Esperar a que la cámara cargue y la frontal esté activa
                        val tiempoCaptura = if (frontal) 3000L else 2000L // 3s si es frontal, 2s si es trasera

                        handler.postDelayed({
                            Log.d(TAG, " Capturando foto...")
                            buscarYPresionarBotonCaptura()

                            // Cerrar diálogo de confirmación después de capturar
                            handler.postDelayed({
                                cerrarDialogoConfirmacionSamsung()
                            }, 1500)
                        }, tiempoCaptura)

                        exitoAccion = true
                        waitTime = tiempoCaptura + 4000L

                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error en take_photo_auto: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "take_selfie" -> {
                    // Siempre frontal
                    val frontal = true
                    val portrait = accion.params?.get("portrait") as? Boolean ?: false
                    val delay = 0 //  SIN temporizador

                    Log.d(TAG, "│  take_selfie: frontal=$frontal, portrait=$portrait, delay=$delay")

                    try {
                        // Verificar permiso de cámara
                        if (ContextCompat.checkSelfPermission(this@MyAccessibilityService, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                            exitoAccion = false
                            waitTime = 300L
                            return@run
                        }

                        // ════════════════════════════════════════════════════════════════
                        // ABRIR CÁMARA - SIN TEMPORIZADOR
                        // ════════════════════════════════════════════════════════════════
                        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                            // Frontal
                            putExtra("android.intent.extras.CAMERA_FACING", 1)
                            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            putExtra("CAMERA_FACING", 1)
                            putExtra("front_camera", true)
                            if (portrait) {
                                putExtra("portrait_mode", true)
                            }
                        }

                        if (intent.resolveActivity(packageManager) == null) {
                            Log.e(TAG, "│  No hay app de cámara")
                            exitoAccion = false
                            waitTime = 300L
                            return@run
                        }

                        startActivity(intent)
                        Log.d(TAG, "│  Cámara abierta para selfie (sin temporizador)")

                        // Notificar
                        val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", "Tomando selfie")
                            setPackage(packageName)
                        }
                        sendBroadcast(speakIntent)

                        // Forzar cambio a frontal
                        handler.postDelayed({
                            Log.d(TAG, " Forzando cámara frontal para selfie...")
                            presionarBotonCambiarCamara()
                        }, 1500)

                        // Capturar
                        handler.postDelayed({
                            buscarYPresionarBotonCaptura()
                            handler.postDelayed({
                                cerrarDialogoConfirmacionSamsung()
                            }, 1500)
                        }, 3000)

                        exitoAccion = true
                        waitTime = 5000L

                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error: ${e.message}")
                        exitoAccion = false
                        waitTime = 300L
                    }
                }
                "take_photo" -> {
                    val frontal = accion.params?.get("frontal") as? Boolean ?: false
                    val portrait = accion.params?.get("portrait") as? Boolean ?: false

                    Log.d(TAG, "│  take_photo: frontal=$frontal, portrait=$portrait")

                    try {
                        // Verificar permiso de cámara
                        if (ContextCompat.checkSelfPermission(this@MyAccessibilityService, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {

                            Log.e(TAG, "│  Permiso de cámara no concedido - solicitando...")

                            // Notificar al usuario
                            val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Necesito permiso de cámara. Ve a ajustes y actívalo.")
                                setPackage(packageName)
                            }
                            sendBroadcast(speakIntent)

                            // Abrir ajustes de la app
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)

                            exitoAccion = false
                            detalleError = "Permiso de cámara denegado"
                            waitTime = 300L
                            return@run
                        }

                        // Crear intent para tomar foto
                        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                            if (frontal) {
                                // Intentar cámara frontal
                                putExtra("android.intent.extras.CAMERA_FACING", 1) // 1 = frontal
                            }
                            if (portrait) {
                                putExtra("portrait_mode", true)
                            }
                        }

                        // Verificar que hay una app de cámara disponible
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                            Log.d(TAG, "│  Intent de cámara enviado - tomando foto")

                            // Notificar al usuario
                            val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", if (frontal) "Tomando selfie" else "Tomando foto")
                                setPackage(packageName)
                            }
                            sendBroadcast(speakIntent)

                            exitoAccion = true
                            waitTime = 3000L
                        } else {
                            Log.e(TAG, "│  No hay app de cámara disponible")
                            exitoAccion = false
                            detalleError = "No hay app de cámara"
                            waitTime = 300L
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error tomando foto: ${e.message}", e)
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "call" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    Log.d(TAG, "│  call: '$contact'")
                    ActionExecutor.callContact(this@MyAccessibilityService, contact)
                    waitTime = 3000L
                }

                "set_alarm" -> {
                    val hour   = (accion.params?.get("hour") as? Number)?.toInt() ?: 7
                    val minute = (accion.params?.get("minute") as? Number)?.toInt() ?: 0
                    val label  = accion.params?.get("label") as? String ?: "Alarma"
                    Log.d(TAG, "│  set_alarm: $hour:$minute '$label'")
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    waitTime = 1500L
                }

                "play_music" -> {
                    val query = accion.params?.get("query") as? String ?: ""
                    val packageName = accion.params?.get("package") as? String ?: "com.spotify.music"

                    Log.d(TAG, "│  play_music: query='$query' pkg='$packageName'")

                    if (query.isNotBlank()) {
                        ActionExecutor.playMusic(this@MyAccessibilityService, query, packageName)
                    } else {
                        Log.w(TAG, "│  Query vacía, abriendo app por defecto")
                        ActionExecutor.openApp(this@MyAccessibilityService, packageName)
                    }
                    waitTime = 3000L  // Esperar más tiempo para que la app cargue y reproduzca
                }

                "play_video" -> {
                    val query = accion.params?.get("query") as? String ?: ""
                    Log.d(TAG, "│  play_video: query='$query'")
                    if (query.isNotBlank()) {
                        ActionExecutor.playVideo(this@MyAccessibilityService, query)
                    } else {
                        ActionExecutor.openApp(this@MyAccessibilityService, "com.google.android.youtube")
                    }
                    waitTime = 2000L
                }
                "navigate_to" -> {
                    val destination = accion.params?.get("destination") as? String ?: ""
                    Log.d(TAG, "│  navigate_to: '$destination'")
                    val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { startActivity(intent) } catch (e: Exception) {
                        val fallback = Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://maps.google.com/?q=${Uri.encode(destination)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(fallback)
                    }
                    waitTime = 2000L
                }

                "search_web" -> {
                    val query = accion.params?.get("query") as? String ?: ""
                    Log.d(TAG, "│  search_web: '$query'")
                    val intent = Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    waitTime = 1500L
                }

                "toggle_setting" -> {
                    val setting = accion.params?.get("setting") as? String ?: ""
                    val state   = accion.params?.get("state") as? String ?: "on"
                    Log.d(TAG, "│  toggle_setting: $setting → $state")
                    when (setting) {
                        "flashlight" -> {
                            val cam = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                            cam.setTorchMode(cam.cameraIdList[0], state == "on")
                        }
                        "wifi"      -> ejecutarAcciones(Gson().toJson(listOf(ActionDto("toggle_wifi", mapOf("state" to state)))))
                        "bluetooth" -> ejecutarAcciones(Gson().toJson(listOf(ActionDto("toggle_bluetooth", mapOf("state" to state)))))
                        "dnd" -> {
                            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        }
                    }
                    waitTime = 500L
                }
                "record_video" -> {
                    val modo = accion.params?.get("mode") as? String ?: "normal"  // normal, portrait, frontal
                    val duracion = (accion.params?.get("duration") as? Number)?.toInt() ?: 0  // 0 = sin límite

                    Log.d(TAG, "│  record_video: modo='$modo' duracion=${duracion}s")

                    try {
                        val intent = Intent("android.media.action.VIDEO_CAPTURE").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            when (modo) {
                                "portrait" -> {
                                    putExtra("portrait_mode", true)
                                    Log.d(TAG, "│    Modo retrato activado")
                                }
                                "frontal" -> {
                                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                                    Log.d(TAG, "│    Cámara frontal activada")
                                }
                            }
                            if (duracion > 0) {
                                putExtra("android.intent.extra.DURATION_LIMIT", duracion)
                                Log.d(TAG, "│    Límite de duración: ${duracion}s")
                            }
                        }
                        startActivity(intent)
                        Log.d(TAG, "│  Intent de video enviado")

                        val intent_speak = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", "Abriendo cámara para grabar video")
                            setPackage(packageName)
                        }
                        sendBroadcast(intent_speak)

                        exitoAccion = true
                        waitTime = 2500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error grabando video: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "switch_camera" -> {
                    val targetCamera = accion.params?.get("target") as? String ?: "toggle"  // frontal, trasera, toggle
                    Log.d(TAG, "│  switch_camera: target='$targetCamera'")

                    try {
                        // Este es un poco más complejo porque necesita acceso directo a la cámara
                        // Para apps que soporten esto, enviamos el intent adecuado

                        val intent = when (targetCamera) {
                            "frontal" -> Intent("android.media.action.IMAGE_CAPTURE").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            }
                            "trasera" -> Intent("android.media.action.IMAGE_CAPTURE").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
                            }
                            else -> Intent("android.media.action.IMAGE_CAPTURE").apply {  // toggle
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("android.intent.extra.CAMERA_FACING", -1)  // -1 = toggle
                            }
                        }

                        startActivity(intent)
                        Log.d(TAG, "│  Cámara cambiada a: $targetCamera")
                        exitoAccion = true
                        waitTime = 1500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error cambiando cámara: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }

                "adjust_brightness" -> {
                    val direction = accion.params?.get("direction") as? String ?: "up"
                    val percent = when (val raw = accion.params?.get("percent")) {
                        is Number -> raw.toInt().coerceIn(0, 100)
                        is String -> raw.toIntOrNull()?.coerceIn(0, 100)
                        else -> null
                    }
                    val steps = when (val raw = accion.params?.get("steps")) {
                        is Number -> raw.toInt().coerceIn(1, 10)
                        is String -> raw.toIntOrNull()?.coerceIn(1, 10) ?: 1
                        else -> 1
                    }

                    try {
                        //  MÉTODO 1: Cambiar brillo del sistema (requiere WRITE_SETTINGS)
                        val puedeEscribir = android.provider.Settings.System.canWrite(this@MyAccessibilityService)

                        if (puedeEscribir) {
                            // Desactivar brillo automático primero
                            android.provider.Settings.System.putInt(
                                contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                            )

                            val currentBrightness = android.provider.Settings.System.getInt(
                                contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                128
                            )

                            val newBrightness = if (percent != null) {
                                // Convertir porcentaje a escala 0-255
                                (255 * percent / 100.0).toInt().coerceIn(15, 255) // mínimo 15 para no apagar
                            } else {
                                val delta = steps * 30 // cada paso ≈ 12% de 255
                                if (direction == "up") (currentBrightness + delta).coerceIn(15, 255)
                                else (currentBrightness - delta).coerceIn(15, 255)
                            }

                            android.provider.Settings.System.putInt(
                                contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                newBrightness
                            )

                            Log.d(TAG, "│  adjust_brightness (SYSTEM): ${if (percent != null) "$percent%" else "$direction x$steps"} → $newBrightness/255")

                            // Hablar la confirmación
                            val pct = if (percent != null) percent else {
                                (newBrightness * 100 / 255)
                            }
                            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Brillo al $pct por ciento")
                                setPackage(packageName)
                            }
                            sendBroadcast(intent)

                        } else {
                            //  MÉTODO 2: Sin permiso → abrir configuración de brillo
                            // Y también intentar cambio mediante Quick Settings tile
                            Log.w(TAG, "│  Sin permiso WRITE_SETTINGS — solicitando permiso")

                            // Pedir permiso al usuario
                            val settingsIntent = Intent(
                                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:$packageName")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(settingsIntent)

                            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Necesito permiso para ajustar el brillo. Activa el permiso que aparece en pantalla.")
                                setPackage(packageName)
                            }
                            sendBroadcast(intent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "│  adjust_brightness error: ${e.message}")
                        exitoAccion = false
                    }
                    waitTime = 300L
                }
                "query_time" -> {
                    val cal = java.util.Calendar.getInstance()
                    val hora = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val minuto = String.format("%02d", cal.get(java.util.Calendar.MINUTE))
                    val periodo = if (hora < 12) "de la mañana" else if (hora < 18) "de la tarde" else "de la noche"
                    val hora12 = if (hora == 0) 12 else if (hora > 12) hora - 12 else hora
                    val textoHora = "Son las $hora12:$minuto $periodo"
                    Log.d(TAG, "│  query_time: $textoHora")

                    // Busca el controlador de voz para hablar
                    val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                        putExtra("texto", textoHora)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                    waitTime = 300L
                }

                "query_date" -> {
                    val cal = java.util.Calendar.getInstance()
                    val dias = arrayOf("domingo", "lunes", "martes", "miércoles",
                        "jueves", "viernes", "sábado")
                    val meses = arrayOf("enero", "febrero", "marzo", "abril", "mayo", "junio",
                        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
                    val diaSemana = dias[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
                    val diaMes    = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    val mes       = meses[cal.get(java.util.Calendar.MONTH)]
                    val anio      = cal.get(java.util.Calendar.YEAR)
                    val textoFecha = "Hoy es $diaSemana $diaMes de $mes de $anio"
                    Log.d(TAG, "│  query_date: $textoFecha")

                    val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                        putExtra("texto", textoFecha)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                    waitTime = 300L
                }
                "write_and_confirm" -> {
                    val mensaje = accion.params?.get("message") as? String ?: ""
                    Log.d(TAG, "│  write_and_confirm: '$mensaje'")

                    // Aumentamos el delay antes de escribir para asegurar que el chat cambió
                    handler.postDelayed({
                        escribirEnChatActual(mensaje)
                    }, 800) // 800ms de espera para el cambio de UI

                    currentActions = null
                    return
                }

                "send_whatsapp" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    val message = accion.params?.get("message") as? String ?: ""
                    Log.d(TAG, "│  send_whatsapp → '$contact': '$message'")

                    // En lugar de ejecutar directamente, mostramos el preview en el overlay
                    val intent = Intent("JARVIS.SHOW_WHATSAPP_PREVIEW").apply {
                        putExtra("contact", contact)
                        putExtra("message", message)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                    waitTime = 500L   // tiempo para que el overlay reaccione
                }

                "call_whatsapp" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    Log.d(TAG, "│  call_whatsapp: '$contact'")
                    if (contact.isNotBlank()) {
                        ActionExecutor.callWhatsApp(this@MyAccessibilityService, contact)
                    }
                    waitTime = 2000L
                    exitoAccion = true
                }
// En el stepRunnable, agregar los nuevos tipos de acciones:

                "record_video_auto" -> {
                    val frontal = accion.params?.get("frontal") as? Boolean ?: false
                    val duracion = (accion.params?.get("duration") as? Number)?.toInt() ?: 0
                    val delay = (accion.params?.get("delay_seconds") as? Number)?.toInt() ?: 0 // Por defecto 3s

                    Log.d(TAG, "│  record_video_auto: frontal=$frontal, duracion=${duracion}s, delay=${delay}s")

                    try {
                        // 1. Verificar permiso de cámara
                        if (ContextCompat.checkSelfPermission(this@MyAccessibilityService, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                            val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Necesito permiso de cámara. Ve a ajustes y actívalo.")
                                setPackage(packageName)
                            }
                            sendBroadcast(speakIntent)
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            exitoAccion = false
                            waitTime = 300L
                            return@run
                        }

                        // ════════════════════════════════════════════════════════════════
                        // 2. ABRIR CÁMARA DE VIDEO CON TEMPORIZADOR NATIVO DE SAMSUNG
                        // ════════════════════════════════════════════════════════════════
                        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                            //  ACTIVAR TEMPORIZADOR NATIVO para video
                            if (delay > 0) {
                                putExtra("timer", delay)
                                putExtra("selfie_timer", delay)
                                putExtra("Timer", delay)
                                putExtra("TIMER_VALUE", delay)
                                putExtra("com.sec.android.app.camera.extra.TIMER", delay)
                                putExtra("capture_timer", delay)
                                Log.d(TAG, "│  Temporizador Samsung activado para video: ${delay}s")
                            }

                            //  Cámara FRONTAL o TRASERA
                            if (frontal) {
                                putExtra("android.intent.extras.CAMERA_FACING", 1)
                                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                                putExtra("CAMERA_FACING", 1)
                                putExtra("front_camera", true)
                                putExtra("isFrontCamera", true)
                                putExtra("front", true)
                                Log.d(TAG, "│  Cámara FRONTAL seleccionada para video")
                            } else {
                                Log.d(TAG, "│  Cámara TRASERA seleccionada para video")
                            }

                            //  Duración límite (si se especificó)
                            if (duracion > 0) {
                                putExtra(MediaStore.EXTRA_DURATION_LIMIT, duracion)
                                putExtra("android.intent.extra.DURATION_LIMIT", duracion)
                                Log.d(TAG, "│  Límite de duración: ${duracion}s")
                            }

                            //  IMPORTANTE: NO poner EXTRA_OUTPUT
                            // Samsung guarda automáticamente en su galería
                        }

                        if (intent.resolveActivity(packageManager) == null) {
                            Log.e(TAG, "│  No hay app de cámara para video")
                            exitoAccion = false
                            waitTime = 300L
                            return@run
                        }

                        // 3. ABRIR LA CÁMARA DE VIDEO
                        startActivity(intent)
                        Log.d(TAG, "│  Cámara de video abierta")

                        // 4. NOTIFICACIÓN POR VOZ
                        val mensaje = buildString {
                            append(if (frontal) "Grabando con cámara frontal" else "Grabando video")
                            if (delay > 0) append(" en $delay segundos")
                            if (duracion > 0) append(" por $duracion segundos")
                        }
                        val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", mensaje)
                            setPackage(packageName)
                        }
                        sendBroadcast(speakIntent)

                        // ════════════════════════════════════════════════════════════════
                        // 5.  INICIAR GRABACIÓN AUTOMÁTICAMENTE (¡NUEVO!)
                        // ════════════════════════════════════════════════════════════════

                        // Calcular tiempo de espera según si hay temporizador
                        val tiempoEspera = if (delay > 0) {
                            // Si hay temporizador, esperar a que termine + 1s extra
                            (delay * 1000L) + 2000L
                        } else {
                            // Sin temporizador, esperar 1.5s para que cargue la cámara
                            1500L
                        }

                        //  Intentar iniciar la grabación después de que la cámara esté lista
                        handler.postDelayed({
                            Log.d(TAG, " Intentando iniciar grabación automáticamente...")
                            buscarYPresionarBotonGrabar()
                            Log.d(TAG, " Proceso de inicio de grabación completado")
                        }, tiempoEspera)

                        // ════════════════════════════════════════════════════════════════
                        // 6. CIERRE AUTOMÁTICO DEL DIÁLOGO (si hay duración límite)
                        // ════════════════════════════════════════════════════════════════
                        if (duracion > 0) {
                            val tiempoCierreDialogo = (delay * 1000L) + (duracion * 1000L) + 2500L

                            handler.postDelayed({
                                Log.d(TAG, " Cerrando diálogo de confirmación (video con duración límite)...")
                                cerrarDialogoConfirmacionSamsung()
                            }, tiempoCierreDialogo)

                            waitTime = tiempoCierreDialogo + 3000L

                        } else {
                            // Grabación continua
                            Log.d(TAG, " Grabación continua - di 'detener grabación' para parar")
                            waitTime = 120000L // 2 minutos
                        }

                        exitoAccion = true

                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error en record_video_auto: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }

                "pause_video" -> {
                    Log.d(TAG, "│  pause_video")
                    try {
                        // Buscar botón de pausa en la interfaz de cámara
                        val root = rootInActiveWindow
                        if (root != null) {
                            // Buscar por texto "Pausa", "Pause", "II"
                            val candidatosPausa = listOf("Pausa", "Pause", "II", "")
                            var botonPausa: AccessibilityNodeInfo? = null

                            for (candidato in candidatosPausa) {
                                botonPausa = encontrarNodoPorTexto(root, candidato)
                                if (botonPausa != null) break
                            }

                            // También buscar por IDs comunes
                            if (botonPausa == null) {
                                val idsPausa = listOf(
                                    "com.google.android.GoogleCamera:id/pause_button",
                                    "com.android.camera2:id/pause_button"
                                )
                                for (id in idsPausa) {
                                    val nodos = root.findAccessibilityNodeInfosByViewId(id)
                                    if (nodos.isNotEmpty()) {
                                        botonPausa = nodos[0]
                                        break
                                    }
                                }
                            }

                            if (botonPausa != null) {
                                hacerClicEnNodoOAncestros(botonPausa)
                                Log.d(TAG, "│  Video pausado")
                            } else {
                                // Fallback: tap en coordenadas típicas del botón de pausa
                                Log.w(TAG, "│  Botón pausa no encontrado, usando coordenadas")
                                val metrics = resources.displayMetrics
                                tapCoordenadas(mapOf(
                                    "x" to (metrics.widthPixels * 0.85f),
                                    "y" to (metrics.heightPixels * 0.85f)
                                ))
                            }
                        }
                        exitoAccion = true
                        waitTime = 500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error pausando: ${e.message}")
                        exitoAccion = false
                        waitTime = 300L
                    }
                }

                "resume_video" -> {
                    Log.d(TAG, "│ ▶ resume_video")
                    try {
                        val root = rootInActiveWindow
                        if (root != null) {
                            // Buscar botón de reanudar/grabar
                            val candidatosReanudar = listOf("Reanudar", "Resume", "Grabar", "▶", "")
                            var botonReanudar: AccessibilityNodeInfo? = null

                            for (candidato in candidatosReanudar) {
                                botonReanudar = encontrarNodoPorTexto(root, candidato)
                                if (botonReanudar != null) break
                            }

                            if (botonReanudar != null) {
                                hacerClicEnNodoOAncestros(botonReanudar)
                                Log.d(TAG, "│  Video reanudado")
                            }
                        }
                        exitoAccion = true
                        waitTime = 500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│  Error reanudando: ${e.message}")
                        exitoAccion = false
                        waitTime = 300L
                    }
                }
                "send_sms" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    val message = accion.params?.get("message") as? String ?: ""
                    Log.d(TAG, "│  send_sms → '$contact': '$message'")
                    ActionExecutor.sendSms(this@MyAccessibilityService, contact, message)
                    waitTime = 2000L
                }

                "call_contact" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    Log.d(TAG, "│  call_contact: '$contact'")
                    ActionExecutor.callContact(this@MyAccessibilityService, contact)
                    waitTime = 3000L
                }

                "global_action" -> {
                    Log.d(TAG, "│  global_action: ${accion.params}")
                    ejecutarAccionGlobal(accion.params)
                    waitTime = 800L
                }

                "scroll" -> {
                    val direction = accion.params?.get("direction") as? String ?: "down"
                    Log.d(TAG, "│  scroll: $direction")
                    scroll(direction)
                    waitTime = 1200L
                }

                "swipe" -> {
                    val direction = accion.params?.get("direction") as? String ?: "left"
                    Log.d(TAG, "│  swipe: $direction")
                    scroll(direction)
                    waitTime = 800L
                }

                "tap" -> {
                    Log.d(TAG, "│  tap: ${accion.params}")
                    tapCoordenadas(accion.params)
                    waitTime = 1000L
                }

                "ocr_tap" -> {
                    val textoABuscar = accion.params?.get("texto") as? String ?: ""
                    Log.d(TAG, "│  ocr_tap: '$textoABuscar'")

                    val esBotoEnviar = textoABuscar.lowercase() in setOf(
                        "enviar", "send", "enviar mensaje", "enviar ahora", "submit"
                    )
                    if (esBotoEnviar && ultimoTextoEscrito.isNotBlank()) {
                        Log.d(TAG, "│    Detectado botón Enviar con texto pendiente")
                        pausarParaConfirmacion(textoABuscar)
                        return
                    }

                    val snapshotAge = System.currentTimeMillis() - (lastSnapshot?.timestamp ?: 0)
                    if (snapshotAge > 3000L || lastSnapshot == null) {
                        lastFingerprint = ""
                        actualizarSnapDePantalla()
                    }
                    val elemento = buscarElementoPorTexto(textoABuscar)
                    if (elemento != null) {
                        Log.d(TAG, "│     Elemento encontrado en (${elemento.centerX}, ${elemento.centerY})")
                        tapCoordenadas(mapOf("x" to elemento.centerX, "y" to elemento.centerY))
                    } else {
                        Log.w(TAG, "│  Elemento '$textoABuscar' NO encontrado")
                        exitoAccion = false
                        detalleError = "Elemento '$textoABuscar' no visible"
                    }
                    waitTime = 1000L
                }

                "type_text" -> {
                    val texto = accion.params?.get("texto") as? String
                        ?: accion.params?.get("text") as? String  // fallback por si Gemini usa "text"
                        ?: ""
                    ultimoTextoEscrito = texto
                    Log.d(TAG, "│  type_text: '$texto'")

                    if (texto.isBlank()) {
                        Log.w(TAG, "│  Texto vacío, ignorando")
                        exitoAccion = false
                    } else {
                        val root = rootInActiveWindow
                        if (root == null) {
                            Log.e(TAG, "│  Sin ventana activa")
                            exitoAccion = false
                        } else {
                            // Intento 1: campo con foco actual
                            val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            if (focusNode != null && focusNode.isEditable) {
                                val args = Bundle()
                                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
                                val ok = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                Log.d(TAG, "│ ${if (ok) "" else ""} type_text con foco: '$texto'")
                                exitoAccion = ok
                            } else {
                                // Intento 2: primer campo editable en árbol
                                val editable = encontrarCampoEditable(root)
                                if (editable != null) {
                                    // Dar foco tocando el campo primero
                                    val bounds = Rect()
                                    editable.getBoundsInScreen(bounds)
                                    if (!bounds.isEmpty) {
                                        tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
                                    }
                                    handler.postDelayed({
                                        val rootRetry = rootInActiveWindow ?: return@postDelayed
                                        val focusRetry = rootRetry.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                            ?: encontrarCampoEditable(rootRetry)
                                        if (focusRetry != null) {
                                            val args = Bundle()
                                            args.putCharSequence(
                                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto
                                            )
                                            val ok = focusRetry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                            Log.d(TAG, "│ ${if (ok) "" else ""} type_text retry: '$texto'")
                                        } else {
                                            Log.e(TAG, "│  No se encontró campo editable tras tap")
                                        }
                                    }, 600L)
                                } else {
                                    Log.w(TAG, "│  No hay campo editable en pantalla")
                                    exitoAccion = false
                                }
                            }
                        }
                    }
                    waitTime = 900L
                }

                "tap_send_button" -> {
                    Log.d(TAG, "│  tap_send_button")
                    val candidatos = accion.params?.get("candidatos")
                        ?.toString()
                        ?.split(",")
                        ?: listOf("Enviar", "Send", "send", "Enviar mensaje")

                    val root = rootInActiveWindow
                    if (root == null) {
                        Log.w(TAG, "│  Sin ventana activa")
                        exitoAccion = false
                    } else {
                        var enviado = false
                        for (candidato in candidatos) {
                            val nodo = encontrarNodoPorTexto(root, candidato.trim())
                            if (nodo != null) {
                                var nodoClick: AccessibilityNodeInfo? = nodo
                                while (nodoClick != null && !nodoClick.isClickable) {
                                    nodoClick = nodoClick.parent
                                }
                                if (nodoClick != null && nodoClick.isClickable) {
                                    nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    Log.d(TAG, "│  Tap en '$candidato'")
                                    enviado = true
                                    break
                                }
                                val bounds = Rect()
                                nodo.getBoundsInScreen(bounds)
                                if (!bounds.isEmpty) {
                                    tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
                                    Log.d(TAG, "│  Tap coords para '$candidato'")
                                    enviado = true
                                    break
                                }
                            }
                        }
                        if (!enviado) {
                            val elemento = buscarElementoPorTexto("Enviar")
                                ?: buscarElementoPorTexto("Send")
                            if (elemento != null) {
                                tapCoordenadas(mapOf("x" to elemento.centerX, "y" to elemento.centerY))
                                Log.d(TAG, "│  Tap fallback snapshot")
                            } else {
                                Log.w(TAG, "│  Botón no encontrado")
                                exitoAccion = false
                            }
                        }
                    }
                    ultimoTextoEscrito = ""
                    waitTime = 800L
                }

                "clear_text_field" -> {
                    Log.d(TAG, "│  clear_text_field")
                    val root = rootInActiveWindow
                    if (root == null) {
                        Log.w(TAG, "│  Sin ventana activa")
                        exitoAccion = false
                    } else {
                        val campo = encontrarCampoEditable(root)
                        if (campo != null) {
                            val args = Bundle()
                            args.putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                            )
                            val ok = campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            Log.d(TAG, "│ ${if (ok) "" else ""} Campo limpiado")
                            exitoAccion = ok
                        } else {
                            Log.w(TAG, "│  Sin campo editable")
                            exitoAccion = false
                        }
                    }
                    ultimoTextoEscrito = ""
                    waitTime = 400L
                }

                "open_notification" -> {
                    val pkg = accion.params?.get("package") as? String ?: ""
                    Log.d(TAG, "│  open_notification: '$pkg'")
                    JarvisNotificationListener.instance?.openNotification(
                        this@MyAccessibilityService, pkg
                    )
                    waitTime = 1200L
                }

                "reply_notification" -> {
                    val pkg   = accion.params?.get("package") as? String ?: ""
                    val texto = accion.params?.get("texto") as? String ?: ""
                    Log.d(TAG, "│  reply_notification: '$pkg' → '$texto'")
                    if (texto.isNotBlank()) {
                        JarvisNotificationListener.instance?.replyToNotification(pkg, texto)
                    }
                    waitTime = 1000L
                }
                "open_url" -> {
                    val url = accion.params?.get("url") as? String ?: ""
                    Log.d(TAG, "│ open_url: '$url'")
                    if (url.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        waitTime = 1500L   // tiempo para que se abra Maps
                        exitoAccion = true
                    } else {
                        Log.e(TAG, "│  URL vacía")
                        exitoAccion = false
                        detalleError = "URL vacía"
                        waitTime = 300L
                    }
                }
                "unlock_screen" -> {
                    Log.d(TAG, "│  unlock_screen")
                    performGlobalAction(GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
                    waitTime = 500L
                }

                else -> {
                    Log.w(TAG, "│  Tipo desconocido: ${accion.tipo}")
                    exitoAccion = false
                    detalleError = "Tipo desconocido: ${accion.tipo}"
                }

            }

            reportarAlServidor(textoUltimaOrden, intencionUltimaOrden, actions, exitoAccion, detalleError)
            Log.d(TAG, "└─  Esperando ${waitTime}ms antes de siguiente acción")
            handler.postDelayed(this, waitTime)
        }
    }
    // Agrega esto dentro de tu clase MyAccessibilityService
    private val splitScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val pkg1 = intent?.getStringExtra("app1") ?: intent?.getStringExtra("first_package")
            val pkg2 = intent?.getStringExtra("app2") ?: intent?.getStringExtra("second_package")

            if (pkg1 != null && pkg2 != null) {
                iniciarPantallaDivididaUnificado(pkg1, pkg2)
            } else if (pkg2 != null) {
                // Si solo llega la segunda, asumimos que la primera ya está abierta
                // (caso de uso desde el overlay o comando rápido)
                val currentPkg = rootInActiveWindow?.packageName?.toString()
                if (currentPkg != null) {
                    iniciarPantallaDivididaUnificado(currentPkg, pkg2)
                } else {
                    Log.e(TAG, " No se conoce la primera app")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter("JARVIS.TRIGGER_SPLIT_SCREEN")
        registerReceiver(splitScreenReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
    }

    private fun cameraAvailable(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_CAMERA_ANY
        )
    }
    // Función para cambiar a cámara frontal en modo video
    private fun presionarBotonCambiarCamaraVideo() {
        val candidatos = listOf(
            "Cambiar cámara", "Switch camera", "Flip", "Rotar cámara",
            "Cámara frontal", "Frontal"
        )

        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed

            var nodoEncontrado: AccessibilityNodeInfo? = null
            for (candidato in candidatos) {
                nodoEncontrado = encontrarNodoPorTexto(root, candidato)
                if (nodoEncontrado != null) break
            }

            if (nodoEncontrado != null) {
                hacerClicEnNodoOAncestros(nodoEncontrado)
                Log.d(TAG, " Cámara cambiada para video")
            }
        }, 800)
    }

    // Función para buscar y presionar botón de grabar
    private fun buscarYPresionarBotonGrabar(): Boolean {
        var resultado = false

        val candidatos = listOf(
            "Grabar", "Record", "Iniciar", "", "●", "Start",
            "Iniciar grabación", "Comenzar",
            "Grabar video", "Iniciar grabación", "Comenzar grabación",
            "Iniciar", "Comenzar", "graba un video",
            //  Más variantes para Samsung
            "Grabar vídeo", "Grabar video ahora", "Empezar",
            "Start recording", "Record video", "Iniciar grabación de video"
        )

        val idsGrabar = listOf(
            "com.google.android.GoogleCamera:id/shutter_button",
            "com.android.camera2:id/shutter_button",
            "com.sec.android.app.camera:id/record_button",
            //  IDs extra de Samsung
            "com.sec.android.app.camera:id/recording_button",
            "com.sec.android.app.camera:id/video_record_button",
            "com.sec.android.app.camera:id/start_recording",
            "com.sec.android.camera:id/record_button"
        )

        handler.postDelayed({
            val root = rootInActiveWindow ?: run {
                Log.w(TAG, " rootInActiveWindow es NULL")
                return@postDelayed
            }

            var botonEncontrado: AccessibilityNodeInfo? = null

            // 1. Buscar por texto
            for (candidato in candidatos) {
                botonEncontrado = encontrarNodoPorTexto(root, candidato)
                if (botonEncontrado != null) {
                    Log.d(TAG, " Botón 'Grabar' encontrado por texto: '$candidato'")
                    break
                }
            }

            // 2. Buscar por IDs
            if (botonEncontrado == null) {
                for (id in idsGrabar) {
                    val nodos = root.findAccessibilityNodeInfosByViewId(id)
                    if (nodos.isNotEmpty()) {
                        botonEncontrado = nodos.firstOrNull { it.isClickable }
                        if (botonEncontrado != null) {
                            Log.d(TAG, " Botón 'Grabar' encontrado por ID: $id")
                            break
                        }
                    }
                }
            }

            // 3. Buscar por descripción de contenido
            if (botonEncontrado == null) {
                fun buscarPorDesc(nodo: AccessibilityNodeInfo?): Boolean {
                    if (nodo == null) return false
                    val desc = nodo.contentDescription?.toString()?.lowercase() ?: ""
                    if (desc.contains("grabar") || desc.contains("record") ||
                        desc.contains("iniciar") || desc.contains("start")) {
                        if (nodo.isClickable) {
                            botonEncontrado = nodo
                            Log.d(TAG, " Botón 'Grabar' encontrado por descripción: '$desc'")
                            return true
                        }
                    }
                    for (i in 0 until nodo.childCount) {
                        if (buscarPorDesc(nodo.getChild(i))) return true
                    }
                    return false
                }
                buscarPorDesc(root)
            }

            // 4. Si encontramos el botón, hacer clic
            if (botonEncontrado != null) {
                hacerClicEnNodoOAncestros(botonEncontrado)
                Log.d(TAG, " Grabación iniciada (clic en botón)")
                resultado = true
            } else {
                // 5. Fallback: tap en centro-inferior
                Log.w(TAG, " No se encontró botón Grabar, usando tap en centro-inferior")
                val metrics = resources.displayMetrics
                tapCoordenadas(mapOf(
                    "x" to (metrics.widthPixels / 2f),
                    "y" to (metrics.heightPixels * 0.78f)
                ))
                Log.d(TAG, " Tap en centro-inferior (fallback)")
                resultado = true
            }

        }, 500)

        return resultado
    }

    private fun presionarBotonAceptarFoto() {
        val candidatos = listOf(
            "Aceptar", "Guardar", "OK", "", "", "Listo", "Done",
            "Aceptar foto", "Guardar foto"
        )

        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, " No se puede acceder a la ventana activa para aceptar foto")
                return@postDelayed
            }

            var nodoEncontrado: AccessibilityNodeInfo? = null
            for (candidato in candidatos) {
                nodoEncontrado = encontrarNodoPorTexto(root, candidato)
                if (nodoEncontrado != null) break
            }

            if (nodoEncontrado == null) {
                // Buscar por IDs comunes de botón de aceptar
                val ids = listOf(
                    "com.google.android.GoogleCamera:id/done_button",
                    "com.sec.android.app.camera:id/ok_button",
                    "com.android.camera2:id/done_button",
                    "com.oppo.camera:id/btn_done"
                )
                for (id in ids) {
                    val nodos = root.findAccessibilityNodeInfosByViewId(id)
                    if (nodos.isNotEmpty()) {
                        nodoEncontrado = nodos[0]
                        break
                    }
                }
            }

            if (nodoEncontrado != null) {
                var nodoClick = nodoEncontrado
                while (nodoClick != null && !nodoClick.isClickable) {
                    nodoClick = nodoClick.parent
                }
                if (nodoClick != null && nodoClick.isClickable) {
                    nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, " Botón Aceptar/Guardar presionado")
                } else {
                    val bounds = Rect()
                    nodoEncontrado.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
                        Log.d(TAG, " Tap en coordenadas del botón Aceptar")
                    }
                }
            } else {
                Log.w(TAG, " No se encontró botón Aceptar/Guardar. La foto puede que ya se haya guardado.")
            }
        }, 500) // esperar 500ms para que la pantalla de confirmación se estabilice
    }
    private fun verificarPermisosCamara() {
        val permisosFaltantes = PermissionHelper.getMissingPermissions(this)

        if (permisosFaltantes.isNotEmpty()) {
            Log.w(TAG, " Permisos faltantes: ${permisosFaltantes.joinToString(", ")}")
            Log.i(TAG, " Los permisos se solicitarán cuando se use la cámara")

            // Notificar al usuario
            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                putExtra("texto", "Necesito permiso de cámara. Ve a Ajustes > Aplicaciones > Permisos")
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } else {
            Log.d(TAG, " Todos los permisos de cámara están otorgados")
        }
    }

    private fun activarSplitScreenConAccessibility(package1: String, package2: String) {
        Log.d(TAG, " Forzando Split Screen vía Gestos de Accesibilidad Nativos")

        // Paso 1: Asegurar que la primera app esté abierta y al frente
        val intent1 = packageManager.getLaunchIntentForPackage(package1)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        if (intent1 == null) {
            Log.e(TAG, " No se obtuvo el intent para $package1")
            return
        }
        startActivity(intent1)

        // Paso 2: Esperar a que Android dibuje la primera app por completo (Crucial)
        handler.postDelayed({
            Log.d(TAG, " Disparando comando GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN")

            // Esto le dice al SystemUI de Android: "Parte la pantalla actual a la mitad"
            val exitoComando = performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

            if (!exitoComando) {
                Log.e(TAG, " El sistema operativo rechazó el comando de pantalla dividida. ¿Está el permiso activo?")
                return@postDelayed
            }

            // Paso 3: Darle tiempo al SystemUI para encoger la primera app y abrir el puerto adyacente
            handler.postDelayed({
                Log.d(TAG, " Lanzando la segunda app en el espacio dividido: $package2")

                val intent2 = packageManager.getLaunchIntentForPackage(package2)?.apply {
                    // IMPORTANTÍSIMO: Estos flags obligan a la app a no pisar a la anterior
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }

                if (intent2 != null) {
                    startActivity(intent2)
                    Log.d(TAG, " Segunda app acoplada con éxito: $package2")
                } else {
                    Log.e(TAG, " No se pudo obtener el intent para $package2")
                }
            }, 800) // 800ms de retraso para que Android termine la animación de división

        }, 1200) // 1.2 segundos para asegurar el enfoque de la primera app
    }

    private fun buscarYActivarSplitScreen(root: AccessibilityNodeInfo) {
        // Buscar texto "Split screen", "Pantalla dividida", "Multiventana"
        val textosSplit = listOf(
            "Split screen", "Pantalla dividida", "Multiventana",
            "Dividir pantalla", "Doble pantalla", "Ventana dividida"
        )

        for (texto in textosSplit) {
            val nodos = root.findAccessibilityNodeInfosByText(texto)
            if (nodos.isNotEmpty()) {
                val nodo = nodos[0]
                var nodoClick = nodo
                while (nodoClick != null && !nodoClick.isClickable) {
                    nodoClick = nodoClick.parent
                }
                if (nodoClick != null && nodoClick.isClickable) {
                    nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, " Split screen activado: $texto")
                    return
                }
            }
        }

        // Buscar por descripción de contenido
        fun buscarPorDescripcion(nodo: AccessibilityNodeInfo?): Boolean {
            if (nodo == null) return false
            val desc = nodo.contentDescription?.toString() ?: ""
            if (desc.contains("split", ignoreCase = true) ||
                desc.contains("dividir", ignoreCase = true) ||
                desc.contains("multivista", ignoreCase = true)) {
                if (nodo.isClickable) {
                    nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, " Split screen por descripción: $desc")
                    return true
                }
            }
            for (i in 0 until nodo.childCount) {
                if (buscarPorDescripcion(nodo.getChild(i))) return true
            }
            return false
        }

        buscarPorDescripcion(root)
    }
    private fun presionarBotonCambiarCamara() {
        val candidatos = listOf(
            "Cambiar cámara", "Girar cámara", "Switch camera", "Flip",
            "Cámara frontal", "Cámara trasera", "Selfie", "Frontal",
            "Cambiar", "Intercambiar", "Girar", "Voltear",
            "Cámara delantera", "Cámara trasera", "Delantera", "Trasera"
        )

        val idsSamsung = listOf(
            "com.sec.android.app.camera:id/button_switch_camera",
            "com.sec.android.app.camera:id/switch_camera_button",
            "com.sec.android.app.camera:id/camera_switch_button",
            "com.sec.android.app.camera:id/btn_switch_camera"
        )

        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            var nodoEncontrado: AccessibilityNodeInfo? = null

            // Buscar por texto
            for (candidato in candidatos) {
                nodoEncontrado = encontrarNodoPorTexto(root, candidato)
                if (nodoEncontrado != null) {
                    Log.d(TAG, " Encontrado por texto: '$candidato'")
                    break
                }
            }

            // Buscar por IDs
            if (nodoEncontrado == null) {
                for (id in idsSamsung) {
                    val nodos = root.findAccessibilityNodeInfosByViewId(id)
                    if (nodos.isNotEmpty()) {
                        nodoEncontrado = nodos[0]
                        Log.d(TAG, " Encontrado por ID: $id")
                        break
                    }
                }
            }

            if (nodoEncontrado != null) {
                var nodoClick = nodoEncontrado
                while (nodoClick != null && !nodoClick.isClickable) {
                    nodoClick = nodoClick.parent
                }
                if (nodoClick != null && nodoClick.isClickable) {
                    nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, " Cámara frontal activada")
                }
            } else {
                // Fallback: tap en esquina superior derecha
                val metrics = resources.displayMetrics
                tapCoordenadas(mapOf(
                    "x" to (metrics.widthPixels * 0.88f),
                    "y" to (metrics.heightPixels * 0.08f)
                ))
                Log.d(TAG, " Tap en esquina superior derecha (fallback)")
            }
        }, 800)
    }
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(packageName) == true
    }
    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        // Mostrar un mensaje explicativo
        val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
            putExtra("texto", "Para usar pantalla dividida, activa Nexus en Ajustes > Accesibilidad")
            setPackage(packageName)
        }
        sendBroadcast(speakIntent)
    }
    private fun buscarYPresionarBotonCaptura() {
        // Lista de textos/descripciones comunes del botón de captura
        val candidatos = listOf(
            "Capturar", "Tomar foto", "Shutter", "Foto", "Capture", "Shutter button",
            "camera shutter", "tomar", "capture"
        )

        // IDs comunes del botón de captura en distintas apps
        val idsCandidatos = listOf(
            "com.google.android.GoogleCamera:id/shutter_button",
            "com.android.camera2:id/shutter_button",
            "com.sec.android.app.camera:id/shutter_button",
            "com.oppo.camera:id/shutter_button",
            "com.xiaomi.camera:id/shutter_button"
        )

        // Esperar un poco para que la cámara esté completamente cargada
        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, " No se puede acceder a la ventana activa")
                return@postDelayed
            }

            // 1. Buscar por texto/descripción usando encontrarNodoPorTexto (recursivo)
            var botonEncontrado: AccessibilityNodeInfo? = null
            for (candidato in candidatos) {
                val nodo = encontrarNodoPorTexto(root, candidato)
                if (nodo != null) {
                    botonEncontrado = nodo
                    break
                }
            }

            // 2. Si no se encuentra por texto, buscar por IDs de vista
            if (botonEncontrado == null) {
                for (id in idsCandidatos) {
                    val nodos = root.findAccessibilityNodeInfosByViewId(id)
                    if (nodos.isNotEmpty()) {
                        // Tomar el primero que sea clickable o cuyo padre lo sea
                        botonEncontrado = nodos.firstOrNull { it.isClickable || it.parent?.isClickable == true }
                        if (botonEncontrado != null) break
                    }
                }
            }

            // 3. Si aún no se encuentra, hacer un tap en el centro inferior de la pantalla (fallback)
            if (botonEncontrado == null) {
                Log.w(TAG, " No se encontró el botón de captura, usando tap en centro inferior")
                val metrics = resources.displayMetrics
                val x = metrics.widthPixels / 2f
                val y = metrics.heightPixels * 0.85f // cerca del borde inferior
                tapCoordenadas(mapOf("x" to x, "y" to y))
                return@postDelayed
            }

            // Ejecutar clic en el nodo encontrado (o en su ancestro clickable)
            var nodoClick = botonEncontrado
            while (nodoClick != null && !nodoClick.isClickable) {
                nodoClick = nodoClick.parent
            }
            if (nodoClick != null && nodoClick.isClickable) {
                nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, " Botón de captura presionado")
            } else {
                // Fallback: tap en coordenadas del nodo
                val bounds = Rect()
                botonEncontrado.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
                    Log.d(TAG, " Tap en coordenadas del botón")
                } else {
                    Log.e(TAG, " No se pudo hacer clic en el botón")
                }
            }
        }, 1000) // esperar 1 segundo adicional para que la cámara se estabilice
    }
    private fun escribirEnChatActual(mensaje: String) {
        val root = rootInActiveWindow
        if (root == null) {
            Log.e(TAG, " escribirEnChatActual: sin ventana activa")
            return
        }

        val campo = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: encontrarCampoEditable(root)

        if (campo != null) {
            val bounds = Rect()
            campo.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
            }

            handler.postDelayed({
                val rootActual = rootInActiveWindow ?: return@postDelayed
                val campoActual = rootActual.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: encontrarCampoEditable(rootActual)

                if (campoActual != null) {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, mensaje
                    )
                    val ok = campoActual.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Log.d(TAG, "${if (ok) "" else ""} Escrito en chat: '$mensaje'")

                    if (ok) {
                        ultimoTextoEscrito = mensaje
                        handler.postDelayed({
                            pausarParaConfirmacion("Enviar")
                        }, 600L)
                    }
                }
            }, 500L)

        }
    }

    private fun pausarParaConfirmacion(textoBoton: String) {
        val mensaje  = ultimoTextoEscrito
        val pregunta = "¿Enviar el mensaje?"
        Log.d(TAG, " Pausa para confirmación: '$mensaje'")

        ActionExecutor.onConfirmacionPendiente = { confirmado ->
            if (confirmado) {
                Log.d(TAG, " Confirmado → tap Enviar")
                //  FIX: refrescar snapshot antes de buscar el botón
                lastFingerprint = ""
                actualizarSnapDePantalla()
                handler.postDelayed({
                    val elemento = buscarElementoPorTexto(textoBoton)
                        ?: buscarElementoPorTexto("Send")
                    if (elemento != null) {
                        tapCoordenadas(mapOf("x" to elemento.centerX, "y" to elemento.centerY))
                        Log.d(TAG, " Tap en botón Enviar")
                    } else {
                        Log.w(TAG, " Botón '$textoBoton' no encontrado en snapshot")
                    }
                    ultimoTextoEscrito = ""
                    handler.postDelayed(stepRunnable, 800L)
                }, 500L)
            } else {
                Log.d(TAG, " Cancelado → borrando texto")
                val root = rootInActiveWindow
                if (root != null) {
                    val campo = encontrarCampoEditable(root)
                    if (campo != null) {
                        val args = Bundle()
                        args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                        )
                        campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                }
                ultimoTextoEscrito = ""
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 300L)
                currentActions = null
            }
        }

        //  FIX CRÍTICO: delay de 800ms antes de enviar el broadcast
        // Esto da tiempo al SR para terminar de iniciar y evita ERROR_CLIENT
        handler.postDelayed({
            val intent = Intent("JARVIS.PEDIR_CONFIRMACION").apply {
                putExtra("pregunta", pregunta)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            Log.d(TAG, " Broadcast PEDIR_CONFIRMACION enviado")
        }, 800L)
    }

    private fun ejecutarAccionGlobal(params: Map<String, Any>?) {
        val actionType = params?.get("action") as? String ?: return
        when (actionType) {
            "home"          -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back"          -> performGlobalAction(GLOBAL_ACTION_BACK)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "recents"       -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "lock"          -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "screenshot"    -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            else            -> Log.w(TAG, "Acción global desconocida: $actionType")
        }
    }
    fun scroll(direction: String) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (yStart, yEnd) = when (direction) {
            "up"    -> Pair(metrics.heightPixels * 0.25f, metrics.heightPixels * 0.75f)
            "down"  -> Pair(metrics.heightPixels * 0.75f, metrics.heightPixels * 0.25f)
            "left"  -> Pair(metrics.widthPixels  * 0.8f,  metrics.widthPixels  * 0.2f)
            "right" -> Pair(metrics.widthPixels  * 0.2f,  metrics.widthPixels  * 0.8f)
            else    -> Pair(metrics.heightPixels * 0.75f, metrics.heightPixels * 0.25f)
        }
        val isHorizontal = direction == "left" || direction == "right"
        val path = Path().apply {
            if (isHorizontal) {
                moveTo(yStart, metrics.heightPixels / 2f)
                lineTo(yEnd,   metrics.heightPixels / 2f)
            } else {
                moveTo(x, yStart)
                lineTo(x, yEnd)
            }
        }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 400L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        ejecutarGesture(gesture)
    }

    private fun ejecutarGesture(gesture: GestureDescription) {
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { Log.d(TAG, "Gesture OK") }
            override fun onCancelled(g: GestureDescription?) { Log.e(TAG, "Gesture cancelado") }
        }, handler)
    }

    // En MyAccessibilityService.kt
    fun tapCoordenadas(params: Map<String, Any>?) {
        val x = when (val raw = params?.get("x")) {
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull() ?: run {
                Log.w(TAG, "tapCoordenadas: 'x' no es número válido"); return
            }
            else -> { Log.w(TAG, "tapCoordenadas: 'x' inválido"); return }
        }
        val y = when (val raw = params?.get("y")) {
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull() ?: run {
                Log.w(TAG, "tapCoordenadas: 'y' no es número válido"); return
            }
            else -> { Log.w(TAG, "tapCoordenadas: 'y' inválido"); return }
        }

        val metrics = resources.displayMetrics
        if (x < 0 || y < 0 || x > metrics.widthPixels || y > metrics.heightPixels) {
            Log.e(TAG, "tapCoordenadas: fuera de pantalla x=$x y=$y")
            return
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 100L) // ✅ 100ms para mejor respuesta
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        ejecutarGesture(gesture)
    }

    private fun escribirTexto(params: Map<String, Any>?) {
        val texto = params?.get("texto") as? String ?: return
        val root  = rootInActiveWindow ?: return

        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusNode != null) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
            val ok = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "${if (ok) "" else ""} escribirTexto con foco: '$texto'")
            return
        }

        val editableNode = lastSnapshot?.elements?.firstOrNull { it.isEditable }
        if (editableNode != null) {
            tapCoordenadas(mapOf("x" to editableNode.centerX, "y" to editableNode.centerY))
            handler.postDelayed({
                val rootRetry  = rootInActiveWindow ?: return@postDelayed
                val focusRetry = rootRetry.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusRetry != null) {
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
                    val ok = focusRetry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Log.d(TAG, "  ${if (ok) "" else ""} escribirTexto retry: '$texto'")
                }
            }, 600L)
        }
    }

    private fun buscarElementoPorTexto(textoBuscado: String): ScreenElement? {
        val snapshot = lastSnapshot ?: run { actualizarSnapDePantalla(); lastSnapshot } ?: return null
        val normalizado = textoBuscado.lowercase().trim()
        snapshot.elements.find { it.text?.lowercase() == normalizado }?.let { return it }
        snapshot.elements.find { it.contentDescription?.lowercase() == normalizado }?.let { return it }
        snapshot.elements.find { (it.text?.lowercase() ?: "").contains(normalizado) }?.let { return it }
        return snapshot.elements
            .filter { it.isClickable || it.isEditable }
            .map { it to calcularSimilitud(normalizado, it.getSearchableText()) }
            .filter { it.second > 0.6 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun encontrarNodoPorTexto(root: AccessibilityNodeInfo, texto: String): AccessibilityNodeInfo? {
        val textoLower = texto.lowercase().trim()

        val byText = root.findAccessibilityNodeInfosByText(texto)
        if (byText.isNotEmpty()) return byText[0]

        fun buscarRecursivo(nodo: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (nodo == null) return null
            val nodeText = (nodo.text?.toString() ?: nodo.contentDescription?.toString() ?: "").lowercase()
            if (nodeText.contains(textoLower) && (nodo.isClickable || nodo.parent?.isClickable == true)) {
                return nodo
            }
            for (i in 0 until nodo.childCount) {
                val resultado = buscarRecursivo(nodo.getChild(i))
                if (resultado != null) return resultado
            }
            return null
        }
        return buscarRecursivo(root)
    }

    private fun encontrarCampoEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusNode != null && focusNode.isEditable) return focusNode

        fun buscarRecursivo(nodo: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (nodo == null) return null
            if (nodo.isEditable) return nodo
            for (i in 0 until nodo.childCount) {
                val resultado = buscarRecursivo(nodo.getChild(i))
                if (resultado != null) return resultado
            }
            return null
        }
        return buscarRecursivo(root)
    }

    private fun calcularSimilitud(s1: String, s2: String): Double {
        val longer  = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0
        return (longer.length - calcularLevenshtein(longer, shorter)) / longer.length.toDouble()
    }

    private fun calcularLevenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }
    private fun cerrarDialogoConfirmacionSamsung() {
        // ════════════════════════════════════════════════════════════
        //  CERRAR DIÁLOGO DE CONFIRMACIÓN DE SAMSUNG (VERSIÓN ULTRA COMPLETA)
        // ════════════════════════════════════════════════════════════

        val candidatos = listOf(
            "Aceptar", "Guardar", "OK", "", "", "Listo", "Done",
            "Aceptar foto", "Guardar foto", "Confirmar", "Continuar",
            // Samsung One UI
            "Guardar en la galería", "Guardar en Galería", "Guardar",
            "Aceptar y guardar", "Hecho", "Finalizar",
            "Guardar en galería", "Galería",
            "Reintentar", "Reintentar foto", "Tomar de nuevo", "Cancelar",
            //  NUEVOS (Samsung A/M series)
            "Aceptar y guardar en galería", "Guardar en dispositivo",
            "Guardar en la tarjeta SD", "Guardar ahora",
            "Sí", "Si", "Confirmar foto"  // Algunas versiones usan estos
        )

        handler.postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                Log.d(TAG, " No hay ventana activa, probablemente ya se cerró el diálogo.")
                return@postDelayed
            }

            var nodoEncontrado: AccessibilityNodeInfo? = null

            // 1. Buscar por TEXTO
            for (candidato in candidatos) {
                nodoEncontrado = encontrarNodoPorTexto(root, candidato)
                if (nodoEncontrado != null) {
                    Log.d(TAG, " Encontrado por texto: '$candidato'")
                    break
                }
            }

            // 2. Buscar por IDs de Samsung (MÁS COMPLETO)
            if (nodoEncontrado == null) {
                val idsSamsung = listOf(
                    "com.sec.android.app.camera:id/ok_button",
                    "com.sec.android.app.camera:id/done_button",
                    "com.sec.android.app.camera:id/btn_done",
                    "com.sec.android.app.camera:id/button_confirm",
                    "com.sec.android.app.camera:id/confirm_button",
                    "com.sec.android.app.camera:id/save_button",
                    "com.sec.android.app.camera:id/okay_button",        //  Nuevo
                    "com.sec.android.app.camera:id/btn_ok",             //  Nuevo
                    "com.sec.android.app.camera:id/positive_button",    //  Nuevo
                    "com.google.android.GoogleCamera:id/done_button",
                    "com.android.camera2:id/done_button"
                )
                for (id in idsSamsung) {
                    val nodos = root.findAccessibilityNodeInfosByViewId(id)
                    if (nodos.isNotEmpty()) {
                        nodoEncontrado = nodos[0]
                        Log.d(TAG, " Encontrado por ID: $id")
                        break
                    }
                }
            }

            // 3. Si encontramos el botón, hacer clic
            if (nodoEncontrado != null) {
                var nodoClick = nodoEncontrado
                while (nodoClick != null && !nodoClick.isClickable) {
                    nodoClick = nodoClick.parent
                }
                if (nodoClick != null && nodoClick.isClickable) {
                    nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, " Diálogo de confirmación cerrado (clic en botón)")
                } else {
                    val bounds = Rect()
                    nodoEncontrado.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
                        Log.d(TAG, " Tap en coordenadas del botón (fallback)")
                    }
                }
            } else {
                // 4. ÚLTIMO RECURSO: Presionar BACK (solo si estamos seguros de que es un diálogo)
                // Verificamos si el paquete activo es la cámara para no cerrar la app
                if (root.packageName?.contains("camera") == true) {
                    Log.d(TAG, " No se encontró botón, presionando BACK para cerrar diálogo")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    Log.d(TAG, " BACK presionado")
                } else {
                    Log.d(TAG, " No hay diálogo de confirmación visible. Foto guardada automáticamente.")
                }
            }

            //  La cámara queda abierta para más fotos

        }, 600) // esperar 600ms para que el diálogo aparezca
    }
    private fun actualizarSnapDePantalla(force: Boolean = false) {
        val root = rootInActiveWindow ?: return

        val elementos = mutableListOf<ScreenElement>()
        val packageName = root.packageName?.toString() ?: "unknown"

        fun escanearNodo(nodo: AccessibilityNodeInfo?, profundidad: Int = 0) {
            if (nodo == null || profundidad > 20) return

            val text = nodo.text?.toString()
            val contentDesc = nodo.contentDescription?.toString()
            val hint = nodo.hintText?.toString()
            val className = nodo.className?.toString()
            val bounds = Rect()
            nodo.getBoundsInScreen(bounds)

            val isClickable = nodo.isClickable
            val isEditable = nodo.isEditable
            val isScrollable = nodo.isScrollable
            val isCheckable = nodo.isCheckable

            //  CAPTURAR TODO LO CLICKEABLE/EDITABLE/SCROLLABLE
            val tieneContenido = !text.isNullOrBlank() ||
                    !contentDesc.isNullOrBlank() ||
                    !hint.isNullOrBlank()
            val esInteractivo = isClickable || isEditable || isCheckable || isScrollable

            if (tieneContenido || esInteractivo) {
                //  INFORMACIÓN UNIVERSAL
                val searchableText = getSearchableText(
                    text = text,
                    contentDesc = contentDesc,
                    hint = hint,
                    className = className
                )

                elementos.add(ScreenElement(
                    id = UUID.randomUUID().toString(),
                    viewId = nodo.viewIdResourceName?.takeIf { it.isNotBlank() },
                    className = className,
                    text = text,
                    contentDescription = contentDesc,
                    hintText = hint,
                    bounds = bounds,
                    centerX = bounds.centerX(),
                    centerY = bounds.centerY(),
                    isClickable = isClickable,
                    isCloneable = isClickable,
                    isEditable = isEditable,
                    isCheckable = isCheckable,
                    isChecked = nodo.isChecked,
                    isScrollable = isScrollable,
                    importance = calculateImportance(isClickable, text, contentDesc),
                    //  INFORMACIÓN UNIVERSAL
                    isPassword = nodo.isPassword,
                    isEnabled = nodo.isEnabled,
                    isFocusable = nodo.isFocusable,
                    isLongClickable = nodo.isLongClickable,
                    parentText = nodo.parent?.text?.toString(),
                    siblingTexts = emptyList(), // Puedes obtenerlo del parent si quieres
                    availableActions = mutableListOf<String>().apply {
                        if (isClickable) add("tap")
                        if (isEditable) add("escribir")
                        if (isScrollable) add("scroll")
                        if (isCheckable) add("toggle")
                    },
                    visibility = if (nodo.isVisibleToUser) "visible" else "invisible",
                ))
            }
            for (i in 0 until nodo.childCount) {
                escanearNodo(nodo.getChild(i), profundidad + 1)
            }
        }
        escanearNodo(root)
        lastSnapshot = ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            activityName = root.className?.toString(),
            elements = elementos,
            totalElements = elementos.size,
            clickableElements = elementos.count { it.isClickable },
            editableElements = elementos.count { it.isEditable },
            scrollableContainers = elementos.count { it.isScrollable }
        )
    }
    //  FUNCIÓN UNIVERSAL: Extraer texto buscable
    private fun getSearchableText(
        text: String?,
        contentDesc: String?,
        hint: String?,
        className: String?
    ): String {
        return listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            contentDesc?.takeIf { it.isNotBlank() },
            hint?.takeIf { it.isNotBlank() },
            extraerTextoDelNombreClase(className)
        ).joinToString(" | ").trim()
    }
    private fun extraerTextoDelNombreClase(className: String?): String? {
        if (className.isNullOrBlank()) return null

        // De "android.widget.Button" → "Button"
        val simpleName = className.split(".").last()

        return when {
            simpleName.contains("Button", ignoreCase = true) -> "Botón"
            simpleName.contains("Text", ignoreCase = true) -> "Campo de texto"
            simpleName.contains("Image", ignoreCase = true) -> "Imagen"
            simpleName.contains("Edit", ignoreCase = true) -> "Campo editable"
            simpleName.contains("List", ignoreCase = true) -> "Lista"
            simpleName.contains("Card", ignoreCase = true) -> "Tarjeta"
            simpleName.contains("View", ignoreCase = true) -> "Elemento"
            else -> null
        }
    }
    private fun calculateImportance(
        isClickable: Boolean,
        text: String?,
        contentDesc: String?
    ): Int {
        var score = 0
        if (isClickable) score += 40
        if (!text.isNullOrBlank()) score += 30
        if (!contentDesc.isNullOrBlank()) score += 20
        if (text?.length ?: 0 > 5) score += 10
        return score.coerceIn(0, 100)
    }

    private fun reportarAlServidor(
        texto: String, intencion: String, payload: List<ActionDto>,
        exito: Boolean, falla: String? = null
    ) {
        val reporte = ReporteFeedback(
            texto_original      = texto,
            intencion_detectada = intencion,
            json_generado       = payload,
            resultado           = if (exito) "EXITO" else "ERROR",
            error_detalle       = falla
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RetrofitClient.feedbackApi.enviarFeedback(reporte)
            } catch (e: Exception) {
                Log.e(TAG, " Feedback: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(sendMessageReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(splitScreenReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(writeMessageReceiver) } catch (_: Exception) {}
        try {
            unregisterReceiver(actionsReceiver)
            Log.d(TAG, " BroadcastReceiver desregistrado")
        } catch (e: Exception) {
            Log.e(TAG, " Error desregistrando receiver: ${e.message}")
        }
        try { unregisterReceiver(callButtonReceiver) } catch (_: Exception) {}
        unregisterReceiver(splitScreenReceiver)
        handler.removeCallbacks(stepRunnable)
        currentActions = null
        super.onDestroy()
        instance = null
    }
}