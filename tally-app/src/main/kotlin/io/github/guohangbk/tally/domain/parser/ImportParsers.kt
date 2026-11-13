package io.github.guohangbk.tally.domain.parser

import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.ImportEncoding
import io.github.guohangbk.tally.domain.model.ImportSourceType
import io.github.guohangbk.tally.domain.model.ParsedBillRow
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.domain.parseSignedAmount
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ParsedTable(
    val sourceType: ImportSourceType,
    val encoding: ImportEncoding,
    val headerRow: Int,
    val headers: List<String>,
    val rows: List<List<String>>,
    val ignoredColumns: List<String>,
)

object ImportParsers {
    private val tallyHeaders = listOf("时间", "账本", "分类", "二级分类", "类型", "金额", "币种", "备注", "已报销", "记账者", "账单图片", "关联账单")
    private val qianjiHeaders = listOf("ID", "时间", "账本", "分类", "二级分类", "类型", "金额", "币种", "账户1", "账户2", "备注", "已报销", "手续费", "优惠券", "记账者", "账单标记", "标签", "账单图片", "关联账单")
    private val alipayHeaders = listOf("记录时间", "分类", "收支类型", "金额", "备注", "账户", "来源", "标签")

    fun readTable(bytes: ByteArray, fileName: String, requested: ImportSourceType? = null): ParsedTable {
        val isXlsx = fileName.endsWith(".xlsx", ignoreCase = true) || bytes.startsWithZip()
        val rawRows: List<List<String>>
        val encoding: ImportEncoding
        if (isXlsx) {
            rawRows = XlsxCodec.read(bytes)
            encoding = ImportEncoding.UTF8
        } else {
            val csv = CsvCodec.read(bytes)
            rawRows = csv.rows
            encoding = csv.encoding
        }
        val headerIndex = rawRows.indexOfFirst { row -> detectSource(row.map(::cleanHeader)) != null }
        require(headerIndex >= 0) { "文件格式无法识别，请确认表头" }
        val headers = rawRows[headerIndex].map(::cleanHeader)
        val detected = detectSource(headers) ?: throw IllegalArgumentException("文件格式无法识别，请确认表头")
        val source = requested ?: detected
        if (requested != null && requested != detected) {
            throw IllegalArgumentException("文件格式与所选来源不符，检测到 ${detected.displayName}")
        }
        val data = rawRows.drop(headerIndex + 1).filterNot { it.all(String::isBlank) }
        return ParsedTable(
            sourceType = source,
            encoding = encoding,
            headerRow = headerIndex + 1,
            headers = headers,
            rows = data,
            ignoredColumns = ignoredColumns(source, headers),
        )
    }

    /**
     * 判定顺序必须从最具体的表头集开始：钱迹的 19 列包含了 Tally 模板的全部 12 列，
     * 先判模板会把钱迹文件误认成模板，「报销记录」这类钱迹专有映射就会失效。
     */
    fun detectSource(headers: List<String>): ImportSourceType? {
        val set = headers.toSet()
        return when {
            set.containsAll(qianjiHeaders) -> ImportSourceType.QIANJI
            set.containsAll(alipayHeaders) -> ImportSourceType.ALIPAY
            set.containsAll(tallyHeaders) -> ImportSourceType.TEMPLATE
            else -> null
        }
    }

    fun parseRows(table: ParsedTable): List<ParsedBillRow> = table.rows.mapIndexed { index, row ->
        val values = table.headers.mapIndexed { i, header -> header to row.getOrElse(i) { "" } }.toMap()
        when (table.sourceType) {
            ImportSourceType.TEMPLATE -> parseTally(index + table.headerRow + 1, values)
            ImportSourceType.QIANJI -> parseQianji(index + table.headerRow + 1, values)
            ImportSourceType.ALIPAY -> parseAlipay(index + table.headerRow + 1, values)
        }
    }

    private fun parseTagNames(raw: String?): List<String> =
        raw.orEmpty().split("、", ";", "；", ",").map(String::trim).filter(String::isNotEmpty).distinct()

    private fun parseTally(rowNumber: Int, values: Map<String, String>): ParsedBillRow {
        val errors = mutableListOf<String>()
        val occurred = parseDate(values["时间"]).also { if (it == null) errors += "时间无效" }
        val signed = parseSignedAmount(values["金额"].orEmpty())
        val amount = signed.first.also { if (it == null) errors += "金额无效" }
        val type = (if (signed.second) BillType.EXPENSE else parseType(values["类型"]))
            .also { if (it == null) errors += "类型无效" }
        val category = values["分类"].orEmpty().trim().also { if (it.isEmpty()) errors += "缺少分类" }
        val ledger = values["账本"].orEmpty().trim().also { if (it.isEmpty()) errors += "缺少账本" }
        return ParsedBillRow(
            rowNumber = rowNumber,
            externalId = values["ID"]?.trim()?.ifEmpty { null },
            occurredAt = occurred,
            ledgerName = ledger.ifEmpty { null },
            categoryL1Name = category.ifEmpty { null },
            categoryL2Name = values["二级分类"]?.trim()?.ifEmpty { null },
            type = type,
            amountMinor = amount,
            currency = values["币种"].orEmpty().ifBlank { "CNY" },
            note = values["备注"]?.trim()?.ifEmpty { null },
            reimbursed = parseReimbursed(values["已报销"].orEmpty()),
            recorder = values["记账者"]?.trim()?.ifEmpty { null },
            linkedExternalId = values["关联账单"]?.trim()?.ifEmpty { null },
            source = BillSource.IMPORT,
            isRefund = signed.second,
            attachmentRefs = values["账单图片"].orEmpty().split(";", "；").map(String::trim).filter(String::isNotEmpty),
            tagNames = parseTagNames(values["标签"]),
            errors = errors,
        )
    }

