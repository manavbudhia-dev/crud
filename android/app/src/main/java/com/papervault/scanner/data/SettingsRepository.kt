package com.papervault.scanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }
enum class SortOrder(val label: String) { RECENT("Recently updated"), NAME("Name"), SIZE("Size"), CREATED("Date created") }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val pageSize: PageSize = PageSize.A4,
    val sort: SortOrder = SortOrder.RECENT,
    val onboarded: Boolean = false,
)

/** Small app preferences backed by SharedPreferences, exposed as a StateFlow. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load() = Settings(
        theme = enumOr(prefs.getString("theme", null), ThemeMode.SYSTEM),
        pageSize = enumOr(prefs.getString("page_size", null), PageSize.A4),
        sort = enumOr(prefs.getString("sort", null), SortOrder.RECENT),
        onboarded = prefs.getBoolean("onboarded", false),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("theme", s.theme.name)
            .putString("page_size", s.pageSize.name)
            .putString("sort", s.sort.name)
            .putBoolean("onboarded", s.onboarded)
            .apply()
        _settings.value = s
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
