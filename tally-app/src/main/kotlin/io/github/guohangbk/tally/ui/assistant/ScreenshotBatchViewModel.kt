package io.github.guohangbk.tally.ui.assistant

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.ocr.OcrOutcome
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillDraft
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.parser.QuickNoteCatalog
import io.github.guohangbk.tally.domain.parser.QuickNoteParser
import io.github.guohangbk.tally.domain.parser.ScreenshotRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次批量识别里的一张图。[confidence] 只由「认出了几个字段」推出来，不引入任何猜测分数。 */
data class ScannedBill(
    val key: Int,
    val imageIndex: Int,
    val amountMinor: Long,
    val type: BillType,
    val occurredAt: Long?,
    val categoryL1Id: String?,
    val categoryL2Id: String?,
    val categoryLabel: String?,
    val merchant: String?,
    val confidence: Float,
    /** 命中分类的那个词，交记账页之后判断要不要沉淀；没命中就是 null。 */
    val keyword: String? = null,
    val checked: Boolean = true,
) {
    val needsCategory: Boolean get() = categoryL1Id == null
}

/** 一张图什么都没认出来时，要能指名道姓地说清是哪一张、为什么。 */
data class ScanFailure(val imageIndex: Int, val reason: String)

data class ScreenshotBatchUiState(
    val busy: Boolean = false,
    val stage: String? = null,
    val imageTotal: Int = 0,
    val imageDone: Int = 0,
    val bills: List<ScannedBill> = emptyList(),
    val failures: List<ScanFailure> = emptyList(),
    val savedCount: Int = 0,
    val error: String? = null,
) {
    val checkedBills: List<ScannedBill> get() = bills.filter { it.checked }
    val blockedByMissingCategory: Boolean get() = checkedBills.any { it.needsCategory }
    val done: Boolean get() = !busy && (bills.isNotEmpty() || failures.isNotEmpty())
}

/**
 * 长按 ＋ 左滑那条链路：一次选最多 9 张，逐张走截图页已经在用的
 * 「读图 → 端侧 OCR → 规则抽取 → 关键词命中分类」，只是结果汇成一份待确认列表。
 * 识别管线不重写，落库前必须过人手（V-06 / V-07）。
 */
class ScreenshotBatchViewModel(private val graph: AppGraph) : ViewModel() {
    private val state = MutableStateFlow(ScreenshotBatchUiState())
    val uiState: StateFlow<ScreenshotBatchUiState> = state.asStateFlow()

