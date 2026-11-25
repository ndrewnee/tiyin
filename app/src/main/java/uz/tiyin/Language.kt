package uz.tiyin

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

object Language {
    val options = listOf("en" to "English", "ru" to "Русский", "uz" to "O'zbekcha")

    private const val PREFS = "language"
    private const val KEY = "tag"
    private const val INSTALL = "install"

    /** The language the strings are shown in; any language without its own resources falls back to Russian. */
    fun shown(config: Configuration): String =
        config.locales[0].language.takeIf { lang -> options.any { it.first == lang } } ?: "ru"

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            remember(activity, tag)
            // The system keeps the choice, shows it in the app's language settings and recreates the activity.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /**
     * Android does not restore the per-app language on reinstall, but it does restore the app's preferences,
     * so a copy of the choice is kept there. An empty system setting on the same install means the user picked
     * the default in the system's app settings, which wins.
     */
    fun restore(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = context.getSystemService(LocaleManager::class.java)
        val current = manager.applicationLocales
        if (!current.isEmpty) return remember(context, current[0].toLanguageTag())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString(KEY, null) ?: return
        if (prefs.getLong(INSTALL, 0) == installedAt(context)) {
            prefs.edit().remove(KEY).apply()
            return
        }
        remember(context, tag)
        manager.applicationLocales = LocaleList.forLanguageTags(tag)
    }

    @RequiresApi(33)
    private fun remember(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, tag).putLong(INSTALL, installedAt(context)).apply()
    }

    @RequiresApi(33)
    private fun installedAt(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).firstInstallTime

    /** Before Android 13 there is no per-app language, so the activity's context carries the saved one. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }
}
