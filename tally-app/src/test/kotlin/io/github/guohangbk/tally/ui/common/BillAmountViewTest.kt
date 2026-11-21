package io.github.guohangbk.tally.ui.common

import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.domain.model.BillListItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BillAmountViewTest {

    private fun item(
        type: String = "EXPENSE",
        amountMinor: Long = 800,
        refundedMinor: Long = 0,
        isRefund: Boolean = false,
        refundOfBillId: String? = null,
    ) = BillListItem(
        bill = BillEntity(
            id = "bill",
            ledgerId = "ledger",
            categoryL1Id = "food",
            categoryL2Id = null,
            type = type,
            amountMinor = amountMinor,
            occurredAt = 0L,
            isRefund = isRefund,
            refundOfBillId = refundOfBillId,
            createdAt = 0L,
            updatedAt = 0L,
            deviceId = "device",
        ),
        ledgerName = "日常账本",
        categoryL1Name = "零食",
        categoryL2Name = "水果",
        refundedMinor = refundedMinor,
    )

    @Test
    fun `普通支出是红色负号`() {
        val view = billAmountView(item())
        assertEquals("−", view.sign)
        assertEquals(800, view.amountMinor)
        assertEquals(BillAmountTone.EXPENSE, view.tone)
    }

    @Test
    fun `部分退款显示净支出`() {
        val view = billAmountView(item(refundedMinor = 400))
        assertEquals("−", view.sign)
        assertEquals(400, view.amountMinor)
        assertEquals(BillAmountTone.EXPENSE, view.tone)
        assertEquals("已退款 ¥4.00", view.subtitle)
    }

    @Test
    fun `全额退款归零且不再用支出色`() {
        val view = billAmountView(item(refundedMinor = 800))
        assertEquals(0, view.amountMinor)
        assertEquals("", view.sign)
        assertEquals(BillAmountTone.NEUTRAL, view.tone)
        assertEquals("已全额退款", view.subtitle)
    }

    @Test
    fun `退款行灰色无符号`() {
        val view = billAmountView(item(amountMinor = 400, isRefund = true, refundOfBillId = "origin"))
        assertEquals("", view.sign)
        assertEquals(400, view.amountMinor)
        assertEquals(BillAmountTone.NEUTRAL, view.tone)
    }
}
