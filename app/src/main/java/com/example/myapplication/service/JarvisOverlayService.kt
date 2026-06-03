package com.example.myapplication.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.core.*
import com.example.myapplication.ui.ListeningBarView
import com.example.myapplication.ui.VoiceWaveView
import kotlinx.coroutines.*

class JarvisOverlayService : Service(), JarvisUi, PorcupineController {

    // ── Ventana ──────────────────────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    // ── Barra inferior ───────────────────────────────────────────────────────
    private var listeningBar: ListeningBarView? = null
    private var tvListeningLabel: TextView? = null
    private var ivMicIcon: ImageView? = null
    private var voiceWaveView: VoiceWaveView? = null
    private var btnMicContainer: FrameLayout? = null
    private var btnPauseContainer: FrameLayout? = null

    // ── Panel de resultados ──────────────────────────────────────────────────
    private var frameContainer: FrameLayout? = null
    private var resultsPanelInner: LinearLayout? = null
    private var imagesScrollView: android.widget.HorizontalScrollView? = null
    private var imagesInnerLayout: LinearLayout? = null   // creado programáticamente
    private var tvTranscription: TextView? = null
    private var chipsContainer: LinearLayout? = null

    // ── Control ──────────────────────────────────────────────────────────────
    private lateinit var controller: JarvisVoiceController
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var voiceEngine: ContinuousVoiceEngine

    @Volatile private var wakeWordPaused = false
    private var currentJarvisState: JarvisState = JarvisState.IDLE
    private var isOverlayReady = false
    private var ultimoResultadoBusqueda: SearchResult? = null

    companion object {
        private const val TAG = "JARVIS_OVERLAY"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nexus_overlay"

        // Colores del tema oscuro
        private const val COLOR_BG_DARK    = "#1C1C1E"
        private const val COLOR_TEXT_MAIN  = "#E8E8F0"
        private const val COLOR_TEXT_DIM   = "#9999BB"
        private const val COLOR_CHIP_BG    = "#2C2C3A"
        private const val COLOR_CHIP_BORDER= "#3A3A50"
        private const val COLOR_AQUA       = "#4DEEE9"
        private const val COLOR_BLUE       = "#7BD7F8"
        private const val COLOR_GREEN      = "#1DE0A0"
    }

    // ────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 Servicio creado")
        startForeground(NOTIFICATION_ID, createNotification())
        createOverlay()

