package com.papervault.scanner.data

import android.content.Context
import android.net.Uri
import com.papervault.scanner.pdf.PdfOps
import com.papervault.scanner.pdf.PdfUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Thrown when an imported PDF is password-protected. Retry with [DocumentRepository.importLocked]. */
class PasswordRequiredException(val lockedFile: File, val title: String) : Exception("Password required")

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
) {
    private val docsDir: File = File(context.filesDir, "documents").apply { mkdirs() }
    val workDir: File get() = File(context.cacheDir, "work").apply { mkdirs() }

    fun observeLibrary(): Flow<List<Document>> = dao.observeLibrary()
    fun observeVault(): Flow<List<Document>> = dao.observeVault()
    fun observeFolders(): Flow<List<String>> = dao.observeFolders()
    fun observe(id: Long): Flow<Document?> = dao.observe(id)
    suspend fun get(id: Long): Document? = dao.get(id)
    suspend fun getAll(ids: List<Long>): List<Document> = dao.getAll(ids).sortedBy { ids.indexOf(it.id) }
    suspend fun update(doc: Document) = dao.update(doc)

    /** Copy a PDF (from the scanner or a file picker) into private storage and register it. */
    suspend fun importPdf(uri: Uri, source: DocumentSource, title: String? = null): Long =
        withContext(Dispatchers.IO) {
            val out = newPdfFile()
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot open file")
            val name = title ?: defaultTitle(source)
            if (isLocked(out)) throw PasswordRequiredException(out, name)
            register(out, source, name)
        }

    /** Remove the password from a locked import and register it. */
    suspend fun importLocked(locked: File, password: String, title: String): Long =
        withContext(Dispatchers.IO) {
            val out = newPdfFile()
            PdfOps.unlock(locked, password, out)
            locked.delete()
            register(out, DocumentSource.IMPORT, title)
        }

    /** Photo → PDF. */
    suspend fun createFromImages(uris: List<Uri>, title: String? = null, pageSize: PageSize = PageSize.A4): Long =
        withContext(Dispatchers.IO) {
            val out = newPdfFile()
            PdfUtils.imagesToPdf(context, uris, out, pageSize)
            register(out, DocumentSource.PHOTOS, title ?: defaultTitle(DocumentSource.PHOTOS))
        }

    /** Register a PDF produced by a tool (written via [newWorkFile] or [newPdfFile]). */
    suspend fun addGenerated(file: File, source: DocumentSource, title: String, folder: String? = null): Long =
        withContext(Dispatchers.IO) {
            val target = if (file.parentFile == docsDir) file else newPdfFile().also { moveFile(file, it) }
            register(target, source, title, folder)
        }

    /** Replace a document's PDF with [newFile] (e.g. after page edits or annotations). */
    suspend fun replaceContent(doc: Document, newFile: File) = withContext(Dispatchers.IO) {
        check(!doc.inVault) { "Vault documents are read-only here" }
        val target = File(doc.filePath)
        moveFile(newFile, target)
        dao.update(
            doc.copy(
                pageCount = PdfUtils.pageCount(target),
                sizeBytes = target.length(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun rename(doc: Document, newTitle: String) {
        dao.update(doc.copy(title = newTitle.trim().ifEmpty { doc.title }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setFolder(doc: Document, folder: String?) =
        dao.update(doc.copy(folder = folder?.trim()?.ifEmpty { null }))

    suspend fun toggleStar(doc: Document) = dao.update(doc.copy(starred = !doc.starred))

    suspend fun delete(doc: Document) = withContext(Dispatchers.IO) {
        File(doc.filePath).delete()
        dao.delete(doc)
    }

    /** Insert a row for a file that's already in its final place (used by the vault). */
    suspend fun insertRaw(doc: Document): Long = dao.insert(doc)

    fun newPdfFile() = File(docsDir, "${UUID.randomUUID()}.pdf")
    fun newWorkFile(ext: String = "pdf") = File(workDir, "${UUID.randomUUID()}.$ext")

    private suspend fun register(file: File, source: DocumentSource, title: String, folder: String? = null): Long {
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
                folder = folder,
            )
        )
    }

    private fun isLocked(file: File): Boolean = try {
        PdfUtils.pageCount(file); false
    } catch (e: SecurityException) {
        true
    } catch (e: Exception) {
        runCatching { PdfOps.needsPassword(file) }.getOrDefault(false)
    }

    private fun moveFile(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    fun defaultTitle(source: DocumentSource): String {
        val prefix = when (source) {
            DocumentSource.SCAN -> "Scan"
            DocumentSource.PHOTOS -> "Photos"
            DocumentSource.MERGE -> "Merged"
            else -> "Document"
        }
        return "$prefix ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())}"
    }
}

enum class PageSize(val label: String) { A4("A4"), LETTER("Letter"), FIT("Fit to image") }
