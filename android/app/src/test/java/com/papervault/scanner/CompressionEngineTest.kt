package com.papervault.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.papervault.scanner.data.PageSize
import com.papervault.scanner.pdf.PdfUtils
import com.papervault.scanner.pdf.compress.CompressionEngine
import com.papervault.scanner.pdf.compress.CompressionLevel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/** End-to-end: real Bitmap encoders (native graphics) → PDF sizes. Prints a size report. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CompressionEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun init() = PDFBoxResourceLoader.init(ctx)

    /** A 12 MP-class "phone photo" of a typed page: off-white paper, sensor noise, black text lines. */
    private fun textPhoto(): Bitmap {
        val w = 3000; val h = 4000
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(246, 244, 238))
        val p = Paint().apply { color = Color.rgb(20, 20, 24); textSize = 64f; isAntiAlias = true }
        var y = 260f
        while (y < h - 200) { c.drawText("The quick brown fox jumps over the lazy dog 0123456789", 200f, y, p); y += 110f }
        return b
    }

    private fun colorPhoto(): Bitmap {
        val w = 3000; val h = 4000
        val r = Random(3)
        val px = IntArray(w * h) { i -> val x = i % w; val yy = i / w; Color.rgb((x * 255 / w + r.nextInt(-12, 12)).coerceIn(0, 255), (yy * 255 / h).coerceIn(0, 255), (128 + r.nextInt(-20, 20)).coerceIn(0, 255)) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }

    private fun jpegFile(b: Bitmap, name: String) = File(tmp.root, name).also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) } }

    private fun pdfSize(src: File, level: CompressionLevel): Long {
        val out = File(tmp.root, "${src.nameWithoutExtension}-$level.pdf")
        PdfUtils.imagesToPdf(ctx, listOf(Uri.fromFile(src)), out, PageSize.A4, level = level)
        assertEquals(1, PDDocument.load(out).use { it.numberOfPages })
        return out.length()
    }

    @Test fun textPageShrinksDramatically() {
        val src = jpegFile(textPhoto(), "text.jpg")
        val off = pdfSize(src, CompressionLevel.OFF)
        val balanced = pdfSize(src, CompressionLevel.BALANCED)
        val smallest = pdfSize(src, CompressionLevel.SMALLEST)
        println("TEXT PAGE  source=${src.length() / 1024}KB  off=${off / 1024}KB  balanced=${balanced / 1024}KB  smallest=${smallest / 1024}KB")
        assertTrue("balanced ($balanced) should be <25% of uncompressed ($off)", balanced < off / 4)
        assertTrue("balanced text page should be under 150 KB, was ${balanced / 1024} KB", balanced < 150_000)
        assertTrue("a real text page can't be ~1 KB (that means a blank/black image)", balanced > 5_000)
        // Render the PDF back and check it isn't blank or inverted: paper light, some ink dark.
        val out = File(tmp.root, "text-BALANCED.pdf")
        PDDocument.load(out).use { d ->
            val img = d.getPage(0).resources.let { r -> r.getXObject(r.xObjectNames.first()) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject }
            assertEquals("text page should be stored 1-bit", 1, img.bitsPerComponent)
            val bmp = img.image
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            val dark = px.count { Color.red(it) < 100 }.toFloat() / px.size
            assertTrue("ink coverage should be a few percent, was ${dark * 100}%", dark in 0.005f..0.3f)
        }
    }

    @Test fun colorPhotoShrinksAndStaysJpeg() {
        val src = jpegFile(colorPhoto(), "photo.jpg")
        val off = pdfSize(src, CompressionLevel.OFF)
        val balanced = pdfSize(src, CompressionLevel.BALANCED)
        val smallest = pdfSize(src, CompressionLevel.SMALLEST)
        println("COLOR PAGE source=${src.length() / 1024}KB  off=${off / 1024}KB  balanced=${balanced / 1024}KB  smallest=${smallest / 1024}KB")
        assertTrue(balanced < off * 0.6)
        assertTrue(smallest < balanced)
    }

    /** 1-bit pages must render ink as dark and paper as light, not inverted. */
    @Test fun bilevelPolarityIsCorrect() {
        val src = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply { drawColor(Color.WHITE); drawRect(100f, 100f, 300f, 300f, Paint().apply { color = Color.BLACK }) }
        }
        PDDocument().use { doc ->
            val img = CompressionEngine.bilevel(doc, src)
            val back = img.image
            fun lum(x: Int, y: Int) = back.getPixel(x, y).let { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }
            assertTrue("ink should be dark, was ${lum(200, 200)}", lum(200, 200) < 80)
            assertTrue("paper should be light, was ${lum(20, 20)}", lum(20, 20) > 175)
        }
    }

    @Test fun smartOptimizeKeepsText() {
        val input = File(tmp.root, "digital.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val img = LosslessFactory.createFromImage(doc, colorPhoto())   // huge lossless photo, typical of exported PDFs
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(img, 50f, 300f, 495f, 495f * 4 / 3 * 0.75f)
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(50f, 100f); cs.showText("Invoice total 4,250.00"); cs.endText()
            }
            doc.save(input)
        }
        val out = File(tmp.root, "digital-opt.pdf")
        val r = CompressionEngine.optimizeImages(input, CompressionLevel.BALANCED, out)
        println("SMART      before=${r.bytesBefore / 1024}KB after=${r.bytesAfter / 1024}KB images=${r.imagesRecompressed}/${r.imagesSeen}")
        assertEquals(1, r.imagesRecompressed)
        assertTrue(r.bytesAfter < r.bytesBefore / 3)
        val text = PDDocument.load(out).use { PDFTextStripper().getText(it) }
        assertTrue("text must survive: $text", text.contains("Invoice total 4,250.00"))
    }
}
