package io.github.guohangbk.tally.ui.assistant

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.attachment.AttachmentOutcome
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillDraft
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.parseAmount
import io.github.guohangbk.tally.domain.parser.QuickNoteCatalog
import io.github.guohangbk.tally.domain.parser.QuickNoteParser
import io.github.guohangbk.tally.domain.parser.ScreenshotFields
import io.github.guohangbk.tally.domain.parser.ScreenshotRules
import io.github.guohangbk.tally.data.ocr.OcrOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P16 截图记账。
 *
 * 图片只在设备内读：端侧 OCR + 规则抽取，草稿即可编辑并可保存，整条链路不出网络。
 * 「保存到账单」常驻且默认不勾选，不受设置里的「账单图片」开关影响。
 */
data class ScreenshotUiState(
    val loaded: Boolean = false,
    val ledgerId: String? = null,
    val source: Uri? = null,
    val busy: Boolean = false,
    val stage: String? = null,
    val ocrText: String? = null,
    val fields: ScreenshotFields? = null,
    val amountInput: String = "",
    val noteInput: String = "",
    val merchant: String = "",
    val occurredAt: Long? = null,
    val timePrecision: TimePrecision = TimePrecision.DATE,
    val type: BillType = BillType.EXPENSE,
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val categoryLabel: String? = null,
    val expenseCategories: List<CategoryEntity> = emptyList(),
    val incomeCategories: List<CategoryEntity> = emptyList(),
    val showPicker: Boolean = false,
    val keepImage: Boolean = false,
    val duplicateHint: String? = null,
    val saving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val imageLoaded: Boolean get() = source != null
    val hasDraft: Boolean get() = fields != null

    /** 待确认清单要跟着用户的修改走：补上了分类或金额，提示就该消失。 */
    val missingFields: Set<DraftField>
        get() = (fields?.missingFields ?: emptySet()).filterNot { pending ->
            (pending == DraftField.CATEGORY && categoryL1Id != null) ||
                (pending == DraftField.AMOUNT && amountInput.isNotBlank())
        }.toSet()

    val canSave: Boolean get() = !saving && hasDraft && amountInput.isNotBlank() && categoryL1Id != null
    val pickerCategories: List<CategoryEntity> get() = if (type == BillType.INCOME) incomeCategories else expenseCategories
}

class ScreenshotViewModel(private val graph: AppGraph) : ViewModel() {
    private val state = MutableStateFlow(ScreenshotUiState())
    val uiState: StateFlow<ScreenshotUiState> = state.asStateFlow()

    private var imageBytes: ByteArray? = null
    private var catalog: QuickNoteCatalog = QuickNoteCatalog(emptyList(), emptyList())

    init {
        // 目录跟着两张表走：刚在记账页点过「确定」沉淀的词，这一页下一次解析就该认得。
        viewModelScope.launch(Dispatchers.IO) {
            graph.keywordRuleRepository.observeCatalog().collect { catalog = it }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ledger = graph.ledgerRepository.defaultLedger()
                val ledgerId = ledger?.id
                if (ledgerId != null) {
                    state.update {
                        it.copy(
                            loaded = true,
                            ledgerId = ledgerId,
                            expenseCategories = graph.billRepository.activeCategories(CategoryType.EXPENSE),
                            incomeCategories = graph.billRepository.activeCategories(CategoryType.INCOME),
                        )
                    }
                } else {
                    state.update { it.copy(loaded = true, error = TallyError.NoDefaultLedger.message) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(loaded = true, error = failure.message ?: "分类读不出来") }
            }
        }
    }

    /** 相册、分享、剪贴板三个入口都汇到这里：先读图，再端侧 OCR，最后规则抽取。 */
    fun load(uri: Uri) {
        state.update { it.copy(busy = true, stage = "正在本机识别…", error = null, notice = null, source = uri) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = graph.attachmentStore.readSource(uri)
                if (bytes == null) {
                    state.update { it.copy(busy = false, stage = null, error = "这张图片读不出来，可以手动录入") }
                    return@launch
                }
                imageBytes = bytes
                when (val ocr = graph.screenshotTextReader.read(bytes)) {
                    is OcrOutcome.NoText -> state.update {
                        it.copy(
                            busy = false,
                            stage = null,
                            ocrText = "",
                            fields = emptyFields(),
                            amountInput = "",
                            noteInput = "",
                            error = "未识别到账单信息，请手动补充",
                        )
                    }

                    is OcrOutcome.Failed -> state.update {
                        it.copy(busy = false, stage = null, fields = emptyFields(), error = ocr.reason)
                    }

                    is OcrOutcome.Recognized -> {
                        val fields = ScreenshotRules.extract(ocr.text)
                        applyFields(fields.copy(rawText = ocr.text))
                        state.update { it.copy(ocrText = ocr.text, busy = false, stage = null) }
                        checkDuplicate(fields.orderNo)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(busy = false, stage = null, error = failure.message ?: "这一步没有完成") }
            }
        }
    }

    fun toggleKeepImage() = state.update { it.copy(keepImage = !it.keepImage) }

    /** 剪贴板里没有图片时要说清楚，不要让用户以为识别坏了。 */
    fun missingClipboardImage() = state.update { it.copy(notice = "剪贴板里没有图片，先复制一张截图或从相册选。") }

