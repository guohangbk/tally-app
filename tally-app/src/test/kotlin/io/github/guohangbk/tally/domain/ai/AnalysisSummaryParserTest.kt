package io.github.guohangbk.tally.domain.ai

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 流式小结：只显示已经闭合的字段，半截值不能提前出现在界面上。 */
class AnalysisSummaryParserTest {

    @Test
    fun `完整 JSON 仍按原样解析`() {
        val parsed = AnalysisSummaryParser.parse(
            """{"区间环比":"涨了 12%","区间同比":"持平","最多支出":"餐饮 800","控制建议":"少点外卖","总结":"还行"}""",
        )
        assertEquals("涨了 12%", parsed[AnalysisField.MOM])
        assertEquals("还行", parsed[AnalysisField.SUMMARY])
    }

    @Test
    fun `流式只取已经闭合的字段`() {
        val partial = """{"区间环比":"本月多花了 120 元","区间同比":"比去年同期多 8%","最多支出":"餐饮"""
        val parsed = AnalysisSummaryParser.parsePartial(partial)
        assertEquals("本月多花了 120 元", parsed[AnalysisField.MOM])
        assertEquals("比去年同期多 8%", parsed[AnalysisField.YOY])
        assertEquals(null, parsed[AnalysisField.TOP])
    }

    @Test
    fun `流式分行写法忽略没换行的最后一行`() {
        val parsed = AnalysisSummaryParser.parsePartial("区间环比：涨 5%\n最多支出：餐饮 800\n总结：还在")
        assertEquals("涨 5%", parsed[AnalysisField.MOM])
        assertEquals("餐饮 800", parsed[AnalysisField.TOP])
        assertEquals(null, parsed[AnalysisField.SUMMARY])
    }

    @Test
    fun `值里的转义引号不会把字段截断`() {
        val parsed = AnalysisSummaryParser.parsePartial("""{"总结":"他说\"省着点\"，还行"}""")
        assertEquals("他说\"省着点\"，还行", parsed[AnalysisField.SUMMARY])
        assertTrue(parsed.isNotEmpty())
    }
}
