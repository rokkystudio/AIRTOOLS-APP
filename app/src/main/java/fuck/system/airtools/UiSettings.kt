package fuck.system.airtools

import android.content.Context
import com.rokkystudio.sharedapp.neoui.NeoLanguageDefaults

/** Stores the selected AIRTOOLS interface theme and language. */
class UiSettings(context: Context)
{
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getTheme(): AppTheme
    {
        return when (preferences.getString(KEY_THEME, null))
        {
            AppTheme.LIGHT.preferenceValue -> AppTheme.LIGHT
            AppTheme.DARK.preferenceValue -> AppTheme.DARK
            else -> AppTheme.DARK
        }
    }

    fun setTheme(theme: AppTheme)
    {
        preferences.edit().putString(KEY_THEME, theme.preferenceValue).commit()
    }

    fun getLanguage(): String
    {
        return NeoLanguageDefaults.selectedOrSystem(
            preferences.getString(KEY_LANGUAGE, null)
        )
    }

    fun setLanguage(language: String)
    {
        preferences.edit().putString(KEY_LANGUAGE, language).commit()
    }

    private companion object
    {
        const val PREFERENCES_NAME = "app_ui"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
    }
}

enum class AppTheme(val preferenceValue: String)
{
    LIGHT("light"),
    DARK("dark"),
}
