package io.github.guohangbk.tally.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账本。默认账本全局唯一，由 `LedgerRepository.setDefault` 在同一事务内保证。
 */
@Entity(
    tableName = "ledger",
    indices = [Index("is_default"), Index("deleted_at")],
)
data class LedgerEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("name") val name: String,
    /** 图标库的键（IconLibrary.isKnown 校验域内），不保存图片路径。 */
    @ColumnInfo("icon_key") val iconKey: String = "ledger",
    @ColumnInfo("is_default") val isDefault: Boolean = false,
    @ColumnInfo("is_archived") val isArchived: Boolean = false,
    @ColumnInfo("sort_order") val sortOrder: Double = 0.0,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/**
 * 两级分类树，全机一份，与账本无关。`parent_id` 为 null 表示一级。
 */
@Entity(
    tableName = "category",
    indices = [
        Index("parent_id"),
        Index("type", "sort_order"),
    ],
)
data class CategoryEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("parent_id") val parentId: String?,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("type") val type: String,
    @ColumnInfo("icon_key") val iconKey: String,
    @ColumnInfo("color") val color: String? = null,
    /** 可插拔排序值：重排只改相邻中点，不重写全表。 */
    @ColumnInfo("sort_order") val sortOrder: Double,
    @ColumnInfo("is_system") val isSystem: Boolean = false,
    @ColumnInfo("is_archived") val isArchived: Boolean = false,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/**
 * 账单。金额以分为单位；报销记录通过 `is_reimbursement` + `excluded_from_stats` 排除统计。
 */
@Entity(
    tableName = "bill",
    indices = [
        Index("external_id", unique = true),
        Index("fingerprint"),
        Index("ledger_id", "occurred_at"),
        Index("category_l1_id", "category_l2_id"),
        Index("deleted_at"),
        // 列表行上的「已退」金额是 refund_of_bill_id 的关联子查询，无索引时每行全表扫描；
        // 搜索页一次取几百行，扫描次数放大成倍，补这一列的索引把它变索引查找。
        Index("refund_of_bill_id"),
        Index("recurrence_rule_id", "period_index", unique = true),
        Index("installment_plan_id", "period_index", unique = true),
    ],
)
data class BillEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("ledger_id") val ledgerId: String,
    @ColumnInfo("category_l1_id") val categoryL1Id: String?,
    @ColumnInfo("category_l2_id") val categoryL2Id: String?,
    @ColumnInfo("type") val type: String,
    @ColumnInfo("amount_minor") val amountMinor: Long,
    @ColumnInfo("currency") val currency: String = "CNY",
    @ColumnInfo("occurred_at") val occurredAt: Long,
    @ColumnInfo("time_precision") val timePrecision: String = "DATE",
    @ColumnInfo("note") val note: String? = null,
    @ColumnInfo("source") val source: String = "MANUAL",
    @ColumnInfo("external_id") val externalId: String? = null,
    @ColumnInfo("fingerprint") val fingerprint: String? = null,
    @ColumnInfo("reimbursed") val reimbursed: String = "NONE",
    /** 标记为已报销的时间；撤销报销后仍保留，供按报销日期回查。 */
    @ColumnInfo("reimbursed_at") val reimbursedAt: Long? = null,
    @ColumnInfo("is_refund") val isRefund: Boolean = false,
    /** 指向被退款的原单：非空即这笔是退款，统计上从原单分类的支出里冲抵，不计收入。 */
    @ColumnInfo("refund_of_bill_id") val refundOfBillId: String? = null,
    @ColumnInfo("is_reimbursement") val isReimbursement: Boolean = false,
    @ColumnInfo("excluded_from_stats") val excludedFromStats: Boolean = false,
    @ColumnInfo("recorder") val recorder: String? = null,
    @ColumnInfo("linked_external_id") val linkedExternalId: String? = null,
    @ColumnInfo("recurrence_rule_id") val recurrenceRuleId: String? = null,
    @ColumnInfo("installment_plan_id") val installmentPlanId: String? = null,
    @ColumnInfo("period_index") val periodIndex: Int? = null,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/** 账单图片元数据；图片二进制存 `files/attachments/`，不存 BLOB。 */
@Entity(
    tableName = "attachment",
    indices = [Index("bill_id"), Index("sha256"), Index("deleted_at")],
)
data class AttachmentEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("bill_id") val billId: String,
    @ColumnInfo("relative_path") val relativePath: String,
    @ColumnInfo("sha256") val sha256: String,
    @ColumnInfo("mime_type") val mimeType: String = "image/webp",
    @ColumnInfo("size_bytes") val sizeBytes: Long,
    @ColumnInfo("width") val width: Int,
    @ColumnInfo("height") val height: Int,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/** 导入批次与对账结果。 */
