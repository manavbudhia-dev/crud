package com.papervault.scanner

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something the app was asked to do from outside: open a screen, or import shared files. */
sealed interface Launch {
    data class Route(val route: String) : Launch
    data class Import(val uris: List<Uri>, val mimes: List<String?>) : Launch
}

/**
 * Hands requests from intents (Open with / Share to PaperVault, widget, quick-settings tile)
 * to the navigation layer. Held until consumed, so it survives onboarding and recreation.
 */
class LaunchRouter {
    private val _pending = MutableStateFlow<Launch?>(null)
    val pending: StateFlow<Launch?> = _pending.asStateFlow()

    fun submit(launch: Launch) { _pending.value = launch }

    fun consume(): Launch? = _pending.value.also { _pending.value = null }

    fun handle(intent: Intent?, resolveType: (Uri) -> String?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { _pending.value = Launch.Route(it); return }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.stream())
            Intent.ACTION_SEND_MULTIPLE -> intent.streams()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        _pending.value = Launch.Import(uris, uris.map { intent.type?.takeIf { t -> !t.endsWith("/*") } ?: resolveType(it) })
    }

    @Suppress("DEPRECATION")
    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streams(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    companion object {
        const val EXTRA_ROUTE = "com.papervault.scanner.ROUTE"

        /** Intent that opens [route] in the app (used by the widget and the tile). */
        fun routeIntent(context: android.content.Context, route: String) =
            Intent(context, MainActivity::class.java)
                .setAction("com.papervault.scanner.OPEN_$route")
                .putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
