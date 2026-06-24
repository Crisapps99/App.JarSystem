package com.example.myapplication.ui

import android.graphics.*
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.isActive
import kotlin.math.*

// Estructuras de datos internas
private data class RingSegment(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val zMid: Float, val alphaFactor: Float, val baseStrokeWidth: Float,
    val baseColor: Int, val isRing2: Boolean
)
private data class Particle3D(
    val localX: Float, val localY: Float, val localZ: Float,
    val period: Float, val phaseOffset: Float, val size: Float
)
private data class Point3D(val x: Float, val y: Float, val z: Float)

// Estructura para componentes del átomo para ordenación de profundidad
private sealed class AtomDrawable(open val z: Float) {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, override val z: Float) : AtomDrawable(z)
    data class Electron(val x: Float, val y: Float, override val z: Float, val radius: Float) : AtomDrawable(z)
}

// Clase para agrupar las pinturas y evitar que Compose las recree innecesariamente
private class OrbPaints {
    val ring1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    val ring2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    val ambient = Paint(Paint.ANTI_ALIAS_FLAG)
    val particle = Paint(Paint.ANTI_ALIAS_FLAG)
    val prismCore = Paint(Paint.ANTI_ALIAS_FLAG)
    val atomEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val electronPaint = Paint(Paint.ANTI_ALIAS_FLAG)
}

@Composable
fun JarvisOrb(
    modifier: Modifier = Modifier,
    energy: Float = 0f,
    maxRings: Int = 6,
    showLightCore: Boolean = true,
    showParticles: Boolean = true
) {
    val density = LocalDensity.current.density
    var timeMs by remember { mutableLongStateOf(0L) }
    val particles3D = remember { mutableListOf<Particle3D>() }
    val paints = remember { OrbPaints() }
    val col00daf3 = android.graphics.Color.parseColor("#00daf3")

    // Loop de animación
    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()
        while (isActive) {
            withFrameMillis {
                timeMs = System.currentTimeMillis() - startTime
            }
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0) return@Canvas

        // Inicializar partículas si la lista está vacía
        if (particles3D.isEmpty()) {
            val r = min(w, h) * 0.40f
            repeat(25) {
                val theta = (Math.random() * PI).toFloat()
                val phi = (Math.random() * 2.0 * PI).toFloat()
                val mRadius = (0.1f + 0.5f * Math.random().toFloat()) * r
                particles3D.add(Particle3D(
                    mRadius * sin(theta) * cos(phi),
                    mRadius * sin(theta) * sin(phi),
                    mRadius * cos(theta),
                    3000f + (Math.random() * 4000f).toFloat(),
                    (Math.random() * 2.0 * PI).toFloat(),
                    (Math.random().toFloat() * 2.0f + 1.0f) * density
                ))
            }
        }

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val cx = w / 2f
            val cy = h / 2f
            val radius = min(cx, cy) * 0.50f
            val t = timeMs / 1000.0

            val blobPhase = ((sin(t * 1.0 * 2 * PI / 8.0) + 1.0) / 2.0).toFloat()
            val inverseBlobPhase = 1f - blobPhase
            val pulseScale = 1f + 0.05f * sin(t * 1.0 * 2 * PI / 4.0).toFloat()
            val rotationY = (t * 22.0).toFloat()
            val rotationX = 14f * sin(t * 1.1).toFloat()

            // 1. Aura Ambiental
            paints.ambient.shader = RadialGradient(cx, cy, radius * 1.5f,
                intArrayOf(android.graphics.Color.argb(35, 0, 218, 243), android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            nativeCanvas.drawCircle(cx, cy, radius * 1.5f, paints.ambient)

            // 2. Generar Segmentos de Anillos
            val allSegments = mutableListOf<RingSegment>()
            if (maxRings >= 1) allSegments += generate3DSegments(cx, cy, radius * 1.06f, inverseBlobPhase, rotationX, rotationY, 0f, 1.2f * density, android.graphics.Color.argb(130, 0, 218, 243), true, energy, t)

            // Dibujar capas traseras de los anillos externos
            drawSegmentsList(nativeCanvas, allSegments.filter { it.zMid < -2f }, radius, energy, paints)

            // 3. Núcleo de Luz
            if (showLightCore) {
                val coreR = radius * pulseScale * 0.55f
                paints.prismCore.shader = RadialGradient(cx, cy, coreR,
                    intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.argb(220, 0, 218, 243), android.graphics.Color.TRANSPARENT),
                    floatArrayOf(0.0f, 0.25f, 1.0f), Shader.TileMode.CLAMP)
                nativeCanvas.drawCircle(cx, cy, coreR, paints.prismCore)
            }

            // 4. Átomo 3D en el centro (reemplaza al rombo)
            drawAtomStructure(nativeCanvas, cx, cy, radius * pulseScale * 2.8f, rotationX, rotationY, density, paints, col00daf3, timeMs)

            // 5. Partículas (enjambre exterior)
            if (showParticles) {
                drawParticles(nativeCanvas, cx, cy, radius, rotationX, rotationY, particles3D, timeMs, density, paints)
            }

            // Dibujar capas delanteras de los anillos externos
            drawSegmentsList(nativeCanvas, allSegments.filter { it.zMid >= -2f }, radius, energy, paints)
        }
    }
}

