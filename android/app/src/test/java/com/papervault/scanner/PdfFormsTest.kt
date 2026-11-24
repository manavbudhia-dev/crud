package com.papervault.scanner

import androidx.test.core.app.ApplicationProvider
import com.papervault.scanner.pdf.FieldType
import com.papervault.scanner.pdf.PdfForms
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfFormsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private fun formPdf(): File {
        val f = tmp.newFile("form.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val form = PDAcroForm(doc); doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 0 Tf 0 g"
            fun place(w: com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget, y: Float) {
                w.rectangle = PDRectangle(50f, y, 250f, 24f); w.page = page; page.annotations.add(w)
            }
            val name = PDTextField(form).apply { partialName = "full_name"; defaultAppearance = "/Helv 12 Tf 0 g" }
            form.fields.add(name); place(name.widgets[0], 700f)
            val email = PDTextField(form).apply { partialName = "EmailAddress"; defaultAppearance = "/Helv 12 Tf 0 g" }
            form.fields.add(email); place(email.widgets[0], 660f)
            val country = PDComboBox(form).apply { partialName = "country"; defaultAppearance = "/Helv 12 Tf 0 g"; setOptions(listOf("India", "Nepal")) }
            form.fields.add(country); place(country.widgets[0], 620f)
            doc.save(f)
        }
        return f
    }

    @Test fun readsFieldsWithLabels() {
        val fields = PdfForms.read(formPdf())
        assertEquals(listOf("full_name", "EmailAddress", "country"), fields.map { it.name })
        assertEquals("Full name", fields[0].label)
        assertEquals("Email address", fields[1].label)
        assertEquals(FieldType.CHOICE, fields[2].type)
        assertEquals(listOf("India", "Nepal"), fields[2].options)
    }

    @Test fun fillsAndReadsBack() {
        val out = File(tmp.root, "filled.pdf")
        val r = PdfForms.fill(formPdf(), mapOf("full_name" to "Asha Rao", "country" to "Nepal"), flatten = false, out = out)
        assertEquals(2, r.filled)
        val back = PdfForms.read(out).associate { it.name to it.value }
        assertEquals("Asha Rao", back["full_name"])
        assertEquals("Nepal", back["country"])
    }

    @Test fun flattenRemovesFieldsButKeepsText() {
        val out = File(tmp.root, "flat.pdf")
        PdfForms.fill(formPdf(), mapOf("full_name" to "Asha Rao"), flatten = true, out = out)
        assertTrue(PdfForms.read(out).isEmpty())
        val text = PDDocument.load(out).use { com.tom_roush.pdfbox.text.PDFTextStripper().getText(it) }
        assertTrue("flattened text should be on the page: $text", text.contains("Asha Rao"))
    }

    @Test fun autofillFromProfile() {
        val m = PdfForms.autofill(PdfForms.read(formPdf()), "Asha Rao", "asha@example.com", "24 Nov 2026")
        assertEquals("Asha Rao", m["full_name"])
        assertEquals("asha@example.com", m["EmailAddress"])
        assertNull(m["country"])
    }
}
