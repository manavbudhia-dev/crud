package com.papervault.scanner

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.papervault.scanner.convert.Converter
import com.papervault.scanner.data.AppDatabase
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.SettingsRepository
import com.papervault.scanner.vault.VaultManager

/** Manual DI container — small enough that Hilt isn't worth the build cost yet. */
class AppContainer(app: Application) {
    private val db = AppDatabase.create(app)
    val documents = DocumentRepository(app, db.documentDao())
    val vault = VaultManager(app, documents)
    val settings = SettingsRepository(app)
    val launches = LaunchRouter()
    val converter = Converter(app) { settings.settings.value.ocrScript }
    val backup = com.papervault.scanner.backup.BackupManager(app, documents, vault, settings)
}

class PaperVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
        container.vault.installAutoLock()
    }
}
