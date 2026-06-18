package com.example.myapplication.activity

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.ui.AmbientGlowView
import com.example.myapplication.viewmodel.PassVm

@Composable
fun LoginScreen(vm: PassVm, onFinish: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // Lanzadores para permisos nativos
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.checkPermissions(context)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xA0B0C))) {
        // Tu vista personalizada de fondo
        AndroidView(factory = { AmbientGlowView(it) }, modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                Text("Configuración de Nexus", fontSize = 26.sp, color = Color(0xFFE2E2E5), fontWeight = FontWeight.Bold)
                Text(
                    "Activa los siguientes accesos para otorgar capacidades completas.",
                    fontSize = 14.sp, color = Color(0xFF8B90A0), modifier = Modifier.padding(vertical = 12.dp)
                )

                // Paneles de permisos
                PermItem("Micrófono", "Para escuchar comandos de voz", "📱", state.micOk) {
                    launcher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
                PermItem("Superposición", "Para mostrar el orbe flotante", "🔳", state.overlayOk) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }
                PermItem("Accesibilidad", "Para automatizar acciones", "♿", state.accessOk) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                PermItem("Notificaciones", "Para leer mensajes entrantes", "🔔", state.notifyOk) {
                    context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
                PermItem("Contactos", "Para identificar a quién llamar", "👥", state.contactsOk) {
                    launcher.launch(android.Manifest.permission.READ_CONTACTS)
                }
                PermItem("Teléfono", "Para iniciar llamadas por voz", "☎️", state.phoneOk) {
                    launcher.launch(android.Manifest.permission.CALL_PHONE)
                }
                PermItem("Cámara", "Para tomar fotos por comando", "📷", state.cameraOk) {
                    launcher.launch(android.Manifest.permission.CAMERA)
                }
                // En LoginScreen.kt, añade este item en la lista de permisos:
                PermItem(title = "Ubicación",
                    desc = "Para buscar lugares cerca de ti",
                    icon = "📍",
                    isOk = state.locationOk,
                    onClick = {
                        launcher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                )
            }

            // Botón Inferior
            Button(
                onClick = onFinish,
                enabled = state.canContinue,
                modifier = Modifier.fillMaxWidth().padding(20.dp).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF007BFF), // Ajusta a tu @drawable/btn_allow_all
                    disabledContainerColor = Color(0xFF007BFF).copy(alpha = 0.4f)
                )
            ) {
                Text("CONTINUAR A NEXUS ➔", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PermItem(title: String, desc: String, icon: String, isOk: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color(0xFF1A1C1E), RoundedCornerShape(12.dp)) // bg_permission_card
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).background(Color(0xFF2D2F33), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 22.sp)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, color = Color(0xFFE2E2E5), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(desc, color = Color(0xFF8B90A0), fontSize = 13.sp)
        }
        Checkbox(
            checked = isOk,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Color.Cyan)
        )
    }
}