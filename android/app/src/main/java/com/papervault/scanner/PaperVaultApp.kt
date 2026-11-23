package com.papervault.scanner

import android.app.Application
import com.papervault.scanner.data.AppDatabase
import com.papervault.scanner.data.DocumentRepository

/** Manual DI container — small enough that Hilt isn't worth the build cost yet. */
class AppContainer(app: Application) {
    private val db = AppDatabase.create(app)
    val documents = DocumentRepository(app, db.documentDao())
}

class PaperVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
