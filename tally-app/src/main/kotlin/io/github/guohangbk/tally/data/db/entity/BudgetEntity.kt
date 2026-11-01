package io.github.guohangbk.tally.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 预算。总额与分类预算同一张表：`category_id` 为空就是总额预算，非空必须是一级分类 id。
 *
 * **一条预算属于一个具体的周期段**：`period` 是节奏（MONTHLY / YEARLY），`period_start` 是那一段的起始时刻
 * （由 `domain/Period.kt` 按每月起始日现算，是这一条归属哪一月 / 哪一年的身份）。于是每月各自一条、
 * 只展示当期、能逐月回看，而 `auto_renew` 决定这条要不要在下一段自动复制一份。
 *
 * 不设 `(ledger_id, period, period_start, category_id)` 唯一索引：软删行仍占唯一位，恢复时会撞；
 * 重复由 `BudgetRepository` 写库前校验（同 `category` 表的做法）。
 */
@Entity(
    tableName = "budget",
    indices = [Index("ledger_id", "period"), Index("category_id")],
)
data class BudgetEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("ledger_id") val ledgerId: String,
    @ColumnInfo("category_id") val categoryId: String?,
    /** `MONTHLY` / `YEARLY`：节奏。 */
    @ColumnInfo("period") val period: String,
    /** 这一条属于哪个周期段的起始时刻（月=当月起始日那天，年=1 月 1 日）。 */
    @ColumnInfo("period_start") val periodStart: Long,
    /** 勾选后到下一个同节奏周期自动复制一条同样的（只对 MONTHLY 生效）。 */
    @ColumnInfo("auto_renew") val autoRenew: Boolean,
    /** 预算额（分），非负。 */
    @ColumnInfo("amount_minor") val amountMinor: Long,
    /** 提醒阈值，百分数，默认 80；100 即只在超支时提醒。 */
    @ColumnInfo("alert_ratio") val alertRatio: Int,
    /** 停用而不删除：停用后不参与提醒与进度。 */
    @ColumnInfo("is_active") val isActive: Boolean,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow
