package com.example.myapplication.model
//esta clase extrae los elemtnos visuales completo de la pantalla para que sepa que es exactametne cada boton
import android.graphics.Rect

data class ScreenElement(
    val id: String,                    // Hash único para rastrear el elemento entre refrescos de pantalla
    val viewId: String?,               // El nombre técnico en XML (ej: "btn_login"). Útil si el botón no tiene texto.
    val className: String?,            // Define si es un "Button", "EditText", "Switch", etc.
)