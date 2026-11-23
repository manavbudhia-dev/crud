package com.papervault.scanner.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.max

/** A page in an edited document: page [pageIndex] of source file [source], rotated by [rotation] extra degrees. */
data class PageSpec(val source: File, val pageIndex: Int, val rotation: Int = 0)

enum class NumberPosition { BOTTOM_CENTER, BOTTOM_RIGHT, TOP_RIGHT }

class WrongPasswordException : Exception("Incorrect password")

/**
 * All structural PDF operations, built on PdfBox-Android.
 * Every function writes to a new [out] file; callers decide whether to replace or add a document.
 */
object PdfOps {

    /** Build a PDF from an ordered list of pages, possibly from several source files. */
    fun assemble(pages: List<PageSpec>, out: File) {
        require(pages.isNotEmpty()) { "No pages" }
        val sources = HashMap<File, PDDocument>()
        try {
            PDDocument().use { target ->
                for (spec in pages) {
                    val src = sources.getOrPut(spec.source) { PDDocument.load(spec.source) }
                    val imported = target.importPage(src.getPage(spec.pageIndex))
                    imported.rotation = ((imported.rotation + spec.rotation) % 360 + 360) % 360
                }
                // Sources must stay open until save: imported pages reference their streams.
                saveAtomically(target, out)
            }
        } finally {
            sources.values.forEach { runCatching { it.close() } }
        }
    }

    fun merge(files: List<File>, out: File) =
        assemble(files.flatMap { f -> (0 until PdfUtils.pageCount(f)).map { PageSpec(f, it) } }, out)

    fun extract(file: File, pageIndexes: List<Int>, out: File) =
        assemble(pageIndexes.map { PageSpec(file, it) }, out)

    /** Split into one file per group of 0-based page indexes. */
    fun split(file: File, groups: List<List<Int>>, outFor: (Int) -> File): List<File> =
        groups.mapIndexed { i, g -> outFor(i).also { extract(file, g, it) } }

    /**
     * Rebuild every page as a JPEG at the given DPI and quality.
     * Large size savings for scans/photos; text in digital PDFs becomes an image.
     */
    fun compress(file: File, dpi: Int, quality: Float, out: File, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val count = PdfUtils.pageCount(file)
        PDDocument().use { target ->
            for (i in 0 until count) {
                val size = PdfUtils.pageSizePoints(file, i)
                val widthPx = max(1, (size.first / 72f * dpi).toInt())
                val bmp = PdfUtils.renderPage(file, i, widthPx) ?: continue
                addImagePage(target, bmp, size.first, size.second, jpegQuality = quality)
                bmp.recycle()
                onProgress(i + 1, count)
            }
            saveAtomically(target, out)
        }
    }

