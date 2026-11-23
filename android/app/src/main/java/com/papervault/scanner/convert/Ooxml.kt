package com.papervault.scanner.convert

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Helpers shared by the minimal Office Open XML writers (no Android dependencies). */
internal object Ooxml {

    /** Escape text for XML and drop characters XML 1.0 forbids (PDF text often contains them). */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c.code < 0x20 || c.code == 0xFFFE || c.code == 0xFFFF -> Unit
                c.isSurrogate() -> sb.append(c) // pairs are fine; lone halves are rare in practice
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    class Zip(out: OutputStream) : AutoCloseable {
        private val zip = ZipOutputStream(out)
        fun text(path: String, content: String) = bytes(path, content.toByteArray(Charsets.UTF_8))
        fun bytes(path: String, data: ByteArray) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(data)
            zip.closeEntry()
        }
        override fun close() = zip.close()
    }

    const val XML_HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    const val REL_OFFICE_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"

    fun rootRels(target: String) = """$XML_HEADER
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="$REL_OFFICE_DOC" Target="$target"/>
</Relationships>"""

    /** EMU: English Metric Units (914400 per inch). */
    const val EMU_PER_INCH = 914400L
}

/** A picture to embed: encoded image bytes plus its pixel size (for aspect ratio). */
class EmbeddedImage(val bytes: ByteArray, val widthPx: Int, val heightPx: Int, val png: Boolean = false) {
    val ext get() = if (png) "png" else "jpeg"
}
