package io.github.guohangbk.tally.domain.model

/** 导入/导出共用的稳定数据契约。 */
enum class ImportEncoding { UTF8, UTF8_BOM, GB18030 }

enum class ExportFormat { CSV, XLSX }

data class ParsedBillRow(
    val rowNumber: Int,
    val externalId: String? = null,
    val occurredAt: Long? = null,
    val ledgerName: String? = null,
    val categoryL1Name: String? = null,
    val categoryL2Name: String? = null,
    val type: BillType? = null,
    val amountMinor: Long? = null,
    val currency: String = "CNY",
    val note: String? = null,
    val reimbursed: ReimburseStatus = ReimburseStatus.NONE,
    val recorder: String? = null,
    val linkedExternalId: String? = null,
    val source: BillSource = BillSource.IMPORT,
    val isReimbursement: Boolean = false,
    val excludedFromStats: Boolean = false,
    /** 钱迹「退款」与负数金额都落成「支出 + 退款标记」，不冲减总支出。 */
    val isRefund: Boolean = false,
    val attachmentRefs: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** 判重键的两种来源：有外部 ID 就用它，没有才退到组合指纹（见《详细设计说明书》§7）。 */
enum class DedupeKeyKind { EXTERNAL_ID, FINGERPRINT }

/**
 * 一行会被跳过的账单。[withinFile] 区分「和文件里更早的一行重复」与「库里已经有一笔」——
 * 前者是导出文件自己带了重复，后者才是同一份账单导了第二次。
 */
data class DuplicateRow(
    val row: ParsedBillRow,
    val keyKind: DedupeKeyKind,
    val withinFile: Boolean,
)

/** 库里已存在的判重键，一次取齐，避免预览时逐行查库。 */
data class ImportExistingKeys(
    val externalIds: Set<String> = emptySet(),
    val fingerprints: Set<String> = emptySet(),
)

data class ImportPreview(
    val sourceType: ImportSourceType,
    val fileName: String,
    val encoding: ImportEncoding,
    val headerRow: Int,
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val reimbursementRows: Int,
    val newCategoryCount: Int,
    val dateRangeStart: Long?,
    val dateRangeEnd: Long?,
    val previewRows: List<ParsedBillRow>,
    val errorRows: List<ParsedBillRow>,
    val ignoredColumns: List<String>,
    val duplicateRows: Int = 0,
    val duplicateItems: List<DuplicateRow> = emptyList(),
) {
    /** 勾着「跳过重复」时真正会写进去的行数。 */
    val importableRows: Int get() = (validRows - duplicateRows).coerceAtLeast(0)
}

data class ImportOptions(
    val sourceType: ImportSourceType,
    val targetLedgerId: String?,
    val dedupe: Boolean = true,
    val skipInvalidRows: Boolean = false,
)

data class MonthlyReconciliation(
    val monthKey: String,
    val sourceCount: Int,
    val importedCount: Int,
    val sourceExpenseMinor: Long,
    val importedExpenseMinor: Long,
    val sourceIncomeMinor: Long,
    val importedIncomeMinor: Long,
    val sourceExcludedMinor: Long,
    val importedExcludedMinor: Long,
) {
    val isBalanced: Boolean
        get() = sourceCount == importedCount &&
            sourceExpenseMinor == importedExpenseMinor &&
            sourceIncomeMinor == importedIncomeMinor &&
            sourceExcludedMinor == importedExcludedMinor
}

data class ImportReport(
    val batchId: String,
    val status: ImportBatchStatus,
    val sourceType: ImportSourceType,
    val totalRows: Int,
    val importedRows: Int,
    val skippedRows: Int,
    val failedRows: Int,
    val months: List<MonthlyReconciliation>,
    val ignoredColumns: List<String> = emptyList(),
    val message: String? = null,
)

data class ImportOutcome(
    val report: ImportReport,
    val rolledBack: Boolean = false,
)

data class ExportOptions(
    val format: ExportFormat,
    val ledgerId: String? = null,
    val startAt: Long? = null,
    val endAtExclusive: Long? = null,
    /** null = 支出与收入都导。 */
    val type: BillType? = null,
    val categoryL1Id: String? = null,
    val source: BillSource? = null,
    val includeImages: Boolean = false,
)

data class ExportResult(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val rowCount: Int,
    val imageBytes: Long = 0L,
)
