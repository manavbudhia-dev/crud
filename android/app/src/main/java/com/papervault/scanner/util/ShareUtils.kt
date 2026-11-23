package com.papervault.scanner.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.papervault.scanner.data.Document
import java.io.File

object ShareUtils {

    /**
     * Share a document's PDF. A copy named after the document title is placed in the cache
     * so the receiving app sees a readable filename instead of a UUID.
     */
    fun shareDocument(context: Context, doc: Document) {
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val safeName = doc.title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "document" }
        val copy = File(shareDir, "$safeName.pdf")
        File(doc.filePath).copyTo(copy, overwrite = true)
        shareFiles(context, listOf(copy), "application/pdf")
    }

    fun shareFiles(context: Context, files: List<File>, mime: String) {
        val authority = "${context.packageName}.fileprovider"
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.apply {
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
