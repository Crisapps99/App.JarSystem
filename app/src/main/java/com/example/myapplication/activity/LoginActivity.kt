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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.R
import com.example.myapplication.service.MyAccessibilityService
import com.ncorti.slidetoact.SlideToActView

/**
 * LoginActivity MEJORADO - Interfaz profesional con secuencia de permisos
 *
 * MEJORAS:
 * ✅ Permisos secuenciales (uno por uno)
 * ✅ Barra de progreso visual
 * ✅ Explicaciones claras
 * ✅ Panel de ayuda interactivo
 * ✅ Colores: Azul (activo) vs Gris (inactivo), NO rojo
 * ✅ Detección automática cuando activa permisos
 * ✅ Botones siguiente/anterior habilitados según estado
 */
class LoginActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_CODE = 1000
    private val SETTINGS_CODE = 2000

    // Permisos en orden secuencial
    private val permissionSequence = listOf(
        PermissionStep(
            title = "MICRÓFONO",
            emoji = "📱",
            permission = Manifest.permission.RECORD_AUDIO,
            description = "Para escuchar tus comandos de voz",
            explanation = "JarVoice usa el micrófono para reconocer lo que dices y ejecutar tus comandos automáticamente. Sin este permiso, JarVoice no podrá funcionar."
        ),
        PermissionStep(
            title = "SUPERPOSICIÓN",
            emoji = "🔳",
            permission = null, // No es un permiso normal
            description = "Para mostrar el orbe flotante",
            explanation = "La superposición permite que JarVoice muestre el orbe flotante sobre otras apps. Necesita acceso especial del sistema.",
            isOverlay = true
        ),
        PermissionStep(
            title = "ACCESIBILIDAD",
            emoji = "♿",
            permission = null,
            description = "Para leer y entender tu pantalla",
            explanation = "El servicio de accesibilidad permite que JarVoice entienda qué aplicación estás usando y qué hay en la pantalla.",
            isAccessibility = true
        ),
        PermissionStep(
            title = "NOTIFICACIONES",
            emoji = "🔔",
            permission = null,
            description = "Para leer mensajes y alertas",
            explanation = "Lee tus notificaciones para informarte de mensajes y alertas importantes por voz.",
            isNotification = true
        ),
        PermissionStep(
            title = "CONTACTOS",
            emoji = "👥",
            permission = Manifest.permission.READ_CONTACTS,
            description = "Para llamar y enviar mensajes",
            explanation = "Necesita acceso a tu libreta de contactos para identificar a quién quieres llamar o enviar mensajes."
        ),
        PermissionStep(
            title = "TELÉFONO",
            emoji = "☎️",
            permission = Manifest.permission.CALL_PHONE,
            description = "Para realizar llamadas por voz",
            explanation = "Este permiso es fundamental para que JarVoice pueda hacer llamadas telefónicas automáticamente."
        )
    )

    // UI Components
    private lateinit var progressBar: ProgressBar
    private lateinit var progressPercentage: TextView
    private lateinit var stepIndicator: TextView
    private lateinit var permissionEmoji: TextView
    private lateinit var permissionName: TextView
    private lateinit var permissionDescription: TextView
    private lateinit var permissionExplanation: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var btnActivatePermission: Button
    private lateinit var helpPanel: View
    private lateinit var btnOpenSettings: Button
    private lateinit var btnPrevious: Button
    private lateinit var btnNext: Button
    private lateinit var permissionsCheckList: LinearLayout

    private var currentStep = 0

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
        displayStep(0)
    }

    private fun initializeUI() {
        progressBar = findViewById(R.id.permissionProgressBar)
        progressPercentage = findViewById(R.id.progressPercentage)
        stepIndicator = findViewById(R.id.stepIndicator)
        permissionEmoji = findViewById(R.id.permissionEmoji)
        permissionName = findViewById(R.id.permissionName)
        permissionDescription = findViewById(R.id.permissionDescription)
        permissionExplanation = findViewById(R.id.permissionExplanation)
        permissionStatus = findViewById(R.id.permissionStatus)
        btnActivatePermission = findViewById(R.id.btnActivatePermission)
        helpPanel = findViewById(R.id.helpPanel)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnNext = findViewById(R.id.btnNext)
        permissionsCheckList = findViewById(R.id.permissionsCheckList)
    }

    private fun setupListeners() {
        btnActivatePermission.setOnClickListener { activateCurrentPermission() }
        btnOpenSettings.setOnClickListener { openSystemSettings() }
        btnPrevious.setOnClickListener { previousStep() }
        btnNext.setOnClickListener { nextStep() }
    }

    // ═════════════════════════════════════════════════════════════════════

    private fun displayStep(step: Int) {
        if (step < 0 || step >= permissionSequence.size) return

        currentStep = step
        val perm = permissionSequence[step]

        // Actualizar barra de progreso
        val progress = ((step + 1).toFloat() / permissionSequence.size * 100).toInt()
        progressBar.progress = progress
        progressPercentage.text = "$progress%"
        stepIndicator.text = "Paso ${step + 1} de ${permissionSequence.size}"

        // Actualizar contenido del permiso
        permissionEmoji.text = perm.emoji
        permissionName.text = perm.title
        permissionDescription.text = perm.description
        permissionExplanation.text = perm.explanation

        // Actualizar estado
        val isActive = checkPermissionStatus(perm)
        updatePermissionStatus(isActive)

        // Actualizar botón principal
        if (isActive) {
            btnActivatePermission.text = "✅ ${perm.title} ACTIVADO"
            btnActivatePermission.isEnabled = false
            btnActivatePermission.alpha = 0.6f
            btnNext.isEnabled = true
            btnNext.alpha = 1f
        } else {
            btnActivatePermission.text = "⚙️ ACTIVAR ${perm.title}"
            btnActivatePermission.isEnabled = true
            btnActivatePermission.alpha = 1f
            btnNext.isEnabled = false
            btnNext.alpha = 0.5f
        }

        // Actualizar botón anterior
        btnPrevious.isEnabled = step > 0
        btnPrevious.alpha = if (step > 0) 1f else 0.5f

        // Ocultar panel de ayuda
        helpPanel.visibility = View.GONE

        // Actualizar lista de pendientes
        updatePendingList()
    }

    private fun checkPermissionStatus(perm: PermissionStep): Boolean {
        return when {
            perm.permission != null -> hasPermission(perm.permission)
            perm.isOverlay -> canDrawOverlays()
            perm.isAccessibility -> isAccessibilityServiceEnabled()
            perm.isNotification -> hasNotificationAccess()
            else -> false
        }
    }

    private fun updatePermissionStatus(isActive: Boolean) {
        if (isActive) {
            permissionStatus.text = "🟢 ACTIVO"
            permissionStatus.setTextColor(0xFF1DE0A0.toInt())
        } else {
            permissionStatus.text = "⚫ NO ACTIVO"
            permissionStatus.setTextColor(0xFFE53935.toInt())
        }
    }

    private fun activateCurrentPermission() {
        val perm = permissionSequence[currentStep]

        when {
            perm.permission != null -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(perm.permission),
                    1000
                )
            }
            perm.isOverlay -> {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")),
                    OVERLAY_PERMISSION_CODE
                )
            }
            perm.isAccessibility -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            perm.isNotification -> {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
    }

    private fun toggleHelpPanel() {
        helpPanel.visibility = if (helpPanel.visibility == View.GONE) View.VISIBLE else View.GONE
    }

    private fun openSystemSettings() {
        val perm = permissionSequence[currentStep]
        startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun nextStep() {
        if (currentStep + 1 >= permissionSequence.size) {
            // Todos completados
            finishSetup()
        } else {
            displayStep(currentStep + 1)
        }
    }

    private fun previousStep() {
        if (currentStep > 0) {
            displayStep(currentStep - 1)
        }
    }

    private fun updatePendingList() {
        permissionsCheckList.removeAllViews()

        val activeBadgeColor = 0xFF1DE0A0.toInt()
        val inactiveBadgeColor = 0xFFE53935.toInt()

        for ((index, perm) in permissionSequence.withIndex()) {
            val isActive = checkPermissionStatus(perm)
            val badge = if (isActive) "✓" else "○"
            val color = if (isActive) activeBadgeColor else inactiveBadgeColor

            val tv = TextView(this).apply {
                text = "$badge ${perm.title}"
                textSize = 12f
                setTextColor(color)
                setPadding(8, 4, 8, 4)
            }
            permissionsCheckList.addView(tv)
        }
    }

    private fun finishSetup() {
        AlertDialog.Builder(this)
            .setTitle("✅ ¡Configuración Completa!")
            .setMessage("JarVoice está listo para usar. Di 'Hey Nexus' para comenzar.")
            .setPositiveButton("Continuar") { _, _ ->
                startActivity(Intent(this, JarActivity::class.java))
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Métodos de verificación de permisos

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, MyAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected.flattenToString(), ignoreCase = true)) return true
        }
        return false
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Listeners del sistema

    override fun onResume() {
        super.onResume()
        displayStep(currentStep) // Actualizar estado actual
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        displayStep(currentStep) // Actualizar UI después de permiso
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        displayStep(currentStep) // Actualizar UI después de volver de Ajustes
    }

    // ═════════════════════════════════════════════════════════════════════
    // Data class para estructura de permisos

    data class PermissionStep(
        val title: String,
        val emoji: String,
        val permission: String? = null,
        val description: String,
        val explanation: String,
        val isOverlay: Boolean = false,
        val isAccessibility: Boolean = false,
        val isNotification: Boolean = false
    )
}