    fun onAmountChange(value: String) = state.update { it.copy(amountInput = value) }

    fun onNoteChange(value: String) = state.update { it.copy(noteInput = value.take(NOTE_MAX)) }

    fun onMerchantChange(value: String) = state.update { it.copy(merchant = value) }

    fun setType(value: BillType) {
        state.update {
            it.copy(type = value, categoryL1Id = null, categoryL2Id = null, categoryLabel = null)
        }
    }

    fun shiftDay(delta: Long) {
        val base = state.value.occurredAt ?: System.currentTimeMillis()
        state.update { it.copy(occurredAt = base + delta * DAY_MILLIS) }
    }

    fun openPicker() = state.update { it.copy(showPicker = true) }

    fun closePicker() = state.update { it.copy(showPicker = false) }

    fun pickCategory(level1Id: String, level2Id: String?) {
        val pool = state.value.pickerCategories
        state.update {
            it.copy(
                categoryL1Id = level1Id,
                categoryL2Id = level2Id,
                categoryLabel = pool.firstOrNull { c -> c.id == (level2Id ?: level1Id) }?.name,
                showPicker = false,
            )
        }
    }

    fun dismissMessages() = state.update { it.copy(notice = null, error = null) }

    fun save(onSaved: () -> Unit) {
        val current = state.value
        val amount = parseAmount(current.amountInput)
        if (amount == null) {
            state.update { it.copy(error = "请确认金额是否正确") }
            return
        }
        val ledgerId = current.ledgerId ?: run {
            state.update { it.copy(error = TallyError.NoDefaultLedger.message) }
            return
        }
        val categoryL1 = current.categoryL1Id ?: run {
            state.update { it.copy(error = "先选一个分类，再保存这一笔") }
            return
        }
        val bytes = imageBytes
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val billId = graph.billRepository.save(
                    BillDraft(
                        ledgerId = ledgerId,
                        categoryL1Id = categoryL1,
                        categoryL2Id = current.categoryL2Id,
                        type = current.type,
                        amountMinor = amount,
                        occurredAt = current.occurredAt ?: System.currentTimeMillis(),
                        timePrecision = current.timePrecision,
                        note = current.noteInput.ifBlank { current.merchant.ifBlank { null } },
                        source = BillSource.SCREENSHOT,
                        externalId = current.fields?.orderNo,
                    ),
                )
                val keepHint = if (current.keepImage && bytes != null) {
                    when (val outcome = graph.attachmentStore.attachToBill(billId, bytes)) {
                        is AttachmentOutcome.Saved ->
                            if (outcome.reused) "截图已存在，账单已记录" else "截图已保存到账单"

                        is AttachmentOutcome.NotSaved -> outcome.reason
                    }
                } else {
                    null
                }
                state.update { it.copy(saving = false, notice = keepHint ?: "已记一笔") }
                // NavController 只认主线程，回调必须切回去，否则导航直接抛错。
                withContext(Dispatchers.Main.immediate) { onSaved() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(saving = false, error = failure.message ?: "这一步没有完成") }
            }
        }
    }

    private suspend fun checkDuplicate(orderNo: String?) {
        if (orderNo.isNullOrBlank()) return
        val existing = graph.billRepository.findByExternalId(orderNo)
        if (existing != null) {
            state.update { it.copy(duplicateHint = "同一订单号已存在（${existing.categoryPath} · ${formatAmount(existing.bill.amountMinor)}），重复导入将提示而不是静默跳过") }
        }
    }

    private fun applyFields(fields: ScreenshotFields) {
        val matched = matchCategory(fields.rawText, fields)
        state.update {
            it.copy(
                fields = fields,
                amountInput = fields.amountMinor?.let { minor -> formatAmount(minor, grouping = false) } ?: "",
                noteInput = "",
                merchant = fields.merchant.orEmpty(),
                occurredAt = fields.occurredAt,
                timePrecision = fields.timePrecision,
                type = fields.type,
                categoryL1Id = matched.level1Id,
                categoryL2Id = matched.level2Id,
                categoryLabel = matched.label,
            )
        }
    }

    /** 截图里的商户/标题也可以命中文本关键词表，命不中就把分类留给用户选。 */
    private fun matchCategory(text: String, fields: ScreenshotFields): CategoryHit {
        val probe = listOfNotNull(fields.merchant, fields.orderNo, text.lineSequence().firstOrNull()).joinToString(" ")
        val parsed = QuickNoteParser.parse(probe, catalog, System.currentTimeMillis())
        val category = parsed.category
        return CategoryHit(
            level1Id = category?.level1Id,
            level2Id = category?.level2Id,
            label = category?.name,
            keyword = parsed.matchedKeyword,
        )
    }

    private fun emptyFields(): ScreenshotFields = ScreenshotFields(
        amountMinor = null,
        type = BillType.EXPENSE,
        occurredAt = null,
        timePrecision = TimePrecision.DATE,
        merchant = null,
        paymentMethod = null,
        orderNo = null,
        rawText = "",
        missingFields = setOf(DraftField.AMOUNT, DraftField.DATE, DraftField.CATEGORY),
    )

    private data class CategoryHit(
        val level1Id: String?,
        val level2Id: String?,
        val label: String?,
        val keyword: String?,
    )

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val NOTE_MAX = 200
    }
}
