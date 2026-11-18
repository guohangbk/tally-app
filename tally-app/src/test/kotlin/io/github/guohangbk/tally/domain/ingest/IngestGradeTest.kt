package io.github.guohangbk.tally.domain.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 判级的穷举表：事实齐全只有一种组合，其余都会在卡上把缺的那一项写出来。
 * 订单号与商户**不在**判级里——订单号只管去重，有没有都不影响这一笔的判级。
 *
 * 口径改了先在这里红。
 */
class IngestGradeTest {

    /** 事实齐全的一笔：金额在原文里、日期是原文给的、分类对上了。 */
    private fun complete(
        duplicate: Boolean = false,
        singleBill: Boolean = true,
    ) = IngestSignals(
        hasAmount = true,
        amountFromText = true,
        dateFromText = true,
        dateMentioned = false,
        hasCategory = true,
        singleBill = singleBill,
        duplicate = duplicate,
    )

    @Test
    fun `事实齐全时判为高且不列缺项`() {
        val verdict = IngestGrader.judge(complete())
        assertEquals(IngestGrade.HIGH, verdict.grade)
        assertTrue(verdict.missing.isEmpty())
    }

    @Test
    fun `没有金额连卡都不出`() {
        val verdict = IngestGrader.judge(complete().copy(hasAmount = false))
        assertEquals(IngestGrade.LOW, verdict.grade)
    }

    @Test
    fun `缺任何一项都降级并写进缺项`() {
        val cases = listOf(
            "金额不在原文里" to complete().copy(amountFromText = false),
            "分类没对上" to complete().copy(hasCategory = false),
            "这一轮不止一笔" to complete(singleBill = false),
        )
        cases.forEach { (label, signals) ->
            val verdict = IngestGrader.judge(signals)
            assertEquals(IngestGrade.MEDIUM, verdict.grade, label)
            assertTrue(verdict.missing.contains(label), "$label 应写进缺项：${verdict.missing}")
        }
    }

    /**
     * 日期不是门槛：口语和大多数截图本来就不带日期，按采集时刻落账是既定口径，
     * 因此它不降级，只在卡上把「今天」标出来。
     */
    @Test
    fun `日期不是原文给的照样判为高只标出来`() {
        val verdict = IngestGrader.judge(complete().copy(dateFromText = false))
        assertEquals(IngestGrade.HIGH, verdict.grade)
        assertTrue(verdict.dateDefaulted)
        assertTrue(verdict.missing.isEmpty())
    }

    @Test
    fun `提到日期却没解析出来就降级并给人话提示`() {
        val verdict = IngestGrader.judge(complete().copy(dateFromText = false, dateMentioned = true))
        assertEquals(IngestGrade.MEDIUM, verdict.grade)
        assertTrue(verdict.dateUnresolved)
        assertTrue(verdict.missing.contains("没识别出日期 · 默认今天"))
    }

    @Test
    fun `日期是原文给的不标默认`() {
        assertFalse(IngestGrader.judge(complete()).dateDefaulted)
    }

    /** 分类是硬条件：认不出分类的那一笔必须落在人手里补。 */
    @Test
    fun `认不出分类就降级并写进缺项`() {
        val verdict = IngestGrader.judge(complete().copy(hasCategory = false))
        assertEquals(IngestGrade.MEDIUM, verdict.grade)
        assertTrue(verdict.missing.contains("分类没对上"))
    }

    @Test
    fun `已记过的同一笔事实仍然齐全`() {
        val verdict = IngestGrader.judge(complete(duplicate = true))
        assertEquals(IngestGrade.HIGH, verdict.grade)
        assertTrue(verdict.duplicate)
    }

    @Test
    fun `一轮多笔一律降级`() {
        assertEquals(IngestGrade.MEDIUM, IngestGrader.judge(complete(singleBill = false)).grade)
    }
}
