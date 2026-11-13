package io.github.guohangbk.tally.ui.bill

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.attachment.AttachmentOutcome
import io.github.guohangbk.tally.data.db.dao.TagUsageRow
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillDraft
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.domain.model.RefundEntry
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.parseAmount
import io.github.guohangbk.tally.domain.parseAmountExpression
import io.github.guohangbk.tally.ui.common.alignToPrecision
import io.github.guohangbk.tally.ui.common.startOfDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BillEntryUiState(
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val type: BillType = BillType.EXPENSE,
    val amountText: String = "",
    val ledgerId: String? = null,
    val ledgers: List<LedgerListItem> = emptyList(),
    /**
     * 账本名不当 getter 而是字段：[ledgers] 要等一次整表聚合才到位，
     * 首帧按 id 去查就会先闪一帧「选择账本」。构造时先用启动缓存播种，聚合回来再覆盖。
     */
    val ledgerName: String = "选择账本",
    val categories: List<CategoryEntity> = emptyList(),
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    /** 录入粒度：设置页开「时刻」后保存写 DATETIME，并出现时分滚轮。 */
    val timePrecision: TimePrecision = TimePrecision.DATE,
    val note: String = "",
    val reimbursed: ReimburseStatus = ReimburseStatus.NONE,
    val excludedFromStats: Boolean = false,
    val isRefund: Boolean = false,
    /** 退款关联的原单；为空的退款行是旧数据，界面上写「未关联原单」。 */
    val refundOfBillId: String? = null,
    /** 本次待写入的图片；保存成功后才落到附件，取消离开就丢。 */
    val imageUri: Uri? = null,
    /** 编辑态下这笔账单已有的附件数，用来让「图片」chip 如实显示数量。 */
    val attachedCount: Int = 0,
    /** 机器填的内容非空时，页面顶部挂一条来源条；撤销即清掉金额/分类/备注。 */
    val sourceText: String? = null,
    val sourceKind: String? = null,
    val error: String? = null,
    val savedId: String? = null,
    val futureConfirm: Boolean = false,
    val futureConfirmRepeat: Boolean = false,
    val billId: String? = null,
    /** 「本次已记 N 笔」：进入页面归零，只在停留期间累加（F1、AC-F1-2）。 */
    val sessionCount: Int = 0,
    val repeatNotice: String? = null,
    /** LN-01：机器预填的分类被改掉并保存后，问一句要不要把这句话沉淀成规则。 */
    val sedimentOffer: SedimentOffer? = null,
    /** 编辑态有没有真正动过内容；「放弃」只在动过时拦一道。 */
    val dirty: Boolean = false,
    /** 这一笔挂着的标签：保存时整批写进 bill_tag，空列表就是一枚都不挂。 */
    val selectedTags: List<TagUsageRow> = emptyList(),
    /** 可供选择的标签（使用中，按手动顺序）。归档标签只留在已选的里，不能再选。 */
    val tagChoices: List<TagUsageRow> = emptyList(),
    /** 新建标签撞名之类的就地提示，显示在弹层里。 */
    val tagError: String? = null,
) {
    val categoryL1: CategoryEntity? get() = categories.firstOrNull { it.id == categoryL1Id && it.parentId == null }
    val categoryChildren: List<CategoryEntity> get() = categories.filter { it.parentId == categoryL1Id }
    val amountMinor: Long? get() = parseAmountExpression(amountText)
        ?: if (expressionPending) expressionResult else null

    /** 式子里出现加减时，金额行要多给一行「＝结果」；单个数字时不重复显示。 */
    val hasOperator: Boolean get() = amountText.contains('+') || amountText.contains('−')

    /** 大字要显示的待保存金额：右操作数还没输完时，先按已完成的左式结算。 */
    val expressionResult: Long? get() = parseAmountExpression(amountText)
        ?: parseAmountExpression(amountText.dropLastWhile { it == '+' || it == '−' })

    /** 式子停在运算符上，还没开始输右操作数。 */
    val expressionPending: Boolean get() = amountText.lastOrNull() == '+' || amountText.lastOrNull() == '−'

    /** 算式里最后按下的运算符，用来把对应功能键点亮。 */
    val pendingOperator: String? get() = if (hasOperator) {
        amountText.lastOrNull { it == '+' || it == '−' }?.toString()
    } else {
        null
    }

    /** 金额为空或 0 时两个保存键同时进入禁用态，而不是点击后报错（AC-F1-4）。 */
    val canSubmit: Boolean get() = (amountMinor ?: 0L) > 0L && categoryL1Id != null && ledgerId != null

    /** 编辑态没动过内容时保存置灰；放弃永远可点。 */
    val canSave: Boolean get() = canSubmit && (!editing || dirty)

    val isIncome: Boolean get() = type == BillType.INCOME

    /** 「图片」chip 上的数量：已落库的附件 + 这一笔还没保存的新图。 */
    val imageCount: Int get() = attachedCount + if (imageUri != null) 1 else 0
}

