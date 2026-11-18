package io.github.guohangbk.tally.domain.parser

import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.model.TimePrecision
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 截图日期这一格：OCR 认出来的日期要能落进去，认不出来就得留空——
 * 留空之后由管线按采集时刻补，这里绝不能自己编一个「今天」。
 */
class ScreenshotRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMillis = Instant.parse("2026-11-14T02:00:00Z").toEpochMilli()

    private fun stamp(millis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString()

    private fun extract(text: String) = ScreenshotRules.extract(text, nowMillis, zone)

    /** 微信 / 支付宝截图最常省掉年份（「10-14 21:06」），按采集当年补上。 */
    @Test
    fun `没写年份的月日时刻按采集当年补`() {
        val fields = extract("商户订单号 10086\n10-14 21:06\n实付款 ¥35.00")
        assertEquals("2026-10-14T21:06", stamp(fields.occurredAt!!))
        assertEquals(TimePrecision.DATETIME, fields.timePrecision)
    }

    @Test
    fun `只写了月日没有时刻就按整天算`() {
        val fields = extract("商品说明：午饭\n11月13日\n¥35.00")
        assertEquals("2026-11-13T00:00", stamp(fields.occurredAt!!))
        assertEquals(TimePrecision.DATE, fields.timePrecision)
    }

    @Test
    fun `截图里没有日期就留空不编一个今天`() {
        val fields = extract("实付款 ¥35.00")
        assertNull(fields.occurredAt)
        assertTrue(DraftField.DATE in fields.missingFields)
    }

    @Test
    fun `单个实付款标签不会被重叠词重复计数`() {
        val text = "商户：盒马\n实付款 ¥35.00"
        assertEquals(1, ScreenshotRules.totalLabelCount(text))
        assertFalse(ScreenshotRules.needsLlm(text))
    }

    /** 「3-4 期」这种没跟时刻的短横线不是日期：认成日期比留空更糟，会静默落到别的日子。 */
    @Test
    fun `期数里的短横线不当成日期`() {
        val fields = extract("分期计划 3-4 期\n实付款 ¥35.00")
        assertNull(fields.occurredAt)
    }

    /**
     * 一笔订单同时印着「订单金额」和「实付款」（滴滴那类截图）时不能拆成两笔：
     * 终值标签只出现一次就说明整张图只有一笔，另一个标签只是同一笔的字段。
     */
    @Test
    fun `同一笔的订单金额加实付款不拆成两笔`() {
        val text = "滴滴出行\n订单金额 ¥46.50\n实付款 ¥46.50\n2026-11-13 21:06"
        val bills = ScreenshotRules.extractAll(text, nowMillis, zone)
        assertEquals(1, bills.size)
        assertEquals(4650L, bills.single().amountMinor)
        assertFalse(ScreenshotRules.needsLlm(text))
    }

    @Test
    fun `一张图里的多个总额各自成账`() {
        val text = "实付款 ¥35.00\n商户：盒马\n订单号：ABCDEFGH11111111\n" +
            "实付款 ¥20.00\n商户：打车\n订单号：ABCDEFGH22222222"
        val bills = ScreenshotRules.extractAll(text, nowMillis, zone)
        assertEquals(2, bills.size)
        assertEquals(3500L, bills[0].amountMinor)
        assertEquals(2000L, bills[1].amountMinor)
        assertFalse(ScreenshotRules.needsLlm(text))
    }
}
