package io.github.guohangbk.tally.data.db.query

import androidx.room.ColumnInfo
import androidx.room.Embedded
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.domain.model.BillListItem

/** 账单列表查询结果：实体 + 账本/分类名称与图标 + 附件数。 */
data class BillListItemRow(
    @Embedded val bill: BillEntity,
    @ColumnInfo("ledger_name") val ledgerName: String,
    @ColumnInfo("category_l1_name") val categoryL1Name: String?,
    @ColumnInfo("category_l2_name") val categoryL2Name: String?,
    @ColumnInfo("category_l1_icon") val categoryL1Icon: String?,
    @ColumnInfo("category_l2_icon") val categoryL2Icon: String?,
    /** 分期账单才有的期数，用于行上「分期 3/12」角标。 */
    @ColumnInfo("installment_period_count") val installmentPeriodCount: Int? = null,
    @ColumnInfo("attachment_count") val attachmentCount: Int,
    /** 作为原单被退掉的累计金额：行上要显示剩余可退与「已退」标记。 */
    @ColumnInfo("refunded_minor") val refundedMinor: Long = 0L,
) {
    /**
     * 查询行 → 领域条目。放在行上而不是仓库的私有方法里，是因为首页、搜索与回收站
     * 三处都要这一份映射；藏在某一个仓库里只会逼第二个仓库抄一遍。
     */
    fun toListItem(): BillListItem = BillListItem(
        bill = bill,
        ledgerName = ledgerName,
        categoryL1Name = categoryL1Name,
        categoryL2Name = categoryL2Name,
        categoryL1Icon = categoryL1Icon,
        categoryL2Icon = categoryL2Icon,
        installmentPeriodCount = installmentPeriodCount,
        attachmentCount = attachmentCount,
        refundedMinor = refundedMinor,
    )
}
