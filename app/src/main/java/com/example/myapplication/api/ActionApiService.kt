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
//grook
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.Part
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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

/**
 * DTo apr aenviar un elemento individual de pantalla
 */
data class ElementoDetalladoDto(
    // Identificación
    @SerializedName("id") val id: String,
    @SerializedName("text") val text: String,
    @SerializedName("content_description") val contentDescription: String,
    @SerializedName("view_id") val viewId: String,
    @SerializedName("class_name") val className: String,
    // Posición
    @SerializedName("x") val x: Int,
    @SerializedName("y") val y: Int,
    @SerializedName("bounds") val bounds: Map<String, Int>,
    // Capacidades
    @SerializedName("clickable") val clickable: Boolean,
    @SerializedName("editable") val editable: Boolean,
    @SerializedName("scrollable") val scrollable: Boolean,
    @SerializedName("checkable") val checkable: Boolean,
    @SerializedName("checked") val checked: Boolean,
    @SerializedName("long_clickable") val longClickable: Boolean,

    // Metadatos
    @SerializedName("type") val type: String,
    @SerializedName("importance") val importance: Int,
    @SerializedName("actions") val actions: List<String>,

    // Contexto
    @SerializedName("parent_text") val parentText: String?,
    @SerializedName("sibling_texts") val siblingTexts: List<String>,
    @SerializedName("visibility") val visibility: String
)
//data class ActionRequest(
//    val texto: String,
//    val contexto: List<String> = emptyList()
//)

/**
 * Request ENRIQUECIDO - Nueva versión con contexto detallado
 */
data class ActionRequestEnriquecido(
    @SerializedName("texto") val texto: String,
    @SerializedName("contexto") val contexto: List<String> = emptyList(),
    @SerializedName("contexto_detallado") val contextoDetallado: List<ElementoDetalladoDto> = emptyList(),
    @SerializedName("metadata") val metadata: Map<String, Any> = emptyMap()
)
/**
 * Respuesta principal del endpoint /predecir
 */
data class JarvisResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("mode") val mode: String,
    @SerializedName("action") val action: String? = null,
    @SerializedName("response_text") val response_text: String,
    @SerializedName("payload") val payload: List<ActionDto>? = null,
    @SerializedName("confidence") val confidence: Float? = null
)
/**
 * Representa una acción individual a ejecutar
 */
data class ActionDto(
    @SerializedName("tipo")      val tipo: String,
    @SerializedName("params")    val params: Map<String, Any>? = null,
    // Campos raíz para android_intent
    @SerializedName("action")    val action: String? = null,
    @SerializedName("package")   val pkg: String? = null,
    @SerializedName("data")      val data: String? = null,
    @SerializedName("mime_type") val mimeType: String? = null,
    @SerializedName("extras")    val extras: Map<String, Any>? = null,
    @SerializedName("component") val component: String? = null,
)
/**
 * Saludo de Jarvis
 */
data class SaludoResponse(
    @SerializedName("saludo") val saludo: String? = null
)
/**
 * Reporte de feedback para aprendizaje
 */
data class ReporteFeedback(
    @SerializedName("texto_original") val texto_original: String,
    @SerializedName("intencion_detectada") val intencion_detectada: String,
    @SerializedName("json_generado") val json_generado: List<ActionDto>,
    @SerializedName("resultado") val resultado: String,
    @SerializedName("error_detalle") val error_detalle: String? = null
)
//)
///**
// * DTO para enviar elementos de pantalla con metadatos al servidor
// */
//data class ElementoDetalladoDto(
//    val text: String,           // Texto visible combinado
//    val x: Int,                 // Centro X para tap
//    val y: Int,                 // Centro Y para tap
//    val clickable: Boolean,     // ¿Es clicable?
//    val editable: Boolean,      // ¿Es campo de texto?
//    val type: String,           // Tipo de widget (Button, EditText, etc.)
//    val importance: Int,        // 0-100
//    val actions: List<String>   // ["click", "long_click", "set_text"]
//)
//
//// ═══════════════════════════════════════════════════════════════════
//// 📤 MODELOS DE RESPONSE (Respuesta del servidor)
//// ═══════════════════════════════════════════════════════════════════
//
//
//
//
//
///**
// * Saludo inicial de Jarvis
// */
//data class GreetingResponse(
//    val saludo: String? = null
//)
//
///**
// * Respuesta genérica para endpoints simples
// */
//data class SimpleApiResponse(
//    val success: Boolean,
//    val message: String? = null
//)
//
//// ═══════════════════════════════════════════════════════════════════
//// 📝 MODELOS DE FEEDBACK (Aprendizaje)
//// ═══════════════════════════════════════════════════════════════════
//
///**
// * Reporte de feedback para que Jarvis aprenda
// */
//data class ReporteFeedback(
//    val texto_original: String,
//    val intencion_detectada: String,
//    val json_generado: List<ActionDto>,
//    val resultado: String,              // "EXITO" o "ERROR"
//    val error_detalle: String? = null
//)

