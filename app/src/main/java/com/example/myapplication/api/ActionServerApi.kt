package com.example.myapplication.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class IntentRequest(
    val intent_code: String
)

data class ActionDto(
    val id: String,
    val tipo: String,
    val params: Map<String, Any>?
)

data class ResolveResponseDto(
    val intent_code: String,
    val actions: List<ActionDto>
)

interface ActionServerApi {
    @POST("actions/resolve")
    suspend fun resolveIntent(@Body req: IntentRequest): ResolveResponseDto
}
// api/ActionServerClient.kt
object ActionServerClient {
    private const val BASE_URL = "http://192.168.0.5:8100/"

    val service: ActionServerApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ActionServerApi::class.java)
    }
}
