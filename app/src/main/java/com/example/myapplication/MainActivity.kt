package com.example.myapplication

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.activity.LoginActivity
import com.example.myapplication.activity.switchToActivity
import com.ncorti.slidetoact.SlideToActView
import android.view.animation.OvershootInterpolator
import android.animation.ValueAnimator
import android.content.Intent
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.myapplication.ui.JarvisOrbView
import com.example.myapplication.utils.ThemeUtils


class MainActivity : AppCompatActivity() {
    private lateinit var IngresoButton: SlideToActView
    private lateinit var orbView: JarvisOrbView
    private var isInitialLaunch = true
    private var pulseAnimator: ValueAnimator? = null
    private var isTransitioning = false  // evita doble-tap

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // Ejemplo: detectar si estamos en modo oscuro
        val isDark = ThemeUtils.isDarkMode(this)
        println("¿Modo oscuro activado? $isDark")

        // Ejemplo: cambiar tema cuando el usuario lo desee
        // setupThemeButton()
        IngresoButton = findViewById(R.id.Ingresobutton)
        orbView = findViewById(R.id.orbView)
        val textView = findViewById<TextView>(R.id.textView)
        val textViewSub = findViewById<TextView>(R.id.textViewSub)

        setupInitialState(textView, textViewSub)
        startWelcomeAnimation(textView, textViewSub)
        setupIngresoButton()
        setupWindowInsets()
    }

    private fun setupInitialState(textView: TextView, textViewSub: TextView) {
        IngresoButton.text = ""
        IngresoButton.translationY = -900f
        IngresoButton.alpha = 0f
        textViewSub.alpha = 0f
        textView.translationY = -600f
        textView.alpha = 0f
        orbView.scaleX = 0f
        orbView.scaleY = 0f
        orbView.alpha = 0f
    }

    private fun startWelcomeAnimation(textView: TextView, textViewSub: TextView) {
        orbView.animate()
            .scaleX(1.2f).scaleY(1.2f).alpha(1f)
            .setDuration(1200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withStartAction { startOrbAnimation() }
            .withEndAction {
                orbView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(800)
                    .setInterpolator(OvershootInterpolator(1.5f))
                    .withStartAction { showTextAndButton(textView, textViewSub) }
                    .start()
            }.start()
    }

    private fun showTextAndButton(textView: TextView, textViewSub: TextView) {
        textViewSub.animate().alpha(1f).setDuration(1000).start()
        textView.animate()
            .translationY(0f).alpha(1f)
            .setDuration(1000)
            .setInterpolator(OvershootInterpolator(1.0f))
            .start()

        IngresoButton.text = "Comenzar"
        IngresoButton.resetSlider()
        IngresoButton.animate()
            .translationY(0f).alpha(1f)
            .setDuration(800).setStartDelay(300)
            .setInterpolator(OvershootInterpolator(1.0f))
            .start()
    }

    private fun startOrbAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 8f, 0f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { orbView.updateRms(it.animatedValue as Float) }
            start()
        }
    }

    private fun setupIngresoButton() {
        IngresoButton.onSlideCompleteListener = object : SlideToActView.OnSlideCompleteListener {
            override fun onSlideComplete(view: SlideToActView) {
                if (isTransitioning) return
                isTransitioning = true

                pulseAnimator?.cancel()

                // Animación rápida simultánea: todo se desvanece a la vez
                val duration = 200L

                IngresoButton.animate()
                    .alpha(0f).scaleX(0.95f).scaleY(0.95f)
                    .setDuration(duration).start()

                orbView.animate()
                    .alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setDuration(duration).start()

                findViewById<TextView>(R.id.textView).animate()
                    .alpha(0f).translationY(-30f)
                    .setDuration(duration).start()

                findViewById<TextView>(R.id.textViewSub).animate()
                    .alpha(0f)
                    .setDuration(duration).start()

                // Navegar al terminar el fade (200ms = casi inmediato)
                IngresoButton.postDelayed({
                    val intent = Intent(this@MainActivity, LoginActivity::class.java)
                    // Sin animación de sistema — la nuestra ya hizo el trabajo visual
                    startActivity(intent)
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }, duration)
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (::IngresoButton.isInitialized && !isInitialLaunch) {
            isTransitioning = false
            resetToReadyState()
        }
        isInitialLaunch = false
    }

    private fun resetToReadyState() {
        val textView = findViewById<TextView>(R.id.textView)
        val textViewSub = findViewById<TextView>(R.id.textViewSub)
        IngresoButton.text = "Comenzar"
        IngresoButton.resetSlider()
        IngresoButton.alpha = 1f
        IngresoButton.translationY = 0f
        IngresoButton.scaleX = 1f
        IngresoButton.scaleY = 1f
        textViewSub.alpha = 1f
        textViewSub.translationY = 0f
        textView.alpha = 1f
        textView.translationY = 0f
        orbView.alpha = 1f
        orbView.scaleX = 1f
        orbView.scaleY = 1f
        orbView.reset()
        startOrbAnimation()
    }

    override fun onPause() {
        super.onPause()
        pulseAnimator?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }
}