@Entity(
    tableName = "import_batch",
    indices = [Index("ledger_id"), Index("status"), Index("source_sha256")],
)
data class ImportBatchEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("source_type") val sourceType: String,
    @ColumnInfo("ledger_id") val ledgerId: String,
    @ColumnInfo("file_name") val fileName: String,
    @ColumnInfo("source_sha256") val sourceSha256: String,
    @ColumnInfo("total_rows") val totalRows: Int = 0,
    @ColumnInfo("imported_rows") val importedRows: Int = 0,
    @ColumnInfo("skipped_rows") val skippedRows: Int = 0,
    @ColumnInfo("failed_rows") val failedRows: Int = 0,
    /** 行级错误与逐月对账结果，JSON 文本。 */
    @ColumnInfo("report_json") val reportJson: String? = null,
    @ColumnInfo("status") val status: String = "RUNNING",
    @ColumnInfo("started_at") val startedAt: Long,
    @ColumnInfo("finished_at") val finishedAt: Long? = null,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

@Entity(
    tableName = "recurring_rule",
    indices = [Index("ledger_id"), Index("status")],
)
data class RecurringRuleEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("ledger_id") val ledgerId: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("type") val type: String,
    @ColumnInfo("amount_minor") val amountMinor: Long,
    @ColumnInfo("category_l1_id") val categoryL1Id: String?,
    @ColumnInfo("category_l2_id") val categoryL2Id: String?,
    @ColumnInfo("note") val note: String? = null,
    @ColumnInfo("frequency") val frequency: String,
    @ColumnInfo("day_of_month") val dayOfMonth: Int? = null,
    @ColumnInfo("day_of_week") val dayOfWeek: Int? = null,
    @ColumnInfo("month_of_year") val monthOfYear: Int? = null,
    @ColumnInfo("start_at") val startAt: Long,
    @ColumnInfo("end_at") val endAt: Long? = null,
    /** 执行次数上限；null 表示不按次数结束。 */
    @ColumnInfo("max_periods") val maxPeriods: Int? = null,
    @ColumnInfo("last_executed_at") val lastExecutedAt: Long? = null,
    /**
     * 最近一次为这条规则发通知的时刻。去重口径是**同一规则每天最多一条**，
     * 所以只存时刻、不存"发过哪几类"——同一天里一条规则要么执行成功、要么失败要人处理，
     * 不会两种同时发生；分期"应还前 1 天"和"当天"本来就落在不同天，各发一次不受影响。
     */
    @ColumnInfo("last_reminded_at") val lastRemindedAt: Long? = null,
    @ColumnInfo("next_due_at") val nextDueAt: Long,
    @ColumnInfo("executed_periods") val executedPeriods: Int = 0,
    @ColumnInfo("status") val status: String = "ACTIVE",
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

