// ARCHIVO: app/src/main/java/com/example/myapplication/core/ContactsManager.kt
package com.example.myapplication.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object ContactsManager {

    data class Contact(
        val name: String,
        val phoneNumber: String,
        val normalizedName: String = name.lowercase().trim()
    )

    private var cachedContacts: List<Contact> = emptyList()
    private var lastLoadTime: Long = 0
    private const val CACHE_DURATION = 60_000L // 1 minuto

    /**
     * Carga todos los contactos con número de teléfono.
     * Usa caché de 1 minuto para no consultar el ContentResolver en cada llamada.
     */
    fun loadContacts(context: Context): List<Contact> {
        val now = System.currentTimeMillis()
        if (cachedContacts.isNotEmpty() && (now - lastLoadTime) < CACHE_DURATION) {
            return cachedContacts
        }

        val contacts = mutableListOf<Contact>()
        val resolver: ContentResolver = context.contentResolver

        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name   = it.getString(nameIdx) ?: continue
                val number = it.getString(numIdx)?.replace(Regex("[^+0-9]"), "") ?: continue
                if (number.isNotBlank()) {
                    contacts.add(Contact(name = name, phoneNumber = number))
                }
            }
        }

        cachedContacts = contacts
        lastLoadTime = now
        Log.d("JARVIS_CONTACTS", "📒 ${contacts.size} contactos cargados")
        return contacts
    }

    /**
     * Busca el contacto más parecido al nombre dado por el usuario.
     * Usa coincidencia exacta primero, luego parcial, luego fuzzy.
     * Retorna null si no encuentra nada con >50% similitud.
     */
    fun findContact(context: Context, nameBuscado: String): Contact? {
        val contacts = loadContacts(context)
        val query = nameBuscado.lowercase().trim()

        // 1. Coincidencia exacta
        contacts.find { it.normalizedName == query }?.let { return it }

        // 2. Contiene el nombre
        contacts.find { it.normalizedName.contains(query) || query.contains(it.normalizedName) }
            ?.let { return it }

        // 3. Fuzzy matching — Levenshtein
        return contacts
            .map { contact ->
                val similarity = calcularSimilitud(query, contact.normalizedName)
                contact to similarity
            }
            .filter { it.second > 0.5 }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Retorna todos los contactos como texto para enviar como contexto al servidor.
     * Solo envía los primeros 100 para no sobrecargar el prompt.
     */
    fun toContextText(context: Context): String {
        val contacts = loadContacts(context)
        if (contacts.isEmpty()) return "Sin contactos disponibles."
        return contacts.take(100).joinToString(", ") { it.name }
    }

    /**
     * Inicia una llamada telefónica.
     * Para el 911 u otros números de emergencia no necesita contacto.
     */
    fun makeCall(context: Context, phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Log.d("JARVIS_CALL", "📞 Llamando a $phoneNumber")
    }

    /**
     * Abre el marcador con el número ya escrito (sin llamar automáticamente).
     * Útil si no tienes permiso CALL_PHONE.
     */
    fun dialNumber(context: Context, phoneNumber: String) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun calcularSimilitud(s1: String, s2: String): Double {
        val longer  = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0
        val editDist = levenshtein(longer, shorter)
        return (longer.length - editDist) / longer.length.toDouble()
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }
}