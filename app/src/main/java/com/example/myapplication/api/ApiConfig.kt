package com.example.myapplication.api
import java.util.concurrent.TimeUnit
import android.os.Message
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
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
data class SimpleApiResponse(
    val success: Boolean,
    val message: String?
)
data class ollamaResult(
    val frase: String,
    val intent: String,
    val entities: Map<String, String>,
    val categoria: String,
    val componente: String
)
//retrofit para definir el endpoint de la red neuronal
interface  ActionApiService{
    @Headers("Content-Type: application/json")
    @POST("predecir")
    suspend fun predictAction(@Body request: ActionRequest): ActionResponse
    @POST("train")
    suspend fun trainModel(): SimpleApiResponse
    @POST("add_command")
    suspend fun trainexample(@Body request: CommandData): SimpleApiResponse
    @POST("classify_ollama")
    suspend fun classifyOllama(@Body request: ActionRequest): ollamaResult
}


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
