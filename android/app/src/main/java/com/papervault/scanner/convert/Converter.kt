package com.papervault.scanner.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.papervault.scanner.pdf.PdfOps
import com.papervault.scanner.pdf.PdfUtils
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

enum class OutputFormat(val label: String, val ext: String, val mime: String) {
    WORD("Word (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    JPG("Images (JPG)", "jpg", "image/jpeg"),
    PNG("Images (PNG)", "png", "image/png"),
    TEXT("Text (.txt)", "txt", "text/plain"),
    EXCEL("Excel (.xlsx)", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    POWERPOINT("PowerPoint (.pptx)", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
}

enum class WordMode(val label: String) {
    TEXT("Editable text"),
    LAYOUT("Exact look (page images)"),
}

typealias Progress = (message: String, fraction: Float) -> Unit

/** On-device conversions. OCR uses ML Kit text recognition (Latin script). */
class Converter(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun ocr(bitmap: Bitmap): String = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text

    suspend fun ocrUri(uri: Uri): String {
        val bmp = PdfUtils.decodeScaled(context, uri, maxSide = 2400) ?: throw IllegalArgumentException("Cannot read image")
        return try { ocr(bmp) } finally { bmp.recycle() }
    }

    /** Per-page text; image-only pages fall back to OCR when [useOcr]. */
    suspend fun pageTexts(pdf: File, useOcr: Boolean, progress: Progress): List<String> {
        val texts = runCatching { PdfOps.extractText(pdf) }.getOrElse { List(PdfUtils.pageCount(pdf)) { "" } }
        return texts.mapIndexed { i, t ->
            progress("Reading page ${i + 1} of ${texts.size}", (i + 1f) / texts.size)
            if (t.isNotBlank() || !useOcr) t
            else PdfUtils.renderPage(pdf, i, 2000)?.let { bmp -> try { ocr(bmp) } finally { bmp.recycle() } }.orEmpty()
        }
    }

    suspend fun convert(pdf: File, format: OutputFormat, outDir: File, baseName: String, useOcr: Boolean, wordMode: WordMode, progress: Progress): List<File> {
        outDir.mkdirs()
        return when (format) {
            OutputFormat.TEXT -> {
                val texts = pageTexts(pdf, useOcr, progress)
                val f = File(outDir, "$baseName.txt")
                f.writeText(texts.mapIndexed { i, t -> "--- Page ${i + 1} ---\n${t.trim()}\n" }.joinToString("\n"))
                listOf(f)
            }
            OutputFormat.WORD -> {
                val f = File(outDir, "$baseName.docx")
                val blocks = if (wordMode == WordMode.TEXT) {
                    val texts = pageTexts(pdf, useOcr, progress)
                    texts.flatMapIndexed { i, t ->
                        val paras = t.trimEnd().lines().map { DocxBlock.Paragraph(it.trimEnd()) }
                        if (i < texts.lastIndex) paras + DocxBlock.PageBreak else paras
                    }
                } else {
                    pageImages(pdf, dpi = 150, progress).map { DocxBlock.Picture(it) }
                }
                f.outputStream().use { DocxWriter.write(blocks, it) }
                listOf(f)
            }
            OutputFormat.EXCEL -> {
                val texts = pageTexts(pdf, useOcr, progress)
                val sheets = texts.mapIndexed { i, t ->
                    XlsxWriter.Sheet("Page ${i + 1}", t.lines().filter { it.isNotBlank() }.map(::splitColumns))
                }
                val f = File(outDir, "$baseName.xlsx")
                f.outputStream().use { XlsxWriter.write(sheets, it) }
                listOf(f)
            }
            OutputFormat.POWERPOINT -> {
                val f = File(outDir, "$baseName.pptx")
                val slides = pageImages(pdf, dpi = 130, progress)
                f.outputStream().use { PptxWriter.write(slides, it) }
                listOf(f)
            }
            OutputFormat.JPG, OutputFormat.PNG -> {
                val count = PdfUtils.pageCount(pdf)
                (0 until count).mapNotNull { i ->
                    progress("Rendering page ${i + 1} of $count", (i + 1f) / count)
                    val bmp = renderAtDpi(pdf, i, 150) ?: return@mapNotNull null
                    val f = File(outDir, "${baseName}_page${i + 1}.${format.ext}")
                    f.outputStream().use {
                        bmp.compress(if (format == OutputFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    bmp.recycle()
                    f
                }
            }
        }
    }

    private fun renderAtDpi(pdf: File, page: Int, dpi: Int): Bitmap? {
        val (wPt, _) = PdfUtils.pageSizePoints(pdf, page)
        return PdfUtils.renderPage(pdf, page, (wPt / 72f * dpi).roundToInt().coerceIn(300, 3000))
    }

    private fun pageImages(pdf: File, dpi: Int, progress: Progress): List<EmbeddedImage> {
        val count = PdfUtils.pageCount(pdf)
        return (0 until count).mapNotNull { i ->
            progress("Rendering page ${i + 1} of $count", (i + 1f) / count)
            val bmp = renderAtDpi(pdf, i, dpi) ?: return@mapNotNull null
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            EmbeddedImage(bos.toByteArray(), bmp.width, bmp.height).also { bmp.recycle() }
        }
    }

    /** Table heuristic: split a text line on tabs or runs of 2+ spaces. */
    private fun splitColumns(line: String): List<String> = line.split(Regex("""\t| {2,}""")).map { it.trim() }

    // ---------------- to PDF ----------------

    data class Para(val text: String, val heading: Boolean = false)

    fun textToPdf(text: String, out: File) = parasToPdf(text.lines().map { Para(it) }, out)

    fun docxToPdf(uri: Uri, out: File) {
        val paras = context.contentResolver.openInputStream(uri)?.use { DocxReader.read(it) }
            ?: throw IllegalArgumentException("Cannot open file")
        parasToPdf(paras.map { Para(it.text, it.heading) }, out)
    }

    /** Lay out paragraphs on A4 pages with 50pt margins, breaking across pages line by line. */
    fun parasToPdf(paras: List<Para>, out: File) {
        val pageW = 595
        val pageH = 842
        val margin = 50f
        val contentW = (pageW - 2 * margin).toInt()
        val body = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val heading = TextPaint(body).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }

        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = margin
        fun newPage(): Canvas {
            page?.let { doc.finishPage(it) }
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, ++pageNo).create())
            y = margin
            return page!!.canvas
        }
        var canvas = newPage()
        try {
            for (p in paras) {
                val paint = if (p.heading) heading else body
                val text = p.text.ifEmpty { " " }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
                for (line in 0 until layout.lineCount) {
                    val lh = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                    if (y + lh > pageH - margin) canvas = newPage()
                    canvas.save()
                    canvas.translate(margin, y - layout.getLineTop(line))
                    canvas.clipRect(0f, layout.getLineTop(line).toFloat(), contentW.toFloat(), layout.getLineBottom(line).toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                    y += lh
                }
                y += if (p.heading) 6f else 3f
            }
            page?.let { doc.finishPage(it) }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}
