package io.github.guohangbk.tally.domain.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DateMentionTest {
    @Test
    fun `相对说法与数字日期都算提到了日期`() {
        listOf(
            "昨天在盒马买菜花了128块5",
            "10月14日 买菜 30.5",
            "2026-11-13 午饭 28.8",
            "上周三打车花了20",
        ).forEach { assertTrue(DateMention.mentions(it), it) }
    }

    @Test
    fun `金额时刻与订单号不当成日期`() {
        listOf(
            "买菜花了30块5",
            "早上吃饭花了五块",
            "订单号：2026111412345678 付了12.00",
            "12.50 元 咖啡",
        ).forEach { assertFalse(DateMention.mentions(it), it) }
    }
}
