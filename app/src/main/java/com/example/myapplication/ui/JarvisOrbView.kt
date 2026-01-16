package com.example.myapplication.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.R
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.graphics.Path
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.max

class JarvisOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var rms = 0f
    private var smoothRms = 0f
    private var rotation = 0f
    private var breathe = 0f

    private val smoothing = 0.15f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var externalEnergy = 0f
    private var targetEnergy = 0f

    fun updateRms(rmsDb: Float) {

        //si el sonido e smuy bajo como lcik ignoramos
        if (rmsDb<2.0f){
            rms = 0f
            smoothRms +=(0f - smoothRms) * smoothing
            return
        }
        //boots y suavisado
        val normalizer = (rmsDb / 12f).coerceIn(0f, 1f)
        rms = normalizer *1.5f
        smoothRms += (rms - smoothRms) * smoothing
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) * 0.30f

        externalEnergy += (targetEnergy - externalEnergy) * 0.08f
        val energy = max(smoothRms / 10f, externalEnergy)

        breathe += 0.015f
        val breathing = sin(breathe) * baseRadius * 0.03f

        val radius = baseRadius + breathing+(energy*baseRadius*0.15f)

        // 🌌 HALO EXTERNO GRANDE Y DIFUSO
        basePaint.shader = RadialGradient(
            cx, cy, radius * 2.6f,
            intArrayOf(
                Color.argb((30 + energy * 100).toInt(), 120, 160, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.15f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 2.2f, basePaint)

        // ✨ CAPA INTERNA LUMINOSA
        basePaint.shader = RadialGradient(
            cx, cy, radius * 0.9f,
            intArrayOf(
                Color.argb((180 + energy * 75).toInt(), 180, 200, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.9f, basePaint)

        // 🔮 NÚCLEO PRINCIPAL (gel)
        basePaint.shader = RadialGradient(
            cx - radius * 0.25f,
            cy - radius * 0.25f,
            radius,
            intArrayOf(
                Color.rgb(150, 180, 255),
                Color.rgb(100, 130, 230),
                Color.rgb(60, 80, 160)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, basePaint)

        // BLOBS ORGÁNICOS INTERNOS
        blobPaint.alpha = (90 + energy * 165).toInt()
        rotation += 0.18f + energy * 2.5f //rotacion mas rapida

        drawBlob(canvas, cx, cy, radius * 0.88f, rotation, Color.argb(120, 255, 90, 160))
        drawBlob(canvas, cx, cy, radius * 0.75f, -rotation * 0.6f, Color.argb(110, 120, 190, 255))
        drawBlob(canvas, cx, cy, radius * 0.62f, rotation * 1.1f, Color.argb(90, 180, 140, 255))

        postInvalidateOnAnimation()
    }

    private fun drawBlob(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        angle: Float,
        color: Int
    ) {
        val path = Path()
        val points = 16
        val amplitude = radius * 0.12f
        val energy = max(smoothRms, externalEnergy)

        for (i in 0..points) {
            val t = (i.toFloat() / points) * Math.PI * 2
            val deform =
                sin(t * 1.8 + angle * 0.015) *
                        amplitude *
                        (0.3f + energy * 1.2f)

            val r = radius + deform
            val x = cx + cos(t) * r
            val y = cy + sin(t) * r

            if (i == 0) path.moveTo(x.toFloat(), y.toFloat())
            else path.lineTo(x.toFloat(), y.toFloat())
        }

        path.close()
        blobPaint.color = color
        canvas.drawPath(path, blobPaint)
    }
    //funcion para resetear energias
    fun reset(){
        rms=0f
        smoothRms = 0f
        externalEnergy = 0f
        targetEnergy = 0f
    }
}
