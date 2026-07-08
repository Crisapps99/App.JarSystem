package com.example.myapplication.core.integrations

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.myapplication.api.ActionDto
import com.example.myapplication.service.JarvisNotificationListener

object YoutubeController {
    private const val TAG = "YT_MEDIA"

    // Obtiene el MediaController activo de YouTube
    private fun getYouTubeController(context: Context): android.media.session.MediaController? {
        return try {
            val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, JarvisNotificationListener::class.java)
            val sessions = mgr.getActiveSessions(component)
            sessions.firstOrNull { it.packageName == "com.google.android.youtube" }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo sesión: ${e.message}")
            null
        }
    }
    fun subirVolumen(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.ADJUST_RAISE,
            android.media.AudioManager.FLAG_SHOW_UI
        )
        Log.d(TAG, "🔊 Volumen subido")
        return true
    }

    fun bajarVolumen(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI
        )
        Log.d(TAG, "🔉 Volumen bajado")
        return true
    }
    object YouTubeCache {
        private val cache = mutableMapOf<String, CacheEntry>()
        private const val TTL_MS = 24 * 60 * 60 * 1000L // 24 horas

        data class CacheEntry(val videoId: String, val timestamp: Long)

        fun get(query: String): String? {
            val entry = cache[query.lowercase()]
            return if (entry != null && System.currentTimeMillis() - entry.timestamp < TTL_MS) {
                entry.videoId
            } else null
        }

        fun put(query: String, videoId: String) {
            cache[query.lowercase()] = CacheEntry(videoId, System.currentTimeMillis())
        }
    }
    fun anteriorCancion(context: Context): Boolean {
        val ctrl = getYouTubeController(context) ?: return false
        ctrl.transportControls.skipToPrevious()
        Log.d(TAG, " Anterior")
        return true
    }

    /**
     * Salta el tiempo actual.
     * @param segundos: positivo para adelantar, negativo para retroceder.
     */
    fun saltarTiempo(context: Context, segundos: Int): Boolean {
        val ctrl = getYouTubeController(context) ?: return false
        val posicionActual = ctrl.playbackState?.position ?: 0L
        val nuevaPosicion = posicionActual + (segundos * 1000L)

        // Evitar que la posición sea menor a cero
        val posicionFinal = if (nuevaPosicion < 0) 0 else nuevaPosicion

        ctrl.transportControls.seekTo(posicionFinal)
        Log.d(TAG, "/ Saltando a ${posicionFinal / 1000} segundos")
        return true
    }
    fun pausar(context: Context): Boolean {
        val ctrl = getYouTubeController(context) ?: return false
        ctrl.transportControls.pause()
        Log.d(TAG, " YouTube pausado")
        return true
    }

    fun reproducir(context: Context): Boolean {
        val ctrl = getYouTubeController(context) ?: return false
        ctrl.transportControls.play()
        Log.d(TAG, "▶ YouTube reanudado")
        return true
    }

    fun detener(context: Context): Boolean {
        val ctrl = getYouTubeController(context) ?: return false
        ctrl.transportControls.stop()
        Log.d(TAG, " YouTube detenido")
        return true
    }

    fun siguienteCancion(context: Context): Boolean {
        val ctrl = getYouTubeController(context) ?: return false
        ctrl.transportControls.skipToNext()
        Log.d(TAG, " Siguiente")
        return true
    }

    fun estaReproduciendo(context: Context): Boolean {
        val ctrl = getYouTubeController(context) ?: return false
        return ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
    }

    fun getTituloActual(context: Context): String? {
        val ctrl = getYouTubeController(context) ?: return null
        return ctrl.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
    }
}