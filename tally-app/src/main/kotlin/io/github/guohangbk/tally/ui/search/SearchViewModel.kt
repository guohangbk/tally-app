package io.github.guohangbk.tally.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.BillFilter
import io.github.guohangbk.tally.domain.model.BillSearchResult
import io.github.guohangbk.tally.domain.model.BillSort
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.parseAmount
import io.github.guohangbk.tally.ui.common.monthStartOf
import io.github.guohangbk.tally.ui.common.shiftMonthStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 筛选里的标签候选：使用中的全列；归档的只在「正被选中」时补进来，带着它进来也看得见、点得掉。 */
data class TagFilterChoice(val id: String, val name: String, val archived: Boolean)

data class SearchUiState(
    val filter: BillFilter = BillFilter(),
    val result: BillSearchResult = BillSearchResult(io.github.guohangbk.tally.domain.model.BillSummary(), emptyList()),
    val ledgers: List<LedgerEntity> = emptyList(),
    /** 筛选可选的标签；归档且正被选中的排在最前，标「已归档」。 */
    val tags: List<TagFilterChoice> = emptyList(),
    /** 分类筛选的名称（「零食 / 咖啡」），只有带着分类进来时才有值。 */
    val categoryLabel: String? = null,
    val loaded: Boolean = false,
)

