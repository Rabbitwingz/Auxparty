package app.musicremote

import android.content.Context

enum class ThemeMode { System, Light, Dark }

/** Small user preferences. Kept apart from [Identity], which must never be reset. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_ONBOARDED = "onboarding_done"
    }
}
