package com.example.myapplication.service
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.activity.ActionExecutor
import com.example.myapplication.api.ActionDto
import com.example.myapplication.api.ReporteFeedback
import com.example.myapplication.api.RetrofitClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyAccessibilityService : AccessibilityService(){

    private val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var textoUltimaOrden: String=""
    private var intencionUltimaOrden: String="desconocida"


    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ACCES", "Accessibility conectado")

        //registra el receptor para recibir las acciones
        val filter = IntentFilter(ACTION_EXECUTE)
        registerReceiver(actionsReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Cada vez que la pantalla cambie (clics, scrolls, nuevas ventanas)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            extraerInfo() // Actualiza la memoria automáticamente
        }
    }

    override fun onInterrupt() {}

    //broadcast para recivir acciones
    private val actionsReceiver = object : BroadcastReceiver(){
        override fun onReceive(context: Context?, intent: Intent?) {
            //objetenmos el json desde jaractivity
            val json = intent?.getStringExtra("actions_json")?: return
            //capturamos el texxto y la inencio original
            textoUltimaOrden = intent.getStringExtra("texto_original") ?: "orden sin texto"
            intencionUltimaOrden = intent.getStringExtra("intencion_original") ?: "intencion_desconocida"
            Log.d("ACCESS", "broadcast recibido $json")
            if (!json.isNullOrBlank()){
                ejecutarAcciones(json)
            }

        }
    }
    private var currentActions: List<ActionDto>? = null
    private var currentIndex = 0

    private val stepRunnable = object : Runnable {
        override fun run() {
            val actions = currentActions ?: return
            if (currentIndex >= actions.size) {
                Log.d("ACCESS", "✅ Todas las acciones completadas")
                currentActions = null
                return
            }
            val accion = actions[currentIndex++]
            var waitTime = 1000L // Tiempo por defecto para clics y scroll
            Log.d("ACCESS", "Ejecutando acción [${currentIndex-1}]: ${accion.tipo}")
            val textoParaReporte = textoUltimaOrden
            val intencionParaReporte = intencionUltimaOrden

            var exitoAccion = true
            var detalleError: String? = null
            when (accion.tipo) {
                "open_app" -> {
                    val pkg = accion.params?.get("package") as? String ?: ""
                    val intentApp = packageManager.getLaunchIntentForPackage(pkg)
                    if (intentApp == null) {
                        exitoAccion = false
                        detalleError = "La app con paquete $pkg no está instalada."
                    } else {
                        ActionExecutor.openApp(this@MyAccessibilityService, pkg)
                    }
                }
                "global_action" -> {
                    ejecutarAccionGlobal(accion.params)
                    waitTime = 1000L
                }
                "scroll" -> {
                    Log.d("ACCESS", "Ejecutando Scroll ahora que la app debería estar lista")
                    scroll(accion.params)
                    exitoAccion= true
                    waitTime = 1500L
                }
                "tap" -> tapCoordenadas(accion.params)
                "ocr_tap" -> {
                    val textoABuscar = accion.params?.get("texto") as? String ?: ""
                    // Intentamos el click y guardamos el resultado
                    val encontrado = realizarTapPorTextoConRetorno(textoABuscar)
                    if (!encontrado) {
                        exitoAccion = false
                        detalleError = "El texto '$textoABuscar' no es visible en la pantalla actual."
                    }
                }
                "type_text" -> escribirTexto(accion.params)
            }
            val resultadoFinal = if (exitoAccion) "EXITO" else "ERROR"
            reportarAlServidor(textoUltimaOrden, intencionUltimaOrden, actions, exitoAccion, detalleError)

            Log.d("JARVIS_LEARNING", "Reporte enviado: $resultadoFinal | Error: $detalleError")
             // Programamos la siguiente acción con el tiempo de espera calculado
            handler.postDelayed(this, waitTime)
        }
    }
    private fun realizarTapPorTextoConRetorno(texto: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(texto)

        if (nodes.isNotEmpty()) {
            val nodo = nodes[0]
            if (intentarClick(nodo)) return true

            // Si no es clicable, intentamos por coordenadas
            val rect = android.graphics.Rect()
            nodo.getBoundsInScreen(rect)
            tapCoordenadas(mapOf("x" to rect.centerX(), "y" to rect.centerY()))
            return true
        }
        return false
    }
    private fun ejecutarAccionGlobal(params: Map<String, Any>?) {
        val actionType = params?.get("action") as? String ?: return
        Log.d("ACCESS", "🌍 Acción Global: $actionType")

        val globalAction = when (actionType) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> -1
        }

        if (globalAction != -1) {
            performGlobalAction(globalAction)
        }
    }
    private fun ejecutarAcciones(json: String){
        handler.removeCallbacks(stepRunnable)

        val listType = object : TypeToken<List<ActionDto>>() {}.type
        currentActions = Gson().fromJson(json, listType)
        //si el json esta bacio excane ala pantalla
        if (currentActions.isNullOrEmpty()){
            Log.d("ACCESS","iniciadno escaneo de pantalla")
            val pantallainfo = extraerInfo()
            if (pantallainfo.isNullOrEmpty()){
                Log.w("ACCESS","no se detectaron elementos visuales")
            }else{
                pantallainfo.forEach { el ->
                    Log.i("ACCESS","visto[${el["texto"]}] en pos X:${el["x"]}")
                }
            }
        }else{
            currentIndex = 0
            handler.post(stepRunnable)
        }
        }

    fun scroll(params: Map<String, Any>?) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val yStart = metrics.heightPixels * 0.8f
        val yEnd = metrics.heightPixels * 0.3f // Un poco más corto para mayor precisión

        val path = android.graphics.Path()
        path.moveTo(x, yStart)
        path.lineTo(x, yEnd)

        val gestureBuilder = GestureDescription.Builder()

        // El secreto: Ponemos 400ms de 'startTime' (retraso antes de mover)
        // y 500ms de duración. Esto suele saltarse los filtros de HiTouch.
        val stroke = GestureDescription.StrokeDescription(path, 400, 500)

        gestureBuilder.addStroke(stroke)
        dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("ACCESS", "✅ Gesto de scroll completado físicamente")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e("ACCESS", "❌ El sistema canceló el gesto de scroll")
            }
        }, null)
    }
    private fun tapCoordenadas(params: Map<String, Any>?) {
        val x = (params?.get("x") as? Number)?.toFloat() ?: return
        val y = (params?.get("y") as? Number)?.toFloat() ?: return

        val path = Path().apply { moveTo(x, y) }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()

        dispatchGesture(gesture, null, null)
    }
    private fun tapPorTexto(params: Map<String, Any>?) {
        val texto = params?.get("texto") as? String ?: return
        val root = rootInActiveWindow ?: return

        val nodes = root.findAccessibilityNodeInfosByText(texto)
        if (nodes.isNotEmpty()) {
            val nodoCualquiera = nodes[0]
            if (!intentarClick(nodoCualquiera)) {
                val rect = android.graphics.Rect()
                nodoCualquiera.getBoundsInScreen(rect)
                tapCoordenadas(mapOf("x" to rect.centerX(), "y" to rect.centerY()))
            }
        }
    }
    private fun intentarClick(node: AccessibilityNodeInfo?): Boolean {
        var tempNode = node
        while (tempNode != null) {
            if (tempNode.isClickable) {
                return tempNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            tempNode = tempNode.parent
        }
        return false
    }

    //obtener lso datos de pantalla
    private fun obtenerTextoDePantalla(): String {
        val root = rootInActiveWindow ?: return "Pantalla vacía"
        val sb = StringBuilder()

        fun recorrer(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text != null) {
                sb.append("${node.text} | ")
            }
            for (i in 0 until node.childCount) {
                recorrer(node.getChild(i))
            }
        }

        recorrer(root)
        return sb.toString()
    }
