package io.github.guohangbk.tally.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.model.BillMonthSnapshot
import io.github.guohangbk.tally.domain.model.CategoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loaded: Boolean = false,
    val monthStart: Long = Period.monthRange(System.currentTimeMillis()).startAt,
    /** 每月起始日：1 号时「本月」就是自然月，否则按周期段滚动。 */
    val monthStartDay: Int = Period.DEFAULT_START_DAY,
    val ledgerId: String? = null,
    val ledgerName: String = "全部账本",
    val ledgerIconKey: String? = null,
    val ledgers: List<LedgerEntity> = emptyList(),
    val ledgerBillCount: Int = 0,
    val snapshot: BillMonthSnapshot? = null,
    val settings: TallySettings = TallySettings.DEFAULT,
    val categoryBadge: String? = null,
) {
    val monthRange: PeriodRange get() = Period.monthRange(monthStart, monthStartDay)

    /** 日均按「本周期已过几天」算，翻到历史周期则按整段天数。 */
    val daysElapsed: Int
        get() {
            val range = monthRange
            val today = System.currentTimeMillis()
            return when {
                today < range.startAt || today >= range.endAtExclusive -> range.lengthDays()
                else -> Period.daysBetween(range.startAt, today) + 1
            }
        }
}

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    // 性能打点基准：VM 创建即零点，所有 Perf 日志都相对它报「第几毫秒」，并带上绝对时刻方便对齐 SQL 回调。
    private val vmStart = System.currentTimeMillis()
    private fun since(tag: String): String = "$tag +${System.currentTimeMillis() - vmStart}ms"

    /**
     * 用户手动翻走的那一段；null 代表没翻过，首页跟着「今天」走。
     * 直接把这个值当月份用，就会出现 10-31 打开、过了零点仍停在 10 月那种事。
     */
    private val pagedMonth = MutableStateFlow<Long?>(null)
    /** 今天所在周期段的起点：过零点、改系统时间/时区、回到前台时刷新。 */
    private val todayMonth = MutableStateFlow(Period.monthRange(System.currentTimeMillis()).startAt)
    private val monthStart = combine(pagedMonth, todayMonth) { paged, today -> paged ?: today }
    private val monthStartDay = MutableStateFlow(Period.DEFAULT_START_DAY)
    // 用启动时缓存的默认账本初始化：命中就省掉「全部账本→默认账本」的第二次查询。
    private val ledgerId = MutableStateFlow(graph.cachedDefaultLedgerId)
    private val loaded = MutableStateFlow(false)

    val settings: StateFlow<TallySettings> = graph.preferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TallySettings.DEFAULT)

    // 默认账本没解析好之前不放行月份查询：否则 ledgerId 还是 null 时会先出一版「全部账本」的数据，
    // 再翻到默认账本——就是你看到的「先全部、后日常」。loaded 置真时 ledgerId 已是默认账本。
    private val monthFlow = combine(loaded, monthStart, ledgerId, monthStartDay) { isReady, month, ledger, startDay ->
        MonthQuery(isReady, month, ledger, startDay)
    }
        .flatMapLatest { query ->
            if (!query.ready) emptyFlow()
            else {
                val range = Period.monthRange(query.month, query.startDay)
                graph.billRepository.observeMonth(query.ledger, range).onEach { snap ->
                    // 这里不记耗时：失效驱动的 Flow 一条订阅会发很多次，拿订阅时刻算差值
                    // 会把「后台放了 123 秒」写成「取数 123 秒」。真实读写耗时看 [Lock] 探针。
                    DebugLog.log("Perf", since("本月查询完成 · 账本=${query.ledger?.take(6) ?: "全部"} 笔=${snap.summary.billCount}"))
                }.flowOn(Dispatchers.Default)
            }
        }

    private data class MonthQuery(val ready: Boolean, val month: Long, val ledger: String?, val startDay: Int)

    /**
     * 首页预算：整份月预算全貌（总额 + 各分类），卡片下那条进度与速览弹层都用它。
     * 有总额那条读总额；只有分类时读「各启用分类合计」的聚合进度（BU21-10）。没设预算返回 null。
     *
     * 锚点取首页当前那一段（[monthStart]），不取「今天」：顶部翻到 9 月，这条进度就得是 9 月的预算，
     * 否则上面写着 10 月、下面那条跟着本月跑，两个数并排摆着。
     */
    val budgetSummary: StateFlow<io.github.guohangbk.tally.domain.model.BudgetSummary?> =
        combine(ledgerId, monthStartDay, monthStart) { ledger, day, month -> Triple(ledger, day, month) }
            .flatMapLatest { (ledger, day, month) ->
                if (ledger == null) emptyFlow()
                else graph.budgetRepository.observeSummary(
                    ledger,
                    io.github.guohangbk.tally.domain.model.BudgetPeriod.MONTHLY,
                    day,
                    month,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 抽屉里「分类管理」后面的「23 / 6」徽标：支出与收入的一级数量，与账本无关。 */
    private val categoryFlow = combine(
        graph.categoryRepository.tree(CategoryType.EXPENSE),
        graph.categoryRepository.tree(CategoryType.INCOME),
    ) { expense, income -> expense.size.toString() + " / " + income.size }

    // 六路上游要用嵌套 combine：直接传六个流会落到 vararg 重载，参数类型退化成 Array<Any?>
    private val monthSlice = combine(
        combine(monthFlow, monthStart, monthStartDay) { snapshot, month, startDay -> Triple(snapshot, month, startDay) },
        combine(ledgerId, categoryFlow) { ledger, badge -> ledger to badge },
    ) { (snapshot, month, startDay), (ledger, badge) -> MonthSlice(snapshot, month, startDay, ledger, badge) }

    private data class MonthSlice(
        val snapshot: BillMonthSnapshot,
        val month: Long,
        val startDay: Int,
        val ledgerId: String?,
        val categoryBadge: String?,
    )

    // 账本列表/统计是「整张 bill 表 GROUP BY」的慢查询，不该压在首帧关键路径上。
    // 首屏只要账本身份（名称/切换列表），用便宜的 active（仅 observeActive），绝不跑 observeLedgerStats。
    private val activeLedgers = graph.ledgerRepository.active
        .onEach { DebugLog.log("Perf", since("active 账本数=${it.size}")) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 抽屉标题的「这本账 N 笔」：单账本 COUNT 走索引，比整表聚合轻得多。
    private val ledgerBillCount = ledgerId.flatMapLatest { ledger ->
        graph.billRepository.observeBillCount(ledger).onEach { count ->
            DebugLog.log("Perf", since("billCount=$count · 账本=${ledger?.take(6) ?: "全部(整表扫)"}"))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, graph.cachedHomeBillCount)

    // 只有账本切换弹层才需要「每本账本多少笔」的整表聚合：用户点开弹层时才加载，不压首屏。
    private val statsRequested = MutableStateFlow(false)
    val ledgerStats: StateFlow<List<LedgerListItem>> = statsRequested.flatMapLatest { requested ->
        if (!requested) flowOf(emptyList())
        else {
            graph.ledgerRepository.items.map { list -> list.filter { !it.ledger.isArchived } }
                .onEach { DebugLog.log("Perf", since("账本统计(整表聚合)完成 · ${it.size}本")) }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadLedgerStats() {
        DebugLog.log("Perf", since("点开账本切换弹层 → 触发整表聚合 observeLedgerStats"))
        statsRequested.value = true
    }

    val state: StateFlow<HomeUiState> = combine(monthSlice, activeLedgers, ledgerBillCount, settings, loaded) { slice, ledgers, billCount, preferences, isLoaded ->
        HomeUiState(
            loaded = isLoaded,
            monthStart = slice.month,
            monthStartDay = slice.startDay,
            ledgerId = slice.ledgerId,
            ledgerName = ledgers.firstOrNull { it.id == slice.ledgerId }?.name ?: "全部账本",
            ledgerIconKey = ledgers.firstOrNull { it.id == slice.ledgerId }?.iconKey,
            categoryBadge = slice.categoryBadge,
            ledgers = ledgers.filter { !it.isArchived },
            ledgerBillCount = billCount,
            snapshot = slice.snapshot,
            settings = preferences,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // 首帧直接拿 prewarmHome 缓存的本月快照/笔数/默认账本名播种：首帧即真实数据，
        // 不再先空一帧「正在汇总本月账单…」。缓存为空（首次安装/预热未跑）时才退回空占位。
        HomeUiState(
            loaded = graph.cachedDefaultLedgerId != null,
            ledgerId = graph.cachedDefaultLedgerId,
            ledgerName = graph.cachedDefaultLedgerName ?: "全部账本",
            ledgerBillCount = graph.cachedHomeBillCount,
            snapshot = graph.cachedHomeSnapshot,
        ),
    )

    init {
        DebugLog.log("Perf", since("HomeViewModel 创建 cachedLedgerId=${graph.cachedDefaultLedgerId?.take(6) ?: "null"}"))
        // 应用层启动时已经 bootstrap 并缓存了默认账本：命中就不再重复触库，直接放行本月查询。
        if (graph.cachedDefaultLedgerId != null) {
            loaded.value = true
        } else {
            viewModelScope.launch {
                val queryAt = System.currentTimeMillis()
                val default = graph.ledgerRepository.bootstrap()
                DebugLog.log("Perf", since("bootstrap 完成 取数${System.currentTimeMillis() - queryAt}ms → ${default.name}"))
                graph.cachedDefaultLedgerId = default.id
                graph.cachedDefaultLedgerName = default.name
                ledgerId.value = default.id
                loaded.value = true
                DebugLog.log("Perf", since("loaded=true → 放行本月查询"))
            }
        }

        // 备份恢复整库替换后，旧账本 id 查不到任何账单：重新解析默认账本再放行查询。
        viewModelScope.launch {
            graph.dataGeneration.drop(1).collect {
                val default = graph.ledgerRepository.bootstrap()
                graph.cachedDefaultLedgerId = default.id
                graph.cachedDefaultLedgerName = default.name
                ledgerId.value = default.id
                backToToday()
                loaded.value = true
                DebugLog.log("Perf", since("恢复备份后重新绑定默认账本 → ${default.name}"))
            }
        }

        // 起始日一改就重新落回「当前周期」：用户刚在设置里改完，回到首页应该看到新的本月。
        viewModelScope.launch(Dispatchers.IO) {
            graph.settingRepository.monthStartDay.collect { day ->
                monthStartDay.value = day
                backToToday()
            }
        }

        // 挂着不动也要翻篇：10-31 开着 App 过零点，首页该自己落到 11 月。
        // 一分钟一次只是重算一个纯函数，值没变不往下游推，不碰数据库。
        viewModelScope.launch {
            while (true) {
                refreshToday()
                delay(TODAY_TICK_MILLIS)
            }
        }
    }

    /** 回到前台时补一次：零点后解锁进 App，不等那一分钟的定时器。 */
    fun resume() = refreshToday()

    private fun refreshToday() {
        todayMonth.value = Period.monthRange(System.currentTimeMillis(), monthStartDay.value).startAt
    }

    /** 落回「今天那一段」，并把手动翻页的意图清掉。 */
    private fun backToToday() {
        pagedMonth.value = null
        refreshToday()
    }

    fun previousMonth() {
        pagedMonth.value = Period.shift(currentMonth(), -1)
    }

    fun nextMonth() {
        pagedMonth.value = Period.shift(currentMonth(), 1)
    }

    fun thisMonth() = backToToday()

    /** 年月选择器直接跳过去：翻月份不该靠一次一格地按箭头。 */
    fun selectMonth(startMillis: Long) {
        val date = Period.toDate(startMillis)
        pagedMonth.value = Period.monthStart(date.year, date.monthValue, monthStartDay.value)
    }

    private fun currentMonth(): Long = pagedMonth.value ?: todayMonth.value

    /**
     * 从抽屉进二级页时记一笔。首页被导航换掉后组合会重建，抽屉状态存不住，
     * 返回时只能靠这个标记把抽屉重新展开——不然"从抽屉进去、回到列表"这套操作是断的。
     */
    private var reopenDrawerOnReturn = false

    fun leaveForDrawerPage() {
        reopenDrawerOnReturn = true
    }

    fun takeDrawerReopen(): Boolean {
        val requested = reopenDrawerOnReturn
        reopenDrawerOnReturn = false
        return requested
    }

    fun selectLedger(id: String?) {
        ledgerId.value = id
    }

    private companion object {
        /** 「今天」的复查间隔：只为跨零点翻篇，纯计算，不取数。 */
        const val TODAY_TICK_MILLIS = 60_000L
    }
}

