package com.papervault.scanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DocumentSource { SCAN, PHOTOS, IMPORT, MERGE, SPLIT, EDIT, CONVERT }

@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Absolute path of the PDF (or encrypted blob when [inVault]) inside app-private storage. */
    val filePath: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val source: DocumentSource,
    val inVault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val folder: String? = null,
    val starred: Boolean = false,
)
