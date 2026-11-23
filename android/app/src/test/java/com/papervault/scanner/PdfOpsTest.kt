package com.papervault.scanner

import androidx.test.core.app.ApplicationProvider
import com.papervault.scanner.pdf.NumberPosition
import com.papervault.scanner.pdf.PageSpec
import com.papervault.scanner.pdf.PdfOps
import com.papervault.scanner.pdf.WrongPasswordException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Exercises the PdfBox-based operations on real PDFs (Robolectric supplies the Android context). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfOpsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    /** A PDF whose page i contains the text "<label> page i+1". */
    private fun pdf(label: String, pages: Int): File {
        val f = tmp.newFile("$label.pdf")
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(72f, 700f)
                    cs.showText("$label page ${i + 1}"); cs.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    private fun out() = File(tmp.root, "out-${System.nanoTime()}.pdf")
    private fun texts(f: File) = PdfOps.extractText(f).map { it.trim() }

    @Test fun mergeKeepsOrder() {
        val o = out()
        PdfOps.merge(listOf(pdf("A", 2), pdf("B", 1)), o)
        assertEquals(listOf("A page 1", "A page 2", "B page 1"), texts(o))
    }

    @Test fun assembleReordersRotatesAndDuplicates() {
        val a = pdf("A", 3)
        val o = out()
        PdfOps.assemble(listOf(PageSpec(a, 2), PageSpec(a, 0, rotation = 90), PageSpec(a, 0, rotation = -90)), o)
        assertEquals(listOf("A page 3", "A page 1", "A page 1"), texts(o))
        PDDocument.load(o).use { d ->
            assertEquals(0, d.getPage(0).rotation)
            assertEquals(90, d.getPage(1).rotation)
            assertEquals(270, d.getPage(2).rotation)
        }
    }

    @Test fun splitIntoGroups() {
        val a = pdf("A", 5)
        val parts = PdfOps.split(a, listOf(listOf(0, 1), listOf(4))) { out() }
        assertEquals(listOf("A page 1", "A page 2"), texts(parts[0]))
        assertEquals(listOf("A page 5"), texts(parts[1]))
    }

    @Test fun protectThenUnlock() {
        val a = pdf("Secret", 1)
        val locked = out()
        PdfOps.protect(a, "hunter22", locked)
        assertTrue(PdfOps.needsPassword(locked))
        try {
            PdfOps.unlock(locked, "wrong", out()); throw AssertionError("expected WrongPasswordException")
        } catch (_: WrongPasswordException) {}
        val open = out()
        PdfOps.unlock(locked, "hunter22", open)
        assertFalse(PdfOps.needsPassword(open))
        assertEquals(listOf("Secret page 1"), texts(open))
    }

    @Test fun pageNumbersAndWatermarkAddText() {
        val a = pdf("A", 2)
        val numbered = out()
        PdfOps.addPageNumbers(a, NumberPosition.BOTTOM_CENTER, "{n} / {total}", numbered)
        assertTrue(texts(numbered)[1].contains("2 / 2"))
        val marked = out()
        PdfOps.addWatermark(a, "DRAFT", 0.3f, marked)
        assertTrue(texts(marked)[0].contains("DRAFT"))
    }

    @Test fun pageCountUsesPdfBox() = assertEquals(4, PdfOps.pageCount(pdf("C", 4)))
}
