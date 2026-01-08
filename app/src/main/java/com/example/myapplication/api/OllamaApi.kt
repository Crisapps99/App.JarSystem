package com.example.myapplication.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

data class OllamaRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false
)

data class OllamaResponse(
    val response: String
)

interface OllamaService {
    @POST("api/generate")
    suspend fun generar(@Body req: OllamaRequest): OllamaResponse
}
interface ApiService {
    @POST("add_command")
    suspend fun saveCommand(@Body data: CommandData): SimpleApiResponse
}

object OllamaClient {
    private const val FASTAPI_URL = "http://192.168.0.5:11434/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: OllamaService by lazy {
        Retrofit.Builder()
            .baseUrl(FASTAPI_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OllamaService::class.java)
    }
}

