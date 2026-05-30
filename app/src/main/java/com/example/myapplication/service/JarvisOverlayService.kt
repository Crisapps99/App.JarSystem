package com.example.myapplication.service

import android.animation.ValueAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.core.*
import com.example.myapplication.ui.ListeningBarView
import kotlinx.coroutines.*
// Asegúrate de importar la herramienta nativa de transiciones
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.widget.HorizontalScrollView

class JarvisOverlayService : Service(), JarvisUi, PorcupineController {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var listeningBar: ListeningBarView? = null
    private var tvListeningLabel: TextView? = null
    private var tvTranscription: TextView? = null
    private var containerOuter: LinearLayout? = null
    private var frameContainer: FrameLayout? = null
    private var imagesContainer: android.widget.HorizontalScrollView? = null
    private var suggestionsContainer: View? = null
    private lateinit var controller: JarvisVoiceController
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var voiceEngine: ContinuousVoiceEngine
//    private var wakeDetector: VoskWakeWordDetector? = null
    @Volatile private var wakeWordPaused = false
    private var currentJarvisState: JarvisState = JarvisState.IDLE
    private var isOverlayReady = false
    private var isExpanded = false
    private var ultimoResultadoBusqueda: SearchResult? = null
    companion object {
        private const val TAG = "JARVIS_OVERLAY"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nexus_overlay"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 Servicio creado")
        startForeground(NOTIFICATION_ID, createNotification())

        createOverlay()

