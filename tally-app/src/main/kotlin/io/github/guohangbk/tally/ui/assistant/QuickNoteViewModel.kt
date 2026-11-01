package io.github.guohangbk.tally.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.speech.SpeechOutcome
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
import io.github.guohangbk.tally.domain.parser.ParsedQuickNote
import io.github.guohangbk.tally.domain.parser.QuickNoteCatalog
import io.github.guohangbk.tally.domain.parser.QuickNoteParser
import io.github.guohangbk.tally.ui.common.alignToPrecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P15 速记（语音 / 文本）。
 *
 * 只有端侧规则这一层：说完即出结果，不出设备、不等网络。
 * 任何路径都不自动落库，只有点「确认保存」才写账单。
 */
data class QuickNoteUiState(
    val loaded: Boolean = false,
    val ledgerId: String? = null,
    val ledgerName: String = "",
    val text: String = "",
    val heard: String? = null,
    val listening: Boolean = false,
    val speechHint: String? = null,
    val parsed: ParsedQuickNote? = null,
    val amountInput: String = "",
    val noteInput: String = "",
    val occurredAt: Long? = null,
    val timePrecision: TimePrecision = TimePrecision.DATE,
    val type: BillType = BillType.EXPENSE,
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val categoryLabel: String? = null,
    val expenseCategories: List<CategoryEntity> = emptyList(),
    val incomeCategories: List<CategoryEntity> = emptyList(),
    val showPicker: Boolean = false,
    val saving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val missingFields: Set<DraftField> get() = parsed?.missingFields ?: emptySet()
    val multipleBills: Boolean get() = parsed?.hasMultipleAmounts == true
    val canSave: Boolean get() = !saving && amountInput.isNotBlank() && categoryL1Id != null
    val pickerCategories: List<CategoryEntity> get() = if (type == BillType.INCOME) incomeCategories else expenseCategories
}

/** 录音上限：超过自动结束并进入识别，不静默截断（V-03）。 */
const val VoiceMaxMillis = 20_000L

/**
 * 一次「按住说」的产出。[failure] 非空表示没拿到可记账的内容，
 * 页面必须显式说清原因并给出路，不许静默回到首页（V-07 / AC-V-7）。
 */data class VoiceCapture(
    val heard: String,
    val amountMinor: Long? = null,
    val type: BillType = BillType.EXPENSE,
    val note: String? = null,
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val occurredAt: Long? = null,
    /** 命中分类的那个词；记账页据此判断「分类被改掉后要不要沉淀」。 */
    val matchedKeyword: String? = null,
    val multiple: Boolean = false,
    val cancelled: Boolean = false,
    val failure: String? = null,
    /** 本机引擎起不来，页面应改走系统语音输入界面。 */
    val needsSystemInput: Boolean = false,
)

class QuickNoteViewModel(private val graph: AppGraph) : ViewModel() {
    private val state = MutableStateFlow(QuickNoteUiState())
    val uiState: StateFlow<QuickNoteUiState> = state.asStateFlow()

    private var catalog: QuickNoteCatalog = QuickNoteCatalog(emptyList(), emptyList())
    private var capture: ((VoiceCapture) -> Unit)? = null

    init {
        // 目录跟着分类表与关键词表走：记账页刚沉淀的词，这一页再说一次就该认得，
        // 不用等杀进程重进。
        viewModelScope.launch(Dispatchers.IO) {
            graph.keywordRuleRepository.observeCatalog().collect { catalog = it }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ledger = graph.ledgerRepository.defaultLedger()
                if (ledger == null) {
                    state.update { it.copy(loaded = true, error = TallyError.NoDefaultLedger.message) }
                    return@launch
                }
                val ledgerId = ledger.id
                state.update {
                    it.copy(
                        loaded = true,
                        ledgerId = ledgerId,
                        ledgerName = ledger.name,
                        expenseCategories = graph.billRepository.activeCategories(CategoryType.EXPENSE),
                        incomeCategories = graph.billRepository.activeCategories(CategoryType.INCOME),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(loaded = true, error = failure.message ?: "分类读不出来") }
            }
        }
    }

    fun onTextChange(value: String) {
        state.update { it.copy(text = value, error = null) }
        reparse(value)
    }

