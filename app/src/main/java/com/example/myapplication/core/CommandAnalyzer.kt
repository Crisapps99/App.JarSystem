package com.example.myapplication.core

import android.util.Log

object CommandAnalyzer {

    enum class Intent {
        // Comunicación
        SEND_MESSAGE, SEND_WHATSAPP, SEND_TELEGRAM, CALL_CONTACT, OPEN_EMAIL,
        // Multimedia
        PLAY_MUSIC, PAUSE_MUSIC, NEXT_SONG, OPEN_YOUTUBE,
        // Navegación
        OPEN_MAPS, GET_DIRECTIONS,
        // Info
        WEATHER, TIME, SEARCH_WEB,
        // Otros
        UNKNOWN
    }

    fun clasificar(texto: String): Intent {
        val t = texto.lowercase().trim()

        return when {
             //Música
            t.contains("reproduce") || t.contains("pon música") ||
                    t.contains("pon la canción") || t.contains("spotify") ||
                    t.contains("youtube music") -> Intent.PLAY_MUSIC

            t.contains("pausa") || t.contains("detén") -> Intent.PAUSE_MUSIC
            t.contains("siguiente") || t.contains("next") -> Intent.NEXT_SONG

            // Videos
            t.contains("youtube") || t.contains("video") || t.contains("ver video") -> Intent.OPEN_YOUTUBE

            // Llamadas
            t.contains("llamar") || t.contains("telefonazo") || t.contains("call") -> Intent.CALL_CONTACT

            // Mensajes
            t.contains("enviar") && (t.contains("mensaje") || t.contains("whatsapp") ||
                    t.contains("sms") || t.contains("texto")) -> {
                when {
                    t.contains("whatsapp") || t.contains("wa") -> Intent.SEND_WHATSAPP
                    t.contains("telegram") -> Intent.SEND_TELEGRAM
                    else -> Intent.SEND_MESSAGE
                }
            }

            // Mapas/Rutas
            t.contains("ruta") || t.contains("cómo llego") || t.contains("mapas") ||
                    t.contains("llévame") || t.contains("directions") -> Intent.GET_DIRECTIONS

            t.contains("mapa") || t.contains("ubicación") -> Intent.OPEN_MAPS

            // Información
            t.contains("clima") || t.contains("temperatura") || t.contains("lluvia") ||
                    t.contains("weather") -> Intent.WEATHER

            t.contains("hora") || t.contains("qué hora") || t.contains("time") -> Intent.TIME

            // Búsqueda
            t.contains("busca") || t.contains("buscar") || t.contains("investiga") ||
                    t.contains("google") || t.contains("quién") || t.contains("qué") -> Intent.SEARCH_WEB

            else -> Intent.UNKNOWN
        }
    }

    fun detectarParametro(texto: String, intento: Intent): String {
        return when (intento) {
            Intent.SEND_MESSAGE, Intent.SEND_WHATSAPP, Intent.SEND_TELEGRAM, Intent.CALL_CONTACT -> {
                extraerNombre(texto)
            }
            Intent.PLAY_MUSIC -> {
                texto.replace("reproduce", "").replace("pon música", "")
                    .replace("pon la canción", "").replace("pon a", "")
                    .replace("en spotify", "").replace("en youtube", "")
                    .replace("pon", "").trim()
            }
            Intent.GET_DIRECTIONS, Intent.SEARCH_WEB -> {
                texto.replace("ruta a", "").replace("cómo llego a", "")
                    .replace("llévame a", "").replace("busca", "")
                    .replace("buscar", "").replace("investiga", "").trim()
            }
            else -> ""
        }
    }


    private fun extraerNombre(texto: String): String {
        var nombre = texto.lowercase()

        val prefijos = listOf(
            "a ", "al ", "con ", "a llamar ", "a mensaje ",
            "enviar a ", "llamar a ", "mensaje a "
        )

        for (prefijo in prefijos) {
            if (nombre.contains(prefijo)) {
                nombre = nombre.substringAfter(prefijo)
            }
        }

        val sufijos = listOf(
            " por whatsapp", " por telegram", " por sms",
            " en whatsapp", " en telegram"
        )
        for (sufijo in sufijos) {
            nombre = nombre.replace(sufijo, "")
        }

        return nombre.trim()
    }
}