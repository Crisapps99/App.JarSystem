package com.example.myapplication.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.sin

class ListeningBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Margen para que el glow no se corte
    private val glowMargin = 16f * density
    private val cornerRadius = 22f * density

    // Fondo oscuro del cajón
    private val darkBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#131618")
        style = Paint.Style.FILL
    }

    // Colores del glow (más sutiles sobre fondo oscuro)
    private val gradientColors = intArrayOf(
        Color.parseColor("#2979FF"),  // azul eléctrico
        Color.parseColor("#D500F9"),  // violeta neón
        Color.parseColor("#FF1744"),  // rojo vivo
        Color.parseColor("#FF6D00"),  // naranja
        Color.parseColor("#FFD600"),  // amarillo
        Color.parseColor("#00E676"),  // verde neón
        Color.parseColor("#2979FF")
    )

    private val glowPaintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var sweepOffset = 0f
    private var wavePhase = 0f
    private var animator: ValueAnimator? = null
    private var energy = 0f

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        val pStart = (glowMargin + 10f * density).toInt()
        val pTop   = (glowMargin + 6f * density).toInt()
        val pEnd   = (glowMargin + 10f * density).toInt()
        val pBottom = (glowMargin + 4f * density).toInt()
        setPadding(pStart, pTop, pEnd, pBottom)

        startAnimation()
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepOffset = it.animatedValue as Float
                val waveSpeed = if (energy > 0.1f) 0.14f else 0.03f
                wavePhase += waveSpeed
                invalidate()
            }
            start()
        }
    }

    fun animateWithEnergy(e: Float) {
        energy = (energy * 0.5f) + (e.coerceIn(0f, 1f) * 0.5f)
        invalidate()
    }

    fun updateProgress(progress: Float) {
        animateWithEnergy(progress)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f

        val rectBox = RectF(glowMargin, glowMargin, w - glowMargin, h - glowMargin)

        val shaderOuter = SweepGradient(centerX, centerY, gradientColors, null)
        val matrixOuter = Matrix().apply { postRotate(sweepOffset, centerX, centerY) }
        shaderOuter.setLocalMatrix(matrixOuter)

        val shaderInner = SweepGradient(centerX, centerY, gradientColors, null)
        val matrixInner = Matrix().apply { postRotate(-sweepOffset * 1.2f, centerX, centerY) }
        shaderInner.setLocalMatrix(matrixInner)

        // Glow exterior
        val blurOuter = (12f * density + 8f * density * energy * sin(wavePhase)).coerceAtLeast(1f)
        glowPaintOuter.apply {
            shader = shaderOuter
            maskFilter = BlurMaskFilter(blurOuter, BlurMaskFilter.Blur.NORMAL)
            strokeWidth = (12f + energy * 18f) * density
            alpha = (160 + (energy * 90) + (12 * sin(wavePhase))).toInt().coerceIn(0, 255)
        }
        canvas.drawRoundRect(rectBox, cornerRadius, cornerRadius, glowPaintOuter)

        // Glow interior
        val blurInner = (4f * density + 2f * density * energy * cos(wavePhase.toDouble()).toFloat()).coerceAtLeast(1f)
        glowPaintInner.apply {
            shader = shaderInner
            maskFilter = BlurMaskFilter(blurInner, BlurMaskFilter.Blur.NORMAL)
            strokeWidth = (6f + energy * 10f) * density
            alpha = (200 + (energy * 55)).toInt().coerceIn(0, 255)
        }
        canvas.drawRoundRect(rectBox, cornerRadius, cornerRadius, glowPaintInner)

        // Cajón oscuro encima — tapa el interior del glow
        canvas.drawRoundRect(rectBox, cornerRadius, cornerRadius, darkBoxPaint)

        // Contenido
        super.onDraw(canvas)
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}