    /**
     * 按住开始收音（微信式）：按下调这里，松手调 [stopListening]。
     * 识别结果仍然从这个协程回来，UI 只负责手势。
     */
    fun startListening() {
        if (state.value.listening) return
        state.update { it.copy(listening = true, speechHint = null, error = null) }
        viewModelScope.launch {
            try {
                when (val outcome = graph.speechPort.listen()) {
                    is SpeechOutcome.Heard -> {
                        state.update { it.copy(listening = false, heard = outcome.text) }
                        onTextChange(outcome.text)
                    }

                    is SpeechOutcome.NothingHeard -> state.update { it.copy(listening = false, speechHint = outcome.reason) }
                    is SpeechOutcome.Unavailable -> state.update { it.copy(listening = false, speechHint = outcome.reason) }
                    is SpeechOutcome.NeedsSystemInput -> state.update { it.copy(listening = false, speechHint = outcome.reason) }
                    SpeechOutcome.Cancelled -> state.update { it.copy(listening = false) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(listening = false, speechHint = "识别失败，可改用文字输入") }
            }
        }
    }

    /** 松手：立刻让引擎收尾，不等它自己判断静音。 */
    fun stopListening() {
        if (!state.value.listening) return
        graph.speechPort.stopListening()
    }

    /**
     * 首页 ＋ 上滑链路的一次收音。识别与规则抽取全部复用本页已有的那套管线，
     * 只是结果不再留在本页草稿卡上，而是交给记账页由人确认（V-04）。
     */
    fun beginCapture(onLevel: (Float) -> Unit, onResult: (VoiceCapture) -> Unit) {
        if (state.value.listening) return
        capture = onResult
        state.update { it.copy(listening = true, heard = null, parsed = null, speechHint = null, error = null) }
        viewModelScope.launch {
            val outcome = try {
                graph.speechPort.listen(onLevel)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                SpeechOutcome.NothingHeard("没有听清，请重试或改用文字输入")
            }
            state.update { it.copy(listening = false) }
            val callback = capture
            capture = null
            when (outcome) {
                is SpeechOutcome.Heard -> {
                    state.update { it.copy(heard = outcome.text) }
                    reparse(outcome.text)
                    callback?.invoke(captureOf(outcome.text))
                }

                is SpeechOutcome.NothingHeard -> callback?.invoke(VoiceCapture(heard = "", failure = outcome.reason))
                is SpeechOutcome.Unavailable -> callback?.invoke(VoiceCapture(heard = "", failure = outcome.reason))
                is SpeechOutcome.NeedsSystemInput ->
                    callback?.invoke(VoiceCapture(heard = "", failure = outcome.reason, needsSystemInput = true))
                SpeechOutcome.Cancelled -> callback?.invoke(VoiceCapture(heard = "", cancelled = true))
            }
        }
    }

    /** 松手结束收音：识别结果仍从 [beginCapture] 的回调里回来。 */
    fun endCapture() = stopListening()

    /** 手势被中途打断：丢掉这一次，不要事后弹结果。 */
    fun abandonCapture() {
        capture = null
        if (state.value.listening) graph.speechPort.stopListening()
        state.update { it.copy(listening = false) }
    }

    /** 用户拒绝了录音权限：说清楚还能打字，别让流程卡在一步上。 */
    fun onMicPermissionDenied() = state.update {
        it.copy(speechHint = "没有麦克风权限，语音不可用；直接打字同样能记账")
    }

    fun onMicPermissionGranted() = state.update {
        it.copy(speechHint = "麦克风已授权 · 按住按钮说话")
    }

    /**
     * 系统语音输入界面返回的文字：走同一条解析路径，结果与端侧识别完全一致，
     * 只是音频是在系统应用里录的（小米这类 ROM 上这是唯一能用的路）。
     */
    fun acceptHeard(text: String, onResult: (VoiceCapture) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onResult(VoiceCapture(heard = "", failure = "没有听清，请重试或输入文字"))
            return
        }
        state.update { it.copy(heard = trimmed) }
        reparse(trimmed)
        onResult(captureOf(trimmed))
    }

