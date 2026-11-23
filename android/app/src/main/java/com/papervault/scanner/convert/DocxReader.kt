package com.papervault.scanner.convert

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Extracts paragraphs from a .docx (text, headings, tabs, line breaks). Images and tables-as-layout are not kept. */
object DocxReader {

    data class Para(val text: String, val heading: Boolean)

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun read(input: InputStream): List<Para> {
        val xml = ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "word/document.xml" }
                ?: throw IllegalArgumentException("Not a Word (.docx) document")
            zip.readBytes()
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Harden against XXE in untrusted files.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(xml.inputStream())
        val paras = doc.getElementsByTagNameNS(W, "p")
        return (0 until paras.length).map { i ->
            val p = paras.item(i) as Element
            val style = p.getElementsByTagNameNS(W, "pStyle").let {
                if (it.length > 0) (it.item(0) as Element).getAttributeNS(W, "val") else ""
            }
            val sb = StringBuilder()
            collect(p, sb)
            Para(sb.toString(), style.startsWith("Heading", ignoreCase = true) || style.equals("Title", true))
        }
    }

    private fun collect(node: Node, sb: StringBuilder) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c.namespaceURI == W) when (c.localName) {
                "t" -> sb.append(c.textContent)
                "tab" -> sb.append('\t')
                "br", "cr" -> sb.append('\n')
                "p" -> continue // nested paragraphs (text boxes) are visited by the outer loop
                else -> collect(c, sb)
            } else collect(c, sb)
        }
    }
}
