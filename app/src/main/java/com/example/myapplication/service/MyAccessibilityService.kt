package com.example.myapplication.service
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.api.ActionDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MyAccessibilityService : AccessibilityService(){

    private val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())


    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ACCES", "Accessibility conectado")

        //registra el receptor para recibir las acciones
        val filter = IntentFilter(ACTION_EXECUTE)
        registerReceiver(actionsReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    //broadcast para recivir acciones
    private val actionsReceiver = object : BroadcastReceiver(){
        override fun onReceive(context: Context?, intent: Intent?) {
            //objetenmos el json desde jaractivity
            val json = intent?.getStringExtra("actions_json")?: return
            Log.d("ACCESS", "broadcast recibido $json")
            if (json.isNullOrBlank()){
                Log.e("ACCESS", "leggo vacio el action_json")
                return
            }
            //ejecutamos las acciones recividas
            ejecutarAcciones(json)
        }
    }
    private var currentActions: List<ActionDto>? = null
    private var currentIndex = 0
    private val stepRunnable = object : Runnable {
        override fun run() {
            val actions = currentActions ?: return
            if (currentIndex >= actions.size) {
                currentActions = null
                return
            }

            val accion = actions[currentIndex++]
            when (accion.tipo) {
                "open_app" -> abrirApp(accion.params)
                "scroll" -> scroll(accion.params)
                "tap" -> tapCoordenadas(accion.params)
                "ocr_tap" -> tapPorTexto(accion.params)
            }

            handler.postDelayed(this, 900)
        }
    }

    private fun ejecutarAcciones(json: String){
        handler.removeCallbacks(stepRunnable)

        val listType = object : TypeToken<List<ActionDto>>() {}.type
        currentActions = Gson().fromJson(json, listType)
        currentIndex = 0

        handler.post(stepRunnable)
    }

    private fun abrirApp(params: Map<String, Any>?) {
        val pack = params?.get("package") as? String ?: return
        Log.d("ACCESS", "➡ Abriendo app: $pack")

        val i = packageManager.getLaunchIntentForPackage(pack)
        if (i == null) {
            Log.e("ACCESS", "❌ launchIntent NULL para $pack (package visibility)")
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }
    private fun scroll(params: Map<String, Any>?) {
        val direction = params?.get("direccion") as? String ?: "down"
        Log.d("ACCESS", "➡ Scroll: $direction")

        val path = Path()
        if (direction == "down") {
            path.moveTo(500f, 1700f)
            path.lineTo(500f, 600f)
        } else {
            path.moveTo(500f, 600f)
            path.lineTo(500f, 1700f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        dispatchGesture(gesture, null, null)
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
    private fun intentarClick(node : AccessibilityNodeInfo?): Boolean{
        if (node == null) return false
        if (node.isClickable){
            return  node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return  intentarClick(node.parent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(actionsReceiver) }
        handler.removeCallbacks  (stepRunnable)
        currentActions = null
        super.onDestroy()
    }
}