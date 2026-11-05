package io.github.guohangbk.tally.data.backup

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
import org.json.JSONArray
import org.json.JSONObject

/** 备份包里的结构化数据；表名与 Room 一致，方便逐表比对与增量扩展。 */
data class BackupSnapshot(
    val ledgers: List<LedgerEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val bills: List<BillEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val importBatches: List<ImportBatchEntity> = emptyList(),
    val recurringRules: List<RecurringRuleEntity> = emptyList(),
    val installmentPlans: List<InstallmentPlanEntity> = emptyList(),
    val aiConfigs: List<AiConfigEntity> = emptyList(),
    val aiConversations: List<AiConversationEntity> = emptyList(),
    val aiMessages: List<AiMessageEntity> = emptyList(),
    val aiMessageAttachments: List<AiMessageAttachmentEntity> = emptyList(),
    val aiReports: List<AiReportEntity> = emptyList(),
    val keywordRules: List<KeywordRuleEntity> = emptyList(),
    val appSettings: List<AppSettingEntity> = emptyList(),
) {
    /** 给人看的「N 笔账单」= 未删行数：备份文件里仍带着软删行（回收站要跟着回来），
     *  但清单和恢复提示必须是同一个口径，否则会出现「清单 5818 / 恢复成功 5739」这种对不上。 */
    val billCount: Int get() = bills.count { it.deletedAt == null }
    val ledgerCount: Int get() = ledgers.count { it.deletedAt == null }
}

/**
 * 备份 JSON 编解码。字段名固定为数据库列名，作为跨版本格式契约：
 * 只允许新增字段并给默认值，不允许改名；未知字段在读取时忽略。
 *
 * AI API Key 只存在 Keystore，`ai_config.key_reference` 不进入备份。
 */
object BackupJson {
    const val FORMAT_VERSION = 1