    /** Append a full-page image page of [w]x[h] points. */
    fun addImagePage(doc: PDDocument, bmp: Bitmap, w: Float, h: Float, jpegQuality: Float? = 0.9f) {
        val page = PDPage(PDRectangle(w, h))
        doc.addPage(page)
        val img = if (jpegQuality != null) JPEGFactory.createFromImage(doc, bmp, jpegQuality)
        else LosslessFactory.createFromImage(doc, bmp)
        PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w, h) }
    }

    /** Password-protect (AES-256). Recipients need [userPassword] to open. */
    fun protect(file: File, userPassword: String, out: File) {
        PDDocument.load(file).use { doc ->
            val perms = AccessPermission()
            val policy = StandardProtectionPolicy(userPassword + "#owner", userPassword, perms)
            policy.encryptionKeyLength = 256
            doc.protect(policy)
            saveAtomically(doc, out)
        }
    }

    /** True if the file needs a password to open. */
    fun needsPassword(file: File): Boolean = try {
        PDDocument.load(file).use { false }
    } catch (e: InvalidPasswordException) {
        true
    }

    /** Remove password protection. Throws [WrongPasswordException]. */
    fun unlock(file: File, password: String, out: File) {
        val doc = try {
            PDDocument.load(file, password)
        } catch (e: InvalidPasswordException) {
            throw WrongPasswordException()
        }
        doc.use {
            it.isAllSecurityToBeRemoved = true
            saveAtomically(it, out)
        }
    }

    fun addPageNumbers(file: File, position: NumberPosition, format: String, out: File) {
        PDDocument.load(file).use { doc ->
            val total = doc.numberOfPages
            val font = PDType1Font.HELVETICA
            val fontSize = 10f
            doc.pages.forEachIndexed { i, page ->
                val label = format.replace("{n}", "${i + 1}").replace("{total}", "$total").winAnsi()
                val textW = font.getStringWidth(label) / 1000f * fontSize
                val margin = 24f
                // Work in the rotated (displayed) frame so numbers land at the visual bottom.
                val (dispW, dispH) = displayedSize(page)
                val x = when (position) {
                    NumberPosition.BOTTOM_CENTER -> (dispW - textW) / 2
                    else -> dispW - textW - margin
                }
                val y = if (position == NumberPosition.TOP_RIGHT) dispH - margin - fontSize else margin
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(displayToUser(page))
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setNonStrokingColor(0.25f, 0.25f, 0.25f)
                    cs.newLineAtOffset(x, y)
                    cs.showText(label)
                    cs.endText()
                }
            }
            saveAtomically(doc, out)
        }
    }

    fun addWatermark(file: File, text: String, opacity: Float, out: File) {
        val label = text.winAnsi().ifBlank { "CONFIDENTIAL" }
        PDDocument.load(file).use { doc ->
            val font = PDType1Font.HELVETICA_BOLD
            val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity.coerceIn(0.05f, 1f) }
            for (page in doc.pages) {
                val (w, h) = displayedSize(page)
                val diag = kotlin.math.sqrt(w * w + h * h)
                val unitW = font.getStringWidth(label) / 1000f
                val fontSize = (diag * 0.7f / unitW).coerceAtMost(120f)
                val textW = unitW * fontSize
                val angle = kotlin.math.atan2(h, w).toDouble()
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(displayToUser(page))
                    cs.setGraphicsStateParameters(gs)
                    cs.setNonStrokingColor(0.8f, 0.1f, 0.1f)
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    val m = Matrix.getRotateInstance(angle, w / 2, h / 2)
                    m.translate(-textW / 2, -fontSize / 3)
                    cs.setTextMatrix(m)
                    cs.showText(label)
                    cs.endText()
                }
            }
            saveAtomically(doc, out)
        }
    }

    /**
     * Draw a transparent overlay bitmap (rendered in the page's displayed orientation) on top of
     * each listed page, keeping existing vector content and text intact.
     */
    fun overlayImages(file: File, overlays: Map<Int, Bitmap>, out: File) {
        PDDocument.load(file).use { doc ->
            overlays.forEach { (index, bmp) ->
                val page = doc.getPage(index)
                val (w, h) = displayedSize(page)
                val img = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(displayToUser(page))
                    cs.drawImage(img, 0f, 0f, w, h)
                }
            }
            saveAtomically(doc, out)
        }
    }

    /**
     * Replace the listed pages with flattened images (used for redaction so masked content is
     * actually removed). Other pages are copied unchanged.
     */
    fun replaceWithImages(file: File, replacements: Map<Int, Bitmap>, out: File) {
        PDDocument.load(file).use { src ->
            PDDocument().use { target ->
                src.pages.forEachIndexed { i, page ->
                    val bmp = replacements[i]
                    if (bmp == null) {
                        target.importPage(page)
                    } else {
                        val (w, h) = displayedSize(page)
                        addImagePage(target, bmp, w, h, jpegQuality = 0.92f)
                    }
                }
                saveAtomically(target, out)
            }
        }
    }

    /** Text of each page (empty string for image-only pages). */
    fun extractText(file: File): List<String> =
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            (1..doc.numberOfPages).map { p ->
                stripper.startPage = p
                stripper.endPage = p
                stripper.getText(doc)
            }
        }

    // --- geometry helpers ---

    private fun displayedSize(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox
        return if (page.rotation % 180 == 0) box.width to box.height else box.height to box.width
    }

    /**
     * Matrix mapping the displayed (rotated, origin bottom-left) coordinate frame to the page's
     * user space, so content drawn in display coordinates appears upright.
     */
    private fun displayToUser(page: PDPage): Matrix {
        val box = page.cropBox
        val x0 = box.lowerLeftX
        val y0 = box.lowerLeftY
        val w = box.width
        val h = box.height
        // PDF /Rotate is clockwise; Matrix(a,b,c,d,e,f)
        return when ((page.rotation % 360 + 360) % 360) {
            90 -> Matrix(0f, 1f, -1f, 0f, x0 + w, y0)
            180 -> Matrix(-1f, 0f, 0f, -1f, x0 + w, y0 + h)
            270 -> Matrix(0f, -1f, 1f, 0f, x0, y0 + h)
            else -> Matrix(1f, 0f, 0f, 1f, x0, y0)
        }
    }

    private fun saveAtomically(doc: PDDocument, out: File) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        doc.save(tmp)
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }

    /** Standard-14 fonts only cover WinAnsi; replace anything else so showText can't throw. */
    private fun String.winAnsi(): String = map { c ->
        if (c.code in 32..126 || c.code in 160..255) c else '?'
    }.joinToString("")
}
