package io.github.guohangbk.tally.domain.ai

import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.parser.KeywordMapping
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiBillToolBatchTest {
    private val catalog = AiBillTool.catalog(
        listOf(AiCategoryRef("food", "餐饮", null, CategoryType.EXPENSE)),
    )
    private val now = 1_700_000_000_000L

    private fun read(arguments: String): AiBillTool.BillsVerdict = AiBillTool.readAll(
        completion = AiCompletion(
            text = "",
            usage = AiUsage(null, null),
            latencyMillis = 0,
            toolCalls = listOf(AiToolCall(AiBillTool.PROPOSE_BILLS, arguments)),
        ),
        catalog = catalog,
        fallbackMillis = now,
        zone = ZoneId.of("Asia/Shanghai"),
        batch = true,
    )

    @Test
    fun `同一张图的多笔订单按图与订单组合区分`() {
        val verdict = read(
            """
            {"bills":[
              {"type":"EXPENSE","amount":35.0,"category_id":"c01","material_index":0,"item_index":0},
              {"type":"EXPENSE","amount":20.0,"category_id":"c01","material_index":0,"item_index":1}
            ]}
            """.trimIndent(),
        )
        assertTrue(verdict is AiBillTool.BillsVerdict.Some)
        assertEquals(2, verdict.accepted.size)
    }

    @Test
    fun `多图批量漏填来源时整批拒绝`() {
        val verdict = read(
            """{"bills":[{"type":"EXPENSE","amount":35.0,"category_id":"c01","item_index":0}]}""",
        )
        assertTrue(verdict is AiBillTool.BillsVerdict.Rejected)
    }

    /**
     * 用户沉淀的词要跟着提示词一起发（小莉对话那条抽卡链路与首页三条入口共用这一份），
     * 分类只写短柄；本次分类表里没有的词整条丢掉，不给模型编分类的机会。
     */
    @Test
    fun `沉淀词按短柄写进抽账提示词`() {
        val table = AiBillTool
            .catalog(
                listOf(
                    AiCategoryRef("food", "餐饮", null, CategoryType.EXPENSE),
                    AiCategoryRef("traffic", "交通", null, CategoryType.EXPENSE),
                ),
            )
            .keywordTable(
                listOf(
                    KeywordMapping("楼下的面馆", "food", priority = 20),
                    KeywordMapping("加油站", "traffic", priority = 5),
                    KeywordMapping("已经不存在的分类", "ghost", priority = 99),
                ),
            )
        assertTrue(table.contains("楼下的面馆 → c01"))
        assertTrue(table.contains("加油站 → c02"))
        assertFalse(table.contains("已经不存在的分类"))

        val instruction = AiPrompts.billExtractionInstruction(
            timeAnchor = "今天：2026-01-01",
            categoryTable = "可选分类（id | 收支 | 分类）：",
            keywordTable = table,
        )
        assertTrue(instruction.contains("用户自己沉淀过的说法"))
        assertTrue(instruction.trimEnd().endsWith("加油站 → c02"))
    }
}
