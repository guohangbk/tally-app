package io.github.guohangbk.tally.domain.parser

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

data class XlsxSheet(val name: String, val rows: List<List<String>>)

object XlsxCodec {
    fun read(bytes: ByteArray): List<List<String>> {
        val entries = unzip(bytes)
        val shared = entries["xl/sharedStrings.xml"]?.let(::readSharedStrings).orEmpty()
        val sheetPath = resolveFirstSheetPath(entries) ?: "xl/worksheets/sheet1.xml"
        val sheetBytes = entries[sheetPath] ?: throw IllegalArgumentException("XLSX 缺少工作表")
        return readSheet(sheetBytes, shared)
    }

    fun write(sheets: List<XlsxSheet>): ByteArray {
        require(sheets.isNotEmpty()) { "至少需要一个工作表" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putText("[Content_Types].xml", contentTypes(sheets.size))
            zip.putText("_rels/.rels", rootRels())
            zip.putText("xl/workbook.xml", workbook(sheets))
            zip.putText("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            sheets.forEachIndexed { index, sheet ->
                zip.putText("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet.rows))
            }
        }
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun readSharedStrings(bytes: ByteArray): List<String> {
        val doc = document(bytes)
        val nodes = doc.getElementsByTagName("si")
        return (0 until nodes.length).map { index ->
            val si = nodes.item(index) as Element
            val texts = si.getElementsByTagName("t")
            buildString { for (i in 0 until texts.length) append(texts.item(i).textContent) }
        }
    }

    private fun resolveFirstSheetPath(entries: Map<String, ByteArray>): String? {
        val workbook = entries["xl/workbook.xml"] ?: return null
        val rels = entries["xl/_rels/workbook.xml.rels"] ?: return null
        val sheet = document(workbook).getElementsByTagName("sheet").item(0) as? Element ?: return null
        val relId = sheet.getAttribute("r:id")
        val relDoc = document(rels)
        val relationships = relDoc.getElementsByTagName("Relationship")
        for (i in 0 until relationships.length) {
            val relationship = relationships.item(i) as Element
            if (relationship.getAttribute("Id") == relId) {
                val target = relationship.getAttribute("Target").removePrefix("/")
                return if (target.startsWith("xl/")) target else "xl/$target"
            }
        }
        return null
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val doc = document(bytes)
        val rows = doc.getElementsByTagName("row")
        val result = mutableListOf<List<String>>()
        for (rowIndex in 0 until rows.length) {
            val row = rows.item(rowIndex) as Element
            val cells = row.getElementsByTagName("c")
            val values = linkedMapOf<Int, String>()
            var sequential = 0
            for (cellIndex in 0 until cells.length) {
                val cell = cells.item(cellIndex) as Element
                val reference = cell.getAttribute("r")
                val column = columnIndex(reference).takeIf { it >= 0 } ?: sequential
                sequential = column + 1
                val type = cell.getAttribute("t")
                val value = when (type) {
                    "s" -> cell.getElementsByTagName("v").item(0)?.textContent?.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                    "inlineStr" -> cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                    else -> cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                }
                values[column] = value
            }
            if (values.isNotEmpty()) {
                val width = (values.keys.maxOrNull() ?: -1) + 1
                result.add(List(width) { values[it].orEmpty() })
            }
        }
        return result
    }

    private fun columnIndex(reference: String): Int {
        var value = 0
        for (ch in reference.takeWhile(Char::isLetter)) value = value * 26 + (ch.uppercaseChar() - 'A' + 1)
        return value - 1
    }

    /**
     * Android 自带的 Expat 解析器不认识这几条 JAXP 特性，setFeature 会直接抛
     * ParserConfigurationException——之前所有 xlsx 导入都卡在这里。
     * 逐条 try：能设上的设上，设不上的跳过（Android 本来也不解析外部实体）。
     */
    private fun document(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isExpandEntityReferences = false }
        runCatching { isXIncludeAware = false }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun ZipOutputStream.putText(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        repeat(sheetCount) { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
        append("</Types>")
    }

    private fun rootRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private fun workbook(sheets: List<XlsxSheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { index, sheet ->
            append("""<sheet name="${escape(sheet.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        repeat(sheetCount) { index ->
            append("""<Relationship Id="rId${index + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${index + 1}.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun worksheet(rows: List<List<String>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { rowIndex, row ->
            append("<row r=\"${rowIndex + 1}\">")
            row.forEach { value ->
                append("""<c t="inlineStr"><is><t xml:space="preserve">${escape(value)}</t></is></c>""")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
