package com.example.myapplication.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.core.*
import com.example.myapplication.ui.ListeningBarView
import kotlinx.coroutines.*

class JarvisOverlayService : Service(), JarvisUi, PorcupineController {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var listeningBar: ListeningBarView? = null
    private var tvListeningLabel: TextView? = null
    private var tvTranscription: TextView? = null
    private var containerOuter: LinearLayout? = null  // Añadir referencia al contenedor

    private lateinit var controller: JarvisVoiceController
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeDetector: VoskWakeWordDetector? = null
    @Volatile private var wakeWordPaused = false
    private var currentJarvisState: JarvisState = JarvisState.IDLE
    private var isOverlayReady = false

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
            controller = JarvisVoiceController(
                context = this,
                ui = this,
                scope = serviceScope,
                porcupineController = this
            )
            controller.init()

            setupWakeWordDetection()
            showOverlay()
            tvTranscription?.text = "Di 'Hey Nexus' para activarme"
        }, 900)
    }

    // Método para expandir el contenedor cuando hay texto largo - CORREGIDO
    private fun expandContainerForText(text: String) {
        val container = containerOuter
        val textView = tvTranscription

        textView?.post {
            val lines = textView.lineCount
            val newPadding = if (lines > 1) 24 else 12

            container?.animate()?.setDuration(300)?.withStartAction {
                container.setPadding(
                    container.paddingLeft,
                    newPadding,
                    container.paddingRight,
                    newPadding
                )
            }?.start()

            container?.requestLayout()
            overlayView?.requestLayout()
        }
    }

    private fun setupWakeWordDetection() {
        try {
            wakeDetector = VoskWakeWordDetector(
                context = this,
                onWakeWordDetected = {
                    Log.d(TAG, "🎤 Wake word detectado!")
                    serviceScope.launch(Dispatchers.Main) {
                        onWakeWordDetected()
                    }
                }
            )

            wakeDetector?.init(
                onReady = {
                    wakeDetector?.start()
                    Log.i(TAG, "✅ Detector de voz listo")
                },
                onError = { msg ->
                    Log.e(TAG, "❌ Error: $msg")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    private fun onWakeWordDetected() {
        pauseWakeWordDetection()
        showOverlay()

        tvListeningLabel?.text = "ESCUCHANDO"
        tvListeningLabel?.setTextColor(Color.parseColor("#4DEEE9"))
        tvTranscription?.text = "¡Te escucho!"

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
                    resumeWakeWordDetection()
                }
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

        mainHandler.post {
            if (!isOverlayReady) return@post

            tvTranscription?.text = text
            tvTranscription?.visibility = View.VISIBLE

            // Expandir contenedor si el texto es largo
            if (text.length > 30 || (text.contains(" ") && text.length > 20)) {
                expandContainerForText(text)
            }

            // Auto-ocultar después de 5 segundos
            if (currentJarvisState == JarvisState.IDLE) {
                mainHandler.postDelayed({
                    if (currentJarvisState == JarvisState.IDLE) {
                        tvTranscription?.text = "Di 'Hey Nexus' para activarme"
                        restoreContainerSize()
                    }
                }, 5000)
            }
        }
    }

    // Restaurar tamaño original - CORREGIDO
    private fun restoreContainerSize() {
        val container = containerOuter
        container?.animate()?.setDuration(300)?.withStartAction {
            container.setPadding(
                container.paddingLeft,
                12,
                container.paddingRight,
                12
            )
        }?.start()
    }

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

    override fun pausarPorcupine() {
        wakeDetector?.stop()
        wakeWordPaused = true
        Log.d(TAG, "Wake word pausado")
    }

    override fun reanudarPorcupine() {
        if (wakeWordPaused) {
            wakeDetector?.start()
            wakeWordPaused = false
            Log.d(TAG, "Wake word reanudado")
        }
    }

    override fun esPorcupinePausado(): Boolean = wakeWordPaused

    private fun pauseWakeWordDetection() {
        wakeDetector?.stop()
        wakeWordPaused = true
    }

    private fun resumeWakeWordDetection() {
        if (wakeWordPaused) {
            wakeDetector?.start()
            wakeWordPaused = false
        }
    }

    // ─── UI ───────────────────────────────────────────────────
    private fun createOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = LayoutInflater.from(this).inflate(R.layout.view_bar, null)

            listeningBar = overlayView!!.findViewById(R.id.listeningBar)
            tvListeningLabel = overlayView!!.findViewById(R.id.tvListeningLabel)
            tvTranscription = overlayView!!.findViewById(R.id.tvTranscription)
            containerOuter = overlayView!!.findViewById(R.id.containerOuter)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM

            windowManager?.addView(overlayView, params)
            overlayView?.visibility = View.GONE
            isOverlayReady = true
            Log.d(TAG, "✅ Overlay creado y listo")
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
            }
        }
    }

    private fun hideOverlay() {
        if (!isOverlayReady) return

        overlayView?.let {
            it.animate().alpha(0f).setDuration(300).withEndAction {
                it.visibility = View.GONE
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
        wakeDetector?.stop()
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