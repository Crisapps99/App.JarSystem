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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.myapplication.api.ActionDto

class MyAccessibilityService : AccessibilityService(){

    private val ACTION_EXECUTE = "JARVIS.EXECUTE_ACTIONS"

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
            val json = intent?.getStringExtra("actions_json")?:return
            //ejecutamos las acciones recividas
            ejecutarAcciones(json)
        }
    }

    private fun ejecutarAcciones(json: String){
        //convertimos el json recivido en list accion
        val listType = object : TypeToken<List<ActionDto>>() {}.type
        val acciones: List<ActionDto> = Gson().fromJson(json,listType)

        //ejecutamos la ccion secuencialmente
        for (accion in acciones){
            when (accion.tipo) {
                "open_app" -> abrirApp(accion.params)       // Abrir aplicaciones
                "scroll" -> scroll(accion.params)           // Scroll automático
                "tap" -> tapCoordenadas(accion.params)      // Tap por coordenadas
                "ocr_tap" -> tapPorTexto(accion.params)     // Tap por texto
                else -> Log.e("ACCESS", "Acción no reconocida: ${accion.tipo}")
            }

            //espera para evitar saturar el sistema
            Thread.sleep(900)
        }
    }
    private fun abrirApp(params: Map<String, Any>?) {
        val pack = params?.get("package") as? String ?: return
        Log.d("ACCESS", "➡ Abriendo app: $pack")

        val i = packageManager.getLaunchIntentForPackage(pack)
        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d("ACCESS", "✔ Tap en '$texto'")
        } else {
            Log.e("ACCESS", "❌ Texto no encontrado '$texto'")
        }
    }
}