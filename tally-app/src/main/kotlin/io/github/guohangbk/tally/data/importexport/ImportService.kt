package io.github.guohangbk.tally.data.importexport

import androidx.room.withTransaction
import io.github.guohangbk.tally.data.db.TallyDatabase
import io.github.guohangbk.tally.data.db.dao.AttachmentDao
import io.github.guohangbk.tally.data.db.dao.ImportBatchDao
import io.github.guohangbk.tally.data.db.dao.ImportBillDao
import io.github.guohangbk.tally.data.db.dao.BillDao
import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.entity.AttachmentEntity
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.ImportBatchEntity
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.DedupeKeyKind
import io.github.guohangbk.tally.domain.model.DuplicateRow
import io.github.guohangbk.tally.domain.model.ImportBatchStatus
import io.github.guohangbk.tally.domain.model.ImportExistingKeys
import io.github.guohangbk.tally.domain.model.ImportOptions
import io.github.guohangbk.tally.domain.model.ImportOutcome
import io.github.guohangbk.tally.domain.model.ImportPreview
import io.github.guohangbk.tally.domain.model.ImportReport
import io.github.guohangbk.tally.domain.model.ImportSourceType
import io.github.guohangbk.tally.domain.model.MonthlyReconciliation
import io.github.guohangbk.tally.domain.model.ParsedBillRow
import io.github.guohangbk.tally.domain.parser.ImportParsers
import io.github.guohangbk.tally.domain.parser.ParsedTable
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 解析、预览、事务导入与逐月对账。UI 只拿这个 facade。 */
class ImportService(
    private val database: TallyDatabase,
    private val billDao: BillDao,
    private val ledgerDao: LedgerDao,
    private val categoryDao: CategoryDao,
    private val attachmentDao: AttachmentDao,
    private val importBatchDao: ImportBatchDao,
    private val importBillDao: ImportBillDao,
    private val preferences: TallyPreferences,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun parse(fileBytes: ByteArray, fileName: String, requestedSource: ImportSourceType? = null): ParsedTable =
        ImportParsers.readTable(fileBytes, fileName, requestedSource)

    /** 判重要跟库里已有的 ID 和指纹比，一次取全，别在逐行预览里发 N 次查询。 */
    suspend fun existingKeys(): ImportExistingKeys = ImportExistingKeys(
        externalIds = importBillDao.allExternalIds().toSet(),
        fingerprints = importBillDao.allFingerprints().toSet(),
    )

    fun preview(table: ParsedTable, existing: ImportExistingKeys = ImportExistingKeys()): ImportPreview {
        val rows = ImportParsers.parseRows(table)
        val valid = rows.filter { it.isValid }
        val seenKeys = mutableSetOf<String>()
        val duplicates = valid.mapNotNull { row ->
            val key = row.dedupeKey()
            val keyKind = if (row.externalId != null) DedupeKeyKind.EXTERNAL_ID else DedupeKeyKind.FINGERPRINT
            val withinFile = key != null && !seenKeys.add(key)
            val inDatabase = row.existingKeyKind(existing)
            when {
                withinFile -> DuplicateRow(row, keyKind, withinFile = true)
                inDatabase != null -> DuplicateRow(row, inDatabase, withinFile = false)
                else -> null
            }
        }
        val categories = valid.mapNotNull { it.categoryL1Name }.toSet()
        return ImportPreview(
            sourceType = table.sourceType,
            fileName = "",
            encoding = table.encoding,
            headerRow = table.headerRow,
            totalRows = rows.size,
            validRows = valid.size,
            invalidRows = rows.size - valid.size,
            reimbursementRows = valid.count { it.isReimbursement },
            newCategoryCount = categories.size,
            dateRangeStart = valid.mapNotNull { it.occurredAt }.minOrNull(),
            dateRangeEnd = valid.mapNotNull { it.occurredAt }.maxOrNull(),
            previewRows = valid.take(20),
            errorRows = rows.filterNot { it.isValid },
            ignoredColumns = table.ignoredColumns,
            duplicateRows = duplicates.size,
            duplicateItems = duplicates,
        )
    }

    suspend fun import(
        table: ParsedTable,
        options: ImportOptions,
        fileName: String,
        sourceBytes: ByteArray,
    ): ImportOutcome {
        val parsed = ImportParsers.parseRows(table)
        val valid = parsed.filter { it.isValid }
        val invalid = parsed.filterNot { it.isValid }
        if (!options.skipInvalidRows && invalid.isNotEmpty()) {
            val report = emptyReport(table.sourceType, parsed.size, invalid.size, "第 ${invalid.first().rowNumber} 行数据异常，导入已取消")
            return ImportOutcome(report, rolledBack = true)
        }
        val targetLedger = options.targetLedgerId?.let { ledgerDao.findById(it) }
        if (table.sourceType == ImportSourceType.ALIPAY && targetLedger == null) {
            return ImportOutcome(emptyReport(table.sourceType, parsed.size, invalid.size, "请选择导入到哪个账本"), rolledBack = true)
        }
        val deviceId = preferences.deviceId()
        val timestamp = now()
        val sourceHash = sha256(sourceBytes)
        val batch = ImportBatchEntity(
            id = newId(),
            sourceType = table.sourceType.name,
            ledgerId = targetLedger?.id ?: "",
            fileName = fileName,
            sourceSha256 = sourceHash,
            totalRows = parsed.size,
            status = ImportBatchStatus.RUNNING.name,
            startedAt = timestamp,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = deviceId,
        )
        return try {
            database.withTransaction {
                val written = mutableListOf<BillEntity>()
                val planned = mutableListOf<ParsedBillRow>()
                var skipped = 0
                for (row in valid) {
                    val ledger = resolveLedger(row, targetLedger, deviceId, timestamp)
                    val (l1, l2) = resolveCategories(row, deviceId, timestamp)
                    // 判重键只有一个：有外部 ID 就用它，没有才看组合指纹，不能交叉命中。
                    val existing = row.dedupeKey()?.let { key ->
                        if (row.externalId != null) {
                            importBillDao.findByExternalId(key)
                        } else {
                            importBillDao.findByFingerprint(key)
                        }
                    }
                    if (options.dedupe && existing != null) {
                        skipped++
                        continue
                    }
                    planned += row
                    val bill = BillEntity(
                        id = row.externalId?.takeIf { it.startsWith("qj") }?.let { "qj-$it" } ?: newId(),
                        ledgerId = ledger.id,
                        categoryL1Id = l1?.id,
                        categoryL2Id = l2?.id,
                        type = row.type?.name ?: BillType.EXPENSE.name,
                        amountMinor = row.amountMinor ?: 0L,
                        currency = row.currency,
                        occurredAt = row.occurredAt ?: timestamp,
                        timePrecision = row.precision(),
                        note = row.note,
                        source = BillSource.IMPORT.name,
                        externalId = row.externalId,
                        fingerprint = row.fingerprint(),
                        reimbursed = row.reimbursed.name,
                        isReimbursement = row.isReimbursement,
                        isRefund = row.isRefund,
                        excludedFromStats = row.excludedFromStats,
                        recorder = row.recorder,
                        linkedExternalId = row.linkedExternalId,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                        deviceId = deviceId,
                    )
                    billDao.upsert(bill)
                    written += bill
                }
                resolveLinks(written)
                val report = buildReport(batch, table.sourceType, parsed.size, planned, written, skipped, invalid.size, table.ignoredColumns)
                importBatchDao.insert(
                    batch.copy(
                        importedRows = written.size,
                        skippedRows = skipped,
                        failedRows = invalid.size,
                        status = if (report.months.all { it.isBalanced }) ImportBatchStatus.SUCCESS.name else ImportBatchStatus.FAILED.name,
                        reportJson = JSONObject().put("duplicate_rows", skipped).toString(),
                        finishedAt = now(),
                        updatedAt = now(),
                    ),
                )
                ImportOutcome(report)
            }
        } catch (error: Exception) {
            val report = emptyReport(table.sourceType, parsed.size, invalid.size, error.message ?: "导入失败，已整体回滚")
            ImportOutcome(report, rolledBack = true)
        }
    }

    /**
     * 用户在预览页选了目标账本，就整份文件都进这个账本——钱迹导出里的「账本」列只当备注，
     * 不再按列名另建一个同名账本，否则「新建账本 → 导入」会凭空多出一个账本。
     */
    private suspend fun resolveLedger(
        row: ParsedBillRow,
        target: LedgerEntity?,
        deviceId: String,
        timestamp: Long,
    ): LedgerEntity {
        if (target != null) return target
        val name = row.ledgerName?.trim()
        if (name != null) {
            ledgerDao.findByName(name)?.let { return it }
            return LedgerEntity(
                id = newId(),
                name = name,
                iconKey = "ledger",
                isDefault = false,
                sortOrder = timestamp.toDouble(),
                createdAt = timestamp,
                updatedAt = timestamp,
                deviceId = deviceId,
            ).also { ledgerDao.upsert(it) }
        }
        return ledgerDao.findDefault() ?: throw IllegalStateException("请选择导入到哪个账本")
    }

    private suspend fun resolveCategories(
        row: ParsedBillRow,
        deviceId: String,
        timestamp: Long,
    ): Pair<CategoryEntity?, CategoryEntity?> {
        val l1Name = row.categoryL1Name?.trim()?.ifEmpty { null } ?: return null to null
        val type = if (row.type == BillType.INCOME) "INCOME" else "EXPENSE"
        val existing = categoryDao.findActive(type)
        val l1 = existing.firstOrNull { it.parentId == null && it.name == l1Name }
            ?: CategoryEntity(
                id = newId(),
                parentId = null,
                name = l1Name,
                type = type,
                iconKey = "tag",
                sortOrder = (existing.filter { it.parentId == null }.maxOfOrNull { it.sortOrder } ?: 0.0) + 1.0,
                createdAt = timestamp,
                updatedAt = timestamp,
                deviceId = deviceId,
            ).also { categoryDao.insert(it) }
        val l2Name = row.categoryL2Name?.trim()?.ifEmpty { null } ?: return l1 to null
        val l2 = existing.firstOrNull { it.parentId == l1.id && it.name == l2Name }
            ?: CategoryEntity(
                id = newId(),
                parentId = l1.id,
                name = l2Name,
                type = type,
                iconKey = l1.iconKey,
                sortOrder = (existing.filter { it.parentId == l1.id }.maxOfOrNull { it.sortOrder } ?: 0.0) + 1.0,
                createdAt = timestamp,
                updatedAt = timestamp,
                deviceId = deviceId,
            ).also { categoryDao.insert(it) }
        return l1 to l2
    }

    private suspend fun resolveLinks(written: List<BillEntity>) {
        written.filter { !it.linkedExternalId.isNullOrBlank() }.forEach { bill ->
            val target = bill.linkedExternalId?.let { importBillDao.findByExternalId(it) }
            if (target != null) {
                billDao.upsert(
                    bill.copy(
                        linkedExternalId = target.id,
                        // 退款行顺带挂上原单：统计按原单分类冲抵，而不是当成一笔独立支出。
                        refundOfBillId = if (bill.isRefund && target.isRefundTarget()) target.id else bill.refundOfBillId,
                    ),
                )
            }
        }
    }

    /** 只有没被退过的支出账单能当原单；导入文件指错了就保持未关联，不去猜。 */
    private fun BillEntity.isRefundTarget(): Boolean =
        type == BillType.EXPENSE.name && !isRefund && refundOfBillId == null

    private fun buildReport(
        batch: ImportBatchEntity,
        sourceType: ImportSourceType,
        totalRows: Int,
        planned: List<ParsedBillRow>,
        written: List<BillEntity>,
        skipped: Int,
        failed: Int,
        ignored: List<String>,
    ): ImportReport {
        val zone = ZoneId.systemDefault()
        // 跳过的重复行本来就不写，对账只比「该写的行」和实际写入的行，否则跳过必然被误判成不平。
        val sourceGroups = planned.groupBy {
            Instant.ofEpochMilli(it.occurredAt ?: 0L).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM"))
        }
        val importedGroups = written.groupBy {
            Instant.ofEpochMilli(it.occurredAt).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM"))
        }
        val months = (sourceGroups.keys + importedGroups.keys).sortedDescending().map { key ->
            val sourceRows = sourceGroups[key].orEmpty()
            val importedRows = importedGroups[key].orEmpty()
            MonthlyReconciliation(
                monthKey = key,
                sourceCount = sourceRows.size,
                importedCount = importedRows.size,
                sourceExpenseMinor = sourceRows.filter { it.type == BillType.EXPENSE && !it.excludedFromStats }.sumOf { it.amountMinor ?: 0L },
                importedExpenseMinor = importedRows.filter { it.type == BillType.EXPENSE.name && !it.excludedFromStats }.sumOf { it.amountMinor },
                sourceIncomeMinor = sourceRows.filter { it.type == BillType.INCOME && !it.excludedFromStats }.sumOf { it.amountMinor ?: 0L },
                importedIncomeMinor = importedRows.filter { it.type == BillType.INCOME.name && !it.excludedFromStats }.sumOf { it.amountMinor },
                sourceExcludedMinor = sourceRows.filter { it.excludedFromStats }.sumOf { it.amountMinor ?: 0L },
                importedExcludedMinor = importedRows.filter { it.excludedFromStats }.sumOf { it.amountMinor },
            )
        }
        return ImportReport(
            batchId = batch.id,
            status = if (months.all { it.isBalanced }) ImportBatchStatus.SUCCESS else ImportBatchStatus.FAILED,
            sourceType = sourceType,
            totalRows = totalRows,
            importedRows = written.size,
            skippedRows = skipped,
            failedRows = failed,
            months = months,
            ignoredColumns = ignored,
            message = if (skipped > 0) "已跳过 $skipped 笔重复账单" else null,
        )
    }

    private fun emptyReport(source: ImportSourceType, total: Int, failed: Int, message: String) = ImportReport(
        batchId = "",
        status = ImportBatchStatus.FAILED,
        sourceType = source,
        totalRows = total,
        importedRows = 0,
        skippedRows = 0,
        failedRows = failed,
        months = emptyList(),
        message = message,
    )

    /** 本地时区的 00:00 就是「只有日期」；按 UTC 取模会把东八区的零点误判成有时刻。 */
    private fun ParsedBillRow.precision(): String {
        val millis = occurredAt ?: return "DATE"
        val localTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
        return if (localTime == LocalTime.MIDNIGHT) "DATE" else "DATETIME"
    }

    private fun ParsedBillRow.dedupeKey(): String? = when {
        externalId != null -> externalId
        else -> fingerprint()
    }

    /** 与导入执行完全一致：有外部 ID 就只比外部 ID，没有才比组合指纹。 */
    private fun ParsedBillRow.existingKeyKind(existing: ImportExistingKeys): DedupeKeyKind? {
        val external = externalId
        if (external != null) {
            return DedupeKeyKind.EXTERNAL_ID.takeIf { external in existing.externalIds }
        }
        return DedupeKeyKind.FINGERPRINT.takeIf { fingerprint() in existing.fingerprints }
    }

    private fun ParsedBillRow.fingerprint(): String? {
        val time = occurredAt ?: return null
        return sha256("$time|${amountMinor ?: 0L}|${note.orEmpty()}|${categoryL1Name.orEmpty()}|${categoryL2Name.orEmpty()}")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

