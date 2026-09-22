package fuck.system.airtools

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import fuck.system.airtools.databinding.TopBarBinding
import java.util.Locale

abstract class ThemedActivity : Activity()
{
    override fun attachBaseContext(newBase: Context)
    {
        val prefs = newBase.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
        val language = prefs.getString(KEY_LANGUAGE, null)
            ?: if (Locale.getDefault().language == "ru") "ru" else "en"
        val dark = prefs.getBoolean(KEY_DARK_THEME, false)
        val configuration = Configuration(newBase.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        val nightMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        setTheme(R.style.Theme_AIRTOOLS)
        super.onCreate(savedInstanceState)
    }

    protected fun setupTopBar(binding: TopBarBinding, title: String)
    {
        binding.titleTextView.text = title
        renderTopBarButtons(binding)
        binding.themeToggleButton.setOnClickListener {
            getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
                .putBoolean(KEY_DARK_THEME, !isDarkTheme())
                .apply()
            recreate()
        }
        binding.languageFlag.setOnClickListener {
            getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, if (currentLanguage() == "ru") "en" else "ru")
                .apply()
            recreate()
        }
    }

    private fun renderTopBarButtons(binding: TopBarBinding)
    {
        binding.themeToggleButton.setImageResource(
            if (isDarkTheme()) R.drawable.theme_moon else R.drawable.theme_sun
        )
        binding.languageFlag.setImageResource(
            if (currentLanguage() == "ru") R.drawable.flag_ru else R.drawable.flag_us
        )
    }

    private fun isDarkTheme(): Boolean =
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).getBoolean(KEY_DARK_THEME, false)

    private fun currentLanguage(): String =
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).getString(KEY_LANGUAGE, null)
            ?: if (Locale.getDefault().language == "ru") "ru" else "en"

    companion object
    {
        private const val PREFS_UI = "airtools_ui"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_LANGUAGE = "language"
    }
}
