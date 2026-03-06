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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.R
import com.example.myapplication.service.MyAccessibilityService
import com.ncorti.slidetoact.SlideToActView

class LoginActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_CODE = 1000
    private val MULTI_PERMISSION_CODE   = 200

    private val colorActivo   = 0xFF1DE0A0.toInt()
    private val colorInactivo = 0xFFE53935.toInt()

    private lateinit var btnMicrofono:     Button
    private lateinit var btnOverlay:       Button
    private lateinit var btnAccesibilidad: Button
    private lateinit var btnNotificaciones:Button
    private lateinit var btnContactos:     Button
    private lateinit var btnLlamadas:      Button
    private lateinit var btnContinue:      SlideToActView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        btnMicrofono      = findViewById(R.id.btnMicrofono)
        btnOverlay        = findViewById(R.id.btnOverlay)
        btnAccesibilidad  = findViewById(R.id.btnAccesibilidad)
        btnNotificaciones = findViewById(R.id.btnNotificaciones)
        btnContactos      = findViewById(R.id.btnContactos)
        btnLlamadas       = findViewById(R.id.btnLlamadas)
        btnContinue       = findViewById(R.id.btnContinue)

        // ── Clicks permisos ──────────────────────────────────

        btnMicrofono.setOnClickListener {
            if (!tienePermiso(Manifest.permission.RECORD_AUDIO)) {
                pedirPermisos(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }

        btnOverlay.setOnClickListener {
            if (!tieneOverlay()) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")),
                    OVERLAY_PERMISSION_CODE
                )
            }
        }

        btnAccesibilidad.setOnClickListener {
            if (!tieneAccesibilidad()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnNotificaciones.setOnClickListener {
            if (!tieneNotificaciones()) {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }

        btnContactos.setOnClickListener {
            if (!tienePermiso(Manifest.permission.READ_CONTACTS)) {
                pedirPermisos(arrayOf(Manifest.permission.READ_CONTACTS))
            }
        }

        btnLlamadas.setOnClickListener {
            if (!tienePermiso(Manifest.permission.CALL_PHONE)) {
                pedirPermisos(arrayOf(Manifest.permission.CALL_PHONE))
            }
        }

        // ── SlideToAct ───────────────────────────────────────
        btnContinue.onSlideCompleteListener = object : SlideToActView.OnSlideCompleteListener {
            override fun onSlideComplete(view: SlideToActView) {
                if (todosPermisosActivos()) {
                    startActivity(Intent(this@LoginActivity, JarActivity::class.java))
                    finish()
                } else {
                    view.resetSlider()
                    mostrarQueFalta()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarTodo()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_CODE) actualizarTodo()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        actualizarTodo()
    }

    // ── Checks ───────────────────────────────────────────────

    private fun tienePermiso(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun tieneOverlay() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun tieneAccesibilidad() =
        isAccessibilityServiceEnabled(this, MyAccessibilityService::class.java)

    private fun tieneNotificaciones(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun todosPermisosActivos() =
        tienePermiso(Manifest.permission.RECORD_AUDIO) &&
                tieneOverlay() &&
                tieneAccesibilidad() &&
                tieneNotificaciones() &&
                tienePermiso(Manifest.permission.READ_CONTACTS) &&
                tienePermiso(Manifest.permission.CALL_PHONE)

    // ── UI ───────────────────────────────────────────────────

    private fun actualizarTodo() {
        setEstado(btnMicrofono,      tienePermiso(Manifest.permission.RECORD_AUDIO))
        setEstado(btnOverlay,        tieneOverlay())
        setEstado(btnAccesibilidad,  tieneAccesibilidad())
        setEstado(btnNotificaciones, tieneNotificaciones())
        setEstado(btnContactos,      tienePermiso(Manifest.permission.READ_CONTACTS))
        setEstado(btnLlamadas,       tienePermiso(Manifest.permission.CALL_PHONE))
        btnContinue.alpha = if (todosPermisosActivos()) 1f else 0.45f
    }

    private fun setEstado(btn: Button, activo: Boolean) {
        if (activo) {
            btn.text = "Activo ✓"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorActivo)
            btn.isEnabled = false
        } else {
            btn.text = "Activar"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorInactivo)
            btn.isEnabled = true
        }
    }

    private fun pedirPermisos(perms: Array<String>) {
        ActivityCompat.requestPermissions(this, perms, MULTI_PERMISSION_CODE)
    }

    private fun mostrarQueFalta() {
        val msg = buildString {
            if (!tienePermiso(Manifest.permission.RECORD_AUDIO)) appendLine("• Micrófono")
            if (!tieneOverlay())                                  appendLine("• Superposición")
            if (!tieneAccesibilidad())                            appendLine("• Accesibilidad")
            if (!tieneNotificaciones())                           appendLine("• Notificaciones")
            if (!tienePermiso(Manifest.permission.READ_CONTACTS)) appendLine("• Contactos")
            if (!tienePermiso(Manifest.permission.CALL_PHONE))    appendLine("• Llamadas")
        }.trim()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permisos pendientes")
            .setMessage("Activa los siguientes permisos:\n\n$msg")
            .setPositiveButton("Entendido", null)
            .show()
    }

    // ── Utilidad accesibilidad ───────────────────────────────

    private fun isAccessibilityServiceEnabled(
        context: Context, service: Class<out AccessibilityService>
    ): Boolean {
        val expected = ComponentName(context, service)
        val enabled  = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected.flattenToString(), ignoreCase = true)) return true
        }
        return false
    }
}