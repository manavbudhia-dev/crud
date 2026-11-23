package com.papervault.scanner

import com.papervault.scanner.convert.DocxBlock
import com.papervault.scanner.convert.DocxReader
import com.papervault.scanner.convert.DocxWriter
import com.papervault.scanner.convert.EmbeddedImage
import com.papervault.scanner.convert.PptxWriter
import com.papervault.scanner.convert.XlsxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Writes sample Office files to build/ooxml-samples so they can also be checked with external tools. */
class OoxmlWritersTest {

    private val outDir = File("build/ooxml-samples").apply { mkdirs() }

    private fun jpeg(w: Int, h: Int): EmbeddedImage {
        val name = if (w > h) "land.jpg" else "port.jpg"
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
        return EmbeddedImage(bytes, w, h)
    }

    @Test
    fun docxRoundTrip() {
        val f = File(outDir, "sample.docx")
        f.outputStream().use {
            DocxWriter.write(
                listOf(
                    DocxBlock.Paragraph("Invoice & Receipt <test>", bold = true, sizePt = 16),
                    DocxBlock.Paragraph("Name:\tJohn\u0001 Doe"),
                    DocxBlock.Paragraph(""),
                    DocxBlock.PageBreak,
                    DocxBlock.Picture(jpeg(600, 800)),
                    DocxBlock.Paragraph("Unicode: हिन्दी 日本語 é"),
                ),
                it,
            )
        }
        ZipFile(f).use { z -> assertTrue(z.getEntry("word/media/image1.jpeg") != null) }
        val paras = f.inputStream().use { DocxReader.read(it) }
        assertEquals("Invoice & Receipt <test>", paras[0].text)
        assertEquals("Name:\tJohn Doe", paras[1].text)
        assertTrue(paras.any { it.text.contains("日本語") })
    }

    @Test
    fun xlsxWrites() {
        val f = File(outDir, "sample.xlsx")
        f.outputStream().use {
            XlsxWriter.write(
                listOf(
                    XlsxWriter.Sheet("Page 1", listOf(listOf("Item", "Qty", "Price"), listOf("Pens", "10", "1,250.50"), listOf("ID", "007", ""))),
                    XlsxWriter.Sheet("Page 1", listOf(listOf("dup name"))),
                ),
                it,
            )
        }
        assertTrue(f.length() > 0)
        assertEquals("AA", XlsxWriter.column(26))
        assertEquals("1250.50", XlsxWriter.asNumber("1,250.50"))
        assertNull(XlsxWriter.asNumber("007"))
        assertNull(XlsxWriter.asNumber("1234567890123456789"))
    }

    @Test
    fun pptxWrites() {
        val f = File(outDir, "sample.pptx")
        f.outputStream().use { PptxWriter.write(listOf(jpeg(800, 600), jpeg(600, 800)), it) }
        ZipFile(f).use { z -> assertTrue(z.getEntry("ppt/slides/slide2.xml") != null) }
    }
}
