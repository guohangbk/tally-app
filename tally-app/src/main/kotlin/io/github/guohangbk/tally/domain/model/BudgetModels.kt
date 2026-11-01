package io.github.guohangbk.tally.domain.model

import io.github.guohangbk.tally.domain.PeriodRange

/** 预算周期。边界一律由 `Period` 按每月起始日现算，不存日期。 */
enum class BudgetPeriod(val storage: String, val label: String) {
    MONTHLY("MONTHLY", "每月"),
    YEARLY("YEARLY", "每年"),

    ;

    companion object {
        fun from(name: String?): BudgetPeriod =
            entries.firstOrNull { it.storage == name } ?: MONTHLY
    }
}

/** 一条预算。`categoryId` 为空就是总额预算，非空必须是一级分类。属于一个具体周期段（`periodStart`）。 */
data class BudgetItem(
    val id: String,
    val ledgerId: String,
    val categoryId: String?,
    val categoryName: String?,
    val categoryIconKey: String?,
    val period: BudgetPeriod,
    /** 这一条归属的周期段起始时刻（与 `periodRange(period).startAt` 对齐）。 */
    val periodStart: Long,
    /** 下一段是否自动复制一条同样的（只对 MONTHLY 有意义）。 */
    val autoRenew: Boolean,
    val amountMinor: Long,
    val alertRatio: Int,
    val isActive: Boolean,
) {
    val isTotal: Boolean get() = categoryId == null
}

/** 一条预算在当前周期的进度。三处可见（首页 / 统计页 / 组件）共用这一份算出来的数。 */
data class BudgetUsage(
    val item: BudgetItem,
    val range: PeriodRange,
    val usedMinor: Long,
    val remainingMinor: Long,
    /** 0..1+：超支时大于 1。 */
    val ratio: Float,
    val isOver: Boolean,
    /** 已用是否已经跨过阈值——通知只认这一条。 */
    val reachedThreshold: Boolean,
    val remainingDays: Int,
    val dailyAverageMinor: Long,
)

/** 当前账本在某一周期的预算全貌：总额一条打头，分类若干。 */
data class BudgetSummary(
    val period: BudgetPeriod,
    val range: PeriodRange,
    val elapsedDays: Int,
    val total: BudgetUsage?,
    val categories: List<BudgetUsage>,
) {
    val hasBudget: Boolean get() = total != null || categories.isNotEmpty()

    /** 统计页与组件要的那一行：有总额用总额，没有就用「分类预算的已用合计」——两者都为 0 时返回 null。 */
    val headline: BudgetUsage?
        get() = total ?: categories.firstOrNull()

    val totalCount: Int get() = if (total != null) 1 else 0
}

/** 保存/编辑提交的草稿。 */
data class BudgetDraft(
    val id: String? = null,
    val ledgerId: String,
    val categoryId: String? = null,
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val periodStart: Long = 0L,
    val autoRenew: Boolean = false,
    val amountMinor: Long = 0L,
    val alertRatio: Int = DEFAULT_ALERT_RATIO,
    val isActive: Boolean = true,
)

/** 提醒阈值默认 80%；100 即只在超支时提醒。 */
const val DEFAULT_ALERT_RATIO = 80

/**
 * 一条预算的进度：`used` 必须是**冲抵后的净支出**（与统计页同一份聚合），
 * 预算层自己不许再算一遍口径。
 */
fun budgetUsage(
    item: BudgetItem,
    usedMinor: Long,
    range: PeriodRange,
    elapsedDays: Int,
    totalDays: Int,
): BudgetUsage {
    val amount = item.amountMinor
    val used = usedMinor.coerceAtLeast(0L)
    val ratio = if (amount > 0L) used.toFloat() / amount else 0f
    return BudgetUsage(
        item = item,
        range = range,
        usedMinor = used,
        remainingMinor = (amount - used).coerceAtLeast(0L),
        ratio = ratio,
        isOver = used > amount,
        reachedThreshold = amount > 0L && ratio >= item.alertRatio.toFloat() / 100f,
        remainingDays = maxOf(0, totalDays - elapsedDays),
        dailyAverageMinor = if (elapsedDays > 0) used / elapsedDays else 0L,
    )
}
