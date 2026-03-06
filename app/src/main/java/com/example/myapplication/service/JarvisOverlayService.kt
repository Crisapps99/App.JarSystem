package com.example.myapplication.service

import ai.picovoice.android.voiceprocessor.VoiceProcessor
import ai.picovoice.porcupine.Porcupine.BuiltInKeyword
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.core.JarvisState
import com.example.myapplication.core.JarvisUi
import com.example.myapplication.core.JarvisVoiceController
import com.example.myapplication.ui.JarvisOrbView
import kotlinx.coroutines.*
import kotlin.math.sqrt

class JarvisOverlayService : Service(), JarvisUi {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var orbView: JarvisOrbView? = null
    private var transcriptionText: TextView? = null

    private lateinit var controller: JarvisVoiceController
    private val serviceScope =
        CoroutineScope(Dispatchers.Main + SupervisorJob()) //maneja tareas en segundo plato

    private var porcupineManager: PorcupineManager? = null
    private val ACCESS_KEY = "YMYKZrTBnmQeviXKwGY8rrXUiUlcHBC1ApCQwg6G99JrluupBCFbUg=="
    private var currentJarvisState: JarvisState = JarvisState.IDLE
    private var audioVisualizer: Visualizer? = null //captura el sonido del sistema
    private val KEYWORD_FILE = "hey-nexus_es_android_v4_0_0.ppn"   // ← tu archivo
    private val MODEL_FILE   = "porcupine_params_es.pv"
    private var isOrbVisible = true
    private var isSessionActive = true
    private var isInitialized = false

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "jarvis_overlay_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("JARVIS_OVERLAY", "Servicio creado")
        //notificacion de serviico
        startForeground(NOTIFICATION_ID, createNotification())
        serviceScope.launch {
            delay(500)
            initializeService()
        }
    }
    override fun setOrbVisibility(visible: Boolean) {
        serviceScope.launch(Dispatchers.Main) {
            if (visible) {
                // Mostramos el orbe y su contenedor
                overlayView?.visibility = View.VISIBLE
                orbView?.visibility = View.VISIBLE
                showOverlay() // Ejecuta la animación de entrada si la tienes
            } else {
                // Ocultamos el orbe
                orbView?.visibility = View.GONE
                // IMPORTANTE: También ocultamos el contenedor principal para que
                // no intercepte los clics del Modo Visual
                overlayView?.visibility = View.GONE
                Log.d("JARVIS_OVERLAY", "Orbe y contenedor ocultos para Modo Visual")
            }
        }
    }
    private fun initializeService() {
        if (isInitialized) {
            Log.w("JARVIS_OVERLAY", "Servicio ya inicializado, ignorando")
            return
        }

        Log.d("JARVIS_OVERLAY", "Inicializando componentes del servicio...")

        //  Crear overlay
        createOverlay()

        // Inicializar controlador de voz
        controller = JarvisVoiceController(
            context = this,
            ui = this,
            scope = serviceScope
        )
        controller.init()
        Log.d("JARVIS_OVERLAY", "✓ Controlador de voz inicializado")

        // (detección de palabra clave)
        setupPorcupine()

        // visualizador de audio
        setupGlobalVisualizer()

        isInitialized = true
        Log.d("JARVIS_OVERLAY", "✓ Servicio completamente inicializado")
    }

    private fun createNotification(): Notification {
        //canal de notificacion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "jarvis Voice Assitent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "jarvis esta escuchando "
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Activo")
            .setContentText("Di 'Hola Jarvis' para activar")
            .setSmallIcon(R.drawable.ic_mic_vector)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Inflar el layout del overlay
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_jarvis, null)

        orbView = overlayView?.findViewById(R.id.overlayOrb)
        transcriptionText = overlayView?.findViewById(R.id.overlayTranscription)

        //ventana flotante
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, // Ancho ajustado al contenido
            WindowManager.LayoutParams.MATCH_PARENT, // Alto ajustado al contenido
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_TOUCH_MODAL permite que los toques fuera del orbe se detecten
            // FLAG_WATCH_OUTSIDE_TOUCH es la clave aquí
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, //trasnparencia
            PixelFormat.TRANSLUCENT
        )
        // 1. TOQUE EN EL FONDO (Ocultar)
        overlayView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isOrbVisible) {
                    hideOverlay()
                }
            }
            false // Permite que el click llegue a las apps de abajo
        }
        // 2. TOQUE EN EL ORBE (Bloquear ocultación)
        orbView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> false
            }
        }
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e("JARVIS", "Error al crear overlay: ${e.message}")
        }
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // Permitir arrastrar el orbe (opcional)
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        // Si el movimiento fue pequeño, considerarlo un tap
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                            // Es un tap, no hacer nada (mantener visible)
                            return true
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Detectar toques FUERA del overlay
        overlayView?.setOnClickListener {
            // Click dentro del orbe, no hacer nada
        }

        // Para detectar toques fuera, usamos un GestureDetector
        val gestureDetector =
            GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    // Si tocaron el orbe, mantenerlo visible
                    return true
                }
            })
    }

    private fun hideOverlay() {
        overlayView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.5f)
            ?.scaleY(0.5f)
            ?.setDuration(300)
            ?.withEndAction {
                overlayView?.visibility = View.GONE
                transcriptionText?.visibility = View.GONE
                isOrbVisible = false
            }
            ?.start()

        Log.d("JARVIS_OVERLAY", "Orbe ocultado")
    }

    private fun showOverlay() {
        overlayView?.visibility = View.VISIBLE
        overlayView?.alpha = 0f
        overlayView?.scaleX = 0.5f
        overlayView?.scaleY = 0.5f

        overlayView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(400)
            ?.withEndAction {
                isOrbVisible = true
            }
            ?.start()

        Log.d("JARVIS_OVERLAY", "Orbe mostrado")
    }

    private fun setupGlobalVisualizer() {
        try {
            audioVisualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?,
                        wave: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (currentJarvisState == JarvisState.SPEAKING && wave != null) {
                            val energy = calculateEnergy(wave)
                            orbView?.updateRms(energy)
                        }
                    }

                    override fun onFftDataCapture(
                        v: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (e: Exception) {
            Log.e("JARVIS_OVERLAY", "Error al iniciar visualizer: ${e.message}")
        }
    }

    private fun calculateEnergy(wave: ByteArray): Float {
        var sum = 0.0
        for (i in wave.indices) {
            val sample = (wave[i].toInt() and 0xFF) - 128
            sum += (sample * sample).toDouble()
        }
        val rms = sqrt(sum / wave.size).toFloat()
        return (rms / 5f).coerceIn(0f, 15f)
    }

    private fun setupPorcupine() {
        try {
            VoiceProcessor.getInstance()
                .clearFrameListeners() // el voice processor est elimpio antes de empesar
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(ACCESS_KEY)
                .setKeywordPath(KEYWORD_FILE)  // ← "Hey Nexus" en español
                .setModelPath(MODEL_FILE)
                .setSensitivity(0.7f)
                .build(this) { keywordIndex ->
                    if (keywordIndex == 0 && isSessionActive) {
                        serviceScope.launch(Dispatchers.Main) { onWakeWordDetected() }
                    }
                }
            //escucha pasiva mueve liegramente el orbe
            VoiceProcessor.getInstance().addFrameListener { frame ->
                val rms = calculateRMS(frame)
                if (currentJarvisState == JarvisState.IDLE) {
                    orbView?.post { orbView?.updateRms(rms) }
                }
            }
            porcupineManager?.start()
            Log.i("JARVIS_OVERLAY", "✅ Porcupine escuchando 'Hey Nexus'")
        } catch (e: PorcupineException) {
            Log.e("JARVIS_OVERLAY", "❌ PorcupineException: ${e.message}")
        } catch (e: Exception) {
            Log.e("JARVIS_OVERLAY", "❌ Error Porcupine: ${e.message}")
        }
    }

    private fun calculateRMS(audioData: ShortArray): Float {
        var sum = 0.0
        for (sample in audioData) {
            sum += (sample * sample).toDouble()
        }
        val average = sum / audioData.size
        val rms = sqrt(average)
        return (rms.toFloat() / 400f).coerceIn(0f, 10f)
    }

    private fun onWakeWordDetected() {
        Log.d("JARVIS_OVERLAY", "Procesando detección de palabra clave...")

        if (!isOrbVisible) {
            serviceScope.launch(Dispatchers.Main) {
                showOverlay()
            }
        }
        transcriptionText?.post {
            transcriptionText?.text = "¡Te escucho!"
            transcriptionText?.visibility = View.VISIBLE
        }

        serviceScope.launch {
            delay(500)
            controller.startInteraction()
        }
    }

    // Implementación de JarvisUi
    override fun updateORB(rms: Float) {
        orbView?.updateRms(rms)
    }

    override fun onRecognizerReady() {
        porcupineManager?.stop()
        Log.d("JARVIS_OVERLAY", "Reconocedor listo")
    }

    //interfaz a respuesta de eventos
    override fun renderState(state: JarvisState) {
        currentJarvisState = state
        orbView?.post {
            when (state) {
                JarvisState.LISTENING -> {
                    transcriptionText?.text = "escuchando"
                    transcriptionText?.visibility = View.VISIBLE
                    porcupineManager?.stop() // Dejamos de escuchar la palabra clave mientras grabamos voz
                }

                JarvisState.THINKING -> {
                    transcriptionText?.text = "Procesando..."
                    transcriptionText?.visibility = View.VISIBLE
                }

                JarvisState.SPEAKING -> {
                    transcriptionText?.visibility = View.VISIBLE
                    // El visualizador se encarga de mover el orbe mientras Jarvis habla
                    if (audioVisualizer == null) setupGlobalVisualizer()
                }

                JarvisState.IDLE -> {
                    orbView?.reset() // Orbe a tamaño normal
                    transcriptionText?.visibility = View.GONE
                    reiniciarEscuchaPasiva() // Volvemos a esperar el "Hola Jarvis"
                }
            }
        }

    }

    override fun showText(text: String) {

        // Solo cerrar sesión si dice exactamente "salir" sin contexto de modo visual
        val t = text.lowercase().trim()
        val esSalidaModoVisual = listOf(
            "salir de modo visual", "salir modo visual",
            "desactivar modo visual", "cerrar modo visual",
            "quitar modo visual"
        ).any { t.contains(it) }

        // Si es un comando de modo visual, no cerrar la sesión
        if (!esSalidaModoVisual && (t == "salir" || t == "salir." || t == "adiós" || t == "adios")) {
            endSession()
            return
        }

        transcriptionText?.post {
            transcriptionText?.text = text
            transcriptionText?.visibility = View.VISIBLE
        }
    }

    private fun endSession() {
        isSessionActive = false
        transcriptionText?.text = "Hasta Luego"
        serviceScope.launch {
            delay(1500)
            stopSelf()
        }
    }

    override fun getCurrentScreenText(): List<String> {
        return com.example.myapplication.core.ScreenMemory.lastSeenTexts
    }

    override fun showToast(text: String) {
        // No mostrar toasts en modo overlay
    }

    private fun reiniciarEscuchaPasiva() {
        try {
            if (isSessionActive) {
                porcupineManager?.start()
                Log.d("JARVIS_OVERLAY", "Vigilancia reactivada")
            }
        } catch (e: Exception) {
            Log.e("JARVIS_OVERLAY", "Error al reiniciar Porcupine: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
// LIMPIEZA: Si no haces esto, el micrófono se queda prendido y la app gasta mucha batería
        porcupineManager?.delete()
        VoiceProcessor.getInstance().clearFrameListeners()
        audioVisualizer?.release()
        controller.destroy()
        if (overlayView != null) {
            windowManager?.removeView(overlayView) // Quitar el orbe de la pantalla
        }
        serviceScope.cancel() // Detener tareas pendientes
        Log.d("JARVIS_OVERLAY", "Servicio destruido")
    }

}