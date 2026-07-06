// ARCHIVO: app/src/main/java/com/example/myapplication/core/MessagingManager.kt
package com.example.myapplication.core.integrations

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

object MessagingManager {

    /**
     * Mapa de apps de mensajería conocidas.
     * Clave: nombre que diría el usuario ("whatsapp", "telegram", etc.)
     * Valor: package name real del sistema
     */
    private val KNOWN_APPS = mapOf(
        "whatsapp"  to "com.whatsapp",
        "telegram"  to "org.telegram.messenger",
        "sms"       to "com.google.android.apps.messaging",
        "mensajes"  to "com.google.android.apps.messaging",
        "signal"    to "org.thoughtcrime.securesms",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android"
    )

    data class MessagingApp(
        val name: String,
        val packageName: String
    )

    /**
     * Retorna todas las apps de mensajería INSTALADAS en el dispositivo.
     */
    fun getInstalledMessagingApps(context: Context): List<MessagingApp> {
        val pm = context.packageManager
        return KNOWN_APPS.entries.mapNotNull { (name, pkg) ->
            try {
                pm.getPackageInfo(pkg, 0)
                MessagingApp(name = name.replaceFirstChar { it.uppercase() }, packageName = pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                null // App no instalada, la ignoramos
            }
        }
    }

    /**
     * Verifica si una app específica está instalada.
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Abre un chat de WhatsApp con un número de teléfono específico.
     * El número debe estar en formato internacional (ej: "593987654321").
     * No necesita que el número esté en los contactos de WhatsApp.
     */
    fun openWhatsAppChat(context: Context, phoneNumber: String, message: String = "") {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9]"), "")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Log.d("JARVIS_MSG", "📱 Abriendo WhatsApp con $cleanNumber")
    }

    /**
     * Abre la app de SMS con un número pre-rellenado y mensaje opcional.
     */
    fun openSmsChat(context: Context, phoneNumber: String, message: String = "") {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$phoneNumber")
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Abre una app de mensajería genérica.
     * Si la app NO está instalada, redirige a la Play Store.
     */
    fun openMessagingApp(context: Context, packageName: String) {
        if (isAppInstalled(context, packageName)) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent != null) {
                context.startActivity(intent)
            }
        } else {
            openPlayStore(context, packageName)
        }
    }

    /**
     * Redirige a la Play Store para instalar una app.
     */
    fun openPlayStore(context: Context, packageName: String) {
        val intent = try {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } catch (e: Exception) {
            // Si no tiene Play Store (emulador/dispositivo sin Google)
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        context.startActivity(intent)
        Log.d("JARVIS_MSG", "🛒 Redirigiendo a Play Store: $packageName")
    }

    /**
     * Convierte la lista de apps instaladas a texto para el contexto del servidor.
     * El servidor usará esto para saber qué opciones darle al usuario.
     */
    fun toContextText(context: Context): String {
        val apps = getInstalledMessagingApps(context)
        return if (apps.isEmpty()) "Sin apps de mensajería instaladas."
        else "Apps de mensajería: " + apps.joinToString(", ") { it.name }
    }
}