// ═══════════════════════════════════════════════════════════════════
// 🌐 INTERFACES DE API (Retrofit)
// ═══════════════════════════════════════════════════════════════════
//interface GroqApiService {
//    @Multipart
//    @POST("v1/audio/transcriptions")
//    suspend fun transcribeAudio(
//        @Part audio: MultipartBody.Part,
//        @Part("model") model: RequestBody = "whisper-large-v3".toRequestBody("text/plain".toMediaType()),
//        @Part("language") language: RequestBody = "es".toRequestBody("text/plain".toMediaType())
//    ): GroqResponse
//}

//data class GroqResponse(val text: String)
/**
 * API principal de acciones
 */
interface ActionApiService {
    /**
     * Predice la acción a ejecutar basándose en el comando de voz
     */
    @Headers("Content-Type: application/json")
    @POST("/jarvis")
    suspend fun predictActionEnriquecido(
        @Body request: ActionRequestEnriquecido
    ): JarvisResponse

    //    saludo de gema
    @GET("/jarvis")
    suspend fun regards(): SaludoResponse
}

    /**
     * API de feedback y aprendizaje
     */
    interface JarvisFeedbackApi {
        @POST("retroalimentacion")
        suspend fun enviarFeedback(@Body reporte: ReporteFeedback
        ): Response<ResponseBody>
    }
    /**
     * API de debugging
     */
    interface JarvisDebugApi {
        @Headers("Content-Type: application/json")
        @POST("debug/pantalla")
        suspend fun debugPantalla(
            @Body data: Map<String, Any>
        ): Response<ResponseBody>
    }

// ═══════════════════════════════════════════════════════════════════
// RETROFIT CLIENT (Singleton)
// ═══════════════════════════════════════════════════════════════════

object RetrofitClient {
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHTTP)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val actionApiService: ActionApiService by lazy {
        retrofit.create(ActionApiService::class.java)
    }
    val feedbackApi: JarvisFeedbackApi by lazy {
        retrofit.create(JarvisFeedbackApi::class.java)
    }
    val debugApi: JarvisDebugApi by lazy {
        retrofit.create(JarvisDebugApi::class.java)
    }
//    // --- 🚀 NUEVO: Configuración para GROQ ---
//    private const val GROQ_URL = "https://api.groq.com/openai/"
//    private const val GROQ_API_KEY = "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" // Reemplaza con tu llave real
//
//    private val okHTTPGroq = OkHttpClient.Builder()
//        .addInterceptor { chain ->
//            val request = chain.request().newBuilder()
//                .addHeader("Authorization", "Bearer $GROQ_API_KEY")
//                .build()
//            chain.proceed(request)
//        }
//        .addInterceptor(logging)
//        .build()
//
//    private val retrofitGroq = Retrofit.Builder()
//        .baseUrl(GROQ_URL)
//        .client(okHTTPGroq)
//        .addConverterFactory(GsonConverterFactory.create())
//        .build()
//
//    val groqApiService: GroqApiService by lazy {
//        retrofitGroq.create(GroqApiService::class.java)
//    }
}
/**
 * Convierte un ScreenElement a ElementoDetalladoDto para enviar al servidor
 */
fun com.example.myapplication.model.ScreenElement.toDto(): ElementoDetalladoDto {
    return ElementoDetalladoDto(
        id = this.id,
        text = this.text ?: "",
        contentDescription = this.contentDescription ?: "",
        viewId = this.viewId ?: "",
        className = this.className ?: "",
        x = this.centerX,
        y = this.centerY,
        bounds = mapOf(
            "left" to this.bounds.left,
            "top" to this.bounds.top,
            "right" to this.bounds.right,
            "bottom" to this.bounds.bottom
        ),
        clickable = this.isClickable,
        editable = this.isEditable,
        scrollable = this.isScrollable,
        checkable = this.isCheckable,
        checked = this.isChecked,
        longClickable = this.isLongClickable,
        type = this.className ?: "unknown",
        importance = this.importance,
        actions = this.availableActions,
        parentText = this.parentText,
        siblingTexts = this.siblingTexts,
        visibility = this.visibility
    )
}
//
///**
// * Convierte un ScreenSnapshot completo a lista de DTOs
// */
//fun com.example.myapplication.model.ScreenSnapshot.toDtoList(): List<ElementoDetalladoDto> {
//    return this.elements
//        .filter { it.importance > 30 } // Solo elementos importantes
//        .map { it.toDto() }
//}
//
//// ═══════════════════════════════════════════════════════════════════
//// 📝 MODELOS LEGACY (Mantener si se usan en otra parte)
//// ═══════════════════════════════════════════════════════════════════
//
//data class ActionResponse(
//    @SerializedName("action") val action: String,
//    @SerializedName("response_text") val responseText: String? = null
//)
//
//data class CommandData(
//    val frase: String = "",
//    val intent: String = "",
//    val categoria: String = "",
//    val componente: String = "",
//    val entities: Map<String, String> = emptyMap(),
//    val created_at: String = ""
//)