package com.example.myapplication.viewmodel

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.example.myapplication.service.MyAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LoginState(
    val micOk: Boolean = false,
    val overlayOk: Boolean = false,
    val accessOk: Boolean = false,
    val notifyOk: Boolean = false,
    val contactsOk: Boolean = false,
    val phoneOk: Boolean = false,
    val cameraOk: Boolean = false,
    val canContinue: Boolean = false,
    val locationOk: Boolean = false,
)

class PassVm : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun checkPermissions(context: Context) {
        val mic = hasPerm(context, Manifest.permission.RECORD_AUDIO)
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        val access = isAccessEnabled(context)
        val notify = isNotifyEnabled(context)
        val contacts = hasPerm(context, Manifest.permission.READ_CONTACTS)
        val phone = hasPerm(context, Manifest.permission.CALL_PHONE)
        val camera = hasPerm(context, Manifest.permission.CAMERA)
        val location = hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION)

        val allReady = mic && overlay && access && notify && contacts && phone && camera

        _state.update { it.copy(
            micOk = mic,
            overlayOk = overlay,
            accessOk = access,
            notifyOk = notify,
            contactsOk = contacts,
            phoneOk = phone,
            cameraOk = camera,
            locationOk = location,
            canContinue = allReady
        )}
    }
    fun checkLocationPermission(context: Context): Boolean {
        val location = hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION)
        _state.update { it.copy(locationOk = location) }
        return location
    }
    private fun hasPerm(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun isAccessEnabled(context: Context): Boolean {
        val expected = ComponentName(context, MyAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isNotifyEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(context.packageName) == true
    }
}