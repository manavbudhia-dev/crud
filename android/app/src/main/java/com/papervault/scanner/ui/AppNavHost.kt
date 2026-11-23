package com.papervault.scanner.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.papervault.scanner.ui.detail.DocumentDetailScreen
import com.papervault.scanner.ui.editor.PageEditorScreen
import com.papervault.scanner.ui.home.HomeScreen
import com.papervault.scanner.ui.tools.PdfTool
import com.papervault.scanner.ui.tools.PdfToolScreen
import com.papervault.scanner.ui.viewer.PdfViewerScreen

object Routes {
    const val HOME = "home"
    fun detail(id: Long) = "doc/$id"
    fun viewer(id: Long, page: Int = 0) = "doc/$id/view?page=$page"
    fun editor(id: Long) = "doc/$id/edit"
    fun tool(tool: PdfTool, docId: Long? = null) = "tool/${tool.name}?doc=${docId ?: -1}"
}

private val idArg = navArgument("id") { type = NavType.LongType }

@Composable
fun AppNavHost(nav: NavHostController = rememberNavController()) {
    val openDoc: (Long) -> Unit = { id ->
        nav.navigate(Routes.detail(id)) { launchSingleTop = true }
    }
    val navigate: (String) -> Unit = { nav.navigate(it) }
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onOpenDocument = openDoc, onNavigate = navigate)
        }
        composable("doc/{id}", arguments = listOf(idArg)) { e ->
            DocumentDetailScreen(
                docId = e.arguments!!.getLong("id"),
                onBack = back,
                onOpenPage = { id, page -> nav.navigate(Routes.viewer(id, page)) },
                onNavigate = navigate,
            )
        }
        composable(
            "doc/{id}/view?page={page}",
            arguments = listOf(idArg, navArgument("page") { type = NavType.IntType; defaultValue = 0 }),
        ) { e ->
            PdfViewerScreen(e.arguments!!.getLong("id"), e.arguments!!.getInt("page"), onBack = back)
        }
        composable("doc/{id}/edit", arguments = listOf(idArg)) { e ->
            PageEditorScreen(e.arguments!!.getLong("id"), onBack = back, onOpenDocument = openDoc)
        }
        composable(
            "tool/{tool}?doc={doc}",
            arguments = listOf(
                navArgument("tool") { type = NavType.StringType },
                navArgument("doc") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { e ->
            PdfToolScreen(
                tool = PdfTool.valueOf(e.arguments!!.getString("tool")!!),
                docId = e.arguments!!.getLong("doc").takeIf { it >= 0 },
                onBack = back,
                onOpenDocument = openDoc,
            )
        }
    }
}
