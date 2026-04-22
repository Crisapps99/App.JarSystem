package com.example.myapplication.core

import android.util.Log
import com.example.myapplication.api.ActionDto
import com.google.gson.Gson

/**
 * Router local de comandos — evita llamar a Gemini para acciones simples.
 *
 * FLUJO:
 *   texto usuario → tryResolveLocally() → ActionDto[] (local) | null (ir a Gemini)
 *
 * Reglas de despacho:
 *  1. Normalizar texto (minúsculas, sin tildes, sin puntuación)
 *  2. Probar cada categoría en orden de especificidad (más específico primero)
 *  3. Si hay match → devolver payload listo para ejecutar
 *  4. Si no hay match → devolver null (el caller llama a Gemini)
 */
object LocalCommandRouter {

    private const val TAG = "LOCAL_ROUTER"

    data class RouteResult(
        val actions: List<ActionDto>,
        val responseText: String,   // Texto que Nexus habla en voz alta
        val isLocal: Boolean = true
    )

    // ── Paquetes conocidos ────────────────────────────────────────────────────
    private val KNOWN_PACKAGES = mapOf(
        listOf("whatsapp", "watsap", "wasap")               to "com.whatsapp",
        listOf("telegram")                                   to "org.telegram.messenger",
        listOf("instagram", "insta")                         to "com.instagram.android",
        listOf("youtube", "you tube")                        to "com.google.android.youtube",
        listOf("tiktok", "tik tok")                          to "com.zhiliaoapp.musically",
        listOf("spotify")                                    to "com.spotify.music",
        listOf("netflix")                                    to "com.netflix.mediaclient",
        listOf("gmail", "correo", "email")                   to "com.google.android.gm",
        listOf("chrome", "navegador")                        to "com.android.chrome",
        listOf("maps", "google maps", "mapa", "mapas")       to "com.google.android.apps.maps",
        listOf("ajustes", "configuracion", "settings")       to "com.android.settings",
        listOf("play store", "playstore", "tienda")          to "com.android.vending",
        listOf("facebook")                                   to "com.facebook.katana",
        listOf("uber")                                       to "com.ubercab",
        listOf("zoom")                                       to "us.zoom.videomeetings",
        listOf("discord")                                    to "com.discord",
        listOf("twitter", "x")                               to "com.twitter.android",
        listOf("reloj", "clock", "alarma")                   to "com.android.deskclock",
        listOf("calculadora", "calculator")                  to "com.android.calculator2",
        listOf("camara", "camera", "camara")                 to "android.media.action.IMAGE_CAPTURE",
        listOf("galeria", "fotos", "photos")                 to "com.google.android.apps.photos",
        listOf("indrive", "in drive")                        to "sinet.startup.inDriver",
    )

    // ── Entrada principal ─────────────────────────────────────────────────────
    fun tryResolveLocally(texto: String): RouteResult? {
        val t = normalize(texto)
        Log.d(TAG, "Evaluando localmente: '$t'")

        return tryVolume(t)
            ?: tryBrightness(t)
            ?: tryTime(t)
            ?: tryDate(t)
            ?: tryWifi(t)
            ?: tryBluetooth(t)
            ?: tryFlashlight(t)
            ?: tryGlobalAction(t)
            ?: tryOpenApp(t)
            ?: trySetAlarm(t)
            ?: trySearchWeb(t)
            ?: tryNavigate(t)
            ?: tryPlayMusic(t)
            ?: tryScreenshot(t)
    }

