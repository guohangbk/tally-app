package io.github.guohangbk.tally.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 皮肤色表的对比度守卫。
 *
 * 色表是手抄的十套 × 两档 = 20 组数值，改一处很容易把某一套的小字压到看不清。
 * 这里按 WCAG 相对亮度逐套核一遍，阈值取设计稿定的那一档：
 * 正文与次文 4.5（要读清的句子）、弱文 3.0（只承载辅助信息）、按钮与汇总条 4.5、
 * 支出/收入在卡片上 3.0（它们是数字不是正文，但也不能糊成纸面）。
 */
class TallySkinTest {

    @Test
    fun `每套皮肤的正文次文弱文都压得住纸面`() {
        for (skin in TallySkins.ordered) {
            for (dark in listOf(false, true)) {
                val tone = TallySkins.tone(skin, dark)
                assertAtLeast("正文", ratio(tone.ink, tone.paper), 4.5, skin, dark)
                assertAtLeast("次文", ratio(tone.ink2, tone.paper), 4.5, skin, dark)
                assertAtLeast("弱文", ratio(tone.ink3, tone.paper), 3.0, skin, dark)
            }
        }
    }

    @Test
    fun `按钮字与汇总条字都压得住自己的底色`() {
        for (skin in TallySkins.ordered) {
            for (dark in listOf(false, true)) {
                val tone = TallySkins.tone(skin, dark)
                assertAtLeast("按钮", ratio(tone.onBrand, tone.brand), 4.5, skin, dark)
                assertAtLeast("汇总", ratio(tone.onSummaryBar, tone.summaryBar), 4.5, skin, dark)
            }
        }
    }

    /**
     * 语义色不跟皮肤走，所以它们必须压得住**每一套**的卡片色——
     * 否则换到暖金、藤紫这种带色相的纸面上，金额会糊进背景。
     */
    @Test
    fun `支出与收入在每套皮肤的卡片上都看得见`() {
        for (skin in TallySkins.ordered) {
            for (dark in listOf(false, true)) {
                val card = TallySkins.tone(skin, dark).card
                assertAtLeast("支出", ratio(TallySemantic.expense(dark), card), 3.0, skin, dark)
                assertAtLeast("收入", ratio(TallySemantic.income(dark), card), 3.0, skin, dark)
            }
        }
    }

    /** 收入钉死成绿：它一旦等于 brand，「绿=进钱」就随皮肤废掉。 */
    @Test
    fun `收入色不随皮肤变化`() {
        assertEquals(TallySemantic.income(dark = false), 0xFF1F4A38.toInt())
        assertEquals(TallySemantic.income(dark = true), 0xFF7ECBA9.toInt())
    }

    @Test
    fun `皮肤键与名称唯一且顺序稳定`() {
        assertEquals(TallySkins.ordered.map { it.key }.distinct().size, TallySkins.ordered.size)
        assertEquals(TallySkins.ordered.map { it.label }.distinct().size, TallySkins.ordered.size)
        assertEquals(TallySkin.entries.toList(), TallySkins.ordered)
    }

    /** 手改偏好、降级安装都可能带进来脏 key：回落纸墨，不能抛也不能画空白。 */
    @Test
    fun `未知皮肤键回落纸墨`() {
        assertEquals(TallySkin.INK, TallySkin.fromKey(null))
        assertEquals(TallySkin.INK, TallySkin.fromKey(""))
        assertEquals(TallySkin.INK, TallySkin.fromKey("neon-pink"))
        assertEquals(TallySkin.AMBER, TallySkin.fromKey("amber"))
    }

    @Test
    fun `每套皮肤都配齐浅色与深色两档`() {
        for (skin in TallySkins.ordered) {
            val light = TallySkins.tone(skin, dark = false)
            val dark = TallySkins.tone(skin, dark = true)
            // 深色档的纸面必须真的更暗，否则「档」这个概念就是假的。
            assertTrue(luminance(light.paper) > luminance(dark.paper), "${skin.key} 深浅两档没分开")
        }
    }

    private fun assertAtLeast(
        what: String,
        actual: Double,
        need: Double,
        skin: TallySkin,
        dark: Boolean,
    ) {
        assertTrue(
            actual >= need,
            "$what 对比度不足：${skin.key}/${if (dark) "dark" else "light"} 实测 %.2f，要求 ≥ %.1f".format(actual, need),
        )
    }

    /** WCAG 2.1 相对亮度：先拆 gamma，再加权。 */
    private fun luminance(argb: Int): Double {
        val channels = intArrayOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF).map { raw ->
            val srgb = raw / 255.0
            if (srgb <= 0.03928) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
