package io.github.guohangbk.tally.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.dao.TagUsageRow
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class BatchState(
    val selected: List<BillListItem> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val ledgerOptions: List<LedgerEntity> = emptyList(),
    val showCategoryPicker: Boolean = false,
    val showDatePicker: Boolean = false,
    val showNoteEditor: Boolean = false,
    val showLedgerPicker: Boolean = false,
    val showTagPicker: Boolean = false,
    /** 可选的标签与勾选状态。批量是「整批对齐」：勾上就挂到每一笔，取消就从每一笔解绑。 */
    val tagChoices: List<TagUsageRow> = emptyList(),
    val tagSelection: Set<String> = emptySet(),
    val tagBaseline: Set<String> = emptySet(),
    val tagError: String? = null,
    val busy: Boolean = false,
    val progress: String? = null,
    val notice: String? = null,
) {
    val active: Boolean get() = selected.isNotEmpty()
    val amountMinor: Long get() = selected.sumOf { it.bill.amountMinor }
    val excludedCount: Int get() = selected.count { it.bill.excludedFromStats }
    val incomeCount: Int get() = selected.count { it.bill.type == BillType.INCOME.name }
    private val typeNames: Set<String> get() = selected.map { it.bill.type }.toSet()

    /** 跨类型时「改分类」置灰并说明原因（F2.6）；分类为全局字典，与账本无关。 */
    val categoryBlockedReason: String?
        get() = when {
            typeNames.size > 1 -> "选中项混合收支，改分类要求同一类型"
            else -> null
        }

    /** 只有一个账本时「移到账本」没有去处。 */
    val canMove: Boolean get() = ledgerOptions.size > 1

    /**
     * 周期 / 分期生成的那些期：计划本身留在源账本，把期搬走等于从计划里少一期，
     * 下次补记会把它再生成一遍，钱就重了。所以迁移整批跳过它们。
     */
    val generatedCount: Int get() = selected.count { it.bill.recurrenceRuleId != null || it.bill.installmentPlanId != null }

    /** 改备注是整批覆盖，落笔前先说清有几笔会被盖掉。 */
    val notedCount: Int get() = selected.count { !it.bill.note.isNullOrBlank() }

    /** 日期弹层的锚点：选中项里最近的那一笔，而不是永远从今天开始。 */
    val dateAnchor: Long get() = selected.maxOfOrNull { it.bill.occurredAt } ?: System.currentTimeMillis()

    /** 选中项已经分布在哪些账本里：迁移弹层要在这些账本上标「已在」。 */
    val sourceLedgerIds: Set<String> get() = selected.map { it.bill.ledgerId }.toSet()
}

/**
* 批量操作。首页与搜索页共用，所以两条链上的文案、口径、结果完全一致（§5 一致性）。
 */
class BillBatchViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = mutable.asStateFlow()

    private var pickerJob: Job? = null
    private var ledgerJob: Job? = null

    fun begin(item: BillListItem) {
        if (mutable.value.selected.any { it.bill.id == item.bill.id }) return
        mutable.value = mutable.value.copy(selected = mutable.value.selected + item, notice = null)
        loadCategoriesForSelection()
        loadLedgerOptions()
    }

    fun toggle(item: BillListItem) {
        val current = mutable.value.selected
        val next = if (current.any { it.bill.id == item.bill.id }) {
            current.filterNot { it.bill.id == item.bill.id }
        } else {
            current + item
        }
        mutable.value = mutable.value.copy(selected = next)
        loadCategoriesForSelection()
        loadLedgerOptions()
    }

    fun selectAll(items: List<BillListItem>) {
        val merged = (mutable.value.selected + items).distinctBy { it.bill.id }
        mutable.value = mutable.value.copy(selected = merged, notice = null)
        loadCategoriesForSelection()
        loadLedgerOptions()
    }

    fun clear() {
        mutable.value = BatchState()
    }

    fun exitSelection() {
        mutable.value = BatchState(notice = mutable.value.notice)
    }

    fun openCategoryPicker() {
        if (mutable.value.categoryBlockedReason != null) return
        if (mutable.value.categories.isEmpty()) loadCategoriesForSelection()
        mutable.value = mutable.value.copy(showCategoryPicker = true)
    }

    fun closeCategoryPicker() {
        mutable.value = mutable.value.copy(showCategoryPicker = false)
    }

    fun openDatePicker() {
        mutable.value = mutable.value.copy(showDatePicker = true)
    }

    fun closeDatePicker() {
        mutable.value = mutable.value.copy(showDatePicker = false)
    }

    fun openNoteEditor() {
        mutable.value = mutable.value.copy(showNoteEditor = true)
    }

    fun closeNoteEditor() {
        mutable.value = mutable.value.copy(showNoteEditor = false)
    }

    fun openLedgerPicker() {
        if (mutable.value.ledgerOptions.isEmpty()) loadLedgerOptions()
        mutable.value = mutable.value.copy(showLedgerPicker = true)
    }

    fun closeLedgerPicker() {
        mutable.value = mutable.value.copy(showLedgerPicker = false)
    }

    /**
     * 批量补标的预选取「交集」：只有这一批账单都挂着的标签才默认勾上，
     * 取消勾选即从每一笔解绑；新勾的挂到每一笔。
     */
    fun openTagPicker() {
        val current = mutable.value
        if (current.selected.isEmpty()) return
        val ids = current.selected.map { it.bill.id }
        viewModelScope.launch {
            val active = graph.tagRepository.observeActive().first()
            val common = graph.tagRepository.commonTags(ids)
            // 归档标签不再出现在可选项里，但如果这一批都挂着它，也要能在这里摘掉。
            val archivedSelected = graph.tagRepository.observeAll().first()
                .filter { it.id in common && active.none { row -> row.id == it.id } }
                .map { TagUsageRow(it.id, it.name, it.sortOrder, it.archivedAt, 0, null) }
            mutable.value = mutable.value.copy(
                tagChoices = active + archivedSelected,
                tagSelection = common,
                tagBaseline = common,
                tagError = null,
                showTagPicker = true,
            )
        }
    }

    fun closeTagPicker() {
        mutable.value = mutable.value.copy(showTagPicker = false, tagError = null)
    }

    fun toggleTag(tagId: String) {
        val current = mutable.value
        val next = if (tagId in current.tagSelection) current.tagSelection - tagId else current.tagSelection + tagId
        mutable.value = current.copy(tagSelection = next, tagError = null)
    }

    /** 批量新建：建完直接勾上，随这次「打标签」一起挂到整批。 */
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
                val current = mutable.value
                mutable.value = current.copy(
                    tagChoices = (current.tagChoices + row).distinctBy { it.id },
                    tagSelection = current.tagSelection + row.id,
                    tagError = null,
                )
            } catch (error: IllegalArgumentException) {
                mutable.value = mutable.value.copy(tagError = error.message)
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(tagError = error.message ?: "标签没有建成")
            }
        }
    }

    /** 只写差量：新勾的挂上、取消勾的解绑；两样都没有就什么都不做。 */
    fun applyTags() {
        val current = mutable.value
        val add = current.tagSelection - current.tagBaseline
        val remove = current.tagBaseline - current.tagSelection
        mutable.value = current.copy(showTagPicker = false)
        if (add.isEmpty() && remove.isEmpty()) return
        runBatch(
            label = "打标签",
            suffix = buildString {
                if (add.isNotEmpty()) append("挂 ${add.size} 枚")
                if (remove.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("摘 ${remove.size} 枚")
                }
            },
        ) { billIds ->
            if (add.isNotEmpty()) graph.tagRepository.attachToBills(billIds, add.toList())
            if (remove.isNotEmpty()) graph.tagRepository.detachFromBills(billIds, remove.toList())
            billIds.size
        }
    }

    fun delete() = runBatch("删除") { ids -> graph.billRepository.softDeleteIds(ids) }

    fun reimburse(on: Boolean) {
        val expense = mutable.value.selected.filter { it.bill.type == BillType.EXPENSE.name }
        val skippedIncome = mutable.value.selected.size - expense.size
        if (expense.isEmpty()) {
            mutable.value = mutable.value.copy(notice = "收入不参与报销")
            return
        }
        runBatch(
            label = if (on) "标记报销" else "取消报销",
            items = expense,
            suffix = if (skippedIncome > 0) "跳过 $skippedIncome 笔收入" else null,
        ) { ids ->
            graph.billRepository.setReimbursedIds(ids, if (on) ReimburseStatus.PENDING else ReimburseStatus.NONE)
        }
    }

    fun exclude(on: Boolean) = runBatch(if (on) "标记不计入收支" else "取消不计入收支") { ids ->
        graph.billRepository.setExcludedIds(ids, on)
    }

    fun applyCategory(l1Id: String, l2Id: String?) {
        runBatch("改分类") { ids -> graph.billRepository.setCategoryIds(ids, l1Id, l2Id) }
        mutable.value = mutable.value.copy(showCategoryPicker = false)
    }

    /** 只换「哪一天」，每一笔自己的时刻保留；选中项跨月也能一次改齐。 */
    fun applyDate(dayStartAt: Long) {
        runBatch("改日期") { ids -> graph.billRepository.setDateIds(ids, dayStartAt) }
        mutable.value = mutable.value.copy(showDatePicker = false)
    }

    /** 整批覆盖同一句话；留空就是清掉备注。 */
    fun applyNote(text: String) {
        val blank = text.isBlank()
        runBatch(if (blank) "清空备注" else "改备注") { ids -> graph.billRepository.setNoteIds(ids, text) }
        mutable.value = mutable.value.copy(showNoteEditor = false)
    }

    /**
     * 跨账本迁移。分类全机共用一套字典，所以不需要重算映射；
     * 要避开的只有周期 / 分期的期次，以及本来就在目标账本里的那些笔。
     */
    fun applyLedger(targetId: String) {
        val current = mutable.value
        val name = current.ledgerOptions.firstOrNull { it.id == targetId }?.name
        val generated = current.generatedCount
        val movable = current.selected.filter {
            it.bill.recurrenceRuleId == null && it.bill.installmentPlanId == null && it.bill.ledgerId != targetId
        }
        val already = current.selected.size - generated - movable.size
        mutable.value = current.copy(showLedgerPicker = false)
        if (movable.isEmpty()) {
            val reason = when {
                generated > 0 && already > 0 -> "$generated 笔由周期 / 分期生成、$already 笔已经在那个账本，没有要搬的"
                generated > 0 -> "这 $generated 笔都由周期 / 分期生成，不随迁移走"
                else -> "这些账单已经都在那个账本里"
            }
            mutable.value = mutable.value.copy(notice = reason)
            return
        }
        runBatch(
            label = "移到「${name ?: "其他账本"}」",
            items = movable,
            suffix = if (current.generatedCount > 0) "跳过 ${current.generatedCount} 笔周期/分期" else null,
        ) { ids ->
            graph.billRepository.moveToLedgerIds(ids, targetId)
        }
    }

    fun dismissNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }

    private fun loadCategoriesForSelection() {
        val first = mutable.value.selected.firstOrNull() ?: return
        if (mutable.value.categoryBlockedReason != null) return
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            val type = runCatching { BillType.valueOf(first.bill.type) }.getOrDefault(BillType.EXPENSE)
            val categoryType = if (type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
            val categories = graph.billRepository.activeCategories(categoryType)
            mutable.value = mutable.value.copy(categories = categories)
        }
    }

    /** 归档账本不做迁移目标：搬进去等于从日常视图里消失。 */
    private fun loadLedgerOptions() {
        if (ledgerJob?.isActive == true || mutable.value.ledgerOptions.isNotEmpty()) return
        ledgerJob = viewModelScope.launch {
            val options = graph.ledgerRepository.active.first().filterNot { it.isArchived }
            mutable.value = mutable.value.copy(ledgerOptions = options)
        }
    }

    private fun runBatch(
        label: String,
        items: List<BillListItem> = mutable.value.selected,
        suffix: String? = null,
        block: suspend (List<String>) -> Int,
    ) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val ids = items.map { it.bill.id }
            mutable.value = mutable.value.copy(busy = true, notice = null)
            try {
                var done = 0
                if (ids.size > CHUNK_THRESHOLD) {
                    ids.chunked(CHUNK_SIZE).forEach { chunk ->
                        done += block(chunk)
                        mutable.value = mutable.value.copy(progress = "正在${label} $done/${ids.size}…")
                    }
                } else {
                    done = block(ids)
                }
                val amount = items.sumOf { it.bill.amountMinor }
                val text = "$label $done 笔 · ${formatAmount(amount)}" + (suffix?.let { " · $it" } ?: "")
                // 只报结果，不给「撤销」：找回走回收站，那里能整组批量恢复，
                // 也就不必再把气泡撑成一条长兜命窗口。
                mutable.value = BatchState(notice = text)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(busy = false, progress = null, notice = error.message ?: "批量操作没有完成")
            }
        }
    }

    companion object {
        private const val CHUNK_SIZE = 200
        private const val CHUNK_THRESHOLD = 500
    }
}
