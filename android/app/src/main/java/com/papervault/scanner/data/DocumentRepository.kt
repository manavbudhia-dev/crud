package com.papervault.scanner.data

import android.content.Context
import android.net.Uri
import com.papervault.scanner.pdf.PdfUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
) {
    private val docsDir: File = File(context.filesDir, "documents").apply { mkdirs() }

    fun observeDocuments(query: String = ""): Flow<List<Document>> = dao.observeDocuments(query)
    fun observe(id: Long): Flow<Document?> = dao.observe(id)
    suspend fun get(id: Long): Document? = dao.get(id)

    /** Copy a PDF (from the scanner or a file picker) into private storage and register it. */
    suspend fun importPdf(uri: Uri, source: DocumentSource, title: String? = null): Long =
        withContext(Dispatchers.IO) {
            val out = newPdfFile()
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot open $uri")
            register(out, source, title ?: defaultTitle(source))
        }

    /** Photo → PDF. */
    suspend fun createFromImages(uris: List<Uri>, title: String? = null): Long =
        withContext(Dispatchers.IO) {
            val out = newPdfFile()
            PdfUtils.imagesToPdf(context, uris, out)
            register(out, DocumentSource.PHOTOS, title ?: defaultTitle(DocumentSource.PHOTOS))
        }

    suspend fun rename(doc: Document, newTitle: String) {
        dao.update(doc.copy(title = newTitle.trim().ifEmpty { doc.title }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(doc: Document) = withContext(Dispatchers.IO) {
        File(doc.filePath).delete()
        dao.delete(doc)
    }

    private suspend fun register(file: File, source: DocumentSource, title: String): Long {
        val pages = runCatching { PdfUtils.pageCount(file) }.getOrElse {
            file.delete()
            throw IllegalArgumentException("Not a valid PDF", it)
        }
        return dao.insert(
            Document(
                title = title,
                filePath = file.absolutePath,
                pageCount = pages,
                sizeBytes = file.length(),
                source = source,
            )
        )
    }

    private fun newPdfFile() = File(docsDir, "${UUID.randomUUID()}.pdf")

    private fun defaultTitle(source: DocumentSource): String {
        val prefix = when (source) {
            DocumentSource.SCAN -> "Scan"
            DocumentSource.PHOTOS -> "Photos"
            else -> "Document"
        }
        return "$prefix ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())}"
    }
}
