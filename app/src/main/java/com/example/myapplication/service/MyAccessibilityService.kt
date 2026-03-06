package com.example.myapplication.service
import android.R
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Context
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
import kotlin.math.min

class MyAccessibilityService : AccessibilityService(){

    private val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var textoUltimaOrden: String=""
    private var intencionUltimaOrden: String="desconocida"

    //creamos chache para evitar reescanear constantemente
    private var lastSnapshot: ScreenSnapshot? = null
    private var lastSnapshotTime : Long = 0
    private val CACHE_DURATION = 2000L
    private var snapshotJob: Job? = null
    private var lastFingerprint: String = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ultimoTextoEscrito: String = "" +
            ""
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ACCES", "Accessibility conectado")

        //registra el receptor para recibir las acciones
        val filter = IntentFilter(ACTION_EXECUTE)
        registerReceiver(actionsReceiver, filter, RECEIVER_NOT_EXPORTED)
    }
// escucha y decide cuando es el momento adecuado para capturar informacion

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        // 1. Filtramos solo eventos que realmente signifiquen un cambio de acción del usuario
        val eventosClave = listOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, // Cambio de App/Actividad
            AccessibilityEvent.TYPE_VIEW_SCROLLED,        // El usuario se movió
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED // Cambios internos
        )
        if (eventType in eventosClave) {
            // 2. Aplicamos DEBOUNCE:
            // Si llega un nuevo evento en menos de 1 segundo, cancelamos el escaneo anterior
            // y volvemos a esperar. Esto evita escanear 50 veces por segundo.
            snapshotJob?.cancel()
            snapshotJob = serviceScope.launch {
                delay(1000L) // Espera 1 segundo de inactividad
// 3. Verificación de seguridad: No escanear si la ventana es nula
                if (rootInActiveWindow != null) {
                    actualizarSnapDePantalla()
                }
            }
        }
    }
    override fun onInterrupt() {}

    //broadcast para recivir acciones
    private val actionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra("actions_json") ?: return
            textoUltimaOrden = intent.getStringExtra("texto_original") ?: "orden sin texto"
            intencionUltimaOrden = intent.getStringExtra("intencion_original") ?: "desconocida"

            Log.d("ACCESS", "📨 Broadcast recibido: $json")

            // ── CRÍTICO: ejecutar SIEMPRE en el Main Looper ──────────────
            // Sin esto, los gestos de accesibilidad fallan silenciosamente
            handler.post {
                ejecutarAcciones(json)
            }
        }
    }

    private fun actualizarSnapDePantalla(force: Boolean = false) {
        val root = rootInActiveWindow ?: return
        val currentFingerprint = "${root.packageName}-${root.childCount}-${System.currentTimeMillis() / 500}"

        if (!force && currentFingerprint == lastFingerprint) {
            Log.d("ACCESS", "Snapshot igual, skip")
            return
        }
        lastFingerprint = currentFingerprint

        val elementos = mutableListOf<ScreenElement>()
        val packageName = root.packageName?.toString() ?: "unknown"
        val activityName = root.className?.toString()
        var clickableCount = 0
        var editableCount = 0
        var scrollableCount = 0

        fun escanearNodo(nodo: AccessibilityNodeInfo?, profundidad: Int = 0) {
            if (nodo == null || profundidad > 20) return

            val viewId = nodo.viewIdResourceName ?: ""
            if (viewId.contains("transcriptionTextView") ||
                viewId.contains("jarvisOrb") ||
                viewId.contains("overlayOrb") ||
                viewId.contains("overlayTranscription") ||
                viewId.contains("micButton")) return

            val text = nodo.text?.toString()
            val contentDesc = nodo.contentDescription?.toString()
            val hint = nodo.hintText?.toString()
            val className = nodo.className?.toString()
            val bounds = Rect()
            nodo.getBoundsInScreen(bounds)

            val isClickable    = nodo.isClickable
            val isLongClickable = nodo.isLongClickable
            val isCheckable    = nodo.isCheckable
            val isChecked      = nodo.isChecked
            val isFocusable    = nodo.isFocusable
            val isEditable     = nodo.isEditable
            val isPassword     = nodo.isPassword
            val isEnabled      = nodo.isEnabled
            val isScrollable   = nodo.isScrollable

            val parentText = nodo.parent?.text?.toString()
            val siblingTexts = mutableListOf<String>()
            nodo.parent?.let { parent ->
                for (i in 0 until parent.childCount) {
                    parent.getChild(i)?.text?.toString()?.let { siblingTexts.add(it) }
                }
            }

            val actions = mutableListOf<String>()
            if (isClickable) actions.add("click")
            if (isLongClickable) actions.add("long_click")
            if (isScrollable) actions.add("scroll")
            if (isEditable) actions.add("set_text")
            if (isCheckable) actions.add("toggle")

            val importance = ScreenElement.calculateImportance(isClickable, text, contentDesc, className)
            val visibility = if (nodo.isVisibleToUser) "visible" else "invisible"

            val tieneContenido = !text.isNullOrBlank() || !contentDesc.isNullOrBlank() || !hint.isNullOrBlank()
            val esInteractivo = isClickable || isEditable || isCheckable || isScrollable

            if (tieneContenido || esInteractivo) {
                elementos.add(ScreenElement(
                    id = UUID.randomUUID().toString(),
                    viewId = viewId.takeIf { it.isNotBlank() },
                    className = className,
                    text = text,
                    contentDescription = contentDesc,
                    hintText = hint,
                    bounds = bounds,
                    centerX = bounds.centerX(),
                    centerY = bounds.centerY(),
                    isClickable = isClickable,
                    isCloneable = isClickable,
                    isLongClickable = isLongClickable,
                    isCheckable = isCheckable,
                    isChecked = isChecked,
                    isFocusable = isFocusable,
                    isEditable = isEditable,
                    isPassword = isPassword,
                    isEnabled = isEnabled,
                    isScrollable = isScrollable,
                    parentText = parentText,
                    siblingTexts = siblingTexts,
                    availableActions = actions,
                    importance = importance,
                    visibility = visibility
                ))
                if (isClickable) clickableCount++
                if (isEditable) editableCount++
                if (isScrollable) scrollableCount++
            }

            for (i in 0 until nodo.childCount) {
                escanearNodo(nodo.getChild(i), profundidad + 1)
            }
        }

        escanearNodo(root)

        val elementosUtiles = elementos.filter { it.isClickable || it.isEditable || it.importance > 60 }

        lastSnapshot = ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            activityName = activityName,
            elements = elementosUtiles,
            totalElements = elementosUtiles.size,
            clickableElements = clickableCount,
            editableElements = editableCount,
            scrollableContainers = scrollableCount
        )

        com.example.myapplication.core.ScreenMemory.lastSnapshot = lastSnapshot
        com.example.myapplication.core.ScreenMemory.lastSeenTexts = lastSnapshot!!.toContextList()

        Log.d("JARVIS_SNAPSHOT", "╔════════ SNAPSHOT ACTUALIZADO ════════╗")
        Log.d("JARVIS_SNAPSHOT", "║ APP: $packageName")
        Log.d("JARVIS_SNAPSHOT", "║ ELEMENTOS: ${elementosUtiles.size}")
        Log.d("JARVIS_SNAPSHOT", "║ INTERACTIVOS: $clickableCount Clics | $editableCount Textos")
        Log.d("JARVIS_SNAPSHOT", "╚════════════════════════════════════════╝")

    }
    /**
     * Búsqueda inteligente de elementos
     * Reemplaza tu lógica de fuzzy matching básica
     */
    private fun buscarElementoPorTexto(textoBuscado: String): ScreenElement? {
        val snapshot = lastSnapshot ?: run { actualizarSnapDePantalla(); lastSnapshot } ?: return null
        val normalizado = textoBuscado.lowercase().trim()

        // 1. Coincidencia exacta en texto
        snapshot.elements.find { it.text?.lowercase() == normalizado }?.let { return it }
        // 2. Coincidencia exacta en contentDescription
        snapshot.elements.find { it.contentDescription?.lowercase() == normalizado }?.let { return it }
        // 3. Contiene el texto buscado
        snapshot.elements.find { (it.text?.lowercase() ?: "").contains(normalizado) }?.let { return it }
        // 4. Búsqueda en texto combinado
        snapshot.elements.find { it.getSearchableText().contains(normalizado) }?.let { return it }
        // 5. Fuzzy matching ≥ 60%
        return snapshot.elements
            .filter { it.isClickable || it.isEditable }
            .map { it to calcularSimilitud(normalizado, it.getSearchableText()) }
            .filter { it.second > 0.6 }
            .maxByOrNull { it.second }
            ?.first
    }

    //calcular el porcentaje de similitud entre 0.0 y 1.0
    private fun calcularSimilitud(s1: String, s2: String): Double {
        val longer  = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0
        return (longer.length - calcularLevenshtein(longer, shorter)) / longer.length.toDouble()
    }
    /**
     * Algoritmo de Levenshtein:
     * Cuenta cuántos cambios (insertar, borrar o cambiar letras)
     * se necesitan para convertir una palabra en otra.
     */
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
    private var currentActions: List<ActionDto>? = null
    private var currentIndex = 0
    private val stepRunnable = object : Runnable {
        override fun run() {
            val actions = currentActions ?: return
            if (currentIndex >= actions.size) {
                Log.d("ACCESS", "✅ Todas las acciones completadas")
                currentActions = null
                return
            }
            val accion = actions[currentIndex++]
            var waitTime = 1000L // Tiempo por defecto para clics y scroll
            var exitoAccion = true
            var detalleError: String? = null
            Log.d("ACCESS_EXEC", "┌─ Acción [${currentIndex-1}/${actions.size}] ─────────────")
            Log.d("ACCESS_EXEC", "│  tipo   : ${accion.tipo}")
            Log.d("ACCESS_EXEC", "│  params : ${accion.params}")
            Log.d("ACCESS_EXEC", "└────────────────────────────────────────────────────")
            when (accion.tipo) {
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
                        this@MyAccessibilityService.startActivity(androidIntent)
                        Log.d("ACCESS_EXEC", "  ✅ Intent lanzado: $intentAction")
                        exitoAccion = true
                        waitTime = 300L  // mínimo para que Android procese el intent
                    } catch (e: Exception) {
                        Log.e("ACCESS_EXEC", "  ❌ Intent falló: ${e.message}")
                        val pkg = accion.pkg ?: accion.params?.get("package") as? String
                        if (!pkg.isNullOrBlank()) {
                            ActionExecutor.openApp(this@MyAccessibilityService, pkg)
                            Log.w("ACCESS_EXEC", "  🔄 Fallback a open_app: $pkg")
                            waitTime = 500L
                        } else {
                            exitoAccion = false
                            detalleError = "Intent falló y sin package: ${e.message}"
                        }
                    }
                }
                "adjust_volume", "adjust_brightness" -> {
                    val direction = accion.params?.get("direction") as? String ?: "up"
                    val steps = when (val raw = accion.params?.get("steps")) {
                        is Number -> raw.toInt().coerceIn(1, 15)
                        is String -> raw.toIntOrNull()?.coerceIn(1, 15) ?: 1
                        else -> 1
                    }
                    val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    val flag = android.media.AudioManager.FLAG_SHOW_UI
                    val adjustFlag = if (direction == "up") android.media.AudioManager.ADJUST_RAISE
                    else android.media.AudioManager.ADJUST_LOWER
                    repeat(steps) { audioManager.adjustVolume(adjustFlag, if (it == 0) flag else 0) }
                    Log.d("ACCESS_EXEC", "  ✅ Volumen ${direction} x$steps")
                    exitoAccion = true
                    waitTime = 200L
                }
                // Dentro del when(accion.tipo) en stepRunnable, AGREGA estos casos:
                "send_whatsapp" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    val message = accion.params?.get("message") as? String ?: ""
                    Log.d("ACCESS", "📱 send_whatsapp → contacto='$contact' mensaje='$message'")
                    ActionExecutor.sendWhatsAppMessage(this@MyAccessibilityService, contact, message)
                    waitTime = 4000L  // dar tiempo a que WhatsApp abra el chat
                }

                "send_sms" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    val message = accion.params?.get("message") as? String ?: ""
                    Log.d("ACCESS", "💬 send_sms → contacto='$contact' mensaje='$message'")
                    ActionExecutor.sendSms(this@MyAccessibilityService, contact, message)
                    waitTime = 2000L
                }

                "call_contact" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    Log.d("ACCESS", "📞 call_contact → contacto='$contact'")
                    ActionExecutor.callContact(this@MyAccessibilityService, contact)
                    waitTime = 3000L
                }
                "open_app" -> {
                    val pkg = accion.params?.get("package") as? String ?: ""
                    ActionExecutor.openApp(this@MyAccessibilityService, pkg)

                    // ─── CRÍTICO: resetear fingerprint y forzar re-scan
                    // La app nueva cargará y disparará onAccessibilityEvent,
                    // pero forzamos también un escaneo manual tras 2.5s
                    waitTime = 2500L
                    handler.postDelayed({
                        lastFingerprint = ""          // fuerza re-scan ignorando caché
                        actualizarSnapDePantalla()
                        Log.d("ACCESS", "Re-scan forzado post open_app de $pkg")
                    }, 2000L)
                }
                "global_action" -> {
                    Log.d("ACCESS_EXEC", "  → global_action: ${accion.params?.get("action")}")
                    ejecutarAccionGlobal(accion.params)
                    waitTime = 800L
                }
                "scroll" -> {
                    val direction = accion.params?.get("direction") as? String ?: "down"
                    scroll(direction)
                    waitTime = 1200L
                }
                "tap" -> {
                    Log.d("ACCESS_EXEC", "  → tap coords: x=${accion.params?.get("x")} y=${accion.params?.get("y")}")
                    tapCoordenadas(accion.params)
                    waitTime = 1000L
                }
                "ocr_tap" -> {
                    val textoABuscar = accion.params?.get("texto") as? String ?: ""
                    // Si el snapshot es viejo (>3s), forzar re-scan antes de buscar
                    // ─── CONFIRMACIÓN ANTES DE ENVIAR ───────────────────
                    val esBotoEnviar = textoABuscar.lowercase() in setOf(
                        "enviar", "send", "enviar mensaje", "enviar ahora",
                        "enviar mensaje de voz", "enviar ahora", "submit",
                    )
                    if (esBotoEnviar && ultimoTextoEscrito.isNotBlank()) {
                        pausarParaConfirmacion(textoABuscar)
                        return   // ← NO avanza; el callback lo reanuda o cancela
                    }
                    val snapshotAge = System.currentTimeMillis() - (lastSnapshot?.timestamp ?: 0)
                    if (snapshotAge > 3000L || lastSnapshot == null) {
                        lastFingerprint = ""
                        actualizarSnapDePantalla()
                        Log.d("ACCESS", "Re-scan forzado antes de ocr_tap '$textoABuscar'")
                    }
                    val elemento = buscarElementoPorTexto(textoABuscar)
                    if (elemento != null) {
                        Log.d("ACCESS", "ocr_tap: encontrado '${elemento.getSearchableText()}'")
                        tapCoordenadas(mapOf("x" to elemento.centerX, "y" to elemento.centerY))
                    } else {
                        Log.w("ACCESS", "ocr_tap: '$textoABuscar' NO encontrado")
                        exitoAccion = false
                        detalleError = "Elemento '$textoABuscar' no visible en pantalla"
                    }
                    waitTime = 1000L
                }
                "type_text" -> {
                    val texto = accion.params?.get("texto") as? String ?: ""
                    ultimoTextoEscrito = texto          // guardamos para confirmación
                    escribirTexto(accion.params)
                    waitTime = 800L
                }

                // ─── NUEVO: open_notification / reply_notification ─────
                "open_notification" -> {
                    val pkg = accion.params?.get("package") as? String ?: ""
                    JarvisNotificationListener.openNotification(this@MyAccessibilityService, pkg)
                    waitTime = 1200L
                }

                "reply_notification" -> {
                    val pkg   = accion.params?.get("package") as? String ?: ""
                    val texto = accion.params?.get("texto") as? String ?: ""
                    if (texto.isNotBlank()) {
                        JarvisNotificationListener.replyToNotification(pkg, texto)
                    }
                    waitTime = 1000L
                }

            }
             reportarAlServidor(textoUltimaOrden, intencionUltimaOrden, actions, exitoAccion, detalleError)
              // Programamos la siguiente acción con el tiempo de espera calculado
            handler.postDelayed(this, waitTime)
        }
    }

    // ── Pausa la cadena y pide confirmación por voz ──────────────────
    private fun pausarParaConfirmacion(textoBoton: String) {
        val mensaje = ultimoTextoEscrito
        val pregunta = "¿Enviar el mensaje: $mensaje?"
        Log.d("ACCESS", "Confirmación pendiente: '$pregunta'")

        // Callback que retoma o cancela la cadena según el usuario responda
        ActionExecutor.onConfirmacionPendiente = { confirmado ->
            if (confirmado) {
                Log.d("ACCESS", "Confirmado → ejecutando tap Enviar")
                val elemento = buscarElementoPorTexto(textoBoton)
                if (elemento != null) {
                    tapCoordenadas(mapOf("x" to elemento.centerX, "y" to elemento.centerY))
                }
                ultimoTextoEscrito = ""
                handler.postDelayed(stepRunnable, 800L)    // continúa la cadena
            } else {
                Log.d("ACCESS", "Cancelado por el usuario")
                ultimoTextoEscrito = ""
                currentActions = null                       // cancela todo
            }
        }

        // Broadcast al JarvisVoiceController para que pregunte por voz
        val intent = Intent("JARVIS.PEDIR_CONFIRMACION").apply {
            putExtra("pregunta", pregunta)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
    /**
     * Extrae el nombre del contacto de una frase como:
     * "llama a hermana" → "hermana"
     * "puedes llamar a mamá" → "mamá"
     * "llama al 911" → "911"
     */
    private fun extraerNombreDeTexto(texto: String): String {
        val t = texto.lowercase().trim()
        // Patrones comunes de llamada
        val patrones = listOf(
            Regex("llam(?:a|ar) al? (.+)"),
            Regex("llam(?:a|ar) (?:a )?(?:mi )?(.+)"),
            Regex("comunica(?:me)? con (.+)"),
            Regex("marca(?:r)? (?:a )?(.+)"),
            Regex("(?:puedes? )?llam(?:a|ar) (?:a )?(?:mi )?(.+)")
        )
        for (patron in patrones) {
            val match = patron.find(t)
            if (match != null) {
                return match.groupValues[1]
                    .replace(Regex("^(a |mi |al )"), "")
                    .trim()
            }
        }
        // Si no matchea ningún patrón, devolvemos el texto limpio
        return t.replace(Regex("^(llama a |llama al |llama |llamar a )"), "").trim()
    }
    private var retryCount = 0
    //    private fun realizarTapPorTextoConRetorno(texto: String): Boolean {
//        val root = rootInActiveWindow ?: return false
//        val nodes = root.findAccessibilityNodeInfosByText(texto)
//
//        if (nodes.isNotEmpty()) {
//            val nodo = nodes[0]
//            if (intentarClick(nodo)) return true
//
//            // Si no es clicable, intentamos por coordenadas
//            val rect = android.graphics.Rect()
//            nodo.getBoundsInScreen(rect)
//            tapCoordenadas(mapOf("x" to rect.centerX(), "y" to rect.centerY()))
//            return true
//        }
//        return false
//    }
private fun ejecutarAcciones(json: String){
    handler.removeCallbacks(stepRunnable)

    val listType = object : TypeToken<List<ActionDto>>() {}.type
        currentActions = Gson().fromJson(json, listType)
        currentIndex = 0
        retryCount = 0  // ✅ reset
    //si el json esta bacio excane ala pantalla
    if (currentActions.isNullOrEmpty()){
        Log.d("ACCESS","iniciadno escaneo de pantalla")
        actualizarSnapDePantalla()
        lastSnapshot?.elements?.take(10)?.forEach {
            Log.i("ACCESS", "  · ${it.getSearchableText()} [${it.className}]")
        }
    }else{
        Log.d("ACCESS_EXEC", "▶ Iniciando secuencia de ${currentActions!!.size} acciones")
        currentActions!!.forEachIndexed { i, a ->
            Log.d("ACCESS_EXEC", "  [$i] ${a.tipo} → ${a.params}")
        }
        handler.post(stepRunnable)
    }
}
    private fun ejecutarAccionGlobal(params: Map<String, Any>?) {
        val actionType = params?.get("action") as? String ?: return
        val globalAction = when (actionType) {
            "home"          -> GLOBAL_ACTION_HOME
            "back"          -> GLOBAL_ACTION_BACK
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "recents"       -> GLOBAL_ACTION_RECENTS
            else            -> { Log.w("ACCESS", "Acción global desconocida: $actionType"); return }
        }
        performGlobalAction(globalAction)
        Log.d("ACCESS_EXEC", "  ✅ Global action ejecutada: $actionType")
    }


    fun scroll(direction: String) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f

        // down: arrastra de abajo hacia arriba (contenido sube)
        // up:   arrastra de arriba hacia abajo (contenido baja)
        val (yStart, yEnd) = when (direction) {
            "up"   -> Pair(metrics.heightPixels * 0.25f, metrics.heightPixels * 0.75f)
            "down" -> Pair(metrics.heightPixels * 0.75f, metrics.heightPixels * 0.25f)
            "left" -> Pair(metrics.widthPixels * 0.8f, metrics.widthPixels * 0.2f)
            "right"-> Pair(metrics.widthPixels * 0.2f, metrics.widthPixels * 0.8f)
            else   -> Pair(metrics.heightPixels * 0.75f, metrics.heightPixels * 0.25f)
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
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d("ACCESS", "Gesture completado")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.e("ACCESS", "Gesture cancelado")
                }
            },
            handler
        )
    }
    private fun tapCoordenadas(params: Map<String, Any>?) {
        val x = when (val raw = params?.get("x")) {
            is Number -> raw.toFloat()
            else -> { Log.w("ACCESS", "tapCoordenadas: 'x' nulo o tipo inválido"); return }
        }
        val y = when (val raw = params?.get("y")) {
            is Number -> raw.toFloat()
            else -> { Log.w("ACCESS", "tapCoordenadas: 'y' nulo o tipo inválido"); return }
        }

        val metrics = resources.displayMetrics
        if (x < 0 || y < 0 || x > metrics.widthPixels || y > metrics.heightPixels) {
            Log.e("ACCESS", "tapCoordenadas: coordenadas fuera de pantalla x=$x y=$y")
            return
        }
        val path = Path().apply { moveTo(x, y) }
        // Un tap es un trazo de duración muy corta (ej. 50ms)
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        // ─ SOLUCIÓN: Usar llaves { } para Unit y pasar el handler correctamente ──
        ejecutarGesture(gesture)
    }
