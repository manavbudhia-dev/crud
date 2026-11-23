package com.papervault.scanner.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.papervault.scanner.ui.detail.DocumentDetailScreen
import com.papervault.scanner.ui.home.HomeScreen
import com.papervault.scanner.ui.viewer.PdfViewerScreen

object Routes {
    const val HOME = "home"
    const val DETAIL = "doc/{id}"
    const val VIEWER = "doc/{id}/view?page={page}"
    fun detail(id: Long) = "doc/$id"
    fun viewer(id: Long, page: Int = 0) = "doc/$id/view?page=$page"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onOpenDocument = { nav.navigate(Routes.detail(it)) })
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            DocumentDetailScreen(
                docId = entry.arguments!!.getLong("id"),
                onBack = { nav.popBackStack() },
                onOpenPage = { id, page -> nav.navigate(Routes.viewer(id, page)) },
            )
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("page") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            PdfViewerScreen(
                docId = entry.arguments!!.getLong("id"),
                initialPage = entry.arguments!!.getInt("page"),
                onBack = { nav.popBackStack() },
            )
        }
    }
}
