package io.github.guohangbk.tally.domain.policy

import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.domain.model.BillDraft

/**
 * 编辑账单时「哪些字段归草稿、哪些归既成事实」的唯一裁决处（纯函数，无 Android / Room 依赖）。
 *
 * 之所以从 [io.github.guohangbk.tally.data.repository.BillRepository] 里抽出来：
 * 这条规则以前埋在事务与一堆 DAO 校验中间，只能靠真机点一遍才能验；
 * 抽成策略后既能在纯 JVM 单测里锁死，也让「编辑不该改来源」这句话在代码里可见。
 *
 * 设计约定：
 * - 草稿能改的：金额、分类、账本、时间、备注、报销态、排除统计、退款关联；
 * - **一律沿用原值**的：来源、设备号、创建时间、软删标记、周期/分期溯源、幂等键、币种，
 *   以及 `externalId` / `fingerprint` 在草稿没带时沿用原值（带了才允许覆盖）。
 * - `version` 自增、`updatedAt` 用同一时间戳，供同步与审计。
 */
data class BillEditContext(
    val ledgerId: String,
    val categoryL1Id: String?,
    val categoryL2Id: String?,
    val occurredAt: Long,
    val timestamp: Long,
    val isRefund: Boolean,
)

object BillEditPolicy {

    fun merge(existing: BillEntity, draft: BillDraft, context: BillEditContext): BillEntity =
        existing.copy(
            ledgerId = context.ledgerId,
            categoryL1Id = context.categoryL1Id,
            categoryL2Id = context.categoryL2Id,
            type = draft.type.name,
            amountMinor = draft.amountMinor,
            occurredAt = context.occurredAt,
            timePrecision = draft.timePrecision.name,
            note = draft.note?.trim()?.ifEmpty { null },
            reimbursed = draft.reimbursed.name,
            isRefund = context.isRefund,
            refundOfBillId = draft.refundOfBillId,
            excludedFromStats = draft.excludedFromStats,
            // 来源、溯源列、幂等键、币种、创建时间、软删标记、deviceId 一律不动（copy 未列出的字段即原值）。
            source = existing.source,
            externalId = draft.externalId ?: existing.externalId,
            fingerprint = draft.fingerprint ?: existing.fingerprint,
            updatedAt = context.timestamp,
            version = existing.version + 1,
        )
}