//    private fun tapPorTexto(params: Map<String, Any>?) {
//        val texto = params?.get("texto") as? String ?: return
//        val root = rootInActiveWindow ?: return
//
//        val nodes = root.findAccessibilityNodeInfosByText(texto)
//        if (nodes.isNotEmpty()) {
//            val nodoCualquiera = nodes[0]
//            if (!intentarClick(nodoCualquiera)) {
//                val rect = android.graphics.Rect()
//                nodoCualquiera.getBoundsInScreen(rect)
//                tapCoordenadas(mapOf("x" to rect.centerX(), "y" to rect.centerY()))
//            }
//        }
//    }
private fun escribirTexto(params: Map<String, Any>?) {
    val texto = params?.get("texto") as? String ?: return
    val root = rootInActiveWindow ?: return

    // Intento 1: campo con foco activo
    val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (focusNode != null) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
        val ok = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d("ACCESS_EXEC", "  ${if (ok) "✅" else "❌"} escribirTexto con foco: '$texto'")
        return
    }
    // Intento 2: buscar primer EditText y darle foco
    Log.w("ACCESS_EXEC", "  ⚠️ Sin foco activo — buscando EditText en snapshot")
    val editableNode = lastSnapshot?.elements?.firstOrNull { it.isEditable }
    if (editableNode != null) {
        Log.d("ACCESS_EXEC", "  → Tapando EditText en (${editableNode.centerX}, ${editableNode.centerY}) para dar foco")
        tapCoordenadas(mapOf("x" to editableNode.centerX, "y" to editableNode.centerY))
        // Escribir después de dar foco (600ms)
        handler.postDelayed({
            val rootRetry = rootInActiveWindow ?: return@postDelayed
            val focusRetry = rootRetry.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusRetry != null) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                focusRetry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
                val ok = focusRetry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d("ACCESS_EXEC", "  ${if (ok) "✅" else "❌"} escribirTexto retry: '$texto'")
            } else {
                Log.e("ACCESS_EXEC", "  ❌ Aún sin foco tras tap — no se pudo escribir '$texto'")
            }
        }, 600L)
    } else {
        Log.e("ACCESS_EXEC", "  ❌ No hay EditText en pantalla para escribir '$texto'")
    }
}
//    private fun intentarClick(node: AccessibilityNodeInfo?): Boolean {
//        var tempNode = node
//        while (tempNode != null) {
//            if (tempNode.isClickable) {
//                return tempNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//            }
//            tempNode = tempNode.parent
//        }
//        return false
//    }