        mainHandler.postDelayed({
            voiceEngine = ContinuousVoiceEngine(
                context = this,
                onWakeWordDetected = {
                    Log.d(TAG, "🎤 Wake word detectado por ContinuousVoiceEngine!")
                    serviceScope.launch(Dispatchers.Main) {
                        onWakeWordDetected()
                    }
                },
                onFinalResult = { texto ->
                    Log.d(TAG, "Resultado final: $texto")
                    // Aquí puedes manejar comandos directamente si quieres
                },
                onPartialResult = { parcial ->
                    Log.v(TAG, "Parcial: $parcial")
                },
                onRmsChanged = { rms ->
                    mainHandler.post { updateORB(rms) }
                }
            )

            Log.d(TAG, "voiceEngine.isRunning: ${voiceEngine.isRunning()}")
            Log.d(TAG, "voiceEngine.isReady: ${voiceEngine.isReady()}")
            controller = JarvisVoiceController(
                context = this,
                ui = this,
                scope = serviceScope,
                porcupineController = this
            )
            controller.init()

//            setupWakeWordDetection()
            showOverlay()
            tvTranscription?.text = "Di 'Hey Nexus' para activarme"
        }, 900)
    }
    private fun expandContainerForText(text: String) {
        tvTranscription?.maxLines = 15
        isExpanded = true
    }

    private fun restoreContainerSize() {
        tvTranscription?.maxLines = 2
        isExpanded = false
        removerContenedorImagenes()
        removerSugerenciasAnteriores()

    }
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    fun mostrarResultadoBusquedaCompleto(resultado: SearchResult) {
        this.ultimoResultadoBusqueda = resultado

        // 1. Renderizar el texto estructurado con formato HTML
        showText(resultado.content)

        // 2. Si la respuesta contiene imágenes válidas, inflar el carrusel de inmediato
        if (resultado.imageUrls.isNotEmpty()) {
            showImages(resultado.imageUrls)
        }
    }
    fun procesarComandoVozUI(textoHablado: String): Boolean {
        val comando = textoHablado.lowercase().trim()

        // Caso A: Copiar Texto
        if (comando.contains("copiar texto") || comando.contains("copia el texto") || comando.contains("copia texto")) {
            ultimoResultadoBusqueda?.let {
                ejecutarCopiarTexto(it.content)
            } ?: run {
                // Si no hay búsqueda previa, intentamos copiar lo que sea que esté en pantalla
                tvTranscription?.text?.toString()?.let { ejecutarCopiarTexto(it) }
            }
            return true // Comando procesado con éxito
        }

        // Caso B: Ver más enlaces / Abrir Navegador
        if (comando.contains("ver más") || comando.contains("ver mas") || comando.contains("abrir enlaces") || comando.contains("ver fuentes")) {
            ultimoResultadoBusqueda?.let {
                ejecutarAbrirNavegador(it.urls)
            } ?: run {
                showText("No tengo enlaces de búsqueda guardados para mostrar.")
            }
            return true // Comando procesado con éxito
        }

        return false // No es un comando de UI, debe continuar con el flujo normal (buscar en internet / LLM)
    }
    private fun ejecutarCopiarTexto(texto: String) {
        if (texto.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Nexus", texto)
        clipboard.setPrimaryClip(clip)

        // Feedback visual rápido en el mismo asistente
        renderState(JarvisState.IDLE)
        showText("¡Texto copiado al portapapeles! ✓")
    }
    override fun showImages(urls: List<String>) {
        mainHandler.post {
            if (!isOverlayReady) return@post

            // 1. Limpieza de elementos visuales previos
            removerContenedorImagenes()
            removerSugerenciasAnteriores()

            val parentLayout = tvTranscription?.parent as? LinearLayout ?: return@post
            val density = resources.displayMetrics.density

            // 2. Transición animada suave del orbe/contenedor
            frameContainer?.let { container ->
                android.transition.TransitionManager.beginDelayedTransition(container, android.transition.AutoTransition().setDuration(300))
            }
            expandContainerForText("Mostrando galería")

            // 3. Crear el contenedor Scroll Horizontal
            val scrollView = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (140 * density).toInt() // Alto fijo para el carrusel de fotos
                ).apply {
                    topMargin = (12 * density).toInt()
                    bottomMargin = (8 * density).toInt()
                }
            }

            val itemsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // 4. Inyectar las imágenes dinámicamente
            urls.take(6).forEach { url -> // Limitamos a las 6 mejores imágenes
                val imageView = android.widget.ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    // Esquinas redondeadas estéticas estilo Android 13+
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12f * density
                        setColor(Color.parseColor("#2A2A3A")) // Placeholder oscuro mientras carga
                    }
                    background = shape
                    clipToOutline = true

                    layoutParams = LinearLayout.LayoutParams(
                        (180 * density).toInt(), // Ancho de cada foto
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        marginEnd = (10 * density).toInt()
                    }

                    // Carga asíncrona nativa libre de bugs de UI
                    cargarImagenNativa(url, serviceScope, this)

                    // Al darle click a la foto, la abre a pantalla completa en el navegador
                    setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(intent)
                        } catch (e: Exception) { Log.e(TAG, "Error abriendo imagen: ${e.message}") }
                    }
                }
                itemsLayout.addView(imageView)
            }
            scrollView.addView(itemsLayout)

            val chipsIndex = if (suggestionsContainer != null) parentLayout.indexOfChild(suggestionsContainer) else -1
            if (chipsIndex >= 0) {
                parentLayout.addView(scrollView, chipsIndex)
            } else {
                parentLayout.addView(scrollView)
            }
            imagesContainer = scrollView
        }
    }
    // 🛠️ Función Helper para descargar la imagen sin bloquear hilos de ejecución de Android
    private fun cargarImagenNativa(url: String, scope: CoroutineScope, imageView: android.widget.ImageView) {
        scope.launch(Dispatchers.IO) {
            try {
                val stream = java.net.URL(url).openStream()
                val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                Log.e("JARVIS_IMG", "No se pudo renderizar: $url -> ${e.message}")
            }
        }
    }
    private fun removerContenedorImagenes() {
        imagesContainer?.let {
            val parent = it.parent as? ViewGroup
            parent?.removeView(it)
            imagesContainer = null
        }
    }
    private fun ejecutarAbrirNavegador(urls: List<String>) {
        val query = ultimoResultadoBusqueda?.query ?: ""
        val url = if (query.isNotBlank()) {
            "https://www.google.com/search?q=${Uri.encode(query)}"
        } else if (urls.isNotEmpty()) {
            urls.first()
        } else {
            "https://www.google.com"
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            renderState(JarvisState.IDLE)
            showText("Abriendo Google...")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo navegador: ${e.message}")
        }
    }
