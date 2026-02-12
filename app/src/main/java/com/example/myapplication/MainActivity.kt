package com.example.myapplication

import android.animation.Animator
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.RenderMode
import com.example.myapplication.R
import com.example.myapplication.activity.JarActivity
//import com.example.myapplication.activity.TrainActivity
import com.example.myapplication.activity.switchToActivity
import com.ncorti.slidetoact.SlideToActView
import android.view.animation.OvershootInterpolator
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.myapplication.activity.LoginActivity
import com.example.myapplication.ui.JarvisOrbView


class MainActivity : AppCompatActivity() {
    private lateinit var IngresoButton: SlideToActView
    private lateinit var orbView: JarvisOrbView
    private var isInitialLaunch = true
    private var pulseAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        //inicializacion Vistas
        IngresoButton = findViewById(R.id.Ingresobutton)
        orbView = findViewById(R.id.orbView)
        val textView = findViewById<TextView>(R.id.textView)
        val textViewSub = findViewById<TextView>(R.id.textViewSub)

        setupInitialState(textView, textViewSub)
        startWelcomeAnimation(textView, textViewSub)
        setupIngresoButton()
        setupWindowInsets()
    }
    private fun setupInitialState(textView: TextView, textViewSub: TextView){
        IngresoButton.text=""
        IngresoButton.translationY=-900f
        IngresoButton.alpha = 0f

        textViewSub.alpha = 0f
        textView.translationY = -600f
        textView.alpha = 0f

        orbView.scaleX= 0f
        orbView.scaleY= 0f
        orbView.alpha = 0f
    }
    private fun startWelcomeAnimation(textView: TextView, textViewSub: TextView){
        orbView.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(1f)
            .setDuration(1200)
            .setInterpolator (AccelerateDecelerateInterpolator())
            .withStartAction {
                startOrbAnimation()
            }
            .withEndAction {
                orbView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(800)
                    .setInterpolator (OvershootInterpolator(1.5f))
                    .withStartAction {
                        showTextAndButton(textView, textViewSub)
                    }
                    .start()
            }
            .start()
    }
    private fun showTextAndButton(textView: TextView, textViewSub: TextView){
        //anim sub
        textViewSub.animate()
            .alpha(1f)
            .setDuration(1000)
            .start()

        textView.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(1000)
            .setInterpolator ( OvershootInterpolator(1.0f) )
            .start()

        //btn con delay
        IngresoButton.text="Comenzatr"
        IngresoButton.resetSlider()
        IngresoButton.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(300)
            .setInterpolator (OvershootInterpolator(1.0f))
            .start()
    }
    private fun startOrbAnimation(){

        pulseAnimator = ValueAnimator.ofFloat(0f, 8f, 0f).apply{
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                orbView.updateRms(value)
            }
            start()
        }
    }
    private fun setupIngresoButton() {
        IngresoButton.onSlideCompleteListener = object : SlideToActView.OnSlideCompleteListener {
            override fun onSlideComplete(view: SlideToActView) {
                //animaricono si es animated
                val mic = IngresoButton.completeIcon
                if (mic is android.graphics.drawable.AnimatedVectorDrawable) {
                    mic.start()
                }// Fade out del botón y orbe
                IngresoButton.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .start()

                orbView.animate()
                    .alpha(0f)
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .setDuration(300)
                    .start()
// Ir a la siguiente pantalla
                IngresoButton.postDelayed({
                    pulseAnimator?.cancel()
                    switchToActivity(
                        context = this@MainActivity,
                        destinationActivity = LoginActivity::class.java,
                        finishCurrent = true
                    )
                }, 3000)
            }
        }
    }
    private fun setupWindowInsets(){
        ViewCompat.setOnApplyWindowInsetsListener( findViewById(R.id.main)){v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
    override fun onResume() {
        super.onResume()
        if (::IngresoButton.isInitialized && !isInitialLaunch){
            resetToReadyState()
        }
        isInitialLaunch = false
    }
    private fun resetToReadyState(){
        val textView = findViewById<TextView>(R.id.textView)
        val textViewSub = findViewById<TextView>(R.id.textViewSub)

        // Resetear botón
        IngresoButton.text = "Comenzar"
        IngresoButton.resetSlider()
        IngresoButton.alpha = 1f
        IngresoButton.translationY = 0f

        // Resetear textos
        textViewSub.alpha = 1f
        textViewSub.translationY = 0f
        textView.alpha = 1f
        textView.translationY = 0f

        // Resetear orbe
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

//    private lateinit var  Ingresobutton: SlideToActView
//
//    private var isInitialLaunch = true
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
//        setContentView(R.layout.activity_main)
//        Ingresobutton = findViewById(R.id.Ingresobutton)
//        Ingresobutton.text = ""
//        val lottieF =findViewById<LottieAnimationView>(R.id.lottieFondo)
//        val lottie =findViewById<LottieAnimationView>(R.id.lottieLog)
//        val textView=findViewById<TextView>(R.id.textView)
//        val textViewSub=findViewById<TextView>(R.id.textViewSub)
//        //iniciar botone fuera de la panalla
//        Ingresobutton.translationY=-900f
//        lottieF.alpha=0f
////        btnEntrenar.translationY=300f
//        Ingresobutton.alpha=0f
////        btnEntrenar.alpha=0f
//
//        textViewSub.alpha=0f
//        textView.translationY=-600f
//        textView.alpha=0f
//
//
//        lottie.setAnimation(R.raw.eyejar)
//        lottie.setRenderMode(RenderMode.HARDWARE)
//        lottie.buildDrawingCache(true)
//
//        lottie.translationX = 0f   // entra desde la izquierda
//        lottie.translationY = 550f
//        lottie.alpha = 0f
//
//                lottie.animate()
//                    .translationX(0f) // Mover al centro (asumiendo que 0f es el centro vertical deseado para el zoom)
//                    .scaleX(1f)    // Aumentar tamaño (1.5x)
//                    .scaleY(1f)
//                    .alpha(1f)
//                    .setDuration(4200) // Duración de la entrada y zoom
//                    .withStartAction {
//                        lottie.playAnimation()
//                    }
//
//                    .withEndAction {
//
//                                // ETAPA 2: Reducir al tamaño normal y mover a la posición final del layout.
//                                lottie.animate()
//                                    .scaleX(1f) // Reducir a tamaño normal
//                                    .scaleY(1f)
//                                    .translationY(0f) // Mover a la posición final (coincide con 0f si el layout está centrado)
//                                    .setDuration(800) // Duración de la reducción
//                                    .setInterpolator(OvershootInterpolator(1.0f))
//                                    .withEndAction {
//                                        // ETAPA 3: Iniciar las animaciones del texto y el botón
//
//                                        Ingresobutton.resetSlider()
//                                        Ingresobutton.text = "Comenzar"
//
//                                        // Animación del fondo (lottieF)
//                                        lottieF.animate()
//                                            .alpha(0.7f)
//                                            .setDuration(900)
//                                            .start()
//
//                                        // Animación del texto JARVOICE (rebote)
//                                        textViewSub.animate()
//                                            .alpha(1f)
//                                            .setDuration(4000)
//                                            .start()
//                                        textView.animate()
//                                            .translationY(0f)
//                                            .alpha(1f)
//                                            .setDuration(1000)
//                                            .setInterpolator(OvershootInterpolator(1.0f))
//                                            .start()
//
//                                        // Animación del botón COMENZAR (rebote)
//                                        Ingresobutton.animate()
//                                            .translationY(0f)
//                                            .alpha(1f)
//                                            .setDuration(800)
//                                            .setStartDelay(300) // Pequeño retraso para que entre después del texto
//                                            .setInterpolator(OvershootInterpolator(1.0f))
//                                            .start()
//                                    }
//                                    .start()
//                            }
//                     .start()
//
//
//
//
//       Ingresobutton.onSlideCompleteListener = object : com.ncorti.slidetoact.SlideToActView.OnSlideCompleteListener {
//           override fun onSlideComplete(view: SlideToActView) {
//               val mic = Ingresobutton.completeIcon
//               if (mic is android.graphics.drawable.AnimatedVectorDrawable) {
//                   mic.start()
//               }
//               Ingresobutton.animate()
//                   .alpha(0f)
//                   .setDuration(300)
//                   .withEndAction {
//                       Ingresobutton.clearAnimation()
//                   }
//                   .start()
//               // Ir a la siguiente pantalla con un pequeño delay
//               Ingresobutton.postDelayed({
//                   switchToActivity(
//                       context = this@MainActivity,
//                       destinationActivity = JarActivity::class.java,
//                       finishCurrent = false
//                   )
//               }, 300)
//           }
//       }
//
////        btnEntrenar.setOnClickListener {
////            switchToActivity(
////                context = this, destinationActivity = TrainActivity::class.java,
////                )
////        }
//
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }
//    }

//    override fun onResume() {
//        super.onResume()
//        if (::Ingresobutton.isInitialized && !isInitialLaunch){
//            // 4. Mostrar Shimmer si regresamos de la actividad
//
//            Ingresobutton.text = "Comenzar"
//            Ingresobutton.resetSlider()
//            // No necesitamos cambiar el texto del Ingresobutton porque está vacío.
//            Ingresobutton.alpha=1f
//            Ingresobutton.translationY=0f
//
//            // Resetear TextView (el texto JARVOICE) al regresar
//            val textViewSub=findViewById<TextView>(R.id.textViewSub)
//            val textView=findViewById<TextView>(R.id.textView)
//            textViewSub.translationY=0f
//            textViewSub.alpha=1f
//            textView.translationY = 0f
//            textView.alpha = 1f
//
//        }
//        isInitialLaunch=false
//    }
}