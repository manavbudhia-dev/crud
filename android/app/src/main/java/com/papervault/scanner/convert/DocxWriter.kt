package com.papervault.scanner.convert

import com.papervault.scanner.convert.Ooxml.XML_HEADER
import com.papervault.scanner.convert.Ooxml.esc
import java.io.OutputStream

sealed interface DocxBlock {
    data class Paragraph(val text: String, val bold: Boolean = false, val sizePt: Int? = null) : DocxBlock
    data object PageBreak : DocxBlock
    class Picture(val image: EmbeddedImage) : DocxBlock
}

/**
 * Writes a minimal but valid .docx (WordprocessingML) with paragraphs, page breaks and inline
 * pictures. A4 page, 1.8 cm margins.
 */
object DocxWriter {

    private const val PAGE_W_TWIPS = 11906 // A4 width
    private const val PAGE_H_TWIPS = 16838
    private const val MARGIN_TWIPS = 1020 // ~1.8 cm
    private const val TWIPS_PER_INCH = 1440

    fun write(blocks: List<DocxBlock>, out: OutputStream) {
        val pictures = blocks.filterIsInstance<DocxBlock.Picture>()
        Ooxml.Zip(out).use { zip ->
            zip.text("[Content_Types].xml", contentTypes(pictures.any { !it.image.png }, pictures.any { it.image.png }))
            zip.text("_rels/.rels", Ooxml.rootRels("word/document.xml"))
            zip.text("word/styles.xml", STYLES)
            val rels = StringBuilder()
            rels.append("""$XML_HEADER<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            rels.append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            pictures.forEachIndexed { i, p ->
                val name = "image${i + 1}.${p.image.ext}"
                zip.bytes("word/media/$name", p.image.bytes)
                rels.append("""<Relationship Id="rIdImg${i + 1}" Type="${Ooxml.REL_IMAGE}" Target="media/$name"/>""")
            }
            rels.append("</Relationships>")
            zip.text("word/_rels/document.xml.rels", rels.toString())
            zip.text("word/document.xml", document(blocks))
        }
    }

    private fun document(blocks: List<DocxBlock>): String {
        val sb = StringBuilder()
        sb.append(XML_HEADER)
        sb.append(
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
                """xmlns:r="${Ooxml.NS_R}" """ +
                """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
                """xmlns:a="${Ooxml.NS_A}" xmlns:pic="${Ooxml.NS_PIC}"><w:body>"""
        )
        var picIndex = 0
        for (b in blocks) when (b) {
            is DocxBlock.Paragraph -> {
                sb.append("<w:p>")
                if (b.text.isNotEmpty()) {
                    sb.append("<w:r>")
                    if (b.bold || b.sizePt != null) {
                        sb.append("<w:rPr>")
                        if (b.bold) sb.append("<w:b/>")
                        b.sizePt?.let { sb.append("""<w:sz w:val="${it * 2}"/>""") }
                        sb.append("</w:rPr>")
                    }
                    // Tabs must be <w:tab/>; text runs keep spaces with xml:space="preserve".
                    b.text.split('\t').forEachIndexed { i, part ->
                        if (i > 0) sb.append("<w:tab/>")
                        if (part.isNotEmpty()) sb.append("""<w:t xml:space="preserve">${esc(part)}</w:t>""")
                    }
                    sb.append("</w:r>")
                }
                sb.append("</w:p>")
            }
            DocxBlock.PageBreak -> sb.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            is DocxBlock.Picture -> {
                picIndex++
                sb.append(picture(b.image, picIndex))
            }
        }
        sb.append(
            """<w:sectPr><w:pgSz w:w="$PAGE_W_TWIPS" w:h="$PAGE_H_TWIPS"/>""" +
                """<w:pgMar w:top="$MARGIN_TWIPS" w:right="$MARGIN_TWIPS" w:bottom="$MARGIN_TWIPS" w:left="$MARGIN_TWIPS" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""
        )
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    /** Inline picture scaled to fit the printable area. */
    private fun picture(img: EmbeddedImage, n: Int): String {
        val availW = (PAGE_W_TWIPS - 2 * MARGIN_TWIPS).toLong() * Ooxml.EMU_PER_INCH / TWIPS_PER_INCH
        val availH = (PAGE_H_TWIPS - 2 * MARGIN_TWIPS - 400).toLong() * Ooxml.EMU_PER_INCH / TWIPS_PER_INCH
        val aspect = img.widthPx.toDouble() / img.heightPx.coerceAtLeast(1)
        var cx = availW
        var cy = (cx / aspect).toLong()
        if (cy > availH) { cy = availH; cx = (cy * aspect).toLong() }
        return """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing>""" +
            """<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/>""" +
            """<wp:docPr id="$n" name="Picture $n"/>""" +
            """<a:graphic><a:graphicData uri="${Ooxml.NS_PIC}"><pic:pic>""" +
            """<pic:nvPicPr><pic:cNvPr id="$n" name="image$n.${img.ext}"/><pic:cNvPicPr/></pic:nvPicPr>""" +
            """<pic:blipFill><a:blip r:embed="rIdImg$n"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""" +
            """<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""" +
            """</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    }

    private fun contentTypes(jpeg: Boolean, png: Boolean) = """$XML_HEADER
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
${if (jpeg) """<Default Extension="jpeg" ContentType="image/jpeg"/>""" else ""}
${if (png) """<Default Extension="png" ContentType="image/png"/>""" else ""}
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private const val STYLES = """$XML_HEADER
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="80" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
</w:styles>"""
}
