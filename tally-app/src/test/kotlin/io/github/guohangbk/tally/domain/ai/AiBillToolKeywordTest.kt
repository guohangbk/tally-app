package io.github.guohangbk.tally.domain.ai

import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.parser.KeywordMapping
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 关键词提示只带用户自建的词；内置常用词留给本机规则，不外发。 */
class AiBillToolKeywordTest {
    private val catalog = AiBillTool.catalog(
        listOf(
            AiCategoryRef("fuel", "交通", null, CategoryType.EXPENSE),
            AiCategoryRef("coffee", "咖啡", null, CategoryType.EXPENSE),
        ),
    )
    private val keywords = listOf(
        KeywordMapping("加油", "fuel"),
        KeywordMapping("咖啡", "coffee"),
        KeywordMapping("早饭", "coffee", priority = 10, builtin = true),
    )

    @Test
    fun `只带用户自建的关键词`() {
        val table = catalog.keywordTable(keywords)
        assertTrue(table.contains("加油 → c01"))
        assertTrue(table.contains("咖啡 → c02"))
    }

    @Test
    fun `内置词不进提示词`() {
        assertFalse(catalog.keywordTable(keywords).contains("早饭"))
    }

    @Test
    fun `分类表里没有的词整条丢掉`() {
        assertTrue(catalog.keywordTable(listOf(KeywordMapping("楼下面馆", "ghost"))).isEmpty())
    }
}
