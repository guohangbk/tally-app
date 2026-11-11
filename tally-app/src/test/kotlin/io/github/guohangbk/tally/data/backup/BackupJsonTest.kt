package io.github.guohangbk.tally.data.backup

import io.github.guohangbk.tally.support.BackupContractAssertions
import io.github.guohangbk.tally.support.BackupTableCases
import io.github.guohangbk.tally.support.EntityFixtures
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.json.JSONObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 备份主流程的回归网，正对四个既有坑：
 * - 坑 1「备份 codec 漏 installment_plan.note」→ 16 张表的往返契约 + 单独的字段断言；
 * - 坑 3「删期次后已入账失真」→ 软删行必须原样跟着备份走（InstallmentPlanViewTest 管口径）；
 * - 坑 4「REPLACE 吞回收站行」→ 同上，回收站行丢不了才谈得上不被覆盖。
 */
class BackupJsonTest {

    /** 一张表四个契约，共 64 个动态用例；加表/加字段自动跟着扩。 */
    @TestFactory
    fun `每张表的编解码契约`(): List<DynamicTest> = BackupTableCases.all.flatMap { case ->
        listOf(
            DynamicTest.dynamicTest("${case.name} · 往返不丢字段") {
                BackupContractAssertions.roundTripIsLossless(case)
            },
            DynamicTest.dynamicTest("${case.name} · 软删行保留") {
                BackupContractAssertions.softDeletedRowSurvives(case)
            },
            DynamicTest.dynamicTest("${case.name} · 空表稳定") {
                BackupContractAssertions.emptyTableIsStable(case)
            },
            DynamicTest.dynamicTest("${case.name} · 未知字段忽略") {
                BackupContractAssertions.unknownFieldsAreIgnored(case)
            },
        )
    }

    @Test
    fun `分期计划的备注进入备份包`() {
        val plan = EntityFixtures.installmentPlan().copy(note = "等额本金 · 30 年 · 提前还过一次")
        val json = BackupJson.encode(BackupSnapshot(installmentPlans = listOf(plan)))
        val encodedPlan = JSONObject(json).getJSONArray("installmentPlans").getJSONObject(0)
        assertEquals(plan.note, encodedPlan.getString("note"))
        assertEquals(plan.note, BackupJson.decode(json).installmentPlans.single().note)
    }

    @Test
    fun `清单里的笔数只算未删行`() {
        val live = EntityFixtures.bill("live")
        val deleted = EntityFixtures.bill("deleted").copy(deletedAt = 1L)
        val snapshot = BackupSnapshot(
            bills = listOf(live, deleted),
            ledgers = listOf(EntityFixtures.ledger(), EntityFixtures.ledger("l2").copy(deletedAt = 1L)),
        )
        assertEquals(1, snapshot.billCount)
        assertEquals(1, snapshot.ledgerCount)
        // 但包里两行都在，恢复后回收站要跟着回来。
        assertEquals(2, BackupJson.decode(BackupJson.encode(snapshot)).bills.size)
    }

    @Test
    fun `AI Key 引用不进备份包`() {
        val json = BackupJson.encode(BackupSnapshot(aiConfigs = listOf(EntityFixtures.aiConfig())))
        val row = JSONObject(json).getJSONArray("aiConfigs").getJSONObject(0)
        assertTrue(!row.has("key_reference"))
        assertEquals(null, BackupJson.decode(json).aiConfigs.single().keyReference)
    }

    @Test
    fun `账单的报销标记与语音消息时长往返保留`() {
        val snapshot = BackupSnapshot(
            bills = listOf(EntityFixtures.bill().copy(reimbursedAt = 999L)),
            aiMessages = listOf(EntityFixtures.aiMessage().copy(durationMs = 4_321L)),
        )
        val decoded = BackupJson.decode(BackupJson.encode(snapshot))
        assertEquals(999L, decoded.bills.single().reimbursedAt)
        assertEquals(4_321L, decoded.aiMessages.single().durationMs)
    }

    @Test
    fun `账本图标键不被旧封面映射洗掉`() {
        val ledger = EntityFixtures.ledger().copy(iconKey = "book")
        val decoded = BackupJson.decode(BackupJson.encode(BackupSnapshot(ledgers = listOf(ledger))))
        assertEquals("book", decoded.ledgers.single().iconKey)
        // 旧封面的兼容映射仍然生效。
        val legacy = JSONObject().put("id", "old").put("cover_key", "gold")
        val root = JSONObject().put("formatVersion", 1).put("ledgers", org.json.JSONArray().put(legacy))
        assertEquals("coin", BackupJson.decode(root.toString()).ledgers.single().iconKey)
    }

    @Test
    fun `旧备份缺字段用默认值而不是崩`() {
        val minimal = JSONObject().put("formatVersion", 1)
            .put("bills", org.json.JSONArray().put(JSONObject().put("id", "b1").put("amount_minor", 500)))
        val bill = BackupJson.decode(minimal.toString()).bills.single()
        assertEquals("MANUAL", bill.source)
        assertEquals("CNY", bill.currency)
        assertEquals(1, bill.version)
    }

    @Test
    fun `旧备份没有标签两节按空表恢复`() {
        val old = JSONObject().put("formatVersion", 1)
            .put("bills", org.json.JSONArray().put(JSONObject().put("id", "b1").put("amount_minor", 500)))
        val snapshot = BackupJson.decode(old.toString())
        assertTrue(snapshot.tags.isEmpty())
        assertTrue(snapshot.billTags.isEmpty())
    }

    @Test
    fun `高版本的备份包明确拒绝`() {
        val future = JSONObject().put("formatVersion", BackupJson.FORMAT_VERSION + 1).toString()
        assertFailsWith<IllegalArgumentException> { BackupJson.decode(future) }
    }

    @Test
    fun `顶层键齐全`() {
        val root = JSONObject(BackupJson.encode(BackupSnapshot()))
        BackupTableCases.all.forEach { case ->
            assertNotNull(root.optJSONArray(case.jsonKey), "缺顶层键 ${case.jsonKey}")
        }
    }
}
