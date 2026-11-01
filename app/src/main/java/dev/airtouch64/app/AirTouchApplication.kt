package dev.airtouch64.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

class AirTouchApplication : Application() {
    override fun attachBaseContext(base: Context) {
        val language = base.resources.configuration.locales[0].language
        val locale = if (language == "pt") Locale.forLanguageTag("pt") else Locale.ENGLISH
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
        }
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }
}