    // ── Normalización ─────────────────────────────────────────────────────────
    private fun normalize(text: String): String {
        return text.lowercase()
            .replace("á", "a").replace("é", "e").replace("í", "i")
            .replace("ó", "o").replace("ú", "u").replace("ü", "u")
            .replace("ñ", "n")
            .replace(Regex("[^a-z0-9\\s%]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ── Volumen ───────────────────────────────────────────────────────────────
    private fun tryVolume(t: String): RouteResult? {
        val isVolume = t.contains("volumen") || t.contains("volume") ||
                t.contains("audio") || t.contains("sonido")

        // "silenciar" / "mute" / "silencio"
        if (anyOf(t, "silencia", "silencio", "mute", "muda", "sin sonido")) {
            return RouteResult(
                actions = listOf(ActionDto("adjust_volume", mapOf("direction" to "down", "percent" to 0))),
                responseText = "Silenciando"
            )
        }

        // Porcentaje: "volumen al 60%"
        val pctMatch = Regex("(?:volumen|audio|sonido).*?(\\d+)\\s*%").find(t)
            ?: Regex("(\\d+)\\s*%.*(?:volumen|audio|sonido)").find(t)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return null
            val dir = if (pct >= 50) "up" else "down"
            return RouteResult(
                actions = listOf(ActionDto("adjust_volume", mapOf("direction" to dir, "percent" to pct))),
                responseText = "Volumen al $pct por ciento"
            )
        }

        if (!isVolume) return null

        val isUp = anyOf(t, "sube", "subir", "aumenta", "aumentar", "mas", "mas alto", "maximo", "max")
        val isDown = anyOf(t, "baja", "bajar", "reduce", "reducir", "menos", "minimo", "min")

        return when {
            isUp -> RouteResult(
                actions = listOf(ActionDto("adjust_volume", mapOf("direction" to "up", "steps" to 3))),
                responseText = "Subiendo el volumen"
            )
            isDown -> RouteResult(
                actions = listOf(ActionDto("adjust_volume", mapOf("direction" to "down", "steps" to 3))),
                responseText = "Bajando el volumen"
            )
            else -> null
        }
    }

    // ── Brillo ────────────────────────────────────────────────────────────────
    private fun tryBrightness(t: String): RouteResult? {
        val isBrightness = anyOf(t, "brillo", "pantalla", "luminosidad", "brightness")
        if (!isBrightness) return null

        // Porcentaje
        val pctMatch = Regex("(\\d+)\\s*%").find(t)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return null
            val dir = if (pct >= 50) "up" else "down"
            return RouteResult(
                actions = listOf(ActionDto("adjust_brightness", mapOf("direction" to dir, "percent" to pct))),
                responseText = "Brillo al $pct por ciento"
            )
        }

        val isUp = anyOf(t, "sube", "subir", "aumenta", "maximo", "max", "mas")
        val isDown = anyOf(t, "baja", "bajar", "reduce", "minimo", "min", "menos")

        return when {
            isUp -> RouteResult(
                actions = listOf(ActionDto("adjust_brightness", mapOf("direction" to "up", "steps" to 3))),
                responseText = "Subiendo el brillo"
            )
            isDown -> RouteResult(
                actions = listOf(ActionDto("adjust_brightness", mapOf("direction" to "down", "steps" to 3))),
                responseText = "Bajando el brillo"
            )
            else -> null
        }
    }

    // ── Hora ──────────────────────────────────────────────────────────────────
    private fun tryTime(t: String): RouteResult? {
        if (!anyOf(t, "que hora", "hora es", "la hora", "dime la hora", "hora actual", "que horas son")) return null
        return RouteResult(
            actions = listOf(ActionDto("query_time", emptyMap())),
            responseText = ""   // query_time habla solo
        )
    }

    // ── Fecha ─────────────────────────────────────────────────────────────────
    private fun tryDate(t: String): RouteResult? {
        if (!anyOf(t, "que dia es", "que fecha", "la fecha", "fecha de hoy", "dia de hoy", "hoy es",
                "dia es hoy", "que dia")) return null
        return RouteResult(
            actions = listOf(ActionDto("query_date", emptyMap())),
            responseText = ""
        )
    }

    // ── WiFi ──────────────────────────────────────────────────────────────────
    private fun tryWifi(t: String): RouteResult? {
        if (!t.contains("wifi") && !t.contains("wi fi") && !t.contains("red")) return null
        val state = when {
            anyOf(t, "activa", "enciende", "prende", "conecta", "encender", "activar", "on") -> "on"
            anyOf(t, "desactiva", "apaga", "desconecta", "apagar", "desactivar", "off") -> "off"
            else -> "toggle"
        }
        val resp = if (state == "on") "Encendiendo WiFi" else if (state == "off") "Apagando WiFi" else "Cambiando WiFi"
        return RouteResult(
            actions = listOf(ActionDto("toggle_wifi", mapOf("state" to state))),
            responseText = resp
        )
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────
    private fun tryBluetooth(t: String): RouteResult? {
        if (!anyOf(t, "bluetooth", "bluethoot", "bluetoot", "bt")) return null
        val state = when {
            anyOf(t, "activa", "enciende", "prende", "conecta", "on", "encender", "activar") -> "on"
            anyOf(t, "desactiva", "apaga", "desconecta", "off", "apagar", "desactivar") -> "off"
            else -> "toggle"
        }
        val resp = if (state == "on") "Encendiendo Bluetooth" else if (state == "off") "Apagando Bluetooth" else "Cambiando Bluetooth"
        return RouteResult(
            actions = listOf(ActionDto("toggle_bluetooth", mapOf("state" to state))),
            responseText = resp
        )
    }

    // ── Linterna ──────────────────────────────────────────────────────────────
    private fun tryFlashlight(t: String): RouteResult? {
        if (!anyOf(t, "linterna", "flashlight", "flash", "luz", "torch")) return null
        // "luz de fondo", "luz de pantalla" no son linterna
        if (anyOf(t, "pantalla", "brillo")) return null
        val state = when {
            anyOf(t, "apaga", "apagar", "desactiva", "off") -> "off"
            else -> "on"
        }
        val resp = if (state == "on") "Linterna encendida" else "Linterna apagada"
        return RouteResult(
            actions = listOf(ActionDto("toggle_flashlight", mapOf("state" to state))),
            responseText = resp
        )
    }

    // ── Acciones globales ─────────────────────────────────────────────────────
    private fun tryGlobalAction(t: String): RouteResult? {
        return when {
            anyOf(t, "volver", "atras", "regresa", "back") -> RouteResult(
                actions = listOf(ActionDto("global_action", mapOf("action" to "back"))),
                responseText = "Volviendo"
            )
            anyOf(t, "inicio", "home", "pantalla principal", "ir a inicio") -> RouteResult(
                actions = listOf(ActionDto("global_action", mapOf("action" to "home"))),
                responseText = "Yendo al inicio"
            )
            anyOf(t, "notificaciones", "panel de notificaciones", "barra de notificaciones") -> RouteResult(
                actions = listOf(ActionDto("global_action", mapOf("action" to "notifications"))),
                responseText = "Abriendo notificaciones"
            )
            anyOf(t, "recientes", "apps recientes", "multitarea") -> RouteResult(
                actions = listOf(ActionDto("global_action", mapOf("action" to "recents"))),
                responseText = "Abriendo apps recientes"
            )
            anyOf(t, "captura de pantalla", "screenshot", "captura pantalla", "tomar captura") -> RouteResult(
                actions = listOf(ActionDto("global_action", mapOf("action" to "screenshot"))),
                responseText = "Tomando captura"
            )
            anyOf(t, "bloquea pantalla", "bloquear pantalla", "bloquear el telefono", "bloquear movil") -> RouteResult(
                actions = listOf(ActionDto("global_action", mapOf("action" to "lock"))),
                responseText = "Bloqueando pantalla"
            )
            else -> null
        }
    }

    // ── Screenshot (alias directo) ────────────────────────────────────────────
    private fun tryScreenshot(t: String): RouteResult? = null  // ya cubierto en tryGlobalAction

    // ── Abrir app ─────────────────────────────────────────────────────────────
    private fun tryOpenApp(t: String): RouteResult? {
        // Solo procesar si contiene palabras de apertura
        if (!anyOf(t, "abre", "abrir", "abrir la", "abrir el", "abre el", "abre la",
                "pon", "poner", "lanza", "lanzar", "entra", "entrar", "ir a")) return null

        for ((keywords, pkg) in KNOWN_PACKAGES) {
            for (kw in keywords) {
                if (t.contains(kw)) {
                    val appName = keywords.first().replaceFirstChar { it.uppercase() }
                    return RouteResult(
                        actions = listOf(ActionDto("open_app", mapOf("package" to pkg))),
                        responseText = "Abriendo $appName"
                    )
                }
            }
        }
        return null
    }

    // ── Alarma ────────────────────────────────────────────────────────────────
    private fun trySetAlarm(t: String): RouteResult? {
        if (!anyOf(t, "alarma", "despertador", "alarm")) return null
        if (!anyOf(t, "pon", "poner", "set", "crea", "crear", "programar", "a las", "para las")) return null

        // Extraer hora: "a las 7", "a las 7:30", "a las 8 y media", "7 am", "7 pm"
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(?:(am|pm))?")
        val match = timeRegex.find(t) ?: return null

        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3]

        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0

        // Extraer label si hay "para" / "con etiqueta" / "para" + texto
        val label = when {
            t.contains("trabajo") -> "Trabajo"
            t.contains("ejercicio") || t.contains("gimnasio") -> "Ejercicio"
            t.contains("medicamento") || t.contains("pastilla") -> "Medicamento"
            else -> "Alarma"
        }

        val hora12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val minStr = if (minute > 0) " y $minute" else ""
        return RouteResult(
            actions = listOf(ActionDto("set_alarm", mapOf("hour" to hour, "minute" to minute, "label" to label))),
            responseText = "Alarma a las $hora12$minStr"
        )
    }

    // ── Búsqueda web ─────────────────────────────────────────────────────────
    private fun trySearchWeb(t: String): RouteResult? {
        val prefixes = listOf("busca en google", "busca en internet", "busca en la web",
            "buscar en google", "googlea", "busca", "buscar")
        for (prefix in prefixes) {
            if (t.startsWith(prefix)) {
                val query = t.removePrefix(prefix).trim()
                if (query.length < 2) return null
                return RouteResult(
                    actions = listOf(ActionDto("search_web", mapOf("query" to query))),
                    responseText = "Buscando $query"
                )
            }
        }
        return null
    }

    // ── Navegación ────────────────────────────────────────────────────────────
    private fun tryNavigate(t: String): RouteResult? {
        val prefixes = listOf("llévame a", "llevame a", "navega a", "navegar a",
            "como llego a", "como llegar a", "ruta a", "direcciones a", "ir a")
        for (prefix in prefixes) {
            if (t.contains(prefix)) {
                val dest = t.substringAfter(prefix).trim()
                if (dest.length < 2) return null
                return RouteResult(
                    actions = listOf(ActionDto("navigate_to", mapOf("destination" to dest))),
                    responseText = "Navegando a $dest"
                )
            }
        }
        return null
    }

    // ── Música ────────────────────────────────────────────────────────────────
    private fun tryPlayMusic(t: String): RouteResult? {
        if (!anyOf(t, "pon musica", "poner musica", "reproduce", "reproducir", "play",
                "escuchar", "pon una", "pon el", "pon la", "suena")) return null
        if (!anyOf(t, "musica", "cancion", "tema", "album", "artista", "play", "spotify",
                "youtube music", "banda")) {
            // Tal vez es "pon Shakira" sin decir "música"
            val queryMatch = Regex("(?:pon|reproduce|play)\\s+(.+)").find(t)
            if (queryMatch != null) {
                val q = queryMatch.groupValues[1].trim()
                if (q.length > 2) return RouteResult(
                    actions = listOf(ActionDto("play_music", mapOf("query" to q))),
                    responseText = "Reproduciendo $q"
                )
            }
            return null
        }
        val queryMatch = Regex("(?:pon|reproduce|play|escuchar|suena)\\s+(?:musica\\s+de\\s+|la cancion\\s+|el tema\\s+|)?(.+)").find(t)
        val query = queryMatch?.groupValues?.get(1)?.trim() ?: "música"
        return RouteResult(
            actions = listOf(ActionDto("play_music", mapOf("query" to query))),
            responseText = "Reproduciendo $query"
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun anyOf(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it) }
}