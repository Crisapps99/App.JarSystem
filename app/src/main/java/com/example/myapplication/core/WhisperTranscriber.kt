package com.example.myapplication.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * WhisperTranscriber — Transcripción local con whisper.cpp vía JNI
 *
 * SETUP REQUERIDO:
 * 1. Agrega whisper.cpp a tu proyecto:
 *    - Clona https://github.com/ggerganov/whisper.cpp
 *    - Copia la carpeta `examples/whisper.android` a tu proyecto
 *    - O usa el wrapper: https://github.com/softmax1/whisper-android
 *
 * 2. En tu build.gradle (app):
 *    android {
 *        defaultConfig {
 *            externalNativeBuild { cmake { cppFlags "-std=c++17" } }
 *        }
 *        externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt" } }
 *    }
 *
 * 3. Descarga el modelo ggml-small.bin (~244MB) o ggml-tiny.bin (~75MB):
 *    https://huggingface.co/ggerganov/whisper.cpp/tree/main
 *    Colócalo en: app/src/main/assets/whisper/
 *
 * 4. Recomendación de modelo según dispositivo:
 *    - Gama alta (8+ GB RAM): ggml-small.bin    → mejor precisión, ~1.5s latencia
 *    - Gama media (4-6 GB):   ggml-base.bin     → balance, ~0.8s latencia
 *    - Gama baja (<4 GB):     ggml-tiny.bin     → rápido, ~0.3s latencia
 */
class WhisperTranscriber(private val context: Context) {

    private var isInitialized = false
    private var whisperContext: Long = 0L  // Puntero nativo al contexto de whisper.cpp

    companion object {
        private const val TAG = "WHISPER_TRANSCRIBER"
        private const val MODEL_FOLDER = "whisper"
        private const val DEFAULT_MODEL = "ggml-tiny.bin"  // Cambia según tu dispositivo
        private const val SAMPLE_RATE = 16000

        // Carga la librería nativa de whisper.cpp
        // Esta librería se compila desde el código C++ de whisper.cpp
        init {
            try {
                System.loadLibrary("whisper")
                Log.d(TAG, "✅ Librería nativa whisper.cpp cargada")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ No se pudo cargar libwhisper.so — revisa el setup de CMake")
                Log.e(TAG, "   Sigue las instrucciones en el comentario de esta clase")
            }
        }

        // ─── Declaraciones JNI ───────────────────────────────────────────────────
        // Estas funciones deben estar implementadas en tu C++ wrapper de whisper.cpp
        // Mira: https://github.com/ggerganov/whisper.cpp/blob/master/examples/whisper.android

        @JvmStatic
        external fun whisperInitFromFile(modelPath: String): Long

        @JvmStatic
        external fun whisperTranscribe(
            ctx: Long,
            audioData: FloatArray,
            numSamples: Int,
            language: String,
            translate: Boolean
        ): String

        @JvmStatic
        external fun whisperFree(ctx: Long)
    }

    /**
     * Inicializa Whisper copiando el modelo de assets a almacenamiento interno.
     * Solo copia si el modelo no existe ya (ahorra tiempo en reinicios).
     *
     * @return true si se inicializó correctamente
     */
    fun init(): Boolean {
        if (isInitialized) {
            Log.w(TAG, "⚠️ Ya inicializado")
            return true
        }

        return try {
            val modelFile = prepareModelFile(DEFAULT_MODEL)

            if (!modelFile.exists()) {
                Log.e(TAG, "❌ Modelo no encontrado: ${modelFile.absolutePath}")
                Log.e(TAG, "   Coloca $DEFAULT_MODEL en app/src/main/assets/$MODEL_FOLDER/")
                return false
            }

            Log.d(TAG, "⏳ Cargando modelo Whisper: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)...")
            val startTime = System.currentTimeMillis()

            // Inicializa el contexto nativo de whisper.cpp
            whisperContext = whisperInitFromFile(modelFile.absolutePath)

            if (whisperContext == 0L) {
                Log.e(TAG, "❌ whisperInitFromFile devolvió 0 — modelo inválido o sin memoria suficiente")
                return false
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "✅ Whisper listo en ${elapsed}ms")
            isInitialized = true
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inicializando Whisper: ${e.message}", e)
            false
        }
    }

