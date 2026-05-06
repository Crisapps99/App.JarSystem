package com.example.myapplication.core

import android.util.Log
import com.example.myapplication.api.ActionDto

object CommandAnalyzer {

    private const val TAG = "CMD_ROUTER"

    // ── Resultado del router ──────────────────────────────────────────────
    data class RouteResult(
        val payload: List<ActionDto>,
        val responseText: String
    )

    // ── Punto de entrada principal ────────────────────────────────────────
    fun tryResolve(texto: String): RouteResult? {
        val t = texto.lowercase().trim()
        return resolverSistema(t)
            ?: resolverHora(t)
            ?: resolverAlarma(t)
            ?: resolverApps(t)
            ?: resolverMultimedia(t)
            ?: resolverNavegacion(t)
            ?: resolverBusqueda(t)
            ?: resolverLlamada(t)
    }

    // ════════════════════════════════════════════════════════════════════
    // SECCIÓN 1 — SISTEMA (volumen, brillo, wifi, bluetooth, linterna,
    //             flashlight, pantalla, screenshots, notificaciones)
    // ════════════════════════════════════════════════════════════════════

    private fun resolverSistema(t: String): RouteResult? {

        // ── VOLUMEN ──────────────────────────────────────────────────────

        // Subir volumen
        if (matchAny(t,
                "sube el volumen", "sube volumen", "más volumen", "mas volumen",
                "aumenta el volumen", "aumenta volumen", "volumen más alto",
                "volumen mas alto", "pon más volumen", "pon mas volumen",
                "súbele al volumen", "subele al volumen", "súbele el volumen",
                "subele el volumen", "sube el audio", "más audio", "mas audio"
            )) return RouteResult(
            listOf(ActionDto("adjust_volume", mapOf("direction" to "up", "steps" to 3))),
            "Subiendo el volumen."
        )

        // Bajar volumen
        if (matchAny(t,
                "baja el volumen", "baja volumen", "menos volumen", "volumen más bajo",
                "volumen mas bajo", "bájale al volumen", "bajale al volumen",
                "baja el audio", "menos audio", "silencia", "silenciar",
                "pon en silencio", "modo silencio", "silencio"
            )) {
            val esSilencio = matchAny(t, "silencia", "silenciar", "silencio", "pon en silencio", "modo silencio")
            return if (esSilencio)
                RouteResult(
                    listOf(ActionDto("adjust_volume", mapOf("direction" to "down", "percent" to 0))),
                    "Silenciando el teléfono."
                )
            else
                RouteResult(
                    listOf(ActionDto("adjust_volume", mapOf("direction" to "down", "steps" to 3))),
                    "Bajando el volumen."
                )
        }

        // ── BRILLO ───────────────────────────────────────────────────────

        if (matchAny(t,
                "sube el brillo", "sube brillo", "más brillo", "mas brillo",
                "aumenta el brillo", "aumenta brillo", "brillo más alto",
                "brillo mas alto", "pon más brillo", "pon mas brillo",
                "brillo al máximo", "brillo maximo", "brillo máximo"
            )) {
            val esMaximo = matchAny(t, "máximo", "maximo", "al máximo", "al maximo")
            return RouteResult(
                listOf(ActionDto("adjust_brightness",
                    if (esMaximo) mapOf("direction" to "up", "percent" to 100)
                    else mapOf("direction" to "up", "steps" to 3))),
                if (esMaximo) "Brillo al máximo." else "Subiendo el brillo."
            )
        }

        if (matchAny(t,
                "baja el brillo", "baja brillo", "menos brillo", "brillo más bajo",
                "brillo mas bajo", "brillo al mínimo", "brillo minimo", "brillo mínimo",
                "reduce el brillo", "oscurece la pantalla", "oscurece pantalla"
            )) {
            val esMinimo = matchAny(t, "mínimo", "minimo", "al mínimo", "al minimo")
            return RouteResult(
                listOf(ActionDto("adjust_brightness",
                    if (esMinimo) mapOf("direction" to "down", "percent" to 5)
                    else mapOf("direction" to "down", "steps" to 3))),
                if (esMinimo) "Brillo al mínimo." else "Bajando el brillo."
            )
        }

        // ── WIFI ──────────────────────────────────────────────────────────

        if (matchAny(t,
                "activa el wifi", "activa wifi", "enciende el wifi", "enciende wifi",
                "prende el wifi", "prende wifi", "conecta el wifi", "conecta wifi",
                "pon el wifi", "activa la wifi", "wi-fi on", "wifi on",
                "necesito wifi", "conectarme al wifi"
            )) return RouteResult(
            listOf(ActionDto("toggle_wifi", mapOf("state" to "on"))),
            "WiFi encendido."
        )

        if (matchAny(t,
                "apaga el wifi", "apaga wifi", "desactiva el wifi", "desactiva wifi",
                "desconecta el wifi", "quita el wifi", "wifi off", "wi-fi off",
                "desconecta wifi", "apaga la wifi"
            )) return RouteResult(
            listOf(ActionDto("toggle_wifi", mapOf("state" to "off"))),
            "WiFi apagado."
        )

        if (matchAny(t, "activa o desactiva wifi", "toggle wifi", "cambia wifi",
                "wifi toggle", "cambia el wifi")) return RouteResult(
            listOf(ActionDto("toggle_wifi", mapOf("state" to "toggle"))),
            "Cambiando estado del WiFi."
        )

        // ── BLUETOOTH ─────────────────────────────────────────────────────

        if (matchAny(t,
                "activa el bluetooth", "activa bluetooth", "enciende bluetooth",
                "enciende el bluetooth", "prende bluetooth", "conecta bluetooth",
                "bluetooth on", "activa el bt", "enciende bt"
            )) return RouteResult(
            listOf(ActionDto("toggle_bluetooth", mapOf("state" to "on"))),
            "Bluetooth encendido."
        )

        if (matchAny(t,
                "apaga el bluetooth", "apaga bluetooth", "desactiva bluetooth",
                "desactiva el bluetooth", "bluetooth off", "apaga el bt",
                "desconecta bluetooth", "quita el bluetooth"
            )) return RouteResult(
            listOf(ActionDto("toggle_bluetooth", mapOf("state" to "off"))),
            "Bluetooth apagado."
        )

        // ── LINTERNA ──────────────────────────────────────────────────────

        if (matchAny(t,
                "enciende la linterna", "enciende linterna", "prende la linterna",
                "prende linterna", "activa la linterna", "activa linterna",
                "linterna on", "necesito luz", "alumbra", "pon la linterna",
                "flash on", "enciende el flash"
            )) return RouteResult(
            listOf(ActionDto("toggle_flashlight", mapOf("state" to "on"))),
            "Linterna encendida."
        )

        if (matchAny(t,
                "apaga la linterna", "apaga linterna", "desactiva la linterna",
                "desactiva linterna", "linterna off", "quita la linterna",
                "apaga la luz", "flash off", "apaga el flash"
            )) return RouteResult(
            listOf(ActionDto("toggle_flashlight", mapOf("state" to "off"))),
            "Linterna apagada."
        )

        // ── ACCIONES GLOBALES ─────────────────────────────────────────────

        if (matchAny(t, "volver", "regresar", "ir atrás", "ir atras",
                "atrás", "atras", "back", "regresa")) return RouteResult(
            listOf(ActionDto("global_action", mapOf("action" to "back"))),
            "Volviendo atrás."
        )

        if (matchAny(t, "ir al inicio", "ir a inicio", "pantalla principal",
                "home", "inicio", "pantalla de inicio", "ir a home",
                "volver al inicio", "menú principal", "menu principal")) return RouteResult(
            listOf(ActionDto("global_action", mapOf("action" to "home"))),
            "Yendo al inicio."
        )

        if (matchAny(t, "apps recientes", "apps abiertas", "recientes",
                "multitarea", "ver aplicaciones abiertas", "cambiar app",
                "cambiar aplicación", "cambiar aplicacion")) return RouteResult(
            listOf(ActionDto("global_action", mapOf("action" to "recents"))),
            "Mostrando apps recientes."
        )

        if (matchAny(t, "tomar captura", "tomar screenshot", "captura de pantalla",
                "screenshot", "captura pantalla", "tomar foto de pantalla",
                "foto de la pantalla", "pantallazo")) return RouteResult(
            listOf(ActionDto("global_action", mapOf("action" to "screenshot"))),
            "Tomando captura de pantalla."
        )

        if (matchAny(t, "bloquear pantalla", "bloquear teléfono", "bloquear telefono",
                "bloquea el teléfono", "bloquea el telefono", "apaga la pantalla",
                "lock", "bloquea pantalla")) return RouteResult(
            listOf(ActionDto("global_action", mapOf("action" to "lock"))),
            "Bloqueando la pantalla."
        )

        if (matchAny(t, "notificaciones", "abre las notificaciones",
                "ver notificaciones", "muestra notificaciones",
                "panel de notificaciones", "baja el panel")) return RouteResult(
            listOf(ActionDto("global_action", mapOf("action" to "notifications"))),
            "Abriendo notificaciones."
        )

        // ── HORA Y FECHA ──────────────────────────────────────────────────

        if (matchAny(t, "qué hora es", "que hora es", "dime la hora",
                "hora actual", "qué horas son", "que horas son",
                "me dices la hora", "dime qué hora es")) return RouteResult(
            listOf(ActionDto("query_time", emptyMap())),
            ""  // el AccessibilityService habla solo
        )

        if (matchAny(t, "qué día es", "que dia es", "qué fecha es", "que fecha es",
                "dime la fecha", "fecha actual", "qué día es hoy",
                "que dia es hoy", "cuál es la fecha", "cual es la fecha")) return RouteResult(
            listOf(ActionDto("query_date", emptyMap())),
            ""
        )
// ── SCROLL ────────────────────────────────────────────────────────

        if (matchAny(t, "baja", "baja la página", "baja la pagina", "scroll abajo",
                "scroll hacia abajo", "desliza abajo", "desplázate abajo",
                "desplazate abajo", "mueve abajo", "más abajo", "mas abajo"
            ) && !matchAny(t, "volumen", "brillo", "baja el vol", "baja la voz")
        ) return RouteResult(
            listOf(ActionDto("scroll", mapOf("direction" to "down"))),
            ""
        )

        if (matchAny(t, "sube", "sube la página", "sube la pagina", "scroll arriba",
                "scroll hacia arriba", "desliza arriba", "más arriba", "mas arriba"
            ) && !matchAny(t, "volumen", "brillo", "sube el vol")
        ) return RouteResult(
            listOf(ActionDto("scroll", mapOf("direction" to "up"))),
            ""
        )

        return null
    }