//    //obtener lso datos de pantalla
//    private fun obtenerTextoDePantalla(): String {
//        val root = rootInActiveWindow ?: return "Pantalla vacía"
//        val sb = StringBuilder()
//
//        fun recorrer(node: AccessibilityNodeInfo?) {
//            if (node == null) return
//            if (node.text != null) {
//                sb.append("${node.text} | ")
//            }
//            for (i in 0 until node.childCount) {
//                recorrer(node.getChild(i))
//            }
//        }
//
//        recorrer(root)
//        return sb.toString()
//    }
//funcion para mirar la pantalla anilizar lso nodos ojo del asistnte
//    private fun extraerInfo(): List<Map<String, Any>>{
//        val elementos=mutableListOf<Map<String, Any>>()
//        val textosTemporales = mutableListOf<String>() // lista para el contexto
//        //obtien eel nodo raiz de la ventana actual
//        val root = rootInActiveWindow ?: return elementos
//        //funcion interna recursiva recorre uno po runo
//        fun recorrerNodo(nodo: AccessibilityNodeInfo){
//            if (nodo ==null) return
//            //extraccion del texto intenta sacar texto visible y busca la descripcion
//            val texto = nodo.text?.toString() ?: nodo.contentDescription?.toString()
//            val viewId = nodo.viewIdResourceName ?:""
//            //filtro para qu eno agrege textos que escuch a
//            if (viewId.contains("transcriptionTextView")){
//                return
//            }
//            //solo  ingresa eementos que tengan algo escrito
//            if (!texto.isNullOrBlank()){
//                textosTemporales.add(texto) //guardamos el texro para que jarvis lo lea
//                //crea un objeto rect para guardar las coordenadas
//                val rect = android.graphics.Rect()
//                nodo. getBoundsInScreen(rect)
//                elementos.add(mapOf(
//                    "texto" to texto,// El nombre o contenido
//                    "x" to rect.centerX(),      // Centro horizontal para saber dónde tocar
//                    "y" to rect.centerY(),      // Centro vertical para saber dónde tocar
//                    "clicable" to nodo.isClickable // Indica si es un botón o algo que reacciona al toque
//                ))
//            }
//            //recursividad si tiene hiojs viulve allamar ala funcion para cada uno de lleos
//            for (i in 0 until nodo.childCount){
//                val hijo = nodo.getChild(i)
//                if (hijo != null){
//                    recorrerNodo(hijo)
//                }
//            }
//        }
//        recorrerNodo(root)
//    //actualizamos mmoria global
//        com.example.myapplication.core.ScreenMemory.lastSeenTexts = textosTemporales
//        return elementos
//    }
//    //funcion apra enviar texto
//    private fun escribirTexto(params: Map<String, Any>?){
//        val texto =  params?.get("texto") as? String ?: return
//        val root = rootInActiveWindow ?: return
//
//        //busca el campo que tien el foco que parpadea la rayita  para escribir
//        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
//        if (focusNode != null){
//            val arguments = Bundle()
//            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
//            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
//        }
//    }
    private fun reportarAlServidor(
        texto: String,
        intencion: String,
        payload: List<ActionDto>,
        exito: Boolean,
        falla: String? = null
    ) {
        val resultado = if (exito) "EXITO" else "ERROR"
        Log.d("JARVIS_FEEDBACK", "🚀 Preparando reporte: $resultado para la frase: '$texto'")
        val reporte = ReporteFeedback(
            texto_original = texto,
            intencion_detectada = intencion,
            json_generado = payload,
            resultado = resultado,
            error_detalle = falla
        )
        // Ejecutar en una corrutina para no bloquear el servicio de accesibilidad
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = RetrofitClient.feedbackApi.enviarFeedback(reporte)
                if (response.isSuccessful) {
                    Log.d("JARVIS_LEARNING", "✅ Feedback enviado: Jarvis aprendió algo nuevo")
                }
            } catch (e: Exception) {
                Log.e("JARVIS_LEARNING", "❌ Error al enviar feedback: ${e.message}")
            }
        }
    }
    override fun onDestroy() {
        runCatching { unregisterReceiver(actionsReceiver) }
        handler.removeCallbacks  (stepRunnable)
        currentActions = null
        super.onDestroy()
    }



}