    fun encode(snapshot: BackupSnapshot): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("ledgers", JSONArray(snapshot.ledgers.map(::ledger)))
        root.put("categories", JSONArray(snapshot.categories.map(::category)))
        root.put("bills", JSONArray(snapshot.bills.map(::bill)))
        root.put("attachments", JSONArray(snapshot.attachments.map(::attachment)))
        root.put("importBatches", JSONArray(snapshot.importBatches.map(::importBatch)))
        root.put("recurringRules", JSONArray(snapshot.recurringRules.map(::recurringRule)))
        root.put("installmentPlans", JSONArray(snapshot.installmentPlans.map(::installmentPlan)))
        root.put("aiConfigs", JSONArray(snapshot.aiConfigs.map(::aiConfig)))
        root.put("aiConversations", JSONArray(snapshot.aiConversations.map(::aiConversation)))
        root.put("aiMessages", JSONArray(snapshot.aiMessages.map(::aiMessage)))
        root.put("aiMessageAttachments", JSONArray(snapshot.aiMessageAttachments.map(::aiMessageAttachment)))
        root.put("aiReports", JSONArray(snapshot.aiReports.map(::aiReport)))
        root.put("keywordRules", JSONArray(snapshot.keywordRules.map(::keywordRule)))
        root.put("appSettings", JSONArray(snapshot.appSettings.map(::appSetting)))
        return root.toString()
    }

    fun decode(text: String): BackupSnapshot {
        val root = JSONObject(text)
        val version = root.optInt("formatVersion", FORMAT_VERSION)
        require(version <= FORMAT_VERSION) { "备份数据版本高于当前 App" }
        return BackupSnapshot(
            ledgers = rows(root, "ledgers", ::ledgerFrom),
            categories = rows(root, "categories", ::categoryFrom),
            bills = rows(root, "bills", ::billFrom),
            attachments = rows(root, "attachments", ::attachmentFrom),
            importBatches = rows(root, "importBatches", ::importBatchFrom),
            recurringRules = rows(root, "recurringRules", ::recurringRuleFrom),
            installmentPlans = rows(root, "installmentPlans", ::installmentPlanFrom),
            aiConfigs = rows(root, "aiConfigs", ::aiConfigFrom),
            aiConversations = rows(root, "aiConversations", ::aiConversationFrom),
            aiMessages = rows(root, "aiMessages", ::aiMessageFrom),
            aiMessageAttachments = rows(root, "aiMessageAttachments", ::aiMessageAttachmentFrom),
            aiReports = rows(root, "aiReports", ::aiReportFrom),
            keywordRules = rows(root, "keywordRules", ::keywordRuleFrom),
            appSettings = rows(root, "appSettings", ::appSettingFrom),
        )
    }

    // ---- helpers -------------------------------------------------------

    private fun <T> rows(root: JSONObject, key: String, read: (JSONObject) -> T): List<T> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(read)
        }
    }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifEmpty { null }

    // ---- entity writers ------------------------------------------------

    private fun ledger(e: LedgerEntity) = JSONObject().apply {
        put("id", e.id); put("name", e.name); put("icon_key", e.iconKey)
        put("is_default", e.isDefault); put("is_archived", e.isArchived)
        put("sort_order", e.sortOrder); put("created_at", e.createdAt); put("updated_at", e.updatedAt)
        put("deleted_at", e.deletedAt); put("device_id", e.deviceId); put("version", e.version)
    }

    private fun category(e: CategoryEntity) = JSONObject().apply {
        put("id", e.id); put("parent_id", e.parentId)
        put("name", e.name); put("type", e.type); put("icon_key", e.iconKey); put("color", e.color)
        put("sort_order", e.sortOrder); put("is_system", e.isSystem); put("is_archived", e.isArchived)
        put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("deleted_at", e.deletedAt)
        put("device_id", e.deviceId); put("version", e.version)
    }

    private fun bill(e: BillEntity) = JSONObject().apply {
        put("id", e.id); put("ledger_id", e.ledgerId)
        put("category_l1_id", e.categoryL1Id); put("category_l2_id", e.categoryL2Id)
        put("type", e.type); put("amount_minor", e.amountMinor); put("currency", e.currency)
        put("occurred_at", e.occurredAt); put("time_precision", e.timePrecision)
        put("note", e.note); put("source", e.source); put("external_id", e.externalId)
        put("fingerprint", e.fingerprint); put("reimbursed", e.reimbursed)
        put("is_refund", e.isRefund); put("is_reimbursement", e.isReimbursement)
        put("refund_of_bill_id", e.refundOfBillId)
        put("excluded_from_stats", e.excludedFromStats); put("recorder", e.recorder)
        put("linked_external_id", e.linkedExternalId); put("recurrence_rule_id", e.recurrenceRuleId)
        put("installment_plan_id", e.installmentPlanId); put("period_index", e.periodIndex)
        put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("deleted_at", e.deletedAt)
        put("device_id", e.deviceId); put("version", e.version)
    }

    private fun attachment(e: AttachmentEntity) = JSONObject().apply {
        put("id", e.id); put("bill_id", e.billId); put("relative_path", e.relativePath)
        put("sha256", e.sha256); put("mime_type", e.mimeType); put("size_bytes", e.sizeBytes)
        put("width", e.width); put("height", e.height); put("created_at", e.createdAt)
        put("updated_at", e.updatedAt); put("deleted_at", e.deletedAt)
        put("device_id", e.deviceId); put("version", e.version)
    }

    private fun importBatch(e: ImportBatchEntity) = JSONObject().apply {
        put("id", e.id); put("source_type", e.sourceType); put("ledger_id", e.ledgerId)
        put("file_name", e.fileName); put("source_sha256", e.sourceSha256)
        put("total_rows", e.totalRows); put("imported_rows", e.importedRows)
        put("skipped_rows", e.skippedRows); put("failed_rows", e.failedRows)
        put("report_json", e.reportJson); put("status", e.status)
        put("started_at", e.startedAt); put("finished_at", e.finishedAt)
        put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("deleted_at", e.deletedAt)
        put("device_id", e.deviceId); put("version", e.version)
    }

    private fun recurringRule(e: RecurringRuleEntity) = JSONObject().apply {
        put("id", e.id); put("ledger_id", e.ledgerId); put("name", e.name); put("type", e.type)
        put("amount_minor", e.amountMinor); put("category_l1_id", e.categoryL1Id)
        put("category_l2_id", e.categoryL2Id); put("note", e.note); put("frequency", e.frequency)
        put("day_of_month", e.dayOfMonth); put("day_of_week", e.dayOfWeek)
        put("month_of_year", e.monthOfYear); put("start_at", e.startAt); put("end_at", e.endAt)
        put("max_periods", e.maxPeriods); put("last_executed_at", e.lastExecutedAt)
        put("last_reminded_at", e.lastRemindedAt)
        put("next_due_at", e.nextDueAt); put("executed_periods", e.executedPeriods)
        put("status", e.status); put("created_at", e.createdAt); put("updated_at", e.updatedAt)
        put("deleted_at", e.deletedAt); put("device_id", e.deviceId); put("version", e.version)
    }

    private fun installmentPlan(e: InstallmentPlanEntity) = JSONObject().apply {
        put("id", e.id); put("ledger_id", e.ledgerId); put("name", e.name); put("type", e.type)
        put("principal_minor", e.principalMinor); put("fee_minor", e.feeMinor); put("fee_mode", e.feeMode)
        put("total_minor", e.totalMinor); put("period_count", e.periodCount)
        put("per_period_minor", e.perPeriodMinor); put("tail_minor", e.tailMinor)
        put("category_l1_id", e.categoryL1Id); put("category_l2_id", e.categoryL2Id)
        // 计划级备注必须跟着走：漏了这一行，恢复一次备份就把备注丢光（周期规则的 note 一直是带的）
        put("note", e.note)
        put("first_occurred_at", e.firstOccurredAt); put("interval_months", e.intervalMonths)
        put("repayment_method", e.repaymentMethod); put("annual_rate_bp", e.annualRateBp)
        put("rate_changes_json", e.rateChangesJson)
        put("generated_periods", e.generatedPeriods); put("status", e.status)
        put("last_reminded_at", e.lastRemindedAt)
        put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("deleted_at", e.deletedAt)
        put("device_id", e.deviceId); put("version", e.version)
    }

    /** API Key 引用属于设备凭据，不进入备份。 */
    private fun aiConfig(e: AiConfigEntity) = JSONObject().apply {
        put("id", e.id); put("provider", e.provider); put("base_url", e.baseUrl)
        put("model", e.model); put("status", e.status); put("active", e.active)
        put("vision_capable", e.visionCapable)
        put("last_error", e.lastError); put("consecutive_failures", e.consecutiveFailures)
        put("updated_at", e.updatedAt)
    }

    private fun aiConversation(e: AiConversationEntity) = JSONObject().apply {
        put("id", e.id); put("title", e.title); put("context_summary", e.contextSummary)
        put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("archived_at", e.archivedAt)
        put("deleted_at", e.deletedAt); put("device_id", e.deviceId); put("version", e.version)
    }

    private fun aiMessage(e: AiMessageEntity) = JSONObject().apply {
        put("id", e.id); put("conversation_id", e.conversationId); put("role", e.role)
        put("content", e.content); put("message_type", e.messageType); put("status", e.status)
        put("sequence", e.sequence); put("error_code", e.errorCode)
        put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("deleted_at", e.deletedAt)
        put("device_id", e.deviceId); put("version", e.version)
    }

    private fun aiMessageAttachment(e: AiMessageAttachmentEntity) = JSONObject().apply {
        put("id", e.id); put("message_id", e.messageId); put("relative_path", e.relativePath)
        put("sha256", e.sha256); put("mime_type", e.mimeType); put("size_bytes", e.sizeBytes)
        put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("deleted_at", e.deletedAt)
        put("device_id", e.deviceId); put("version", e.version)
    }

    private fun aiReport(e: AiReportEntity) = JSONObject().apply {
        put("id", e.id); put("ledger_id", e.ledgerId); put("month_key", e.monthKey)
        put("input_sha256", e.inputSha256); put("summary_json", e.summaryJson)
        put("content", e.content); put("created_at", e.createdAt); put("updated_at", e.updatedAt)
        put("deleted_at", e.deletedAt); put("device_id", e.deviceId); put("version", e.version)
    }

    private fun keywordRule(e: KeywordRuleEntity) = JSONObject().apply {
        put("id", e.id); put("keyword", e.keyword); put("category_id", e.categoryId)
        put("source", e.source); put("priority", e.priority)
        put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("deleted_at", e.deletedAt)
        put("device_id", e.deviceId); put("version", e.version)
    }

    private fun appSetting(e: AppSettingEntity) = JSONObject().apply {
        put("setting_key", e.key); put("setting_value", e.value); put("updated_at", e.updatedAt)
    }

    // ---- entity readers ------------------------------------------------

    private fun ledgerFrom(o: JSONObject) = LedgerEntity(
        id = o.getString("id"), name = o.optString("name"),
        iconKey = legacyCoverToIcon(o.optString("icon_key", o.optString("cover_key", "ledger"))),
        isDefault = o.optBoolean("is_default"), isArchived = o.optBoolean("is_archived"),
        sortOrder = o.optDouble("sort_order", 0.0), createdAt = o.optLong("created_at"),
        updatedAt = o.optLong("updated_at"), deletedAt = o.nullableLong("deleted_at"),
        deviceId = o.optString("device_id"), version = o.optInt("version", 1),
    )

    /** 旧备份文件里的封面值不在图标库键域内，按 v9→v10 迁移同一张映射表落回图标。 */
    private fun legacyCoverToIcon(key: String): String = when (key) {
        "gold" -> "coin"
        "umber" -> "sofa"
        "clay" -> "cup"
        "ink" -> "book"
        "slate" -> "piggy"
        else -> "ledger"
    }

    private fun categoryFrom(o: JSONObject) = CategoryEntity(
        id = o.getString("id"), parentId = o.nullableString("parent_id"),
        name = o.optString("name"), type = o.optString("type"), iconKey = o.optString("icon_key", "tag"),
        color = o.nullableString("color"), sortOrder = o.optDouble("sort_order", 0.0),
        isSystem = o.optBoolean("is_system"), isArchived = o.optBoolean("is_archived"),
        createdAt = o.optLong("created_at"), updatedAt = o.optLong("updated_at"),
        deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
        version = o.optInt("version", 1),
    )

    private fun billFrom(o: JSONObject) = BillEntity(
        id = o.getString("id"), ledgerId = o.optString("ledger_id"),
        categoryL1Id = o.nullableString("category_l1_id"), categoryL2Id = o.nullableString("category_l2_id"),
        type = o.optString("type", "EXPENSE"), amountMinor = o.optLong("amount_minor"),
        currency = o.optString("currency", "CNY"), occurredAt = o.optLong("occurred_at"),
        timePrecision = o.optString("time_precision", "DATE"), note = o.nullableString("note"),
        source = o.optString("source", "MANUAL"), externalId = o.nullableString("external_id"),
        fingerprint = o.nullableString("fingerprint"), reimbursed = o.optString("reimbursed", "NONE"),
        isRefund = o.optBoolean("is_refund"), isReimbursement = o.optBoolean("is_reimbursement"),
        refundOfBillId = o.nullableString("refund_of_bill_id"),
        excludedFromStats = o.optBoolean("excluded_from_stats"), recorder = o.nullableString("recorder"),
        linkedExternalId = o.nullableString("linked_external_id"),
        recurrenceRuleId = o.nullableString("recurrence_rule_id"),
        installmentPlanId = o.nullableString("installment_plan_id"),
        periodIndex = o.nullableInt("period_index"), createdAt = o.optLong("created_at"),
        updatedAt = o.optLong("updated_at"), deletedAt = o.nullableLong("deleted_at"),
        deviceId = o.optString("device_id"), version = o.optInt("version", 1),
    )

    private fun attachmentFrom(o: JSONObject) = AttachmentEntity(
        id = o.getString("id"), billId = o.optString("bill_id"), relativePath = o.optString("relative_path"),
        sha256 = o.optString("sha256"), mimeType = o.optString("mime_type", "image/webp"),
        sizeBytes = o.optLong("size_bytes"), width = o.optInt("width"), height = o.optInt("height"),
        createdAt = o.optLong("created_at"), updatedAt = o.optLong("updated_at"),
        deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
        version = o.optInt("version", 1),
    )

    private fun importBatchFrom(o: JSONObject) = ImportBatchEntity(
        id = o.getString("id"), sourceType = o.optString("source_type"), ledgerId = o.optString("ledger_id"),
        fileName = o.optString("file_name"), sourceSha256 = o.optString("source_sha256"),
        totalRows = o.optInt("total_rows"), importedRows = o.optInt("imported_rows"),
        skippedRows = o.optInt("skipped_rows"), failedRows = o.optInt("failed_rows"),
        reportJson = o.nullableString("report_json"), status = o.optString("status", "SUCCESS"),
        startedAt = o.optLong("started_at"), finishedAt = o.nullableLong("finished_at"),
        createdAt = o.optLong("created_at"), updatedAt = o.optLong("updated_at"),
        deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
        version = o.optInt("version", 1),
    )

    private fun recurringRuleFrom(o: JSONObject) = RecurringRuleEntity(
        id = o.getString("id"), ledgerId = o.optString("ledger_id"), name = o.optString("name"),
        type = o.optString("type", "EXPENSE"), amountMinor = o.optLong("amount_minor"),
        categoryL1Id = o.nullableString("category_l1_id"), categoryL2Id = o.nullableString("category_l2_id"),
        note = o.nullableString("note"), frequency = o.optString("frequency", "MONTHLY"),
        dayOfMonth = o.nullableInt("day_of_month"), dayOfWeek = o.nullableInt("day_of_week"),
        monthOfYear = o.nullableInt("month_of_year"), startAt = o.optLong("start_at"),
        endAt = o.nullableLong("end_at"), maxPeriods = o.nullableInt("max_periods"),
        lastExecutedAt = o.nullableLong("last_executed_at"),
        lastRemindedAt = o.nullableLong("last_reminded_at"), nextDueAt = o.optLong("next_due_at"),
        executedPeriods = o.optInt("executed_periods"), status = o.optString("status", "ACTIVE"),
        createdAt = o.optLong("created_at"), updatedAt = o.optLong("updated_at"),
        deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
        version = o.optInt("version", 1),
    )

    private fun installmentPlanFrom(o: JSONObject): InstallmentPlanEntity {
        val total = o.optLong("total_minor")
        return InstallmentPlanEntity(
            id = o.getString("id"), ledgerId = o.optString("ledger_id"), name = o.optString("name"),
            note = o.nullableString("note"),
            type = o.optString("type", "EXPENSE"),
            principalMinor = o.optLong("principal_minor", total),
            feeMinor = o.optLong("fee_minor"), feeMode = o.optString("fee_mode", "INCLUDED"),
            totalMinor = total, periodCount = o.optInt("period_count", 1),
            perPeriodMinor = o.optLong("per_period_minor"), tailMinor = o.optLong("tail_minor"),
            categoryL1Id = o.nullableString("category_l1_id"), categoryL2Id = o.nullableString("category_l2_id"),
            firstOccurredAt = o.optLong("first_occurred_at"), intervalMonths = o.optInt("interval_months", 1),
            repaymentMethod = o.optString("repayment_method", "PLAIN"),
            annualRateBp = o.optInt("annual_rate_bp", 0),
            rateChangesJson = o.nullableString("rate_changes_json"),
            generatedPeriods = o.optInt("generated_periods"), status = o.optString("status", "ACTIVE"),
            lastRemindedAt = o.nullableLong("last_reminded_at"),
            createdAt = o.optLong("created_at"), updatedAt = o.optLong("updated_at"),
            deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
            version = o.optInt("version", 1),
        )
    }

    private fun aiConfigFrom(o: JSONObject) = AiConfigEntity(
        id = o.optString("id", "default"), provider = o.optString("provider", "openai_compatible"),
        baseUrl = o.nullableString("base_url"), model = o.nullableString("model"),
        keyReference = null, status = o.optString("status", "UNCONFIGURED"),
        active = o.optBoolean("active"),
        visionCapable = o.optBoolean("vision_capable"), lastError = o.nullableString("last_error"),
        consecutiveFailures = o.optInt("consecutive_failures"), updatedAt = o.optLong("updated_at"),
    )

    private fun aiConversationFrom(o: JSONObject) = AiConversationEntity(
        id = o.getString("id"), title = o.optString("title"),
        contextSummary = o.nullableString("context_summary"), createdAt = o.optLong("created_at"),
        updatedAt = o.optLong("updated_at"), archivedAt = o.nullableLong("archived_at"),
        deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
        version = o.optInt("version", 1),
    )

    private fun aiMessageFrom(o: JSONObject) = AiMessageEntity(
        id = o.getString("id"), conversationId = o.optString("conversation_id"),
        role = o.optString("role", "USER"), content = o.optString("content"),
        messageType = o.optString("message_type", "TEXT"), status = o.optString("status", "SUCCESS"),
        sequence = o.optInt("sequence"), errorCode = o.nullableString("error_code"),
        createdAt = o.optLong("created_at"), updatedAt = o.optLong("updated_at"),
        deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
        version = o.optInt("version", 1),
    )

    private fun aiMessageAttachmentFrom(o: JSONObject) = AiMessageAttachmentEntity(
        id = o.getString("id"), messageId = o.optString("message_id"),
        relativePath = o.optString("relative_path"), sha256 = o.optString("sha256"),
        mimeType = o.optString("mime_type", "image/webp"), sizeBytes = o.optLong("size_bytes"),
        createdAt = o.optLong("created_at"), updatedAt = o.optLong("updated_at"),
        deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
        version = o.optInt("version", 1),
    )

    private fun aiReportFrom(o: JSONObject) = AiReportEntity(
        id = o.getString("id"), ledgerId = o.optString("ledger_id"), monthKey = o.optString("month_key"),
        inputSha256 = o.optString("input_sha256"), summaryJson = o.optString("summary_json"),
        content = o.optString("content"), createdAt = o.optLong("created_at"),
        updatedAt = o.optLong("updated_at"), deletedAt = o.nullableLong("deleted_at"),
        deviceId = o.optString("device_id"), version = o.optInt("version", 1),
    )

    private fun keywordRuleFrom(o: JSONObject) = KeywordRuleEntity(
        id = o.getString("id"), keyword = o.optString("keyword"), categoryId = o.optString("category_id"),
        source = o.optString("source", "MANUAL"), priority = o.optInt("priority"),
        createdAt = o.optLong("created_at"), updatedAt = o.optLong("updated_at"),
        deletedAt = o.nullableLong("deleted_at"), deviceId = o.optString("device_id"),
        version = o.optInt("version", 1),
    )

    private fun appSettingFrom(o: JSONObject) = AppSettingEntity(
        key = o.optString("setting_key"), value = o.optString("setting_value"),
        updatedAt = o.optLong("updated_at"),
    )
}