    // ════════════════════════════════════════════════════════════════════
    // SECCIÓN 2 — HORA Y ALARMAS
    // ════════════════════════════════════════════════════════════════════

    private fun resolverHora(t: String): RouteResult? = null  // ya en resolverSistema

    private fun resolverAlarma(t: String): RouteResult? {
        // Detectar "pon una alarma a las X" / "despiértame a las X"
        val patronesAlarma = listOf(
            Regex("""(?:pon|poner|set|crear|crea|programa|programar|activa|activar).*alarma.*(?:a las?|para las?)\s*(\d{1,2})(?::(\d{2}))?"""),
            Regex("""(?:despiértame|despertarme|despértame|wake me up).*(?:a las?|para las?)\s*(\d{1,2})(?::(\d{2}))?"""),
            Regex("""alarma.*(?:a las?|para las?)\s*(\d{1,2})(?::(\d{2}))?"""),
            Regex("""(?:a las?|para las?)\s*(\d{1,2})(?::(\d{2}))?.*(?:alarma|despertador)""")
        )

        for (patron in patronesAlarma) {
            val match = patron.find(t) ?: continue
            val hora = match.groupValues[1].toIntOrNull() ?: continue
            val minuto = match.groupValues[2].toIntOrNull() ?: 0
            val label = if (t.contains("despert")) "Despertar" else "Alarma"
            return RouteResult(
                listOf(ActionDto("set_alarm", mapOf("hour" to hora, "minute" to minuto, "label" to label))),
                "Alarma configurada para las $hora:${minuto.toString().padStart(2,'0')}."
            )
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════════
    // SECCIÓN 3 — ABRIR APPS
    // ════════════════════════════════════════════════════════════════════

    private val APPS = mapOf(
        "com.whatsapp"                    to listOf("whatsapp", "wsp", "wa"),
        "org.telegram.messenger"          to listOf("telegram"),
        "com.instagram.android"           to listOf("instagram", "insta"),
        "com.google.android.youtube"      to listOf("youtube", "you tube"),
        "com.zhiliaoapp.musically"        to listOf("tiktok", "tik tok"),
        "com.spotify.music"               to listOf("spotify"),
        "com.netflix.mediaclient"         to listOf("netflix"),
        "com.google.android.gm"           to listOf("gmail", "correo", "email"),
        "com.android.chrome"              to listOf("chrome", "navegador", "browser"),
        "com.google.android.apps.maps"    to listOf("maps", "google maps", "mapa"),
        "com.android.settings"            to listOf("ajustes", "configuración", "configuracion", "settings"),
        "com.android.vending"             to listOf("play store", "playstore", "tienda"),
        "com.facebook.katana"             to listOf("facebook", "face", "fb"),
        "com.ubercab"                     to listOf("uber"),
        "sinet.startup.inDriver"          to listOf("indrive", "in drive"),
        "us.zoom.videomeetings"           to listOf("zoom"),
        "com.discord"                     to listOf("discord"),
        "com.twitter.android"             to listOf("twitter", "x"),
        "com.android.deskclock"           to listOf("reloj", "clock", "despertador"),
        "com.android.calculator2"         to listOf("calculadora", "calculator"),
        "com.google.android.apps.photos"  to listOf("fotos", "galería", "galeria", "photos"),
        "com.google.android.apps.messaging" to listOf("mensajes", "sms", "messages"),
        "com.google.android.music"        to listOf("música", "musica", "google music"),
        "com.amazon.mShop.android.shopping" to listOf("amazon"),
        "com.snapchat.android"            to listOf("snapchat", "snap")
    )

    private fun resolverApps(t: String): RouteResult? {
        val verbosAbrir = listOf("abre", "abrir", "lanza", "lanzar", "abre la app",
            "abre la aplicación", "abre la aplicacion", "entra a", "entra en",
            "ir a", "muéstrame", "muestrame", "pon", "accede a", "acceder a",
            "necesito", "quiero abrir")

        // Verificar si el texto menciona abrir + app
        val mencionaAbrir = verbosAbrir.any { t.contains(it) } || t.startsWith("abre")

        for ((pkg, keywords) in APPS) {
            for (kw in keywords) {
                if (t.contains(kw)) {
                    val appName = kw.replaceFirstChar { it.uppercase() }
                    return RouteResult(
                        listOf(ActionDto("open_app", mapOf("package" to pkg))),
                        "Abriendo $appName."
                    )
                }
            }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════════
    // SECCIÓN 4 — MULTIMEDIA
    // ════════════════════════════════════════════════════════════════════

    private fun resolverMultimedia(t: String): RouteResult? {

        // Reproducir música con búsqueda
        val patronMusica = listOf(
            Regex("""(?:reproduce|pon|poner|play|escuchar|quiero escuchar|pon a)\s+(.+?)(?:\s+en spotify|\s+en youtube|\s+en música|$)"""),
            Regex("""(?:ponme|pónme)\s+(.+?)(?:\s+en spotify|\s+en youtube|$)""")
        )
        for (patron in patronMusica) {
            val match = patron.find(t) ?: continue
            val query = match.groupValues[1].trim()
            if (query.isNotBlank() && !esComandoSistema(query)) {
                return RouteResult(
                    listOf(ActionDto("play_music", mapOf("query" to query))),
                    "Reproduciendo $query."
                )
            }
        }

        // Música genérica sin artista
        if (matchAny(t, "pon música", "pon musica", "reproduce música",
                "reproduce musica", "música aleatoria", "musica aleatoria",
                "pon algo de música", "pon algo de musica")) return RouteResult(
            listOf(ActionDto("play_music", mapOf("query" to "música"))),
            "Reproduciendo música."
        )

        // Cámara / foto
        if (matchAny(t, "toma una foto", "tomar foto", "tomar una foto",
                "saca una foto", "foto", "cámara", "camara", "abre la cámara",
                "abre la camara", "abre cámara", "quiero tomar una foto")) return RouteResult(
            listOf(ActionDto("take_photo", emptyMap())),
            "Abriendo cámara."
        )

        if (matchAny(t, "selfie", "tómate una foto", "tomate una foto",
                "foto frontal", "cámara frontal", "camara frontal")) return RouteResult(
            listOf(ActionDto("take_photo", mapOf("mode" to "frontal"))),
            "Abriendo cámara frontal."
        )

        return null
    }

    // ════════════════════════════════════════════════════════════════════
    // SECCIÓN 5 — NAVEGACIÓN / MAPAS
    // ════════════════════════════════════════════════════════════════════

    private fun resolverNavegacion(t: String): RouteResult? {
        val patrones = listOf(
            Regex("""(?:llévame a|llevame a|ruta a|cómo llego a|como llego a|ir a|navegar a|navegar hasta|cómo voy a|como voy a|dirección a|direccion a)\s+(.+)"""),
            Regex("""(?:quiero ir a|quiero llegar a)\s+(.+)""")
        )
        for (patron in patrones) {
            val match = patron.find(t) ?: continue
            val destino = match.groupValues[1].trim()
            if (destino.isNotBlank()) {
                return RouteResult(
                    listOf(ActionDto("navigate_to", mapOf("destination" to destino))),
                    "Calculando ruta hacia $destino."
                )
            }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════════
    // SECCIÓN 6 — BÚSQUEDA WEB
    // ════════════════════════════════════════════════════════════════════

    private fun resolverBusqueda(t: String): RouteResult? {
        val patron = Regex("""(?:busca|buscar|googlea|googlear|investiga|busca en google|busca en internet)\s+(.+)""")
        val match = patron.find(t) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isBlank()) return null
        return RouteResult(
            listOf(ActionDto("search_web", mapOf("query" to query))),
            "Buscando $query."
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // SECCIÓN 7 — LLAMADAS
    // ════════════════════════════════════════════════════════════════════

    private fun resolverLlamada(t: String): RouteResult? {
        val patron = Regex("""(?:llama a|llamar a|llámale a|llámale a|llama al|llamar al|telefonea a|marcar a|marca a|quiero llamar a|quiero hablar con)\s+(.+)""")
        val match = patron.find(t) ?: return null
        val contacto = match.groupValues[1].trim()
            .replace(" por whatsapp", "").replace(" por teléfono", "")
            .replace(" al celular", "").trim()
        if (contacto.isBlank()) return null
        return RouteResult(
            listOf(ActionDto("call_contact", mapOf("contact" to contacto))),
            "Llamando a $contacto."
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════

    private fun matchAny(texto: String, vararg frases: String): Boolean =
        frases.any { texto.contains(it) }

    private fun esComandoSistema(texto: String): Boolean =
        listOf("wifi", "bluetooth", "brillo", "volumen", "linterna",
            "alarma", "hora", "fecha").any { texto.contains(it) }
}