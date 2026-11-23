package com.papervault.scanner.convert

import com.papervault.scanner.convert.Ooxml.XML_HEADER
import com.papervault.scanner.convert.Ooxml.esc
import java.io.OutputStream

/** Writes a minimal .xlsx: one worksheet per entry, cells as inline strings (numbers as numbers). */
object XlsxWriter {

    class Sheet(val name: String, val rows: List<List<String>>)

    fun write(sheets: List<Sheet>, out: OutputStream) {
        require(sheets.isNotEmpty())
        val names = uniqueNames(sheets.map { it.name })
        Ooxml.Zip(out).use { zip ->
            zip.text("[Content_Types].xml", buildString {
                append(XML_HEADER)
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach {
                    append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                }
                append("</Types>")
            })
            zip.text("_rels/.rels", Ooxml.rootRels("xl/workbook.xml"))
            zip.text("xl/workbook.xml", buildString {
                append(XML_HEADER)
                append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="${Ooxml.NS_R}"><sheets>""")
                names.forEachIndexed { i, n -> append("""<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            zip.text("xl/_rels/workbook.xml.rels", buildString {
                append(XML_HEADER)
                append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach {
                    append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
                }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                append("</Relationships>")
            })
            zip.text("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s -> zip.text("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private fun sheetXml(s: Sheet) = buildString {
        append(XML_HEADER)
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        s.rows.forEachIndexed { r, row ->
            append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, value ->
                val ref = column(c) + (r + 1)
                val v = value.trim()
                val num = asNumber(v)
                if (num != null) {
                    append("""<c r="$ref"><v>$num</v></c>""")
                } else if (v.isNotEmpty()) {
                    append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** Numeric cell value, or null to keep as text (leading zeros, IDs, >15 digits stay text). */
    fun asNumber(v: String): String? {
        if (!Regex("""-?(\d{1,3}(,\d{3})+|\d+)(\.\d+)?""").matches(v)) return null
        val plain = v.replace(",", "")
        val digits = plain.count(Char::isDigit)
        if (digits > 15) return null
        val intPart = plain.removePrefix("-").substringBefore('.')
        if (intPart.length > 1 && intPart.startsWith("0")) return null
        return plain
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** Sheet names: max 31 chars, no []:*?/\ and unique. */
    private fun uniqueNames(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.map { raw ->
            val base = raw.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().ifEmpty { "Sheet" }.take(28)
            var name = base
            var i = 2
            while (!used.add(name.lowercase())) name = "$base ${i++}"
            name
        }
    }

    private const val STYLES = """$XML_HEADER
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
