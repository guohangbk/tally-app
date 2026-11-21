package io.github.guohangbk.tally.data.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 通知抓取的解析器：**只认支付类通知，解析不出金额直接丢弃**。
 *
 * 纯函数、不依赖 Android，金额/商户/订单号与两条丢弃边界都能在这里钉死。
 */
class PaymentNotificationParserTest {

    private fun snapshot(
        title: String,
        text: String,
        extras: Map<String, String> = emptyMap(),
        atMillis: Long = 1_794_600_000_000,
    ) = PaymentSnapshot("com.tencent.mm", title, text, extras, atMillis)

    @Test
    fun `从正文里解析出金额商户与订单号`() {
        val parsed = PaymentNotificationParser.parse(
            snapshot("微信支付", "支付成功 ¥38.50 商户：瑞幸咖啡 订单号：20260101001"),
        )

        assertEquals(3850L, parsed!!.amountMinor)
        assertEquals("瑞幸咖啡", parsed.merchant)
        assertEquals("20260101001", parsed.orderNo)
    }

    @Test
    fun `金额支持元与千分位`() {
        val parsed = PaymentNotificationParser.parse(snapshot("支付宝", "付款成功 1,234.56元"))
        assertEquals(123456L, parsed!!.amountMinor)
    }

    /** 金额换算不能吃掉一分钱：19.99 元是 1999 分，用 Double 乘 100 会变成 1998。 */
    @Test
    fun `金额换算到分不吃零头`() {
        assertEquals(1999L, PaymentNotificationParser.parse(snapshot("支付宝", "支出19.99元"))!!.amountMinor)
        assertEquals(810L, PaymentNotificationParser.parse(snapshot("支付宝", "支出8.10元"))!!.amountMinor)
    }

    @Test
    fun `extras 里的订单号与商户优先`() {
        val parsed = PaymentNotificationParser.parse(
            snapshot(
                "微信支付",
                "支付成功 ¥38.50",
                extras = mapOf("out_trade_no" to "EXTRA-1", "merchant" to "瑞幸咖啡"),
            ),
        )
        assertEquals("EXTRA-1", parsed!!.orderNo)
        assertEquals("瑞幸咖啡", parsed.merchant)
    }

    @Test
    fun `正文里的时刻优先于通知到达时刻`() {
        val parsed = PaymentNotificationParser.parse(
            snapshot("微信支付", "支付成功 ¥38.50 2026-11-19 14:02"),
        )
        // 正文给了时刻，就不该退回通知到达时刻。
        assertEquals(false, parsed!!.occurredAt == 1_794_600_000_000L)
    }

    /** 标题与正文分开给 AI：标题里的金额、正文里的商品名各自归位，营销尾巴剪掉。 */
    @Test
    fun `标题与正文分开且剪掉营销尾巴`() {
        val parsed = PaymentNotificationParser.parse(
            snapshot("支付成功2.9元", "【火锅】手作猪肉小馄饨 126g，点击查看详情"),
        )!!
        assertEquals("【火锅】手作猪肉小馄饨 126g", parsed.body)
        assertEquals("支付成功2.9元 【火锅】手作猪肉小馄饨 126g", parsed.text)
    }

    /** 只有金额、没有任何支付特征的普通提醒，不当支付抓。 */
    @Test
    fun `非支付通知丢弃`() {
        assertNull(PaymentNotificationParser.parse(snapshot("提醒", "今天花了 ¥12.00")))
    }

    /** 推广通知：带「立省」和「到手价」，一个字没提支付，整条丢弃。 */
    @Test
    fun `推广通知整条丢弃`() {
        assertNull(
            PaymentNotificationParser.parse(
                snapshot(
                    "抖音省省",
                    "【立省10.81元]【周四提货】A级-佳沛阳光金果个(33#)【按只售卖|请提前致电门店是否到货】到手4.99元，订单过期自动退",
                ),
            ),
        )
    }

    /** 汇总通知：本周累计消费不是一笔账。 */
    @Test
    fun `汇总通知丢弃`() {
        assertNull(PaymentNotificationParser.parse(snapshot("美团", "本周累计消费580.87元")))
        assertNull(PaymentNotificationParser.parse(snapshot("支付宝", "本月账单已出，合计1,234.56元")))
    }

    /** 一条通知里原价、实付都有时，记的是实付。 */
    @Test
    fun `金额优先取实付而非原价`() {
        val parsed = PaymentNotificationParser.parse(snapshot("支付成功", "原价199元，实付99.50元"))
        assertEquals(9950L, parsed!!.amountMinor)
    }

    /** 满减只否掉它自己那一句的金额，实付那句照记。 */
    @Test
    fun `满减只否掉同句金额`() {
        val parsed = PaymentNotificationParser.parse(snapshot("支付成功", "满100减20，实付80元"))
        assertEquals(8000L, parsed!!.amountMinor)
    }

    /** 强句式里的金额照收，旁边的「立省」不影响。 */
    @Test
    fun `强句式旁边的立省不影响金额`() {
        val parsed = PaymentNotificationParser.parse(snapshot("抖音省省", "支付成功4.99元，已为你立省10.81元"))
        assertEquals(499L, parsed!!.amountMinor)
    }

    @Test
    fun `解析不出金额直接丢弃`() {
        assertNull(PaymentNotificationParser.parse(snapshot("微信支付", "支付成功")))
    }

    @Test
    fun `空通知丢弃`() {
        assertNull(PaymentNotificationParser.parse(snapshot("", "  ")))
    }
}
