package com.example.myapplication.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.activity.ActionExecutor
import com.example.myapplication.api.ActionDto
import com.example.myapplication.api.ReporteFeedback
import com.example.myapplication.api.RetrofitClient
import com.example.myapplication.core.NotificationMemory
import com.example.myapplication.model.ScreenElement
import com.example.myapplication.model.ScreenSnapshot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import com.example.myapplication.service.JarvisNotificationListener
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.example.myapplication.util.PermissionHelper
import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings

class MyAccessibilityService : AccessibilityService() {

    private val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var textoUltimaOrden: String = ""
    private var intencionUltimaOrden: String = "desconocida"

    private var lastSnapshot: ScreenSnapshot? = null
    private var lastSnapshotTime: Long = 0
    private val CACHE_DURATION = 2000L
    private var snapshotJob: Job? = null
    private var lastFingerprint: String = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ultimoTextoEscrito: String = ""

    companion object {
        var instance: MyAccessibilityService? = null
        private const val TAG = "JARVIS_ACCESSIBILITY"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // FIX CRÍTICO: Registra el BroadcastReceiver aquí
        try {
            val filter = IntentFilter(ACTION_EXECUTE)
            val context: Context = this@MyAccessibilityService
            registerReceiver(actionsReceiver, filter, RECEIVER_NOT_EXPORTED)
            Log.i(TAG, "✅ BroadcastReceiver registrado para: $ACTION_EXECUTE")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registrando BroadcastReceiver: ${e.message}", e)
        }
        verificarPermisosCamara()
        Log.d(TAG, "✅ AccessibilityService conectado y listo")
    }