private fun generate3DSegments(
    cx: Float, cy: Float, baseRadius: Float, blobPhase: Float,
    rotX: Float, rotY: Float, spin: Float, strokeWidth: Float, color: Int, isRing2: Boolean, energy: Float, t: Double
): List<RingSegment> {
    val points = 80
    val segments = mutableListOf<RingSegment>()
    val rY = Math.toRadians(rotY.toDouble()).toFloat()
    val rX = Math.toRadians(rotX.toDouble()).toFloat()
    val rS = Math.toRadians(spin.toDouble()).toFloat()
    val rBase = baseRadius * (1f + energy * 0.25f)

    val px = FloatArray(points + 1)
    val py = FloatArray(points + 1)
    val pz = FloatArray(points + 1)
//
//    for (i in 0..points) {
//        val angle = i.toFloat() / points * 2 * PI.toFloat()
////        val rOrg = rBase * (1f + (0.12f * sin(3.0 * angle).toFloat() + 0.08f * cos(2.0 * angle).toFloat())) * (1f - blobPhase) +
////                rBase * (1f + (0.10f * sin(2.0 * angle + PI).toFloat() + 0.10f * cos(3.0 * angle).toFloat())) * blobPhase
//
////        val r = rOrg // Aquí podrías añadir ondas adicionales
////        val lx = r * cos(angle + rS)
////        val ly = r * sin(angle + rS)
//
////        val ryX = lx * cos(rY)
////        val ryZ = lx * sin(rY)
////        val fX = ryX
////        val fY = ly * cos(rX) - ryZ * sin(rX)
////        val fZ = ly * sin(rX) + ryZ * cos(rX)
////
////        val pers = (baseRadius * 2.5f) / (baseRadius * 2.5f - fZ)
////        px[i] = cx + fX * pers
////        py[i] = cy + fY * pers
////        pz[i] = fZ
//    }

    for (i in 0 until points) {
        val zMid = (pz[i] + pz[i+1]) / 2f
        segments.add(RingSegment(px[i], py[i], px[i+1], py[i+1], zMid, ((zMid / baseRadius + 1f) / 2f).coerceIn(0.1f, 1f), strokeWidth, color, isRing2))
    }
    return segments
}

private fun drawSegmentsList(canvas: Canvas, segments: List<RingSegment>, radius: Float, energy: Float, paints: OrbPaints) {
    for (seg in segments) {
        val p = if (seg.isRing2) paints.ring2 else paints.ring1
        p.strokeWidth = seg.baseStrokeWidth * (1f + energy * 2f)
        p.color = seg.baseColor
        p.alpha = (seg.alphaFactor * 255).toInt()
        canvas.drawLine(seg.x1, seg.y1, seg.x2, seg.y2, p)
    }
}

