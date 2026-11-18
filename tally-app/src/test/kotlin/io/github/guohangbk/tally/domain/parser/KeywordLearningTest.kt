package io.github.guohangbk.tally.domain.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 静默沉淀的判定矩阵穷举：只有「机器来源 + 没命中关键词 + 存了 + 备注像个人话」这一种组合
 * 能写规则，其余一律 null。备注是不是 AI 写的不影响学不学，只改标成哪个来源。
 */
class KeywordLearningTest {

    private fun candidate(
        note: String? = "床单",
        sourceKind: String? = "voice",
        prefillKeyword: String? = null,
        editing: Boolean = false,
        fromAi: Boolean = false,
    ) = KeywordLearning.candidate(note, sourceKind, prefillKeyword, editing, fromAi)

    /** 学到的词本身；没学到就是 null。多数用例只关心这一半。 */
    private fun word(
        note: String? = "床单",
        sourceKind: String? = "voice",
        prefillKeyword: String? = null,
        editing: Boolean = false,
        fromAi: Boolean = false,
    ): String? = candidate(note, sourceKind, prefillKeyword, editing, fromAi)?.word

    @Test
    fun `语音草稿手动选分类后学这个词`() {
        assertEquals("床单", word())
    }

    @Test
    fun `截图草稿同样学`() {
        assertEquals("盒马", word(note = "盒马", sourceKind = "shot"))
    }

    @Test
    fun `编辑历史账单不学`() {
        assertNull(word(editing = true))
    }

    @Test
    fun `手工记一笔不学`() {
        assertNull(word(sourceKind = null))
        assertNull(word(sourceKind = "manual"))
    }

    @Test
    fun `命中了关键词的不进静默路径`() {
        // 这条交给既有的沉淀气泡：改了分类才问，没改就什么都不做。
        assertNull(word(prefillKeyword = "打车"))
        // 空白等价于「没命中过」：这时静默路径照走。
        assertEquals("床单", word(prefillKeyword = " "))
    }

    /** AI 写的备注照样学，只是来源标成 LEARNED_AI——规则越全，后面问 AI 的次数越少。 */
    @Test
    fun `AI 写的备注照样学并带上来源标记`() {
        assertEquals("床单", candidate(fromAi = true)?.word)
        assertEquals(true, candidate(fromAi = true)?.fromAi)
        assertEquals(false, candidate()?.fromAi)
    }

    @Test
    fun `备注长度必须在 2 到 6 之间`() {
        assertEquals("买菜", word(note = "买菜"))
        assertEquals("便利店咖啡", word(note = "便利店咖啡"))
        assertNull(word(note = "肉"))
        assertNull(word(note = "便利店咖啡豆奶"))
        assertNull(word(note = ""))
        assertNull(word(note = null))
    }

    @Test
    fun `先 trim 再判断与取词`() {
        assertEquals("床单", word(note = "  床单  "))
        assertNull(word(note = "  "))
    }

    @Test
    fun `词里有空白的不学`() {
        assertNull(word(note = "买 菜"))
        assertNull(word(note = "床 单"))
    }

    @Test
    fun `纯数字与金额不学`() {
        assertNull(word(note = "1234"))
        assertNull(word(note = "12.5"))
    }

    @Test
    fun `日期不学`() {
        assertNull(word(note = "11月14日"))
        assertNull(word(note = "2026-11-14"))
        assertNull(word(note = "11/14"))
        assertNull(word(note = "14号"))
        // 但带语义的不算日期
        assertEquals("3号店", word(note = "3号店"))
    }

    @Test
    fun `不含中文的不学`() {
        assertNull(word(note = "abc"))
        assertNull(word(note = "KFC"))
        assertNull(word(note = "12ab"))
    }

    @Test
    fun `中英混排只要有中文就学`() {
        assertEquals("星巴克", word(note = "星巴克"))
        assertEquals("KFC店", word(note = "KFC店"))
    }
}
