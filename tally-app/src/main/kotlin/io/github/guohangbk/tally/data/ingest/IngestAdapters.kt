package io.github.guohangbk.tally.data.ingest

import io.github.guohangbk.tally.data.ai.AiBillDraftService
import io.github.guohangbk.tally.data.ai.BillDraftOutcome
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.repository.BillRepository
import io.github.guohangbk.tally.domain.ai.AiTurn
import io.github.guohangbk.tally.domain.ingest.BillIngestor
import io.github.guohangbk.tally.domain.ingest.DefaultBillIngestor
import io.github.guohangbk.tally.domain.ingest.DuplicateLookup
import io.github.guohangbk.tally.domain.ingest.ExtractedBill
import io.github.guohangbk.tally.domain.ingest.IngestKeys
import io.github.guohangbk.tally.domain.ingest.IngestRequest
import io.github.guohangbk.tally.domain.ingest.IngestResult
import io.github.guohangbk.tally.domain.ingest.LlmExtractor
import io.github.guohangbk.tally.domain.ingest.RuleBill
import io.github.guohangbk.tally.domain.ingest.RuleExtractor
import io.github.guohangbk.tally.domain.ingest.findOrderNo
import io.github.guohangbk.tally.domain.ingest.originMap
import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.parser.QuickNoteCatalog
import io.github.guohangbk.tally.domain.parser.QuickNoteParser
import io.github.guohangbk.tally.domain.parser.ScreenshotRules

/** 规则层：语音/文字走 QuickNoteParser，截图 OCR 走 ScreenshotRules，输出同一份字段。 */
class DefaultRuleExtractor(
    private val catalog: suspend () -> QuickNoteCatalog,
) : RuleExtractor {
    override fun extract(request: IngestRequest, text: String): RuleBill {
        if (text.isBlank()) return RuleBill.None
        return if (request.ocrText.isBlank()) quickNote(text, request) else screenshot(text, request)
    }

    private fun quickNote(text: String, request: IngestRequest): RuleBill {
        val parsed = QuickNoteParser.parse(text, catalogSnapshot(), request.nowMillis)
        if (parsed.amountMinor == null && parsed.amountCandidates == 0) return RuleBill.None
        val category = parsed.category
        val bill = ExtractedBill(
            amountMinor = parsed.amountMinor,
            type = parsed.type,
            occurredAt = parsed.occurredAt,
            timePrecision = parsed.timePrecision,
            categoryL1Id = category?.level1Id,
            categoryL2Id = category?.level2Id,
            note = parsed.note,
            merchant = null,
            orderNo = null,
            keyword = parsed.matchedKeyword,
            origins = originMap(
                IngestKeys.AMOUNT to parsed.amountMinor,
                IngestKeys.TYPE to parsed.type,
                IngestKeys.DATE to parsed.occurredAt,
                IngestKeys.CATEGORY to category?.level1Id,
                IngestKeys.NOTE to parsed.note,
                IngestKeys.MERCHANT to null,
                IngestKeys.ORDER to null,
            ),
            needsConfirmation = parsed.missingFields.map { it.name }.toSet(),
        )
        return if (parsed.hasMultipleAmounts) RuleBill.Multiple(listOf(bill)) else RuleBill.Single(bill)
    }

    private fun screenshot(text: String, request: IngestRequest): RuleBill {
        val fields = ScreenshotRules.extractAll(text, request.nowMillis)
        val bills = fields.mapIndexedNotNull { fieldIndex, field ->
            val amount = field.amountMinor ?: return@mapIndexedNotNull null
            // 候选词取自这一块订单自己的抬头：多块截图里，全篇第一行对每块都是同一句。
            val probe = listOfNotNull(
                field.merchant,
                field.orderNo,
                field.rawText.lineSequence().firstOrNull { it.isNotBlank() },
            )
                .joinToString(" ")
            val parsed = QuickNoteParser.parse(probe, catalogSnapshot(), request.nowMillis)
            val category = parsed.category
            ExtractedBill(
                amountMinor = amount,
                type = field.type,
                occurredAt = field.occurredAt,
                timePrecision = field.timePrecision,
                categoryL1Id = category?.level1Id,
                categoryL2Id = category?.level2Id,
                note = field.merchant,
                merchant = field.merchant,
                orderNo = field.orderNo ?: findOrderNo(text),
                keyword = parsed.matchedKeyword,
                origins = originMap(
                    IngestKeys.AMOUNT to amount,
                    IngestKeys.TYPE to field.type,
                    IngestKeys.DATE to field.occurredAt,
                    IngestKeys.CATEGORY to category?.level1Id,
                    IngestKeys.NOTE to field.merchant,
                    IngestKeys.MERCHANT to field.merchant,
                    IngestKeys.ORDER to field.orderNo,
                ),
                needsConfirmation = field.missingFields.map { it.name }.toSet(),
                itemIndex = fieldIndex.takeIf { fields.size > 1 },
            )
        }
        return when {
            bills.isEmpty() || ScreenshotRules.needsLlm(text) -> RuleBill.NeedsLlm
            bills.size == 1 -> RuleBill.Single(bills.single())
            else -> RuleBill.Multiple(bills)
        }
    }

    private fun catalogSnapshot(): QuickNoteCatalog = catalogFallback

    private var catalogFallback: QuickNoteCatalog = QuickNoteCatalog(emptyList(), emptyList())

    suspend fun refreshCatalog() {
        catalogFallback = catalog()
    }
}

