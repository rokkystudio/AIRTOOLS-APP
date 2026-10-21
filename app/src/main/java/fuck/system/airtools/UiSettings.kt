package fuck.system.airtools

import android.content.Context
import badfox.logic.neoui.NeoLanguageDefaults

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

    /**
     * Returns the persisted interface language. On first launch the supported system language
     * is selected once and immediately stored, so later system-language changes do not override it.
     */
    fun getLanguage(): String
    {
        preferences.getString(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val initialLanguage = NeoLanguageDefaults.currentSupportedSystemLanguage()
        preferences.edit().putString(KEY_LANGUAGE, initialLanguage).commit()
        return initialLanguage
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
