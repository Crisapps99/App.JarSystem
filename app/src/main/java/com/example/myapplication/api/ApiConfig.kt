package com.example.myapplication.api

import java.util.concurrent.TimeUnit
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.Response
import okhttp3.ResponseBody

private const val BASE_URL = "https://beata-unweakening-echo.ngrok-free.dev/"

val logging = HttpLoggingInterceptor().apply {
    level = HttpLoggingInterceptor.Level.BODY
}

private val okHTTP = OkHttpClient.Builder()
    .connectTimeout(120, TimeUnit.SECONDS)
    .readTimeout(600, TimeUnit.SECONDS)
    .writeTimeout(12, TimeUnit.SECONDS)
    .addInterceptor(logging) // Agregar logging para debug
    .build()

// ═══════════════════════════════════════════════════════════════════
// 📊 MODELOS DE REQUEST (Entrada a la API)
// ═══════════════════════════════════════════════════════════════════

/**
 * Request BÁSICO - Mantiene compatibilidad con tu código existente
 */
data class ActionRequest(
    val texto: String,
    val contexto: List<String> = emptyList()
)

/**
 * Request ENRIQUECIDO - Nueva versión con contexto detallado
 * Es RETROCOMPATIBLE: si no envías contexto_detallado, funciona como antes
 */
data class ActionRequestEnriquecido(
    val texto: String,
    val contexto: List<String> = emptyList(),
    @SerializedName("contexto_detallado")
    val contextoDetallado: List<ElementoDetalladoDto>? = null
)

/**
 * DTO para enviar elementos de pantalla con metadatos al servidor
 */
data class ElementoDetalladoDto(
    val text: String,           // Texto visible combinado
    val x: Int,                 // Centro X para tap
    val y: Int,                 // Centro Y para tap
    val clickable: Boolean,     // ¿Es clicable?
    val editable: Boolean,      // ¿Es campo de texto?
    val type: String,           // Tipo de widget (Button, EditText, etc.)
    val importance: Int,        // 0-100
    val actions: List<String>   // ["click", "long_click", "set_text"]
)

// ═══════════════════════════════════════════════════════════════════
// 📤 MODELOS DE RESPONSE (Respuesta del servidor)
// ═══════════════════════════════════════════════════════════════════

/**
 * Respuesta principal del endpoint /predecir
 * Compatible con tu código actual
 */
data class JarvisResponse(
    val success: Boolean,
    val mode: String,           // "COMMAND", "DYNAMIC_ACTION", "CHAT_FREE"
    @SerializedName("response_text")
    val response_text: String,  // Texto que Jarvis dice en voz
    val payload: List<ActionDto>? = null, // Acciones a ejecutar (puede ser null)

    // Campos opcionales para debugging
    val action: String? = null,      // Nombre de la intención detectada
    val confidence: Float? = null    // Nivel de confianza del modelo
)

/**
 * Representa una acción individual a ejecutar
 */
data class ActionDto(
    val tipo: String,                    // "tap", "scroll", "open_app", "type_text", etc.
    val params: Map<String, Any>? = null // Parámetros específicos de la acción
)

/**
 * Saludo inicial de Jarvis
 */
data class GreetingResponse(
    val saludo: String? = null
)

/**
 * Respuesta genérica para endpoints simples
 */
data class SimpleApiResponse(
    val success: Boolean,
    val message: String? = null
)

// ═══════════════════════════════════════════════════════════════════
// 📝 MODELOS DE FEEDBACK (Aprendizaje)
// ═══════════════════════════════════════════════════════════════════

/**
 * Reporte de feedback para que Jarvis aprenda
 */
data class ReporteFeedback(
    val texto_original: String,
    val intencion_detectada: String,
    val json_generado: List<ActionDto>,
    val resultado: String,              // "EXITO" o "ERROR"
    val error_detalle: String? = null
)

// ═══════════════════════════════════════════════════════════════════
// 🌐 INTERFACES DE API (Retrofit)
// ═══════════════════════════════════════════════════════════════════

/**
 * API principal de acciones
 */
interface ActionApiService {
    /**
     * Predice la acción a ejecutar basándose en el comando de voz
     * VERSIÓN BÁSICA (compatibilidad)
     */
    @Headers("Content-Type: application/json")
    @POST("predecir")
    suspend fun predictAction(@Body request: ActionRequest): JarvisResponse

    /**
     * Predice la acción con contexto enriquecido
     * VERSIÓN MEJORADA (nueva)
     */
    @Headers("Content-Type: application/json")
    @POST("predecir")
    suspend fun predictActionEnriquecido(@Body request: ActionRequestEnriquecido): JarvisResponse

    /**
     * Obtiene saludo aleatorio de Gemma
     */
    @GET("obtener_saludo")
    suspend fun regards(): GreetingResponse
}

/**
 * API de feedback y aprendizaje
 */
interface JarvisFeedbackApi {
    /**
     * Envía feedback de ejecución al servidor
     */
    @POST("retroalimentacion")
    suspend fun enviarFeedback(@Body reporte: ReporteFeedback): Response<ResponseBody>
}

/**
 * API de debugging (opcional)
 */
interface JarvisDebugApi {
    /**
     * Endpoint para ver qué detecta Jarvis en pantalla
     */
    @Headers("Content-Type: application/json")
    @POST("debug/pantalla")
    suspend fun debugPantalla(@Body data: Map<String, Any>): Response<ResponseBody>
}

// ═══════════════════════════════════════════════════════════════════
// 🏗️ RETROFIT CLIENT (Singleton)
// ═══════════════════════════════════════════════════════════════════

object RetrofitClient {
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHTTP)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /**
     * API principal de acciones
     */
    val actionApiService: ActionApiService by lazy {
        retrofit.create(ActionApiService::class.java)
    }

    /**
     * API de feedback
     */
    val feedbackApi: JarvisFeedbackApi by lazy {
        retrofit.create(JarvisFeedbackApi::class.java)
    }

    /**
     * API de debugging (opcional)
     */
    val debugApi: JarvisDebugApi by lazy {
        retrofit.create(JarvisDebugApi::class.java)
    }
}

// ═══════════════════════════════════════════════════════════════════
// 🔧 FUNCIONES DE UTILIDAD
// ═══════════════════════════════════════════════════════════════════

/**
 * Convierte un ScreenElement a ElementoDetalladoDto para enviar al servidor
 */
fun com.example.myapplication.model.ScreenElement.toDto(): ElementoDetalladoDto {
    return ElementoDetalladoDto(
        text = this.getSearchableText(),
        x = this.centerX,
        y = this.centerY,
        clickable = this.isClickable,
        editable = this.isEditable,
        type = this.className ?: "unknown",
        importance = this.importance,
        actions = this.availableActions
    )
}

/**
 * Convierte un ScreenSnapshot completo a lista de DTOs
 */
fun com.example.myapplication.model.ScreenSnapshot.toDtoList(): List<ElementoDetalladoDto> {
    return this.elements
        .filter { it.importance > 30 } // Solo elementos importantes
        .map { it.toDto() }
}

// ═══════════════════════════════════════════════════════════════════
// 📝 MODELOS LEGACY (Mantener si se usan en otra parte)
// ═══════════════════════════════════════════════════════════════════

data class ActionResponse(
    @SerializedName("action") val action: String,
    @SerializedName("response_text") val responseText: String? = null
)

data class CommandData(
    val frase: String = "",
    val intent: String = "",
    val categoria: String = "",
    val componente: String = "",
    val entities: Map<String, String> = emptyMap(),
    val created_at: String = ""
)