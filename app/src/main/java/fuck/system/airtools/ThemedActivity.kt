package fuck.system.airtools

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.rokkystudio.sharedapp.neoui.NeoThemeMode
import com.rokkystudio.sharedapp.neoui.NeoUiSettings
import fuck.system.airtools.databinding.TopBarBinding

abstract class ThemedActivity : Activity()
{
    private val uiSettings: NeoUiSettings by lazy {
        NeoUiSettings(
            context = applicationContext,
            preferencesName = PREFS_UI,
            defaultTheme = NeoThemeMode.LIGHT,
        )
    }

    override fun attachBaseContext(newBase: Context)
    {
        val configuration = Configuration(newBase.resources.configuration)
        NeoUiSettings(
            context = newBase,
            preferencesName = PREFS_UI,
            defaultTheme = NeoThemeMode.LIGHT,
        ).applyToConfiguration(configuration)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        setTheme(R.style.Theme_AIRTOOLS)
        super.onCreate(savedInstanceState)
    }

    protected fun setupTopBar(binding: TopBarBinding, title: String)
    {
        binding.backButton.visibility = android.view.View.GONE
        binding.neoTopBar.setAppInfo(
            title = title,
            subtitle = getString(R.string.app_name),
            version = BuildConfig.VERSION_NAME,
            iconResId = R.mipmap.ic_launcher,
        )
        uiSettings.bindTopBar(binding.neoTopBar) {
            uiSettings.applyAppCompatNightMode()
            recreate()
        }
    }

    companion object
    {
        private const val PREFS_UI = "airtools_ui"
    }
}