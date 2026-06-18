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
import com.example.myapplication.core.ContinuousVoiceEngine
import com.example.myapplication.service.JarvisOverlayService
import com.example.myapplication.ui.screens.JarScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupTts()
        startIdlePulse()

        setContent {
            JarScreen(
                vm = vm,
                onStart = { startPresentacion() },
                onOmitir = { omitirPresentacion() }
            )
        }
    }

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale("es", "ES"))
                ttsReady = true
            }
        }
    }

    private fun startPresentacion() {
        vm.setPhase(JarPhase.INTRO)
        stopPulse()
        vm.updateUi(status = "HABLANDO", color = "#4DEEE9", omitir = true)

        val intro1 = "Hola. Soy Nexus..."
        vm.updateUi(transcription = intro1)

        speak(intro1, "intro_1") {
            // VERIFICACIÓN: ¿Seguimos en la fase INTRO? Si no, no hagas nada.
            if (vm.state.value.phase == JarPhase.INTRO) {
                val intro2 = "Puedo ayudarte..."
                vm.updateUi(transcription = intro2)

                speak(intro2, "intro_2") {
                    // VERIFICACIÓN: ¿Seguimos en la fase INTRO?
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

    private fun omitirPresentacion() {
        // 1. Detener el habla inmediatamente para que no se superponga con la siguiente fase
        if (::tts.isInitialized) {
            tts.stop()
        }

        // 2. IMPORTANTE: Cambiamos el estado del ViewModel PRIMERO
        // Esto hace que en JarUi.kt desaparezca el botón Omitir y el botón Comenzar
        vm.updateUi(
            status = "ESCUCHANDO",
            color = "#4DEEE9", // Color aqua para escucha
            transcription = "Di 'Hey Nexus' para comenzar",
            instruction = "\"Hey Nexus\"",
            omitir = false // Esto oculta el botón que acabas de presionar
        )

        // 3. Cambiamos la fase a nivel lógico
        // Esto es vital porque los callbacks de los "speak" anteriores
        // deben tener un check: if (phase != Phase.INTRO) return
        vm.setPhase(JarPhase.WAITING_WAKEWORD)

        // 4. Activamos los motores de audio
        // Detenemos cualquier proceso previo y arrancamos el motor de escucha
        voiceEngine?.stop()
        iniciarVoiceEngine()

        // Iniciamos la animación visual de "escucha" (pulso más rápido)
        startListeningPulse()
    }

    private fun startWakeWordPhase() {
        vm.setPhase(JarPhase.WAITING_WAKEWORD)
        vm.updateUi(
            status = "ESCUCHANDO",
            color = "#4DEEE9",
            transcription = "Di 'Hey Nexus' para comenzar",
            instruction = "\"Hey Nexus\"",
            omitir = false
        )

        speak("Para activarme, di: Hey Nexus", "prompt") {
            iniciarVoiceEngine()
            startListeningPulse()
        }
    }

    private fun iniciarVoiceEngine() {
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

    private fun onWakeWordDetected() {
        stopPulse()
        voiceEngine?.stop()
        vibrar(120)

        vm.updateUi(status = "ACTIVADO", color = "#1DE0A0", transcription = "¡Hey Nexus! Iniciando...")

        speak("Listo.", "final") {
            lifecycleScope.launch {
                delay(200)
                vm.hideScreen() // Inicia fade out de Compose
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

    // --- Lógica de Audio y Pulsos ---

    private fun speak(text: String, id: String, onDone: () -> Unit) {
        if (!ttsReady) { onDone(); return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { startListeningPulse() }
            override fun onDone(id: String?) { runOnUiThread { stopPulse(); onDone() } }
            override fun onError(id: String?) { runOnUiThread { stopPulse(); onDone() } }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

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

    private fun vibrar(ms: Long) {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else v.vibrate(ms)
    }
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