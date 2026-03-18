package com.example.myapplication.core

object CommandAnalyzer {
    enum class Intent { MUSIC, WEATHER, NOTIFICATIONS, TIME, UNKNOWN, NAVIGATION, SEARCH }

    fun clasificar(texto: String): Intent {
        val t = texto.lowercase()
        return when {
            // Música (Spotify/YouTube)
            t.contains("reproduce") || t.contains("pon la canción") || t.contains("pon música") || t.contains("escuchar a") -> Intent.MUSIC

            // Mapas / Rutas
            t.contains("ruta") || t.contains("cómo llego a") || t.contains("mapas") || t.contains("llévame a") -> Intent.NAVIGATION

            // Búsqueda Internet
            t.contains("busca en internet") || t.contains("investiga") || t.contains("quién es") || t.contains("qué es") -> Intent.SEARCH

            // Otros
            t.contains("clima") || t.contains("temperatura") -> Intent.WEATHER
            t.contains("notificaciones") || t.contains("mensajes") -> Intent.NOTIFICATIONS
            t.contains("hora") -> Intent.TIME
            else -> Intent.UNKNOWN
        }
    }
}