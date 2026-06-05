package com.example.myapplication.activity
//
//import ai.picovoice.android.voiceprocessor.VoiceProcessor
//import ai.picovoice.porcupine.PorcupineException
//import ai.picovoice.porcupine.PorcupineManager
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.core.JarvisState
import com.example.myapplication.core.JarvisUi
import com.example.myapplication.core.JarvisVoiceController
import com.example.myapplication.databinding.ActivityJarBinding
import com.example.myapplication.service.JarvisOverlayService
import com.ncorti.slidetoact.SlideToActView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.myapplication.core.VoskWakeWordDetector
import com.example.myapplication.service.MyAccessibilityService

class JarActivity : AppCompatActivity(), JarvisUi {

    private lateinit var binding: ActivityJarBinding
    private lateinit var controller: JarvisVoiceController
    private val RECORD_AUDIO_PERMISSION_CODE = 200
    private var wakeDetector: VoskWakeWordDetector? = null
    private var currentJarvisState: JarvisState = JarvisState.IDLE
    private var audioVisualizer: android.media.audiofx.Visualizer? = null
    private var hasDetectedWakeWord = false
    private var isListeningForWakeWord = false
    private var wakeWordDetectionActive = false
    private var ttsEnProgreso = false
    private lateinit var audioManager: com.example.myapplication.core.AudioManager
    private var voiceEngine: com.example.myapplication.core.ContinuousVoiceEngine? = null
    private var wakeWordActivado = false
    // ── Fases de la presentación ─────────────────────────────
    private enum class Phase { INTRO, WAITING_WAKEWORD, DETECTED }
    private var currentPhase = Phase.INTRO
    private lateinit var ttsLocal: android.speech.tts.TextToSpeech
    private var ttsLocalListo = false
    private val overlayReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.nexus.assistant.OVERLAY_READY") {
                sendBroadcast(Intent("com.nexus.assistant.WAKE_WORD_DETECTED"))
            }
        }
    }

    // ════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.jarvisOrb.visibility = View.VISIBLE
        audioManager = com.example.myapplication.core.AudioManager(this)
        ttsLocal = android.speech.tts.TextToSpeech(this) { status ->
            ttsLocalListo = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ttsLocalListo) ttsLocal.setLanguage(java.util.Locale("es", "ES"))
        }
        registerReceiver(
            overlayReadyReceiver,
            IntentFilter("com.nexus.assistant.OVERLAY_READY"),
            RECEIVER_NOT_EXPORTED
        )
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. Detener motores de audio y TTS para que no sigan sonando en segundo plano
                try {
                    ttsLocal.stop()
                    voiceEngine?.stop()
                    voiceEngine = null
                    stopIdlePulse()
                    stopListeningPulse()
                } catch (e: Exception) {
                    Log.e(TAG, "Error al limpiar componentes en el retroceso: ${e.message}")
                }

                // 2. Transición visual suave hacia atrás
                binding.root.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        // Finaliza esta actividad y vuelve de forma natural a MainActivity
                        finish()
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                    .start()
            }
        })
        animateOrbEntrance()
    }
    override fun showSearchResult(textoCompleto: String, fuentes: List<String>, imagenes: List<String>, preguntas: List<String>) {
        // En la Activity de presentación no necesitamos mostrar búsquedas completas,
        // pero podemos al menos mostrar el texto principal.
        runOnUiThread {
            animateTextChange(textoCompleto.take(200)) // o simplemente showText(textoCompleto)
            // Si quieres, también puedes mostrar un toast indicando que hay fuentes/imágenes
            if (fuentes.isNotEmpty()) {
                Toast.makeText(this, "Se encontraron ${fuentes.size} fuentes", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // ── TTS — redirige al controller (ElevenLabs o Android según TTS_MODE) ──
    private fun speak(text: String, utteranceId: String, onDone: (() -> Unit)? = null) {
        if (!ttsLocalListo) { onDone?.invoke(); return }
        startTtsPulse()
        ttsLocal.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                runOnUiThread { stopTtsPulse(); onDone?.invoke() }
            }
            override fun onError(id: String?) {
                runOnUiThread { stopTtsPulse(); onDone?.invoke() }
            }
        })
        ttsLocal.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
    private var ttsPulseAnimator: ValueAnimator? = null

    private fun startTtsPulse() {
        stopIdlePulse()
        stopListeningPulse()
        ttsPulseAnimator?.cancel()
        ttsPulseAnimator = null
        // El Visualizer(0) captura el audio del TTS en tiempo real
    }

    private fun stopTtsPulse() {
        ttsPulseAnimator?.cancel()
        ttsPulseAnimator = null
        binding.jarvisOrb.updateRms(0f)
        if (currentPhase == Phase.WAITING_WAKEWORD) startListeningPulse()
        else startIdlePulse()
    }

    // ── Entrada del orbe ────────────────────────────────────
//    private fun animateOrbEntrance() {
//        binding.jarvisOrb.visibility = View.INVISIBLE
//        binding.jarvisOrb.scaleX = 0f
//        binding.jarvisOrb.scaleY = 0f
//        binding.jarvisOrb.alpha  = 0f
//
//        binding.jarvisOrb.postDelayed({
//            binding.jarvisOrb.visibility = View.VISIBLE
//            binding.jarvisOrb.animate()
//                .scaleX(1f).scaleY(1f).alpha(1f)
//                .setDuration(1000)
//                .setInterpolator(OvershootInterpolator(1.2f))
//                .withEndAction { showIntroUI() }
//                .start()
//        }, 300)
//    }
    private fun animateOrbEntrance() {
        // Puedes mostrar un fade in del texto o skip directamente
        //binding.titleWel.alpha = 1f
        //binding.statusText.alpha = 1f
        showIntroUI()
    }
    // ── UI de introducción ───────────────────────────────────
    private fun showIntroUI() {
       // binding.titleWel.animate().alpha(1f).setDuration(600).setStartDelay(0).start()
        //binding.statusText.animate().alpha(1f).setDuration(600).setStartDelay(200).start()

        binding.transcriptionTextView.text =
            "Hola, soy Nexus. Tu asistente personal para el móvil.\n" +
                    "Estoy aquí para ayudarte con llamadas, mensajes, apps y mucho más."
        binding.instructionsText.visibility = View.GONE

        binding.cardTranscription.animate().alpha(1f).setDuration(700).setStartDelay(400).start()
        binding.btnComenzar.animate().alpha(1f).setDuration(700).setStartDelay(600).start()

        startIdlePulse()

        binding.btnComenzar.onSlideCompleteListener = object : SlideToActView.OnSlideCompleteListener {
            override fun onSlideComplete(view: SlideToActView) {
                startPresentacion()
            }
        }
    }

    // ── Presentación con TTS ─────────────────────────────────
    // ── Presentación con TTS Modificada ───────────────────────────────
    private fun startPresentacion() {
        currentPhase = Phase.INTRO
        stopIdlePulse()

        // Ocultamos el botón de comenzar y mostramos el de omitir
        binding.btnComenzar.animate().alpha(0f).setDuration(300).start()

        binding.btnOmitir.visibility = View.VISIBLE
        binding.btnOmitir.alpha = 0f
        binding.btnOmitir.animate().alpha(1f).setDuration(300).start()

        // Configuramos la acción del botón Omitir
        binding.btnOmitir.setOnClickListener {
            omitirPresentacion()
        }

        setStatusLabel("HABLANDO", "#4DEEE9")
        animateTextChange(
            "Hola. Soy Nexus, tu asistente personal inteligente. " +
                    "Fui diseñado para ayudarte en todo momento, directamente desde tu teléfono."
        )

        speak(
            "Hola. Soy Nexus, tu asistente personal inteligente. " +
                    "Fui diseñado para ayudarte en todo momento, directamente desde tu teléfono.",
            "intro_1"
        ) {
            // Verificación por si el usuario ya presionó omitir mientras hablaba la primera parte
            if (currentPhase != Phase.INTRO) return@speak

            animateTextChange(
                "Puedo ayudarte a hacer llamadas, leer Notificaciones, abrir aplicaciones y mucho más, " +
                        "todo con tu voz."
            )
            speak(
                "Puedo ayudarte a hacer llamadas, leer Notificaciones, abrir aplicaciones y mucho más, " +
                        "todo con tu voz.",
                "intro_2"
            ) {
                if (currentPhase != Phase.INTRO) return@speak
                lifecycleScope.launch {
                    delay(400)
                    // Si llega al final de forma natural, ocultamos omitir y vamos al wake word
                    binding.btnOmitir.animate().alpha(0f).setDuration(300).withEndAction {
                        binding.btnOmitir.visibility = View.GONE
                    }.start()
                    startWakeWordPhase()
                }
            }
        }
    }

    // ── Función Nueva: Salto Directo a Hey Nexus ──────────────────────
    // ── Función Nueva: Salto Directo a Hey Nexus (Corregido) ──────────────────────
    private fun omitirPresentacion() {
        if (currentPhase != Phase.INTRO) return

        // 1. Detener todo audio
        ttsLocal.stop()
        setOrbPulsing(false)

        // 2. Ocultar botón omitir
        binding.btnOmitir.animate().alpha(0f).setDuration(200).withEndAction {
            binding.btnOmitir.visibility = View.GONE
        }.start()

        // 3. Iniciar fase de wake word PERO SIN ACTIVAR DETECTOR TODAVÍA
        lifecycleScope.launch {
            delay(300)
            startWakeWordPhase()
        }
    }
//
// ── NUEVA: Fase de wake word que espera a que termine el TTS ──
private fun startWakeWordPhasePeroEsperarTTS() {
    currentPhase = Phase.WAITING_WAKEWORD
    wakeWordDetectionActive = false  // ← CRÍTICO: detector desactivado
    ttsEnProgreso = true

    setStatusLabel("ESCUCHANDO", "#4DEEE9")
    animateTextChange("Para comenzar, di la palabra de activación:")

    binding.instructionsText.text = "\"Hey Nexus\""
    binding.instructionsText.textSize = 22f
    binding.instructionsText.setTextColor(android.graphics.Color.parseColor("#4DEEE9"))
    binding.instructionsText.visibility = View.VISIBLE

    // Reproducir TTS y SOLO después de que termine activar el detector
    speak("Para activarme, di: Hey Nexus", "wakeword_prompt") {
        // ✅ TTS TERMINÓ - Ahora sí activamos el detector
        ttsEnProgreso = false
        activarDetectorWakeWord()
    }
}
    // ── NUEVA: Activar detector de wake word de forma segura ──
    private fun activarDetectorWakeWord() {
        if (wakeWordDetectionActive) return
        if (currentPhase != Phase.WAITING_WAKEWORD) return

        wakeWordDetectionActive = true
//        setupPorcupine()  // Esto inicia la escucha real
        startListeningPulse()

        Log.d(TAG, "✅ Detector wake word ACTIVADO después del TTS")
    }

    // ── Fase de espera de wake word ──────────────────────────
    private fun startWakeWordPhase() {
        currentPhase = Phase.WAITING_WAKEWORD

        setStatusLabel("ESCUCHANDO", "#4DEEE9")
        animateTextChange("Di 'Hey Nexus' para comenzar")

        binding.instructionsText.text = "\"Hey Nexus\""
        binding.instructionsText.textSize = 22f
        binding.instructionsText.setTextColor(android.graphics.Color.parseColor("#4DEEE9"))
        binding.instructionsText.visibility = View.VISIBLE

        speak("Para activarme, di: Hey Nexus", "wakeword_prompt") {
            iniciarDetectorWakeWord()
            startListeningPulse()
        }

    }
    // AÑADIR función:
    private fun iniciarDetectorWakeWord() {
        if (wakeWordActivado) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        voiceEngine = com.example.myapplication.core.ContinuousVoiceEngine(
            context = this,
            onWakeWordDetected = {
                runOnUiThread {
                    if (!wakeWordActivado && currentPhase == Phase.WAITING_WAKEWORD) {
                        wakeWordActivado = true
                        onWakeWordDetected()
                    }
                }
            },
            onFinalResult = { /* JarActivity NO procesa comandos */ },
            onPartialResult = {},
            onRmsChanged = { rms -> runOnUiThread { binding.jarvisOrb.updateRms(rms) } }
        )
    }

    // ── Wake word detectado ──────────────────────────────────
    // REEMPLAZAR onWakeWordDetected():
    private fun onWakeWordDetected() {
        stopListeningPulse()
        voiceEngine?.stop()   // apagar micrófono de esta activity
        voiceEngine = null

        audioManager.playActionSuccess()
        hacerVibrar(120)

        setStatusLabel("ACTIVADO", "#1DE0A0")
        animateTextChange("¡Hey Nexus! Iniciando...")
        binding.instructionsText.visibility = android.view.View.GONE

        // Corto TTS de confirmación → lanza overlay
        speak("Listo.", "detected") {
            lifecycleScope.launch {
                delay(200)
                launchOrbToOverlay()
            }
        }
    }

    private fun hacerVibrar(ms: Long) {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(ms)
            }
        } catch (e: Exception) { }
    }

