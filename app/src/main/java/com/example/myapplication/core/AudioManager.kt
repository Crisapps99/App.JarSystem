package com.example.myapplication.core

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import com.example.myapplication.R

class AudioManager(private val context: Context){
    //reproduce el dsonido desde la capeta raw
    private fun playSystemSound(resourcesId: Int){
        try {
            val mediaPlayer = MediaPlayer.create(context, resourcesId)
            mediaPlayer.setOnCompletionListener{
                it.release() //libera memoria automaticamente al termina
            }
            // Dentro de playSystemSound, justo antes de mediaPlayer.start()
            mediaPlayer.setVolume(0.8f, 0.8f) // Ajusta el volumen al 80%
            mediaPlayer.start()
            mediaPlayer.start()
        }catch(e: Exception){
            Log.e("JARVIS_AUDIO", "Error al reproducir sonido: ${e.message}")
        }

    }
    // Reproduce el tono de notificación/efecto del sistema
    private fun playRingtone(type: Int) {
        try {
            val uri = RingtoneManager.getDefaultUri(type)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e("JARVIS_AUDIO", "Error ringtone: ${e.message}")
        }
    }
    // Funciones de conveniencia para sonidos específicos
    fun playMicOn() {
//        try{
        playSystemSound(R.raw.mic_on)
//        }catch (e: Exception){
//            playRingtone(RingtoneManager.TYPE_NOTIFICATION)
//        }
    }
    fun playMicOff() {
//        try{
            playSystemSound(R.raw.mic_off)
//        }catch (e: Exception){
//            playRingtone(RingtoneManager.TYPE_NOTIFICATION)
//        }
    }
    fun playActionSuccess() {
//        try{
            playSystemSound(R.raw.mic_init)
//        }catch (e: Exception){
//            playRingtone(RingtoneManager.TYPE_NOTIFICATION)
//        }
    } // Si decides añadir más
}