class SearchViewModel(
    private val graph: AppGraph,
    initialLedgerId: String? = null,
    initialCategoryL1Id: String? = null,
    initialCategoryL2Id: String? = null,
    initialTagIds: List<String> = emptyList(),
) : ViewModel() {
    // 首页切到某个账本再进来搜索，默认就搜这个账本，而不是回到默认账本。
    // 从分类管理的「查看统计数据」进来时带着分类，进来就是一类账的清单。
    private val filter = MutableStateFlow(
        BillFilter(
            ledgerId = initialLedgerId,
            categoryL1Id = initialCategoryL1Id,
            categoryL2Id = initialCategoryL2Id,
            tagIds = initialTagIds,
        ),
    )

    /** 分类名在建立这一页时取一次就够：这一页不改分类名，改完再进来是新的一次。 */
    private val categoryLabel = MutableStateFlow<String?>(null)

    /**
     * 从归档标签详情带进来的那枚只是**入场默认**，不是他挑的：一旦点了别的筛选 chip，
     * 它就该退场——否则筛着筛着它一直亮着，看着像自己多选了一枚看不见来源的标签。
     * 使用中的标签照常留在筛选里，只有归档的这种「只因为带着进来才出现」的才退。
     */
    private var seededArchivedTagIds: Set<String> = emptySet()
    private var filterChanged = false

    init {
        if (initialCategoryL1Id != null || initialCategoryL2Id != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val l1 = initialCategoryL1Id?.let { graph.categoryRepository.find(it)?.name }
                val l2 = initialCategoryL2Id?.let { graph.categoryRepository.find(it)?.name }
                categoryLabel.value = listOfNotNull(l1, l2).joinToString(" / ")
            }
        }
        if (initialTagIds.isNotEmpty()) {
            viewModelScope.launch {
                val archived = withContext(Dispatchers.IO) {
                    initialTagIds.filter { graph.tagRepository.find(it)?.archivedAt != null }
                }.toSet()
                // 查归档要过一趟数据库；这期间他已经点了别的 chip 就直接摘掉，不再当成默认项。
                if (filterChanged) {
                    filter.update { it.copy(tagIds = it.tagIds - archived) }
                } else {
                    seededArchivedTagIds = archived
                }
            }
        }
    }

    /**
     * 搜索是一条不带 LIMIT 的三列 LIKE 联表，每敲一个字跑一遍是白烧时间；停手 160ms 再查。
     * 但**第一次必须立刻查**——不然每次点进搜索都要先空转 160ms，那正是"正在查…"闪一下的来处。
     * 筛选 chip 读的是 [filter] 本身，所以点下去仍然即时生效。
     */
    private val result = merge(
        filter.take(1),
        filter.drop(1).debounce(KEYWORD_DEBOUNCE_MILLIS),
    ).flatMapLatest { graph.billRepository.observeSearch(it) }

    /** 账本 chip 只要名称与 id：走轻量的账本表，不为了几个按钮跑整张 bill 表的 GROUP BY。 */
    private val ledgerChips = graph.ledgerRepository.active
        .map { ledgers -> ledgers.filterNot { it.isArchived } }

    /**
     * 筛选候选仍只列使用中的标签；但**正被选中的归档标签要补进来**——标签详情的
     * 「查看全部」会把归档标签的 id 带进搜索，不补就出现「账单有、筛选里没有」。
     * 首页搜索不会主动列出归档标签，所以这条规则不会把归档标签铺进日常筛选。
     */
    private val tagChoices = combine(
        graph.tagRepository.observeActive(),
        graph.tagRepository.observeArchived(),
        filter.map { it.tagIds },
    ) { active, archived, selectedIds ->
        val picked = selectedIds.toSet()
        archived.filter { it.id in picked }.map { TagFilterChoice(it.id, it.name, archived = true) } +
            active.map { TagFilterChoice(it.id, it.name, archived = false) }
    }

    val state: StateFlow<SearchUiState> = combine(
        filter,
        result,
        ledgerChips,
        tagChoices,
        categoryLabel,
    ) { currentFilter, currentResult, ledgers, tags, label ->
        SearchUiState(
            filter = currentFilter,
            result = currentResult,
            ledgers = ledgers,
            tags = tags,
            categoryLabel = label.takeIf { currentFilter.categoryL1Id != null || currentFilter.categoryL2Id != null },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setKeyword(value: String) = update { copy(keyword = value) }

    fun setType(value: BillType?) = updateFromChip { copy(type = value) }

    fun setLedger(value: String?) = updateFromChip { copy(ledgerId = value) }

    fun setSource(value: BillSource?) = updateFromChip { copy(source = value) }

    /** 多选＝含任一：再点一次摘掉那一枚，摘到零枚就是不限。 */
    fun toggleTag(tagId: String) = updateFromChip {
        copy(tagIds = if (tagId in tagIds) tagIds - tagId else tagIds + tagId)
    }

    fun clearTags() = updateFromChip { copy(tagIds = emptyList()) }

    /** 分类筛选只能带着进来或清掉：这一页没有分类选择器，要挑分类去分类管理。 */
    fun clearCategory() = updateFromChip { copy(categoryL1Id = null, categoryL2Id = null) }

    fun setAllTime() = updateFromChip { copy(startAt = null, endAtExclusive = null) }

    fun setThisMonth() {
        val start = monthStartOf()
        updateFromChip { copy(startAt = start, endAtExclusive = shiftMonthStart(start, 1)) }
    }

    fun setLast30Days() {
        val end = System.currentTimeMillis() + 86_400_000L
        updateFromChip { copy(startAt = end - 30L * 86_400_000L, endAtExclusive = end) }
    }

    /** 时间筛选按钮要能显出当前选中项，否则「本月」按下去没有反馈。 */
    fun isThisMonth(): Boolean {
        val start = monthStartOf()
        return filter.value.startAt == start && filter.value.endAtExclusive == shiftMonthStart(start, 1)
    }

    /**
     * 金额区间以「元」文本进来，空串即该侧不限；解析不动就当没写过，
     * 不在输入框旁边挂一条红色报错（`parseAmount` 已容 ￥ 与千分位）。
     * 输入框改一下就走一次这条更新，防抖在 [result] 那里，所以不再要「应用」按钮。
     */
    fun setAmountRange(minText: String, maxText: String) = update {
        copy(
            amountMinMinor = parseAmount(minText)?.coerceAtLeast(0L),
            amountMaxMinor = parseAmount(maxText)?.coerceAtLeast(0L),
        )
    }

    fun setSort(value: BillSort) = update { copy(sort = value) }

    fun isLast30Days(): Boolean {
        val end = System.currentTimeMillis() + 86_400_000L
        val current = filter.value
        return current.endAtExclusive != null && Math.abs(current.endAtExclusive - end) < 86_400_000L &&
            current.startAt != null && Math.abs(current.startAt - (end - 30L * 86_400_000L)) < 86_400_000L
    }

    fun clear() {
        seededArchivedTagIds = emptySet()
        filter.value = BillFilter()
    }

    private fun update(block: BillFilter.() -> BillFilter) {
        filter.update { it.block() }
    }

    /** 点了某个筛选 chip：归档标签的入场默认项一起退场。 */
    private fun updateFromChip(block: BillFilter.() -> BillFilter) {
        val seeded = seededArchivedTagIds.also { seededArchivedTagIds = emptySet() }
        filterChanged = true
        filter.update { current ->
            val next = current.block()
            if (seeded.isEmpty()) next else next.copy(tagIds = next.tagIds - seeded)
        }
    }

    private companion object {
        const val KEYWORD_DEBOUNCE_MILLIS = 160L
    }
}
