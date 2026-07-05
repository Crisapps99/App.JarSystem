package com.example.myapplication.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SearchResult(
    val content: String,
    val urls: List<String>,
    val imageUrls: List<String> = emptyList(), // 📸 Guardará las imágenes reales devueltas por la API
    val query: String = ""                     // 🔍 Guarda la consulta limpia para comandos como "Ver más"
)

object TavilySearchService {

    private const val TAG = "TAVILY_SEARCH"
    private val API_KEY = com.example.myapplication.BuildConfig.TAVILY_API_KEY
    private const val BASE_URL = "https://api.tavily.com/search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Filtra y limpia las frases naturales para quedarse solo con la consulta real.
     */
    private fun extraerConsultaReal(fraseNatural: String): String {
        var consulta = fraseNatural.lowercase().trim()

        val comandosVoz = listOf(
            "busca en internet por favor qué es",
            "busca en internet por favor quién es",
            "busca en internet por favor",
            "buscar en internet por favor",
            "qué dice internet sobre",
            "que dice internet sobre",
            "busca en internet sobre",
            "buscar en internet sobre",
            "mira en internet si",
            "búscame algo sobre",
            "buscame algo sobre",
            "investiga sobre",
            "investigar sobre",
            "busca en internet",
            "buscar en internet",
            "búscame sobre",
            "buscame sobre",
            "averigua sobre",
            "quiero saber sobre",
            "cuéntame sobre",
            "cuentame sobre",
            "mira qué es",
            "mira que es",
            "búscame",
            "buscame",
            "busca",
            "buscar"
        )

        for (comando in comandosVoz) {
            if (consulta.startsWith(comando)) {
                consulta = consulta.substring(comando.length).trim()
                break
            }
        }

        if (consulta.startsWith("sobre ")) consulta = consulta.substring(6).trim()
        if (consulta.startsWith("de ")) consulta = consulta.substring(3).trim()
        if (consulta.startsWith("a ")) consulta = consulta.substring(2).trim()

        return consulta.ifBlank { fraseNatural }.trim()
    }

    suspend fun search(
        query: String,
        includeAnswer: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        try {
            val consultaLimpia = extraerConsultaReal(query)
            Log.d(TAG, "🎤 Frase original: '$query' -> 🔍 Consulta limpia: '$consultaLimpia'")

            val jsonBody = JSONObject().apply {
                put("api_key", API_KEY)
                put("query", consultaLimpia)
                put("search_depth", "basic")
                put("include_answer", includeAnswer)
                put("include_raw_content", false)
                put("max_results", 3)
                put("language", "es")
            }

            val request = Request.Builder()
                .url(BASE_URL)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) return@withContext ""

            val json = JSONObject(responseBody)

            if (includeAnswer) {
                val answer = json.optString("answer", "")
                if (answer.isNotBlank() && answer.length > 20) {
                    val respuestaEspanol = if (contieneEspanol(answer)) answer else traducirRespuesta(answer)
                    return@withContext formatearRespuesta(respuestaEspanol)
                }
            }

            val results = json.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val firstResult = results.getJSONObject(0)
                var content = firstResult.optString("content", "")
                if (content.isBlank()) content = firstResult.optString("title", "")

                if (content.isNotBlank()) {
                    return@withContext formatearRespuesta(content)
                }
            }
            return@withContext ""
        } catch (e: Exception) {
            return@withContext ""
        }
    }

    private fun contieneEspanol(texto: String): Boolean {
        val palabrasEspanol = listOf("que", "para", "como", "por", "esta", "este", "una", "el", "la", "los", "y")
        val textoLower = texto.lowercase()
        return palabrasEspanol.count { textoLower.contains(it) } >= 2
    }

    private fun traducirRespuesta(texto: String): String {
        if (!contieneEspanol(texto)) return "$texto \n\n*(Nota: resultado en inglés)*"
        return texto
    }

    /**
     * Búsqueda Avanzada Estructurada estilo Asistente Inteligente / Copilot Card
     */
    suspend fun searchAdvanced(query: String): SearchResult = withContext(Dispatchers.IO) {
        val emptyResult = SearchResult("", emptyList(), emptyList(), query)
        try {
            val consultaLimpia = extraerConsultaReal(query)
            Log.d(TAG, "🔍 Avanzada - Consulta limpia: '$consultaLimpia'")

            val jsonBody = JSONObject().apply {
                put("api_key", API_KEY)
                put("query", consultaLimpia)
                put("search_depth", "advanced")
                put("include_answer", true)
                put("include_images", true) // Solicitamos el carrusel multimedia
                put("include_raw_content", false)
                put("max_results", 4)
            }

            val request = Request.Builder()
                .url(BASE_URL)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) return@withContext emptyResult

            val json = JSONObject(responseBody)
            val answer = json.optString("answer", "")

            // 1. Extraer fuentes web de destino
            val urlsList = mutableListOf<String>()
            val results = json.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    val resObj = results.getJSONObject(i)
                    val url = resObj.optString("url", "").trim()
                    if (url.isNotBlank()) urlsList.add(url)
                }
            }

            // 2. Extraer el set de imágenes reales encontradas por Tavily
            val imageUrlsList = mutableListOf<String>()
            val imagesArray = json.optJSONArray("images")
            if (imagesArray != null) {
                for (i in 0 until imagesArray.length()) {
                    val imgUrl = imagesArray.optString(i, "").trim()
                    if (imgUrl.isNotBlank()) imageUrlsList.add(imgUrl)
                }
            }

            // 3. CONSTRUCCIÓN DE LA RESPUESTA ENRIQUECIDA (Unificamos resumen y bloques de datos)
            val sb = StringBuilder()

            if (answer.isNotBlank() && answer.length > 20) {
                sb.append("**💡 Resumen Directo:**\n")
                sb.append("${formatearRespuesta(answer)}\n\n")
            }

            val contenidoFinal = sb.toString().trim()
            if (contenidoFinal.isNotBlank()) {
                return@withContext SearchResult(contenidoFinal, urlsList, imageUrlsList, consultaLimpia)
            }

            return@withContext emptyResult
        } catch (e: Exception) {
            Log.e(TAG, "Error en búsqueda avanzada: ${e.message}")
            return@withContext emptyResult
        }
    }

    private fun formatearRespuesta(respuesta: String): String {
        return respuesta.split("\n")
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}