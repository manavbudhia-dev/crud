package com.papervault.scanner.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.papervault.scanner.LaunchRouter
import com.papervault.scanner.R
import com.papervault.scanner.ui.Routes

/** Home-screen widget with one-tap Scan, Import and Vault. */
class QuickScanWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_scan).apply {
            setOnClickPendingIntent(R.id.widget_scan, pending(context, Routes.SCAN, 10))
            setOnClickPendingIntent(R.id.widget_import, pending(context, Routes.IMPORT_PICKER, 11))
            setOnClickPendingIntent(R.id.widget_vault, pending(context, Routes.vault(), 13))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun pending(context: Context, route: String, code: Int) = PendingIntent.getActivity(
        context, code, LaunchRouter.routeIntent(context, route),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
