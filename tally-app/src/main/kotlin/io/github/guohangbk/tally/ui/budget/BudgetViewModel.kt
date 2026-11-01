package io.github.guohangbk.tally.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.model.BudgetDraft
import io.github.guohangbk.tally.domain.model.BudgetItem
import io.github.guohangbk.tally.domain.model.BudgetPeriod
import io.github.guohangbk.tally.domain.model.BudgetSummary
import io.github.guohangbk.tally.domain.model.budgetUsage
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.DEFAULT_ALERT_RATIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 编辑态的预算：金额按文本逐位输入（键盘），保存时才折成分。 */
data class BudgetEditState(
    val id: String? = null,
    val ledgerId: String,
    val ledgerName: String,
    val isTotal: Boolean,
    val categoryId: String?,
    val categoryName: String?,
    val period: BudgetPeriod,
    /** 这一条归属的周期段起始时刻：新建取当前段，编辑沿用被改那条的段——预算真·按月独立。 */
    val periodStart: Long,
    /** 下一段是否自动复制一条同样的（只对每月生效）。 */
    val autoRenew: Boolean = false,
    val amountText: String,
    val alertRatio: Int,
    val isActive: Boolean,
    /** 该分类本月有多少笔——编辑页副行那句「含 N 笔本月账单」。 */
    val categoryBillCount: Int? = null,
)

data class BudgetUiState(
    val loaded: Boolean = false,
    val ledgerId: String? = null,
    val ledgerName: String = "全部账本",
    val ledgers: List<LedgerListItem> = emptyList(),
    val summary: BudgetSummary? = null,
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val startDay: Int = Period.DEFAULT_START_DAY,
    val settings: TallySettings = TallySettings.DEFAULT,
    /** 本账本共几条预算（不限周期）：区分「真的一条都没有」与「另一段里才有」。 */
    val totalItems: Int = 0,
    /**
     * 刚保存完、但真实流还没追上时的那一份总额修正：非空且当前在总览，总额卡就读它。
     * 这是「保存→回显」这一步的唯一真状态，[dismissEdit] 后自动消失，不伪装成真实流。
     */
    val pendingTotal: PendingTotal? = null,
)

/** 保存后待真实流接管的总额两格：只带额度与提醒开关，用量仍归真实流。 */
data class PendingTotal(val amountMinor: Long, val isActive: Boolean)

class BudgetViewModel(private val graph: AppGraph) : ViewModel() {
    private val ledgerId = MutableStateFlow(graph.cachedDefaultLedgerId)
    private val period = MutableStateFlow(BudgetPeriod.MONTHLY)
    private val startDay = MutableStateFlow(Period.DEFAULT_START_DAY)
    private val edit = MutableStateFlow<BudgetEditState?>(null)
    private val toast = MutableStateFlow<String?>(null)
    /** 保存时的重复拦截（BU21-08）：同段已有同范围的一条（总额撞总额、分类撞同分类）→ 不写库，界面给一行提示。 */
    private val clash = MutableStateFlow<BudgetItem?>(null)
    /**
     * 刚保存的那条总额的两个字段修正。真实流按 periodStart 匹配的是那一段的当前行，
     * 保存（尤其是改周期、改范围）可能写回一个新 id，那一行在真实流追上前仍带着旧额度；
     * 界面先读这份修正，追上后两边值自然一致，修正就隐身后不影响了。
     *
     * 只存「额度 + 开关」两格，不存整份 usage：用量归账单聚合那一路管，
     * 替换整份会把旧快照的 `used / ratio` 也带回去，进度与日均就与统计页对不上（2026-10-28 走查）。
     */
    private val pendingTotal = MutableStateFlow<PendingTotal?>(null)

    /** 账本切换只用「本账 N 笔」那份聚合里的账本身份，不跑统计。 */
    val ledgers: StateFlow<List<LedgerListItem>> = graph.ledgerRepository.items
        .map { list -> list.filter { !it.ledger.isArchived } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<TallySettings> = graph.preferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TallySettings.DEFAULT)

