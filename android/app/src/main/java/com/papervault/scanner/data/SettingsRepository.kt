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
    val profileName: String = "",
    val profileEmail: String = "",
    val notifications: Boolean = true,
    val documentsGrid: Boolean = false,
    val ocrScript: com.papervault.scanner.convert.OcrScript = com.papervault.scanner.convert.OcrScript.LATIN,
) {
    val initial: String get() = profileName.trim().firstOrNull()?.uppercase() ?: "P"
}

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
        profileName = prefs.getString("profile_name", "").orEmpty(),
        profileEmail = prefs.getString("profile_email", "").orEmpty(),
        notifications = prefs.getBoolean("notifications", true),
        documentsGrid = prefs.getBoolean("documents_grid", false),
        ocrScript = enumOr(prefs.getString("ocr_script", null), com.papervault.scanner.convert.OcrScript.LATIN),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("theme", s.theme.name)
            .putString("page_size", s.pageSize.name)
            .putString("sort", s.sort.name)
            .putBoolean("onboarded", s.onboarded)
            .putString("profile_name", s.profileName)
            .putString("profile_email", s.profileEmail)
            .putBoolean("notifications", s.notifications)
            .putBoolean("documents_grid", s.documentsGrid)
            .putString("ocr_script", s.ocrScript.name)
            .apply()
        _settings.value = s
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
