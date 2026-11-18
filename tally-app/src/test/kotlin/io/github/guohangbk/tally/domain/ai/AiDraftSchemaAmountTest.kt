package io.github.guohangbk.tally.domain.ai

import io.github.guohangbk.tally.domain.model.CategoryType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 「说出来的钱认不出来」这一档：模型常把原话抄回 `amount` 字段（「128块5」），
 * 定点解析吃不下，得退回中文数字。这里锁住真实踩过的两个说法，别再退化回去。
 */
class AiDraftSchemaAmountTest {
    private val categories = listOf(AiCategoryRef("food", "餐饮", null, CategoryType.EXPENSE))

    private fun amountOf(amountJson: String): Long? {
        val raw = """{"bills":[{"type":"EXPENSE","amount":$amountJson,"date":null,"category_id":"food"}]}"""
        val verdict = AiDraftSchema.readBillsFromText(raw, categories, fallbackMillis = 1_700_000_000_000L).single()
        return (verdict as AiDraftVerdict.Accepted).fields.amountMinor
    }

    @Test
    fun `定点写法照旧`() {
        assertEquals(6010L, amountOf("60.1"))
        assertEquals(3500L, amountOf("\"35.00\""))
    }

    @Test
    fun `模型抄回原话里的块与角也能认`() {
        assertEquals(12850L, amountOf("\"128块5\""))
        assertEquals(250L, amountOf("\"两块五\""))
    }

    @Test
    fun `认不出来的金额仍然留空交给用户补`() {
        assertEquals(null, amountOf("\"记账\""))
    }
}
