package com.example.myapplication.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Html
import android.util.Log
import android.view.*
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.core.app.NotificationCompat
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.myapplication.activity.JarActivity
import com.example.myapplication.core.audio.ContinuousVoiceEngine
import com.example.myapplication.core.integrations.SearchResult
import com.example.myapplication.core.memory.ScreenMemory
import com.example.myapplication.core.voice.JarvisState
import com.example.myapplication.core.voice.JarvisUi
import com.example.myapplication.core.voice.JarvisVoiceController
import com.example.myapplication.core.voice.PorcupineController
import com.example.myapplication.data.ChatDatabase
import com.example.myapplication.data.ChatRepository
import com.example.myapplication.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull


class JarvisOverlayService : Service(), JarvisUi, PorcupineController {

    // ── Ventana ──────────────────────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null

    // ── Estado Compose ───────────────────────────────────────────────────────
    private val uiState   = JarvisOverlayUiState()
    private val barState  = ListeningBarState()

    private var stepsJob: Job? = null
    // ── Control ──────────────────────────────────────────────────────────────
    private lateinit var controller: JarvisVoiceController
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler  = Handler(Looper.getMainLooper())
    private lateinit var voiceEngine: ContinuousVoiceEngine

    @Volatile private var wakeWordPaused = false
    private var isOverlayReady = false
    private var ultimoResultadoBusqueda: SearchResult? = null

    // Typewriter
    private var typewriterJob: Job? = null

    companion object {
        private const val TAG           = "JARVIS_OVERLAY"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID   = "nexus_overlay"
    }
    private val chatRepository by lazy { ChatRepository(this) }
    // ── Lifecycle owner para ComposeView (requerido fuera de Activity) ───────
    private val lifecycleOwner = ServiceLifecycleOwner()

