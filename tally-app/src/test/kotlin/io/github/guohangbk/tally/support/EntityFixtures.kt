package io.github.guohangbk.tally.support

import io.github.guohangbk.tally.data.db.entity.AiConfigEntity
import io.github.guohangbk.tally.data.db.entity.AiConversationEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageAttachmentEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageEntity
import io.github.guohangbk.tally.data.db.entity.AiReportEntity
import io.github.guohangbk.tally.data.db.entity.AppSettingEntity
import io.github.guohangbk.tally.data.db.entity.AttachmentEntity
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.ImportBatchEntity
import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.data.db.entity.RecurringRuleEntity

/**
 * 测试对象的唯一构造入口（Object Mother）。
 *
 * 每个工厂都把**所有字段填成非默认值**——空值 / 默认值恰好是编解码最容易漏的一类，
 * 填满了，往返契约一旦少写一个 put 就会立刻红。新增字段时只改这里一处，
 * 所有表的契约测试自动跟着覆盖。
 */
object EntityFixtures {
    const val T0 = 1_700_000_000_000L
    const val DEVICE = "fixture-device"

    fun ledger(id: String = "ledger-1") = LedgerEntity(
        id = id, name = "日常账本", iconKey = "book", isDefault = true, isArchived = true,
        sortOrder = 1.5, createdAt = T0, updatedAt = T0 + 1, deletedAt = null,
        deviceId = DEVICE, version = 3,
    )

    fun category(id: String = "cat-1") = CategoryEntity(
        id = id, parentId = "cat-parent", name = "三餐", type = "EXPENSE", iconKey = "meal",
        color = "#123456", sortOrder = 2.5, isSystem = true, isArchived = true,
        createdAt = T0, updatedAt = T0 + 1, deletedAt = null, deviceId = DEVICE, version = 4,
    )

    fun bill(id: String = "bill-1") = BillEntity(
        id = id, ledgerId = "ledger-1", categoryL1Id = "cat-1", categoryL2Id = "cat-2",
        type = "EXPENSE", amountMinor = 12_345L, currency = "CNY", occurredAt = T0 + 100,
        timePrecision = "MINUTE", note = "午饭", source = "INSTALLMENT",
        externalId = "order-1", fingerprint = "fp-1", reimbursed = "DONE",
        reimbursedAt = T0 + 200, isRefund = true, refundOfBillId = "origin-1",
        isReimbursement = true, excludedFromStats = true, recorder = "VOICE",
        linkedExternalId = "linked-1", recurrenceRuleId = "rule-1",
        installmentPlanId = "plan-1", periodIndex = 7,
        createdAt = T0, updatedAt = T0 + 1, deletedAt = null, deviceId = DEVICE, version = 5,
    )

    fun attachment() = AttachmentEntity(
        id = "att-1", billId = "bill-1", relativePath = "a/b.webp", sha256 = "sha-1",
        mimeType = "image/png", sizeBytes = 4096L, width = 320, height = 240,
        createdAt = T0, updatedAt = T0 + 1, deletedAt = null, deviceId = DEVICE, version = 2,
    )

    fun importBatch() = ImportBatchEntity(
        id = "batch-1", sourceType = "ALIPAY", ledgerId = "ledger-1", fileName = "2026-11.csv",
        sourceSha256 = "sha-batch", totalRows = 100, importedRows = 90, skippedRows = 7,
        failedRows = 3, reportJson = "{\"ok\":true}", status = "SUCCESS",
        startedAt = T0, finishedAt = T0 + 500, createdAt = T0, updatedAt = T0 + 1,
        deletedAt = null, deviceId = DEVICE, version = 2,
    )

    fun recurringRule() = RecurringRuleEntity(
        id = "rule-1", ledgerId = "ledger-1", name = "房租", type = "EXPENSE",
        amountMinor = 380_000L, categoryL1Id = "cat-1", categoryL2Id = "cat-2",
        note = "每月 5 日", frequency = "MONTHLY", dayOfMonth = 5, dayOfWeek = 3,
        monthOfYear = 11, startAt = T0, endAt = T0 + 10_000, maxPeriods = 12,
        lastExecutedAt = T0 + 100, lastRemindedAt = T0 + 200, nextDueAt = T0 + 300,
        executedPeriods = 11, status = "ACTIVE", createdAt = T0, updatedAt = T0 + 1,
        deletedAt = null, deviceId = DEVICE, version = 6,
    )

    fun installmentPlan() = InstallmentPlanEntity(
        id = "plan-1", ledgerId = "ledger-1", name = "房贷",
        note = "等额本金 · 30 年", type = "EXPENSE",
        principalMinor = 1_000_000_00L, feeMinor = 12_000L, feeMode = "SEPARATE",
        totalMinor = 1_000_120_00L, periodCount = 360, perPeriodMinor = 277_777L,
        tailMinor = 278_000L, categoryL1Id = "cat-1", categoryL2Id = "cat-2",
        firstOccurredAt = T0, intervalMonths = 1, repaymentMethod = "EQUAL_PRINCIPAL",
        annualRateBp = 300, rateChangesJson = "[{\"from\":6,\"bp\":280}]",
        generatedPeriods = 5, lastRemindedAt = T0 + 400, status = "ACTIVE",
        createdAt = T0, updatedAt = T0 + 1, deletedAt = null, deviceId = DEVICE, version = 7,
    )

    fun aiConfig() = AiConfigEntity(
        id = "default", provider = "openai_compatible", baseUrl = "https://example.invalid/v1",
        model = "model-x", keyReference = "keystore-alias", active = true,
        status = "READY", visionCapable = true, lastError = "timeout",
        consecutiveFailures = 2, updatedAt = T0 + 1,
    )

    fun aiConversation() = AiConversationEntity(
        id = "conv-1", title = "本月花销", contextSummary = "摘要", createdAt = T0,
        updatedAt = T0 + 1, archivedAt = T0 + 2, deletedAt = null, deviceId = DEVICE, version = 2,
    )

    fun aiMessage() = AiMessageEntity(
        id = "msg-1", conversationId = "conv-1", role = "ASSISTANT", content = "内容",
        messageType = "IMAGE", status = "SUCCESS", sequence = 3, errorCode = "E-1",
        durationMs = 12_000L, createdAt = T0, updatedAt = T0 + 1, deletedAt = null,
        deviceId = DEVICE, version = 2,
    )

    fun aiMessageAttachment() = AiMessageAttachmentEntity(
        id = "msg-att-1", messageId = "msg-1", relativePath = "m/a.webp", sha256 = "sha-2",
        mimeType = "image/jpeg", sizeBytes = 2048L, createdAt = T0, updatedAt = T0 + 1,
        deletedAt = null, deviceId = DEVICE, version = 2,
    )

    fun aiReport() = AiReportEntity(
        id = "report-1", ledgerId = "ledger-1", monthKey = "2026-11", inputSha256 = "sha-3",
        summaryJson = "{}", content = "正文", createdAt = T0, updatedAt = T0 + 1,
        deletedAt = null, deviceId = DEVICE, version = 2,
    )

    fun keywordRule() = KeywordRuleEntity(
        id = "kw-1", keyword = "火锅", categoryId = "cat-1", source = "LEARNED",
        priority = 9, createdAt = T0, updatedAt = T0 + 1, deletedAt = null,
        deviceId = DEVICE, version = 2,
    )

    fun appSetting() = AppSettingEntity(
        key = "default_currency", value = "CNY", updatedAt = T0 + 1,
    )
}