    // 🔴 LOGGING EXHAUSTIVO: BroadcastReceiver con debugging completo
    private val actionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "╔═══════════════════════════════════════════════════════════")
            Log.d(TAG, "║ 📥 BROADCAST RECIBIDO")
            Log.d(TAG, "╠═══════════════════════════════════════════════════════════")
            Log.d(TAG, "║ Action: ${intent?.action}")

            if (intent?.action != ACTION_EXECUTE) {
                Log.w(TAG, "║ ⚠️ Action no coincide. Esperado: $ACTION_EXECUTE")
                Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
                return
            }

            val json = intent.getStringExtra("actions_json")
            Log.d(TAG, "║ JSON recibido: ${json?.take(200)}...")

            if (json == null) {
                Log.e(TAG, "║ ❌ JSON es NULL - No hay acciones para ejecutar")
                Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
                return
            }

            textoUltimaOrden = intent.getStringExtra("texto_original") ?: "orden sin texto"
            intencionUltimaOrden = intent.getStringExtra("intencion_original") ?: "desconocida"

            Log.d(TAG, "║ Texto original: '$textoUltimaOrden'")
            Log.d(TAG, "║ Intención: '$intencionUltimaOrden'")
            Log.d(TAG, "║ → Pasando a ejecutarAcciones()...")
            Log.d(TAG, "╚═══════════════════════════════════════════════════════════")

            handler.post { ejecutarAcciones(json) }
        }
    }

    fun captureNow() {
        serviceScope.launch(Dispatchers.Main) {
            var root = rootInActiveWindow
            var intentos = 0

            while (root == null && intentos < 5) {
                delay(200)
                root = rootInActiveWindow
                intentos++
            }

            if (root == null) {
                Log.e(TAG, "❌ [FALLO CRÍTICO] No se puede obtener rootInActiveWindow tras $intentos reintentos.")
                return@launch
            }

            Log.d(TAG, "📸 Captura manual iniciada sobre: ${root.packageName}")
            actualizarSnapDePantalla(force = true)

            com.example.myapplication.core.ScreenMemory.lastSnapshot = lastSnapshot
            com.example.myapplication.core.ScreenMemory.lastSeenTexts = lastSnapshot?.toContextList() ?: emptyList()
        }
    }

    fun captureCurrentScreenNow(callback: ((ScreenSnapshot?) -> Unit)? = null) {
        serviceScope.launch(Dispatchers.Main) {
            // Esperar a que la ventana esté lista
            var root = rootInActiveWindow
            var intentos = 0

            // Reintentar hasta 5 veces con delays
            while (root == null && intentos < 8) {
                delay(2500)
                root = rootInActiveWindow
                intentos++
                Log.d(TAG, "🔄 Reintentando captura... intento $intentos")
            }

            if (root == null) {
                Log.e(TAG, "❌ No se pudo obtener rootInActiveWindow después de $intentos intentos")
                callback?.invoke(null)
                return@launch
            }

            Log.d(TAG, "📸 Captura manual iniciada sobre: ${root.packageName}")
            actualizarSnapDePantalla(force = true)
            // Esperar a que se complete el escaneo
            delay(500)

            // Asegurar que el snapshot se guarde en ScreenMemory
            if (lastSnapshot != null) {
                com.example.myapplication.core.ScreenMemory.lastSnapshot = lastSnapshot
                com.example.myapplication.core.ScreenMemory.lastSeenTexts = lastSnapshot?.toContextList() ?: emptyList()
                com.example.myapplication.core.ScreenMemory.lastUpdateTimestamp = System.currentTimeMillis()
            }
            Log.d(TAG, "✅ Captura completada: ${lastSnapshot?.elements?.size ?: 0} elementos")
            callback?.invoke(lastSnapshot)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val eventosClave = listOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        if (eventType in eventosClave) {
            snapshotJob?.cancel()
            snapshotJob = serviceScope.launch {
                delay(1000L)
                if (rootInActiveWindow != null) {
                    actualizarSnapDePantalla()
                    com.example.myapplication.core.ScreenMemory.lastSnapshot = lastSnapshot
                    com.example.myapplication.core.ScreenMemory.lastSeenTexts = lastSnapshot?.toContextList() ?: emptyList()
                    Log.d(TAG, "📸 Snapshot actualizado")
                    handler.post { diagnosticarScreenMemory() }
                } else {
                    Log.w(TAG, "⚠️ rootInActiveWindow es NULL")
                }
            }
        }
    }

    private fun diagnosticarScreenMemory() {
        val memoria = com.example.myapplication.core.ScreenMemory.lastSnapshot

        Log.d("DIAGNOSIS", "════════════════════════════════════════")
        Log.d("DIAGNOSIS", "🔍 ESTADO DE SCREENMEMORY")
        Log.d("DIAGNOSIS", "════════════════════════════════════════")

        if (memoria == null) {
            Log.e("DIAGNOSIS", "❌ ScreenMemory.lastSnapshot es NULL")
        } else {
            Log.d("DIAGNOSIS", "✅ ScreenMemory.lastSnapshot existe")
            Log.d("DIAGNOSIS", "   📱 App: ${memoria.packageName}")
            Log.d("DIAGNOSIS", "   🔢 Elementos totales: ${memoria.totalElements}")
            Log.d("DIAGNOSIS", "   🖱️  Clickables: ${memoria.clickableElements}")
        }

        Log.d("DIAGNOSIS", "════════════════════════════════════════")
    }

    override fun onInterrupt() {}


    private fun ejecutarAcciones(json: String) {
        Log.d(TAG, "╔═══════════════════════════════════════════════════════════")
        Log.d(TAG, "║ 🔄 EJECUTAR_ACCIONES - Inicio")
        Log.d(TAG, "╠═══════════════════════════════════════════════════════════")
        Log.d(TAG, "║ JSON recibido: ${json.take(300)}...")

        handler.removeCallbacks(stepRunnable)
        val listType = object : TypeToken<List<ActionDto>>() {}.type

        try {
            currentActions = Gson().fromJson(json, listType)
            Log.d(TAG, "║ ✅ JSON deserializado exitosamente")
            Log.d(TAG, "║ Acciones parseadas: ${currentActions?.size}")

            if (currentActions != null) {
                currentActions!!.forEachIndexed { idx, action ->
                    Log.d(TAG, "║   [$idx] tipo='${action.tipo}' params=${action.params}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "║ ❌ Error deserializando JSON: ${e.message}", e)
            Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
            return
        }

        currentIndex = 0
        retryCount = 0

        if (currentActions.isNullOrEmpty()) {
            Log.w(TAG, "║ ⚠️ currentActions es null o vacío — actualizando snapshot")
            Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
            actualizarSnapDePantalla()
        } else {
            Log.d(TAG, "║ ▶️ Iniciando ejecución secuencial...")
            Log.d(TAG, "╚═══════════════════════════════════════════════════════════")
            handler.post(stepRunnable)
        }
    }

    private var currentActions: List<ActionDto>? = null
    private var currentIndex = 0
    private var retryCount = 0

    private val stepRunnable = object : Runnable {
        override fun run() {
            val actions = currentActions ?: return
            if (currentIndex >= actions.size) {
                Log.d(TAG, "✅ Todas las acciones completadas (${currentIndex}/${actions.size})")
                currentActions = null
                return
            }
            val accion = actions[currentIndex++]
            var waitTime = 1000L
            var exitoAccion = true
            var detalleError: String? = null

            Log.d(TAG, "┌─────────────────────────────────────────────────")
            Log.d(TAG, "│ ⏳ Acción [${currentIndex}/${actions.size}]: ${accion.tipo}")
            Log.d(TAG, "│  Params: ${accion.params}")

            when (accion.tipo) {


                "open_app" -> {
                    val pkg = accion.params?.get("package") as? String ?: ""
                    Log.d(TAG, "│ 📱 open_app")
                    Log.d(TAG, "│    package='$pkg'")

                    if (pkg.isBlank()) {
                        Log.e(TAG, "│ ❌ Package está vacío!")
                        exitoAccion = false
                        detalleError = "Package vacío"
                    } else {
                        try {
                            Log.d(TAG, "│    → Llamando ActionExecutor.openApp()...")
                            ActionExecutor.openApp(this@MyAccessibilityService, pkg)
                            Log.d(TAG, "│    ✅ ActionExecutor.openApp() devolvió")
                            waitTime = 2500L
                        } catch (e: Exception) {
                            Log.e(TAG, "│ ❌ Exception en ActionExecutor.openApp(): ${e.message}", e)
                            exitoAccion = false
                            detalleError = "Exception: ${e.message}"
                        }
                    }

                    handler.postDelayed({
                        Log.d(TAG, "│ [POST-DELAY] Actualizando snapshot...")
                        lastFingerprint = ""
                        actualizarSnapDePantalla()
                    }, 2000L)
                }
                "set_alarm" -> {
                    val hour = (accion.params?.get("hour") as? Number)?.toInt() ?: 7
                    val minute = (accion.params?.get("minute") as? Number)?.toInt() ?: 0
                    val label = accion.params?.get("label") as? String ?: ""

                    Log.d(TAG, "│ ⏰ set_alarm: $hour:$minute '$label'")

                    ActionExecutor.setAlarm(this@MyAccessibilityService, hour, minute, label)
                    waitTime = 2000L
                }
                "android_intent" -> {
                    val intentAction = accion.action ?: accion.params?.get("action") as? String ?: "android.intent.action.VIEW"
                    val androidIntent = Intent(intentAction).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val dataUri = accion.data ?: accion.params?.get("data") as? String
                        if (!dataUri.isNullOrBlank()) data = Uri.parse(dataUri)
                        val pkg = accion.pkg ?: accion.params?.get("package") as? String
                        if (!pkg.isNullOrBlank()) setPackage(pkg)
                        val comp = accion.component ?: accion.params?.get("component") as? String
                        if (!comp.isNullOrBlank()) {
                            val parts = comp.split("/")
                            if (parts.size == 2) setClassName(parts[0], parts[1])
                        }
                        val mime = accion.mimeType ?: accion.params?.get("mime_type") as? String
                        if (!mime.isNullOrBlank()) type = mime
                        val extras = accion.extras ?: @Suppress("UNCHECKED_CAST") (accion.params?.get("extras") as? Map<String, Any>)
                        extras?.forEach { (key, value) ->
                            when (value) {
                                is String  -> putExtra(key, value)
                                is Int     -> putExtra(key, value)
                                is Boolean -> putExtra(key, value)
                                is Double  -> putExtra(key, value.toString())
                                else       -> putExtra(key, value.toString())
                            }
                        }
                    }
                    try {
                        Log.d(TAG, "│ 📱 android_intent: $intentAction")
                        this@MyAccessibilityService.startActivity(androidIntent)
                        Log.d(TAG, "│ ✅ Intent lanzado")
                        waitTime = 300L
                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Intent falló: ${e.message}")
                        val pkg = accion.pkg ?: accion.params?.get("package") as? String
                        if (!pkg.isNullOrBlank()) {
                            Log.d(TAG, "│    Intentando fallback: openApp($pkg)")
                            ActionExecutor.openApp(this@MyAccessibilityService, pkg)
                            waitTime = 500L
                        } else {
                            exitoAccion = false
                            detalleError = "Intent falló: ${e.message}"
                        }
                    }
                }

                "adjust_volume" -> {
                    val direction = accion.params?.get("direction") as? String ?: "up"
                    val percent = when (val raw = accion.params?.get("percent")) {
                        is Number -> raw.toInt().coerceIn(0, 100)
                        is String -> raw.toIntOrNull()?.coerceIn(0, 100)
                        else -> null
                    }
                    val steps = when (val raw = accion.params?.get("steps")) {
                        is Number -> raw.toInt().coerceIn(1, 15)
                        is String -> raw.toIntOrNull()?.coerceIn(1, 15) ?: 1
                        else -> 1
                    }
                    val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    val streamType = android.media.AudioManager.STREAM_MUSIC

                    if (percent != null) {
                        val maxVol = audioManager.getStreamMaxVolume(streamType)
                        val target = (maxVol * percent / 100.0).toInt()
                        audioManager.setStreamVolume(streamType, target, android.media.AudioManager.FLAG_SHOW_UI)
                        Log.d(TAG, "│ 🔊 adjust_volume: percent=$percent% → nivel=$target/$maxVol")
                    } else {
                        val adjustFlag = if (direction == "up") android.media.AudioManager.ADJUST_RAISE
                        else android.media.AudioManager.ADJUST_LOWER
                        repeat(steps) { i ->
                            audioManager.adjustStreamVolume(streamType, adjustFlag,
                                if (i == 0) android.media.AudioManager.FLAG_SHOW_UI else 0)
                        }
                        Log.d(TAG, "│ 🔊 adjust_volume: direction=$direction steps=$steps")
                    }
                    waitTime = 200L
                }

                "toggle_bluetooth" -> {
                    val shouldEnable = accion.params?.get("state") as? String ?: "toggle"
                    Log.d(TAG, "│ 🔵 toggle_bluetooth: state='$shouldEnable'")

                    try {
                        // ✅ FIX: Usar Context explícitamente
                        // Verificación de permiso para evitar SecurityException
                        if (ContextCompat.checkSelfPermission(this@MyAccessibilityService, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Log.e(TAG, "❌ Falta permiso BLUETOOTH_CONNECT")
                            return@run
                        }
                        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()

                        if (bluetoothAdapter != null) {
                            val isEnabled = bluetoothAdapter.isEnabled
                            val respuesta: String

                            when (shouldEnable) {
                                "on" -> {
                                    if (!isEnabled) {
                                        bluetoothAdapter.enable()
                                        Log.d(TAG, "│ ✅ Bluetooth ENCENDIDO")
                                        respuesta = "Bluetooth encendido."
                                    } else {
                                        Log.d(TAG, "│ ⚠️ Bluetooth ya está encendido")
                                        respuesta = "Bluetooth ya estaba encendido."
                                    }
                                }
                                "off" -> {
                                    if (isEnabled) {
                                        bluetoothAdapter.disable()
                                        Log.d(TAG, "│ ✅ Bluetooth APAGADO")
                                        respuesta = "Bluetooth apagado."
                                    } else {
                                        Log.d(TAG, "│ ⚠️ Bluetooth ya está apagado")
                                        respuesta = "Bluetooth ya estaba apagado."
                                    }
                                }
                                "toggle" -> {
                                    if (isEnabled) {
                                        bluetoothAdapter.disable()
                                        Log.d(TAG, "│ ✅ Bluetooth APAGADO (toggle)")
                                        respuesta = "Bluetooth apagado."
                                    } else {
                                        bluetoothAdapter.enable()
                                        Log.d(TAG, "│ ✅ Bluetooth ENCENDIDO (toggle)")
                                        respuesta = "Bluetooth encendido."
                                    }
                                }
                                else -> {
                                    respuesta = "Estado Bluetooth desconocido."
                                }
                            }

                            // Notificar al usuario por voz
                            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", respuesta)
                                setPackage(packageName)
                            }


                            exitoAccion = true
                            waitTime = 500L

                        } else {
                            Log.e(TAG, "│ ❌ BluetoothAdapter no disponible")
                            exitoAccion = false
                            detalleError = "Bluetooth no disponible en este dispositivo"
                            waitTime = 300L
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error toggling Bluetooth: ${e.message}", e)
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "toggle_wifi" -> {
                    val shouldEnable = accion.params?.get("state") as? String ?: "toggle"
                    Log.d(TAG, "│ 📶 toggle_wifi: state='$shouldEnable'")

                    try {
                        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

                        val isEnabled = wifiManager.isWifiEnabled
                        val respuesta: String

                        when (shouldEnable) {
                            "on" -> {
                                if (!isEnabled) {
                                    @Suppress("DEPRECATION")
                                    wifiManager.isWifiEnabled = true
                                    Log.d(TAG, "│ ✅ WiFi ENCENDIDO")
                                    respuesta = "WiFi encendido."
                                } else {
                                    Log.d(TAG, "│ ⚠️ WiFi ya está encendido")
                                    respuesta = "WiFi ya estaba encendido."
                                }
                            }
                            "off" -> {
                                if (isEnabled) {
                                    @Suppress("DEPRECATION")
                                    wifiManager.isWifiEnabled = false
                                    Log.d(TAG, "│ ✅ WiFi APAGADO")
                                    respuesta = "WiFi apagado."
                                } else {
                                    Log.d(TAG, "│ ⚠️ WiFi ya está apagado")
                                    respuesta = "WiFi ya estaba apagado."
                                }
                            }
                            "toggle" -> {
                                @Suppress("DEPRECATION")
                                if (isEnabled) {
                                    wifiManager.isWifiEnabled = false
                                    Log.d(TAG, "│ ✅ WiFi APAGADO (toggle)")
                                    respuesta = "WiFi apagado."
                                } else {
                                    wifiManager.isWifiEnabled = true
                                    Log.d(TAG, "│ ✅ WiFi ENCENDIDO (toggle)")
                                    respuesta = "WiFi encendido."
                                }
                            }
                            else -> {
                                respuesta = "Estado WiFi desconocido."
                            }
                        }

                        val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", respuesta)
                            setPackage(packageName)
                        }
                        sendBroadcast(intent)

                        exitoAccion = true
                        waitTime = 500L

                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error toggling WiFi: ${e.message}", e)
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "toggle_flashlight" -> {
                    val shouldEnable = accion.params?.get("state") as? String ?: "on"
                    Log.d(TAG, "│ 💡 toggle_flashlight: state='$shouldEnable'")

                    try {
                        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val cameraId = cameraManager.cameraIdList[0]

                        val respuesta: String

                        when (shouldEnable) {
                            "on" -> {
                                cameraManager.setTorchMode(cameraId, true)
                                Log.d(TAG, "│ ✅ Linterna ENCENDIDA")
                                respuesta = "Linterna encendida."
                            }
                            "off" -> {
                                cameraManager.setTorchMode(cameraId, false)
                                Log.d(TAG, "│ ✅ Linterna APAGADA")
                                respuesta = "Linterna apagada."
                            }
                            else -> {
                                respuesta = "Estado linterna desconocido."
                            }
                        }

                        val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", respuesta)
                            setPackage(packageName)
                        }
                        sendBroadcast(intent)

                        exitoAccion = true
                        waitTime = 500L

                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error toggling flashlight: ${e.message}", e)
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "open_camera" -> {
                    val modo = accion.params?.get("mode") as? String ?: "photo"
                    val frontal = (accion.params?.get("frontal") as? Boolean) ?: false
                    Log.d(TAG, "│ 📷 open_camera: modo='$modo' frontal=$frontal")

                    try {
                        // ✅ NUEVO: Verificar permiso ANTES de abrir cámara
                        if (!PermissionHelper.hasCameraPermission(this@MyAccessibilityService)) {
                            Log.e(TAG, "│ ❌ Permiso de cámara denegado")
                            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Necesito permiso de cámara. Ve a Ajustes > Aplicaciones > Permisos > Otorga cámara")
                                setPackage(packageName)
                            }
                            sendBroadcast(intent)
                            exitoAccion = false
                            detalleError = "Permiso de cámara denegado"
                            waitTime = 300L
                            return@run
                        }

                        val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            when (modo) {
                                "portrait" -> {
                                    putExtra("portrait_mode", true)
                                    Log.d(TAG, "│    Intentando modo retrato")
                                }
                                "frontal" -> {
                                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                                    Log.d(TAG, "│    Intentando cámara frontal")
                                }
                            }
                        }
                        startActivity(intent)
                        Log.d(TAG, "│ ✅ Cámara abierta (foto)")
                        exitoAccion = true
                        waitTime = 2500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error abriendo cámara: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "take_photo_auto" -> {
                    val frontal = accion.params?.get("frontal") as? Boolean ?: false
                    val portrait = accion.params?.get("portrait") as? Boolean ?: false

                    Log.d(TAG, "│ 📸 take_photo_auto: frontal=$frontal, portrait=$portrait")

                    try {
                        // Verificar permiso de cámara
                        if (ContextCompat.checkSelfPermission(this@MyAccessibilityService, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {

                            Log.e(TAG, "│ ❌ Permiso de cámara no concedido")
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            exitoAccion = false
                            detalleError = "Permiso de cámara denegado"
                            waitTime = 300L
                            return@run
                        }

                        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (frontal) {
                                putExtra("android.intent.extras.CAMERA_FACING", 1) // 1 = frontal
                            }
                            if (portrait) {
                                putExtra("portrait_mode", true)
                            }
                        }

                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                            Log.d(TAG, "│ ✅ Tomando foto" + if (frontal) " (selfie)" else "")
                            exitoAccion = true
                            waitTime = 2500L
                        } else {
                            Log.e(TAG, "│ ❌ No hay app de cámara")
                            exitoAccion = false
                            detalleError = "No hay app de cámara"
                            waitTime = 300L
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "take_photo" -> {
                    val frontal = accion.params?.get("frontal") as? Boolean ?: false
                    val portrait = accion.params?.get("portrait") as? Boolean ?: false

                    Log.d(TAG, "│ 📸 take_photo: frontal=$frontal, portrait=$portrait")

                    try {
                        // Verificar permiso de cámara
                        if (ContextCompat.checkSelfPermission(this@MyAccessibilityService, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {

                            Log.e(TAG, "│ ❌ Permiso de cámara no concedido - solicitando...")

                            // Notificar al usuario
                            val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Necesito permiso de cámara. Ve a ajustes y actívalo.")
                                setPackage(packageName)
                            }
                            sendBroadcast(speakIntent)

                            // Abrir ajustes de la app
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)

                            exitoAccion = false
                            detalleError = "Permiso de cámara denegado"
                            waitTime = 300L
                            return@run
                        }

                        // Crear intent para tomar foto
                        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                            if (frontal) {
                                // Intentar cámara frontal
                                putExtra("android.intent.extras.CAMERA_FACING", 1) // 1 = frontal
                            }
                            if (portrait) {
                                putExtra("portrait_mode", true)
                            }
                        }

                        // Verificar que hay una app de cámara disponible
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                            Log.d(TAG, "│ ✅ Intent de cámara enviado - tomando foto")

                            // Notificar al usuario
                            val speakIntent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", if (frontal) "Tomando selfie" else "Tomando foto")
                                setPackage(packageName)
                            }
                            sendBroadcast(speakIntent)

                            exitoAccion = true
                            waitTime = 3000L
                        } else {
                            Log.e(TAG, "│ ❌ No hay app de cámara disponible")
                            exitoAccion = false
                            detalleError = "No hay app de cámara"
                            waitTime = 300L
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error tomando foto: ${e.message}", e)
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "call" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    Log.d(TAG, "│ ☎️ call: '$contact'")
                    ActionExecutor.callContact(this@MyAccessibilityService, contact)
                    waitTime = 3000L
                }

                "set_alarm" -> {
                    val hour   = (accion.params?.get("hour") as? Number)?.toInt() ?: 7
                    val minute = (accion.params?.get("minute") as? Number)?.toInt() ?: 0
                    val label  = accion.params?.get("label") as? String ?: "Alarma"
                    Log.d(TAG, "│ ⏰ set_alarm: $hour:$minute '$label'")
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    waitTime = 1500L
                }

                "play_music" -> {
                    val query = accion.params?.get("query") as? String ?: ""
                    val packageName = accion.params?.get("package") as? String ?: "com.spotify.music"

                    Log.d(TAG, "│ 🎵 play_music: query='$query' pkg='$packageName'")

                    if (query.isNotBlank()) {
                        ActionExecutor.playMusic(this@MyAccessibilityService, query, packageName)
                    } else {
                        Log.w(TAG, "│ ⚠️ Query vacía, abriendo app por defecto")
                        ActionExecutor.openApp(this@MyAccessibilityService, packageName)
                    }
                    waitTime = 3000L  // Esperar más tiempo para que la app cargue y reproduzca
                }

                "play_video" -> {
                    val query = accion.params?.get("query") as? String ?: ""

                    Log.d(TAG, "│ 🎬 play_video: query='$query'")

                    if (query.isNotBlank()) {
                        ActionExecutor.playVideo(this@MyAccessibilityService, query)
                    } else {
                        ActionExecutor.openApp(this@MyAccessibilityService, "com.google.android.youtube")
                    }
                    waitTime = 3000L
                }
                "navigate_to" -> {
                    val destination = accion.params?.get("destination") as? String ?: ""
                    Log.d(TAG, "│ 🗺️ navigate_to: '$destination'")
                    val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { startActivity(intent) } catch (e: Exception) {
                        val fallback = Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://maps.google.com/?q=${Uri.encode(destination)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(fallback)
                    }
                    waitTime = 2000L
                }

                "search_web" -> {
                    val query = accion.params?.get("query") as? String ?: ""
                    Log.d(TAG, "│ 🔍 search_web: '$query'")
                    val intent = Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    waitTime = 1500L
                }

                "toggle_setting" -> {
                    val setting = accion.params?.get("setting") as? String ?: ""
                    val state   = accion.params?.get("state") as? String ?: "on"
                    Log.d(TAG, "│ ⚙️ toggle_setting: $setting → $state")
                    when (setting) {
                        "flashlight" -> {
                            val cam = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                            cam.setTorchMode(cam.cameraIdList[0], state == "on")
                        }
                        "wifi"      -> ejecutarAcciones(Gson().toJson(listOf(ActionDto("toggle_wifi", mapOf("state" to state)))))
                        "bluetooth" -> ejecutarAcciones(Gson().toJson(listOf(ActionDto("toggle_bluetooth", mapOf("state" to state)))))
                        "dnd" -> {
                            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        }
                    }
                    waitTime = 500L
                }
                "record_video" -> {
                    val modo = accion.params?.get("mode") as? String ?: "normal"  // normal, portrait, frontal
                    val duracion = (accion.params?.get("duration") as? Number)?.toInt() ?: 0  // 0 = sin límite

                    Log.d(TAG, "│ 🎥 record_video: modo='$modo' duracion=${duracion}s")

                    try {
                        val intent = Intent("android.media.action.VIDEO_CAPTURE").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            when (modo) {
                                "portrait" -> {
                                    putExtra("portrait_mode", true)
                                    Log.d(TAG, "│    Modo retrato activado")
                                }
                                "frontal" -> {
                                    putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                                    Log.d(TAG, "│    Cámara frontal activada")
                                }
                            }
                            if (duracion > 0) {
                                putExtra("android.intent.extra.DURATION_LIMIT", duracion)
                                Log.d(TAG, "│    Límite de duración: ${duracion}s")
                            }
                        }
                        startActivity(intent)
                        Log.d(TAG, "│ ✅ Intent de video enviado")

                        val intent_speak = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", "Abriendo cámara para grabar video")
                            setPackage(packageName)
                        }
                        sendBroadcast(intent_speak)

                        exitoAccion = true
                        waitTime = 2500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error grabando video: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "switch_camera" -> {
                    val targetCamera = accion.params?.get("target") as? String ?: "toggle"  // frontal, trasera, toggle
                    Log.d(TAG, "│ 🔄 switch_camera: target='$targetCamera'")

                    try {
                        // Este es un poco más complejo porque necesita acceso directo a la cámara
                        // Para apps que soporten esto, enviamos el intent adecuado

                        val intent = when (targetCamera) {
                            "frontal" -> Intent("android.media.action.IMAGE_CAPTURE").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            }
                            "trasera" -> Intent("android.media.action.IMAGE_CAPTURE").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
                            }
                            else -> Intent("android.media.action.IMAGE_CAPTURE").apply {  // toggle
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("android.intent.extra.CAMERA_FACING", -1)  // -1 = toggle
                            }
                        }

                        startActivity(intent)
                        Log.d(TAG, "│ ✅ Cámara cambiada a: $targetCamera")
                        exitoAccion = true
                        waitTime = 1500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error cambiando cámara: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "take_selfie" -> {
                    Log.d(TAG, "│ 🤳 take_selfie: activando cámara frontal")

                    try {
                        val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            // Algunos dispositivos también reconocen esto:
                            putExtra("android.intent.extras.CAMERA_FACING", 1)  // 1 = frontal, 0 = trasera
                        }
                        startActivity(intent)
                        Log.d(TAG, "│ ✅ Cámara frontal activada para selfie")

                        val intent_speak = Intent("JARVIS.SPEAK_TEXT").apply {
                            putExtra("texto", "Abriendo cámara frontal para selfie")
                            setPackage(packageName)
                        }
                        sendBroadcast(intent_speak)

                        exitoAccion = true
                        waitTime = 2500L
                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ Error en selfie: ${e.message}")
                        exitoAccion = false
                        detalleError = "Error: ${e.message}"
                        waitTime = 300L
                    }
                }
                "adjust_brightness" -> {
                    val direction = accion.params?.get("direction") as? String ?: "up"
                    val percent = when (val raw = accion.params?.get("percent")) {
                        is Number -> raw.toInt().coerceIn(0, 100)
                        is String -> raw.toIntOrNull()?.coerceIn(0, 100)
                        else -> null
                    }
                    val steps = when (val raw = accion.params?.get("steps")) {
                        is Number -> raw.toInt().coerceIn(1, 10)
                        is String -> raw.toIntOrNull()?.coerceIn(1, 10) ?: 1
                        else -> 1
                    }

                    try {
                        // ✅ MÉTODO 1: Cambiar brillo del sistema (requiere WRITE_SETTINGS)
                        val puedeEscribir = android.provider.Settings.System.canWrite(this@MyAccessibilityService)

                        if (puedeEscribir) {
                            // Desactivar brillo automático primero
                            android.provider.Settings.System.putInt(
                                contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                            )

                            val currentBrightness = android.provider.Settings.System.getInt(
                                contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                128
                            )

                            val newBrightness = if (percent != null) {
                                // Convertir porcentaje a escala 0-255
                                (255 * percent / 100.0).toInt().coerceIn(15, 255) // mínimo 15 para no apagar
                            } else {
                                val delta = steps * 30 // cada paso ≈ 12% de 255
                                if (direction == "up") (currentBrightness + delta).coerceIn(15, 255)
                                else (currentBrightness - delta).coerceIn(15, 255)
                            }

                            android.provider.Settings.System.putInt(
                                contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                newBrightness
                            )

                            Log.d(TAG, "│ ☀️ adjust_brightness (SYSTEM): ${if (percent != null) "$percent%" else "$direction x$steps"} → $newBrightness/255")

                            // Hablar la confirmación
                            val pct = if (percent != null) percent else {
                                (newBrightness * 100 / 255)
                            }
                            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Brillo al $pct por ciento")
                                setPackage(packageName)
                            }
                            sendBroadcast(intent)

                        } else {
                            // ✅ MÉTODO 2: Sin permiso → abrir configuración de brillo
                            // Y también intentar cambio mediante Quick Settings tile
                            Log.w(TAG, "│ ⚠️ Sin permiso WRITE_SETTINGS — solicitando permiso")

                            // Pedir permiso al usuario
                            val settingsIntent = Intent(
                                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:$packageName")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(settingsIntent)

                            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                                putExtra("texto", "Necesito permiso para ajustar el brillo. Activa el permiso que aparece en pantalla.")
                                setPackage(packageName)
                            }
                            sendBroadcast(intent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "│ ❌ adjust_brightness error: ${e.message}")
                        exitoAccion = false
                    }
                    waitTime = 300L
                }
                "query_time" -> {
                    val cal = java.util.Calendar.getInstance()
                    val hora = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val minuto = String.format("%02d", cal.get(java.util.Calendar.MINUTE))
                    val periodo = if (hora < 12) "de la mañana" else if (hora < 18) "de la tarde" else "de la noche"
                    val hora12 = if (hora == 0) 12 else if (hora > 12) hora - 12 else hora
                    val textoHora = "Son las $hora12:$minuto $periodo"
                    Log.d(TAG, "│ 🕐 query_time: $textoHora")

                    // Busca el controlador de voz para hablar
                    val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                        putExtra("texto", textoHora)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                    waitTime = 300L
                }

                "query_date" -> {
                    val cal = java.util.Calendar.getInstance()
                    val dias = arrayOf("domingo", "lunes", "martes", "miércoles",
                        "jueves", "viernes", "sábado")
                    val meses = arrayOf("enero", "febrero", "marzo", "abril", "mayo", "junio",
                        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
                    val diaSemana = dias[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
                    val diaMes    = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    val mes       = meses[cal.get(java.util.Calendar.MONTH)]
                    val anio      = cal.get(java.util.Calendar.YEAR)
                    val textoFecha = "Hoy es $diaSemana $diaMes de $mes de $anio"
                    Log.d(TAG, "│ 📅 query_date: $textoFecha")

                    val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                        putExtra("texto", textoFecha)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                    waitTime = 300L
                }
                "write_and_confirm" -> {
                    val mensaje = accion.params?.get("message") as? String ?: ""
                    Log.d(TAG, "│ ✏️ write_and_confirm: '$mensaje'")
                    if (mensaje.isBlank()) {
                        Log.w(TAG, "│ ⚠️ Mensaje vacío")
                        exitoAccion = false
                    } else {
                        escribirEnChatActual(mensaje)
                        currentActions = null
                        return
                    }
                }

                "send_whatsapp" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    val message = accion.params?.get("message") as? String ?: ""
                    Log.d(TAG, "│ 💬 send_whatsapp → '$contact': '$message'")
                    ActionExecutor.sendWhatsAppMessage(this@MyAccessibilityService, contact, message)
                    waitTime = 4000L
                }

                "send_sms" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    val message = accion.params?.get("message") as? String ?: ""
                    Log.d(TAG, "│ 📱 send_sms → '$contact': '$message'")
                    ActionExecutor.sendSms(this@MyAccessibilityService, contact, message)
                    waitTime = 2000L
                }

                "call_contact" -> {
                    val contact = accion.params?.get("contact") as? String ?: ""
                    Log.d(TAG, "│ ☎️ call_contact: '$contact'")
                    ActionExecutor.callContact(this@MyAccessibilityService, contact)
                    waitTime = 3000L
                }

                "global_action" -> {
                    Log.d(TAG, "│ 🌍 global_action: ${accion.params}")
                    ejecutarAccionGlobal(accion.params)
                    waitTime = 800L
                }

                "scroll" -> {
                    val direction = accion.params?.get("direction") as? String ?: "down"
                    Log.d(TAG, "│ 📜 scroll: $direction")
                    scroll(direction)
                    waitTime = 1200L
                }

                "swipe" -> {
                    val direction = accion.params?.get("direction") as? String ?: "left"
                    Log.d(TAG, "│ 👉 swipe: $direction")
                    scroll(direction)
                    waitTime = 800L
                }

                "tap" -> {
                    Log.d(TAG, "│ 🖱️ tap: ${accion.params}")
                    tapCoordenadas(accion.params)
                    waitTime = 1000L
                }

                "ocr_tap" -> {
                    val textoABuscar = accion.params?.get("texto") as? String ?: ""
                    Log.d(TAG, "│ 🔍 ocr_tap: '$textoABuscar'")

                    val esBotoEnviar = textoABuscar.lowercase() in setOf(
                        "enviar", "send", "enviar mensaje", "enviar ahora", "submit"
                    )
                    if (esBotoEnviar && ultimoTextoEscrito.isNotBlank()) {
                        Log.d(TAG, "│    Detectado botón Enviar con texto pendiente")
                        pausarParaConfirmacion(textoABuscar)
                        return
                    }

                    val snapshotAge = System.currentTimeMillis() - (lastSnapshot?.timestamp ?: 0)
                    if (snapshotAge > 3000L || lastSnapshot == null) {
                        lastFingerprint = ""
                        actualizarSnapDePantalla()
                    }
                    val elemento = buscarElementoPorTexto(textoABuscar)
                    if (elemento != null) {
                        Log.d(TAG, "│    ✅ Elemento encontrado en (${elemento.centerX}, ${elemento.centerY})")
                        tapCoordenadas(mapOf("x" to elemento.centerX, "y" to elemento.centerY))
                    } else {
                        Log.w(TAG, "│ ❌ Elemento '$textoABuscar' NO encontrado")
                        exitoAccion = false
                        detalleError = "Elemento '$textoABuscar' no visible"
                    }
                    waitTime = 1000L
                }

                "type_text" -> {
                    val texto = accion.params?.get("texto") as? String
                        ?: accion.params?.get("text") as? String  // fallback por si Gemini usa "text"
                        ?: ""
                    ultimoTextoEscrito = texto
                    Log.d(TAG, "│ ⌨️ type_text: '$texto'")

                    if (texto.isBlank()) {
                        Log.w(TAG, "│ ⚠️ Texto vacío, ignorando")
                        exitoAccion = false
                    } else {
                        val root = rootInActiveWindow
                        if (root == null) {
                            Log.e(TAG, "│ ❌ Sin ventana activa")
                            exitoAccion = false
                        } else {
                            // Intento 1: campo con foco actual
                            val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            if (focusNode != null && focusNode.isEditable) {
                                val args = Bundle()
                                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
                                val ok = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                Log.d(TAG, "│ ${if (ok) "✅" else "❌"} type_text con foco: '$texto'")
                                exitoAccion = ok
                            } else {
                                // Intento 2: primer campo editable en árbol
                                val editable = encontrarCampoEditable(root)
                                if (editable != null) {
                                    // Dar foco tocando el campo primero
                                    val bounds = Rect()
                                    editable.getBoundsInScreen(bounds)
                                    if (!bounds.isEmpty) {
                                        tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
                                    }
                                    handler.postDelayed({
                                        val rootRetry = rootInActiveWindow ?: return@postDelayed
                                        val focusRetry = rootRetry.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                            ?: encontrarCampoEditable(rootRetry)
                                        if (focusRetry != null) {
                                            val args = Bundle()
                                            args.putCharSequence(
                                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto
                                            )
                                            val ok = focusRetry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                            Log.d(TAG, "│ ${if (ok) "✅" else "❌"} type_text retry: '$texto'")
                                        } else {
                                            Log.e(TAG, "│ ❌ No se encontró campo editable tras tap")
                                        }
                                    }, 600L)
                                } else {
                                    Log.w(TAG, "│ ⚠️ No hay campo editable en pantalla")
                                    exitoAccion = false
                                }
                            }
                        }
                    }
                    waitTime = 900L
                }

                "tap_send_button" -> {
                    Log.d(TAG, "│ 📤 tap_send_button")
                    val candidatos = accion.params?.get("candidatos")
                        ?.toString()
                        ?.split(",")
                        ?: listOf("Enviar", "Send", "send", "Enviar mensaje")

                    val root = rootInActiveWindow
                    if (root == null) {
                        Log.w(TAG, "│ ❌ Sin ventana activa")
                        exitoAccion = false
                    } else {
                        var enviado = false
                        for (candidato in candidatos) {
                            val nodo = encontrarNodoPorTexto(root, candidato.trim())
                            if (nodo != null) {
                                var nodoClick: AccessibilityNodeInfo? = nodo
                                while (nodoClick != null && !nodoClick.isClickable) {
                                    nodoClick = nodoClick.parent
                                }
                                if (nodoClick != null && nodoClick.isClickable) {
                                    nodoClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    Log.d(TAG, "│ ✅ Tap en '$candidato'")
                                    enviado = true
                                    break
                                }
                                val bounds = Rect()
                                nodo.getBoundsInScreen(bounds)
                                if (!bounds.isEmpty) {
                                    tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
                                    Log.d(TAG, "│ ✅ Tap coords para '$candidato'")
                                    enviado = true
                                    break
                                }
                            }
                        }
                        if (!enviado) {
                            val elemento = buscarElementoPorTexto("Enviar")
                                ?: buscarElementoPorTexto("Send")
                            if (elemento != null) {
                                tapCoordenadas(mapOf("x" to elemento.centerX, "y" to elemento.centerY))
                                Log.d(TAG, "│ ✅ Tap fallback snapshot")
                            } else {
                                Log.w(TAG, "│ ⚠️ Botón no encontrado")
                                exitoAccion = false
                            }
                        }
                    }
                    ultimoTextoEscrito = ""
                    waitTime = 800L
                }

                "clear_text_field" -> {
                    Log.d(TAG, "│ 🗑️ clear_text_field")
                    val root = rootInActiveWindow
                    if (root == null) {
                        Log.w(TAG, "│ ❌ Sin ventana activa")
                        exitoAccion = false
                    } else {
                        val campo = encontrarCampoEditable(root)
                        if (campo != null) {
                            val args = Bundle()
                            args.putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                            )
                            val ok = campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            Log.d(TAG, "│ ${if (ok) "✅" else "❌"} Campo limpiado")
                            exitoAccion = ok
                        } else {
                            Log.w(TAG, "│ ⚠️ Sin campo editable")
                            exitoAccion = false
                        }
                    }
                    ultimoTextoEscrito = ""
                    waitTime = 400L
                }

                "open_notification" -> {
                    val pkg = accion.params?.get("package") as? String ?: ""
                    Log.d(TAG, "│ 🔔 open_notification: '$pkg'")
                    JarvisNotificationListener.instance?.openNotification(
                        this@MyAccessibilityService, pkg
                    )
                    waitTime = 1200L
                }

                "reply_notification" -> {
                    val pkg   = accion.params?.get("package") as? String ?: ""
                    val texto = accion.params?.get("texto") as? String ?: ""
                    Log.d(TAG, "│ 💬 reply_notification: '$pkg' → '$texto'")
                    if (texto.isNotBlank()) {
                        JarvisNotificationListener.instance?.replyToNotification(pkg, texto)
                    }
                    waitTime = 1000L
                }

                "unlock_screen" -> {
                    Log.d(TAG, "│ 🔓 unlock_screen")
                    performGlobalAction(GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
                    waitTime = 500L
                }

                else -> {
                    Log.w(TAG, "│ ⚠️ Tipo desconocido: ${accion.tipo}")
                    exitoAccion = false
                    detalleError = "Tipo desconocido: ${accion.tipo}"
                }

            }

            reportarAlServidor(textoUltimaOrden, intencionUltimaOrden, actions, exitoAccion, detalleError)
            Log.d(TAG, "└─ ⏱️ Esperando ${waitTime}ms antes de siguiente acción")
            handler.postDelayed(this, waitTime)
        }
    }

    private fun cameraAvailable(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_CAMERA_ANY
        )
    }
    private fun verificarPermisosCamara() {
        val permisosFaltantes = PermissionHelper.getMissingPermissions(this)

        if (permisosFaltantes.isNotEmpty()) {
            Log.w(TAG, "⚠️ Permisos faltantes: ${permisosFaltantes.joinToString(", ")}")
            Log.i(TAG, "📋 Los permisos se solicitarán cuando se use la cámara")

            // Notificar al usuario
            val intent = Intent("JARVIS.SPEAK_TEXT").apply {
                putExtra("texto", "Necesito permiso de cámara. Ve a Ajustes > Aplicaciones > Permisos")
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } else {
            Log.d(TAG, "✅ Todos los permisos de cámara están otorgados")
        }
    }
    private fun frontalCameraAvailable(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_CAMERA_FRONT
        )
    }

    private fun escribirEnChatActual(mensaje: String) {
        val root = rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "❌ escribirEnChatActual: sin ventana activa")
            return
        }

        val campo = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: encontrarCampoEditable(root)

        if (campo != null) {
            val bounds = Rect()
            campo.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                tapCoordenadas(mapOf("x" to bounds.centerX(), "y" to bounds.centerY()))
            }

            handler.postDelayed({
                val rootActual = rootInActiveWindow ?: return@postDelayed
                val campoActual = rootActual.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: encontrarCampoEditable(rootActual)

                if (campoActual != null) {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, mensaje
                    )
                    val ok = campoActual.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Log.d(TAG, "${if (ok) "✅" else "❌"} Escrito en chat: '$mensaje'")

                    if (ok) {
                        ultimoTextoEscrito = mensaje
                        handler.postDelayed({
                            pausarParaConfirmacion("Enviar")
                        }, 600L)
                    }
                }
            }, 500L)

        }
    }

    private fun pausarParaConfirmacion(textoBoton: String) {
        val mensaje  = ultimoTextoEscrito
        val pregunta = "¿Enviar el mensaje?"
        Log.d(TAG, "⏸️ Pausa para confirmación: '$mensaje'")

        ActionExecutor.onConfirmacionPendiente = { confirmado ->
            if (confirmado) {
                Log.d(TAG, "✅ Confirmado → tap Enviar")
                // ✅ FIX: refrescar snapshot antes de buscar el botón
                lastFingerprint = ""
                actualizarSnapDePantalla()
                handler.postDelayed({
                    val elemento = buscarElementoPorTexto(textoBoton)
                        ?: buscarElementoPorTexto("Send")
                    if (elemento != null) {
                        tapCoordenadas(mapOf("x" to elemento.centerX, "y" to elemento.centerY))
                        Log.d(TAG, "✅ Tap en botón Enviar")
                    } else {
                        Log.w(TAG, "⚠️ Botón '$textoBoton' no encontrado en snapshot")
                    }
                    ultimoTextoEscrito = ""
                    handler.postDelayed(stepRunnable, 800L)
                }, 500L)
            } else {
                Log.d(TAG, "❌ Cancelado → borrando texto")
                val root = rootInActiveWindow
                if (root != null) {
                    val campo = encontrarCampoEditable(root)
                    if (campo != null) {
                        val args = Bundle()
                        args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                        )
                        campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                }
                ultimoTextoEscrito = ""
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 300L)
                currentActions = null
            }
        }

        // ✅ FIX CRÍTICO: delay de 800ms antes de enviar el broadcast
        // Esto da tiempo al SR para terminar de iniciar y evita ERROR_CLIENT
        handler.postDelayed({
            val intent = Intent("JARVIS.PEDIR_CONFIRMACION").apply {
                putExtra("pregunta", pregunta)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            Log.d(TAG, "📡 Broadcast PEDIR_CONFIRMACION enviado")
        }, 800L)
    }

    private fun ejecutarAccionGlobal(params: Map<String, Any>?) {
        val actionType = params?.get("action") as? String ?: return
        when (actionType) {
            "home"          -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back"          -> performGlobalAction(GLOBAL_ACTION_BACK)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "recents"       -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "lock"          -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "screenshot"    -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            else            -> Log.w(TAG, "Acción global desconocida: $actionType")
        }
    }
    fun scroll(direction: String) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (yStart, yEnd) = when (direction) {
            "up"    -> Pair(metrics.heightPixels * 0.25f, metrics.heightPixels * 0.75f)
            "down"  -> Pair(metrics.heightPixels * 0.75f, metrics.heightPixels * 0.25f)
            "left"  -> Pair(metrics.widthPixels  * 0.8f,  metrics.widthPixels  * 0.2f)
            "right" -> Pair(metrics.widthPixels  * 0.2f,  metrics.widthPixels  * 0.8f)
            else    -> Pair(metrics.heightPixels * 0.75f, metrics.heightPixels * 0.25f)
        }
        val isHorizontal = direction == "left" || direction == "right"
        val path = Path().apply {
            if (isHorizontal) {
                moveTo(yStart, metrics.heightPixels / 2f)
                lineTo(yEnd,   metrics.heightPixels / 2f)
            } else {
                moveTo(x, yStart)
                lineTo(x, yEnd)
            }
        }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 400L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        ejecutarGesture(gesture)
    }

    private fun ejecutarGesture(gesture: GestureDescription) {
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { Log.d(TAG, "Gesture OK") }
            override fun onCancelled(g: GestureDescription?) { Log.e(TAG, "Gesture cancelado") }
        }, handler)
    }

    private fun tapCoordenadas(params: Map<String, Any>?) {
        val x = when (val raw = params?.get("x")) {
            is Number -> raw.toFloat()
            else -> { Log.w(TAG, "tapCoordenadas: 'x' inválido"); return }
        }
        val y = when (val raw = params?.get("y")) {
            is Number -> raw.toFloat()
            else -> { Log.w(TAG, "tapCoordenadas: 'y' inválido"); return }
        }
        val metrics = resources.displayMetrics
        if (x < 0 || y < 0 || x > metrics.widthPixels || y > metrics.heightPixels) {
            Log.e(TAG, "tapCoordenadas: fuera de pantalla x=$x y=$y"); return
        }
        val path    = Path().apply { moveTo(x, y) }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        ejecutarGesture(gesture)
    }

    private fun escribirTexto(params: Map<String, Any>?) {
        val texto = params?.get("texto") as? String ?: return
        val root  = rootInActiveWindow ?: return

        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusNode != null) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
            val ok = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "${if (ok) "✅" else "❌"} escribirTexto con foco: '$texto'")
            return
        }

        val editableNode = lastSnapshot?.elements?.firstOrNull { it.isEditable }
        if (editableNode != null) {
            tapCoordenadas(mapOf("x" to editableNode.centerX, "y" to editableNode.centerY))
            handler.postDelayed({
                val rootRetry  = rootInActiveWindow ?: return@postDelayed
                val focusRetry = rootRetry.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusRetry != null) {
                    val args = Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
                    val ok = focusRetry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Log.d(TAG, "  ${if (ok) "✅" else "❌"} escribirTexto retry: '$texto'")
                }
            }, 600L)
        }
    }

    private fun buscarElementoPorTexto(textoBuscado: String): ScreenElement? {
        val snapshot = lastSnapshot ?: run { actualizarSnapDePantalla(); lastSnapshot } ?: return null
        val normalizado = textoBuscado.lowercase().trim()
        snapshot.elements.find { it.text?.lowercase() == normalizado }?.let { return it }
        snapshot.elements.find { it.contentDescription?.lowercase() == normalizado }?.let { return it }
        snapshot.elements.find { (it.text?.lowercase() ?: "").contains(normalizado) }?.let { return it }
        return snapshot.elements
            .filter { it.isClickable || it.isEditable }
            .map { it to calcularSimilitud(normalizado, it.getSearchableText()) }
            .filter { it.second > 0.6 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun encontrarNodoPorTexto(root: AccessibilityNodeInfo, texto: String): AccessibilityNodeInfo? {
        val textoLower = texto.lowercase().trim()

        val byText = root.findAccessibilityNodeInfosByText(texto)
        if (byText.isNotEmpty()) return byText[0]

        fun buscarRecursivo(nodo: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (nodo == null) return null
            val nodeText = (nodo.text?.toString() ?: nodo.contentDescription?.toString() ?: "").lowercase()
            if (nodeText.contains(textoLower) && (nodo.isClickable || nodo.parent?.isClickable == true)) {
                return nodo
            }
            for (i in 0 until nodo.childCount) {
                val resultado = buscarRecursivo(nodo.getChild(i))
                if (resultado != null) return resultado
            }
            return null
        }
        return buscarRecursivo(root)
    }

    private fun encontrarCampoEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusNode != null && focusNode.isEditable) return focusNode

        fun buscarRecursivo(nodo: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (nodo == null) return null
            if (nodo.isEditable) return nodo
            for (i in 0 until nodo.childCount) {
                val resultado = buscarRecursivo(nodo.getChild(i))
                if (resultado != null) return resultado
            }
            return null
        }
        return buscarRecursivo(root)
    }

    private fun calcularSimilitud(s1: String, s2: String): Double {
        val longer  = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0
        return (longer.length - calcularLevenshtein(longer, shorter)) / longer.length.toDouble()
    }

    private fun calcularLevenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun actualizarSnapDePantalla(force: Boolean = false) {
        val root = rootInActiveWindow ?: return

        val elementos = mutableListOf<ScreenElement>()
        val packageName = root.packageName?.toString() ?: "unknown"

        fun escanearNodo(nodo: AccessibilityNodeInfo?, profundidad: Int = 0) {
            if (nodo == null || profundidad > 20) return

            val text = nodo.text?.toString()
            val contentDesc = nodo.contentDescription?.toString()
            val hint = nodo.hintText?.toString()
            val className = nodo.className?.toString()
            val bounds = Rect()
            nodo.getBoundsInScreen(bounds)

            val isClickable = nodo.isClickable
            val isEditable = nodo.isEditable
            val isScrollable = nodo.isScrollable
            val isCheckable = nodo.isCheckable

            // ✅ CAPTURAR TODO LO CLICKEABLE/EDITABLE/SCROLLABLE
            val tieneContenido = !text.isNullOrBlank() ||
                    !contentDesc.isNullOrBlank() ||
                    !hint.isNullOrBlank()
            val esInteractivo = isClickable || isEditable || isCheckable || isScrollable

            if (tieneContenido || esInteractivo) {
                // ✅ INFORMACIÓN UNIVERSAL
                val searchableText = getSearchableText(
                    text = text,
                    contentDesc = contentDesc,
                    hint = hint,
                    className = className
                )

                elementos.add(ScreenElement(
                    id = UUID.randomUUID().toString(),
                    viewId = nodo.viewIdResourceName?.takeIf { it.isNotBlank() },
                    className = className,
                    text = text,
                    contentDescription = contentDesc,
                    hintText = hint,
                    bounds = bounds,
                    centerX = bounds.centerX(),
                    centerY = bounds.centerY(),
                    isClickable = isClickable,
                    isCloneable = isClickable,
                    isEditable = isEditable,
                    isCheckable = isCheckable,
                    isChecked = nodo.isChecked,
                    isScrollable = isScrollable,
                    importance = calculateImportance(isClickable, text, contentDesc),
                    // ✅ INFORMACIÓN UNIVERSAL
                    isPassword = nodo.isPassword,
                    isEnabled = nodo.isEnabled,
                    isFocusable = nodo.isFocusable,
                    isLongClickable = nodo.isLongClickable,
                    parentText = nodo.parent?.text?.toString(),
                    siblingTexts = emptyList(), // Puedes obtenerlo del parent si quieres
                    availableActions = mutableListOf<String>().apply {
                        if (isClickable) add("tap")
                        if (isEditable) add("escribir")
                        if (isScrollable) add("scroll")
                        if (isCheckable) add("toggle")
                    },
                    visibility = if (nodo.isVisibleToUser) "visible" else "invisible",
                ))
            }
            for (i in 0 until nodo.childCount) {
                escanearNodo(nodo.getChild(i), profundidad + 1)
            }
        }
        escanearNodo(root)
        lastSnapshot = ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            activityName = root.className?.toString(),
            elements = elementos,
            totalElements = elementos.size,
            clickableElements = elementos.count { it.isClickable },
            editableElements = elementos.count { it.isEditable },
            scrollableContainers = elementos.count { it.isScrollable }
        )
    }
    // ✅ FUNCIÓN UNIVERSAL: Extraer texto buscable
    private fun getSearchableText(
        text: String?,
        contentDesc: String?,
        hint: String?,
        className: String?
    ): String {
        return listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            contentDesc?.takeIf { it.isNotBlank() },
            hint?.takeIf { it.isNotBlank() },
            extraerTextoDelNombreClase(className)
        ).joinToString(" | ").trim()
    }
    private fun extraerTextoDelNombreClase(className: String?): String? {
        if (className.isNullOrBlank()) return null

        // De "android.widget.Button" → "Button"
        val simpleName = className.split(".").last()

        return when {
            simpleName.contains("Button", ignoreCase = true) -> "Botón"
            simpleName.contains("Text", ignoreCase = true) -> "Campo de texto"
            simpleName.contains("Image", ignoreCase = true) -> "Imagen"
            simpleName.contains("Edit", ignoreCase = true) -> "Campo editable"
            simpleName.contains("List", ignoreCase = true) -> "Lista"
            simpleName.contains("Card", ignoreCase = true) -> "Tarjeta"
            simpleName.contains("View", ignoreCase = true) -> "Elemento"
            else -> null
        }
    }
    private fun calculateImportance(
        isClickable: Boolean,
        text: String?,
        contentDesc: String?
    ): Int {
        var score = 0
        if (isClickable) score += 40
        if (!text.isNullOrBlank()) score += 30
        if (!contentDesc.isNullOrBlank()) score += 20
        if (text?.length ?: 0 > 5) score += 10
        return score.coerceIn(0, 100)
    }

    private fun reportarAlServidor(
        texto: String, intencion: String, payload: List<ActionDto>,
        exito: Boolean, falla: String? = null
    ) {
        val reporte = ReporteFeedback(
            texto_original      = texto,
            intencion_detectada = intencion,
            json_generado       = payload,
            resultado           = if (exito) "EXITO" else "ERROR",
            error_detalle       = falla
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RetrofitClient.feedbackApi.enviarFeedback(reporte)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Feedback: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        // 🔴 IMPORTANTE: Desregistra el receiver al destruir
        try {
            unregisterReceiver(actionsReceiver)
            Log.d(TAG, "✅ BroadcastReceiver desregistrado")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error desregistrando receiver: ${e.message}")
        }

        handler.removeCallbacks(stepRunnable)
        currentActions = null
        super.onDestroy()
        instance = null
    }
}