//funcion para mirar la pantalla anilizar lso nodos ojo del asistnte
    private fun extraerInfo(): List<Map<String, Any>>{
        val elementos=mutableListOf<Map<String, Any>>()
        val textosTemporales = mutableListOf<String>() // lista para el contexto
        //obtien eel nodo raiz de la ventana actual
        val root = rootInActiveWindow ?: return elementos
        //funcion interna recursiva recorre uno po runo
        fun recorrerNodo(nodo: AccessibilityNodeInfo){
            if (nodo ==null) return
            //extraccion del texto intenta sacar texto visible y busca la descripcion
            val texto = nodo.text?.toString() ?: nodo.contentDescription?.toString()
            val viewId = nodo.viewIdResourceName ?:""
            //filtro para qu eno agrege textos que escuch a
            if (viewId.contains("transcriptionTextView")){
                return
            }
            //solo  ingresa eementos que tengan algo escrito
            if (!texto.isNullOrBlank()){
                textosTemporales.add(texto) //guardamos el texro para que jarvis lo lea
                //crea un objeto rect para guardar las coordenadas
                val rect = android.graphics.Rect()
                nodo. getBoundsInScreen(rect)
                elementos.add(mapOf(
                    "texto" to texto,// El nombre o contenido
                    "x" to rect.centerX(),      // Centro horizontal para saber dónde tocar
                    "y" to rect.centerY(),      // Centro vertical para saber dónde tocar
                    "clicable" to nodo.isClickable // Indica si es un botón o algo que reacciona al toque
                ))
            }
            //recursividad si tiene hiojs viulve allamar ala funcion para cada uno de lleos
            for (i in 0 until nodo.childCount){
                val hijo = nodo.getChild(i)
                if (hijo != null){
                    recorrerNodo(hijo)
                }
            }
        }
        recorrerNodo(root)
    //actualizamos mmoria global
        com.example.myapplication.core.ScreenMemory.lastSeenTexts = textosTemporales
        return elementos
    }
    //funcion apra enviar texto
    private fun escribirTexto(params: Map<String, Any>?){
        val texto =  params?.get("texto") as? String ?: return
        val root = rootInActiveWindow ?: return

        //busca el campo que tien el foco que parpadea la rayita  para escribir
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusNode != null){
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        }
    }
    private fun reportarAlServidor(
        texto: String,
        intencion: String,
        payload: List<ActionDto>,
        exito: Boolean,
        falla: String? = null
    ) {
        val resultado = if (exito) "EXITO" else "ERROR"
        Log.d("JARVIS_FEEDBACK", "🚀 Preparando reporte: $resultado para la frase: '$texto'")
        val reporte = ReporteFeedback(
            texto_original = texto,
            intencion_detectada = intencion,
            json_generado = payload,
            resultado = resultado,
            error_detalle = falla
        )
        // Ejecutar en una corrutina para no bloquear el servicio de accesibilidad
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = RetrofitClient.feedbackApi.enviarFeedback(reporte)
                if (response.isSuccessful) {
                    Log.d("JARVIS_LEARNING", "✅ Feedback enviado: Jarvis aprendió algo nuevo")
                }
            } catch (e: Exception) {
                Log.e("JARVIS_LEARNING", "❌ Error al enviar feedback: ${e.message}")
            }
        }
    }
    override fun onDestroy() {
        runCatching { unregisterReceiver(actionsReceiver) }
        handler.removeCallbacks  (stepRunnable)
        currentActions = null
        super.onDestroy()
    }



}