        mainHandler.postDelayed({
            voiceEngine = ContinuousVoiceEngine(
                context = this,
                onWakeWordDetected = {
                    Log.d(TAG, "🎤 Wake word detectado!")
                    serviceScope.launch(Dispatchers.Main) { onWakeWordDetected() }
                },
                onFinalResult = { texto -> Log.d(TAG, "Resultado final: $texto") },
                onPartialResult = { parcial -> Log.v(TAG, "Parcial: $parcial") },
                onRmsChanged = { rms -> mainHandler.post { updateORB(rms) } }
            )
            controller = JarvisVoiceController(
                context = this,
                ui = this,
                scope = serviceScope,
                porcupineController = this
            )
            controller.init()
            showOverlay()
            tvTranscription?.text = "Di 'Hey Nexus' para activarme"
        }, 900)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controller.isInitialized) controller.destroy()
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {
                Log.e(TAG, "Error removiendo overlay: ${e.message}")
            }
        }
        serviceScope.cancel()
        Log.d(TAG, "Servicio destruido")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────────────────────────────────
    // CREAR OVERLAY
    // ────────────────────────────────────────────────────────────────────────

    private fun createOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.view_bar, null)

            // Barra inferior
            listeningBar      = overlayView!!.findViewById(R.id.containerOuter)
            tvListeningLabel  = overlayView!!.findViewById(R.id.tvListeningLabel)
            ivMicIcon         = overlayView!!.findViewById(R.id.ivMicIcon)
            voiceWaveView     = overlayView!!.findViewById(R.id.voiceWaveView)
            btnMicContainer   = overlayView!!.findViewById(R.id.btnMicContainer)
            btnPauseContainer = overlayView!!.findViewById(R.id.btnPauseContainer)

            // Panel de resultados
            frameContainer   = overlayView!!.findViewById(R.id.frameContainer)
            resultsPanelInner = overlayView!!.findViewById(R.id.resultsPanelInner)
            imagesScrollView = overlayView!!.findViewById(R.id.imagesScrollView)
            tvTranscription  = overlayView!!.findViewById(R.id.tvTranscription)
            chipsContainer   = overlayView!!.findViewById(R.id.chipsContainer)

            // LinearLayout interno del scroll de imágenes (creado aquí para full-width)
            imagesInnerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            imagesScrollView?.addView(imagesInnerLayout)

            // Fondo del panel de resultados: oscuro con esquinas redondeadas solo arriba
            aplicarFondoOscuroConEsquinas(resultsPanelInner, soloArriba = false)

            // Tap en mic: activa/cancela
            btnMicContainer?.setOnClickListener {
                if (!::controller.isInitialized) return@setOnClickListener
                if (currentJarvisState == JarvisState.IDLE) {
                    onWakeWordDetected()
                } else {
                    controller.detenerSesionCompleta()
                    renderState(JarvisState.IDLE)
                }
            }

            // Tap en pausa: detiene TTS
            btnPauseContainer?.setOnClickListener {
                if (::controller.isInitialized) {
                    controller.detenerAudio()
                    renderState(JarvisState.IDLE)
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM

            overlayView?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) { hideOverlay(); true } else false
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

    /**
     * Fondo oscuro con esquinas redondeadas.
     * soloArriba=true → solo esquinas superiores redondeadas (cuando el panel se acopla a la barra).
     */
    private fun aplicarFondoOscuroConEsquinas(view: View?, soloArriba: Boolean) {
        val r = 22f * resources.displayMetrics.density
        view?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (soloArriba) {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            } else {
                cornerRadius = r
            }
            setColor(Color.parseColor(COLOR_BG_DARK))
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // PANEL DE RESULTADOS
    // ────────────────────────────────────────────────────────────────────────

    private fun mostrarPanelResultados() {
        frameContainer?.let { panel ->
            if (panel.visibility != View.VISIBLE) {
                panel.visibility = View.VISIBLE
                panel.alpha = 0f
                panel.animate().alpha(1f).setDuration(260).start()
            }
        }
    }

    private fun ocultarPanelResultados() {
        frameContainer?.let { panel ->
            if (panel.visibility == View.VISIBLE) {
                panel.animate().alpha(0f).setDuration(200).withEndAction {
                    panel.visibility = View.GONE
                    limpiarContenidoPanel()
                }.start()
            }
        }
    }

    private fun limpiarContenidoPanel() {
        tvTranscription?.text = ""
        imagesInnerLayout?.removeAllViews()
        imagesScrollView?.visibility = View.GONE
        chipsContainer?.removeAllViews()
        chipsContainer?.visibility = View.GONE
    }

    // ────────────────────────────────────────────────────────────────────────
    // JarvisUi
    // ────────────────────────────────────────────────────────────────────────

    override fun showText(text: String) {
        val t = text.lowercase().trim()
        if (listOf("salir", "salir.", "adiós", "adios", "hasta luego").any { it == t }) {
            endSession(); return
        }
        mainHandler.post {
            if (!isOverlayReady) return@post
            mostrarPanelResultados()

            val formatoHtml = text
                .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("» (.*?)"), "<font color='$COLOR_AQUA'><b>» $1</b></font>")
                .replace("•", "<font color='$COLOR_AQUA'>•</font>")
                .replace("\n", "<br/>")

            tvTranscription?.run {
                setLineSpacing(5f * resources.displayMetrics.density, 1.0f)
                this.text = android.text.Html.fromHtml(formatoHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                setTextColor(Color.parseColor(COLOR_TEXT_MAIN))
                visibility = View.VISIBLE
            }
            if (currentJarvisState == JarvisState.IDLE && text.length < 50) {
                mainHandler.removeCallbacksAndMessages(null)
                mainHandler.postDelayed({
                    if (currentJarvisState == JarvisState.IDLE &&
                        frameContainer?.visibility == View.VISIBLE &&
                        tvTranscription?.text?.toString()?.length ?: 0 < 100) {
                        ocultarPanelResultados()
                    }
                }, 15_000L)
            } else if (currentJarvisState == JarvisState.IDLE && text.length >= 50) {
                // Si el texto es largo (resultado de búsqueda), mantenemos el panel visible
                // y NO programamos auto-ocultado
                Log.d(TAG, "Resultado largo ($text.length chars) - manteniendo panel visible permanentemente hasta nueva interacción")
            }
        }
    }

    override fun showImages(urls: List<String>) {
        mainHandler.post {
            if (!isOverlayReady) return@post

            imagesInnerLayout?.removeAllViews()
            mostrarPanelResultados()

            val density = resources.displayMetrics.density
            // Alto fijo: imágenes de pantalla completa sin espacios a los lados
            val imageH = (185 * density).toInt()
            val imageW = (185 * density).toInt()

            urls.take(6).forEach { url ->
                val imageView = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    // Sin bordes redondeados — imagen de borde a borde como en Copilot
                    layoutParams = LinearLayout.LayoutParams(imageW, imageH).apply {
                        marginEnd = (3 * density).toInt()
                    }
                    cargarImagenNativa(url, serviceScope, this)
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (e: Exception) { Log.e(TAG, "Error abriendo imagen: ${e.message}") }
                    }
                }
                imagesInnerLayout?.addView(imageView)
            }

            // Ajustar alto del HorizontalScrollView al tamaño de las imágenes
            imagesScrollView?.layoutParams?.height = imageH
            imagesScrollView?.requestLayout()

            // Fondo oscuro con esquinas redondeadas ARRIBA del scroll (primera sección visible)
            imagesScrollView?.background = GradientDrawable().apply {
                val r = 22f * density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor(COLOR_BG_DARK))
            }
            imagesScrollView?.clipToOutline = true
            imagesScrollView?.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND

            imagesScrollView?.visibility = View.VISIBLE
        }
    }

    override fun renderState(state: JarvisState) {
        currentJarvisState = state
        mainHandler.post {
            if (!isOverlayReady) return@post
            when (state) {
                JarvisState.LISTENING -> {
                    tvListeningLabel?.text = "ESCUCHANDO"
                    tvListeningLabel?.setTextColor(Color.parseColor(COLOR_AQUA))
                    listeningBar?.animateWithEnergy(0.7f)
                    setMicMode(ttsHablando = false)
                    btnPauseContainer?.visibility = View.GONE
                }
                JarvisState.THINKING -> {
                    tvListeningLabel?.text = "PENSANDO"
                    tvListeningLabel?.setTextColor(Color.parseColor(COLOR_BLUE))
                    listeningBar?.animateWithEnergy(0.4f)
                    setMicMode(ttsHablando = false)
                    btnPauseContainer?.visibility = View.GONE
                }
                JarvisState.SPEAKING -> {
                    tvListeningLabel?.text = "HABLANDO"
                    tvListeningLabel?.setTextColor(Color.parseColor(COLOR_GREEN))
                    listeningBar?.animateWithEnergy(0.6f)
                    setMicMode(ttsHablando = true)
                    btnPauseContainer?.visibility = View.VISIBLE
                }
                JarvisState.IDLE -> {
                    tvListeningLabel?.text = "NEXUS"
                    tvListeningLabel?.setTextColor(Color.WHITE)
                    listeningBar?.animateWithEnergy(0.15f)
                    setMicMode(ttsHablando = false)
                    btnPauseContainer?.visibility = View.GONE
                    resumeWakeWordDetection()
                    // Solo ocultar panel si no hay texto mostrado o es muy corto
                    val textoActual = tvTranscription?.text?.toString() ?: ""
                    if (textoActual.isBlank() || textoActual.length < 50) {
                        mainHandler.postDelayed({
                            if (currentJarvisState == JarvisState.IDLE) ocultarPanelResultados()
                        }, 3_000L)
                    } else {
                        Log.d(TAG, "Resultado largo presente ($textoActual.length chars) - manteniendo panel visible")
                        // No programamos ocultado automático
                    }
                }
            }
        }
    }

    override fun updateORB(rms: Float) {
        mainHandler.post {
            if (!isOverlayReady) return@post
            val energy = (rms / 15f).coerceIn(0f, 1f)
            listeningBar?.updateProgress(energy)
            if (currentJarvisState == JarvisState.LISTENING) listeningBar?.animateWithEnergy(energy)
            if (currentJarvisState == JarvisState.SPEAKING) voiceWaveView?.setEnergy(energy)
        }
    }

    override fun hideOverlayFromTimeout() {
        mainHandler.post {
            if (!isOverlayReady || overlayView?.visibility != View.VISIBLE) return@post
            if (frameContainer?.visibility == View.VISIBLE) {
                Log.d(TAG, "Timeout con panel visible — manteniendo overlay abierto")
                if (currentJarvisState != JarvisState.IDLE) {
                    renderState(JarvisState.IDLE)
                }
                return@post
            }
            Log.d(TAG, "Ocultando overlay por timeout")
            overlayView?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
                overlayView?.visibility = View.GONE
                ocultarPanelResultados()
            }?.start()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // BOTÓN MIC / ONDAS / PAUSA
    // ────────────────────────────────────────────────────────────────────────

    private fun setMicMode(ttsHablando: Boolean) {
        if (ttsHablando) {
            ivMicIcon?.visibility = View.GONE
            voiceWaveView?.visibility = View.VISIBLE
        } else {
            voiceWaveView?.visibility = View.GONE
            ivMicIcon?.visibility = View.VISIBLE
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // CHIPS
    // ────────────────────────────────────────────────────────────────────────

    private fun inyectarChipsDeSugerencia(textoBase: String) {
        val chips = chipsContainer ?: return
        chips.removeAllViews()
        val density = resources.displayMetrics.density

        listOf("🌐 Ver más fuentes", "📋 Copiar texto", "🔄 Volver a buscar").forEach { sugerencia ->
            val chip = TextView(this).apply {
                text = sugerencia
                textSize = 12f
                setTextColor(Color.parseColor("#CCCCEE"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * density
                    setColor(Color.parseColor(COLOR_CHIP_BG))
                    setStroke((1 * density).toInt(), Color.parseColor(COLOR_CHIP_BORDER))
                }
                setPadding(
                    (12 * density).toInt(), (6 * density).toInt(),
                    (12 * density).toInt(), (6 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }

                setOnClickListener {
                    if (!::controller.isInitialized) return@setOnClickListener
                    when (sugerencia) {
                        "🌐 Ver más fuentes" -> {
                            val query = controller.ultimoResultadoBusqueda?.query ?: ""
                            val url = if (query.isNotBlank())
                                "https://www.google.com/search?q=${Uri.encode(query)}"
                            else "https://www.google.com"
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                            } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
                        }
                        "📋 Copiar texto" -> {
                            val textoACopiar = controller.ultimoResultadoBusqueda?.content ?: textoBase
                            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Nexus", textoACopiar))
                            this.text = "Copiado! ✓"
                            postDelayed({ this.text = sugerencia }, 1500)
                        }
                        "🔄 Volver a buscar" -> onWakeWordDetected()
                    }
                }
            }
            chips.addView(chip)
        }
        chips.visibility = View.VISIBLE
    }

    // ────────────────────────────────────────────────────────────────────────
    // WAKE WORD / SESIÓN
    // ────────────────────────────────────────────────────────────────────────

    private fun onWakeWordDetected() {
        showOverlay()
        mainHandler.post {
            ocultarPanelResultados()
            tvListeningLabel?.text = "ESCUCHANDO"
            tvListeningLabel?.setTextColor(Color.parseColor(COLOR_AQUA))
        }
        if (::controller.isInitialized) controller.detenerSesionCompleta()
        serviceScope.launch { controller.startInteraction() }
    }

    fun mostrarResultadoBusquedaCompleto(resultado: SearchResult) {
        this.ultimoResultadoBusqueda = resultado
        showText(resultado.content)
        if (resultado.imageUrls.isNotEmpty()) showImages(resultado.imageUrls)
    }

    fun procesarComandoVozUI(textoHablado: String): Boolean {
        val comando = textoHablado.lowercase().trim()
        if (comando.contains("copiar texto") || comando.contains("copia el texto")) {
            ultimoResultadoBusqueda?.let { ejecutarCopiarTexto(it.content) }
                ?: tvTranscription?.text?.toString()?.let { ejecutarCopiarTexto(it) }
            return true
        }
        if (comando.contains("ver más") || comando.contains("ver mas") || comando.contains("abrir enlaces")) {
            ultimoResultadoBusqueda?.let { ejecutarAbrirNavegador(it.urls) }
                ?: showText("No tengo enlaces guardados.")
            return true
        }
        return false
    }

    private fun ejecutarCopiarTexto(texto: String) {
        if (texto.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("Nexus", texto))
        renderState(JarvisState.IDLE)
        showText("¡Texto copiado! ✓")
    }

    private fun ejecutarAbrirNavegador(urls: List<String>) {
        val query = ultimoResultadoBusqueda?.query ?: ""
        val url = if (query.isNotBlank()) "https://www.google.com/search?q=${Uri.encode(query)}"
        else if (urls.isNotEmpty()) urls.first()
        else "https://www.google.com"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            renderState(JarvisState.IDLE)
        } catch (e: Exception) { Log.e(TAG, "Error abriendo navegador: ${e.message}") }
    }

    // ────────────────────────────────────────────────────────────────────────
    // OVERLAY SHOW / HIDE
    // ────────────────────────────────────────────────────────────────────────

    private fun showOverlay() {
        if (!isOverlayReady) return
        overlayView?.let {
            if (it.visibility != View.VISIBLE) {
                it.visibility = View.VISIBLE
                it.alpha = 0f
                it.animate().alpha(1f).setDuration(300).start()
            } else {
                it.alpha = 1f
            }
        }
    }

    private fun hideOverlay() {
        if (!isOverlayReady) return
        overlayView?.let {
            it.animate().alpha(0f).setDuration(200).withEndAction {
                it.visibility = View.GONE
                if (::controller.isInitialized) controller.detenerSesionCompleta()
                wakeWordPaused = true
                resumeWakeWordDetection()
            }.start()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ────────────────────────────────────────────────────────────────────────

    private fun cargarImagenNativa(url: String, scope: CoroutineScope, imageView: ImageView) {
        scope.launch(Dispatchers.IO) {
            try {
                val stream = java.net.URL(url).openStream()
                val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                withContext(Dispatchers.Main) { imageView.setImageBitmap(bitmap) }
            } catch (e: Exception) { Log.e("JARVIS_IMG", "Error: $url -> ${e.message}") }
        }
    }

    private fun endSession() { stopSelf() }

    // ────────────────────────────────────────────────────────────────────────
    // PorcupineController
    // ────────────────────────────────────────────────────────────────────────

    override fun pausarPorcupine() { wakeWordPaused = true }
    override fun reanudarPorcupine() { if (wakeWordPaused) wakeWordPaused = false }
    override fun esPorcupinePausado(): Boolean = wakeWordPaused

    private fun resumeWakeWordDetection() {
        if (wakeWordPaused && overlayView?.visibility != View.VISIBLE) {
            wakeWordPaused = false
            Log.d(TAG, "Wake word reanudado")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // JarvisUi — stubs
    // ────────────────────────────────────────────────────────────────────────

    override fun setOrbVisibility(visible: Boolean) { if (visible) showOverlay() else hideOverlay() }
    override fun onRecognizerReady() {}
    override fun getCurrentScreenText(): List<String> = ScreenMemory.lastSeenTexts
    override fun showToast(text: String) {}
    override fun getDisplayedText(): String = tvTranscription?.text?.toString() ?: ""

    // ────────────────────────────────────────────────────────────────────────
    // NOTIFICACIÓN
    // ────────────────────────────────────────────────────────────────────────

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Nexus Assistant",
                NotificationManager.IMPORTANCE_LOW).apply { description = "Nexus está activo" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus Activo")
            .setContentText("Escuchando 'Hey Nexus'")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}