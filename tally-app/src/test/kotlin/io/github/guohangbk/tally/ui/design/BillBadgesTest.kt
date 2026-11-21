package io.github.guohangbk.tally.ui.design

import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BillBadgesTest {

    private fun item(source: BillSource) = BillListItem(
        bill = BillEntity(
            id = "bill",
            ledgerId = "ledger",
            categoryL1Id = "food",
            categoryL2Id = null,
            type = "EXPENSE",
            amountMinor = 1000,
            occurredAt = 0L,
            source = source.name,
            createdAt = 0L,
            updatedAt = 0L,
            deviceId = "device",
        ),
        ledgerName = "日常账本",
        categoryL1Name = "餐饮",
        categoryL2Name = null,
    )

    @Test
    fun `通知抓取的账同样标速记`() {
        assertEquals(listOf("速记"), billBadges(item(BillSource.NOTIFICATION)).map { it.label })
    }

    @Test
    fun `速记来源的口径保持一致`() {
        BillSource.entries
            .filter { it == BillSource.VOICE || it == BillSource.SCREENSHOT || it == BillSource.NOTIFICATION }
            .forEach { source ->
                assertEquals(listOf("速记"), billBadges(item(source)).map { it.label }, "$source")
            }
    }

    @Test
    fun `小莉仍是独立来源`() {
        assertEquals(listOf("小莉"), billBadges(item(BillSource.ASSISTANT)).map { it.label })
    }
}
