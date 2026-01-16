package com.example.myapplication.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity // Mejor si trabajas con Activity o AppCompatActivity

/**
 * Objeto singleton para ejecutar acciones específicas de Android.
 * Requiere un Context o Activity para lanzar Intents.
 */
object ActionExecutor {

    /**
     * Abre la aplicación de la cámara por defecto del dispositivo.
     * @param context El contexto desde donde se lanza el Intent.
     * @return Un mensaje de resultado de la acción.
     */
    fun openApp(context: Context, packageName: String): Boolean{
        fun goHome(context: Context) {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
        return try {
            val pm= context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            //si no ha inten para abri registra log y retnorna false
            if (launchIntent == null){
                Log.e("ActionExecutor","launchIntent NULL para $packageName")
                return false
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        }catch (e: Exception){
            Log.e("ActionExecutor","Error abriendo $packageName:${e.message}")
            false
        }
    }
    fun openCamera(context: Context): String {
        return try {
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            // Es crucial verificar si hay alguna app que pueda manejar este Intent
            if (cameraIntent.resolveActivity(context.packageManager) != null) {
                // Si estás dentro de una Activity, es mejor usar startActivity
                context.startActivity(cameraIntent)
                "Cámara abierta exitosamente."
            } else {
                "Error: No se encontró una aplicación de cámara para abrir."
            }
        } catch (e: Exception) {
            "Error al intentar abrir la cámara: ${e.message}"
        }
    }

    /**
     * Abre Google Maps en una ubicación genérica (0,0) como ejemplo.
     * @param context El contexto desde donde se lanza el Intent.
     * @return Un mensaje de resultado de la acción.
     */
    fun openMap(context: Context): String {
        return try {
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Punto+de+interes"))
            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
                "Mapa abierto exitosamente."
            } else {
                "Error: No se encontró una aplicación de mapas para abrir."
            }
        } catch (e: Exception) {
            "Error al intentar abrir el mapa: ${e.message}"
        }
    }

    // Puedes añadir más funciones como openBrowser(url: String), makeCall(number: String), etc.
}