package com.example.myapplication.service
import android.R
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.activity.ActionExecutor
import com.example.myapplication.api.ActionDto
import com.example.myapplication.api.ReporteFeedback
import com.example.myapplication.api.RetrofitClient
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
import kotlin.math.min

class MyAccessibilityService : AccessibilityService(){

    private val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var textoUltimaOrden: String=""
    private var intencionUltimaOrden: String="desconocida"

    //creamos chache para evitar reescanear constantemente
    private var lastSnapshot: ScreenSnapshot? = null
    private var lastSnapshotTime : Long = 0
    private val CACHE_DURATION = 2000L
    private var snapshotJob: Job? = null
    private var lastFingerprint: String = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())


    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ACCES", "Accessibility conectado")

        //registra el receptor para recibir las acciones
        val filter = IntentFilter(ACTION_EXECUTE)
        registerReceiver(actionsReceiver, filter, RECEIVER_NOT_EXPORTED)
    }
// escucha y decide cuando es el momento adecuado para capturar informacion

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return

        // 1. Filtramos solo eventos que realmente signifiquen un cambio de acción del usuario
        val eventosClave = listOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, // Cambio de App/Actividad
            AccessibilityEvent.TYPE_VIEW_SCROLLED,        // El usuario se movió
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED // Cambios internos
        )
        if (eventType in eventosClave) {
            // 2. Aplicamos DEBOUNCE:
            // Si llega un nuevo evento en menos de 1 segundo, cancelamos el escaneo anterior
            // y volvemos a esperar. Esto evita escanear 50 veces por segundo.
            snapshotJob?.cancel()
            snapshotJob = serviceScope.launch {
                delay(8000L) // Espera 1 segundo de inactividad
// 3. Verificación de seguridad: No escanear si la ventana es nula
                if (rootInActiveWindow != null) {
                    actualizarSnapDePantalla()
                }
            }
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

    private fun actualizarSnapDePantalla(){
        //si no hay evento activo no podemos hacer nada
        val root = rootInActiveWindow ?: return
        val elementos = mutableListOf<ScreenElement>()
        //infromacion de la app que el usuario esta viendo
        val packageManager = root.packageName?.toString()?: "unknown"
        val activityName = root.className?.toString()
        var clickableCount = 0
        var editableCount = 0
        var scrollableCount = 0
        // Generamos una "firma" rápida de la pantalla actual (puedes usar el texto de los primeros 5 elementos)
        // Creamos una huella rápida basada en la cantidad de hijos y el paquete
        val currentFingerprint = "${root.packageName}-${root.childCount}"
        if (currentFingerprint == lastFingerprint) return
        lastFingerprint = currentFingerprint
        /**
         * FUNCIÓN RECURSIVA: Se llama a sí misma para entrar en cada "capa" de la UI
         * @param nodo: El elemento actual que estamos analizando
         * @param profundidad: Límite para evitar bucles infinitos o excesivos (max 20)
         */
        fun escanearNodo(nodo: AccessibilityNodeInfo?, profundidad: Int = 0){
            if (nodo == null || profundidad > 20) return
            //filtro para que el servicio no se vea a si mismo ignoramos elementos
            val viewId = nodo.viewIdResourceName ?: ""
            if (viewId.contains("transcriptionTextView")||
                viewId.contains("jarvisOrb")||
                viewId.contains("micButton")){
                return
            }
            //obtenemos texto y tipo de vistas como boton imagen text etc
            val text = nodo.text?.toString()
            val contentDesc = nodo.contentDescription?.toString()
            val hint = nodo.hintText?.toString()
            val className = nodo.className?.toString()

            //otbenemos la geometriia dodne esta el elemento fisicamente
            val bounds = Rect()
            nodo.getBoundsInScreen(bounds)

            //capacidades
            val isClickable = nodo.isClickable
            val isLongClickable = nodo.isLongClickable
            val isCheckable = nodo.isCheckable
            val isChecked = nodo.isChecked
            val isFocusable = nodo.isFocusable
            val isEditable = nodo.isEditable
            val isPassword = nodo.isPassword
            val isEnabled = nodo.isEnabled
            val isScrollable = nodo.isScrollable

            // 6. CONTEXTO SEMÁNTICO: Miramos quién es el "padre" y los "hermanos"
            // Esto sirve para saber que un botón "Enviar" pertenece a un formulario específico
            val parentText = nodo.parent?.text?.toString()
            val siblingTexts = mutableListOf<String>()
            nodo.parent?.let { parent ->
                for (i in 0 until parent.childCount){
                    parent.getChild(i)?.text?.toString()?.let {siblingTexts.add(it)}
                }
            }
            // LISTA DE ACCIONES: Creamos un resumen de lo que se puede hacer
            val actions = mutableListOf<String>()
            if (isClickable) actions.add("click")
            if (isLongClickable) actions.add("long_click")
            if (isScrollable) actions.add("scroll")
            if (isEditable) actions.add("set_text")
            if (isCheckable) actions.add("toggle")

            //calculo de relevancia
            val importance = ScreenElement.calculateImportance(isClickable,text ,contentDesc, className)
            val visibility = if (nodo.isVisibleToUser)"visible" else "invisible"

            //decicion de uardado y guardamos si tien etexto o si es alg con lo que se peuda interactuar
            val tieneContenido = !text.isNullOrBlank() || !contentDesc.isNullOrBlank() || !hint.isNullOrBlank()
            val esInteractivo = isClickable || isEditable || isCheckable || isScrollable

            if (tieneContenido || esInteractivo) {
                val elemento = ScreenElement(
                    id = UUID.randomUUID().toString(),
                    viewId = viewId.takeIf { it.isNotBlank() },
                    className = className,
                    text = text,
                    contentDescription = contentDesc,
                    hintText = hint,
                    bounds = bounds,
                    centerX = bounds.centerX(),
                    centerY = bounds.centerY(),
                    isClickable = isClickable,
                    isCloneable = isClickable,
                    isLongClickable = isLongClickable,
                    isCheckable = isCheckable,
                    isChecked = isChecked,
                    isFocusable = isFocusable,
                    isEditable = isEditable,
                    isPassword = isPassword,
                    isEnabled = isEnabled,
                    isScrollable = isScrollable,
                    parentText = parentText,
                    siblingTexts = siblingTexts,
                    availableActions = actions,
                    importance = importance,
                    visibility = visibility
                )
                elementos.add(elemento)
                //sumamos a ls contadores globales del sanpshot
                if (isCheckable) clickableCount++
                if (isEditable) editableCount++
                if (isScrollable)scrollableCount++

            }
            //recursividad y buscamos a lso hijos de este nodo
            for (i in 0 until nodo.childCount){
                escanearNodo(nodo.getChild(i), profundidad + 1)
            }
            if (elementos.size != lastSnapshot?.totalElements) {
                Log.d("ACCESS_SCAN", "📸 Snapshot actualizado por acción real...")
            }
        }
        escanearNodo(root)//ejecutamos el escaneo desde la raiz
        val elementosUtiles = elementos.filter{it.isClickable || it.isEditable|| it.importance > 60}
        lastSnapshot = ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            activityName = activityName,
            elements = elementosUtiles,
            totalElements = elementosUtiles.size,
            clickableElements = clickableCount,
            editableElements = editableCount,
            scrollableContainers = scrollableCount
        )
        // Actualizar memoria global para que JarvisVoiceController la lea
        com.example.myapplication.core.ScreenMemory.lastSnapshot = lastSnapshot
        com.example.myapplication.core.ScreenMemory.lastSeenTexts = lastSnapshot!!.toContextList()
        // ═══════════════════════════════════════════════════════════
        // 🔍 LOG DE AUDITORÍA PARA TU MODELO
        // ═══════════════════════════════════════════════════════════
        Log.d("JARVIS_SNAPSHOT", "╔════════ REPORTE PARA SERVIDOR ════════╗")
        Log.d("JARVIS_SNAPSHOT", "║ APP: $packageName")
        Log.d("JARVIS_SNAPSHOT", "║ INTERACTIVOS: $clickableCount Clics | $editableCount Textos")

        // Ver exactamente qué texto se envía al modelo para que aprenda
        elementosUtiles.sortedByDescending { it.importance }.take(50).forEachIndexed { i, e ->
            val info = e.text ?: e.contentDescription ?: e.viewId ?: "Sin Identidad"
            Log.d("JARVIS_SNAPSHOT", "║ [$i] $info | Coords: (${e.centerX},${e.centerY}) | Imp: ${e.importance}")
        }
        Log.d("JARVIS_SNAPSHOT", "╚═══════════════════════════════════════════╝")
    }
    /**
     * Búsqueda inteligente de elementos
     * Reemplaza tu lógica de fuzzy matching básica
     */
    private fun buscarElementoPorTexto(textoBuscado: String): ScreenElement?{
        //verificamos si tenemos la info de la pantalla o s i no la creamo s
        val snapshot = lastSnapshot ?: run {
            actualizarSnapDePantalla()
            lastSnapshot
        }?: return null //si despeus de iintentar sigue siendo null salimo s
        val normalizado = textoBuscado.lowercase().trim()
        //busqueda en cascada
        //1 cocidencia exata en el texto que ve el usuario
        snapshot.elements.find {
            it.text?.lowercase() == normalizado
        }?.let { return  it }
        //2 cocidencia exata en la desciccion
        snapshot.elements.find {
            it.contentDescription?.lowercase() == normalizado
        }?.let{return  it}
        //3concidencia parfcial
        // Aquí usa la función getSearchableText() que procesa IDs y texto del padre.
        snapshot.elements.find { elem ->
            elem.getSearchableText().contains(normalizado)
        }?.let{return  it}

        //4 busqueda difusa fuzzy matching si nada coicidio buscamos lo que mas se paresca utilziando matematicas
        val candidatos = snapshot.elements
            .filter { it.isClickable || it.isEditable } // Solo nos interesan cosas interactivas
            .map { elem ->
                // Comparamos qué tan parecidos son el texto buscado y el del elemento
                val similarity = calcularSimilitud(normalizado, elem.getSearchableText())
                elem to similarity
            }
            .filter { it.second > 0.6 } // Solo aceptamos si se parecen más de un 60%
            .sortedByDescending { it.second } // Ponemos el más parecido primero

        return candidatos.firstOrNull()?.first
    }

    //calcular el porcentaje de similitud entre 0.0 y 1.0
    private fun calcularSimilitud(s1: String, s2:String):Double{
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length >s2.length) s2 else s1

        if (longer.isEmpty()) return  1.0
        //formula (largoTtoal - Errores) / largoTotal
        val longerLength = longer.length
        val editDistance = calcularLevenshtein(longer, shorter)
        return (longerLength - editDistance)/ longerLength.toDouble()
    }
    /**
     * Algoritmo de Levenshtein:
     * Cuenta cuántos cambios (insertar, borrar o cambiar letras)
     * se necesitan para convertir una palabra en otra.
     */
    private fun calcularLevenshtein(s1: String, s2: String): Int {
        //se crea yuuna matriz para comprar cada letra de s1 con cada letra de s2
        val dp = Array(s1.length + 1 ){ IntArray(s2.length + 1) }
        for (i in 0..s2.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length){
            for (j in 1..s2.length){
                val cost = if (s1[i-1] == s2[j -1])0 else 1
                //buscamos el camino minimo
                dp[i][j] = minOf(
                    dp[i - 1 ][j] + 1,//borrar
                    dp[i][j - 1]+1,//insertar
                    dp[i - 1][j - 1] + cost//cambiar letra
                )
            }
        }
        return  dp [s1.length][s2.length]
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
                        waitTime = 2000L
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
                    val elemento = buscarElementoPorTexto(textoABuscar)
                    if (elemento != null) {
                        Log.d("ACCESS","elemtno encontrado: ${elemento.getSearchableText()}")
                        tapCoordenadas(mapOf(
                            "x" to elemento.centerX,
                            "y" to elemento.centerY
                        ))
                    }else{
                        exitoAccion = false
                        detalleError = "Elemento '$textoABuscar' no encontrado en pantalla"
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
//    private fun realizarTapPorTextoConRetorno(texto: String): Boolean {
//        val root = rootInActiveWindow ?: return false
//        val nodes = root.findAccessibilityNodeInfosByText(texto)
//
//        if (nodes.isNotEmpty()) {
//            val nodo = nodes[0]
//            if (intentarClick(nodo)) return true
//
//            // Si no es clicable, intentamos por coordenadas
//            val rect = android.graphics.Rect()
//            nodo.getBoundsInScreen(rect)
//            tapCoordenadas(mapOf("x" to rect.centerX(), "y" to rect.centerY()))
//            return true
//        }
//        return false
//    }
private fun ejecutarAcciones(json: String){
    handler.removeCallbacks(stepRunnable)

    val listType = object : TypeToken<List<ActionDto>>() {}.type
    currentActions = Gson().fromJson(json, listType)
    //si el json esta bacio excane ala pantalla
    if (currentActions.isNullOrEmpty()){
        Log.d("ACCESS","iniciadno escaneo de pantalla")
        actualizarSnapDePantalla()
       lastSnapshot?.let { snapshot ->
           Log.i("ACCESS", "Elementos detectados: ${snapshot.totalElements}")
           snapshot.elements.take(10).forEach { elem ->
               Log.i("ACCESS", "  - ${elem.getSearchableText()} [${elem.className}]")
           }
       }
    }else{
        currentIndex = 0
        handler.post(stepRunnable)
    }
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


    fun scroll(params: Map<String, Any>?) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val yStart = metrics.heightPixels * 0.8f
        val yEnd = metrics.heightPixels * 0.3f // Un poco más corto para mayor precisión

        val path = android.graphics.Path()
        path.moveTo(x, yStart)
        path.lineTo(x, yEnd)

        val stroke = GestureDescription.StrokeDescription(path, 400, 500)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object:GestureResultCallback() {
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
//    private fun tapPorTexto(params: Map<String, Any>?) {
//        val texto = params?.get("texto") as? String ?: return
//        val root = rootInActiveWindow ?: return
//
//        val nodes = root.findAccessibilityNodeInfosByText(texto)
//        if (nodes.isNotEmpty()) {
//            val nodoCualquiera = nodes[0]
//            if (!intentarClick(nodoCualquiera)) {
//                val rect = android.graphics.Rect()
//                nodoCualquiera.getBoundsInScreen(rect)
//                tapCoordenadas(mapOf("x" to rect.centerX(), "y" to rect.centerY()))
//            }
//        }
//    }
    private fun escribirTexto (params: Map<String, Any>?){
        //extraemos el texto que queremos escribir en el aprametro
        val texto = params?.get("texto") as? String ?: return
        //obtenemos la raiz de la ventana activa par apoder buscar el elemento

        val root = rootInActiveWindow ?: return
    //intentamos buscar que elemnto tien eel curso parpadeante
    // FOCUS_INPUT busca específicamente campos de texto o áreas donde se puede escribir
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    //y si encontrmaos un lugar donde escribit
    if (focusNode != null){
        // Los comandos de accesibilidad complejos usan un 'Bundle' para pasar datos
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            texto
        )
        //esto reeemplaza todo el texto qwue existe en el campo
        //ejecuitamos la accion Le ordenamos al nodo que cambie su contenido por el nuevo texto
        focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments)
    }
    }
//    private fun intentarClick(node: AccessibilityNodeInfo?): Boolean {
//        var tempNode = node
//        while (tempNode != null) {
//            if (tempNode.isClickable) {
//                return tempNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//            }
//            tempNode = tempNode.parent
//        }
//        return false
//    }

//    //obtener lso datos de pantalla
//    private fun obtenerTextoDePantalla(): String {
//        val root = rootInActiveWindow ?: return "Pantalla vacía"
//        val sb = StringBuilder()
//
//        fun recorrer(node: AccessibilityNodeInfo?) {
//            if (node == null) return
//            if (node.text != null) {
//                sb.append("${node.text} | ")
//            }
//            for (i in 0 until node.childCount) {
//                recorrer(node.getChild(i))
//            }
//        }
//
//        recorrer(root)
//        return sb.toString()
//    }
//funcion para mirar la pantalla anilizar lso nodos ojo del asistnte
//    private fun extraerInfo(): List<Map<String, Any>>{
//        val elementos=mutableListOf<Map<String, Any>>()
//        val textosTemporales = mutableListOf<String>() // lista para el contexto
//        //obtien eel nodo raiz de la ventana actual
//        val root = rootInActiveWindow ?: return elementos
//        //funcion interna recursiva recorre uno po runo
//        fun recorrerNodo(nodo: AccessibilityNodeInfo){
//            if (nodo ==null) return
//            //extraccion del texto intenta sacar texto visible y busca la descripcion
//            val texto = nodo.text?.toString() ?: nodo.contentDescription?.toString()
//            val viewId = nodo.viewIdResourceName ?:""
//            //filtro para qu eno agrege textos que escuch a
//            if (viewId.contains("transcriptionTextView")){
//                return
//            }
//            //solo  ingresa eementos que tengan algo escrito
//            if (!texto.isNullOrBlank()){
//                textosTemporales.add(texto) //guardamos el texro para que jarvis lo lea
//                //crea un objeto rect para guardar las coordenadas
//                val rect = android.graphics.Rect()
//                nodo. getBoundsInScreen(rect)
//                elementos.add(mapOf(
//                    "texto" to texto,// El nombre o contenido
//                    "x" to rect.centerX(),      // Centro horizontal para saber dónde tocar
//                    "y" to rect.centerY(),      // Centro vertical para saber dónde tocar
//                    "clicable" to nodo.isClickable // Indica si es un botón o algo que reacciona al toque
//                ))
//            }
//            //recursividad si tiene hiojs viulve allamar ala funcion para cada uno de lleos
//            for (i in 0 until nodo.childCount){
//                val hijo = nodo.getChild(i)
//                if (hijo != null){
//                    recorrerNodo(hijo)
//                }
//            }
//        }
//        recorrerNodo(root)
//    //actualizamos mmoria global
//        com.example.myapplication.core.ScreenMemory.lastSeenTexts = textosTemporales
//        return elementos
//    }
//    //funcion apra enviar texto
//    private fun escribirTexto(params: Map<String, Any>?){
//        val texto =  params?.get("texto") as? String ?: return
//        val root = rootInActiveWindow ?: return
//
//        //busca el campo que tien el foco que parpadea la rayita  para escribir
//        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
//        if (focusNode != null){
//            val arguments = Bundle()
//            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
//            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
//        }
//    }
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