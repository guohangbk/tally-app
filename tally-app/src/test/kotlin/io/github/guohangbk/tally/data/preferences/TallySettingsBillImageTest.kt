package io.github.guohangbk.tally.data.preferences

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 账单图片的唯一读取口是 [TallySettings.billImageSaved]：**主开关开且子项开**才真的存来源图。
 *
 * 识别管线、记账页、卡上全部只认这一个派生值，所以这三格组合要钉死。
 */
class TallySettingsBillImageTest {

    @Test
    fun `主开关关着时一律不存来源图`() {
        assertFalse(
            TallySettings.DEFAULT.copy(billImageEnabled = false, autoSaveBillImage = true).billImageSaved,
        )
    }

    @Test
    fun `主开关与子项都开才存来源图`() {
        assertTrue(
            TallySettings.DEFAULT.copy(billImageEnabled = true, autoSaveBillImage = true).billImageSaved,
        )
    }

    @Test
    fun `主开关开了但子项关着也不存`() {
        assertFalse(
            TallySettings.DEFAULT.copy(billImageEnabled = true, autoSaveBillImage = false).billImageSaved,
        )
    }
}
