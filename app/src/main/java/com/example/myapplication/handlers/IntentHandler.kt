//package com.example.myapplication.handlers
//
//import android.content.Context
//import com.example.myapplication.activity.ActionExecutor
//
//object IntentHandler {
//
//    /**
//     * Procesa el código de acción (intención) y ejecuta la función de Android asociada.
//     * @param context El contexto (Activity) necesario para lanzar Intents.
//     * @param actionCode La intención predicha por la Red Neuronal (ej: "abrir_camara").
//     * @return El mensaje de resultado de la ejecución.
//     */
//    fun handleIntent(context: Context, actionCode: String?): String {
//        val safeAction = actionCode?.trim()?: "desconocido "
//
//        return when (safeAction) {
//            "open_camera" -> {
//                ActionExecutor.openCamera(context)
//            }
//            "abrir_mapa" -> {
//                ActionExecutor.openMap(context)
//            }
//            else -> {
//
//                "No se reconocio una accion valida para ejecutar($safeAction)"
//            }
//        }
//
//    }
//}
//
