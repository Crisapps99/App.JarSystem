// app/src/main/java/com/example/myapplication/core/ContactsManager.kt
package com.example.myapplication.core.integrations

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import java.text.Normalizer
object ContactsManager {

    data class Contact(
        val name: String,
        val phoneNumber: String,
        val normalizedName: String = name.normalizeForMatching()
    )

    private var cachedContacts: List<Contact> = emptyList()
    private var lastLoadTime: Long = 0
    private const val CACHE_DURATION = 60_000L // 1 minuto

    // ─────────────────────────────────────────────────────────────────────────
    // NORMALIZACIÓN (elimina tildes, emojis, caracteres especiales)
    // ─────────────────────────────────────────────────────────────────────────
    private fun String.normalizeForMatching(): String {
        // 1. Descomponer caracteres acentuados (é → e + ́)
        val decomposed = Normalizer.normalize(this, Normalizer.Form.NFD)
        // 2. Eliminar marcas diacríticas (tildes, diéresis, etc.)
        val noDiacritics = decomposed.replace(Regex("\\p{M}"), "")
        // 3. Eliminar emojis y cualquier carácter que no sea letra, número o espacio
        val noEmojis = noDiacritics.replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
        // 4. Minúsculas y trim
        return noEmojis.trim().lowercase()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CARGA DE CONTACTOS (con caché)
    // ─────────────────────────────────────────────────────────────────────────
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
    fun getAllContacts(context: Context): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val contentResolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: ""
                val number = it.getString(numberIndex) ?: ""
                if (name.isNotBlank() && number.isNotBlank()) {
                    contacts.add(Contact(name, number))
                }
            }
        }
        return contacts
    }
    // ─────────────────────────────────────────────────────────────────────────
    // BÚSQUEDA ROBUSTA (ignora emojis, tildes, mayúsculas)
    // ─────────────────────────────────────────────────────────────────────────
    fun findContact(context: Context, nameBuscado: String): Contact? {
        val contacts = loadContacts(context)
        val query = nameBuscado.normalizeForMatching()

        // 1. Coincidencia exacta (normalizada)
        contacts.find { it.normalizedName == query }?.let { return it }

        // 2. Contiene el nombre (en cualquiera de los dos sentidos)
        contacts.find { contact ->
            contact.normalizedName.contains(query) || query.contains(contact.normalizedName)
        }?.let { return it }

        // 3. Fuzzy matching (Levenshtein) con umbral >0.5
        return contacts
            .map { contact ->
                val similarity = calculateSimilarity(query, contact.normalizedName)
                contact to similarity
            }
            .filter { it.second > 0.5 }
            .maxByOrNull { it.second }
            ?.first
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREAR NUEVO CONTACTO (opcional, se puede activar por voz)
    // ─────────────────────────────────────────────────────────────────────────
    fun createContact(context: Context, name: String, phoneNumber: String): Boolean {
        return try {
            val values = ContentValues().apply {
                // Cambiamos put(key, null) por putNull(key) para evitar ambigüedad
                putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
            }
            val rawContactUri = context.contentResolver.insert(
                ContactsContract.RawContacts.CONTENT_URI, values
            )
            val rawContactId = rawContactUri?.lastPathSegment?.toLong()
            if (rawContactId == null) return false

            // Insertar nombre
            val nameValues = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, nameValues)

            // Insertar teléfono
            val phoneValues = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                // Aseguramos que phoneNumber se trate como String
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber as String)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, phoneValues)

            cachedContacts = emptyList()
            Log.d("JARVIS_CONTACTS", "✅ Contacto creado: $name → $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e("JARVIS_CONTACTS", "❌ Error creando contacto: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLAMADAS (con permiso CALL_PHONE)
    // ─────────────────────────────────────────────────────────────────────────
    fun makeCall(context: Context, phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Log.d("JARVIS_CALL", "📞 Llamando a $phoneNumber")
    }

    fun dialNumber(context: Context, phoneNumber: String) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILIDADES
    // ─────────────────────────────────────────────────────────────────────────
    fun toContextText(context: Context): String {
        val contacts = loadContacts(context)
        if (contacts.isEmpty()) return "Sin contactos disponibles."
        return contacts.take(100).joinToString(", ") { it.name }
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
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