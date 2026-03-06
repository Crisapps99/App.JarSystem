package com.example.myapplication.model
//esta clase extrae los elemtnos visuales completo de la pantalla para que sepa que es exactametne cada boton
import android.R.attr.text
import android.graphics.Rect
import android.text.BoringLayout
import android.text.Editable

data class ScreenElement(
    val id: String,                    // Hash único para rastrear el elemento entre refrescos de pantalla
    val viewId: String?,               // El nombre técnico en XML (ej: "btn_login"). Útil si el botón no tiene texto.
    val className: String?,            // Define si es un "Button", "EditText", "Switch", etc.

    // para contenido visible
    val text: String?, // texto que el usuario lee
    val contentDescription: String?,
    val hintText: String? , // texto ayuda en campos vacios
    //tamaño
    val bounds:Rect,//cuadro exacto que ocua en pixeles
    val centerX: Int,
    val centerY: Int,

    //prpoopiedades de interaccion
    val isCloneable: Boolean, // si es falso no debe hacer nada
    val isLongClickable: Boolean, //acciones especiales
    val isCheckable: Boolean, //indica si es interrupcor on /off
    val isChecked: Boolean ,// estado actual del interruptor
    val isFocusable: Boolean, // indica si elemento peude ser seleccionado por teclado o voz
    val isClickable: Boolean,
    val isEditable: Boolean, //
    val isPassword: Boolean, //
    val isEnabled: Boolean, // vers si el boton esta bloqueado
    val isScrollable: Boolean,

    val parentText: String?,           // Texto del contenedor. "Ajustes > Wi-Fi". Ayuda a desambiguar
    val siblingTexts: List<String>,    // Textos cercanos.

    //  ACCIONES DISPONIBLES
    val availableActions: List<String>, // Listade capacidades técnicas del elemento.

    //  METADATOS
    val importance: Int,               // Prioridad calculada
    val visibility: String

){
    fun getSearchableText(): String{
        return buildString{
            text?.let{append("$it")}
            contentDescription?.let{append("$it")}
            hintText?.let { append("$it") }
            viewId?.let{
                val humanReadable = it.split("_","/") //rompe el texto donde vea guiones bajos o vbarras
                    .filter { s -> s.length > 2 } // descarta conectores cortos  o basura como id btn
                    .joinToString(" ") //jutna lo que queda con espaciso
                append("$humanReadable")
            }
            parentText?.let { append("cerca de $it") }
        }.trim().lowercase()
    }

    //asigna puntos alos elemtnos clicleables con ams improtancia
    companion object{
        fun calculateImportance(
            isClickable: Boolean,
            text: String?,
            contentDescription: String?,
            className: String?
        ):Int{
            var score = 50
            if (isClickable) score += 20 // Tocar es lo más importante en Jarvis
            if (!text.isNullOrBlank()) score += 15
            if (!contentDescription.isNullOrBlank()) score += 10

            when {
                className?.contains("Button") == true -> score += 15
                className?.contains("EditText") == true -> score += 10
            }
            return score.coerceIn(0, 100)
        }
    }
}
data class ScreenSnapshot(
    val timestamp: Long,               // Para saber qué tan vieja es la información.
    val packageName: String,           // Ejemplo: "com.whatsapp".
    val activityName: String?,         // Ejemplo: ".ChatActivity".
    val elements: List<ScreenElement>, // La lista de todos los botones y textos.
    val totalElements: Int,
    val clickableElements: Int,
    val editableElements: Int,
    val scrollableContainers: Int
) {/**
 * toContextList: Prepara los datos para el servidor.
 * Filtra la "basura" visual y solo envía elementos con importancia > 30.
 * Esto reduce el consumo de internet y acelera la respuesta de la IA.
 */
fun toContextList(): List<String> {
    return elements
        .filter { it.importance > 30 }
        .sortedByDescending { it.importance }
        .map { it.getSearchableText() }
        .filter { it.isNotBlank() }
        .distinct()
}
    /**
    * toDetailedMap: Lo que el backend recibe como JSON.
    * Envía las coordenadas (X, Y) para que cuando la IA decida "Tocar",
    * el servidor le devuelva al móvil el punto exacto donde poner el dedo.
    */
    fun toDetailedMap(): List<Map<String, Any>> {
        return elements.map { elem ->
            mapOf(
                "text" to (elem.getSearchableText()),
                "x" to elem.centerX,
                "y" to elem.centerY,
                "clickable" to elem.isClickable,
                "editable" to elem.isEditable,
                "actions" to elem.availableActions,
                "importance" to elem.importance,
                "type" to (elem.className ?: "unknown")
            )
        }
    }
}
//paquete de datos final como una foto de la patnalla actual