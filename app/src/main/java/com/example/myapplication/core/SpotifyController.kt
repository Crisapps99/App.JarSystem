package com.example.myapplication.core

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.example.myapplication.service.JarvisNotificationListener

object SpotifyController {
    private const val TAG = "SPOTIFY_MEDIA"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    private fun getSpotifyController(context: Context): android.media.session.MediaController? {
        return try {
            val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, JarvisNotificationListener::class.java)
            val sessions = mgr.getActiveSessions(component)
            sessions.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo sesión: ${e.message}")
            null
        }
    }

    fun pausar(context: Context): Boolean {
        val ctrl = getSpotifyController(context) ?: return false
        ctrl.transportControls.pause()
        Log.d(TAG, "⏸️ Spotify pausado")
        return true
    }

    fun reproducir(context: Context): Boolean {
        val ctrl = getSpotifyController(context) ?: return false
        ctrl.transportControls.play()
        Log.d(TAG, "▶️ Spotify reanudado")
        return true
    }

    fun detener(context: Context): Boolean {
        val ctrl = getSpotifyController(context) ?: return false
        ctrl.transportControls.stop()
        Log.d(TAG, "⏹️ Spotify detenido")
        return true
    }

    fun siguienteCancion(context: Context): Boolean {
        val ctrl = getSpotifyController(context) ?: return false
        ctrl.transportControls.skipToNext()
        Log.d(TAG, "⏭️ Siguiente")
        return true
    }

    fun cancionAnterior(context: Context): Boolean {
        val ctrl = getSpotifyController(context) ?: return false
        ctrl.transportControls.skipToPrevious()
        Log.d(TAG, "⏮️ Anterior")
        return true
    }

    fun estaReproduciendo(context: Context): Boolean {
        val ctrl = getSpotifyController(context) ?: return false
        return ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
    }

    fun getTituloActual(context: Context): String? {
        val ctrl = getSpotifyController(context) ?: return null
        return ctrl.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
    }

    fun getArtistaActual(context: Context): String? {
        val ctrl = getSpotifyController(context) ?: return null
        return ctrl.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
    }
}