    private var catalog: QuickNoteCatalog = QuickNoteCatalog(emptyList(), emptyList())
    private var ledgerId: String? = null
    private var expenseCategories: List<CategoryEntity> = emptyList()
    private val sources = mutableMapOf<Int, Uri>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val id = graph.ledgerRepository.defaultLedger()?.id
            ledgerId = id
            if (id != null) {
                catalog = graph.keywordRuleRepository.catalog()
                expenseCategories = graph.billRepository.activeCategories(CategoryType.EXPENSE)
            }
        }
    }

    /** 逐张识别。任何一张失败都不影响其余的，但一定要出现在 [ScreenshotBatchUiState.failures] 里。 */
    fun scan(uris: List<Uri>) {
        if (uris.isEmpty()) return
        sources.clear()
        state.update {
            ScreenshotBatchUiState(busy = true, stage = "正在本机识别…", imageTotal = uris.size, imageDone = 0)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val scanned = mutableListOf<ScannedBill>()
            val failures = mutableListOf<ScanFailure>()
            uris.forEachIndexed { index, uri ->
                val key = index
                sources[key] = uri
                val bytes = graph.attachmentStore.readSource(uri)
                if (bytes == null) {
                    failures += ScanFailure(index, "这张图片读不出来")
                } else {
                    when (val ocr = graph.screenshotTextReader.read(bytes)) {
                        is OcrOutcome.NoText -> failures += ScanFailure(index, "图里没有可解析的文字")
                        is OcrOutcome.Failed -> failures += ScanFailure(index, ocr.reason)
                        is OcrOutcome.Recognized -> {
                            val fields = ScreenshotRules.extract(ocr.text)
                            val amount = fields.amountMinor
                            if (amount == null) {
                                failures += ScanFailure(index, "没认出金额")
                            } else {
                                val matched = matchCategory(ocr.text, fields.merchant, fields.orderNo)
                                scanned += ScannedBill(
                                    key = key,
                                    imageIndex = index,
                                    amountMinor = amount,
                                    type = fields.type,
                                    occurredAt = fields.occurredAt,
                                    categoryL1Id = matched.level1Id,
                                    categoryL2Id = matched.level2Id,
                                    categoryLabel = matched.name,
                                    merchant = fields.merchant,
                                    confidence = confidenceOf(matched.level1Id != null, fields.occurredAt != null),
                                    keyword = matched.keyword,
                                )
                            }
                        }
                    }
                }
                state.update { it.copy(imageDone = index + 1) }
            }
            state.update {
                it.copy(
                    busy = false,
                    stage = null,
                    bills = scanned.sortedByDescending { bill -> bill.confidence },
                    failures = failures,
                )
            }
        }
    }

    fun toggle(key: Int) = state.update {
        it.copy(bills = it.bills.map { bill -> if (bill.key == key) bill.copy(checked = !bill.checked) else bill })
    }

    /** 逐条修改：把这一笔交给记账页，由人在原页确认；列表里先摘掉，避免重复落库。 */
    fun takeForEdit(key: Int): ScannedBill? {
        val bill = state.value.bills.firstOrNull { it.key == key } ?: return null
        state.update { it.copy(bills = it.bills.filterNot { row -> row.key == key }) }
        return bill
    }

    /** 存入勾选的几笔。分类没认出的那一律挡在这里，不塞进「其他」后静默落库。 */
    fun saveChecked(onSaved: (Int) -> Unit) {
        val current = state.value
        val target = current.checkedBills
        if (target.isEmpty() || target.any { it.needsCategory }) return
        state.update { it.copy(busy = true, stage = "正在写入…") }
        viewModelScope.launch(Dispatchers.IO) {
            val ledger = ledgerId
            if (ledger == null) {
                state.update { it.copy(busy = false, stage = null, error = "还没有可用账本") }
                return@launch
            }
            var saved = 0
            val failures = current.failures.toMutableList()
            try {
                target.forEach { bill ->
                    runCatching {
                        graph.billRepository.save(
                            BillDraft(
                                id = null,
                                ledgerId = ledger,
                                categoryL1Id = bill.categoryL1Id,
                                categoryL2Id = bill.categoryL2Id,
                                type = bill.type,
                                amountMinor = bill.amountMinor,
                                occurredAt = bill.occurredAt ?: System.currentTimeMillis(),
                                timePrecision = if (bill.occurredAt != null) TimePrecision.DATETIME else TimePrecision.DATE,
                                note = bill.merchant,
                                source = BillSource.IMPORT,
                            ),
                        )
                    }.onSuccess { saved++ }
                        .onFailure { failures += ScanFailure(bill.imageIndex, it.message ?: "这一笔没有写进去") }
                }
                val keys = target.map { it.key }.toSet()
                state.update {
                    it.copy(
                        busy = false,
                        stage = null,
                        bills = it.bills.filterNot { row -> row.key in keys },
                        failures = failures,
                        savedCount = it.savedCount + saved,
                    )
                }
                onSaved(saved)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(busy = false, stage = null, error = failure.message ?: "写入没有完成") }
            }
        }
    }

    fun sourceOf(key: Int): Uri? = sources[key]

    fun clear() {
        sources.clear()
        state.value = ScreenshotBatchUiState()
    }

    private data class Matched(
        val level1Id: String?,
        val level2Id: String?,
        val name: String?,
        val keyword: String?,
    )

    /** 商户名 / 订单号 / 首行文字命中文本关键词表，命不中就把分类留给用户选。 */
    private fun matchCategory(rawText: String, merchant: String?, orderNo: String?): Matched {
        val probe = listOfNotNull(merchant, orderNo, rawText.lineSequence().firstOrNull()).joinToString(" ")
        val parsed = QuickNoteParser.parse(probe, catalog, System.currentTimeMillis())
        val hit = parsed.category
        return Matched(hit?.level1Id, hit?.level2Id, hit?.name, parsed.matchedKeyword)
    }

    /** 置信度只看事实：金额已经拿到，再看分类与日期有没有落。 */
    private fun confidenceOf(hasCategory: Boolean, hasDate: Boolean): Float =
        0.5f + (if (hasCategory) 0.35f else 0f) + (if (hasDate) 0.15f else 0f)

    companion object {
        /** 与 附件上限一致；选到第 10 张由系统选择器就地拦下。 */
        const val MAX_IMAGES = 9
    }
}

/** 列表行副标题里的金额，与设计板同一套显示口径。 */
internal fun scannedAmount(bill: ScannedBill): String = formatAmount(bill.amountMinor, grouping = false)
