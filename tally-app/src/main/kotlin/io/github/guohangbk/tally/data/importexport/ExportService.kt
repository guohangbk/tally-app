package io.github.guohangbk.tally.data.importexport

import io.github.guohangbk.tally.data.db.dao.AttachmentDao
import io.github.guohangbk.tally.data.db.dao.BillDao
import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.dao.TagDao
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.ExportFormat
import io.github.guohangbk.tally.domain.model.ExportOptions
import io.github.guohangbk.tally.domain.model.ExportResult
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.domain.parser.CsvCodec
import io.github.guohangbk.tally.domain.parser.XlsxCodec
import io.github.guohangbk.tally.domain.parser.XlsxSheet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 14 列账单导出与 13 列模板，CSV/XLSX 都可被导入器原样读回。 */
class ExportService(
    private val billDao: BillDao,
    private val ledgerDao: LedgerDao,
    private val categoryDao: CategoryDao,
    private val attachmentDao: AttachmentDao,
    private val tagDao: TagDao,
) {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    suspend fun export(options: ExportOptions): ExportResult {
        val rows = buildRows(options)
        require(rows.size > 1) { "该范围内没有账单" }
        val stamp = Instant.now().atZone(ZoneId.systemDefault()).format(fileStamp)
        val fileName = "tally_export_$stamp.${if (options.format == ExportFormat.CSV) "csv" else "xlsx"}"
        val bytes = if (options.format == ExportFormat.CSV) {
            CsvCodec.write(rows)
        } else {
            XlsxCodec.write(listOf(XlsxSheet("账单", rows)))
        }
        val imageBytes = if (options.includeImages) rows.drop(1).sumOf { row -> row[11].length.toLong() } else 0L
        return ExportResult(
            fileName = fileName,
            mimeType = if (options.format == ExportFormat.CSV) "text/csv" else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes = bytes,
            rowCount = rows.size - 1,
            imageBytes = imageBytes,
        )
    }

    fun templateCsv(): ByteArray = CsvCodec.write(TallyCsvSchema.templateRows())

    private suspend fun buildRows(options: ExportOptions): List<List<String>> {
        val start = options.startAt ?: 0L
        val end = options.endAtExclusive ?: Long.MAX_VALUE
        val rows = billDao.exportRows(
            ledgerId = options.ledgerId,
            startAt = start,
            endAtExclusive = end,
            type = options.type?.name,
            categoryL1Id = options.categoryL1Id,
            source = options.source?.name,
        )
        val ledgers = ledgerDao.findAllOnce().associateBy { it.id }
        val categories = rows.mapNotNull { it.bill.categoryL1Id }.toSet().associateWith { id ->
            categoryDao.findById(id)
        }
        val categoryL2 = rows.mapNotNull { it.bill.categoryL2Id }.toSet().associateWith { id ->
            categoryDao.findById(id)
        }
        val attachments = if (rows.isEmpty()) emptyMap() else attachmentDao
            .findByBillIds(rows.map { it.bill.id })
            .groupBy { it.billId }
        // 一次取一批：导出常常是整本账，逐笔查标签会把 IO 拉成一条长队列。
        val tagNames = rows.chunked(500).flatMap { chunk ->
            tagDao.namesForBills(chunk.map { it.bill.id })
        }.groupBy({ it.billId }, { it.name })
        val zone = ZoneId.systemDefault()
        return buildList {
            add(TallyCsvSchema.header)
            rows.forEach { item ->
                val bill = item.bill
                val imageRefs = attachments[bill.id].orEmpty().joinToString(";") { it.relativePath }
                add(
                    listOf(
                        bill.id,
                        Instant.ofEpochMilli(bill.occurredAt).atZone(zone).format(dateTime),
                        ledgers[bill.ledgerId]?.name ?: item.ledgerName,
                        categories[bill.categoryL1Id]?.name ?: item.categoryL1Name.orEmpty(),
                        categoryL2[bill.categoryL2Id]?.name ?: item.categoryL2Name.orEmpty(),
                        if (bill.type == BillType.INCOME.name) "收入" else "支出",
                        minorToText(bill.amountMinor),
                        bill.currency,
                        bill.note.orEmpty(),
                        when (bill.reimbursed) {
                            ReimburseStatus.DONE.name -> "是"
                            ReimburseStatus.PENDING.name -> "待报销"
                            else -> ""
                        },
                        bill.recorder.orEmpty(),
                        imageRefs,
                        // 退款写原单 ID（第 1 列能对上）；其它关联（钱迹订单）仍写外部单号。
                        bill.refundOfBillId ?: bill.linkedExternalId.orEmpty(),
                        // 多枚标签用顿号连：Excel / 手机输入法里都好打，也不会和标签名里的逗号打架。
                        tagNames[bill.id].orEmpty().joinToString("、"),
                    ),
                )
            }
        }
    }

    private fun minorToText(minor: Long): String {
        val cents = (minor % 100).toString().padStart(2, '0')
        return "${minor / 100}.$cents"
    }
}

/**
 * 导出与导入模板共用的列定义。模板就是表头去掉 `ID`，测试也读这里——
 * 免得列名与示例各写一份，改了一边另一边悄悄漂掉。
 */
internal object TallyCsvSchema {
    val header = listOf(
        "ID", "时间", "账本", "分类", "二级分类", "类型", "金额", "币种",
        "备注", "已报销", "记账者", "账单图片", "关联账单", "标签",
    )

    fun templateRows(): List<List<String>> = listOf(
        header.drop(1),
        listOf("2026-10-01 12:30:00", "日常账本", "三餐", "午餐", "支出", "28.50", "CNY", "公司楼下快餐", "", "", "", "", ""),
        listOf("2026-10-02 09:15:00", "日常账本", "交通", "停车费", "支出", "120.00", "CNY", "商场停车", "", "", "", "", "🚗 车"),
        listOf("2026-10-05 20:00:00", "日常账本", "工资", "主职收入", "收入", "15000.00", "CNY", "10月工资", "", "", "", "", ""),
        listOf("2026-10-06 08:00:00", "日常账本", "保险", "车险", "支出", "4468.00", "CNY", "车险续保", "是", "小马儿", "", "", "🚗 车、📄 续保"),
    )
}
