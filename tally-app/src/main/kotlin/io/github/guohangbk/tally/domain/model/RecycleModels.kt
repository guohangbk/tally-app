package io.github.guohangbk.tally.domain.model

import java.time.LocalDate

/**
 * 回收站保留期。
 *
 * 与附件回收共用这一个设置：分开设会出现「账单还在回收站、图片已经过期被回收」，
 * 恢复出来是一笔没图的账——那比少一个选项糟得多。
 */
enum class RecycleKeepDays(val label: String, val days: Long?) {
    WEEK("7 天", 7L),
    MONTH("30 天", 30L),
    QUARTER("90 天", 90L),
    NEVER("永不清理", null),
    ;

    /** 落库的就是这个值；`never` 是显式选择，不是缺省。 */
    val key: String get() = days?.toString() ?: NEVER_KEY

    companion object {
        const val NEVER_KEY = "never"
        val DEFAULT = MONTH

        /** 读不到或值不认识时回到默认，不猜成「永不」——那会让磁盘永远长。 */
        fun fromKey(value: String?): RecycleKeepDays =
            entries.firstOrNull { it.key == value?.trim() } ?: DEFAULT
    }
}

/** 回收站里的一笔。删除时间、还能留几天都由 [item] 上的 `deleted_at` 推出来。 */
data class RecycleItem(
    val item: BillListItem,
    val deletedAt: Long,
    /** 还剩几天被自动清理；选了「永不清理」时为 null，行上就不显示倒计时。 */
    val daysLeft: Long?,
) {
    val billId: String get() = item.bill.id
}

/** 回收站一屏的读模型：分组、合计、保留期与上次清理结果。 */
data class RecycleSnapshot(
    val loaded: Boolean = false,
    val groups: List<RecycleGroup> = emptyList(),
    /** 全库软删笔数与合计，不受 [LIST_LIMIT] 截断影响。 */
    val totalBills: Int = 0,
    val totalMinor: Long = 0,
    val keepDays: RecycleKeepDays = RecycleKeepDays.DEFAULT,
    val lastCleanup: RecycleCleanupReport? = null,
    /** 列表被截断时要如实说明，否则用户以为回收站里就只有这些。 */
    val truncated: Boolean = false,
)

/** 按删除日聚合的一组，页面上就是一个「今天 10-14」的块。 */
data class RecycleGroup(
    val day: LocalDate,
    val items: List<RecycleItem>,
) {
    val totalMinor: Long get() = items.sumOf { it.item.bill.amountMinor }
}

/** 一次清理或彻底删除的产出；页面顶部那行与「系统」渠道的通知共用这一份。 */
data class RecycleCleanupReport(
    val at: Long,
    val bills: Int,
    val freedBytes: Long,
) {
    companion object {
        /** 存进 `app_setting` 的紧凑形式；读坏了就当没清理过，不编一个假数字给界面。 */
        fun parse(stored: String?): RecycleCleanupReport? {
            if (stored.isNullOrBlank()) return null
            val parts = stored.split('|')
            if (parts.size != 3) return null
            val at = parts[0].toLongOrNull() ?: return null
            val bills = parts[1].toIntOrNull() ?: return null
            val bytes = parts[2].toLongOrNull() ?: return null
            return RecycleCleanupReport(at, bills, bytes)
        }
    }

    fun store(): String = "$at|$bills|$freedBytes"
}

/** 彻底删除前的代价预览：删几笔、几张图、几张因为被别的账单共用而留下。 */
data class PurgePreview(
    val bills: Int,
    val totalMinor: Long,
    val images: Int,
    val sharedImages: Int,
) {
    /** 真正会消失的文件数。 */
    val reclaimableImages: Int get() = images - sharedImages
}

/**
 * 一次彻底删除的结果。[deferredBills] 是那些图片文件没能删掉、于是账单也先留着不动的笔次——
 * 宁可让用户看到「稍后自动重试」，也不要出现「行没了、文件还在磁盘上永远没人管」。
 */
data class PurgeResult(
    val purgedBills: Int,
    val freedBytes: Long,
    val deferredBills: Int = 0,
)
