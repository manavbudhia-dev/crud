package com.papervault.scanner.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File

enum class FieldType { TEXT, CHECKBOX, CHOICE, RADIO }

data class FormField(
    val name: String,
    val label: String,
    val type: FieldType,
    val value: String,
    val options: List<String> = emptyList(),
    val multiline: Boolean = false,
    val readOnly: Boolean = false,
)

data class FillResult(val filled: Int, val warnings: List<String>)

/** Read and fill interactive PDF forms (AcroForm) with PdfBox. */
object PdfForms {

    const val CHECKED = "true"

    fun read(file: File): List<FormField> = PDDocument.load(file).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return emptyList()
        form.fieldTree.filterIsInstance<PDTerminalField>().mapNotNull { f -> toField(f) }
    }

    private fun toField(f: PDTerminalField): FormField? {
        val name = f.fullyQualifiedName ?: return null
        val label = f.alternateFieldName?.takeIf { it.isNotBlank() } ?: humanize(f.partialName ?: name)
        return when (f) {
            is PDTextField -> FormField(name, label, FieldType.TEXT, f.value.orEmpty(), multiline = f.isMultiline, readOnly = f.isReadOnly)
            is PDCheckBox -> FormField(name, label, FieldType.CHECKBOX, if (f.isChecked) CHECKED else "", readOnly = f.isReadOnly)
            is PDRadioButton -> FormField(name, label, FieldType.RADIO, f.value.orEmpty(), f.exportValues.ifEmpty { f.onValues.toList() }, readOnly = f.isReadOnly)
            is PDChoice -> FormField(name, label, FieldType.CHOICE, f.value.firstOrNull().orEmpty(), f.optionsExportValues.ifEmpty { f.optionsDisplayValues }, readOnly = f.isReadOnly)
            is PDSignatureField, is PDPushButton -> null
            else -> null
        }
    }

    /**
     * Write [values] (field name → value; checkboxes use [CHECKED]) into a copy of [file].
     * With [flatten] the answers become regular page content and can no longer be edited.
     */
    fun fill(file: File, values: Map<String, String>, flatten: Boolean, out: File): FillResult {
        val warnings = ArrayList<String>()
        var filled = 0
        PDDocument.load(file).use { doc ->
            val form = doc.documentCatalog.acroForm ?: throw IllegalArgumentException("This PDF has no fillable fields")
            for ((name, value) in values) {
                val field: PDField = form.getField(name) ?: continue
                if (field.isReadOnly) continue
                try {
                    when (field) {
                        is PDCheckBox -> if (value == CHECKED) checkBox(field) else field.unCheck()
                        is PDRadioButton -> if (value.isNotEmpty()) field.setValue(value)
                        is PDChoice -> if (value.isNotEmpty()) field.setValue(value)
                        is PDTextField -> field.setValue(value)
                        else -> continue
                    }
                    filled++
                } catch (e: Exception) {
                    // Usually a character the field's font can't draw: store the value and let viewers render it.
                    field.cosObject.setString(COSName.V, value)
                    form.needAppearances = true
                    warnings += "${humanize(name)}: saved, but this PDF's font can't display some characters"
                    filled++
                }
            }
            if (flatten) {
                if (form.needAppearances) {
                    warnings += "Some answers couldn't be locked into the page; the form was left editable"
                } else {
                    form.flatten()
                }
            }
            doc.save(out)
        }
        return FillResult(filled, warnings)
    }

    private fun checkBox(cb: PDCheckBox) {
        val on = cb.onValue
        if (on.isNullOrEmpty()) {
            cb.cosObject.setName(COSName.V, "Yes")
            cb.widgets.forEach { it.cosObject.setName(COSName.AS, "Yes") }
        } else cb.check()
    }

    /** "first_name" / "FirstName" / "txtFirstName" → "First name". */
    fun humanize(raw: String): String {
        val last = raw.substringAfterLast('.')
        val spaced = last.replace(Regex("^(txt|fld|chk|cb|rb|ddl)(?=[A-Z_])"), "")
            .replace('_', ' ').replace('-', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2").trim()
        return spaced.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { raw }
    }

    /** Suggest values from the local profile for fields that look like name / email / date. */
    fun autofill(fields: List<FormField>, name: String, email: String, today: String): Map<String, String> =
        fields.filter { it.type == FieldType.TEXT && !it.readOnly && it.value.isBlank() }.mapNotNull { f ->
            val key = (f.name + " " + f.label).lowercase()
            val v = when {
                "email" in key || "e-mail" in key -> email
                "date" in key && "birth" !in key && "dob" !in key -> today
                ("first" in key && "name" in key) -> name.substringBefore(' ')
                ("last" in key || "sur" in key) && "name" in key -> name.substringAfterLast(' ', "")
                "name" in key && "company" !in key && "file" !in key -> name
                else -> ""
            }
            if (v.isBlank()) null else f.name to v
        }.toMap()
}
