package com.example.myapplication.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.myapplication.model.ScreenElement

//clase encargada de gestionar  dibujos encima de las apps poner etiquetass sobre los elementos que s epeude tocar
class NumberedElementsOverlay(private val context: Context){
    //an;ade vistas directamente a la pantalla del sisitema
    private var windowManager: WindowManager? = null
    //muestra vistas personalziadas que dibuja circulso y numero s
    private var overlayView: NumberBubblesView? = null
    //list alocal para guardar que estamos viendo  actualmente enumerado
    private var elementosNumerados: List<ScreenElement> = emptyList()
    private var isVisible = false

    init {
        //inicializamos el servidio de ventanas de android
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    //muestra lso numeros sobre los elements interactivos
    fun mostrar(elementos: List<ScreenElement>){
        //procesamiento de elementos
        elementosNumerados = elementos
            //cosas que s epeuda interactuar
            .filter {it.isClickable || it.isEditable || it.isScrollable }
            //filtro de seguridad de tama;o
            .filter { it.bounds.width() > 10 && it.bounds.height() > 10}
            //orden logico
            .sortedWith (compareBy({it.bounds.top}, {it.bounds.left}))
        //limite
            .take (40)
        if (elementosNumerados.isEmpty()){
            Log.d("NUMBERED_OVERLAY", "No hay elementos interactivos para numerar")
            return
        }
        //quitamos si ya habia numeros
        ocultar()
        //vista encargada de dibujar numeros
        val view = NumberBubblesView(context, elementosNumerados)
        overlayView = view

        //conf de la ventana
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, //toda la anchura
            WindowManager.LayoutParams.MATCH_PARENT, //toda la altura

            // type_aplicattion overlay apra dibuje encima de otras apps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else

                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            // Formato transparente para ver la app que está detrás
            android.graphics.PixelFormat.TRANSLUCENT
        )
        try {
            //a;adimos la vista a la pantalla del sisitem a
            windowManager?.addView(view, params)
            isVisible = true
            Log.d("NUMBERED_OVERLAY", "✅ Mostrando ${elementosNumerados.size} elementos numerados")

            // Imprimimos en el Log qué número corresponde a qué texto para depuración
            elementosNumerados.forEachIndexed { i, e ->
                Log.d("NUMBERED_OVERLAY", "  [${i + 1}] ${e.getSearchableText()} (${e.centerX},${e.centerY})")
            }
        }catch(e: Exception){
            Log.e("NUMBERED_OVERLAY", "Error al mostrar overlay: ${e.message}")
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
    /**
     * Obtiene el elemento correspondiente al número dicho por el usuario.
     * Los números empiezan en 1 (como dice el usuario: "toca el 1").
     */
    fun obtenerPorNumero(numero: Int): ScreenElement? {
        val index = numero - 1
        return if (index in elementosNumerados.indices) elementosNumerados[index] else null
    }

    fun estaVisible() = isVisible

    fun cantidadElementos() = elementosNumerados.size

    /**
     * Genera un resumen de texto para que Jarvis lo diga en voz alta.
     * Ej: "Veo 5 elementos. 1: Buscar. 2: Menú. 3: Perfil..."
     */
    fun generarResumenParaVoz(): String {
        if (elementosNumerados.isEmpty()) return "No veo elementos interactivos en esta pantalla."
        val lista = elementosNumerados.take(8).mapIndexed { i, e ->
            "${i + 1}: ${e.getSearchableText().take(20)}"
        }.joinToString(". ")
        return "Veo ${elementosNumerados.size} elementos. $lista. Di el número para tocarlo."
    }
}
/**
 * Vista personalizada que dibuja burbujas numeradas sobre la pantalla.
 * Usa Canvas para dibujar directamente sin vistas adicionales.
 */
class NumberBubblesView(
    context: Context,
    private val elementos: List<ScreenElement>
) : View(context) {
    // Sombra para que el número sea legible sobre cualquier fondo
    private val paintSombra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    // Número principal en blanco brillante
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

            canvas.drawText(numero, cx, ty, paintSombra)
            canvas.drawText(numero, cx, ty, paintNumero)
        }
    }
}
