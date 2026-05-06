package com.example.myapplication.service

//import ai.picovoice.porcupine.PorcupineException
//import ai.picovoice.porcupine.PorcupineManager
//import ai.picovoice.android.voiceprocessor.VoiceProcessor
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.core.*
import com.example.myapplication.ui.JarvisOrbView
import kotlinx.coroutines.*
import com.example.myapplication.core.VoskWakeWordDetector

class JarvisOverlayService : Service(), JarvisUi, PorcupineController {

    // ── Overlay ──────────────────────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var orbView: JarvisOrbView? = null
    private var transcriptionText: TextView? = null
    private var isOrbVisible = true

    // ── Controlador ──────────────────────────────────────────────────────────
    private lateinit var controller: JarvisVoiceController
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

//    // ── Porcupine (wake word) ─────────────────────────────────────────────────
//    private var porcupineManager: PorcupineManager? = null
//    private val ACCESS_KEY = "YMYKZrTBnmQeviXKwGY8rrXUiUlcHBC1ApCQwg6G99JrluupBCFbUg=="
//    private val KEYWORD_FILE = "hey-nexus_es_android_v4_0_0.ppn"
//    private val MODEL_FILE = "porcupine_params_es.pv"

    // Estado simplificado de Porcupine
    @Volatile private var porcupinePausado = false
    private val porcupineLock = Any()
//vosk
    private var wakeDetector: VoskWakeWordDetector? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // ── Estado general ────────────────────────────────────────────────────────
    private var currentJarvisState: JarvisState = JarvisState.IDLE
    private var isSessionActive = true
    private var isInitialized = false

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "jarvis_overlay_channel"
        private const val TAG = "JARVIS_OVERLAY"
//        private const val FRAME_LENGTH = 512
//        private const val SAMPLE_RATE = 16000
    }

    // ────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio creado")
        startForeground(NOTIFICATION_ID, createNotification())
        serviceScope.launch {
            delay(300)  // Da tiempo a que el sistema esté listo
            initializeService()
        }
    }

    private fun initializeService() {
        if (isInitialized) return

        Log.d(TAG, "Inicializando...")
        createOverlay()

        controller = JarvisVoiceController(
            context = this,
            ui = this,
            scope = serviceScope,
            porcupineController = this
        )
        controller.init()

        // Configura Porcupine para wake word
        // IMPORTANTE: VoiceProcessor solo corre cuando Porcupine está activo (en IDLE)
        // Cuando la sesión empieza, pausarPorcupine() detiene VoiceProcessor
        setupPorcupine()

        isInitialized = true
        Log.d(TAG, "✅ Servicio inicializado")
    }

    override fun onDestroy() {
        super.onDestroy()

        // Detiene Porcupine y VoiceProcessor
        synchronized(porcupineLock) {
            try {
                wakeDetector?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error limpiando Porcupine: ${e.message}")
            }
        }

        if (::controller.isInitialized) controller.destroy()

        overlayView?.let {
            try { windowManager?.removeView(it) }
            catch (e: Exception) { Log.e(TAG, "Error removiendo overlay: ${e.message}") }
        }

        serviceScope.cancel()
        Log.d(TAG, "Servicio destruido")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────────────────────────────────
    // PORCUPINE — Wake Word Detection
    // ────────────────────────────────────────────────────────────────────────

    private fun setupPorcupine() {
        try {
            wakeDetector = VoskWakeWordDetector(
                context = this,
                onWakeWordDetected = {
                    if (isSessionActive) {
                        serviceScope.launch(Dispatchers.Main) { onWakeWordDetected() }
                    }
                }
            )

            wakeDetector?.init(
                onReady = {
                    wakeDetector?.start()
                    porcupinePausado = false
                    Log.i(TAG, "✅ Vosk listo — escuchando wake word")
                },
                onError = { msg ->
                    Log.e(TAG, "❌ Error cargando Vosk: $msg")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error Vosk: ${e.message}", e)
        }
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "🎙️ Wake word detectado")

        // Pausa Porcupine inmediatamente para evitar falsos positivos durante la sesión
        pausarPorcupine()

        if (!isOrbVisible) showOverlay()

        transcriptionText?.post {
            transcriptionText?.text = "¡Te escucho!"
            transcriptionText?.visibility = View.VISIBLE
        }

        serviceScope.launch {
            controller.startInteraction()
        }
    }

    // ── PorcupineController interface ────────────────────────────────────────

    override fun pausarPorcupine() {
        // No lanzamos en serviceScope para evitar delay (se llama desde onWakeWordDetected)
        synchronized(porcupineLock) {
            if (porcupinePausado) return

            try {
                wakeDetector?.stop()
                porcupinePausado = true
                Log.d("JARVIS_PORCUPINE", "⏸️ Vosk pausado — VoiceProcessor detenido")
            } catch (e: Exception) {
                Log.e("JARVIS_PORCUPINE", "Error pausando: ${e.message}")
            }
        }
    }

    override fun reanudarPorcupine() {
        mainHandler.post {
            synchronized(porcupineLock) {
                if (!porcupinePausado) return@synchronized

                try {
                    wakeDetector?.start()
                    porcupinePausado = false
                    Log.d("JARVIS_PORCUPINE", "▶️ Vosk reanudado — VoiceProcessor activo")
                } catch (e: Exception) {
                    Log.e("JARVIS_PORCUPINE", "Error reanudando: ${e.message}")
                }
            }
        }
    }

    override fun esPorcupinePausado(): Boolean = porcupinePausado

    // ────────────────────────────────────────────────────────────────────────
    // JarvisUi interface
    // ────────────────────────────────────────────────────────────────────────

    override fun renderState(state: JarvisState) {
        currentJarvisState = state
        orbView?.post {
            when (state) {
                JarvisState.LISTENING -> {
                    transcriptionText?.text = "Escuchando..."
                    transcriptionText?.visibility = View.VISIBLE
                }
                JarvisState.THINKING -> {
                    transcriptionText?.text = "Procesando..."
                    transcriptionText?.visibility = View.VISIBLE
                }
                JarvisState.SPEAKING -> {
                    transcriptionText?.visibility = View.VISIBLE
                }
                JarvisState.IDLE -> {
                    orbView?.reset()
                    transcriptionText?.visibility = View.GONE
                }
                else -> {}
            }
        }
    }

    override fun showText(text: String) {
        val t = text.lowercase().trim()
        val esSalida = listOf("salir", "salir.", "adiós", "adios", "hasta luego").any { it == t }
        if (esSalida) {
            endSession()
            return
        }
        transcriptionText?.post {
            transcriptionText?.text = text
            transcriptionText?.visibility = View.VISIBLE
        }
    }

    override fun updateORB(rms: Float) {
        orbView?.updateRms(rms)
    }

    override fun setOrbVisibility(visible: Boolean) {
        serviceScope.launch(Dispatchers.Main) {
            if (visible) {
                overlayView?.visibility = View.VISIBLE
                orbView?.visibility = View.VISIBLE
                showOverlay()
            } else {
                orbView?.visibility = View.GONE
                overlayView?.visibility = View.GONE
            }
        }
    }

    override fun onRecognizerReady() {
        Log.d(TAG, "Reconocedor listo")
    }

    override fun getCurrentScreenText(): List<String> = ScreenMemory.lastSeenTexts

    override fun showToast(text: String) {
        // Implementa si necesitas toasts desde el servicio
    }

    // ────────────────────────────────────────────────────────────────────────
    // OVERLAY UI
    // ────────────────────────────────────────────────────────────────────────

    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_jarvis, null)
        orbView = overlayView?.findViewById(R.id.overlayOrb)
        transcriptionText = overlayView?.findViewById(R.id.overlayTranscription)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

        orbView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    controller.toggleMic()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> false
            }
        }
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error al crear overlay: ${e.message}")
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis Voice Assistant", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Nexus está escuchando" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus Activo")
            .setContentText("Di 'Hey Nexus' para activar")
            .setSmallIcon(R.drawable.ic_mic_vector)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun hideOverlay() {
        overlayView?.animate()?.alpha(0f)?.scaleX(0.5f)?.scaleY(0.5f)?.setDuration(300)
            ?.withEndAction {
                overlayView?.visibility = View.GONE
                transcriptionText?.visibility = View.GONE
                isOrbVisible = false
            }?.start()
    }

    private fun showOverlay() {
        overlayView?.visibility = View.VISIBLE
        overlayView?.alpha = 0f
        overlayView?.scaleX = 0.5f
        overlayView?.scaleY = 0.5f
        overlayView?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(400)
            ?.withEndAction { isOrbVisible = true }?.start()
    }

    private fun endSession() {
        // No destruir el servicio — solo terminar la sesión y volver a escuchar wake word
        transcriptionText?.post {
            transcriptionText?.text = "Hasta luego"
            transcriptionText?.visibility = View.VISIBLE
        }
        serviceScope.launch {
            delay(1500)
            withContext(Dispatchers.Main) {
                transcriptionText?.visibility = View.GONE
                isSessionActive = true
                Log.d(TAG, "✅ Sesión terminada — Vosk escuchando wake word")
            }
        }
    }
}