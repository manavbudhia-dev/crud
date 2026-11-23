package com.papervault.scanner

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.ui.AppNavHost
import com.papervault.scanner.ui.theme.PaperVaultTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the main screens to build/screenshots for visual review (not a pass/fail check). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun seed() {
        val app = ApplicationProvider.getApplicationContext<PaperVaultApp>()
        app.container.settings.update { it.copy(onboarded = true, profileName = "Alex Morgan", profileEmail = "alex@example.com") }
        val now = System.currentTimeMillis()
        runBlocking {
            val repo = app.container.documents
            listOf(
                Document(title = "Project Proposal 2025", filePath = "/x/1.pdf", pageCount = 6, sizeBytes = 2_400_000, source = DocumentSource.IMPORT, updatedAt = now - 2 * 3600_000),
                Document(title = "Meeting Notes", filePath = "/x/2.pdf", pageCount = 2, sizeBytes = 856_000, source = DocumentSource.CONVERT, updatedAt = now - 26 * 3600_000, starred = true),
                Document(title = "Design Mockup", filePath = "/x/3.pdf", pageCount = 1, sizeBytes = 3_100_000, source = DocumentSource.PHOTOS, updatedAt = now - 50 * 3600_000),
                Document(title = "Invoice_1125", filePath = "/x/4.pdf", pageCount = 1, sizeBytes = 742_000, source = DocumentSource.SCAN, updatedAt = now - 74 * 3600_000),
            ).forEach { repo.insertRaw(it) }
        }
    }

    private fun shot(route: String?, name: String) {
        seed()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val nav = rememberNavController()
            PaperVaultTheme(dark = false) { AppNavHost(nav) }
            if (route != null) androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate(route) }
        }
        repeat(10) {
            Thread.sleep(150)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(200)
        }
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(android.graphics.Canvas(it)) }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shot(null, "1_home")
    @Test fun documents() = shot("documents", "2_documents")
    @Test fun tools() = shot("tools", "5_tools")
    @Test fun settings() = shot("settings", "6_settings")
}