//    private fun setupWakeWordDetection() {
//        try {
//            wakeDetector = VoskWakeWordDetector(
//                context = this,
//                onWakeWordDetected = {
//                    Log.d(TAG, "🎤 Wake word detectado!")
//                    serviceScope.launch(Dispatchers.Main) {
//                        onWakeWordDetected()
//                    }
//                }
//            )
//
//            wakeDetector?.init(
//                onReady = {
//                    wakeDetector?.start()
//                    Log.i(TAG, "✅ Detector de voz listo")
//                },
//                onError = { msg ->
//                    Log.e(TAG, "❌ Error: $msg")
//                }
//            )
//        } catch (e: Exception) {
//            Log.e(TAG, "Error: ${e.message}")
//        }
//    }

    private fun onWakeWordDetected() {
        showOverlay()
        mainHandler.post {
            restoreContainerSize()
            removerContenedorImagenes()
            removerSugerenciasAnteriores()
            tvTranscription?.text = "¡Te escucho!"
            tvListeningLabel?.text = "ESCUCHANDO"
            tvListeningLabel?.setTextColor(Color.parseColor("#4DEEE9"))
        }

        // Reiniciar cualquier sesión anterior
        if (::controller.isInitialized) {
            // Si hay una sesión activa, detenerla primero
            controller.detenerSesionCompleta()
        }

        serviceScope.launch {
            controller.startInteraction()
        }
    }

    override fun renderState(state: JarvisState) {
        currentJarvisState = state
        mainHandler.post {
            if (!isOverlayReady) return@post

            when (state) {
                JarvisState.LISTENING -> {
                    tvListeningLabel?.text = "ESCUCHANDO"
                    tvListeningLabel?.setTextColor(Color.parseColor("#4DEEE9"))
                    listeningBar?.animateWithEnergy(0.7f)
                }
                JarvisState.THINKING -> {
                    tvListeningLabel?.text = "PENSANDO"
                    tvListeningLabel?.setTextColor(Color.parseColor("#7BD7F8"))
                    listeningBar?.animateWithEnergy(0.4f)
                }
                JarvisState.SPEAKING -> {
                    tvListeningLabel?.text = "HABLANDO"
                    tvListeningLabel?.setTextColor(Color.parseColor("#1DE0A0"))
                    listeningBar?.animateWithEnergy(0.6f)
                }
                JarvisState.IDLE -> {
                    tvListeningLabel?.text = "NEXUS"
                    tvListeningLabel?.setTextColor(Color.WHITE)
                    listeningBar?.animateWithEnergy(0.2f)
                    resumeWakeWordDetection()
                    // ← AGREGAR ESTO
                    mainHandler.postDelayed({
                        if (currentJarvisState == JarvisState.IDLE) {
                            frameContainer?.let { container ->
                                TransitionManager.beginDelayedTransition(container, AutoTransition().setDuration(250))
                            }
                            restoreContainerSize()
                            tvTranscription?.text = "Di 'Hey Nexus' para activarme"
                        }
                    }, 3000)
                }
            }
        }
    }

    override fun showText(text: String) {
        val t = text.lowercase().trim()
        if (listOf("salir", "salir.", "adiós", "adios", "hasta luego").any { it == t }) {
            endSession()
            return
        }

        mainHandler.post {
            if (!isOverlayReady) return@post

            removerSugerenciasAnteriores()
            removerContenedorImagenes()
            frameContainer?.let { container ->
                TransitionManager.beginDelayedTransition(container, AutoTransition().setDuration(300))
            }

            expandContainerForText(text)

            // Estilización avanzada de Markdown a HTML nativo estilo Asistente Premium
            val formatoHtml = text
                .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>") // Negritas
                .replace(Regex("» (.*?)"), "<font color='#4DEEE9'><b>» $1</b></font>") // Títulos de secciones en Aqua
                .replace("•", "<font color='#4DEEE9'>•</font>") // Darle el color Aqua de Nexus a los bullets de las listas
                .replace("\n", "<br/>") // Saltos de línea HTML

            tvTranscription?.run {
                // Un interlineado del 1.25x (como diseño web limpio) para que no se amontone el texto largo
                setLineSpacing(5f * resources.displayMetrics.density, 1.0f)
                this.text = android.text.Html.fromHtml(formatoHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                visibility = View.VISIBLE
            }

            if (text.length > 30 && currentJarvisState != JarvisState.LISTENING) {
                inyectarChipsDeSugerencia(text)
            }

            if (currentJarvisState == JarvisState.IDLE) {
                mainHandler.removeCallbacksAndMessages(null)
                mainHandler.postDelayed({
                    if (currentJarvisState == JarvisState.IDLE) {
                        frameContainer?.let { container ->
                            TransitionManager.beginDelayedTransition(container, AutoTransition().setDuration(250))
                        }
                        removerSugerenciasAnteriores()
                        restoreContainerSize()
                        tvTranscription?.text = "Di 'Hey Nexus' para activarme"
                    }
                }, 15000) // 15 segundos para dar tiempo holgado de lectura de listas largas
            }
        }
    }
    private fun inyectarChipsDeSugerencia(textoBase: String) {
        val parentLayout = tvTranscription?.parent as? LinearLayout ?: return
        val density = resources.displayMetrics.density

        val chipsRow = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        }

        listOf("🌐 Ver más fuentes", "📋 Copiar texto", "🔄 Volver a buscar").forEach { sugerencia ->
            val chip = TextView(this).apply {
                text = sugerencia
                textSize = 13f
                setTextColor(Color.parseColor("#1A1A2E"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(Color.parseColor("#F1F3F4"))
                    setStroke((1 * density).toInt(), Color.parseColor("#D1D5DB"))
                }
                setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
                setOnClickListener {
                    if (!::controller.isInitialized) return@setOnClickListener
                    when (sugerencia) {
                        "🌐 Ver más fuentes" -> {
                            val query = controller.ultimoResultadoBusqueda?.query ?: ""
                            val url = if (query.isNotBlank()) "https://www.google.com/search?q=${Uri.encode(query)}" else "https://www.google.com"
                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
                            catch (e: Exception) { Log.e("OVERLAY", "Error: ${e.message}") }
                        }
                        "📋 Copiar texto" -> {
                            val textoACopiar = controller.ultimoResultadoBusqueda?.content ?: textoBase
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Nexus", textoACopiar))
                            text = "Copiado! ✓"
                            postDelayed({ text = sugerencia }, 1500)
                        }
                        "🔄 Volver a buscar" -> onWakeWordDetected()
                    }
                }
            }
            chipsRow.addView(chip)
        }

        parentLayout.addView(chipsRow)
        suggestionsContainer = chipsRow
    }

    private fun removerSugerenciasAnteriores() {
        suggestionsContainer?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            suggestionsContainer = null
        }
    }
    private var _chipsRow: LinearLayout? = null

    private fun endSession() {
        stopSelf()
    }

    override fun updateORB(rms: Float) {
        mainHandler.post {
            if (!isOverlayReady) return@post

            val energy = (rms / 15f).coerceIn(0f, 1f)
            listeningBar?.updateProgress(energy)

            if (currentJarvisState == JarvisState.LISTENING) {
                listeningBar?.animateWithEnergy(energy)
            }
        }
    }

    override fun setOrbVisibility(visible: Boolean) {
        if (visible) showOverlay() else hideOverlay()
    }

    override fun onRecognizerReady() {
        Log.d(TAG, "Reconocedor listo")
    }

    override fun getCurrentScreenText(): List<String> = ScreenMemory.lastSeenTexts
    override fun showToast(text: String) { }
    override fun getDisplayedText(): String = tvTranscription?.text?.toString() ?: ""
    override fun pausarPorcupine() {
//        wakeDetector?.stop()
        wakeWordPaused = true
        Log.d(TAG, "Wake word pausado")
    }

    override fun reanudarPorcupine() {
        if (wakeWordPaused) {
//            wakeDetector?.start()
            wakeWordPaused = false
            Log.d(TAG, "Wake word reanudado")
        }
    }

    override fun esPorcupinePausado(): Boolean = wakeWordPaused

    private fun pauseWakeWordDetection() {
//        wakeDetector?.stop()
        wakeWordPaused = true
    }

    private fun resumeWakeWordDetection() {

        if (wakeWordPaused && overlayView?.visibility != View.VISIBLE) {
//            wakeDetector?.start()
            wakeWordPaused = false
            Log.d(TAG, "Wake word reanudado")
        } else if (overlayView?.visibility == View.VISIBLE) {
            Log.d(TAG, "Wake word no reanudado - overlay visible")
        }
    }

    private fun createOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.view_bar, null)
            val unifiedBarView = overlayView!!.findViewById<ListeningBarView>(R.id.containerOuter)
            containerOuter = unifiedBarView
            listeningBar = unifiedBarView // Ambos apuntan al mismo objeto integrado
            tvListeningLabel = overlayView!!.findViewById(R.id.tvListeningLabel)
            tvTranscription = overlayView!!.findViewById(R.id.tvTranscription)
            frameContainer = overlayView!!.findViewById(R.id.frameContainer)


            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM
            overlayView?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    hideOverlay()
                    true
                } else false
            }
            windowManager?.addView(overlayView, params)
            overlayView?.visibility = View.GONE
            isOverlayReady = true
            Log.d(TAG, "✅ Overlay creado")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creando overlay: ${e.message}")
            isOverlayReady = false
        }
    }

    private fun showOverlay() {
        if (!isOverlayReady) return

        overlayView?.let {
            if (it.visibility != View.VISIBLE) {
                it.visibility = View.VISIBLE
                it.alpha = 0f
                it.animate().alpha(1f).setDuration(300).start()
                Log.d(TAG, "Overlay mostrado")
            }else{
                Log.d(TAG, "Overlay ya visible")
                it.alpha = 1f

            }
        }
    }
    // Añade esta función en JarvisOverlayService
    override fun hideOverlayFromTimeout() {
        mainHandler.post {
            if (isOverlayReady && overlayView?.visibility == View.VISIBLE) {
                Log.d(TAG, "Ocultando overlay por timeout - volviendo a wake word")

                // Animación de fade out
                overlayView?.animate()
                    ?.alpha(0f)
                    ?.setDuration(300)
                    ?.withEndAction {
                        overlayView?.visibility = View.GONE
                        restoreContainerSize()
                        removerContenedorImagenes()
                        removerSugerenciasAnteriores()
                        tvTranscription?.text = "Di 'Hey Nexus' para activarme"
                    }
                    ?.start()
            }
        }
    }
    private fun hideOverlay() {
        if (!isOverlayReady) return

        overlayView?.let {
            it.animate().alpha(0f).setDuration(200).withEndAction {
                it.visibility = View.GONE
                if (::controller.isInitialized) {
                    controller.detenerSesionCompleta()
                }
                // Forzar wake word activo sin importar estado previo
                wakeWordPaused = true
                resumeWakeWordDetection()
            }.start()
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nexus Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nexus está activo"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus Activo")
            .setContentText("Escuchando 'Hey Nexus'")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
//        wakeDetector?.stop()
        if (::controller.isInitialized) controller.destroy()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removiendo overlay: ${e.message}")
            }
        }
        serviceScope.cancel()
        Log.d(TAG, "Servicio destruido")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}