/** AI 补缺：只拿脱敏后的文本，素材通道的原图永远不出设备。 */
class DefaultLlmExtractor(
    private val service: AiBillDraftService,
    private val catalog: suspend () -> QuickNoteCatalog,
) : LlmExtractor {
    override suspend fun extract(
        request: IngestRequest,
        outboundText: String,
        rawText: String,
        missing: List<String>,
    ): List<ExtractedBill> {
        if (outboundText.isBlank() && request.images.isEmpty()) return emptyList()
        val note = "这是本机规则没拆清楚的素材。只根据脱敏文本整理成待确认草稿，不要自动记账；" +
            "同一张图有多笔订单时逐笔列出并用 item_index 标顺序。\n"
        val outcome = service.extractFields(
            message = note + outboundText,
            catalog = catalog(),
            fallbackMillis = request.fallbackMillis,
            localRawText = rawText,
            batch = request.materialIndex == -1,
        )
        if (outcome !is BillDraftOutcome.Draft) return emptyList()
        return rowsOf(outcome)
    }

    override suspend fun extractChat(
        request: IngestRequest,
        message: String,
        rawText: String,
        history: List<AiTurn>,
    ): List<ExtractedBill> {
        val outcome = service.extract(
            message = if (request.images.isEmpty() || request.redactedOcrText.isBlank()) {
                message
            } else {
                "$message\n${request.redactedOcrText}"
            },
            images = request.images,
            catalog = catalog(),
            history = history,
            fallbackMillis = request.fallbackMillis,
            localRawText = rawText,
        )
        if (outcome !is BillDraftOutcome.Draft) return emptyList()
        return rowsOf(outcome)
    }

    private fun rowsOf(outcome: BillDraftOutcome.Draft): List<ExtractedBill> =
        outcome.bills.map { extracted ->
            val fields = extracted.fields
            ExtractedBill(
                amountMinor = fields.amountMinor,
                type = fields.type,
                occurredAt = fields.occurredAt,
                timePrecision = fields.timePrecision,
                categoryL1Id = fields.categoryL1Id,
                categoryL2Id = fields.categoryL2Id,
                note = fields.note,
                merchant = fields.merchant,
                orderNo = fields.orderNo,
                materialIndex = fields.materialIndex,
                itemIndex = fields.itemIndex,
                origins = originMap(
                    IngestKeys.AMOUNT to fields.amountMinor,
                    IngestKeys.TYPE to fields.type,
                    IngestKeys.DATE to fields.occurredAt.takeIf {
                        DraftField.DATE.name !in extracted.needsConfirmation.map { confirmation -> confirmation.name }
                    },
                    IngestKeys.CATEGORY to fields.categoryL1Id,
                    IngestKeys.NOTE to fields.note,
                    IngestKeys.MERCHANT to fields.merchant,
                    IngestKeys.ORDER to fields.orderNo,
                ),
                needsConfirmation = extracted.needsConfirmation.map { it.name }.toSet(),
            )
        }
}

class RoomDuplicateLookup(private val bills: BillRepository) : DuplicateLookup {
    override suspend fun isDuplicate(orderNo: String?, imageSha: String?, itemIndex: Int?): Boolean {
        if (!orderNo.isNullOrBlank() && bills.findByExternalId(orderNo) != null) return true
        val fingerprint = imageSha?.let { if (itemIndex == null) it else "$it#$itemIndex" }
        if (!fingerprint.isNullOrBlank() && bills.findByFingerprint(fingerprint) != null) return true
        return false
    }
}

class IngestCoordinator(
    private val ingestor: BillIngestor,
    private val rules: DefaultRuleExtractor,
) {
    suspend fun ingest(request: IngestRequest): IngestResult {
        rules.refreshCatalog()
        val result = ingestor.ingest(request)
        logResult(listOf(request), listOf(result))
        return result
    }

    suspend fun ingestBatch(requests: List<IngestRequest>): List<IngestResult> {
        rules.refreshCatalog()
        val results = ingestor.ingestBatch(requests)
        logResult(requests, results)
        return results
    }

    private fun logResult(requests: List<IngestRequest>, results: List<IngestResult>) {
        DebugLog.log(
            "识别管线",
            "${requests.firstOrNull()?.source?.name.orEmpty()} · ${requests.size} 份素材 · " +
                results.sumOf { it.bills.size } + " 笔 · " +
                results.flatMap { it.bills }.joinToString("；") { bill -> bill.verdict.grade.name }
                    .ifEmpty { results.firstOrNull()?.reason.orEmpty() },
        )
    }
}

fun createBillIngestor(
    service: AiBillDraftService,
    bills: BillRepository,
    catalog: suspend () -> QuickNoteCatalog,
): IngestCoordinator {
    val rules = DefaultRuleExtractor(catalog)
    return IngestCoordinator(
        ingestor = DefaultBillIngestor(
            rules = rules,
            llm = DefaultLlmExtractor(service, catalog),
            duplicates = RoomDuplicateLookup(bills),
        ),
        rules = rules,
    )
}

