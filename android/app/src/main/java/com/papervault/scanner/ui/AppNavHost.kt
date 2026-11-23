package com.papervault.scanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.papervault.scanner.ui.home.DocumentsMode
import com.papervault.scanner.ui.home.DocumentsScreen
import com.papervault.scanner.ui.main.PaperBottomBar
import com.papervault.scanner.ui.main.Tab
import com.papervault.scanner.ui.scan.ScanScreen
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.papervault.scanner.ui.detail.DocumentDetailScreen
import com.papervault.scanner.ui.edit.AnnotateScreen
import com.papervault.scanner.ui.edit.MaskScreen
import com.papervault.scanner.ui.editor.PageEditorScreen
import com.papervault.scanner.ui.home.HomeScreen
import com.papervault.scanner.ui.settings.SettingsScreen
import com.papervault.scanner.ui.vault.VaultScreen
import com.papervault.scanner.convert.OutputFormat
import com.papervault.scanner.ui.tools.AllToolsScreen
import com.papervault.scanner.ui.tools.ConvertScreen
import com.papervault.scanner.ui.tools.ImportConvertScreen
import com.papervault.scanner.ui.tools.ImportKind
import com.papervault.scanner.ui.tools.PdfTool
import com.papervault.scanner.ui.tools.PdfToolScreen
import com.papervault.scanner.ui.viewer.PdfViewerScreen

object Routes {
    const val HOME = "home"
    const val ALL_TOOLS = "tools"
    const val SEARCH = "search"
    const val SCAN = "scan"
    const val SETTINGS = "settings"
    fun vault(moveDocId: Long? = null) = "vault?move=${moveDocId ?: -1}"
    fun convert(id: Long, fmt: OutputFormat? = null) = "doc/$id/convert?fmt=${fmt?.name ?: ""}"
    fun importConvert(kind: ImportKind) = "import/${kind.name}"
    fun detail(id: Long) = "doc/$id/pages"
    fun open(id: Long) = "doc/$id/view?page=0"
    fun viewer(id: Long, page: Int = 0) = "doc/$id/view?page=$page"
    fun editor(id: Long) = "doc/$id/edit"
    fun mask(id: Long) = "doc/$id/mask"
    fun annotate(id: Long, page: Int = 0) = "doc/$id/annotate?page=$page"
    fun tool(tool: PdfTool, docId: Long? = null) = "tool/${tool.name}?doc=${docId ?: -1}"
}

private val idArg = navArgument("id") { type = NavType.LongType }

@Composable
fun AppNavHost(nav: NavHostController = rememberNavController()) {
    val openDoc: (Long) -> Unit = { id ->
        nav.navigate(Routes.open(id)) { launchSingleTop = true }
    }
    val selectTab: (Tab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val navigate: (String) -> Unit = { route ->
        Tab.entries.firstOrNull { it.route == route }?.let(selectTab) ?: nav.navigate(route)
    }
    val back: () -> Unit = { nav.popBackStack() }
    val entry by nav.currentBackStackEntryAsState()
    val currentTab = Tab.entries.firstOrNull { it.route == entry?.destination?.route }

    Box(Modifier.fillMaxSize()) {
    val tabRoutes = Tab.entries.map { it.route }.toSet()
    fun isTab(e: androidx.navigation.NavBackStackEntry) = e.destination.route in tabRoutes
    val ease = androidx.compose.animation.core.FastOutSlowInEasing
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        // Tabs cross-fade; pushed screens slide in from the right with a slight parallax.
        enterTransition = {
            if (isTab(initialState) && isTab(targetState)) fadeIn(tween(220))
            else slideInHorizontally(tween(320, easing = ease)) { it / 3 } + fadeIn(tween(260))
        },
        exitTransition = {
            if (isTab(initialState) && isTab(targetState)) fadeOut(tween(160))
            else slideOutHorizontally(tween(320, easing = ease)) { -it / 6 } + fadeOut(tween(220))
        },
        popEnterTransition = { slideInHorizontally(tween(300, easing = ease)) { -it / 6 } + fadeIn(tween(240)) },
        popExitTransition = { slideOutHorizontally(tween(300, easing = ease)) { it / 3 } + fadeOut(tween(200)) },
    ) {
        composable(Tab.HOME.route) {
            HomeScreen(onOpenDocument = openDoc, onNavigate = navigate)
        }
        composable(Tab.DOCUMENTS.route) { DocumentsScreen(DocumentsMode.ALL, openDoc, navigate) }
        composable(Tab.STARRED.route) { DocumentsScreen(DocumentsMode.STARRED, openDoc, navigate) }
        composable(Tab.TOOLS.route) { AllToolsScreen(onNavigate = navigate) }
        composable(Routes.SEARCH) { DocumentsScreen(DocumentsMode.SEARCH, openDoc, navigate, onBack = back) }
        composable(
            Routes.SCAN,
            enterTransition = { androidx.compose.animation.slideInVertically(tween(320, easing = ease)) { it } + fadeIn(tween(200)) },
            popExitTransition = { androidx.compose.animation.slideOutVertically(tween(280, easing = ease)) { it } + fadeOut(tween(200)) },
        ) {
            ScanScreen(onClose = back, onCreated = { id -> nav.popBackStack(); openDoc(id) })
        }
        composable("doc/{id}/pages", arguments = listOf(idArg)) { e ->
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
            PdfViewerScreen(e.arguments!!.getLong("id"), e.arguments!!.getInt("page"), onBack = back, onNavigate = navigate)
        }
        composable("doc/{id}/edit", arguments = listOf(idArg)) { e ->
            PageEditorScreen(e.arguments!!.getLong("id"), onBack = back, onOpenDocument = openDoc)
        }
        composable("doc/{id}/mask", arguments = listOf(idArg)) { e ->
            MaskScreen(e.arguments!!.getLong("id"), onBack = back, onOpenDocument = openDoc)
        }
        composable(
            "doc/{id}/annotate?page={page}",
            arguments = listOf(idArg, navArgument("page") { type = NavType.IntType; defaultValue = 0 }),
        ) { e ->
            AnnotateScreen(e.arguments!!.getLong("id"), e.arguments!!.getInt("page"), onBack = back)
        }
        composable("vault?move={move}", arguments = listOf(navArgument("move") { type = NavType.LongType; defaultValue = -1L })) { e ->
            VaultScreen(
                moveDocId = e.arguments!!.getLong("move").takeIf { it >= 0 },
                onBack = back,
                onOpenDocument = { nav.navigate(Routes.open(it)) },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = back, onOpenVault = { nav.navigate(Routes.vault()) }) }
        composable(
            "doc/{id}/convert?fmt={fmt}",
            arguments = listOf(idArg, navArgument("fmt") { type = NavType.StringType; defaultValue = "" }),
        ) { e ->
            ConvertScreen(
                docId = e.arguments!!.getLong("id"),
                initial = e.arguments!!.getString("fmt")?.takeIf { it.isNotEmpty() }?.let { OutputFormat.valueOf(it) },
                onBack = back,
                onOpenDocument = openDoc,
            )
        }
        composable("import/{kind}", arguments = listOf(navArgument("kind") { type = NavType.StringType })) { e ->
            ImportConvertScreen(ImportKind.valueOf(e.arguments!!.getString("kind")!!), onBack = back, onOpenDocument = openDoc)
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
    if (currentTab != null) {
        PaperBottomBar(currentTab, selectTab, Modifier.align(Alignment.BottomCenter))
    }
    }
}
