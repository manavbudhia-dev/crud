package com.papervault.scanner.convert

import com.papervault.scanner.convert.Ooxml.NS_A
import com.papervault.scanner.convert.Ooxml.NS_R
import com.papervault.scanner.convert.Ooxml.XML_HEADER
import java.io.OutputStream

/**
 * Writes a minimal .pptx where each slide is one full-bleed picture (e.g. a rendered PDF page).
 * Includes the master, layout and theme parts PowerPoint requires.
 */
object PptxWriter {

    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun write(slides: List<EmbeddedImage>, out: OutputStream) {
        require(slides.isNotEmpty())
        val first = slides.first()
        val cx = 9_144_000L // 10 in
        val cy = (cx * first.heightPx / first.widthPx.coerceAtLeast(1)).coerceIn(914_400L, 51_206_400L)

        Ooxml.Zip(out).use { zip ->
            zip.text("[Content_Types].xml", contentTypes(slides))
            zip.text("_rels/.rels", Ooxml.rootRels("ppt/presentation.xml"))
            zip.text("ppt/presentation.xml", presentation(slides.size, cx, cy))
            zip.text("ppt/_rels/presentation.xml.rels", buildString {
                append(XML_HEADER)
                append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                append("""<Relationship Id="rId1" Type="$REL/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
                append("""<Relationship Id="rId2" Type="$REL/theme" Target="theme/theme1.xml"/>""")
                slides.indices.forEach { append("""<Relationship Id="rId${it + 3}" Type="$REL/slide" Target="slides/slide${it + 1}.xml"/>""") }
                append("</Relationships>")
            })
            zip.text("ppt/slideMasters/slideMaster1.xml", MASTER)
            zip.text("ppt/slideMasters/_rels/slideMaster1.xml.rels", """$XML_HEADER<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$REL/theme" Target="../theme/theme1.xml"/></Relationships>""")
            zip.text("ppt/slideLayouts/slideLayout1.xml", LAYOUT)
            zip.text("ppt/slideLayouts/_rels/slideLayout1.xml.rels", """$XML_HEADER<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>""")
            zip.text("ppt/theme/theme1.xml", THEME)

            slides.forEachIndexed { i, img ->
                val n = i + 1
                zip.bytes("ppt/media/image$n.${img.ext}", img.bytes)
                zip.text("ppt/slides/slide$n.xml", slide(img, n, cx, cy))
                zip.text("ppt/slides/_rels/slide$n.xml.rels", """$XML_HEADER<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="${Ooxml.REL_IMAGE}" Target="../media/image$n.${img.ext}"/></Relationships>""")
            }
        }
    }

    private fun slide(img: EmbeddedImage, n: Int, cx: Long, cy: Long): String {
        // Fit picture inside the slide, centred.
        val aspect = img.widthPx.toDouble() / img.heightPx.coerceAtLeast(1)
        var w = cx
        var h = (w / aspect).toLong()
        if (h > cy) { h = cy; w = (h * aspect).toLong() }
        val x = (cx - w) / 2
        val y = (cy - h) / 2
        return """$XML_HEADER<p:sld xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P"><p:cSld><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            """<p:pic><p:nvPicPr><p:cNvPr id="2" name="Page $n"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
            """<p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
            """<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>""" +
            """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    private fun presentation(count: Int, cx: Long, cy: Long) = buildString {
        append(XML_HEADER)
        append("""<p:presentation xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P" saveSubsetFonts="1">""")
        append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
        append("<p:sldIdLst>")
        (0 until count).forEach { append("""<p:sldId id="${256 + it}" r:id="rId${it + 3}"/>""") }
        append("</p:sldIdLst>")
        append("""<p:sldSz cx="$cx" cy="$cy"/><p:notesSz cx="6858000" cy="9144000"/>""")
        append("""<p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr></p:defaultTextStyle>""")
        append("</p:presentation>")
    }

    private fun contentTypes(slides: List<EmbeddedImage>) = buildString {
        append(XML_HEADER)
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        if (slides.any { !it.png }) append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
        if (slides.any { it.png }) append("""<Default Extension="png" ContentType="image/png"/>""")
        append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
        append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
        append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        slides.indices.forEach {
            append("""<Override PartName="/ppt/slides/slide${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        }
        append("</Types>")
    }

    private const val EMPTY_TREE =
        """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree>"""

    private const val MASTER = XML_HEADER +
        """<p:sldMaster xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P">""" +
        """<p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>$EMPTY_TREE</p:cSld>""" +
        """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
        """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>""" +
        """<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle>""" +
        """<p:bodyStyle><a:lvl1pPr><a:defRPr sz="2800"/></a:lvl1pPr></p:bodyStyle>""" +
        """<p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"""

    private const val LAYOUT = XML_HEADER +
        """<p:sldLayout xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P" type="blank" preserve="1">""" +
        """<p:cSld name="Blank">$EMPTY_TREE</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private fun sys(name: String, rgb: String) = """<a:$name><a:srgbClr val="$rgb"/></a:$name>"""

    private val THEME = XML_HEADER +
        """<a:theme xmlns:a="$NS_A" name="PaperVault"><a:themeElements>""" +
        """<a:clrScheme name="PaperVault">""" +
        sys("dk1", "000000") + sys("lt1", "FFFFFF") + sys("dk2", "1F2A2A") + sys("lt2", "E6ECEA") +
        sys("accent1", "0F7B6C") + sys("accent2", "4A635F") + sys("accent3", "D97706") +
        sys("accent4", "2563EB") + sys("accent5", "9333EA") + sys("accent6", "DC2626") +
        sys("hlink", "0563C1") + sys("folHlink", "954F72") +
        """</a:clrScheme>""" +
        """<a:fontScheme name="PaperVault"><a:majorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
        """<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>""" +
        """<a:fmtScheme name="PaperVault">""" +
        """<a:fillStyleLst>${"""<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""".repeat(3)}</a:fillStyleLst>""" +
        """<a:lnStyleLst>${"""<a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>""".repeat(3)}</a:lnStyleLst>""" +
        """<a:effectStyleLst>${"""<a:effectStyle><a:effectLst/></a:effectStyle>""".repeat(3)}</a:effectStyleLst>""" +
        """<a:bgFillStyleLst>${"""<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""".repeat(3)}</a:bgFillStyleLst>""" +
        """</a:fmtScheme></a:themeElements></a:theme>"""
}
