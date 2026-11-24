package uz.tiyin

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object Language {
    val options = listOf("en" to "English", "ru" to "Русский", "uz" to "O'zbekcha")

    private const val PREFS = "language"
    private const val KEY = "tag"

    /** The language the strings are shown in; any language without its own resources falls back to Russian. */
    fun shown(config: Configuration): String =
        config.locales[0].language.takeIf { lang -> options.any { it.first == lang } } ?: "ru"

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system keeps the choice, shows it in the app's language settings and recreates the activity.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /** Before Android 13 there is no per-app language, so the activity's context carries the saved one. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }
}
