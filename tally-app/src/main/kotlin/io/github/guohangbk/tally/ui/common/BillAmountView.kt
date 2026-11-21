package io.github.guohangbk.tally.ui.common

import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem

enum class BillAmountTone { EXPENSE, INCOME, NEUTRAL }

/**
 * 列表行和详情共用的金额口径：
 * 原单显示扣除退款后的净额，退款行只作为原单附注，不再抢收入的正号与绿色。
 */
data class BillAmountView(
    val sign: String,
    val amountMinor: Long,
    val tone: BillAmountTone,
    val subtitle: String? = null,
)

fun billAmountView(item: BillListItem): BillAmountView {
    val bill = item.bill
    val isRefund = bill.isRefund || bill.refundOfBillId != null
    if (isRefund) return BillAmountView(sign = "", amountMinor = bill.amountMinor, tone = BillAmountTone.NEUTRAL)

    if (bill.type == "INCOME") {
        return BillAmountView(sign = "+", amountMinor = bill.amountMinor, tone = BillAmountTone.INCOME)
    }

    val refunded = item.refundedMinor.coerceAtLeast(0L)
    if (refunded > 0L) {
        val remaining = (bill.amountMinor - refunded).coerceAtLeast(0L)
        return BillAmountView(
            sign = if (remaining > 0L) "−" else "",
            amountMinor = remaining,
            tone = if (remaining > 0L) BillAmountTone.EXPENSE else BillAmountTone.NEUTRAL,
            subtitle = if (remaining > 0L) "已退款 ¥${formatAmount(refunded)}" else "已全额退款",
        )
    }

    return BillAmountView(sign = "−", amountMinor = bill.amountMinor, tone = BillAmountTone.EXPENSE)
}
