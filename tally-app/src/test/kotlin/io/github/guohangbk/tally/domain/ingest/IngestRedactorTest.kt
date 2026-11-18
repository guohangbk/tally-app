package io.github.guohangbk.tally.domain.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestRedactorTest {
    @Test
    fun `遮掉手机号与银行卡号且不留可还原数字`() {
        val text = "付款 35.00 手机号 13812345678 银行卡 6222020202020202020"
        val redaction = IngestRedactor.redact(text)
        assertFalse(redaction.text.contains("13812345678"))
        assertFalse(redaction.text.contains("6222020202020202020"))
        assertTrue(redaction.text.contains("35.00"))
        assertTrue(redaction.maskedCount >= 2)
    }

    @Test
    fun `订单号留后四位其余打码`() {
        val text = "商户订单号：ABCDEFGH12345678\n支付金额 ¥12.50"
        val redaction = IngestRedactor.redact(text)
        assertTrue(redaction.text.contains("****5678"))
        assertFalse(redaction.text.contains("ABCDEFGH1234"))
        assertTrue(redaction.text.contains("12.50"))
    }

    @Test
    fun `身份证快递单与详细地址一起遮掉`() {
        val text = "身份证 11010119900101123X\n快递单号 SF1234567890123\n详细地址：北京市朝阳区某路 1 号院 2 单元"
        val redaction = IngestRedactor.redact(text)
        assertFalse(redaction.text.contains("11010119900101123X"))
        assertFalse(redaction.text.contains("SF1234567890123"))
        assertFalse(redaction.text.contains("某路 1 号院"))
        assertTrue(redaction.text.contains("地址已隐藏"))
    }

    @Test
    fun `空文本不产生遮罩`() {
        assertEquals(0, IngestRedactor.redact("").maskedCount)
    }
}
