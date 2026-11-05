package io.github.guohangbk.tally.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.github.guohangbk.tally.MainActivity
import io.github.guohangbk.tally.R
import io.github.guohangbk.tally.TallyApplication
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.notify.NotificationLink
import io.github.guohangbk.tally.data.notify.TallyNotifier
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillDayGroup
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.BudgetPeriod
import io.github.guohangbk.tally.ui.design.IconLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 三期新增的七枚桌面组件（docs/v3 桌面组件板）。全部只读，与已上线的
 * [TallyWidgets]（近七日 / 预算环 / 当日当月）互不影响——那三枚的布局与取数一行没动。
 *
 * 七枚共用一次刷新、一份快照：桌面上同时摆几枚也只查一次库。
 * 取数失败与「今天真的没花钱」分开——失败时拿上一次成功的数顶住并标「未更新」，
 * 一次都没成功过才显示空态，绝不拿 ¥0.00 冒充。
 */
object InsightWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 前台/记账后的即发即忘入口：桌面上没摆这几枚时连协程都不起。 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        if (PROVIDERS.none { manager.getAppWidgetIds(ComponentName(app, it)).isNotEmpty() }) return
        scope.launch { runCatching { updateAll(app) } }
    }

    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val instances = PROVIDERS.map { it to manager.getAppWidgetIds(ComponentName(app, it)) }
        if (instances.all { it.second.isEmpty() }) return

        val graph = TallyApplication.from(app).graph
        val loaded = runCatching { load(app, graph, System.currentTimeMillis()) }
        loaded.exceptionOrNull()?.let {
            DebugLog.log("组件", "取数失败：${it.message ?: it::class.java.simpleName}")
        }
        val fresh = loaded.getOrNull()
        val cached = readSnapshot(app)
        if (fresh != null) writeSnapshot(app, fresh)
        val data = fresh ?: cached
        val stale = fresh == null && cached != null

        instances.forEach { (provider, ids) ->
            if (ids.isEmpty()) return@forEach
            val views = viewsFor(app, provider, data, stale) ?: return@forEach
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }

    private fun viewsFor(
        context: Context,
        provider: Class<out AppWidgetProvider>,
        data: Snapshot?,
        stale: Boolean,
    ): RemoteViews? = when (provider) {
        FlowSmallWidgetProvider::class.java -> flowSmallViews(context, data)
        FlowWideWidgetProvider::class.java -> flowWideViews(context, data, stale)
        QuickSmallWidgetProvider::class.java -> quickViews(context, data, R.layout.widget_quick_s)
        QuickWideWidgetProvider::class.java -> quickViews(context, data, R.layout.widget_quick_m)
        BudgetRingWidgetProvider::class.java -> budgetViews(context, data, stale)
        RhythmWidgetProvider::class.java -> rhythmViews(context, data)
        StreakWidgetProvider::class.java -> streakViews(context, data)
        else -> null
    }

    private val PROVIDERS = arrayOf(
        FlowSmallWidgetProvider::class.java,
        FlowWideWidgetProvider::class.java,
        QuickSmallWidgetProvider::class.java,
        QuickWideWidgetProvider::class.java,
        BudgetRingWidgetProvider::class.java,
        RhythmWidgetProvider::class.java,
        StreakWidgetProvider::class.java,
    )

    // ---- 取数 ----

    /**
     * 一次刷新里七枚共用的全部数字。可序列化是为了取数失败时能拿上一次那份顶住——
     * 组件不能因为一次查库失败就整块变空。
     */
    @Serializable
    internal data class Snapshot(
        val ledgerId: String? = null,
        val todayExpense: Long = 0L,
        val todayIncome: Long = 0L,
        val monthExpense: Long = 0L,
        val monthIncome: Long = 0L,
        val periodTag: String = "",
        /** 近 30 天每日支出，索引 0 是最早那天，最后一格是今天。 */
        val rhythm: List<Long> = emptyList(),
        /** 近 28 天每日有效笔数，同上按日期升序。一格一笔与一格五笔在记录格里是两种深浅。 */
        val activeDays: List<Int> = emptyList(),
        val streak: Int = 0,
        val quick: List<QuickTile> = emptyList(),
        val hasBudget: Boolean = false,
        val budgetAmount: Long = 0L,
        val budgetUsed: Long = 0L,
        val budgetRatio: Int = 0,
        val budgetAlert: Int = 80,
        val budgetDaysLeft: Int = 0,
    )

    @Serializable
    internal data class QuickTile(
        val l1Id: String = "",
        val name: String = "",
        val iconKey: String = "",
        val type: String = BillType.EXPENSE.name,
    )

    private suspend fun load(context: Context, graph: AppGraph, now: Long): Snapshot {
        val startDay = graph.settingRepository.monthStartDay()
        val monthRange = Period.monthRange(now, startDay)
        val ledgerId = graph.billRepository.defaultLedgerId()
        val todayStart = Period.dayRange(now, now).startAt

        val month = graph.billRepository.observeMonth(ledgerId, monthRange).first()
        val today = month.days.firstOrNull { it.dayStart == todayStart }

        // 30 天的窗口同时喂消费节奏（30 格）与连续记录（取尾巴 28 格），只查一次。
        val windowStart = Period.shiftDays(todayStart, -(RHYTHM_DAYS - 1).toLong())
        val window = graph.billRepository
            .observeMonth(ledgerId, PeriodRange(windowStart, Period.shiftDays(todayStart, 1)))
            .first()
            .days
        val byDay = window.associateBy { it.dayStart }
        val rhythm = (0 until RHYTHM_DAYS).map { offset ->
            byDay[Period.shiftDays(windowStart, offset.toLong())]?.expenseMinor ?: 0L
        }
        // 「记过」只算真正进了收支的笔：不计入收支的那几笔不算一天。
        val activeDays = (0 until STREAK_DAYS).map { offset ->
            val dayStart = Period.shiftDays(todayStart, -(STREAK_DAYS - 1 - offset).toLong())
            byDay[dayStart]?.bills?.count { !it.bill.excludedFromStats } ?: 0
        }

        val budget = ledgerId?.let { ledger ->
            runCatching {
                graph.budgetRepository.observeSummary(ledger, BudgetPeriod.MONTHLY, startDay).first().total
            }.getOrNull()
        }

        return Snapshot(
            ledgerId = ledgerId,
            todayExpense = today?.expenseMinor ?: 0L,
            todayIncome = today?.incomeMinor ?: 0L,
            monthExpense = month.summary.expenseMinor,
            monthIncome = month.summary.incomeMinor,
            periodTag = periodTag(context, monthRange, startDay),
            rhythm = rhythm,
            activeDays = activeDays,
            streak = streakOf(activeDays),
            quick = topCategories(window),
            hasBudget = budget != null,
            budgetAmount = budget?.item?.amountMinor ?: 0L,
            budgetUsed = budget?.usedMinor ?: 0L,
            budgetRatio = budget?.let { ((it.usedMinor.toFloat() / it.item.amountMinor) * 100).toInt() } ?: 0,
            budgetAlert = budget?.item?.alertRatio ?: 80,
            budgetDaysLeft = budget?.remainingDays ?: 0,
        )
    }

    /**
     * 最近三十天用得最多的前 N 个一级分类，取满八格所需的量，2×2 那枚再用前三个。
     * 没有现成 SQL 能一次算完（统计层的分类排行只按金额、不按笔数），
     * 而这些行本来就要为节奏格读一遍，顺手从行里聚合。
     */
    private fun topCategories(days: List<BillDayGroup>): List<QuickTile> {
        class Used(val tile: QuickTile, var count: Int)
        val byId = LinkedHashMap<String, Used>()
        days.flatMap { it.bills }.forEach { item: BillListItem ->
            val id = item.bill.categoryL1Id ?: return@forEach
            val seen = byId[id]
            if (seen != null) {
                seen.count++
            } else {
                val name = item.categoryL1Name?.takeIf { it.isNotBlank() } ?: return@forEach
                byId[id] = Used(
                    QuickTile(
                        l1Id = id,
                        name = name,
                        iconKey = item.categoryL1Icon.orEmpty(),
                        type = item.bill.type,
                    ),
                    1,
                )
            }
        }
        // 并列时按名字定序，否则每次刷新槽位会互换，图标在桌面上跳。
        return byId.values
            .sortedWith(compareByDescending<Used> { it.count }.thenBy { it.tile.name })
            .take(QUICK_M_SLOTS)
            .map { it.tile }
    }

    /** 无宽限日：今天没记就是 0，不写「昨天也算」那类挽留文案。 */
    private fun streakOf(activeDays: List<Int>): Int {
        var streak = 0
        for (count in activeDays.asReversed()) {
            if (count <= 0) break
            streak++
        }
        return streak
    }

    /** 周期标签：1 号起写「11 月」，改了起始日写「10/15–11/14」。 */
    private fun periodTag(context: Context, range: PeriodRange, startDay: Int): String =
        if (Period.normalizeStartDay(startDay) == Period.DEFAULT_START_DAY) {
            context.getString(R.string.widget_month_tag, Period.toDate(range.startAt).monthValue)
        } else {
            Period.label(range, startDay)
        }

    // ---- 收支速览 ----

    /** 2×2（WG-S1）：今日支出是唯一主角，本月收支两行贴底，右上角 ＋ 进记账页。 */
    private fun flowSmallViews(context: Context, data: Snapshot?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flow_s)
        if (data == null) {
            views.setTextViewText(R.id.wfs_today_int, "—")
            views.setTextColor(R.id.wfs_today_int, context.getColor(R.color.widget_ink3))
            views.setTextColor(R.id.wfs_today_currency, context.getColor(R.color.widget_ink3))
            views.setViewVisibility(R.id.wfs_today_currency, View.GONE)
            views.setViewVisibility(R.id.wfs_today_dec, View.GONE)
            views.setTextViewText(R.id.wfs_month_expense, "—")
            views.setTextViewText(R.id.wfs_month_income, "—")
            showFailure(views)
        } else {
            val (integer, fraction) = split(data.todayExpense)
            views.setTextViewText(R.id.wfs_today_int, integer)
            views.setTextViewText(R.id.wfs_today_dec, fraction)
            views.setViewVisibility(R.id.wfs_today_currency, View.VISIBLE)
            views.setViewVisibility(R.id.wfs_today_dec, View.VISIBLE)
            views.setTextViewText(R.id.wfs_month_expense, moneyWhole(data.monthExpense))
            views.setTextViewText(R.id.wfs_month_income, moneyWhole(data.monthIncome))
            hideFailure(views)
        }
        views.setOnClickPendingIntent(R.id.widget_root, link(context, NotificationLink.Home, SLOT_FS_HOME))
        views.setOnClickPendingIntent(R.id.wfs_add, link(context, NotificationLink.NewBill, SLOT_FS_ADD))
        return views
    }

    /** 4×2（WG-S2）：今日与本月、支出与收入四格同权重。 */
    private fun flowWideViews(context: Context, data: Snapshot?, stale: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flow_m)
        val flowIds = intArrayOf(R.id.wfm_today_expense, R.id.wfm_today_income, R.id.wfm_month_expense, R.id.wfm_month_income)
        views.setTextViewText(R.id.wfm_period, data?.periodTag?.let { if (stale) staleTag(context, it) else it }.orEmpty())
        if (data == null) {
            flowIds.forEach { views.setTextViewText(it, "—") }
            showFailure(views)
        } else {
            hideFailure(views)
            views.setTextViewText(R.id.wfm_today_expense, money(data.todayExpense))
            views.setTextViewText(R.id.wfm_today_income, money(data.todayIncome))
            views.setTextViewText(R.id.wfm_month_expense, money(data.monthExpense))
            views.setTextViewText(R.id.wfm_month_income, money(data.monthIncome))
        }
        views.setOnClickPendingIntent(R.id.widget_root, link(context, NotificationLink.Stats(data?.ledgerId), SLOT_FM_HOME))
        return views
    }

    // ---- 快速记账 ----

    /**
     * 两枚共用一套填法：槽位数等于布局里的 View 数。分类不足就把空槽整块隐藏，
     * 不拿固定分类凑数；一笔都没记过时只剩固定存在的「＋ 记一笔」那一格。
     */
    private fun quickViews(context: Context, data: Snapshot?, layout: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        val tiles = data?.quick.orEmpty()
        val ids = if (layout == R.layout.widget_quick_s) QUICK_S_IDS else QUICK_M_IDS
        val addId = if (layout == R.layout.widget_quick_s) R.id.wqs_tile_add else R.id.wqm_tile_add
        views.setOnClickPendingIntent(R.id.widget_root, link(context, NotificationLink.NewBill, SLOT_Q_HOME))
        views.setOnClickPendingIntent(addId, link(context, NotificationLink.NewBill, SLOT_Q_ADD))
        if (tiles.isEmpty()) showFailure(views) else hideFailure(views)
        // 两枚共用同一份排序结果，各自取自己槽位数的前几个。
        ids.forEachIndexed { slot, (tileId, iconId, nameId) ->
            val tile = tiles.getOrNull(slot)
            if (tile == null) {
                views.setViewVisibility(tileId, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(tileId, View.VISIBLE)
            views.setTextViewText(nameId, tile.name)
            // 图标就是记账页那批 drawable；RemoteViews 不能染色，所以组件里是墨色不是品牌绿。
            views.setImageViewResource(iconId, IconLibrary.drawable(tile.iconKey) ?: R.drawable.ic_note)
            views.setOnClickPendingIntent(
                tileId,
                link(
                    context,
                    NotificationLink.NewBillDraft(
                        type = BillType.entries.firstOrNull { it.name == tile.type },
                        l1Id = tile.l1Id,
                    ),
                    SLOT_Q_TILE_BASE + slot,
                ),
            )
        }
        return views
    }

    // ---- 预算环（新样式） ----

    /** 2×2（WG-B1 / WG-B2）：圈内百分比与「已用」，圈下「剩 ¥1,158 / ¥8,000」。 */
    private fun budgetViews(context: Context, data: Snapshot?, stale: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_budget_v2)
        val rings = intArrayOf(R.id.wbv_ring_green, R.id.wbv_ring_gold, R.id.wbv_ring_expense)
        rings.forEach { views.setViewVisibility(it, View.GONE) }
        views.setTextViewText(R.id.wbv_period, data?.periodTag?.let { if (stale) staleTag(context, it) else it }.orEmpty())
        if (data == null) {
            views.setViewVisibility(R.id.wbv_ring_wrap, View.GONE)
            views.setViewVisibility(R.id.wbv_left, View.GONE)
            views.setViewVisibility(R.id.wbv_meta, View.GONE)
            views.setViewVisibility(R.id.wbv_setup, View.GONE)
            showFailure(views)
        } else if (!data.hasBudget || data.budgetAmount <= 0L) {
            views.setViewVisibility(R.id.wbv_ring_wrap, View.GONE)
            views.setViewVisibility(R.id.wbv_left, View.GONE)
            views.setViewVisibility(R.id.wbv_meta, View.GONE)
            views.setViewVisibility(R.id.wbv_setup, View.VISIBLE)
            hideFailure(views)
        } else {
            views.setViewVisibility(R.id.wbv_ring_wrap, View.VISIBLE)
            views.setViewVisibility(R.id.wbv_left, View.VISIBLE)
            views.setViewVisibility(R.id.wbv_meta, View.VISIBLE)
            views.setViewVisibility(R.id.wbv_setup, View.GONE)
            hideFailure(views)
            val over = data.budgetUsed > data.budgetAmount
            val gap = if (over) data.budgetUsed - data.budgetAmount else data.budgetAmount - data.budgetUsed
            val accent = if (over) R.color.widget_expense else R.color.widget_brand
            views.setTextViewText(R.id.wbv_percent, context.getString(R.string.widget_budget_percent_fmt, data.budgetRatio))
            views.setTextColor(R.id.wbv_percent, context.getColor(if (over) R.color.widget_expense else R.color.widget_ink))
            views.setTextColor(R.id.wbv_title, context.getColor(accent))
            views.setInt(R.id.wbv_dot, "setColorFilter", context.getColor(accent))
            rings.forEach { views.setInt(it, "setProgress", data.budgetRatio.coerceIn(0, 100)) }
            val tier = when {
                over -> R.id.wbv_ring_expense
                data.budgetRatio >= data.budgetAlert -> R.id.wbv_ring_gold
                else -> R.id.wbv_ring_green
            }
            views.setViewVisibility(tier, View.VISIBLE)
            // 结论词必须跟着换：超支时还写「剩」就会被读成「还剩 842 元」。
            views.setTextViewText(
                R.id.wbv_left,
                context.getString(
                    if (over) R.string.widget_budget_over_fmt else R.string.widget_budget_left_fmt,
                    moneyWhole(gap),
                    moneyWhole(data.budgetAmount),
                ),
            )
            views.setTextColor(R.id.wbv_left, context.getColor(if (over) R.color.widget_expense else R.color.widget_ink))
            views.setTextViewText(
                R.id.wbv_meta,
                if (over) {
                    context.getString(R.string.widget_budget_days_over, data.periodTag, data.budgetRatio - 100)
                } else {
                    context.getString(R.string.widget_budget_days_left, data.periodTag, data.budgetDaysLeft)
                },
            )
        }
        val budgetLink = link(context, NotificationLink.Budget, SLOT_BUDGET_V2)
        views.setOnClickPendingIntent(R.id.widget_root, budgetLink)
        views.setOnClickPendingIntent(R.id.wbv_ring_wrap, budgetLink)
        views.setOnClickPendingIntent(R.id.wbv_left, budgetLink)
        views.setOnClickPendingIntent(R.id.wbv_setup, budgetLink)
        return views
    }

    // ---- 消费节奏 ----

    /** 4×2（WG-R1）：近 30 天每日一格、四档深浅，最后一格是今天。 */
    private fun rhythmViews(context: Context, data: Snapshot?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_rhythm)
        views.setOnClickPendingIntent(R.id.widget_root, link(context, NotificationLink.Stats(data?.ledgerId), SLOT_RHYTHM))
        if (data == null || data.rhythm.size != RHYTHM_DAYS) {
            // 读不到数就整片不画：一片空格会被读成「这三十天一天都没花」，那是假数据。
            RHYTHM_IDS.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.wrh_today_ring, View.GONE)
            views.setTextViewText(R.id.wrh_foot, context.getString(R.string.widget_failure_hint))
            return views
        }
        val levels = heatLevels(data.rhythm)
        RHYTHM_IDS.forEachIndexed { index, id -> views.setImageViewResource(id, HEAT_DRAWABLES[levels[index]]) }
        views.setTextViewText(R.id.wrh_foot, rhythmFoot(context, data.rhythm))
        return views
    }

    /**
     * 四档阈值按窗口内当日值的分位数算，不写死金额——同样花八十块，
     * 对一个人是最高档、对另一个人是最低档，写死的阈值只会让整片要么全空要么全深。
     */
    private fun heatLevels(values: List<Long>): List<Int> {
        val nonzero = values.filter { it > 0L }.sorted()
        if (nonzero.size < HEAT_LEVELS) return values.map { if (it > 0L) HEAT_LEVELS - 1 else 0 }
        val low = percentile(nonzero, 34)
        val high = percentile(nonzero, 67)
        return values.map { value ->
            when {
                value <= 0L -> 0
                value <= low -> 1
                value <= high -> 2
                else -> 3
            }
        }
    }

    /** 第 p 百分位（最近秩）：窗口只有三十格，取整秩足够稳。 */
    private fun percentile(sorted: List<Long>, p: Int): Long {
        val rank = ((p.toLong() * sorted.size + 99) / 100).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** 底部那句结论：最近七天与再往前七天比。只描述节奏，不评价消费是否合理。 */
    private fun rhythmFoot(context: Context, rhythm: List<Long>): String {
        val recent = rhythm.takeLast(7).sum()
        val previous = rhythm.takeLast(14).dropLast(7).sum()
        val label = when {
            previous <= 0L -> context.getString(if (recent > 0L) R.string.widget_rhythm_up else R.string.widget_rhythm_flat)
            recent * 100 <= previous * 85 -> context.getString(R.string.widget_rhythm_down)
            recent * 100 >= previous * 115 -> context.getString(R.string.widget_rhythm_up)
            else -> context.getString(R.string.widget_rhythm_flat)
        }
        return "$label · ${context.getString(R.string.widget_rhythm_scale)}"
    }

    // ---- 连续记录 ----

    /** 2×2（WG-K1）：连续天数 + 「28 天里记了 N 天」+ 28 格记录，最后一格是今天。 */
    private fun streakViews(context: Context, data: Snapshot?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_streak)
        if (data == null) {
            views.setTextViewText(R.id.wsk_days, "—")
            views.setTextViewText(R.id.wsk_active, "")
            STREAK_IDS.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.wsk_today_ring, View.GONE)
            showFailure(views)
        } else {
            hideFailure(views)
            views.setTextViewText(R.id.wsk_days, data.streak.toString())
            views.setTextViewText(
                R.id.wsk_active,
                context.getString(R.string.widget_streak_active, data.activeDays.count { it > 0 }),
            )
            // 只有一笔的那天给浅一档：「记了 21 天」里哪几天只是顺手一笔记得出来。
            data.activeDays.take(STREAK_IDS.size).forEachIndexed { index, count ->
                val drawable = when {
                    count <= 0 -> R.drawable.widget_comb_off
                    count == 1 -> R.drawable.widget_comb_one
                    else -> R.drawable.widget_comb_on
                }
                views.setImageViewResource(STREAK_IDS[index], drawable)
            }
        }
        views.setOnClickPendingIntent(R.id.widget_root, link(context, NotificationLink.Home, SLOT_STREAK))
        return views
    }

    // ---- 共用小件 ----

    private fun showFailure(views: RemoteViews) {
        views.setViewVisibility(R.id.widget_note, View.VISIBLE)
    }

    private fun hideFailure(views: RemoteViews) {
        views.setViewVisibility(R.id.widget_note, View.GONE)
    }

    private fun staleTag(context: Context, tag: String): String =
        context.getString(R.string.widget_period_stale, tag)

    /** 主数字拆整数与小数：小数与货币符号都小一号，与已上线那两枚同一套排法。 */
    private fun split(minor: Long): Pair<String, String> {
        val text = formatAmount(minor)
        val dot = text.length - 3
        return text.substring(0, dot) to text.substring(dot)
    }

    private fun money(minor: Long): String = "¥" + formatAmount(minor)

    /** 取整到元并加千分位：2×2 的净宽放不下带角分的「¥18,500.00」。 */
    private fun moneyWhole(minor: Long): String {
        val negative = minor < 0
        val abs = if (negative) -minor else minor
        val yuan = (abs + 50) / 100
        val grouped = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
        return if (negative) "-¥$grouped" else "¥$grouped"
    }

    /** 点击一律走现成深链，组件里不做任何写操作。 */
    private fun link(context: Context, link: NotificationLink, slot: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(TallyNotifier.EXTRA_LINK_KEY, link.key)
            link.arg?.let { putExtra(TallyNotifier.EXTRA_LINK_ARG, it) }
        }
        return PendingIntent.getActivity(
            context,
            slot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---- 上一次成功的快照 ----

    private fun readSnapshot(context: Context): Snapshot? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SNAPSHOT, null)
            ?: return null
        return runCatching { cacheJson.decodeFromString<Snapshot>(raw) }.getOrNull()
    }

    private fun writeSnapshot(context: Context, data: Snapshot) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SNAPSHOT, cacheJson.encodeToString(data))
            .apply()
    }

    private val cacheJson = Json { ignoreUnknownKeys = true }

    private const val PREFS_NAME = "widget_state_v3"
    private const val KEY_SNAPSHOT = "snapshot"

    /** 消费节奏 30 格、连续蜂迹 28 格、深浅四档。 */
    private const val RHYTHM_DAYS = 30
    private const val STREAK_DAYS = 28
    private const val HEAT_LEVELS = 4

    /** 快速记账：4×2 七格分类（2×2 用同一份排序结果的前三个），都再带一格固定的「＋ 记一笔」。 */
    private const val QUICK_M_SLOTS = 7

    private val HEAT_DRAWABLES = intArrayOf(
        R.drawable.widget_heat_0,
        R.drawable.widget_heat_1,
        R.drawable.widget_heat_2,
        R.drawable.widget_heat_3,
    )

    private val RHYTHM_IDS = intArrayOf(
        R.id.wrh_c00, R.id.wrh_c01, R.id.wrh_c02, R.id.wrh_c03, R.id.wrh_c04,
        R.id.wrh_c05, R.id.wrh_c06, R.id.wrh_c07, R.id.wrh_c08, R.id.wrh_c09,
        R.id.wrh_c10, R.id.wrh_c11, R.id.wrh_c12, R.id.wrh_c13, R.id.wrh_c14,
        R.id.wrh_c15, R.id.wrh_c16, R.id.wrh_c17, R.id.wrh_c18, R.id.wrh_c19,
        R.id.wrh_c20, R.id.wrh_c21, R.id.wrh_c22, R.id.wrh_c23, R.id.wrh_c24,
        R.id.wrh_c25, R.id.wrh_c26, R.id.wrh_c27, R.id.wrh_c28, R.id.wrh_c29,
    )

    private val STREAK_IDS = intArrayOf(
        R.id.wsk_c00, R.id.wsk_c01, R.id.wsk_c02, R.id.wsk_c03, R.id.wsk_c04, R.id.wsk_c05, R.id.wsk_c06,
        R.id.wsk_c07, R.id.wsk_c08, R.id.wsk_c09, R.id.wsk_c10, R.id.wsk_c11, R.id.wsk_c12, R.id.wsk_c13,
        R.id.wsk_c14, R.id.wsk_c15, R.id.wsk_c16, R.id.wsk_c17, R.id.wsk_c18, R.id.wsk_c19, R.id.wsk_c20,
        R.id.wsk_c21, R.id.wsk_c22, R.id.wsk_c23, R.id.wsk_c24, R.id.wsk_c25, R.id.wsk_c26, R.id.wsk_c27,
    )

    /** 每格三个 View：整块（点击区）、图标、分类名。 */
    private val QUICK_S_IDS = arrayOf(
        intArrayOf(R.id.wqs_tile_0, R.id.wqs_icon_0, R.id.wqs_name_0),
        intArrayOf(R.id.wqs_tile_1, R.id.wqs_icon_1, R.id.wqs_name_1),
        intArrayOf(R.id.wqs_tile_2, R.id.wqs_icon_2, R.id.wqs_name_2),
    )

    private val QUICK_M_IDS = arrayOf(
        intArrayOf(R.id.wqm_tile_0, R.id.wqm_icon_0, R.id.wqm_name_0),
        intArrayOf(R.id.wqm_tile_1, R.id.wqm_icon_1, R.id.wqm_name_1),
        intArrayOf(R.id.wqm_tile_2, R.id.wqm_icon_2, R.id.wqm_name_2),
        intArrayOf(R.id.wqm_tile_3, R.id.wqm_icon_3, R.id.wqm_name_3),
        intArrayOf(R.id.wqm_tile_4, R.id.wqm_icon_4, R.id.wqm_name_4),
        intArrayOf(R.id.wqm_tile_5, R.id.wqm_icon_5, R.id.wqm_name_5),
        intArrayOf(R.id.wqm_tile_6, R.id.wqm_icon_6, R.id.wqm_name_6),
    )

    private const val SLOT_FS_HOME = 101
    private const val SLOT_FS_ADD = 102
    private const val SLOT_FM_HOME = 103
    private const val SLOT_Q_HOME = 104
    private const val SLOT_Q_ADD = 105
    private const val SLOT_BUDGET_V2 = 106
    private const val SLOT_RHYTHM = 107
    private const val SLOT_STREAK = 108
    private const val SLOT_Q_TILE_BASE = 110
}

/** 收支速览 2×2（WG-S1）。 */
class FlowSmallWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshAll(context)
}

/** 收支速览 4×2（WG-S2）。 */
class FlowWideWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshAll(context)
}

/** 快速记账 2×2（WG-Q1）。 */
class QuickSmallWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshAll(context)
}

/** 快速记账 4×2（WG-Q2）。 */
class QuickWideWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshAll(context)
}

/** 预算环新样式 2×2（WG-B1 / WG-B2）。 */
class BudgetRingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshAll(context)
}

/** 消费节奏 4×2（WG-R1）。 */
class RhythmWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshAll(context)
}

/** 记账连续记录 2×2（WG-K1）。 */
class StreakWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refreshAll(context)
}

/** 七枚共用一次刷新：桌面上摆几枚也只查一次库。 */
private fun AppWidgetProvider.refreshAll(context: Context) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            InsightWidgets.updateAll(context.applicationContext)
        } finally {
            pending.finish()
        }
    }
}
