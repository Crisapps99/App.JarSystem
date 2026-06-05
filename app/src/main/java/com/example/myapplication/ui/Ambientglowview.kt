package com.example.myapplication.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Reemplaza los dos <View> de ambient glow del XML.
 * Pinta el glow cyan (top-left) y violet (bottom-right) con
 * RadialGradient + BlurMaskFilter, idéntico al HTML.
 */
class AmbientGlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintCyan = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintViolet = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Cyan glow — top-left (60% del ancho, 60% del alto)
        val cyanR = w * 0.55f
        paintCyan.shader = RadialGradient(
            0f, 0f, cyanR,
            intArrayOf(Color.argb(60, 0, 218, 243), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paintCyan.maskFilter = BlurMaskFilter(cyanR * 0.5f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(0f, 0f, cyanR, paintCyan)

        // Violet glow — bottom-right (70% del ancho, 70% del alto)
        val violetR = w * 0.65f
        paintViolet.shader = RadialGradient(
            w, h, violetR,
            intArrayOf(Color.argb(40, 180, 171, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paintViolet.maskFilter = BlurMaskFilter(violetR * 0.5f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(w, h, violetR, paintViolet)
    }
}