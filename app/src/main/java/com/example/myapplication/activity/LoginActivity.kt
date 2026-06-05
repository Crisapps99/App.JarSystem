package com.example.myapplication.activity

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.R
import com.example.myapplication.service.MyAccessibilityService

class LoginActivity : AppCompatActivity() {

    // Códigos de petición únicos para los permisos nativos
    private val REQ_CODE_MICROPHONE = 201
    private val REQ_CODE_CONTACTS = 202
    private val REQ_CODE_PHONE = 203
    private val REQ_CODE_CAMERA = 204

    // 7 Contenedores Clickables (Paneles)
    private lateinit var panelMicrophone: LinearLayout
    private lateinit var panelOverlay: LinearLayout
    private lateinit var panelAccessibility: LinearLayout
    private lateinit var panelNotifications: LinearLayout
    private lateinit var panelContacts: LinearLayout
    private lateinit var panelPhone: LinearLayout
    private lateinit var panelCamera: LinearLayout

    // 7 Checkboxes correspondientes
    private lateinit var checkMicrophone: CheckBox
    private lateinit var checkOverlay: CheckBox
    private lateinit var checkAccessibility: CheckBox
    private lateinit var checkNotifications: CheckBox
    private lateinit var checkContacts: CheckBox
    private lateinit var checkPhone: CheckBox
    private lateinit var checkCamera: CheckBox

    private lateinit var btnFinalizeSetup: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        initializeUI()
        setupListeners()
    }

    private fun initializeUI() {
        // Enlace de paneles utilizando los IDs exactos del nuevo layout XML
        panelMicrophone = findViewById(R.id.panelMicrophone)
        panelOverlay = findViewById(R.id.panelOverlay)
        panelAccessibility = findViewById(R.id.panelAccessibility)
        panelNotifications = findViewById(R.id.panelNotifications)
        panelContacts = findViewById(R.id.panelContacts)
        panelPhone = findViewById(R.id.panelPhone)
        panelCamera = findViewById(R.id.panelCamera)

        // Enlace de Checkboxes
        checkMicrophone = findViewById(R.id.checkMicrophone)
        checkOverlay = findViewById(R.id.checkOverlay)
        checkAccessibility = findViewById(R.id.checkAccessibility)
        checkNotifications = findViewById(R.id.checkNotifications)
        checkContacts = findViewById(R.id.checkContacts)
        checkPhone = findViewById(R.id.checkPhone)
        checkCamera = findViewById(R.id.checkCamera)

        btnFinalizeSetup = findViewById(R.id.btnFinalizeSetup)
    }

    private fun setupListeners() {
        // 1. MICRÓFONO
        panelMicrophone.setOnClickListener {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_CODE_MICROPHONE)
            }
        }

        // 2. SUPERPOSICIÓN (Ajustes del Sistema)
        panelOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Superposición ya permitida", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. ACCESIBILIDAD (Ajustes de Accesibilidad)
        panelAccessibility.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                Toast.makeText(this, "Servicio de accesibilidad ya activo", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. NOTIFICACIONES (Listener de Notificaciones del Sistema)
        panelNotifications.setOnClickListener {
            if (!isNotificationServiceEnabled()) {
                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                startActivity(intent)
            } else {
                Toast.makeText(this, "Acceso a notificaciones ya otorgado", Toast.LENGTH_SHORT).show()
            }
        }

        // 5. CONTACTOS
        panelContacts.setOnClickListener {
            if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQ_CODE_CONTACTS)
            }
        }

        // 6. TELÉFONO
        panelPhone.setOnClickListener {
            if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQ_CODE_PHONE)
            }
        }

        // 7. CÁMARA
        panelCamera.setOnClickListener {
            if (!hasPermission(Manifest.permission.CAMERA)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CODE_CAMERA)
            }
        }

        // Botón de finalización
        btnFinalizeSetup.setOnClickListener {
            enterApp()
        }
    }

    private fun checkAllPermissionsStatus() {
        // Consulta los estados reales de hardware y del sistema operativo Android
        checkMicrophone.isChecked = hasPermission(Manifest.permission.RECORD_AUDIO)

        checkOverlay.isChecked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        checkAccessibility.isChecked = isAccessibilityServiceEnabled()
        checkNotifications.isChecked = isNotificationServiceEnabled()
        checkContacts.isChecked = hasPermission(Manifest.permission.READ_CONTACTS)
        checkPhone.isChecked = hasPermission(Manifest.permission.CALL_PHONE)
        checkCamera.isChecked = hasPermission(Manifest.permission.CAMERA)

        // Evalúa si se desbloquea el botón de continuar
        verificarYActualizarBoton()
    }

    private fun verificarYActualizarBoton() {
        val todosListos = checkMicrophone.isChecked &&
                checkOverlay.isChecked &&
                checkAccessibility.isChecked &&
                checkNotifications.isChecked &&
                checkContacts.isChecked &&
                checkPhone.isChecked &&
                checkCamera.isChecked

        if (todosListos) {
            btnFinalizeSetup.isEnabled = true
            btnFinalizeSetup.alpha = 1.0f  // Totalmente visible y clickable
        } else {
            btnFinalizeSetup.isEnabled = false
            btnFinalizeSetup.alpha = 0.4f  // Opaco, denota un estado bloqueado
        }
    }

    // --- Funciones Helpers de validación ---

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, MyAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected.flattenToString(), ignoreCase = true)) return true
        }
        return false
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        // Ejecutado cada vez que el usuario regresa de las pantallas de Ajustes de Android
        checkAllPermissionsStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Ejecutado inmediatamente después de que el usuario acepta/deniega un diálogo nativo de permisos
        checkAllPermissionsStatus()
    }

    private fun enterApp() {
        startActivity(Intent(this, JarActivity::class.java))
        finish()
    }
}