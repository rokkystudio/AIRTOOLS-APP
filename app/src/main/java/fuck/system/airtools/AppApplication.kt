package fuck.system.airtools

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Applies saved interface settings before activities are created. */
class AppApplication : Application()
{
    override fun onCreate()
    {
        super.onCreate()
        applySavedUiSettings()
    }

    private fun applySavedUiSettings()
    {
        val uiSettings = UiSettings(this)
        AppCompatDelegate.setDefaultNightMode(
            when (uiSettings.getTheme())
            {
                AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(uiSettings.getLanguage())
        )
    }
}
