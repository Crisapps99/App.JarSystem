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
    val texto: String)

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
object RetrofitClient{
    val actionApiService: ActionApiService by lazy{
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHTTP)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ActionApiService::class.java)
    }
}
