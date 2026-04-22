package com.example.myapplication.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Detector de wake word basado en Vosk (offline, open source).
 * Sustituye a Porcupine. Usa un modelo de español pequeño (~40MB)
 * con gramática restringida para máxima eficiencia.
 */
class VoskWakeWordDetector(
    private val context: Context,
    private val wakeWords: List<String> = listOf(
        "hey nexus", "nexus", "ey nexus", "he nexus", "nexo"
    ),
    private val onWakeWordDetected: () -> Unit
) : RecognitionListener {

    companion object { private const val TAG = "VOSK_WAKE" }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    @Volatile private var isActive = false
    @Volatile private var modelListo = false
    @Volatile private var detectado = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Carga el modelo Vosk desde assets. Tarda 1-3s la primera vez.
     * Llama a onReady cuando está listo para usar.
     */
    fun init(onReady: () -> Unit, onError: (String) -> Unit = {}) {
        if (modelListo) { onReady(); return }

        StorageService.unpack(
            context,
            "model-es",
            "model",
            { unpackedModel ->
                model = unpackedModel
                modelListo = true
                Log.d(TAG, "✅ Modelo Vosk listo")
                mainHandler.post { onReady() }
            },
            { exception ->
                Log.e(TAG, "❌ Error cargando modelo: ${exception.message}")
                mainHandler.post { onError(exception.message ?: "Error desconocido") }
            }
        )
    }

    fun start() {
        if (isActive) { Log.w(TAG, "Ya activo"); return }
        if (!modelListo || model == null) {
            Log.w(TAG, "⚠️ Modelo no cargado — llama init() primero")
            return
        }

        detectado = false

        try {
            // Gramática restringida: Vosk solo intenta reconocer estas palabras
            // [unk] captura todo lo demás como "desconocido" (muy eficiente)
            val grammar = """["hey nexus", "nexus", "ey nexus", "[unk]"]"""
            val recognizer = Recognizer(model, 16000.0f, grammar)

            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            isActive = true
            Log.d(TAG, "🎙️ Escuchando wake word")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error iniciando: ${e.message}", e)
            isActive = false
        }
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo: ${e.message}")
        }
        speechService = null
        Log.d(TAG, "⏹️ Wake word detenido")
    }

    fun destroy() {
        stop()
        try {
            model?.close()
        } catch (_: Exception) {}
        model = null
        modelListo = false
    }

    fun isActive(): Boolean = isActive
    fun isReady(): Boolean = modelListo

    // ── Callbacks de Vosk ─────────────────────────────────────────

    override fun onPartialResult(hypothesis: String?) {
        val texto = extraerTexto(hypothesis, "partial") ?: return
        if (texto.isNotBlank()) {
            Log.v(TAG, "Parcial: '$texto'")
            if (contieneWakeWord(texto)) disparar()
        }
    }

    override fun onResult(hypothesis: String?) {
        val texto = extraerTexto(hypothesis, "text") ?: return
        if (texto.isNotBlank()) {
            Log.d(TAG, "Resultado: '$texto'")
            if (contieneWakeWord(texto)) disparar()
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        val texto = extraerTexto(hypothesis, "text") ?: return
        if (contieneWakeWord(texto)) disparar()
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Error: ${exception?.message}")
    }

    override fun onTimeout() {
        // Vosk reinicia automáticamente
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun extraerTexto(json: String?, campo: String): String? {
        if (json.isNullOrBlank()) return null
        return try {
            JSONObject(json).optString(campo, "")
        } catch (e: Exception) {
            null
        }
    }

    private fun contieneWakeWord(texto: String): Boolean {
        val t = texto.lowercase().trim()
        if (t.isBlank() || t == "[unk]") return false
        return wakeWords.any { t.contains(it) }
    }

    private fun disparar() {
        if (detectado) return
        detectado = true
        Log.i(TAG, "🔥 Wake word detectado")
        stop()
        mainHandler.post { onWakeWordDetected.invoke() }
    }
}