/** 沉淀只记「哪个词 → 哪个分类」，不带金额与日期：规则是全局词表，不是这一笔的副本。 */
data class SedimentOffer(val keyword: String, val categoryId: String)

class BillEntryViewModel(
    private val graph: AppGraph,
    private val billId: String? = null,
    private val prefillL1Id: String? = null,
    private val prefillL2Id: String? = null,
    prefillType: BillType? = null,
    prefillAmountMinor: Long? = null,
    prefillNote: String? = null,
    prefillSource: String? = null,
    prefillSourceKind: String? = null,
    prefillOccurredAt: Long? = null,
    /** 语音 / 截图命中分类的那个词；分类被改掉时用它问一句沉淀。 */
    private val prefillKeyword: String? = null,
) : ViewModel() {
    /** 同一句话只问一次沉淀，即便在「再记」里连着存了几笔。 */
    private var sedimentAsked = false

    private val local = MutableStateFlow(
        BillEntryUiState(
            editing = billId != null,
            billId = billId,
            type = prefillType ?: BillType.EXPENSE,
            // 启动时缓存好的默认账本：不等查询就有账本 id 与名字，首帧不闪「选择账本」。
            ledgerId = graph.cachedDefaultLedgerId,
            ledgerName = graph.cachedDefaultLedgerName ?: "选择账本",
            categoryL1Id = prefillL1Id,
            categoryL2Id = prefillL2Id,
            amountText = prefillAmountMinor?.let(::formatEntryAmount).orEmpty(),
            note = prefillNote.orEmpty(),
            sourceText = prefillSource,
            sourceKind = prefillSourceKind,
            // 机器认出的落账日要跟着进来：卡上写「昨天」，这一页不能把它写回今天。
            occurredAt = prefillOccurredAt ?: System.currentTimeMillis(),
        ),
    )
    init {
        // 分类树启动时已经预热过：首帧直接播种，不必等一次 IO 查询回来再把几十枚磁贴整片重画。
        // 下面的 init 仍会复核一次，拿到的是同一批数据，不会再闪一下。
        if (graph.cachedCategories.isNotEmpty()) {
            val seedType = if (local.value.type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
            local.value = local.value.copy(
                categories = graph.cachedCategories[seedType].orEmpty(),
                loaded = true,
            )
        }
        // 可选标签跟着词库走：别处新建/归档/改名后，这一页再打开弹层就是新的一份。
        viewModelScope.launch(Dispatchers.IO) {
            graph.tagRepository.observeActive().collect { rows ->
                local.value = local.value.copy(tagChoices = rows)
            }
        }
    }

    val settings: StateFlow<TallySettings> = graph.preferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TallySettings.DEFAULT)

    val state: StateFlow<BillEntryUiState> = combine(
        local,
        graph.ledgerRepository.items.flowOn(Dispatchers.IO),
    ) { current, ledgers ->
        val active = ledgers.filter { !it.ledger.isArchived }
        current.copy(
            ledgers = active,
            ledgerName = active.firstOrNull { it.ledger.id == current.ledgerId }?.ledger?.name ?: current.ledgerName,
        )
        // 初始值用 local.value 而不是一个空态：空态会让第一帧画出一张没分类、
        // 没金额、没备注的空白页，然后整页重画——那就是"进入记账页闪一下"。
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        // 整段放 IO：这里几份查询的结果一次性落成一次状态更新，
        // 不在转场动画中间连发多次整页重组。bootstrap 应用启动时已经跑过，不再查一遍。
        viewModelScope.launch(Dispatchers.IO) {
            val defaultId = graph.billRepository.defaultLedgerId()
            // 录入粒度在进页时取一次就够：这一页活着的时候设置页不会同时被改。
            val precision = graph.preferences.settings.first().timePrecision
            val ledgerId = local.value.ledgerId ?: defaultId
            if (billId != null) {
                local.value = local.value.copy(ledgerId = ledgerId, timePrecision = precision, loaded = true)
                loadExisting(billId)
            } else {
                val current = local.value.copy(ledgerId = ledgerId, timePrecision = precision)
                val categoryType = if (current.type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
                val categories = graph.billRepository.activeCategories(categoryType)
                // 「再记一笔」带来的预填优先；只有空白进入才回忆上次用的分类（AC-F1-1）。
                val preferred = if (current.categoryL1Id == null) {
                    graph.billRepository.preferredCategory(current.type)
                } else {
                    current.categoryL1Id to current.categoryL2Id
                }
                local.value = current.copy(
                    categories = categories,
                    categoryL1Id = preferred.first,
                    categoryL2Id = preferred.second,
                    loaded = true,
                )
            }
        }
    }

    fun setType(type: BillType) {
        if (type == BillType.TRANSFER) return
        if (type == local.value.type) return
        viewModelScope.launch {
            // 切收支不把上一笔的算式带过去：式子结清，金额只留结算结果。
            val keptAmount = if (local.value.hasOperator) {
                local.value.expressionResult?.takeIf { it > 0 }?.let(::formatEntryAmount).orEmpty()
            } else {
                local.value.amountText
            }
            // 收入不参与报销，也不可能是退款：切过去时顺手把两个标记摘掉，
            // 别留一笔挂不进报销页的「待报销收入」或没有原单的退款。
            local.value = local.value.copy(
                type = type,
                amountText = keptAmount,
                categoryL1Id = null,
                categoryL2Id = null,
                reimbursed = if (type == BillType.INCOME) ReimburseStatus.NONE else local.value.reimbursed,
                isRefund = if (type == BillType.INCOME) false else local.value.isRefund,
                refundOfBillId = if (type == BillType.INCOME) null else local.value.refundOfBillId,
                error = null,
                dirty = true,
            )
            loadCategories(type)
            val (l1, l2) = graph.billRepository.preferredCategory(type)
            local.value = local.value.copy(categoryL1Id = l1, categoryL2Id = l2)
        }
    }

    /** 键盘可以连算：`15+20-3.5`。运算符按一次改一个，不会连着摞两个。 */
    fun pressKey(key: String) {
        val current = local.value.amountText
        val next = when (key) {
            "⌫" -> current.dropLast(1)
            "+", "−" -> appendOperator(current, key)
            else -> appendDigit(current, key)
        }
        local.value = local.value.copy(amountText = next, error = null, dirty = true)
    }

    /** 「清零」只清金额与算式，不动分类、备注和标记。 */
    fun clearAmount() {
        local.value = local.value.copy(amountText = "", error = null, dirty = true)
    }

    fun selectCategory(l1Id: String, l2Id: String? = null) {
        local.value = local.value.copy(categoryL1Id = l1Id, categoryL2Id = l2Id, error = null, dirty = true)
    }

    /** 账本只决定账单归处，分类是全机一份，换账本不动已选分类。 */
    fun setLedger(id: String) {
        local.value = local.value.copy(ledgerId = id, dirty = true)
    }

    fun setDate(epochMillis: Long) {
        local.value = local.value.copy(occurredAt = epochMillis, error = null, dirty = true)
    }

    fun setNote(value: String) {
        local.value = local.value.copy(note = value, dirty = true)
    }

    /** 报销只是标记，不产生回款；收入不参与报销（F5.1）。 */
    fun setReimbursed(on: Boolean) {
        val status = if (on) ReimburseStatus.PENDING else ReimburseStatus.NONE
        local.value = local.value.copy(reimbursed = status, dirty = true)
    }

    fun setExcluded(value: Boolean) {
        local.value = local.value.copy(excludedFromStats = value, dirty = true)
    }

    /** 弹层里点一下即改草稿；保存时才落 bill_tag。 */
    fun toggleTag(tagId: String) {
        val current = local.value
        val existing = current.selectedTags.firstOrNull { it.id == tagId }
        val next = if (existing != null) {
            current.selectedTags - existing
        } else {
            val choice = current.tagChoices.firstOrNull { it.id == tagId } ?: return
            current.selectedTags + choice
        }
        local.value = current.copy(selectedTags = next, tagError = null, dirty = true)
    }

    /** 名称即全部，可带 emoji；撞名就地报，不静默复用已有标签。 */
    fun createTag(rawName: String) {
        viewModelScope.launch {
            try {
                val tag = graph.tagRepository.create(rawName)
                val row = TagUsageRow(
                    id = tag.id,
                    name = tag.name,
                    sortOrder = tag.sortOrder,
                    archivedAt = tag.archivedAt,
                    billCount = 0,
                    lastUsedAt = null,
                )
                local.value = local.value.copy(
                    selectedTags = local.value.selectedTags + row,
                    tagChoices = (local.value.tagChoices + row).distinctBy { it.id },
                    tagError = null,
                    dirty = true,
                )
            } catch (error: IllegalArgumentException) {
                local.value = local.value.copy(tagError = error.message)
            } catch (error: Exception) {
                local.value = local.value.copy(tagError = error.message ?: "标签没有建成")
            }
        }
    }

    fun clearTagError() {
        local.value = local.value.copy(tagError = null)
    }

    /** 撤销识别填充：只清机器填的三项，页面不关，用户接着手填（AC-V-5）。 */
    fun undoSource() {
        local.value = local.value.copy(
            sourceText = null,
            sourceKind = null,
            amountText = "",
            note = "",
            categoryL1Id = null,
            categoryL2Id = null,
            dirty = true,
        )
    }

    /** 选图只记 uri，写盘发生在保存成功之后（图存不住不影响账单本身）。 */
    fun setImage(uri: Uri?) {
        local.value = local.value.copy(imageUri = uri, dirty = true)
    }

    fun requestSave() {
        if (!validate()) return
        if (isFuture()) {
            local.value = local.value.copy(futureConfirm = true, futureConfirmRepeat = false)
            return
        }
        saveNow(repeat = false)
    }

    fun requestRepeat() {
        if (!validate()) return
        if (isFuture()) {
            local.value = local.value.copy(futureConfirm = true, futureConfirmRepeat = true)
            return
        }
        saveNow(repeat = true)
    }

    fun confirmFutureSave() {
        val repeat = local.value.futureConfirmRepeat
        local.value = local.value.copy(futureConfirm = false, futureConfirmRepeat = false)
        saveNow(repeat = repeat)
    }

    fun dismissFutureConfirm() {
        local.value = local.value.copy(futureConfirm = false, futureConfirmRepeat = false)
    }

    fun clearError() {
        local.value = local.value.copy(error = null)
    }

    fun clearNotice() {
        local.value = local.value.copy(repeatNotice = null)
    }

    fun dismissSediment() {
        local.value = local.value.copy(sedimentOffer = null)
    }

    /**
     * 只有「机器认过分类、人又改了」才值得问。没人改就说明规则已经对，
     * 再让点一次只会把规则页塞满没有信息量的词。
     */
    private fun offerSediment(current: BillEntryUiState): SedimentOffer? {
        val keyword = prefillKeyword?.takeIf { it.isNotBlank() } ?: return null
        if (sedimentAsked) return null
        val changed = current.categoryL1Id != prefillL1Id || current.categoryL2Id != prefillL2Id
        if (!changed) return null
        val categoryId = current.categoryL2Id ?: current.categoryL1Id ?: return null
        sedimentAsked = true
        return SedimentOffer(keyword = keyword, categoryId = categoryId)
    }

    private fun isFuture(): Boolean = local.value.occurredAt > System.currentTimeMillis() + 60_000

    private fun validate(): Boolean {
        val current = local.value
        if (current.amountMinor == null) {
            local.value = current.copy(error = "请输入有效金额")
            return false
        }
        if (current.categoryL1Id == null) {
            local.value = current.copy(error = "请选择分类")
            return false
        }
        return true
    }

    private fun saveNow(repeat: Boolean) {
        val current = local.value
        viewModelScope.launch {
            try {
                val id = graph.billRepository.save(
                    BillDraft(
                        id = current.billId,
                        ledgerId = current.ledgerId,
                        categoryL1Id = current.categoryL1Id,
                        categoryL2Id = current.categoryL2Id,
                        type = current.type,
                        amountMinor = current.amountMinor ?: 0L,
                        occurredAt = alignToPrecision(current.occurredAt, current.timePrecision),
                        timePrecision = current.timePrecision,
                        note = current.note,
                        reimbursed = current.reimbursed,
                        isRefund = current.isRefund,
                        refundOfBillId = current.refundOfBillId,
                        excludedFromStats = current.excludedFromStats,
                        tagIds = current.selectedTags.map { it.id },
                    ),
                )
                val categoryPath = (current.categoryL1?.name ?: "") +
                    current.categoryChildren.firstOrNull { it.id == current.categoryL2Id }?.name?.let { " / $it" }.orEmpty()
                val amount = formatAmount(current.amountMinor ?: 0L)
                val imageFailure = current.imageUri?.let { attachImage(id, it) }
                val sediment = offerSediment(current)
                if (repeat) {
                    // 再记：金额 / 备注 / 日期 / 图片复位，分类 / 类型 / 两个标记 / 标签保留，页面停留（F1.5）。
                    // 退款关联不保留：原单已经退过一笔，再来一笔应当是用户重新选的。
                    graph.billRepository.rememberCategory(current.categoryL1Id, current.categoryL2Id)
                    local.value = local.value.copy(
                        billId = null,
                        editing = false,
                        amountText = "",
                        note = "",
                        occurredAt = System.currentTimeMillis(),
                        imageUri = null,
                        attachedCount = 0,
                        isRefund = false,
                        refundOfBillId = null,
                        sourceText = null,
                        sourceKind = null,
                        savedId = null,
                        error = imageFailure,
                        sessionCount = local.value.sessionCount + 1,
                        repeatNotice = "已存 ¥$amount · $categoryPath",
                        sedimentOffer = sediment,
                        dirty = false,
                    )
                } else {
                    graph.billRepository.rememberCategory(current.categoryL1Id, current.categoryL2Id)
                    if (imageFailure == null) {
                        local.value = local.value.copy(savedId = id, error = null, sedimentOffer = sediment)
                    } else {
                        // 账单已经落库，只是图没留住：把 id 认下来，重试保存只会补图，不会再记一笔。
                        local.value = local.value.copy(
                            billId = id,
                            editing = true,
                            imageUri = null,
                            error = imageFailure,
                            sedimentOffer = sediment,
                        )
                    }
                }
            } catch (error: TallyError) {
                local.value = local.value.copy(error = error.message)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                local.value = local.value.copy(error = error.message ?: "保存失败，请重试")
            }
        }
    }

    /** 返回 null 表示图片写成功了；否则带回一句能直接显示的原因。 */
    private suspend fun attachImage(billId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = graph.attachmentStore.readSource(uri)
            ?: return@withContext "账单已记下，图片读不出来"
        when (val outcome = graph.attachmentStore.attachToBill(billId, bytes)) {
            is AttachmentOutcome.Saved -> null
            is AttachmentOutcome.NotSaved -> outcome.reason
        }
    }

    private suspend fun loadCategories(type: BillType) {
        val categoryType = if (type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        val categories = graph.billRepository.activeCategories(categoryType)
        local.value = local.value.copy(categories = categories)
    }

    private suspend fun loadExisting(id: String) {
        val item = graph.billRepository.find(id) ?: return
        val bill = item.bill
        val type = runCatching { BillType.valueOf(bill.type) }.getOrDefault(BillType.EXPENSE)
        val categories = graph.billRepository.activeCategories(
            if (type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
        )
        local.value = local.value.copy(
            editing = true,
            billId = bill.id,
            type = type,
            amountText = formatInputAmount(bill.amountMinor),
            ledgerId = bill.ledgerId,
            categories = categories,
            categoryL1Id = bill.categoryL1Id,
            categoryL2Id = bill.categoryL2Id,
            occurredAt = bill.occurredAt,
            // 编辑一笔历史 DATETIME 账单时，精度跟着这一行走，不因为设置改回「日」就被降级。
            timePrecision = runCatching { TimePrecision.valueOf(bill.timePrecision) }.getOrDefault(local.value.timePrecision),
            note = bill.note.orEmpty(),
            reimbursed = runCatching { ReimburseStatus.valueOf(bill.reimbursed) }.getOrDefault(ReimburseStatus.NONE),
            excludedFromStats = bill.excludedFromStats,
            isRefund = bill.isRefund,
            refundOfBillId = bill.refundOfBillId,
            attachedCount = withContext(Dispatchers.IO) { graph.attachmentStore.countForBill(bill.id) },
            // 这一笔挂了哪些标签要带进来：编辑页取消一枚就是解绑，不能看起来是空着。
            selectedTags = withContext(Dispatchers.IO) {
                graph.tagRepository.tagsForBill(bill.id).map {
                    TagUsageRow(
                        id = it.id,
                        name = it.name,
                        sortOrder = it.sortOrder,
                        archivedAt = it.archivedAt,
                        billCount = 0,
                        lastUsedAt = null,
                    )
                }
            },
            dirty = false,
        )
    }

    private fun formatInputAmount(minor: Long): String {
        val cents = minor % 100
        return "${minor / 100}.${cents.toString().padStart(2, '0')}"
    }
}

/** 分 → 记账页的输入串：语音与截图带进来的金额走这里，避免各处各写一份。 */
internal fun formatEntryAmount(minor: Long): String {
    val cents = minor % 100
    return "${minor / 100}.${cents.toString().padStart(2, '0')}"
}

/** 单个数字项最长 99999999.99（11 个字符），整条式子最长 24 个字符。 */
private const val MAX_TERM_LENGTH = 11
private const val MAX_EXPRESSION_LENGTH = 24

private fun currentTerm(raw: String): String = raw.substringAfterLast('+').substringAfterLast('−')

private fun appendOperator(raw: String, key: String): String = when {
    raw.isEmpty() -> raw
    raw.last() == '+' || raw.last() == '−' -> raw.dropLast(1) + key
    parseAmount(currentTerm(raw)) == null -> raw
    raw.length >= MAX_EXPRESSION_LENGTH -> raw
    else -> {
        // 连按时先把上一段结算掉：18+12 再按 + 得 30+，而不是 18+12+。
        val settled = if (raw.contains('+') || raw.contains('−')) {
            parseAmountExpression(raw)?.takeIf { it > 0 }
        } else {
            null
        }
        if (settled != null) formatEntryAmount(settled) + key else raw + key
    }
}

private fun appendDigit(raw: String, key: String): String {
    if (raw.length >= MAX_EXPRESSION_LENGTH) return raw
    val term = currentTerm(raw)
    val grown = when {
        key == "." -> when {
            term.contains('.') || term.length >= MAX_TERM_LENGTH -> term
            term.isEmpty() -> "0."
            else -> "$term."
        }
        term.length >= MAX_TERM_LENGTH -> term
        else -> term + key
    }
    return if (grown == term) raw else raw.dropLast(term.length) + grown
}

data class BillDetailUiState(
    val loaded: Boolean = false,
    val item: io.github.guohangbk.tally.domain.model.BillListItem? = null,
    val imagePaths: List<String> = emptyList(),
    /** 原单被退过几笔：弹层里要写「已退 x / y」并列出明细。 */
    val refunds: List<RefundEntry> = emptyList(),
    /** 退款行指向的原单，用来写「退自 …」。 */
    val origin: io.github.guohangbk.tally.domain.model.BillListItem? = null,
    val error: String? = null,
    /** 退款弹层：金额 + 退款日期。金额默认填剩余可退，日期默认今天。 */
    val refundSheet: Boolean = false,
    val refundAmountText: String = "",
    val refundAt: Long = 0L,
    val refundError: String? = null,
    val refundNotice: String? = null,
) {
    val refundedMinor: Long get() = refunds.sumOf { it.amountMinor }
    /** 原单剩余可退：已退满时退款按钮不出现，免得弹层里只能撞校验。 */
    val refundableMinor: Long get() = (item?.bill?.amountMinor ?: 0L) - refundedMinor
}

/** 弹层只读 + 删除/撤销；改内容一律走记账页，不在弹层里再长出一套编辑器。 */
class BillDetailViewModel(
    private val graph: AppGraph,
    private val billId: String,
) : ViewModel() {
    private val mutable = MutableStateFlow(BillDetailUiState())
    val state: StateFlow<BillDetailUiState> = mutable

    /**
     * 删除只发事件，不落状态。弹层的 ViewModel 按 billId 缓存在 Activity 上，
     * 状态里的「已删除」会一直留到下一次打开：回收站恢复后再点这笔，第一帧读到的
     * 还是上一次删它时的 true，弹层还没等到新数据就先把自己关掉了。
     * replay = 0 是关键——新收集者收不到上一次打开发出的那次。
     */
    private val deletedOutcome = MutableSharedFlow<Unit>(replay = 0)
    val deletedEvents: SharedFlow<Unit> = deletedOutcome

    /**
     * 弹层的 ViewModel 按 billId 缓存，只在 init 取一次的话，改完再打开看到的还是旧快照；
     * 所以由弹层每次出现时调一次 [load]。
     */
    fun load() {
        viewModelScope.launch {
            mutable.value = try {
                val item = graph.billRepository.find(billId)
                val paths = if (item != null && item.attachmentCount > 0) {
                    graph.attachmentStore.pathsForBill(billId)
                } else {
                    emptyList()
                }
                val bill = item?.bill
                val isRefund = bill != null && (bill.isRefund || bill.refundOfBillId != null)
                val refunds = if (bill != null && !isRefund && bill.type == BillType.EXPENSE.name) {
                    graph.billRepository.refundsOf(billId)
                } else {
                    emptyList()
                }
                val origin = bill?.refundOfBillId?.let { graph.billRepository.find(it) }
                // copy 而不是整份重建：退款弹层正开着时刷新（提交完那一次），
                // 弹层的输入不能被这次读取冲掉。
                mutable.value.copy(
                    loaded = true,
                    item = item,
                    imagePaths = paths,
                    refunds = refunds,
                    origin = origin,
                    error = null,
                )
            } catch (error: Exception) {
                mutable.value.copy(loaded = true, error = error.message)
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                graph.billRepository.delete(billId)
                deletedOutcome.emit(Unit)
            } catch (error: TallyError) {
                mutable.value = mutable.value.copy(error = error.message)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(error = error.message ?: "删除失败")
            }
        }
    }

    fun clearError() {
        mutable.value = mutable.value.copy(error = null)
    }

    /** 退款只从原单发起：打开时把剩余可退金额填进输入框，日期默认今天。 */
    fun openRefund() {
        val current = mutable.value
        if (current.item == null || current.refundableMinor <= 0L) return
        mutable.value = current.copy(
            refundSheet = true,
            refundAmountText = formatEntryAmount(current.refundableMinor),
            refundAt = startOfDay(System.currentTimeMillis()),
            refundError = null,
            refundNotice = null,
        )
    }

    fun closeRefund() {
        mutable.value = mutable.value.copy(refundSheet = false, refundError = null)
    }

    fun setRefundAmount(text: String) {
        mutable.value = mutable.value.copy(refundAmountText = text, refundError = null)
    }

    fun setRefundDate(millis: Long) {
        mutable.value = mutable.value.copy(refundAt = millis)
    }

    fun clearRefundNotice() {
        mutable.value = mutable.value.copy(refundNotice = null)
    }

    /**
     * 提交一笔退款：分类跟原单走，类型固定支出。不超过剩余可退这一条本地先拦，
     * 仓库的事务校验是最后一道——连点两下也不会超退。
     */
    fun confirmRefund() {
        val current = mutable.value
        val item = current.item ?: return
        val bill = item.bill
        val amount = parseAmount(current.refundAmountText)
        if (amount == null || amount <= 0L) {
            mutable.value = current.copy(refundError = "请输入有效金额")
            return
        }
        val remaining = current.refundableMinor
        if (amount > remaining) {
            mutable.value = current.copy(refundError = "最多还能退 ¥" + formatAmount(remaining))
            return
        }
        viewModelScope.launch {
            try {
                graph.billRepository.save(
                    BillDraft(
                        ledgerId = bill.ledgerId,
                        categoryL1Id = bill.categoryL1Id,
                        categoryL2Id = bill.categoryL2Id,
                        type = BillType.EXPENSE,
                        amountMinor = amount,
                        occurredAt = alignToPrecision(current.refundAt, TimePrecision.DATE),
                        timePrecision = TimePrecision.DATE,
                        isRefund = true,
                        refundOfBillId = bill.id,
                    ),
                )
                mutable.value = current.copy(
                    refundSheet = false,
                    refundNotice = "已记退款 ¥" + formatAmount(amount),
                )
                load()            } catch (error: TallyError) {
                mutable.value = mutable.value.copy(refundError = error.message)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(refundError = error.message ?: "退款失败，请重试")
            }
        }
    }
}
