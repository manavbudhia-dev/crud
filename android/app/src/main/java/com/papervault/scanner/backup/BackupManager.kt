package com.papervault.scanner.backup

import android.content.Context
import android.net.Uri
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentRepository
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.data.SettingsRepository
import com.papervault.scanner.pdf.PdfUtils
import com.papervault.scanner.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class BackupSummary(val documents: Int, val vaultDocuments: Int, val signatures: Int, val bytes: Long)
data class RestoreSummary(val restored: Int, val vaultRestored: Int, val skippedDuplicates: Int, val vaultSkipped: Int, val signatures: Int)

/**
 * Builds and restores encrypted backups.
 *
 * Archive (before encryption): manifest.json + docs/<n>.pdf + signatures/<name>.png.
 * Vault documents are only included when the vault is unlocked; they are stored decrypted
 * inside the archive, which is itself encrypted with the backup password, and re-encrypted
 * with this device's vault key on restore.
 */
class BackupManager(
    private val context: Context,
    private val repo: DocumentRepository,
    private val vault: VaultManager,
    private val settings: SettingsRepository,
) {
    private val work get() = File(context.cacheDir, "backup").apply { mkdirs() }
    private val signaturesDir get() = File(context.filesDir, "signatures").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    val lastBackupAt: Long get() = prefs.getLong("last_backup", 0)

    suspend fun backup(target: Uri, password: CharArray, includeVault: Boolean, progress: (String, Float) -> Unit): BackupSummary =
        withContext(Dispatchers.IO) {
            val library = repo.libraryNow()
            val vaultDocs = if (includeVault && vault.isUnlocked) repo.vaultNow() else emptyList()
            val signatures = signaturesDir.listFiles { f -> f.extension == "png" }.orEmpty().toList()
            val zipFile = File(work, "backup.zip")
            try {
                val manifest = JSONObject().put("version", 1).put("created", System.currentTimeMillis())
                val docsJson = JSONArray()
                ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                    val all = library.map { it to false } + vaultDocs.map { it to true }
                    all.forEachIndexed { i, (d, inVault) ->
                        progress("Packing ${i + 1} of ${all.size}", (i + 1f) / (all.size + 2))
                        val name = "docs/$i.pdf"
                        zip.putNextEntry(ZipEntry(name))
                        if (inVault) vault.decryptInto(d, zip) else File(d.filePath).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        docsJson.put(docJson(d, name, inVault))
                    }
                    signatures.forEach { f ->
                        zip.putNextEntry(ZipEntry("signatures/${f.name}"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    manifest.put("documents", docsJson)
                    manifest.put("settings", settingsJson())
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toString().toByteArray())
                    zip.closeEntry()
                }
                progress("Encrypting…", 0.95f)
                var written = 0L
                context.contentResolver.openOutputStream(target, "w")?.use { out ->
                    val counting = object : java.io.FilterOutputStream(out) {
                        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
                        override fun write(b: Int) { out.write(b); written++ }
                    }
                    zipFile.inputStream().buffered().use { BackupCodec.encrypt(password, it, counting) }
                } ?: error("Can't write to the chosen location")
                prefs.edit().putLong("last_backup", System.currentTimeMillis()).apply()
                BackupSummary(library.size, vaultDocs.size, signatures.size, written)
            } finally {
                zipFile.delete()
                java.util.Arrays.fill(password, '\u0000')
            }
        }

    suspend fun restore(source: Uri, password: CharArray, progress: (String, Float) -> Unit): RestoreSummary =
        withContext(Dispatchers.IO) {
            val zipFile = File(work, "restore.zip")
            try {
                progress("Decrypting…", 0.05f)
                context.contentResolver.openInputStream(source)?.use { input ->
                    zipFile.outputStream().buffered().use { BackupCodec.decrypt(password, input, it) }
                } ?: error("Can't open the backup file")
                ZipFile(zipFile).use { zip ->
                    val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json") ?: error("Backup is missing its manifest")).bufferedReader().readText())
                    val docs = manifest.getJSONArray("documents")
                    val existing = (repo.libraryNow() + repo.vaultNow()).map { Triple(it.title, it.sizeBytes, it.createdAt) }.toSet()
                    var restored = 0; var vaultRestored = 0; var dupes = 0; var vaultSkipped = 0
                    for (i in 0 until docs.length()) {
                        val o = docs.getJSONObject(i)
                        progress("Restoring ${i + 1} of ${docs.length()}", 0.1f + 0.85f * (i + 1) / docs.length())
                        val title = o.getString("title"); val size = o.getLong("size"); val created = o.getLong("createdAt")
                        if (Triple(title, size, created) in existing) { dupes++; continue }
                        val inVault = o.optBoolean("vault")
                        if (inVault && !vault.isUnlocked) { vaultSkipped++; continue }
                        val target = repo.newPdfFile()
                        zip.getInputStream(zip.getEntry(o.getString("file"))).use { input -> target.outputStream().use { input.copyTo(it) } }
                        val id = repo.insertRaw(
                            Document(
                                title = title,
                                filePath = target.absolutePath,
                                pageCount = runCatching { PdfUtils.pageCount(target) }.getOrDefault(o.optInt("pages")),
                                sizeBytes = target.length(),
                                source = runCatching { DocumentSource.valueOf(o.optString("source")) }.getOrDefault(DocumentSource.IMPORT),
                                createdAt = created,
                                updatedAt = o.optLong("updatedAt", created),
                                folder = o.optString("folder").ifBlank { null },
                                starred = o.optBoolean("starred"),
                            ),
                        )
                        if (inVault) { repo.get(id)?.let { vault.moveIn(it) }; vaultRestored++ } else restored++
                    }
                    var sigs = 0
                    zip.entries().asSequence().filter { it.name.startsWith("signatures/") && !it.isDirectory }.forEach { e ->
                        val f = File(signaturesDir, e.name.substringAfter('/'))
                        if (!f.exists()) { zip.getInputStream(e).use { input -> f.outputStream().use { input.copyTo(it) } }; sigs++ }
                    }
                    manifest.optJSONObject("settings")?.let(::applySettings)
                    RestoreSummary(restored, vaultRestored, dupes, vaultSkipped, sigs)
                }
            } finally {
                zipFile.delete()
                java.util.Arrays.fill(password, '\u0000')
            }
        }

    private fun docJson(d: Document, file: String, inVault: Boolean) = JSONObject()
        .put("file", file).put("title", d.title).put("size", if (inVault) -1L else d.sizeBytes).put("pages", d.pageCount)
        .put("source", d.source.name).put("createdAt", d.createdAt).put("updatedAt", d.updatedAt)
        .put("folder", d.folder ?: "").put("starred", d.starred).put("vault", inVault)

    private fun settingsJson(): JSONObject {
        val s = settings.settings.value
        return JSONObject().put("theme", s.theme.name).put("pageSize", s.pageSize.name).put("sort", s.sort.name)
            .put("profileName", s.profileName).put("profileEmail", s.profileEmail).put("ocrScript", s.ocrScript.name)
    }

    /** Only fills in what's empty or default here, so a restore never overwrites a newer profile. */
    private fun applySettings(o: JSONObject) {
        settings.update { s ->
            s.copy(
                profileName = s.profileName.ifBlank { o.optString("profileName") },
                profileEmail = s.profileEmail.ifBlank { o.optString("profileEmail") },
            )
        }
    }
}
