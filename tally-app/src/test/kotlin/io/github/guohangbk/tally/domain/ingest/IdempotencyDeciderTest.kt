package io.github.guohangbk.tally.domain.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 跨入口幂等的四档键与窗口：从上到下取第一个命中的，命中即合并，不合并就各出各的。
 *
 * 纯函数、不依赖 Android，所以四档窗口与「只补空不覆盖」都能在这里逐条钉死。
 */
class IdempotencyDeciderTest {

    private val now = 1_000_000L
    private val decider = IdempotencyDecider(now = { now })

    private fun key(
        orderNo: String? = null,
        app: String = "com.tencent.mm",
        title: String = "微信支付",
        body: String = "支付成功",
        amount: Long? = 3850,
        merchant: String? = "瑞幸咖啡",
        at: Long = now,
        source: String = "NOTIFICATION",
    ) = IdempotencyKey(orderNo, app, title, body, amount, merchant, at, source)

    private fun record(
        orderNo: String? = null,
        app: String = "com.tencent.mm",
        title: String = "微信支付",
        body: String = "支付成功",
        amount: Long? = 3850,
        merchant: String? = "瑞幸咖啡",
        at: Long = now,
        sources: List<String> = listOf("NOTIFICATION"),
    ) = IdempotencyRecord(orderNo, app, title, body, amount, merchant, at, sources, now)

    @Test
    fun `完整订单号命中，优先级最高`() {
        val decided = decider.decide(
            key(orderNo = "A1", amount = 9_999, merchant = "别的店"),
            listOf(record(orderNo = "A1", amount = 1, merchant = "别的店")),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.ORDER_NO, match.kind)
    }

    @Test
    fun `订单号超出窗口不合并`() {
        val decided = decider.decide(
            key(orderNo = "A1", at = now),
            listOf(record(orderNo = "A1", at = now - 31L * 24 * 60 * 60 * 1000)),
        )
        assertIs<IdempotencyDecision.NoMatch>(decided)
    }

    /** 重连补收会把通知栏上的同一条再读一遍：postTime 分毫不差，隔多久都认重放。 */
    @Test
    fun `同一条通知重放不受窗口限制`() {
        val decided = decider.decide(
            key(at = now),
            listOf(record(at = now)),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.REPLAY, match.kind)
    }

    /** 两次真支付隔了十分钟、文案一模一样（同店同价）：不是重放，各自入账。 */
    @Test
    fun `相隔十分钟的同价两笔不算重放`() {
        val decided = decider.decide(
            key(at = now),
            listOf(record(at = now - 10 * 60_000L)),
        )
        assertIs<IdempotencyDecision.NoMatch>(decided)
    }

    @Test
    fun `同渠道重复通知在窗口内合并`() {
        val decided = decider.decide(
            key(at = now),
            listOf(record(at = now - 20_000)),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.SAME_CHANNEL, match.kind)
    }

    @Test
    fun `同渠道超出窗口不合并`() {
        val decided = decider.decide(
            key(at = now),
            // 过了同渠道 60 秒窗口就不合并；金额故意错开，免得又落到跨渠道那几档兜底上。
            listOf(record(at = now - 90_000, amount = 3851)),
        )
        assertIs<IdempotencyDecision.NoMatch>(decided)
    }

    /** 连买两杯同价咖啡：同渠道、同金额、同商户，但订单号不同，是两笔，不能并成一笔。 */
    @Test
    fun `同价不同订单号不合并`() {
        val decided = decider.decide(
            key(orderNo = "A1", at = now),
            listOf(record(orderNo = "A2", at = now - 10_000)),
        )
        assertIs<IdempotencyDecision.NoMatch>(decided)
    }

    @Test
    fun `跨渠道同金额同商户合并`() {
        val decided = decider.decide(
            key(app = "com.eg.android.AlipayGphone", at = now),
            listOf(record(app = "com.tencent.mm", at = now - 60_000)),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.CROSS_CHANNEL_MERCHANT, match.kind)
    }

    @Test
    fun `跨渠道同金额但商户不同不合并`() {
        val decided = decider.decide(
            key(app = "com.eg.android.AlipayGphone", merchant = "星巴克", at = now),
            listOf(record(app = "com.tencent.mm", merchant = "瑞幸咖啡", at = now - 60_000)),
        )
        assertIs<IdempotencyDecision.NoMatch>(decided)
    }

    @Test
    fun `跨渠道无商户时按同应用同金额合并`() {
        val decided = decider.decide(
            // 标题正文不同，才不会先被「同渠道重复通知」那一档截走，落到无商户兜底。
            key(app = "com.tencent.mm", title = "微信收款", body = "收款", merchant = null, amount = 3850, at = now),
            listOf(record(app = "com.tencent.mm", merchant = null, amount = 3850, at = now - 30_000)),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.CROSS_CHANNEL_APP, match.kind)
    }

