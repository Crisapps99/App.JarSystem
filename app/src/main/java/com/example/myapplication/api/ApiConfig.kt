package com.example.myapplication.api
import java.util.concurrent.TimeUnit
import android.os.Message
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

private const val BASE_URL="https://beata-unweakening-echo.ngrok-free.dev/"
val logging = HttpLoggingInterceptor().apply {
    level = HttpLoggingInterceptor.Level.BODY
}
private val okHTTP= OkHttpClient.Builder()
    .connectTimeout(120, TimeUnit.SECONDS)
    .readTimeout(600, TimeUnit.SECONDS)
    .writeTimeout(12, TimeUnit.SECONDS)
    .build()

//cuerpo JSON QUE ENViamos ala api
data class ActionRequest(
    val texto: String,
    val contexto: List<String> = emptyList()
)

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
// Actualiza tu archivo de API para que reciba estos campos
data class JarvisResponse(
    val success: Boolean,
    val mode: String,          // "COMMAND" o "CHAT_FREE"
    val action: String,        // Nombre de la intención
    val confidence: Float,
    val response_text: String, // La frase natural de Gemma
    val payload: List<ActionDto>? // El paquete de ejecución técnica
)
data class SimpleApiResponse(
    val success: Boolean,
    val message: String?
)
interface JarvisFeedbackApi {
    @POST("/retroalimentacion")
    suspend fun enviarFeedback(@Body reporte: ReporteFeedback): Response<ResponseBody>
}

// Modelo de datos para el reporte
data class ReporteFeedback(
    val texto_original: String,
    val intencion_detectada: String,
    val json_generado: List<ActionDto>,
    val resultado: String, // "EXITO" o "ERROR"
    val error_detalle: String? = null
)
interface  ActionApiService{
    @Headers("Content-Type: application/json")
    @POST("predecir")
    suspend fun predictAction(@Body request: ActionRequest): JarvisResponse
    @GET("obtener_saludo")//saludo
    suspend fun regards(): GreetingResponse

}
data class GreetingResponse(
    val saludo: String?=null
)
object RetrofitClient {
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHTTP)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val actionApiService: ActionApiService by lazy {
        retrofit.create(ActionApiService::class.java)
    }

    // Agregamos esta propiedad para solucionar el error "Unresolved reference"
    val feedbackApi: JarvisFeedbackApi by lazy {
        retrofit.create(JarvisFeedbackApi::class.java)
    }
}
