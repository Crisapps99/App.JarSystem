package com.example.myapplication.core
import com.example.myapplication.model.ScreenSnapshot
object ScreenMemory {
    // Aquí guardaremos la lista de textos detectados para que sea accesible desde cualquier parte
    var lastSeenTexts: List<String> = emptyList()

    var lastSnapshot: ScreenSnapshot? = null
    /**
     * Obtiene el contexto en formato lista simple
     */
    fun getSimpleContext(): List<String> {
        return lastSnapshot?.toContextList() ?: lastSeenTexts
    }
    /**
     * Obtiene el contexto detallado para búsquedas avanzadas
     */
    fun getDetailedContext(): List<Map<String, Any>> {
        return lastSnapshot?.toDetailedMap() ?: emptyList()
    }
    /**
     * Estadísticas rápidas
     */
    fun getStats(): String {
        val snapshot = lastSnapshot ?: return "Sin datos de pantalla"
        return """
            App: ${snapshot.packageName}
            Elementos: ${snapshot.totalElements}
            Botones: ${snapshot.clickableElements}
            Campos: ${snapshot.editableElements}
        """.trimIndent()
    }
}