//    // ── Efecto orbe que "sale" hacia overlay ─────────────────
//    private fun launchOrbToOverlay() {
//        window.setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
//        val scaleUp = AnimatorSet().apply {
//            playTogether(
//                ObjectAnimator.ofFloat(binding.jarvisOrb, "scaleX", 1f, 1.4f),
//                ObjectAnimator.ofFloat(binding.jarvisOrb, "scaleY", 1f, 1.4f),
//                ObjectAnimator.ofFloat(binding.jarvisOrb, "alpha",  1f, 0.9f)
//            )
//            duration = 300
//            interpolator = AccelerateDecelerateInterpolator()
//        }
//
//        val launchUp = AnimatorSet().apply {
//            playTogether(
//                ObjectAnimator.ofFloat(binding.jarvisOrb, "translationY", 0f, -1000f),
//                ObjectAnimator.ofFloat(binding.jarvisOrb, "scaleX", 1.4f, 0.3f),
//                ObjectAnimator.ofFloat(binding.jarvisOrb, "scaleY", 1.4f, 0.3f),
//                ObjectAnimator.ofFloat(binding.jarvisOrb, "alpha",  0.9f, 0f)
//            )
//            duration = 500
//            interpolator = AccelerateInterpolator(2f)
//        }
//
//        val fadeUI = AnimatorSet().apply {
//            playTogether(
//                ObjectAnimator.ofFloat(binding.cardTranscription, "alpha", 1f, 0f),
//                ObjectAnimator.ofFloat(binding.titleWel,  "alpha", 1f, 0f),
//                ObjectAnimator.ofFloat(binding.statusText,"alpha", 1f, 0f)
//            )
//            duration = 400
//        }
//
//        AnimatorSet().apply {
//            play(scaleUp).before(launchUp)
//            play(fadeUI).with(launchUp)
//            addListener(object : AnimatorListenerAdapter() {
//                override fun onAnimationEnd(animation: Animator) {
//                    startOverlayServiceAndFinish()
//                }
//            })
//            start()
//        }
//    }
private fun launchOrbToOverlay() {
    // Animación de fade out de la actividad actual
    binding.main.animate()
        .alpha(0f)
        .setDuration(400)
        .withEndAction {
            startOverlayServiceAndFinish()
        }
        .start()
}
    private fun startOverlayServiceAndFinish() {
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        Log.d(TAG, "canDrawOverlays=$canDraw")
        if (canDraw) {
            val intent = Intent(this, JarvisOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
        sendBroadcast(Intent("com.nexus.assistant.WAKE_WORD_DETECTED"))
        lifecycleScope.launch {
            delay(200)
            // Limpiar todo el backstack y cerrar la app — el overlay flota encima
            finishAffinity()
        }
    }

    // En JarActivity, cuando se activa el modo visual
    private fun checkAccessibilityAndActivate() {
        if (isAccessibilityServiceEnabled(this, MyAccessibilityService::class.java)) {
            controller.procesarComandoExterno("modo visual")
        } else {
            Toast.makeText(this, "Activa el servicio de accesibilidad en Ajustes > Accesibilidad", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
    private fun vPorcupine() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
            return
        }
        wakeDetector = VoskWakeWordDetector(
            context = this, onWakeWordDetected = {
                Log.i("Nexus","hey dexus detectado V")
                runOnUiThread { onWakeWordDetected() }
            }
        )
        //carga modelo y edmpiez aa escuchar
        wakeDetector?.init(
            onReady = {
                wakeDetector?.start()
                isListeningForWakeWord = true
                Log.d("Nexus","Vosk iniciando")
            },
            onError = { msg ->
                Log.e("Nexus","Erorr al cargar Vosk: $msg")
                showRetryMessage()
            }
        )
    }

    private fun showRetryMessage() {
        runOnUiThread {
            setStatusLabel("ERROR", "#E53935")
            animateTextChange("No pude escucharte. Inténtalo de nuevo.")
            lifecycleScope.launch {
                delay(2000)
                hasDetectedWakeWord = false
                isListeningForWakeWord = false
                startWakeWordPhase()
            }
        }
    }

    // ── Animaciones del orbe ─────────────────────────────────
    private var idlePulseAnimator: ValueAnimator? = null
    private var listeningPulseAnimator: ValueAnimator? = null

    private fun startIdlePulse() {
        idlePulseAnimator = ValueAnimator.ofFloat(0f, 3f, 0f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { binding.jarvisOrb.updateRms(it.animatedValue as Float) }
            start()
        }
    }

    private fun stopIdlePulse() {
        idlePulseAnimator?.cancel()
        idlePulseAnimator = null
    }

    private fun startListeningPulse() {
        listeningPulseAnimator = ValueAnimator.ofFloat(0f, 5f, 0f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { binding.jarvisOrb.updateRms(it.animatedValue as Float) }
            start()
        }
    }

    private fun stopListeningPulse() {
        listeningPulseAnimator?.cancel()
        listeningPulseAnimator = null
    }

    private fun setOrbPulsing(active: Boolean) {
        if (active) { stopIdlePulse(); startListeningPulse() }
        else { stopListeningPulse(); startIdlePulse() }
    }

    // ── Helpers UI ───────────────────────────────────────────
    private fun animateTextChange(newText: String) {
        binding.transcriptionTextView.animate()
            .alpha(0f).setDuration(150)
            .withEndAction {
                binding.transcriptionTextView.text = newText
                binding.transcriptionTextView.animate().alpha(1f).setDuration(200).start()
            }.start()
    }

    private fun setStatusLabel(label: String, hexColor: String) {
        binding.statusLabel.text = label
        val color = android.graphics.Color.parseColor(hexColor)
        binding.statusLabel.setTextColor(color)
        binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        binding.statusDot.animate().scaleX(1.4f).scaleY(1.4f).setDuration(200)
            .withEndAction { binding.statusDot.animate().scaleX(1f).scaleY(1f).setDuration(200).start() }
            .start()
    }

    // ── JarvisUI callbacks ───────────────────────────────────
    override fun updateORB(rms: Float) {
        runOnUiThread { binding.jarvisOrb.updateRms(rms) }
    }
    // Implementación obligatoria para solucionar el error de compilación
    override fun setOrbVisibility(visible: Boolean) {
        runOnUiThread {
            if (visible) {
                binding.jarvisOrb.visibility = View.VISIBLE
                binding.jarvisOrb.animate().alpha(1f).setDuration(300).start()
            } else {
                binding.jarvisOrb.animate().alpha(0f).setDuration(300)
                    .withEndAction { binding.jarvisOrb.visibility = View.GONE }
                    .start()
            }
        }
    }
    override fun onRecognizerReady() {
        runOnUiThread { wakeDetector?.stop() }
    }

    override fun renderState(state: JarvisState) {
        runOnUiThread {
            currentJarvisState = state
            when (state) {
                JarvisState.LISTENING -> {
                    setStatusLabel("ESCUCHANDO", "#4DEEE9")
                    animateTextChange("Escuchando...")
                    wakeDetector?.stop()
                }
                JarvisState.THINKING -> {
                    setStatusLabel("PENSANDO", "#7BD7F8")
                    animateTextChange("Procesando...")
                }
                JarvisState.SPEAKING -> {
                    setStatusLabel("HABLANDO", "#4DEEE9")
                }
                JarvisState.IDLE -> {
                    binding.jarvisOrb.reset()
                    if (!hasDetectedWakeWord) {
                        try { wakeDetector?.start() } catch (e: Exception) { }
                    }
                }
            }
        }
    }
    override fun hideOverlayFromTimeout() {
        // No hace nada porque la activity no tiene overlay
    }
    override fun getCurrentScreenText(): List<String> =
        com.example.myapplication.core.ScreenMemory.lastSeenTexts

    override fun showText(text: String) {
        runOnUiThread { animateTextChange(text) }
    }
    override fun getDisplayedText(): String =
        binding.transcriptionTextView.text?.toString() ?: ""
    override fun showToast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }
    override fun showImages(urls: List<String>) {
        // La actividad de presentación no necesita renderizar la galería,
        // pero cumplimos con la interfaz obligatoria.
    }
    // ── Cálculos de audio ────────────────────────────────────
    private fun calculateRMS(audioData: ShortArray): Float {
        var sum = 0.0
        for (sample in audioData) sum += (sample * sample).toDouble()
        return (Math.sqrt(sum / audioData.size).toFloat() / 400f).coerceIn(0f, 10f)
    }

    private fun calculateEnergy(wave: ByteArray): Float {
        var sum = 0.0
        for (b in wave) {
            val s = (b.toInt() and 0xFF) - 128
            sum += (s * s).toDouble()
        }
        return (Math.sqrt(sum / wave.size).toFloat() / 2f).coerceIn(0f, 15f)
    }

    // ── Visualizer ───────────────────────────────────────────
    private fun setupGlobalVisualizer() {
        try {
            audioVisualizer = android.media.audiofx.Visualizer(0).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, wave: ByteArray?, rate: Int) {
                        if (wave != null)  {
                            runOnUiThread { binding.jarvisOrb.updateRms(calculateEnergy(wave)) }
                        }
                    }
                    override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, rate: Int) {}
                }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (e: Exception) {
            Log.e("VISUALIZER", "Error: ${e.message}")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        setupGlobalVisualizer()
    }

    override fun onPause() {
        super.onPause()
        audioVisualizer?.release()
        audioVisualizer = null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(overlayReadyReceiver) }

        // Apagamos el detector de la actividad pase lo que pase
        wakeDetector?.stop()
        wakeDetector = null
        voiceEngine?.stop()
        voiceEngine = null
        audioVisualizer?.release()
        audioVisualizer = null
        stopIdlePulse()
        stopListeningPulse()
        if (::ttsLocal.isInitialized && ttsLocalListo) ttsLocal.shutdown()
        super.onDestroy()
    }

    // ── Accesibilidad util ───────────────────────────────────
    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expected = ComponentName(context, service)
        val enabled  = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected.flattenToString(), ignoreCase = true)) return true
        }
        return false
    }
}