    /**
     * 一次收音的产出，字段全部来自端侧规则。
     * 备注取 `buildNote` 剥掉日期、金额与「花了 / 买了 / 大约」之后的残留词
     * （「早上吃饭花了二十九块八毛五」→ 备注「早上吃饭」），不再塞整句口语；
     * `heard` 原文只用于失败提示与「再听一次」。
     * 截到记账页备注框的上限为止，别让计数器一上手就超线。
     */
    private fun captureOf(heard: String): VoiceCapture {
        val current = state.value
        return VoiceCapture(
            heard = heard,
            amountMinor = parseAmount(current.amountInput),
            type = current.type,
            note = current.noteInput.ifBlank { null }?.take(BILL_NOTE_MAX),
            categoryL1Id = current.categoryL1Id,
            categoryL2Id = current.categoryL2Id,
            occurredAt = current.occurredAt,
            matchedKeyword = current.parsed?.matchedKeyword,
            multiple = current.parsed?.hasMultipleAmounts == true,
        )
    }

    private fun reparse(text: String) {
        if (text.isBlank()) {
            state.update {
                it.copy(parsed = null, amountInput = "", noteInput = "", occurredAt = null, categoryLabel = null)
            }
            return
        }
        val parsed = QuickNoteParser.parse(text, catalog, System.currentTimeMillis())
        state.update {
            it.copy(
                parsed = parsed,
                amountInput = parsed.amountMinor?.let { minor -> formatAmount(minor, grouping = false) } ?: "",
                noteInput = parsed.note.orEmpty(),
                occurredAt = parsed.occurredAt,
                timePrecision = parsed.timePrecision,
                type = parsed.type,
                categoryL1Id = parsed.category?.level1Id,
                categoryL2Id = parsed.category?.level2Id,
                categoryLabel = parsed.category?.name,
            )
        }
    }

    fun onAmountChange(value: String) = state.update { it.copy(amountInput = value) }

    fun onNoteChange(value: String) = state.update { it.copy(noteInput = value.take(NOTE_MAX)) }

    fun shiftDay(delta: Long) {
        val base = state.value.occurredAt ?: System.currentTimeMillis()
        state.update { it.copy(occurredAt = base + delta * DAY_MILLIS) }
    }

    fun useToday() = state.update { it.copy(occurredAt = System.currentTimeMillis()) }

    fun setType(value: BillType) {
        state.update {
            it.copy(
                type = value,
                // 收支一换，原来选的分类必然不再成立，直接清空而不是留着骗人。
                categoryL1Id = null,
                categoryL2Id = null,
                categoryLabel = null,
            )
        }
    }

    fun openPicker() = state.update { it.copy(showPicker = true) }

    fun closePicker() = state.update { it.copy(showPicker = false) }

    fun pickCategory(level1Id: String, level2Id: String?) {
        val pool = state.value.pickerCategories
        state.update {
            it.copy(
                categoryL1Id = level1Id,
                categoryL2Id = level2Id,
                categoryLabel = (pool.firstOrNull { c -> c.id == (level2Id ?: level1Id) })?.name,
                showPicker = false,
            )
        }
    }

    /** 确认保存才会落库；草稿阶段的一切改动都只在内存里。 */
    fun save(onSaved: () -> Unit) {
        val current = state.value
        val amount = parseAmount(current.amountInput)
        if (amount == null) {
            state.update { it.copy(error = TallyError.AmountOutOfRange.message) }
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
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                graph.billRepository.save(
                    BillDraft(
                        ledgerId = ledgerId,
                        categoryL1Id = categoryL1,
                        categoryL2Id = current.categoryL2Id,
                        type = current.type,
                        amountMinor = amount,
                        occurredAt = current.occurredAt ?: System.currentTimeMillis(),
                        timePrecision = current.timePrecision,
                        note = current.noteInput.ifBlank { null },
                        source = BillSource.VOICE,
                    ),
                )
                state.update { it.copy(saving = false, notice = "已记一笔") }
                // NavController 只认主线程，回调必须切回去。
                withContext(Dispatchers.Main.immediate) { onSaved() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(saving = false, error = failure.message ?: "这一步没有完成") }
            }
        }
    }

    fun dismissMessages() = state.update { it.copy(notice = null, error = null) }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val NOTE_MAX = 200

        /** 记账页备注框的字数上限（BillEntryScreen 就地限），语音填进去不能比它能收的更多。 */
        const val BILL_NOTE_MAX = 60
    }
}
