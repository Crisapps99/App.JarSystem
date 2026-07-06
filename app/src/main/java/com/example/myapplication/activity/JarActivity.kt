package com.example.myapplication.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.core.audio.ContinuousVoiceEngine
import com.example.myapplication.service.JarvisOverlayService
import com.example.myapplication.ui.screens.MainScreen
import com.example.myapplication.viewmodel.JarPhase
import com.example.myapplication.viewmodel.JarVm
import kotlinx.coroutines.*
import java.util.*

class JarActivity : ComponentActivity() {
    private val vm: JarVm by viewModels()
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var voiceEngine: ContinuousVoiceEngine? = null
    private var audioVisualizer: Visualizer? = null
    private var pulseJob: Job? = null

    // ─── onNewIntent corregido ──────────────────────────────────────────
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleResetIntent(intent)
    }
    private fun handleResetIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("reset_to_wakeword", false) == true) {
            resetToWakeWord()
            intent.removeExtra("reset_to_wakeword") // limpia el extra para que no se repita
        }
    }
    // ─── Reseteo a modo wake word ──────────────────────────────────────
    private fun resetToWakeWord() {
        matarVoiceEngine()
        stopPulse()
        vm.setPhase(JarPhase.WAITING_WAKEWORD)
        vm.updateUi(
            status = "ESPERANDO",
            color = "#8E939E",
            transcription = "", // Vacío para que MainScreen muestre "Da clic en el micrófono..."
            instruction = "",
            omitir = false
        )
        vm.updateRms(0f)
        startIdlePulse()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleResetIntent(intent)
        setupTts()

        // Determinar si es la primera ejecución (solo una vez)
        val isFirstRun = true // cámbialo según prefieras

        // Si el intent contiene el extra "reset_to_wakeword", forzar modo wake word
        if (intent?.getBooleanExtra("reset_to_wakeword", false) == true) {
            resetToWakeWord()
        } else {
            // Flujo normal
            if (isFirstRun) {
                startPresentacion()
            } else {
                startWakeWordPhase()
            }
        }

        setContent {
            MainScreen(
                vm = vm,
                onMicClick = {
                    if (vm.state.value.phase == JarPhase.WAITING_WAKEWORD ||
                        vm.state.value.phase == JarPhase.INTRO) {
                        startManualListening()
                    }
                },
                onStopListening = {
                    stopManualListening()
                }
            )
        }
    }

    // ─────────── CONFIGURACIÓN TTS ───────────
    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale("es", "ES"))
                ttsReady = true
            }
        }
    }

    // ─────────── FLUJO DE INTRODUCCIÓN ───────────
    private fun startPresentacion() {
        vm.setPhase(JarPhase.INTRO)
        stopPulse()
        vm.updateUi(status = "HABLANDO", color = "#4DEEE9", omitir = true)

        val intro1 = "Hola. Soy Nexus..."
        vm.updateUi(transcription = intro1)

        speak(intro1, "intro_1") {
            if (vm.state.value.phase == JarPhase.INTRO) {
                val intro2 = "Puedo ayudarte..."
                vm.updateUi(transcription = intro2)

                speak(intro2, "intro_2") {
                    if (vm.state.value.phase == JarPhase.INTRO) {
                        lifecycleScope.launch {
                            delay(400)
                            startWakeWordPhase()
                        }
                    }
                }
            }
        }
    }

    // ─────────── ESPERAR PALABRA CLAVE ───────────
    private fun startWakeWordPhase() {
        voiceEngine?.detenerSesion()
        stopPulse()
        vm.setPhase(JarPhase.WAITING_WAKEWORD)
        vm.updateUi(
            status = "ESPERANDO",
            color = "#8E939E",
            transcription = "", // Permite que se dibuje el texto base adaptativo
            instruction = "",
            omitir = false
        )
        startIdlePulse()
    }

    // ─────────── ESCUCHA MANUAL ───────────
    // ACTIVAR: Enciende el hardware desde cero de forma segura
    private fun startManualListening() {
        vm.setPhase(JarPhase.LISTENING)
        vm.updateUi(
            status = "ESCUCHANDO",
            color = "#3DF2FF",
            transcription = ""
        )

        iniciarVoiceEngine()
        voiceEngine?.iniciarSesionContinua("es")
        startListeningPulse()
    }

    private fun stopManualListening() {
        matarVoiceEngine() // ← Cambiado de detenerSesion() a matarVoiceEngine()
        stopPulse()
        vm.setPhase(JarPhase.WAITING_WAKEWORD)
        vm.updateUi(
            status = "ESPERANDO",
            color = "#8E939E",
            transcription = ""
        )
        vm.updateRms(0f)
        startIdlePulse()
    }

    // ─────────── MOTOR DE VOZ CONTINUA ───────────
    private fun iniciarVoiceEngine() {
        matarVoiceEngine() // Nos aseguramos de que no haya duplicados residuales
        voiceEngine = ContinuousVoiceEngine(
            context = this,
            onWakeWordDetected = {
                runOnUiThread { onWakeWordDetected() }
            },
            onFinalResult = {},
            onPartialResult = {},
            onRmsChanged = { rms -> vm.updateRms(rms) }
        )
    }
    // Método helper para limpiar y destruir el reconocedor de voz por completo
    private fun matarVoiceEngine() {
        try {
            voiceEngine?.detenerSesion()
            voiceEngine?.stop() // Cierra canales de audio e hilos nativos
        } catch (e: Exception) {
            Log.e("JAR_ACTIVITY", "Error al destruir el motor de voz: ${e.message}")
        } finally {
            voiceEngine = null // Liberación de memoria
        }
    }
    private fun onWakeWordDetected() {
        stopPulse()
        matarVoiceEngine()
        vibrar(120)

        vm.updateUi(status = "ACTIVADO", color = "#1DE0A0", transcription = "¡Hey Nexus! Iniciando...")

        speak("Listo.", "final") {
            lifecycleScope.launch {
                delay(200)
                vm.hideScreen()
                delay(400)
                lanzarOverlayYCerrar()
            }
        }
    }

    private fun lanzarOverlayYCerrar() {
        val intent = Intent(this, JarvisOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        sendBroadcast(Intent("com.nexus.assistant.WAKE_WORD_DETECTED"))
        finishAffinity()
    }

    // ─────────── TTS ───────────
    private fun speak(text: String, id: String, onDone: () -> Unit) {
        if (!ttsReady) { onDone(); return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { startListeningPulse() }
            override fun onDone(id: String?) { runOnUiThread { stopPulse(); onDone() } }
            override fun onError(id: String?) { runOnUiThread { stopPulse(); onDone() } }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    // ─────────── PULSOS PARA EL ORBE ───────────
    private fun startIdlePulse() = startPulseAnimation(3000, 3f)
    private fun startListeningPulse() = startPulseAnimation(1800, 5f)

    private fun startPulseAnimation(duration: Long, maxRms: Float) {
        stopPulse()
        pulseJob = lifecycleScope.launch {
            var startTime = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed % duration).toFloat() / duration
                val sinValue = kotlin.math.sin(progress * 2 * kotlin.math.PI).toFloat()
                val rms = (sinValue + 1) / 2 * maxRms
                vm.updateRms(rms)
                delay(16)
            }
        }
    }

    private fun stopPulse() {
        pulseJob?.cancel()
        vm.updateRms(0f)
    }

    // ─────────── VIBRACIÓN ───────────
    private fun vibrar(ms: Long) {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else v.vibrate(ms)
    }

    // ─────────── NAVEGACIÓN ───────────
    override fun onBackPressed() {
        super.onBackPressed()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        tts.shutdown()
        voiceEngine?.stop()
        stopPulse()
        super.onDestroy()
    }
}