// Función para dibujar el átomo 3D en el centro
private fun drawAtomStructure(canvas: Canvas, cx: Float, cy: Float, r: Float, rotX: Float, rotY: Float, density: Float, paints: OrbPaints, color: Int, timeMs: Long) {
    val rY = Math.toRadians(rotY.toDouble()).toFloat()
    val rX = Math.toRadians(rotX.toDouble()).toFloat()

    val persCenterZ = r * 2.5f // Profundidad de referencia para la perspectiva
    val atomElements = mutableListOf<AtomDrawable>()

    // Configurar pintura para los bordes del átomo
    paints.atomEdge.color = color
    paints.atomEdge.strokeWidth = 1.0f * density // Rayas finas como el rombo
    paints.atomEdge.alpha = 200

    // Configurar pintura para los electrones
    paints.electronPaint.style = Paint.Style.FILL
    paints.electronPaint.color = Color.WHITE // Color de las perlitas
    paints.electronPaint.alpha = 255
    val electronRadius = 1.8f * density // Tamaño de las perlitas

    // 1. Núcleo Wireframe (Octaedro pequeño)
    val nucleusRadius = r * 0.15f // Pequeño núcleo central
    val nucleusVertices = arrayOf(
        Point3D(0f, -nucleusRadius, 0f), Point3D(0f, nucleusRadius, 0f),
        Point3D(-nucleusRadius, 0f, 0f), Point3D(nucleusRadius, 0f, 0f),
        Point3D(0f, 0f, -nucleusRadius), Point3D(0f, 0f, nucleusRadius)
    )
    val nucleusEdges = arrayOf(
        0 to 2, 0 to 3, 0 to 4, 0 to 5,
        1 to 2, 1 to 3, 1 to 4, 1 to 5,
        2 to 4, 4 to 3, 3 to 5, 5 to 2
    )
    // Transformar y añadir líneas del núcleo
    val nucleusScreenX = FloatArray(6)
    val nucleusScreenY = FloatArray(6)
    val nucleusScreenZ = FloatArray(6)
    nucleusVertices.forEachIndexed { i, v ->
        // Rotación
        val ryX = v.x * cos(rY) - v.z * sin(rY)
        val ryZ = v.x * sin(rY) + v.z * cos(rY)
        val fY = v.y * cos(rX) - ryZ * sin(rX)
        val fZ = v.y * sin(rX) + ryZ * cos(rX)
        nucleusScreenZ[i] = fZ
        // Perspectiva
        val pers = persCenterZ / (persCenterZ - fZ)
        nucleusScreenX[i] = cx + ryX * pers
        nucleusScreenY[i] = cy + fY * pers
    }
    nucleusEdges.forEach { (a, b) ->
        val zMid = (nucleusScreenZ[a] + nucleusScreenZ[b]) / 2f
        atomElements.add(AtomDrawable.Line(nucleusScreenX[a], nucleusScreenY[a], nucleusScreenX[b], nucleusScreenY[b], zMid))
    }

    // 2. Órbitas (Anillos) Wireframe
    val numOrbitals = 4
    val orbitalPeriods = arrayOf(2500f, 3100f, 4000f, 4900f) // Periodos de los electrones
    val numLinePoints = 40 // Puntos para el ring wireframe
    for (i in 0 until numOrbitals) {
        val orbitR = r * (0.6f + i * 0.1f) // Radios crecientes
        // Ángulos de inclinación para cada órbita
        val orbitalTiltX = Math.toRadians((if (i % 2 == 0) 45.0 else -45.0) + (i * 20.0)).toFloat()
        val orbitalTiltY = Math.toRadians((if (i < 2) 30.0 else -30.0) + (i * 15.0)).toFloat()

        // Generar puntos proyectados de la órbita (wireframe ring)
        val ringPx = FloatArray(numLinePoints + 1)
        val ringPy = FloatArray(numLinePoints + 1)
        val ringPz = FloatArray(numLinePoints + 1)
        for (j in 0..numLinePoints) {
            val angle = j.toFloat() / numLinePoints * 2 * PI.toFloat()
            // Local a orbital plane
            var lx = orbitR * cos(angle)
            var ly = 0f
            var lz = orbitR * sin(angle)
            // Tilted to global space
            val tyX = lx
            val tyY = ly * cos(orbitalTiltX) - lz * sin(orbitalTiltX)
            val tyZ = ly * sin(orbitalTiltX) + lz * cos(orbitalTiltX)
            lx = tyX * cos(orbitalTiltY) - tyZ * sin(orbitalTiltY)
            ly = tyY
            lz = tyX * sin(orbitalTiltY) + tyZ * cos(orbitalTiltY)
            // Global rotation
            val ryX = lx * cos(rY) - lz * sin(rY)
            val ryZ = lx * sin(rY) + lz * cos(rY)
            val fY = ly * cos(rX) - ryZ * sin(rX)
            val fZ = ly * sin(rX) + ryZ * cos(rX)
            ringPz[j] = fZ
            // Perspectiva
            val pers = persCenterZ / (persCenterZ - fZ)
            ringPx[j] = cx + ryX * pers
            ringPy[j] = cy + fY * pers
        }
        // Añadir segmentos de la órbita wireframe
        for (j in 0 until numLinePoints) {
            val zMid = (ringPz[j] + ringPz[j+1]) / 2f
            atomElements.add(AtomDrawable.Line(ringPx[j], ringPy[j], ringPx[j+1], ringPy[j+1], zMid))
        }

        // 3. Electron Moving (Perlita dando vueltas)
        val electronPhase = (timeMs / orbitalPeriods[i]) * 2 * PI.toFloat() // Posición en la órbita
        // Local a orbital plane
        var eLx = orbitR * cos(electronPhase)
        var eLy = 0f
        var eLz = orbitR * sin(electronPhase)
        // Tilted to global space
        val eTyY = eLy * cos(orbitalTiltX) - eLz * sin(orbitalTiltX)
        val eTyZ = eLy * sin(orbitalTiltX) + eLz * cos(orbitalTiltX)
        eLx = eLx * cos(orbitalTiltY) - eTyZ * sin(orbitalTiltY)
        eLy = eTyY
        eLz = eLx * sin(orbitalTiltY) + eTyZ * cos(orbitalTiltY)
        // Global rotation
        val eRyX = eLx * cos(rY) - eLz * sin(rY)
        val eRyZ = eLx * sin(rY) + eLz * cos(rY)
        val eFy = eLy * cos(rX) - eRyZ * sin(rX)
        val eFz = eLy * sin(rX) + eRyZ * cos(rX)
        // Perspectiva
        val persE = persCenterZ / (persCenterZ - eFz)
        atomElements.add(AtomDrawable.Electron(cx + eRyX * persE, cy + eFy * persE, eFz, electronRadius))
    }

    // Dibujar todos los elementos ordenados por profundidad (z)
    atomElements.sortedBy { it.z }.forEach { element ->
        when (element) {
            is AtomDrawable.Line -> canvas.drawLine(element.x1, element.y1, element.x2, element.y2, paints.atomEdge)
            is AtomDrawable.Electron -> canvas.drawCircle(element.x, element.y, element.radius, paints.electronPaint)
        }
    }
}

private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, r: Float, rotX: Float, rotY: Float, particles: List<Particle3D>, time: Long, density: Float, paints: OrbPaints) {
    val rY = Math.toRadians(rotY.toDouble()).toFloat()
    val rX = Math.toRadians(rotX.toDouble()).toFloat()
    particles.forEach { p ->
        val phase = (time / p.period) * 2 * PI.toFloat() + p.phaseOffset
        val lx = p.localX + r * 0.05f * sin(phase)
        val ly = p.localY + r * 0.05f * cos(phase)
        val ryX = lx * cos(rY) - p.localZ * sin(rY)
        val ryZ = lx * sin(rY) + p.localZ * cos(rY)
        val fY = ly * cos(rX) - ryZ * sin(rX)
        val fZ = ly * sin(rX) + ryZ * cos(rX)
        val pers = (r * 2.5f) / (r * 2.5f - fZ)
        paints.particle.color = Color.WHITE
        paints.particle.alpha = 150
        canvas.drawCircle(cx + ryX * pers, cy + fY * pers, p.size, paints.particle)
    }
}

