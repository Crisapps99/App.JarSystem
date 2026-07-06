package com.example.myapplication.grpc

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NexusWebSocketClient(
    private val hostUrl: String,
    private val scope: CoroutineScope = GlobalScope
) {
    companion object {
        private const val TAG = "NEXUS_WS"
    }

    //  Agregar Handler del hilo principal
    private val mainHandler = Handler(Looper.getMainLooper())

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    private var isConnected = false
    private var sessionId = ""
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5
    private val RECONNECT_DELAY_MS = 2000L

    var onEvent: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun connect() {
        if (isConnected) {
            Log.w(TAG, "Ya te encuentras conectado al WebSocket.")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val protocol = if (hostUrl.startsWith("10.0.2.2") || hostUrl.startsWith("localhost")) "ws" else "wss"
                val cleanUrl = hostUrl.replace("https://", "").replace("http://", "").replace("wss://", "").replace("ws://", "")
                val wsUrl = "$protocol://$cleanUrl/ws/jarvis"

                Log.d(TAG, " Conectando a WebSocket: $wsUrl")

                client = OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(wsUrl)
                    .build()

                sessionId = "nexus_ws_${System.currentTimeMillis()}"

                webSocket = client?.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        isConnected = true
                        reconnectAttempts = 0  //  Resetear contador al conectar exitosamente
                        Log.d(TAG, " Conexión WebSocket establecida. SessionID: $sessionId")

                        //  Usar mainHandler para asegurar ejecución en hilo principal
                        mainHandler.post {
                            onConnected?.invoke()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d(TAG, " Mensaje recibido de la GPU")

                        //  Procesar SIEMPRE en hilo principal para evitar problemas de UI
                        mainHandler.post {
                            try {
                                onEvent?.invoke(text)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error en callback onEvent: ${e.message}", e)
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, " Error en el canal WebSocket: ${t.message}", t)
                        isConnected = false

                        mainHandler.post {
                            onError?.invoke("Error de conexión: ${t.message}")
                            onDisconnected?.invoke()
                        }
                        scheduleReconnect()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, " Servidor cerrando el canal: $reason")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, " WebSocket cerrado. Código: $code, Razón: $reason")
                        isConnected = false

                        mainHandler.post {
                            onDisconnected?.invoke()
                        }

                        // Intentar reconectar si no fue un cierre manual
                        if (code != 1000) {
                            scheduleReconnect()
                        }
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, " Error crítico iniciando WebSocket: ${e.message}", e)
                isConnected = false

                mainHandler.post {
                    onError?.invoke("Fallo de inicialización: ${e.message}")
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, " Máximo de intentos de reconexión alcanzado")
            mainHandler.post {
                onError?.invoke("No se pudo reconectar al servidor después de $MAX_RECONNECT_ATTEMPTS intentos")
            }
            return
        }
        reconnectAttempts++
        scope.launch {
            delay(RECONNECT_DELAY_MS * reconnectAttempts)
            Log.d(TAG, " Intentando reconectar (intento $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
            connect()
        }
    }

    fun disconnect() {
        Log.d(TAG, "Cerrando conexión WebSocket de forma controlada...")
        webSocket?.close(1000, "Desconexión manual desde la App Android")
        webSocket = null
        client = null
        isConnected = false
    }

    fun sendText(texto: String) {
        //  Si no está conectado, intentar reconectar ANTES de fallar
        if (!isConnected || webSocket == null) {
            Log.w(TAG, " WebSocket inactivo, intentando reconectar...")

            // Cancelar intentos previos
            reconnectAttempts = 0

            scope.launch(Dispatchers.IO) {
                // Esperar un poco y reconectar
                delay(500)

                if (!isConnected) {
                    Log.d(TAG, " Reconectando tras petición del usuario...")
                    connect()

                    // Esperar a que se conecte
                    var intentos = 0
                    while (!isConnected && intentos < 10) {
                        delay(300)
                        intentos++
                    }

                    // Reintentar envío después de conectar
                    if (isConnected) {
                        enviarTextoInterno(texto)
                    } else {
                        mainHandler.post {
                            onError?.invoke("No se pudo conectar al servidor. Intenta de nuevo.")
                        }
                    }
                } else {
                    enviarTextoInterno(texto)
                }
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            enviarTextoInterno(texto)
        }
    }

    private suspend fun enviarTextoInterno(texto: String) {
        if (webSocket == null) return

        try {
            val jsonString = if (texto.trim().startsWith("{")) {
                texto
            } else {
                JSONObject().apply {
                    put("text", texto)
                    put("session_id", sessionId)
                    put("timestamp", System.currentTimeMillis())
                }.toString()
            }

            val sent = webSocket?.send(jsonString) ?: false
            if (sent) {
                Log.d(TAG, " Payload enviado: ${jsonString.take(80)}...")
            } else {
                Log.e(TAG, " Error al enviar")
                mainHandler.post {
                    onError?.invoke("Error al enviar comando")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, " Error: ${e.message}")
            mainHandler.post {
                onError?.invoke("Error al enviar")
            }
        }
    }

    fun cancel() {
        sendText("CANCEL_CURRENT_PIPELINE_SIGNAL")
        Log.d(TAG, " Señal de cancelación enviada por el canal.")
    }

    fun isReady(): Boolean = isConnected
    fun isStreamActive(): Boolean = isConnected

    /**
     * Obtiene el ID de sesión actual
     */
    fun getSessionId(): String = sessionId
}