package io.github.guohangbk.tally.domain.policy

import io.github.guohangbk.tally.domain.model.BillDraft
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.support.EntityFixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 坑 2 的回归网：编辑分期 / 周期 / 语音来的账单时，不能把来源洗成 MANUAL。
 * 编辑页提交的草稿不带 source（默认 MANUAL），策略必须忽略它。
 */
class BillEditPolicyTest {

    private fun context() = BillEditContext(
        ledgerId = "ledger-2",
        categoryL1Id = "cat-9",
        categoryL2Id = "cat-10",
        occurredAt = EntityFixtures.T0 + 9_999,
        timestamp = EntityFixtures.T0 + 20_000,
        isRefund = false,
    )

    private fun draft() = BillDraft(
        id = "bill-1",
        ledgerId = "ledger-2",
        categoryL1Id = "cat-9",
        categoryL2Id = "cat-10",
        type = BillType.EXPENSE,
        amountMinor = 8_888L,
        occurredAt = EntityFixtures.T0 + 9_999,
        note = "  改过的备注  ",
        source = BillSource.MANUAL,
    )

    @Test
    fun `编辑分期账单不改来源与溯源列`() {
        val existing = EntityFixtures.bill()
        val merged = BillEditPolicy.merge(existing, draft(), context())

        assertEquals("INSTALLMENT", merged.source)
        assertEquals("plan-1", merged.installmentPlanId)
        assertEquals(7, merged.periodIndex)
        assertEquals("rule-1", merged.recurrenceRuleId)
        assertEquals("VOICE", merged.recorder)
        assertEquals("linked-1", merged.linkedExternalId)
    }

    @Test
    fun `编辑只改草稿负责的字段`() {
        val existing = EntityFixtures.bill()
        val merged = BillEditPolicy.merge(existing, draft(), context())

        assertEquals(8_888L, merged.amountMinor)
        assertEquals("ledger-2", merged.ledgerId)
        assertEquals("cat-9", merged.categoryL1Id)
        assertEquals("cat-10", merged.categoryL2Id)
        assertEquals("改过的备注", merged.note)
        assertEquals(EntityFixtures.T0 + 9_999, merged.occurredAt)
        assertEquals(EntityFixtures.T0 + 20_000, merged.updatedAt)
        assertEquals(existing.version + 1, merged.version)
    }

    @Test
    fun `既成事实字段一律沿用原值`() {
        val existing = EntityFixtures.bill()
        val merged = BillEditPolicy.merge(existing, draft(), context())

        assertEquals(existing.id, merged.id)
        assertEquals(existing.currency, merged.currency)
        assertEquals(existing.createdAt, merged.createdAt)
        assertEquals(existing.deletedAt, merged.deletedAt)
        assertEquals(existing.deviceId, merged.deviceId)
        assertEquals(existing.reimbursedAt, merged.reimbursedAt)
        assertEquals(existing.isReimbursement, merged.isReimbursement)
        assertEquals(existing.externalId, merged.externalId, "草稿没带 externalId 时沿用")
        assertEquals(existing.fingerprint, merged.fingerprint, "草稿没带 fingerprint 时沿用")
    }

    @Test
    fun `草稿带了 externalId 与指纹时允许覆盖`() {
        val merged = BillEditPolicy.merge(
            EntityFixtures.bill(),
            draft().copy(externalId = "new-order", fingerprint = "new-fp"),
            context(),
        )
        assertEquals("new-order", merged.externalId)
        assertEquals("new-fp", merged.fingerprint)
    }

    @Test
    fun `空备注归一成 null`() {
        val merged = BillEditPolicy.merge(EntityFixtures.bill(), draft().copy(note = "   "), context())
        assertEquals(null, merged.note)
    }

    @Test
    fun `软删中的行被编辑也不复活`() {
        val deleted = EntityFixtures.bill().copy(deletedAt = 123L)
        val merged = BillEditPolicy.merge(deleted, draft(), context())
        assertEquals(123L, merged.deletedAt)
    }
}