    @Test
    fun `跨渠道无商户超出窗口不合并`() {
        val decided = decider.decide(
            key(app = "com.tencent.mm", merchant = null, amount = 3850, at = now),
            listOf(record(app = "com.tencent.mm", merchant = null, amount = 3850, at = now - 120_000)),
        )
        assertIs<IdempotencyDecision.NoMatch>(decided)
    }

    /** 美团下单 + 支付宝付款：两个应用、同一个金额、都没解析出商户，按同一笔合并。 */
    @Test
    fun `不同应用同金额同笔合并`() {
        val decided = decider.decide(
            key(app = "com.eg.android.AlipayGphone", merchant = null, amount = 1000, title = "交易提醒", body = "你有一笔10.00元的支出"),
            listOf(record(app = "com.sankuai.meituan", merchant = null, amount = 1000, title = "美团", body = "订单已支付", at = now - 30_000)),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.CROSS_CHANNEL_AMOUNT, match.kind)
    }

    /**
     * 真实场景：美团「订单已支付」带美团订单号，支付宝「交易提醒」带支付宝交易号。
     * 两个系统的单号天然不同，不能拿它当「两笔」的证据；同金额同商户就该并成一笔。
     */
    @Test
    fun `跨渠道各自带订单号也合并`() {
        val decided = decider.decide(
            key(
                orderNo = "2026112022001450001234567890",
                app = "com.eg.android.AlipayGphone",
                title = "交易提醒",
                body = "你有一笔28.50元的支出",
                amount = 2850,
                merchant = "肯德基",
                at = now,
            ),
            listOf(
                record(
                    orderNo = "2601201234567890",
                    app = "com.sankuai.meituan",
                    title = "美团外卖",
                    body = "订单已支付，商家：肯德基",
                    amount = 2850,
                    merchant = "肯德基",
                    at = now - 40_000,
                ),
            ),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.CROSS_CHANNEL_MERCHANT, match.kind)
    }

    /** 同金额、没解析出商户、两边订单号还都不一样：靠金额 + 3 分钟窗兜底。 */
    @Test
    fun `跨渠道无商户带不同订单号按金额合并`() {
        val decided = decider.decide(
            key(orderNo = "ALI123456", app = "com.eg.android.AlipayGphone", amount = 1000, merchant = null, at = now),
            listOf(record(orderNo = "MT987654", app = "com.sankuai.meituan", amount = 1000, merchant = null, at = now - 150_000)),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.CROSS_CHANNEL_AMOUNT, match.kind)
    }

    /** 商户名带门店后缀（「肯德基」与「肯德基（望京店）」）要能认成一家。 */
    @Test
    fun `商户名带门店后缀也合并`() {
        val decided = decider.decide(
            key(app = "com.eg.android.AlipayGphone", amount = 2850, merchant = "肯德基（望京店）", at = now),
            listOf(record(app = "com.sankuai.meituan", amount = 2850, merchant = "肯德基", at = now - 60_000)),
        )
        val match = assertIs<IdempotencyDecision.Match>(decided)
        assertEquals(IdempotencyKeyKind.CROSS_CHANNEL_MERCHANT, match.kind)
    }

    /** 同一家店连买两杯同价咖啡：同渠道、单号不同，隔了两分钟也是两笔。 */
    @Test
    fun `同渠道同商户同金额不同单号仍不合并`() {
        val decided = decider.decide(
            key(orderNo = "A2", app = "com.tencent.mm", amount = 1900, merchant = "瑞幸咖啡", at = now),
            listOf(record(orderNo = "A1", app = "com.tencent.mm", amount = 1900, merchant = "瑞幸咖啡", at = now - 120_000)),
        )
        assertIs<IdempotencyDecision.NoMatch>(decided)
    }

    /** 先到者保留：后到的只补空字段，不覆盖人已经改过的值。 */
    @Test
    fun `合并只补空字段不覆盖已有值`() {
        val earlier = record(orderNo = null, merchant = "瑞幸咖啡", amount = 3850)
        val merged = decider.merge(earlier, key(orderNo = "A1", merchant = "星巴克", amount = 9_999))

        assertEquals("A1", merged.orderNo)
        assertEquals("瑞幸咖啡", merged.merchant)
        assertEquals(3850L, merged.amountMinor)
        assertEquals(now, merged.occurredAt)
    }

    /** 来源不丢：合并后一行带两枚来源。 */
    @Test
    fun `合并把两路来源都留住`() {
        val merged = decider.merge(
            record(sources = listOf("NOTIFICATION")),
            key(app = "com.eg.android.AlipayGphone", source = "PAYMENT"),
        )
        assertEquals(listOf("NOTIFICATION", "PAYMENT"), merged.sources)
    }

    @Test
    fun `台账里没有可命中的键时不合并`() {
        assertFalse(decider.decide(key(), emptyList()) is IdempotencyDecision.Match)
        assertTrue(decider.decide(key(), emptyList()) is IdempotencyDecision.NoMatch)
    }
}