    val summary: StateFlow<BudgetSummary?> = combine(ledgerId, period, startDay) { ledger, p, day ->
        Triple(ledger, p, day)
    }
        .mapNotNull { (ledger, p, day) -> ledger?.let { Triple(it, p, day) } }
        .flatMapLatest { (ledger, p, day) -> graph.budgetRepository.observeSummary(ledger, p, day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 本账本的预算条数（两个周期都算、只认真实周期段）：空态得知道人到底设过没设过，迁移遗留的 periodStart=0 旧行不算。 */
    private val itemCounts: StateFlow<Int> = ledgerId
        .flatMapLatest { ledger ->
            if (ledger == null) flowOf(0)
            else graph.budgetRepository.observeItems(ledger).map { items -> items.count { it.periodStart > 0L } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 预算历史：本账本、当前节奏最近的若干段，逐段给出当时的额度与最终完成（供历史页只读回看）。 */
    val history: StateFlow<List<BudgetSummary>> = combine(ledgerId, period, startDay) { ledger, p, day ->
        Triple(ledger, p, day)
    }
        .mapNotNull { (ledger, p, day) -> ledger?.let { Triple(it, p, day) } }
        .flatMapLatest { (ledger, p, day) -> graph.budgetRepository.observeHistory(ledger, p, day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 七路上游要用嵌套 combine：直接传六个以上流会落到 vararg 重载，参数类型退化成 Array<Any?>。
    val state: StateFlow<BudgetUiState> =
        combine(
            combine(ledgers, summary, itemCounts) { ledgers, summary, count -> Triple(ledgers, summary, count) },
            combine(ledgerId, period, startDay) { ledger, p, day -> Triple(ledger, p, day) },
            combine(pendingTotal, edit) { pending, e -> pending to e },
        ) { (ledgers, summary, count), (ledger, p, day), (pending, editing) ->
            BudgetUiState(
                loaded = ledger != null,
                ledgerId = ledger,
                ledgerName = ledgers.firstOrNull { it.ledger.id == ledger }?.ledger?.name ?: "全部账本",
                ledgers = ledgers,
                summary = patchTotal(summary, pending, editing),
                period = p,
                startDay = day,
                settings = settings.value,
                totalItems = count,
                pendingTotal = pending.takeIf { editing == null },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    /**
     * 把刚保存的额度/开关覆盖到总额那一条上，其余字段（`used / ratio`）原样留着。
     * 只在总览生效（编辑态不覆盖，那一页本来就读草稿）；真实流追上后这里算出来的与它一致，看不出差异。
     */
    private fun patchTotal(summary: BudgetSummary?, pending: PendingTotal?, editing: BudgetEditState?): BudgetSummary? {
        if (summary == null || pending == null || editing != null) return summary
        val total = summary.total ?: return summary
        if (total.item.amountMinor == pending.amountMinor && total.item.isActive == pending.isActive) return summary
        val patched = total.item.copy(amountMinor = pending.amountMinor, isActive = pending.isActive)
        return summary.copy(
            total = budgetUsage(
                item = patched,
                usedMinor = total.usedMinor,
                range = summary.range,
                elapsedDays = summary.elapsedDays,
                totalDays = summary.range.lengthDays(),
            ),
        )
    }

    /** 分类选择器要的那一级分类。只有点开选择器时才取，不压首帧。 */
    private val expenseCategoryEntities = MutableStateFlow<List<io.github.guohangbk.tally.data.db.entity.CategoryEntity>>(emptyList())
    val expenseCategoryEntitiesFlow: StateFlow<List<io.github.guohangbk.tally.data.db.entity.CategoryEntity>> =
        expenseCategoryEntities.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun loadExpenseCategories() {
        viewModelScope.launch {
            expenseCategoryEntities.value = graph.database.categoryDao()
                .findTreeOnce(CategoryType.EXPENSE.name)
                .filter { it.parentId == null }
        }
    }

    init {
        // 起始日一改，预算的周期边界就跟着变——与首页、统计、提醒四处同一口径。
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            graph.settingRepository.monthStartDay.collect { day -> startDay.value = day }
        }
        // 冷启动时进程里的默认账本可能还是空的（记账页在 init 里会复核一次真实默认账本，预算页早先没有）。
        // 空账本不会报错，只会让页变成「一条预算都没有」，新预算又写进一个不存在的账本（orEmpty），
        // 读不回来——这就是「新增/修改不生效 + 总览闪一下没预算」的根（2026-10-28 走查）。
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            resolveLedger()
        }
    }

    /** 预算一律挂在「当前选中的那一个账本」上：还没选中就先从库里取默认账本，取不到返回 null 让调用方拦下。 */
    private suspend fun resolveLedger(): String? {
        ledgerId.value?.let { return it }
        val fallback = graph.ledgerRepository.defaultLedger()?.id ?: ledgers.value.firstOrNull()?.ledger?.id
        if (fallback != null) ledgerId.value = fallback
        return fallback
    }

    val editState: StateFlow<BudgetEditState?> = edit
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val toastState: StateFlow<String?> = toast.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 非空时编辑页在分类行下给一行陶土提示「已存在同样的分类预算设置」+「去看那一条」。 */
    val clashState: StateFlow<BudgetItem?> = clash.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun selectLedger(id: String) {
        ledgerId.value = id
    }

    fun selectPeriod(next: BudgetPeriod) {
        period.value = next
    }

    fun clearToast() {
        toast.value = null
    }

    fun notify(message: String) {
        toast.value = message
    }

    /**
     * 点「＋」新建：一律开一张空白草稿，不在入口这里判断「同一段里有没有同一条」。
     * 早先那一次重定向既让「已有总额时点＋」直接进总额编辑，又让新建分类预算撞上总额那条、
     * 保存时把总额就地改成分类（总额凭空消失）——重复一律到保存时校验（见 [save]，2026-10-28 走查）。
     */
    fun beginCreate(isTotal: Boolean) {
        viewModelScope.launch {
            val ledger = resolveLedger() ?: run { notify("还没有账本，先建一个账本再设预算"); return@launch }
            edit.value = BudgetEditState(
                ledgerId = ledger,
                ledgerName = ledgerName(ledger),
                isTotal = isTotal,
                categoryId = null,
                categoryName = null,
                period = period.value,
                periodStart = currentPeriodStart(),
                amountText = "",
                alertRatio = DEFAULT_ALERT_RATIO,
                isActive = true,
            )
        }
    }

    fun beginEdit(item: BudgetItem) {
        viewModelScope.launch {
            clash.value = null
            val category = item.categoryId?.let { graph.categoryRepository.find(it) }
            edit.value = BudgetEditState(
                id = item.id,
                ledgerId = item.ledgerId,
                ledgerName = ledgerName(item.ledgerId),
                isTotal = item.isTotal,
                categoryId = category?.id ?: item.categoryId,
                categoryName = category?.name ?: item.categoryName,
                period = item.period,
                periodStart = item.periodStart,
                autoRenew = item.autoRenew,
                amountText = amountToText(item.amountMinor),
                alertRatio = item.alertRatio,
                isActive = item.isActive,
                categoryBillCount = category?.let { countCategoryBills(it.id) },
            )
        }
    }

    fun dismissEdit() {
        // 离开编辑态就把「刚保存」的痕迹抹掉：下一次进编辑页是全新的一轮。
        pendingTotal.value = null
        edit.value = null
        clash.value = null
    }

    /** 当前周期段的起始时刻：新建、改节奏都以「这一段」为准现算。 */
    private fun currentPeriodStart(): Long =
        graph.budgetRepository.periodRange(period.value, startDay.value).startAt

    /** 编辑页顶栏那个账本标签：列表里查不到就是「全部账本」。 */
    private fun ledgerName(id: String?): String =
        ledgers.value.firstOrNull { it.ledger.id == id }?.ledger?.name ?: "全部账本"

    fun pressKey(key: String) {
        val draft = edit.value ?: return
        // 键盘的退格键走的是 onKey("⌫")（记账页同一条通路），这里必须接住，
        // 否则它会像一个字符一样被塞进金额里。
        if (key == "⌫") {
            deleteDigit()
            return
        }
        // 与记账页同一条追加规则：新数字永远接在当前那段的末尾，小数点前空着先补 0。
        // 早先在这里自己写了一套「把键拼进整数位」的算法，金额只要带角分，
        // 3,600.45 按 6 就成了 360045.6——数字键看着就「没反应 / 乱跳」（2026-10-28 走查）。
        val current = draft.amountText
        val next = when {
            key == "." -> when {
                "." in current -> current
                current.isEmpty() -> "0."
                else -> "$current."
            }
            "." in current -> {
                val cents = current.substringAfter('.')
                if (cents.length >= MAX_AMOUNT_CENTS_DIGITS) current else "$current$key"
            }
            current.length >= MAX_AMOUNT_YUAN_DIGITS -> current
            else -> current + key
        }
        edit.value = draft.copy(amountText = next)
    }

    fun deleteDigit() {
        val draft = edit.value ?: return
        edit.value = draft.copy(amountText = draft.amountText.dropLast(1))
    }

    fun clearAmount() {
        val draft = edit.value ?: return
        edit.value = draft.copy(amountText = "")
    }

    /** 范围在编辑页里切换：总额预算不需要分类，切过去就把分类清掉。 */
    fun setScope(isTotal: Boolean) {
        val draft = edit.value ?: return
        clash.value = null
        edit.value = draft.copy(
            isTotal = isTotal,
            categoryId = if (isTotal) null else draft.categoryId,
        )
    }

    fun setAlertRatio(ratio: Int) {
        val draft = edit.value ?: return
        edit.value = draft.copy(alertRatio = ratio)
    }

    /** 「每月自动续期」开关：只对每月预算有意义，关掉就不往下一段复制。 */
    fun setAutoRenew(on: Boolean) {
        val draft = edit.value ?: return
        edit.value = draft.copy(autoRenew = on)
    }

    /** 周期在编辑页里直接换（BU21-04）：保存时按这一条走，不再只能从总览间接带过去。 */
    fun setPeriod(next: BudgetPeriod) {
        val draft = edit.value ?: return
        clash.value = null
        edit.value = draft.copy(period = next, periodStart = graph.budgetRepository.periodRange(next, startDay.value).startAt)
    }

    /** 选一级分类：副行那句「含 N 笔本月账单」在这里一起算好，不在界面里再查一次。 */
    fun pickCategory(categoryId: String, categoryName: String) {
        viewModelScope.launch {
            val count = countCategoryBills(categoryId)
            val draft = edit.value ?: return@launch
            clash.value = null
            edit.value = draft.copy(
                isTotal = false,
                categoryId = categoryId,
                categoryName = categoryName,
                categoryBillCount = count,
            )
        }
    }

    fun saveAmountTextMinor(): Long = amountToMinor(edit.value?.amountText.orEmpty())

    fun save() {
        val draft = edit.value ?: return
        // 账本空的绝不允许写库：写进去就是一行挂在不存在账本上的孤儿预算，哪里也读不到。
        if (draft.ledgerId.isBlank()) {
            notify("还没有账本，先建一个账本再设预算")
            return
        }
        // 分类预算必须先选一个一级分类；没选时 categoryId 为空会被当成「总额」那条，绝不能写进去。
        if (!draft.isTotal && draft.categoryId == null) {
            notify("先选一个一级分类")
            return
        }
        val minor = amountToMinor(draft.amountText)
        if (minor <= 0L) {
            notify("预算金额不能是 0")
            return
        }
        viewModelScope.launch {
            // 撞车拦截（BU21-08）：这条不是它自己、却已占了同段同范围 → 拦下不写库。
            // 总额也要查：把总额改成分类保存时，早先只有分类走这道拦——总额位被分类拿走，总额就没了。
            val occupied = graph.budgetRepository.sameScope(draft.ledgerId, draft.period, draft.periodStart, if (draft.isTotal) null else draft.categoryId)
            if (occupied != null && occupied.id != draft.id) {
                clash.value = occupied
                return@launch
            }
            val saved = graph.budgetRepository.save(
                BudgetDraft(
                    id = draft.id,
                    ledgerId = draft.ledgerId,
                    categoryId = draft.categoryId,
                    period = draft.period,
                    periodStart = draft.periodStart,
                    autoRenew = draft.autoRenew,
                    amountMinor = minor,
                    alertRatio = draft.alertRatio,
                    isActive = draft.isActive,
                ),
            )
            // 总额那条记下「额度 + 开关」两格修正，总览在真实流追上前先读它，不整页重订阅。
            // （改周期/改范围时库里会写回一个新 id，真实流按 periodStart 只能把旧行继续列出来，
            // 不覆盖就会看着像「保存了但卡片还是旧值」。）
            pendingTotal.value = if (saved.isTotal) PendingTotal(amountMinor = saved.amountMinor, isActive = saved.isActive) else null
            clash.value = null
            notify(if (draft.id == null) "预算已保存" else "预算已更新")
            // 把周期改了就先切到那一段，不然保存完停在原来那一屏，看着像没生效。
            period.value = draft.period
            edit.value = null
        }
    }

    /** 撞车提示里的「去看那一条」：进那条已有预算的编辑，本屏的改动丢弃。 */
    fun openClash() {
        val target = clash.value ?: return
        beginEdit(target)
    }

    /** 删除一条预算：只删规则，账单与统计都不受影响。删除入口在列表那一行里，不在编辑页。 */
    fun delete(item: BudgetItem) {
        viewModelScope.launch {
            if (graph.budgetRepository.delete(item.id)) {
                pendingTotal.value = null
                notify("预算已删除")
            }
        }
    }

    /** 预算提醒开关：编辑页只改草稿，点保存才连同金额一起落库（早先是一点就写库 + 弹气泡，开关还不动）。 */
    fun setActive(active: Boolean) {
        val draft = edit.value ?: return
        edit.value = draft.copy(isActive = active)
    }

    private suspend fun countCategoryBills(categoryId: String): Int {
        val ledger = ledgerId.value ?: return 0
        val range = graph.budgetRepository.periodRange(period.value, startDay.value)
        return graph.billRepository.observeCategoryBills(
            ledgerId = ledger,
            range = range,
            type = BillType.EXPENSE,
            l1Id = categoryId,
            l2Id = null,
            excludePendingReimburse = false,
        ).first().bills.size
    }

    /** 一级分类（预算只认一级，二级的支出归堆到它的父类上）。 */
    suspend fun expenseCategories(): List<io.github.guohangbk.tally.domain.model.Category> =
        graph.categoryRepository.categories(CategoryType.EXPENSE).filter { it.parentId == null }

    private fun amountToText(minor: Long): String {
        val yuan = minor / 100
        val cents = minor % 100
        return if (cents == 0L) yuan.toString() else "$yuan.${cents.toString().padStart(2, '0')}"
    }

    private fun amountToMinor(text: String): Long {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0L
        // 没有小数点时 substringAfter 会把整串当小数尾巴返回，「100」于是被折成 100 元 10 分（2026-10-28 走查）。
        val head = trimmed.substringBefore(".").filter(Char::isDigit).ifEmpty { "0" }
        val cents = if ('.' in trimmed) {
            trimmed.substringAfter('.').take(2).filter(Char::isDigit).padEnd(2, '0').toIntOrNull() ?: 0
        } else {
            0
        }
        return head.toLongOrNull()?.times(100)?.plus(cents) ?: 0L
    }

    companion object {
        /** 预算金额最长 8 位整数 + 2 位角分：预算是年额度也不是记账表达式，不需要 9 位。 */
        private const val MAX_AMOUNT_YUAN_DIGITS = 8
        private const val MAX_AMOUNT_CENTS_DIGITS = 2
    }
}
