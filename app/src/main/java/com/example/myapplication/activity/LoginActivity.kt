package com.example.myapplication.activity

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.R
import com.example.myapplication.service.MyAccessibilityService
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginActivity : AppCompatActivity() {
    private val OVERLAY_PERMISSION_CODE = 1000
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val inputLayout=findViewById<TextInputLayout>(R.id.nombreInput)
        val btnContinue =findViewById<Button>(R.id.btnContinue)
        val editText = findViewById<TextInputEditText>(R.id.NombreEditText)

        //bloqueo del boton
        btnContinue.isEnabled = false
        btnContinue.alpha=0.5f

        //validacion mientras escribe
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validarNombre(s.toString(), inputLayout, btnContinue)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnContinue.setOnClickListener{
            checkPermissionsBeforeContinuing()
        }

    }
    private fun checkPermissionsBeforeContinuing() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
            return // Detenemos aquí hasta que el usuario acepte el micro
        }
        // Primero Superposición (Overlay)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
        }
        // Segundo Accesibilidad
        else if (!isAccessibilityServiceEnabled(this, MyAccessibilityService::class.java)) {
            showAccessibilityDialog()
        }
        //  Si todo está bien, pasamos a JarActivity
        else {
            switchToActivity(
                context = this@LoginActivity,
                destinationActivity = JarActivity::class.java,
                finishCurrent = true // Te recomiendo cerrar Login para no volver atrás
            )
        }
    }
    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de Superposición")
            .setMessage("Para que el orbe de Jarvis aparezca flotando sobre otras apps, activa el permiso 'Aparecer encima'.")
            .setPositiveButton("Configurar") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
            }
            .setCancelable(false)
            .show()
    }
    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Servicio de Accesibilidad")
            .setMessage("Jarvis necesita esto para entender qué pasa en tu pantalla. Busca 'MyAccessibilityService' y actívalo.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setCancelable(false)
            .show()
    }
    // Cuando el usuario regresa de la pantalla de ajustes de Overlay
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            // Re-evaluamos los permisos al volver
            checkPermissionsBeforeContinuing()
        }
    }
    // Función auxiliar para saber si Accesibilidad está activo
    private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, service)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName.flattenToString(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }
    private fun validarNombre(
        nombre:String,
        input: TextInputLayout,
        boton: Button
    ){
        val soloLetras="^[a-zA-ZáéíóúÁÉÍÓÚñÑ ]+\$".toRegex()
        when {
            nombre.isEmpty()->{
                input.error="Ingreesa tu nombre"
                boton.isEnabled=false
                boton.alpha=0.5f
            }
            !soloLetras.matches(nombre)->{
                input.error = "solo s epermite letras"
                boton.isEnabled=false
                boton.alpha=0.5f
            }
            else->{
                input.error = null
                boton.isEnabled= true
                boton.alpha= 1f

            }
        }
    }
}