    /**
     * Transcribe un buffer de audio a texto.
     *
     * @param audioData  Array de shorts (PCM 16-bit, mono, 16kHz)
     *                   Exactamente como lo graba AudioRecord
     * @param language   Código de idioma: "es" para español, "en" para inglés
     * @return           Texto transcrito, o null si hay error
     */
    fun transcribe(audioData: ShortArray, language: String = "es"): String? {
        if (!isInitialized || whisperContext == 0L) {
            Log.e(TAG, "❌ Whisper no inicializado. Llama init() primero.")
            return null
        }

        if (audioData.isEmpty()) {
            Log.w(TAG, "⚠️ Buffer de audio vacío")
            return null
        }

        return try {
            val startTime = System.currentTimeMillis()

            // Convierte ShortArray (PCM 16-bit) a FloatArray normalizado (-1.0 a 1.0)
            // Whisper.cpp trabaja internamente con floats normalizados
            val floatAudio = convertPcm16ToFloat(audioData)

            // Llama a whisper.cpp a través de JNI
            val result = whisperTranscribe(
                ctx = whisperContext,
                audioData = floatAudio,
                numSamples = floatAudio.size,
                language = language,
                translate = false  // false = transcribir en el idioma original
            ).trim()

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "✅ Transcripción en ${elapsed}ms: \"$result\"")

            if (result.isEmpty() || result == "[BLANK_AUDIO]") null else result

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error transcribiendo: ${e.message}", e)
            null
        }
    }

    /**
     * Convierte PCM 16-bit signed (ShortArray) a float normalizado (FloatArray).
     * Whisper.cpp requiere audio normalizado en rango -1.0 a 1.0.
     */
    private fun convertPcm16ToFloat(pcm: ShortArray): FloatArray {
        val floats = FloatArray(pcm.size)
        for (i in pcm.indices) {
            // Divide por 32768.0f (valor máximo de un short) para normalizar
            floats[i] = pcm[i] / 32768.0f
        }
        return floats
    }

    /**
     * Copia el modelo de assets a almacenamiento interno si no existe ya.
     * Los assets se empaquetan en el APK pero no son accesibles como File,
     * necesitamos copiarlos para que whisper.cpp pueda leerlos.
     */
    private fun prepareModelFile(modelName: String): File {
        val modelDir = File(context.filesDir, MODEL_FOLDER)
        if (!modelDir.exists()) modelDir.mkdirs()

        val modelFile = File(modelDir, modelName)

        if (modelFile.exists()) {
            Log.d(TAG, "📁 Modelo ya existe en: ${modelFile.absolutePath}")
            return modelFile
        }

        Log.d(TAG, "📦 Copiando modelo desde assets...")
        try {
            context.assets.open("$MODEL_FOLDER/$modelName").use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                    Log.d(TAG, "✅ Copiados ${totalBytes / 1024 / 1024}MB a ${modelFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copiando modelo: ${e.message}")
            modelFile.delete()  // Borra archivo incompleto
        }

        return modelFile
    }

    /**
     * Libera los recursos nativos de whisper.cpp.
     * SIEMPRE llama esto cuando ya no necesites el transcriptor.
     */
    fun destroy() {
        if (isInitialized && whisperContext != 0L) {
            try {
                whisperFree(whisperContext)
                whisperContext = 0L
                isInitialized = false
                Log.d(TAG, "✅ Recursos Whisper liberados")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error liberando Whisper: ${e.message}")
            }
        }
    }

    fun isReady(): Boolean = isInitialized && whisperContext != 0L
}