    private fun parseQianji(rowNumber: Int, values: Map<String, String>): ParsedBillRow {
        val rawType = values["类型"].orEmpty().trim()
        val errors = mutableListOf<String>()
        val occurred = parseDate(values["时间"]).also { if (it == null) errors += "时间无效" }
        val signed = parseSignedAmount(values["金额"].orEmpty())
        val amount = signed.first.also { if (it == null) errors += "金额无效" }
        val category = values["分类"].orEmpty().trim().also { if (it.isEmpty()) errors += "缺少分类" }
        val ledger = values["账本"].orEmpty().trim().also { if (it.isEmpty()) errors += "缺少账本" }
        val reimbursementRecord = rawType == "报销记录"
        val reimbursedExpense = rawType == "报销"
        // 钱迹的「退款」与负数金额都是钱回到手里：记成支出 + 退款标记，不进收入。
        val refundRow = rawType == "退款"
        val isRefund = refundRow || signed.second
        val type = when {
            reimbursementRecord -> BillType.INCOME
            reimbursedExpense || refundRow || signed.second -> BillType.EXPENSE
            else -> parseType(rawType)
        }.also { if (it == null) errors += "类型无效" }
        return ParsedBillRow(
            rowNumber = rowNumber,
            externalId = values["ID"]?.trim()?.ifEmpty { null },
            occurredAt = occurred,
            ledgerName = ledger.ifEmpty { null },
            categoryL1Name = category.ifEmpty { null },
            categoryL2Name = values["二级分类"]?.trim()?.ifEmpty { null },
            type = type,
            amountMinor = amount,
            currency = values["币种"].orEmpty().ifBlank { "CNY" },
            note = values["备注"]?.trim()?.ifEmpty { null },
            reimbursed = if (reimbursedExpense) ReimburseStatus.DONE else parseReimbursed(values["已报销"].orEmpty()),
            recorder = values["记账者"]?.trim()?.ifEmpty { null },
            linkedExternalId = values["关联账单"]?.trim()?.ifEmpty { null },
            source = BillSource.IMPORT,
            isReimbursement = reimbursementRecord,
            excludedFromStats = reimbursementRecord,
            isRefund = isRefund,
            attachmentRefs = values["账单图片"].orEmpty().split(";", "；").map(String::trim).filter(String::isNotEmpty),
            tagNames = parseTagNames(values["标签"]),
            errors = errors,
        )
    }

    private fun parseAlipay(rowNumber: Int, values: Map<String, String>): ParsedBillRow {
        val errors = mutableListOf<String>()
        val occurred = parseDate(values["记录时间"]).also { if (it == null) errors += "时间无效" }
        val signed = parseSignedAmount(values["金额"].orEmpty())
        val amount = signed.first.also { if (it == null) errors += "金额无效" }
        val type = (if (signed.second) BillType.EXPENSE else parseType(values["收支类型"]))
            .also { if (it == null) errors += "类型无效" }
        val category = values["分类"].orEmpty().trim().also { if (it.isEmpty()) errors += "缺少分类" }
        return ParsedBillRow(
            rowNumber = rowNumber,
            occurredAt = occurred,
            ledgerName = null,
            categoryL1Name = category.ifEmpty { null },
            type = type,
            amountMinor = amount,
            currency = "CNY",
            note = values["备注"]?.trim()?.ifEmpty { null },
            source = BillSource.IMPORT,
            isRefund = signed.second,
            tagNames = parseTagNames(values["标签"]),
            errors = errors,
        )
    }

    /** 负数金额不判坏行：统一转成「支出 + 退款标记」，符号识别在 `Money.parseSignedAmount`。 */

    fun parseDate(raw: String?): Long? {
        val text = raw?.trim().orEmpty().replace('年', '-').replace('月', '-').replace("日", "")
        if (text.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val patterns = listOf(
            DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:m:s"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:m"),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
        )
        patterns.forEach { formatter ->
            runCatching {
                val parsed = if (text.contains(':')) LocalDateTime.parse(text, formatter)
                else LocalDate.parse(text, formatter).atStartOfDay()
                return parsed.atZone(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun parseType(raw: String?): BillType? = when (raw?.trim()) {
        "支出", "EXPENSE" -> BillType.EXPENSE
        "收入", "INCOME" -> BillType.INCOME
        else -> null
    }

    private fun parseReimbursed(raw: String?): ReimburseStatus = when (raw?.trim()) {
        "是", "已报销", "DONE" -> ReimburseStatus.DONE
        "待报销", "待", "PENDING" -> ReimburseStatus.PENDING
        else -> ReimburseStatus.NONE
    }

    private fun ignoredColumns(source: ImportSourceType, headers: List<String>): List<String> {
        val ignored = when (source) {
            ImportSourceType.TEMPLATE -> emptyList()
            ImportSourceType.QIANJI -> listOf("账户1", "账户2", "手续费", "优惠券", "账单标记")
            ImportSourceType.ALIPAY -> listOf("账户")
        }
        return ignored.filter(headers::contains)
    }

    private fun cleanHeader(value: String): String = value.trim().removePrefix("\uFEFF")

    private fun ByteArray.startsWithZip(): Boolean = size >= 2 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()

    private val ImportSourceType.displayName: String
        get() = when (this) {
            ImportSourceType.TEMPLATE -> "Tally 模板"
            ImportSourceType.QIANJI -> "钱迹"
            ImportSourceType.ALIPAY -> "支付宝"
        }
}

