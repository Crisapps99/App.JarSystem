package com.example.myapplication.core.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.gax.rpc.ClientStream
import com.google.api.gax.rpc.ResponseObserver
import com.google.api.gax.rpc.StreamController
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechSettings
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * GrpcVoiceRecognizer — Cliente gRPC para Google Cloud STT
 */
class GrpcVoiceRecognizer(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onSpeechStarted: () -> Unit = {},
    private val onSpeechEnded: () -> Unit = {},
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "GRPC_SPEECH"
        private const val SAMPLE_RATE = 16000
    }

    // ✅ Variables agregadas
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPartial: String? = null
    @Volatile private var streamingActive = false

    private var ultimoTextoHablado = ""
    private var speechClient: SpeechClient? = null
    private var requestObserver: ClientStream<StreamingRecognizeRequest>? = null

    // Flag para evitar múltiples resultados finales
    private var haEnviadoFinal = false
    private var ultimoTextoEnviado = ""
    private var ultimoTimestamp = 0L

    fun setUltimoTextoHablado(texto: String) {
        ultimoTextoHablado = texto.lowercase().trim()
    }

    suspend fun init() {
        withContext(Dispatchers.IO) {
            try {
                val credentials = GoogleCredentials.fromStream(loadCredentials())
                    .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
                val settings = SpeechSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build()
                speechClient = SpeechClient.create(settings)
                Log.d(TAG, " Cliente gRPC inicializado")
            } catch (e: Exception) {
                Log.e(TAG, " Error inicializando gRPC: ${e.message}")
                onError("Error de inicialización: ${e.message}")
            }
        }
    }

    fun startStreaming(language: String = "es-ES") {
        if (streamingActive) {
            Log.w(TAG, "Streaming ya activo")
            return
        }

        // Resetear flags al iniciar
        haEnviadoFinal = false
        ultimoTextoEnviado = ""
        ultimoTimestamp = 0L
        currentPartial = null

        try {
            val responseObserver = object : ResponseObserver<StreamingRecognizeResponse> {
                override fun onStart(controller: StreamController?) {}

                override fun onResponse(response: StreamingRecognizeResponse?) {
                    if (response != null && response.resultsCount > 0) {
                        val result = response.getResults(0)
                        val transcript = if (result.alternativesCount > 0) {
                            result.getAlternatives(0).transcript
                        } else ""

                        val textoLimpio = transcript.trim()

                        if (result.isFinal && textoLimpio.isNotBlank()) {
                            // Guardar el resultado final
                            val ahora = System.currentTimeMillis()
                            val esDuplicado = textoLimpio == ultimoTextoEnviado &&
                                    (ahora - ultimoTimestamp) < 3000L

                            if (!haEnviadoFinal && !esDuplicado) {
                                haEnviadoFinal = true
                                ultimoTextoEnviado = textoLimpio
                                ultimoTimestamp = ahora
                                currentPartial = null
                                Log.d(TAG, " Resultado final: $textoLimpio")
                                mainHandler.post {
                                    onFinalResult(textoLimpio)
                                }
                            } else if (esDuplicado) {
                                Log.d(TAG, " Resultado duplicado ignorado: $textoLimpio")
                            } else if (haEnviadoFinal) {
                                Log.d(TAG, " Ya se envió un resultado final, ignorando: $textoLimpio")
                            }
                        } else if (textoLimpio.isNotBlank()) {
                            // ✅ Guardar el último parcial
                            currentPartial = textoLimpio
                            // Resultados parciales siempre se envían
                            mainHandler.post {
                                onPartialResult(textoLimpio)
                            }
                        }
                    }
                }

                override fun onError(t: Throwable) {
                    val errorMsg = t.message ?: "Error desconocido"
                    Log.e(TAG, "Error gRPC: $errorMsg")

                    // ✅ Si es OUT_OF_RANGE, devolver el último parcial
                    if (t is StatusRuntimeException && t.status.code == Status.Code.OUT_OF_RANGE) {
                        val partial = currentPartial
                        if (partial != null && partial.isNotBlank()) {
                            Log.w(TAG, "OUT_OF_RANGE: devolviendo resultado parcial: '$partial'")
                            mainHandler.post {
                                onFinalResult(partial)
                            }
                        }
                    } else {
                        mainHandler.post {
                            onError("Error en streaming: $errorMsg")
                        }
                    }
                    streamingActive = false
                }

                override fun onComplete() {
                    Log.d(TAG, " Stream completado")
                    streamingActive = false
                    // ✅ Si hay un parcial pendiente, enviarlo como final
                    val partial = currentPartial
                    if (partial != null && partial.isNotBlank() && !haEnviadoFinal) {
                        Log.d(TAG, " Completado con parcial: '$partial'")
                        mainHandler.post {
                            onFinalResult(partial)
                        }
                        haEnviadoFinal = true
                    }
                }
            }

            requestObserver = speechClient?.streamingRecognizeCallable()?.splitCall(responseObserver)
                ?: throw IllegalStateException("SpeechClient no inicializado")

            val config = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                .setSampleRateHertz(SAMPLE_RATE)
                .setLanguageCode(language)
                .build()

            val streamingConfig = StreamingRecognitionConfig.newBuilder()
                .setConfig(config)
                .setInterimResults(true)
                .build()

            requestObserver?.send(
                StreamingRecognizeRequest.newBuilder()
                    .setStreamingConfig(streamingConfig)
                    .build()
            )

            streamingActive = true
            Log.d(TAG, " Streaming iniciado para: $language")

        } catch (e: Exception) {
            Log.e(TAG, " Error iniciando streaming: ${e.message}")
            onError("Error iniciando stream: ${e.message}")
        }
    }

    fun sendAudioChunk(audioBytes: ShortArray) {
        if (!streamingActive) {
            Log.w(TAG, "Stream no activo, ignorando chunk")
            return
        }

        try {
            val byteArray = ByteArray(audioBytes.size * 2)
            for (i in audioBytes.indices) {
                val sample = audioBytes[i].toInt()
                byteArray[i * 2] = (sample and 0xFF).toByte()
                byteArray[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }

            requestObserver?.send(
                StreamingRecognizeRequest.newBuilder()
                    .setAudioContent(ByteString.copyFrom(byteArray))
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, " Error enviando chunk: ${e.message}")
            streamingActive = false
        }
    }

    fun stopStreaming() {
        try {
            requestObserver?.closeSend()
            streamingActive = false
            Log.d(TAG, " Streaming detenido")
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo stream: ${e.message}")
        }
    }

    fun destroy() {
        stopStreaming()
        speechClient?.close()
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, " Recursos liberados")
    }

    private fun loadCredentials(): InputStream {
        return context.assets.open("jarvis-speech-499817-059bb80a7e14.json")
    }

    fun isActive(): Boolean = streamingActive
}