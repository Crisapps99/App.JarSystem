package com.example.myapplication.activity

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginActivity : AppCompatActivity() {
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
            switchToActivity(
                context=this@LoginActivity,
                destinationActivity = JarActivity::class.java,
                finishCurrent = false
            )
        }
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