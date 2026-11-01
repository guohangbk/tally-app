package io.github.guohangbk.tally.domain.parser

import io.github.guohangbk.tally.domain.model.ImportEncoding
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class CsvDocument(
    val rows: List<List<String>>,
    val encoding: ImportEncoding,
)

object CsvCodec {
    private val utf8 = Charsets.UTF_8
    private val gb18030 = Charset.forName("GB18030")

    fun read(bytes: ByteArray): CsvDocument {
        val hasBom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val payload = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        val decoded = decodeStrict(payload, utf8)
        return if (decoded != null) {
            CsvDocument(parseRows(decoded), if (hasBom) ImportEncoding.UTF8_BOM else ImportEncoding.UTF8)
        } else {
            val fallback = decodeStrict(payload, gb18030) ?: String(payload, gb18030)
            CsvDocument(parseRows(fallback), ImportEncoding.GB18030)
        }
    }

    fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        cell.append('"')
                        index++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    ',' -> {
                        row.add(cell.toString())
                        cell.clear()
                    }
                    '\r' -> {
                        if (index + 1 < text.length && text[index + 1] == '\n') index++
                        row.add(cell.toString())
                        cell.clear()
                        rows.add(row)
                        row = mutableListOf()
                    }
                    '\n' -> {
                        row.add(cell.toString())
                        cell.clear()
                        rows.add(row)
                        row = mutableListOf()
                    }
                    else -> cell.append(ch)
                }
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows.filterNot { it.all(String::isBlank) }
    }

    fun write(rows: List<List<String>>, withBom: Boolean = true): ByteArray {
        val text = buildString {
            rows.forEach { row ->
                row.forEachIndexed { index, value ->
                    if (index > 0) append(',')
                    append(escape(value))
                }
                append("\r\n")
            }
        }
        val bytes = text.toByteArray(utf8)
        return if (withBom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes else bytes
    }

    private fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
