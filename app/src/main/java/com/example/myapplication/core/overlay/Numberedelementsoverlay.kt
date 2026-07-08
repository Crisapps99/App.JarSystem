package com.example.myapplication.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.example.myapplication.model.ScreenElement
import kotlin.math.abs
class NumberedElementsOverlay(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var overlayView: NumberBubblesView? = null
    private var elementosNumerados: List<ScreenElement> = emptyList()
    private var isVisible = false

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }


    fun agruparElementosCercanos(elementos: List<ScreenElement>, distanciaMaxima: Int = 80): List<ScreenElement> {
        if (elementos.isEmpty()) return emptyList()

        val resultado = mutableListOf<ScreenElement>()
        val usados = mutableSetOf<ScreenElement>()

        for (elemento in elementos) {
            if (elemento in usados) continue

            // Buscar elementos cercanos a este
            val cercanos = elementos.filter { otro ->
                otro != elemento && otro !in usados &&
                        kotlin.math.abs(otro.centerX - elemento.centerX) < distanciaMaxima &&
                        kotlin.math.abs(otro.centerY - elemento.centerY) < distanciaMaxima
            }

            // Si hay cercanos, elegir el más importante
            if (cercanos.isNotEmpty()) {
                val grupo = listOf(elemento) + cercanos

                // Usamos maxByOrNull para obtener solo el objeto con mayor "peso"
                val mejor = grupo.maxByOrNull {
                    (it.bounds.width() * it.bounds.height()) + if (it.isClickable) 10000 else 0
                } ?: elemento

                resultado.add(mejor)
                usados.addAll(cercanos)
                usados.add(elemento)
            } else {
                resultado.add(elemento)
                usados.add(elemento)
            }
        }

        return resultado
    }

    fun mostrar(elementos: List<ScreenElement>) {
        // ✅ PRIMERO agrupar elementos cercanos
        val agrupados = agruparElementosCercanos(elementos, distanciaMaxima = 80)

        // ✅ LUEGO filtrar y ordenar los AGRUPADOS
        elementosNumerados = agrupados
            .filter { it.isClickable || it.isEditable || it.isScrollable }
            .filter { it.bounds.width() > 20 && it.bounds.height() > 20 }  // Aumentado a 20px
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(40)

        if (elementosNumerados.isEmpty()) {
            Log.d("NUMBERED_OVERLAY", "No hay elementos interactivos para numerar")
            return
        }

        ocultar()

        val view = NumberBubblesView(context, elementosNumerados)
        overlayView = view

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(view, params)
            isVisible = true
            Log.d("NUMBERED_OVERLAY", "Mostrando ${elementosNumerados.size} elementos numerados")

            elementosNumerados.forEachIndexed { i, e ->
                Log.d("NUMBERED_OVERLAY", "  [${i + 1}] ${e.getSearchableText().take(25)} (${e.centerX},${e.centerY})")
            }
        } catch (e: Exception) {
            Log.e("NUMBERED_OVERLAY", "Error al mostrar overlay: ${e.message}")
        }
    }

    fun resaltarElemento(numero: Int, duracionMs: Long = 500) {
        val index = numero - 1
        if (index in elementosNumerados.indices && overlayView != null) {
            overlayView?.resaltarElemento(index, duracionMs)
            Log.d("NUMBERED_OVERLAY", "Resaltando elemento #$numero")
        } else {
            Log.w("NUMBERED_OVERLAY", "No se puede resaltar #$numero: índice inválido o overlay no visible")
        }
    }

    fun ocultar() {
        if (isVisible && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
                overlayView = null
                isVisible = false
            } catch (e: Exception) {
                Log.e("NUMBERED_OVERLAY", "Error al ocultar: ${e.message}")
            }
        }
    }

    fun obtenerPorNumero(numero: Int): ScreenElement? {
        val index = numero - 1
        return if (index in elementosNumerados.indices) elementosNumerados[index] else null
    }

    fun estaVisible() = isVisible
    fun cantidadElementos() = elementosNumerados.size

    fun generarResumenParaVoz(): String {
        if (elementosNumerados.isEmpty()) return "No veo elementos interactivos en esta pantalla."
        val lista = elementosNumerados.take(8).mapIndexed { i, e ->
            "${i + 1}: ${e.getSearchableText().take(20)}"
        }.joinToString(". ")
        return "Veo ${elementosNumerados.size} elementos. $lista. Di el número para tocarlo."
    }
}

// ✅ NumberBubblesView - sin cambios
class NumberBubblesView(
    context: Context,
    private val elementos: List<ScreenElement>
) : View(context) {
    private var elementoResaltado: Int? = null
    private var tiempoResaltado = 0L

    fun resaltarElemento(index: Int, duracionMs: Long) {
        elementoResaltado = index
        tiempoResaltado = System.currentTimeMillis() + duracionMs
        invalidate()

        Handler(Looper.getMainLooper()).postDelayed({
            elementoResaltado = null
            invalidate()
        }, duracionMs)
    }

    private val paintBurbuja = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 150, 255)
        style = Paint.Style.FILL
    }

    private val paintBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paintSombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    private val paintNumero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fm = paintNumero.fontMetrics

        elementos.forEachIndexed { index, elemento ->
            val numero = (index + 1).toString()
            val cx = elemento.bounds.exactCenterX()
            val cy = elemento.bounds.exactCenterY()
            val ty = cy - (fm.ascent + fm.descent) / 2f

            val colorBurbuja = if (elementoResaltado == index) {
                Color.argb(255, 255, 200, 0)
            } else {
                Color.argb(200, 0, 150, 255)
            }

            paintBurbuja.color = colorBurbuja
            canvas.drawCircle(cx, cy, 22f, paintBurbuja)
            canvas.drawCircle(cx, cy, 22f, paintBorde)
            canvas.drawText(numero, cx, ty, paintSombra)
            canvas.drawText(numero, cx, ty, paintNumero)
        }
    }
}