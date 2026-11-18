package io.github.guohangbk.tally.domain.ingest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本机复核的两件事：订单号找得到/找不到、金额是不是真在原文里。
 * 判级靠它们决定「够不够格自动写入」，所以边界（千分位、整数金额、数字挨着数字）都要钉住。
 */
class IngestTextTest {

    @Test
    fun `四种前缀都能认`() {
        assertEquals("2026011400123456", findOrderNo("商户订单号：2026011400123456"))
        assertEquals("2026011400123456", findOrderNo("订单号 2026011400123456"))
        assertEquals("T200P1234abcd", findOrderNo("交易单号:T200P1234abcd"))
        assertEquals("87654321", findOrderNo("订单编号：87654321"))
    }

    @Test
    fun `没有订单号就返回 null，不编一个`() {
        assertNull(findOrderNo("支付宝 沙县小吃 12.00"))
        assertNull(findOrderNo("订单号：12"))
    }

    @Test
    fun `金额在原文里的常见写法都认`() {
        assertTrue(amountAppearsInText("沙县小吃 12.00", 1200))
        assertTrue(amountAppearsInText("午饭 ¥29.85", 2985))
        assertTrue(amountAppearsInText("地铁 3.5 元", 350))
        assertTrue(amountAppearsInText("付款 35.50", 3550))
        assertTrue(amountAppearsInText("整单 35", 3500))
        assertTrue(amountAppearsInText("整单 35.00 元", 3500))
        assertTrue(amountAppearsInText("合计 1,280.00", 128000))
        assertTrue(amountAppearsInText("早上吃饭花了五块", 500))
        assertTrue(amountAppearsInText("昨天在盒马买菜花了128块5", 12850))
        assertTrue(amountAppearsInText("盒马买菜60.1", 6010))
    }

    @Test
    fun `金额不在原文里一律不算数`() {
        assertFalse(amountAppearsInText("沙县小吃", 1200))
        // 35 不该命中 1350：数字两侧挨着数字不算命中。
        assertFalse(amountAppearsInText("订单 1350", 3500))
        // 金额 35.00 不该被「35.5」蒙过去。
        assertFalse(amountAppearsInText("付款 35.5", 3500))
        assertFalse(amountAppearsInText("", 1200))
        assertFalse(amountAppearsInText("付款 12.00", 0))
        assertFalse(amountAppearsInText("订单号 2026111412345678", 500))
    }

    @Test
    fun `图片指纹对同一份输入稳定、对不同输入不同`() {
        val a = imageFingerprint(byteArrayOf(1, 2, 3, 4))
        val b = imageFingerprint(byteArrayOf(1, 2, 3, 4))
        val c = imageFingerprint(byteArrayOf(1, 2, 3, 5))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertTrue(a.startsWith("img:"))
    }
}