    // ────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ────────────────────────────────────────────────────────────────────────
    private val whatsappPreviewReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "JARVIS.SHOW_WHATSAPP_PREVIEW") {
                val contact = intent.getStringExtra("contact") ?: return
                val message = intent.getStringExtra("message") ?: return
                Log.d(TAG, " Mostrando preview de WhatsApp para $contact")
                mainHandler.post {
                    uiState.pendingWhatsappContact = contact
                    uiState.pendingWhatsappMessage = message
                    uiState.showWhatsappPreview = true
                    uiState.showPanel = true
                    // Aseguramos que el overlay sea visible
                    showOverlay()
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, " Servicio creado")
        startForeground(NOTIFICATION_ID, createNotification())
        lifecycleOwner.start()
        createOverlay()

        mainHandler.postDelayed({
            controller = JarvisVoiceController(
                context              = this,
                ui                   = this,
                scope                = serviceScope,
                porcupineController  = this,
                uiState = uiState,
                chatRepository = chatRepository
            )
            forceOpenDatabase()
            controller.init()
            showOverlay()
            uiState.transcription = "Di 'Hey Nexus' para activarme"
        }, 900)

        registerReceiver(whatsappPreviewReceiver, IntentFilter("JARVIS.SHOW_WHATSAPP_PREVIEW"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(whatsappPreviewReceiver)
        if (::controller.isInitialized) controller.destroy()
        composeView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {
                Log.e(TAG, "Error removiendo overlay: ${e.message}")
            }
        }
        lifecycleOwner.stop()
        serviceScope.cancel()
        Log.d(TAG, "Servicio destruido")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun updateUserTranscription(text: String) {
        mainHandler.post {
            if (!isOverlayReady) return@post
            uiState.userTranscription = text
            // Si el usuario está hablando, cancelamos cualquier temporizador de borrado
            if (text.isNotBlank()) {
                mainHandler.removeCallbacksAndMessages("HIDE_UI_IDLE")
            }
        }
    }
    private fun forceOpenDatabase() {
        try {
            val db = ChatDatabase.getDatabase(this)
            val dao = db.chatMessageDao()
            serviceScope.launch {
                val sessionId = chatRepository.getSessionId()
                val count = dao.getMessagesForSession(sessionId).firstOrNull()?.size ?: 0
                Log.d(TAG, " Base de datos abierta, mensajes: $count")

                //  También imprime los mensajes para verlos en logs
                val messages = dao.getMessagesForSession(sessionId).firstOrNull()
                messages?.forEach { msg ->
                    Log.d(TAG, " [${msg.sender}] ${msg.content}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo BD: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // CREAR OVERLAY
    // ────────────────────────────────────────────────────────────────────────

    private fun createOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            composeView = ComposeView(this).apply {
                setContent {
                    JarvisOverlayContent(
                        uiState      = uiState,
                        barState     = barState,
                        chatRepository = chatRepository,
                        onMicClick = {
                            // Acción al hacer clic en el micrófono (puedes implementar)
                        },
                        onPauseClick = {
                            if (::controller.isInitialized) {
                                controller.detenerAudio()
                                renderState(com.example.myapplication.core.voice.JarvisState.IDLE)
                            }
                        },
                        onBackgroundClick = {
                            handleBackgroundDismiss()
                        },
                        onOrbClick = {
                            // Oculta el overlay de inmediato (sin animación)
                            composeView?.apply {
                                visibility = View.GONE
                                alpha = 0f
                            }
                            if (::controller.isInitialized) {
                                controller.detenerSesionCompleta()
                            }
                            // Abre la actividad principal con flag de reinicio
                            val intent = Intent(this@JarvisOverlayService, JarActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("reset_to_wakeword", true)
                            }
                            startActivity(intent)
                        },
                        onSendMessage = { text ->
                            //  Enviar mensaje escrito al servidor
                            if (::controller.isInitialized) {
                                controller.enviarComandoAlServidor(text)
                            }
                        }
                    )
                }
            }

            // Conectar ComposeView al LifecycleOwner del Service
            composeView!!.setViewTreeLifecycleOwner(lifecycleOwner)
            composeView!!.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            // Recomposer manual (necesario en un Service sin Activity)
            val coroutineContext = AndroidUiDispatcher.CurrentThread
            val runRecomposeScope = CoroutineScope(coroutineContext)
            val recomposer = Recomposer(coroutineContext)
            composeView!!.compositionContext = recomposer
            runRecomposeScope.launch { recomposer.runRecomposeAndApplyChanges() }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,

                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM

            windowManager?.addView(composeView, params)
            composeView?.visibility = View.GONE
            isOverlayReady = true
            Log.d(TAG, " Overlay Compose creado")
        } catch (e: Exception) {
            Log.e(TAG, " Error creando overlay: ${e.message}")
            isOverlayReady = false
        }
    }
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "JARVIS.RESET_COMPLETE") {
                Log.d(TAG, " Reset completo recibido por broadcast")
                handleBackgroundDismiss()
            }
        }
    }

    // En JarvisOverlayService.kt

    private fun handleBackgroundDismiss() {
        Log.d(TAG, " Reset completo por clic en fondo")
        mainHandler.removeCallbacksAndMessages(null)
        // 1. Detener cualquier sesión activa
        if (::controller.isInitialized) {
            controller.volverAWakeWordPorClicAfuera()
        }

        // 2. Resetear TODOS los estados de UI
        mainHandler.post {
            // Resetear estado a IDLE
            uiState.applyJarvisState(JarvisState.IDLE)

            // Limpiar todos los paneles y resultados
            uiState.clearPanel()
            uiState.clearMusicResult()
            uiState.showWhatsappPreview = false
            uiState.showPanel = false
            uiState.userTranscription = ""
            uiState.processingSteps = emptyList()
            uiState.imageUrls = emptyList()
            uiState.sourceUrls = emptyList()
            uiState.pendingWhatsappContact = ""
            uiState.pendingWhatsappMessage = ""

            // Forzar estado IDLE en la barra
            barState.updateProgress(0.15f)

            // OCULTAR EL OVERLAY
            composeView?.apply {
                visibility = View.GONE
                alpha = 0f
            }

            // Reanudar wake word si estaba pausado
            resumeWakeWordDetection()
        }

        serviceScope.coroutineContext.cancelChildren()

        Log.d(TAG, " Reset completo - Modo wake word activo")
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
            uiState.applyText(text)
            uiState.showPanel = true
        }
    }

    override fun showImages(urls: List<String>) {
        mainHandler.post {
            if (!isOverlayReady) return@post
            uiState.applyImages(urls)
        }
    }

    override fun renderState(state: JarvisState) {
        mainHandler.post {
            if (!isOverlayReady) return@post

            //  Si hay resultado de música, NO resetear el panel
            if (uiState.showMusicResult && state == JarvisState.IDLE) {
                // Mantener el panel visible con el resultado de música
                uiState.showPanel = true
                return@post
            }

            //  Si hay WhatsApp preview, NO resetear
            if (uiState.showWhatsappPreview && state == JarvisState.IDLE) {
                uiState.showPanel = true
                return@post
            }

            // Aplicar el estado normalmente
            uiState.applyJarvisState(state)
            barState.animateWithEnergy(when (state) {
                JarvisState.LISTENING -> 0.7f
                JarvisState.THINKING  -> 0.4f
                JarvisState.SPEAKING  -> 0.6f
                JarvisState.IDLE      -> 0.15f
            })

            if (state == JarvisState.IDLE) {
                resumeWakeWordDetection()
            }

            mainHandler.removeCallbacksAndMessages("HIDE_UI_IDLE")
        }
    }

    override fun updateORB(rms: Float) {
        mainHandler.post {
            if (!isOverlayReady) return@post
            val energy = (rms / 15f).coerceIn(0f, 1f)
            barState.updateProgress(energy)
            if (uiState.jarvisState == JarvisState.LISTENING) barState.animateWithEnergy(energy)
        }
    }

    override fun hideOverlayFromTimeout() {
        mainHandler.post {
            if (!isOverlayReady) return@post
            if (uiState.jarvisState != JarvisState.IDLE) {
                renderState(JarvisState.IDLE)
            }
        }
    }

    override fun showSearchResult(
        textoCompleto: String,
        fuentes: List<String>,
        imagenes: List<String>,
        preguntas: List<String>,
        html: String
    ) {
        mainHandler.post {
            if (!isOverlayReady) return@post

            // Cancelar animaciones anteriores
            stepsJob?.cancel()
            typewriterJob?.cancel()

            // Mostrar panel
            uiState.showPanel = true

            // 1. GUARDAR IMÁGENES
            uiState.imageUrls = imagenes.take(6)

            // 2. GUARDAR FUENTES
            uiState.sourceUrls = fuentes


            uiState.typewriterText = ""  //  Limpiar antes de empezar
            uiState.fullHtmlText = html.ifBlank { textoCompleto }
            // Mostrar overlay
            showOverlay()
        }
    }
    // ────────────────────────────────────────────────────────────────────────
    // Panel helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun ocultarPanelResultados() {
        uiState.hidePanel()
    }

    // ────────────────────────────────────────────────────────────────────────
    // WAKE WORD / SESIÓN
    // ────────────────────────────────────────────────────────────────────────



    private fun onWakeWordDetected() {
        showOverlay()
        uiState.userTranscription = ""
        uiState.clearPanel()
        uiState.showPanel = false
        uiState.applyJarvisState(JarvisState.LISTENING)
        if (::controller.isInitialized) controller.detenerSesionCompleta()
        serviceScope.launch { controller.startInteraction() }
    }

    // ────────────────────────────────────────────────────────────────────────
    // OVERLAY SHOW / HIDE
    // ────────────────────────────────────────────────────────────────────────
    override fun updateProcessingSteps(steps: List<ProcessingStep>) {
        stepsJob?.cancel()
        stepsJob = serviceScope.launch {
            uiState.processingSteps = steps  // limpia antes de empezar

        }
    }

    private fun showOverlay() {
        if (!isOverlayReady) return
        composeView?.let {
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
        composeView?.let {
            it.animate().alpha(0f).setDuration(200).withEndAction {
                it.visibility = View.GONE
                if (::controller.isInitialized) controller.detenerSesionCompleta()
                wakeWordPaused = true
                resumeWakeWordDetection()
            }.start()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Utilidades copiadas intactas del Service original
    // ────────────────────────────────────────────────────────────────────────

    fun mostrarResultadoBusquedaCompleto(resultado: SearchResult) {
        ultimoResultadoBusqueda = resultado
        showText(resultado.content)
        if (resultado.imageUrls.isNotEmpty()) showImages(resultado.imageUrls)
    }

    fun procesarComandoVozUI(textoHablado: String): Boolean {
        val comando = textoHablado.lowercase().trim()
        if (comando.contains("copiar texto") || comando.contains("copia el texto")) {
            ultimoResultadoBusqueda?.let { ejecutarCopiarTexto(it.content) }
                ?: uiState.transcription.takeIf { it.isNotBlank() }?.let { ejecutarCopiarTexto(it) }
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
        showText("¡Texto copiado! ")
    }

    private fun ejecutarAbrirNavegador(urls: List<String>) {
        val query = ultimoResultadoBusqueda?.query ?: ""
        val url   = if (query.isNotBlank()) "https://www.google.com/search?q=${Uri.encode(query)}"
        else if (urls.isNotEmpty()) urls.first()
        else "https://www.google.com"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            renderState(JarvisState.IDLE)
        } catch (e: Exception) { Log.e(TAG, "Error abriendo navegador: ${e.message}") }
    }

    private fun endSession() { stopSelf() }

    // ────────────────────────────────────────────────────────────────────────
    // PorcupineController
    // ────────────────────────────────────────────────────────────────────────

    override fun pausarPorcupine()      { wakeWordPaused = true }
    override fun reanudarPorcupine()    { if (wakeWordPaused) wakeWordPaused = false }
    override fun esPorcupinePausado()   = wakeWordPaused

    private fun resumeWakeWordDetection() {
        if (wakeWordPaused && composeView?.visibility != View.VISIBLE) {
            wakeWordPaused = false
            Log.d(TAG, "Wake word reanudado")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // JarvisUi — stubs
    // ────────────────────────────────────────────────────────────────────────

    override fun setOrbVisibility(visible: Boolean) {
        if (visible) {
            showOverlay()
        } else {
            //  No ocultar si hay resultado de música activo
            if (!uiState.showMusicResult) {
                hideOverlay()
            }
        }
    }override fun onRecognizerReady()                 {}
    override fun getCurrentScreenText(): List<String> = ScreenMemory.lastSeenTexts
    override fun showToast(text: String)             {}
    override fun getDisplayedText(): String          = uiState.transcription

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

// ─────────────────────────────────────────────────────────────────────────────
// LifecycleOwner + SavedStateRegistryOwner para usar ComposeView en un Service
// (sin esto ComposeView lanza IllegalStateException)
// ─────────────────────────────────────────────────────────────────────────────
internal class ServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry  = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore     = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}