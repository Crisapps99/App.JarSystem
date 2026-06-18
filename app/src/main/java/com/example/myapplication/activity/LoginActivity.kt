package com.example.myapplication.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.myapplication.viewmodel.PassVm

class LoginActivity : ComponentActivity() {

    // Obtenemos el ViewModel que creamos antes
    private val vm: PassVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // setContent reemplaza a setContentView(R.layout...)
        setContent {
            LoginScreen(
                vm = vm,
                onFinish = {
                    // Acción cuando todos los permisos están listos y pulsan el botón
                    startActivity(Intent(this, JarActivity::class.java))
                    finish()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Cuando el usuario vuelve de la pantalla de Ajustes de Android,
        // le pedimos al ViewModel que refresque el estado de los checks.
        vm.checkPermissions(this)
    }
}