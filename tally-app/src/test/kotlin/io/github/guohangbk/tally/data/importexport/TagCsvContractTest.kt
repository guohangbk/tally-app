package io.github.guohangbk.tally.data.importexport

import io.github.guohangbk.tally.domain.model.ImportSourceType
import io.github.guohangbk.tally.domain.parser.CsvCodec
import io.github.guohangbk.tally.domain.parser.ImportParsers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 「导出 → 导入」这条链路的标签契约：14 列导出的「标签」列必须能被模板解析器原样读回，
 * 模板（13 列、无 ID）同样；多枚标签用顿号连接，导入侧按名拆分。新名字落成新标签
 * 的写库动作在 ImportService，这里守住列名、分隔符与去重。
 */
class TagCsvContractTest {

    @Test
    fun `模板自带的标签列能被原样读回`() {
        val table = ImportParsers.readTable(CsvCodec.write(TallyCsvSchema.templateRows()), "tally_template.csv")
        assertEquals(ImportSourceType.TEMPLATE, table.sourceType)

        val tags = ImportParsers.parseRows(table).map { it.tagNames }
        assertEquals(listOf(emptyList(), listOf("🚗 车"), emptyList(), listOf("🚗 车", "📄 续保")), tags)
    }

    @Test
    fun `导出的标签列能被模板解析器读回`() {
        val rows = listOf(
            TallyCsvSchema.header,
            listOf(
                "bill-1", "2026-11-02 09:15:00", "日常账本", "交通", "停车费", "支出",
                "120.00", "CNY", "商场停车", "", "", "", "", "🚗 车、📄 续保",
            ),
        )
        val table = ImportParsers.readTable(CsvCodec.write(rows), "tally_export.csv")
        assertEquals(ImportSourceType.TEMPLATE, table.sourceType)
        assertEquals(listOf("🚗 车", "📄 续保"), ImportParsers.parseRows(table).single().tagNames)
    }

    @Test
    fun `顿号分号都能分隔且空格被去掉并按名去重`() {
        val rows = listOf(
            TallyCsvSchema.header,
            listOf(
                "bill-1", "2026-11-02 09:15:00", "日常账本", "交通", "停车费", "支出",
                "120.00", "CNY", "商场停车", "", "", "", "", " 旅行 、结婚;旅行；装修 ",
            ),
        )
        val table = ImportParsers.readTable(CsvCodec.write(rows), "tally_export.csv")
        assertEquals(
            listOf("旅行", "结婚", "装修"),
            ImportParsers.parseRows(table).single().tagNames,
        )
    }
}
