package com.example.myapplication.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ListeningBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    // Paint para el efecto de brillo/glow
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    // Colores del borde animado
    private val gradientColors = intArrayOf(
        Color.parseColor("#4285F4"), // Azul
        Color.parseColor("#EA4335"), // Rojo
        Color.parseColor("#FBBC05"), // Amarillo
        Color.parseColor("#34A853"), // Verde
        Color.parseColor("#9C27B0"), // Morado
        Color.parseColor("#FF6D00"), // Naranja
        Color.parseColor("#4285F4")  // Cierra con azul
    )

    private var sweepOffset = 0f
    private var animator: ValueAnimator? = null
    private var energy = 0f

    init {
        setBackgroundColor(Color.TRANSPARENT)
        startAnimation()
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun animateWithEnergy(e: Float) {
        energy = e.coerceIn(0f, 1f)

        // Ajustar velocidad según energía
        val newDuration = (3000 - energy * 1500).toLong().coerceAtLeast(1500)
        if (animator?.duration != newDuration) {
            animator?.duration = newDuration
        }

        // Ajustar grosor del borde según energía
        val newStroke = 3f + energy * 8f
        paint.strokeWidth = newStroke

        invalidate()
    }

    fun updateProgress(progress: Float) {
        animateWithEnergy(progress)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f
        val cornerRadius = h / 2f

        val inset = paint.strokeWidth / 2f
        val rect = RectF(inset, inset, w - inset, h - inset)

        // Capa 1: Brillo externo (glow)
        val glowShader = SweepGradient(centerX, centerY, gradientColors, null)
        val glowMatrix = Matrix()
        glowMatrix.postRotate(sweepOffset, centerX, centerY)
        glowShader.setLocalMatrix(glowMatrix)

        glowPaint.apply {
            shader = glowShader
            strokeWidth = paint.strokeWidth + 8f
            alpha = 60 + (energy * 100).toInt()
        }
        canvas.drawRoundRect(rect.left - 4f, rect.top - 4f,
            rect.right + 4f, rect.bottom + 4f,
            cornerRadius + 4f, cornerRadius + 4f, glowPaint)

        // Capa 2: Borde principal animado
        val mainShader = SweepGradient(centerX, centerY, gradientColors, null)
        val mainMatrix = Matrix()
        mainMatrix.postRotate(sweepOffset, centerX, centerY)
        mainShader.setLocalMatrix(mainMatrix)

        paint.shader = mainShader
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        // Capa 3: Borde interior brillante
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = paint.strokeWidth / 2f
            shader = mainShader
            alpha = 150
        }
        val innerRect = RectF(inset + 2f, inset + 2f,
            w - inset - 2f, h - inset - 2f)
        canvas.drawRoundRect(innerRect, cornerRadius - 2f, cornerRadius - 2f, innerPaint)
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