@Entity(
    tableName = "installment_plan",
    indices = [Index("ledger_id"), Index("status")],
)
data class InstallmentPlanEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("ledger_id") val ledgerId: String,
    @ColumnInfo("name") val name: String,
    /** 计划级备注。创建页一直有这个输入，之前根本没落库，改分期时一起补上。 */
    @ColumnInfo("note") val note: String? = null,
    @ColumnInfo("type") val type: String = "EXPENSE",
    /** 本金；总金额 = 本金 + 手续费。 */
    @ColumnInfo("principal_minor", defaultValue = "0") val principalMinor: Long = 0L,
    @ColumnInfo("fee_minor", defaultValue = "0") val feeMinor: Long = 0L,
    /** INCLUDED：手续费并入每期；SEPARATE：手续费单独成最后一期。 */
    @ColumnInfo("fee_mode", defaultValue = "'INCLUDED'") val feeMode: String = "INCLUDED",
    @ColumnInfo("total_minor") val totalMinor: Long,
    @ColumnInfo("period_count") val periodCount: Int,
    @ColumnInfo("per_period_minor") val perPeriodMinor: Long,
    /** 最后一期用尾差修正，保证各期合计等于总额。 */
    @ColumnInfo("tail_minor") val tailMinor: Long,
    @ColumnInfo("category_l1_id") val categoryL1Id: String?,
    @ColumnInfo("category_l2_id") val categoryL2Id: String?,
    @ColumnInfo("first_occurred_at") val firstOccurredAt: Long,
    @ColumnInfo("interval_months") val intervalMonths: Int = 1,
    /** PLAIN：普通分期；EQUAL_PAYMENT / EQUAL_PRINCIPAL：房贷。 */
    @ColumnInfo("repayment_method", defaultValue = "'PLAIN'") val repaymentMethod: String = "PLAIN",
    /** 创建时的年利率（BP）；改利率只追加 [rateChangesJson]，不动这一列。 */
    @ColumnInfo("annual_rate_bp", defaultValue = "0") val annualRateBp: Int = 0,
    /** 利率变更段 `[{"from":期号,"bp":年利率}]`，从该期起生效；已入账的期不受影响。 */
    @ColumnInfo("rate_changes_json") val rateChangesJson: String? = null,
    @ColumnInfo("generated_periods") val generatedPeriods: Int = 0,
    /** 同 [RecurringRuleEntity.lastRemindedAt]：同一计划每天最多一条通知。 */
    @ColumnInfo("last_reminded_at") val lastRemindedAt: Long? = null,
    @ColumnInfo("status") val status: String = "ACTIVE",
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/** 小莉配置。API Key 只存 Keystore 引用，数据库不放明文。 */
@Entity(tableName = "ai_config")
data class AiConfigEntity(
    @PrimaryKey val id: String = "default",
    @ColumnInfo("provider") val provider: String = "openai_compatible",
    @ColumnInfo("base_url") val baseUrl: String? = null,
    @ColumnInfo("model") val model: String? = null,
    @ColumnInfo("key_reference") val keyReference: String? = null,
    /** 多条配置里哪一条正在生效：一次只有一条为真，切换时把旧的那条置回 false。 */
    @ColumnInfo("active") val active: Boolean = false,
    @ColumnInfo("status") val status: String = "UNCONFIGURED",
    @ColumnInfo("vision_capable") val visionCapable: Boolean = false,
    @ColumnInfo("last_error") val lastError: String? = null,
    @ColumnInfo("consecutive_failures") val consecutiveFailures: Int = 0,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "ai_conversation",
    indices = [Index("updated_at"), Index("archived_at")],
)
data class AiConversationEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("title") val title: String,
    @ColumnInfo("context_summary") val contextSummary: String? = null,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("archived_at") val archivedAt: Long? = null,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

@Entity(
    tableName = "ai_message",
    indices = [Index("conversation_id", "sequence"), Index("status")],
)
data class AiMessageEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("role") val role: String,
    @ColumnInfo("content") val content: String,
    @ColumnInfo("message_type") val messageType: String = "TEXT",
    @ColumnInfo("status") val status: String = "SUCCESS",
    @ColumnInfo("sequence") val sequence: Int,
    @ColumnInfo("error_code") val errorCode: String? = null,
    /** 语音消息的录音时长；文字与图片消息为 null。 */
    @ColumnInfo("duration_ms") val durationMs: Long? = null,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/** 只有用户明确要求长期保留的对话图片才建这条记录。 */
@Entity(
    tableName = "ai_message_attachment",
    indices = [Index("message_id"), Index("sha256")],
)
data class AiMessageAttachmentEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("message_id") val messageId: String,
    @ColumnInfo("relative_path") val relativePath: String,
    @ColumnInfo("sha256") val sha256: String,
    @ColumnInfo("mime_type") val mimeType: String = "image/webp",
    @ColumnInfo("size_bytes") val sizeBytes: Long,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

@Entity(
    tableName = "ai_report",
    indices = [Index("ledger_id", "month_key"), Index("input_sha256", unique = true)],
)
data class AiReportEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("ledger_id") val ledgerId: String,
    @ColumnInfo("month_key") val monthKey: String,
    @ColumnInfo("input_sha256") val inputSha256: String,
    @ColumnInfo("summary_json") val summaryJson: String,
    @ColumnInfo("content") val content: String,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/** 端侧文本分类关键词，全机一套（与分类一样不按账本隔离）。 */
@Entity(
    tableName = "keyword_rule",
    indices = [Index("keyword"), Index("category_id")],
)
data class KeywordRuleEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("keyword") val keyword: String,
    @ColumnInfo("category_id") val categoryId: String,
    /** `MANUAL` 用户在规则页加 / `LEARNED` 记账页沉淀 / `BUILTIN` 预留内置词组（当前不落表）。 */
    @ColumnInfo("source") val source: String = "MANUAL",
    @ColumnInfo("priority") val priority: Int = 0,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/** 需要随备份迁移的业务开关；设备本地显示偏好走 DataStore。 */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey @ColumnInfo("setting_key") val key: String,
    @ColumnInfo("setting_value") val value: String,
    @ColumnInfo("updated_at") val updatedAt: Long,
)
