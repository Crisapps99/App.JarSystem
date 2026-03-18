package com.example.myapplication.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity

object ThemeUtils {

    private const val PREFS_NAME = "nexus_theme_prefs"
    private const val THEME_KEY = "selected_theme"

    enum class ThemeMode {
        LIGHT,
        DARK,
        SYSTEM
    }

    /**
     * Aplicar el tema al iniciar la aplicación
     * Llamar en Application.onCreate() o en la Activity principal
     */
    fun applyTheme(context: Context) {
        val savedTheme = getSavedTheme(context)
        when (savedTheme) {
            ThemeMode.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            ThemeMode.DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            ThemeMode.SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /**
     * Cambiar el tema dinámicamente
     * El cambio se aplicará después de recrear la Activity
     */
    fun setTheme(activity: AppCompatActivity, theme: ThemeMode) {
        saveTheme(activity, theme)
        when (theme) {
            ThemeMode.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            ThemeMode.DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            ThemeMode.SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        // Recrear la activity para aplicar los cambios de tema
        activity.recreate()
    }

    /**
     * Obtener el tema actualmente guardado
     */
    fun getSavedTheme(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeValue = prefs.getString(THEME_KEY, ThemeMode.SYSTEM.name)
        return ThemeMode.valueOf(themeValue ?: ThemeMode.SYSTEM.name)
    }

    /**
     * Guardar la preferencia de tema
     */
    private fun saveTheme(context: Context, theme: ThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(THEME_KEY, theme.name).apply()
    }

    /**
     * Verificar si el dispositivo está en modo oscuro
     */
    fun isDarkMode(context: Context): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Obtener el color basado en el tema actual
     */
    fun getColorByTheme(context: Context, darkColor: Int, lightColor: Int): Int {
        return if (isDarkMode(context)) darkColor else lightColor
    }
}