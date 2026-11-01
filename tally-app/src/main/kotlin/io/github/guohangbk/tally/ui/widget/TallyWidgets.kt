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
import io.github.guohangbk.tally.data.notify.NotificationLink
import io.github.guohangbk.tally.data.notify.TallyNotifier
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BudgetPeriod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 三枚桌面组件共用一次刷新（WG-06 近七日 / WG-07 预算环 / WG-08 当日当月）。
 *
 * 组件只读：算出「今天 + 近 7 天 + 本月支出」与「本月总额预算」后写进 RemoteViews，不碰任何写库路径。
 * 刷新只有三个来源——组件自己被系统要求更新、App 回到前台、每日那次
 * [io.github.guohangbk.tally.work.AutomationWorker]。桌面上没有组件时一次库都不查。
 *
 * 取数失败与「今天真的没花钱」必须分开：失败时用 SharedPreferences 里上一次成功的数字并标
 * 「· 未更新」，一次都没成功过才显示「—」与「数据没读到」——绝不拿 ¥0.00 冒充（WG-04）。
 */
object TallyWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 前台/后台的即发即忘入口：没有组件实例时连协程都不起。 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        if (!hasInstances(app, manager)) return
        scope.launch { runCatching { updateAll(app) } }
    }

    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        val graph = TallyApplication.from(app).graph
        val manager = AppWidgetManager.getInstance(app)
        val sevenIds = manager.getAppWidgetIds(ComponentName(app, SevenDayWidgetProvider::class.java))
        val dayMonthIds = manager.getAppWidgetIds(ComponentName(app, DayMonthWidgetProvider::class.java))
        val budgetIds = manager.getAppWidgetIds(ComponentName(app, BudgetWidgetProvider::class.java))
        if (sevenIds.isEmpty() && dayMonthIds.isEmpty() && budgetIds.isEmpty()) return

        val now = System.currentTimeMillis()
        // 每月起始日读不到就整轮当失败：月界算错比显示「未更新」更糟。
        val startDay = runCatching { graph.settingRepository.monthStartDay() }.getOrNull()
        val monthRange = startDay?.let { Period.monthRange(now, it) }

        // 「近七日 / 当日当月」共用今天那一份数据，只 load 一次。
        if ((sevenIds + dayMonthIds).isNotEmpty()) {
            val fresh = if (monthRange == null) null else runCatching { loadToday(graph, now, monthRange) }.getOrNull()
            val cached = readToday(app)
            if (fresh != null) writeToday(app, fresh)
            val data = fresh ?: cached
            val stale = fresh == null && cached != null
            if (sevenIds.isNotEmpty()) {
                val views = sevenDayViews(app, data, stale)
                sevenIds.forEach { manager.updateAppWidget(it, views) }
            }
            if (dayMonthIds.isNotEmpty()) {
                val views = dayMonthViews(app, data, stale)
                dayMonthIds.forEach { manager.updateAppWidget(it, views) }
            }
        }
        // 预算环那一份本月数据（含预算）单独 load。
        if (budgetIds.isNotEmpty()) {
            val fresh = if (monthRange == null || startDay == null) null else runCatching { loadMonth(app, graph, monthRange, startDay) }.getOrNull()
            val cached = readMonth(app)
            if (fresh != null) writeMonth(app, fresh)
            val data = fresh ?: cached
            val stale = fresh == null && cached != null
            val views = budgetViews(app, data, stale)
            budgetIds.forEach { manager.updateAppWidget(it, views) }
        }
    }

    private fun hasInstances(app: Context, manager: AppWidgetManager): Boolean =
        listOf(
            SevenDayWidgetProvider::class.java,
            DayMonthWidgetProvider::class.java,
            BudgetWidgetProvider::class.java,
        ).any { manager.getAppWidgetIds(ComponentName(app, it)).isNotEmpty() }

    private data class TodayData(
        val todayExpense: Long,
        val todayCount: Int,
        val monthExpense: Long,
        /** 今天的 0 点时间戳，用来推“近七日”每一列的日期标签。 */
        val todayStart: Long,
        /** 含今天的 7 天支出，从左到右。 */
        val week: List<Long>,
        /** 这一份数算的是哪本账：点组件进统计要带同一本，否则组件与统计各说一个数。 */
        val ledgerId: String?,
    )

    /** 预算环（WG-07）那一份本月数据：旧版概览的收支结余三栏随组件一起退了。 */
    private data class MonthData(
        val tag: String,
        /** 预算条：没有设预算时 `hasBudget` 为 false，整格换成「未设预算 · 点这里设置」。 */
        val hasBudget: Boolean = false,
        val budgetAmount: Long = 0L,
        val budgetUsed: Long = 0L,
        /** 已用百分比，超支时大于 100。 */
        val budgetRatio: Int = 0,
        val budgetAlert: Int = 80,
        val budgetRemainingDays: Int = 0,
    )

    private suspend fun loadToday(graph: AppGraph, now: Long, monthRange: PeriodRange): TodayData {
        val todayStart = Period.dayRange(now, now).startAt
        val ledgerId = graph.billRepository.defaultLedgerId()
        val weekRange = PeriodRange(Period.shiftDays(todayStart, -6), Period.shiftDays(todayStart, 1))
        val week = graph.billRepository.observeMonth(ledgerId, weekRange).first()
        val month = graph.billRepository.observeMonth(ledgerId, monthRange).first()
        val byDay = week.days.associateBy { it.dayStart }
        val today = byDay[todayStart]
        val weekValues = (0..6).map { offset ->
            byDay[Period.shiftDays(todayStart, (offset - 6).toLong())]?.expenseMinor ?: 0L
        }
        return TodayData(
            ledgerId = ledgerId,
            todayExpense = today?.expenseMinor ?: 0L,
            todayCount = today?.bills?.size ?: 0,
            monthExpense = month.summary.expenseMinor,
            todayStart = todayStart,
            week = weekValues,
        )
    }

    private suspend fun loadMonth(
        context: Context,
        graph: AppGraph,
        range: PeriodRange,
        startDay: Int,
    ): MonthData {
        val ledgerId = graph.billRepository.defaultLedgerId()
        // 预算只取总额、只取月预算——分类预算在预算页与统计页看，组件上放不下。
        val budget = ledgerId?.let { ledger ->
            runCatching {
                graph.budgetRepository.observeSummary(ledger, BudgetPeriod.MONTHLY, startDay).first().total
            }.getOrNull()
        }
        return MonthData(
            tag = periodTag(context, range, startDay),
            hasBudget = budget != null,
            budgetAmount = budget?.item?.amountMinor ?: 0L,
            budgetUsed = budget?.usedMinor ?: 0L,
            budgetRatio = budget?.let { ((it.usedMinor.toFloat() / it.item.amountMinor) * 100).toInt() } ?: 0,
            budgetAlert = budget?.item?.alertRatio ?: 80,
            budgetRemainingDays = budget?.remainingDays ?: 0,
        )
    }

    /** 周期标签：1 号起写「10 月」，改了起始日写「10/15–11/14」。 */
    private fun periodTag(context: Context, range: PeriodRange, startDay: Int): String =
        if (Period.normalizeStartDay(startDay) == Period.DEFAULT_START_DAY) {
            val month = Instant.ofEpochMilli(range.startAt).atZone(ZoneId.systemDefault()).toLocalDate().monthValue
            context.getString(R.string.widget_month_tag, month)
        } else {
            Period.label(range, startDay)
        }

    /**
     * 4×2「近七日柱状图」（WG-06）：标题用品牌绿、合计 / 日均各放大一档，中间 7 根柱按权重铺满图表区，
     * 今天陶土、其余墨色，0 支出压到底条。数据与首页同一份日聚合（只算净支出）。
     */
    private fun sevenDayViews(context: Context, data: TodayData?, stale: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_seven_day)
        if (data == null) {
            views.setTextViewText(R.id.widget_sd_period, "")
            views.setTextViewText(R.id.widget_sd_total, "—")
            views.setTextViewText(R.id.widget_sd_avg, "—")
            views.setViewVisibility(R.id.widget_sd_chart, View.GONE)
            SD_DAY_IDS.forEach { views.setTextViewText(it, "") }
            views.setViewVisibility(R.id.widget_note, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_sd_chart, View.VISIBLE)
            views.setViewVisibility(R.id.widget_note, View.GONE)
            views.setTextViewText(
                R.id.widget_sd_period,
                if (stale) {
                    context.getString(R.string.widget_period_stale, sdPeriodLabel(context, data.todayStart))
                } else {
                    sdPeriodLabel(context, data.todayStart)
                },
            )
            val total = data.week.sum()
            views.setTextViewText(R.id.widget_sd_total, formatAmount(total))
            views.setTextViewText(R.id.widget_sd_avg, formatYuan(if (total > 0L) total / 7 else 0L))
            views.setSevenDayBars(data.week)
            SD_DAY_IDS.forEachIndexed { index, id ->
                val label = if (index == TODAY_BAR_INDEX) {
                    context.getString(R.string.widget_foot_today)
                } else {
                    weekdayCn(Period.shiftDays(data.todayStart, (index - 6).toLong()))
                }
                views.setTextViewText(id, label)
                views.setTextColor(
                    id,
                    context.getColor(if (index == TODAY_BAR_INDEX) R.color.widget_expense else R.color.widget_ink3),
                )
            }
        }
        views.setOnClickPendingIntent(R.id.widget_root, linkIntent(context, NotificationLink.Home, SLOT_HOME))
        views.setOnClickPendingIntent(R.id.widget_sd_chart, linkIntent(context, NotificationLink.Stats(data?.ledgerId), SLOT_STATS))
        return views
    }

    /** 2×2「预算圆环」（WG-07）：环 = 已用占比，中心百分比，下面只一个结论数（剩余 / 超出）。 */
    private fun budgetViews(context: Context, data: MonthData?, stale: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_budget)
        views.setTextViewText(
            R.id.widget_period,
            when {
                data == null -> ""
                stale -> context.getString(R.string.widget_period_stale, data.tag)
                else -> data.tag
            },
        )
        val rings = intArrayOf(R.id.widget_budget_ring_green, R.id.widget_budget_ring_gold, R.id.widget_budget_ring_expense)
        rings.forEach { views.setViewVisibility(it, View.GONE) }
        if (data == null) {
            views.setViewVisibility(R.id.widget_budget_ring_wrap, View.GONE)
            views.setViewVisibility(R.id.widget_budget_left, View.GONE)
            views.setViewVisibility(R.id.widget_budget_setup, View.GONE)
            views.setViewVisibility(R.id.widget_note, View.VISIBLE)
        } else if (!data.hasBudget || data.budgetAmount <= 0L) {
            views.setViewVisibility(R.id.widget_budget_ring_wrap, View.GONE)
            views.setViewVisibility(R.id.widget_budget_left, View.GONE)
            views.setViewVisibility(R.id.widget_budget_setup, View.VISIBLE)
            views.setViewVisibility(R.id.widget_note, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_budget_ring_wrap, View.VISIBLE)
            views.setViewVisibility(R.id.widget_budget_left, View.VISIBLE)
            views.setViewVisibility(R.id.widget_budget_setup, View.GONE)
            views.setViewVisibility(R.id.widget_note, View.GONE)
            val over = data.budgetUsed > data.budgetAmount
            views.setTextViewText(R.id.widget_budget_percent, context.getString(R.string.widget_budget_percent_fmt, data.budgetRatio))
            views.setTextColor(R.id.widget_budget_percent, context.getColor(if (over) R.color.widget_expense else R.color.widget_ink))
            val percent = data.budgetRatio.coerceIn(0, 100)
            rings.forEach { views.setInt(it, "setProgress", percent) }
            val tier = when {
                over -> R.id.widget_budget_ring_expense
                data.budgetRatio >= data.budgetAlert -> R.id.widget_budget_ring_gold
                else -> R.id.widget_budget_ring_green
            }
            views.setViewVisibility(tier, View.VISIBLE)
            val gap = if (over) data.budgetUsed - data.budgetAmount else data.budgetAmount - data.budgetUsed
            views.setTextViewText(
                R.id.widget_budget_left,
                context.getString(if (over) R.string.widget_budget_over else R.string.widget_budget_remaining, "¥${formatYuan(gap)}"),
            )
            views.setTextColor(R.id.widget_budget_left, context.getColor(if (over) R.color.widget_expense else R.color.widget_ink))
        }
        views.setOnClickPendingIntent(R.id.widget_root, linkIntent(context, NotificationLink.Budget, SLOT_BUDGET))
        views.setOnClickPendingIntent(R.id.widget_budget_ring_wrap, linkIntent(context, NotificationLink.Budget, SLOT_BUDGET))
        views.setOnClickPendingIntent(R.id.widget_budget_left, linkIntent(context, NotificationLink.Budget, SLOT_BUDGET))
        views.setOnClickPendingIntent(R.id.widget_budget_setup, linkIntent(context, NotificationLink.Budget, SLOT_BUDGET))
        return views
    }

    /** 2×2「当日 + 当月」（WG-08）：顶栏日期 + 今日支出主数字 + 本月支出副行。 */
    private fun dayMonthViews(context: Context, data: TodayData?, stale: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_day_month)
        val date = if (data == null) "" else {
            val day = Period.toDate(data.todayStart)
            "${day.monthValue}月${day.dayOfMonth}日 周${weekdayCn(data.todayStart)}" +
                if (stale) " · ${context.getString(R.string.widget_foot_stale_suffix)}" else ""
        }
        views.setTextViewText(R.id.widget_date, date)
        if (data == null) {
            views.setTextViewText(R.id.widget_today_int, "—")
            views.setTextColor(R.id.widget_today_int, context.getColor(R.color.widget_ink3))
            views.setViewVisibility(R.id.widget_today_currency, View.GONE)
            views.setViewVisibility(R.id.widget_today_dec, View.GONE)
            views.setTextViewText(R.id.widget_month_value, "—")
            views.setViewVisibility(R.id.widget_note, View.VISIBLE)
        } else {
            val (integer, fraction) = splitAmount(data.todayExpense)
            val mainColor = context.getColor(if (data.todayCount == 0) R.color.widget_ink else R.color.widget_expense)
            views.setTextViewText(R.id.widget_today_int, integer)
            views.setTextViewText(R.id.widget_today_dec, fraction)
            views.setTextColor(R.id.widget_today_int, mainColor)
            views.setTextColor(R.id.widget_today_dec, mainColor)
            views.setTextColor(R.id.widget_today_currency, mainColor)
            views.setViewVisibility(R.id.widget_today_currency, View.VISIBLE)
            views.setViewVisibility(R.id.widget_today_dec, View.VISIBLE)
            views.setTextViewText(R.id.widget_month_value, formatAmount(data.monthExpense))
            views.setViewVisibility(R.id.widget_note, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.widget_root, linkIntent(context, NotificationLink.Stats(data?.ledgerId), SLOT_STATS))
        views.setOnClickPendingIntent(R.id.widget_add, linkIntent(context, NotificationLink.NewBill, SLOT_ADD))
        return views
    }

    /** 中文星期单字（Mon=一 … Sun=日）。 */
    private fun weekdayCn(epochMillis: Long): String = WEEKDAY_CN[Period.toDate(epochMillis).dayOfWeek.value - 1]

    /** 「近七日」区间标签：M/d – M/d。 */
    private fun sdPeriodLabel(context: Context, todayStart: Long): String {
        val start = Period.toDate(Period.shiftDays(todayStart, -6))
        val end = Period.toDate(todayStart)
        return context.getString(
            R.string.widget_sd_period_range,
            "${start.monthValue}/${start.dayOfMonth}",
            "${end.monthValue}/${end.dayOfMonth}",
        )
    }

    /**
     * 近七日柱状图（WG-06）的柱子：三档各占图表区 30% / 60% / 满格，权重在布局里，
     * 所以柱高跟着组件实际高度走，不再固定 26dp（2026-10-28 走查：组件看着很矮）。
     * 每档前面那条空段负责垫高，满档铺满所以没有空段；今天那根陶土（t1/t2/t3），其余墨色；0 支出只留 4dp 底条。
     */
    private fun RemoteViews.setSevenDayBars(values: List<Long>) {
        val max = values.maxOrNull() ?: 0L
        values.forEachIndexed { index, value ->
            val slots = SD_BAR_IDS[index]
            val spacers = SD_SPACER_IDS[index]
            val todaySlots = if (index == TODAY_BAR_INDEX) SD_TODAY_BAR_IDS else null
            slots.forEach { setViewVisibility(it, View.GONE) }
            spacers.forEach { setViewVisibility(it, View.GONE) }
            todaySlots?.forEach { setViewVisibility(it, View.GONE) }
            val level = when {
                value <= 0L || max <= 0L -> 0
                else -> ((value * MAX_BAR_LEVEL + max - 1) / max).toInt().coerceIn(1, MAX_BAR_LEVEL)
            }
            if (level == 0) {
                setViewVisibility(slots[0], View.VISIBLE)
                return@forEachIndexed
            }
            // 满档铺整段，所以只有前两档需要前面那条垫高的空段。
            if (level < MAX_BAR_LEVEL) setViewVisibility(spacers[level - 1], View.VISIBLE)
            if (todaySlots != null) {
                setViewVisibility(todaySlots[level - 1], View.VISIBLE)
            } else {
                setViewVisibility(slots[level], View.VISIBLE)
            }
        }
    }

    /** 主数字拆成整数与小数两段：小数与货币符号都要小一号。 */
    private fun splitAmount(minor: Long): Pair<String, String> {
        val text = formatAmount(minor)
        val dot = text.length - 3
        return text.substring(0, dot) to text.substring(dot)
    }

    /** 副栏取整到元：250dp 宽放不下三栏都带角分。 */
    private fun formatYuan(minor: Long): String {
        val negative = minor < 0
        val abs = if (negative) -minor else minor
        val yuan = (abs + 50) / 100
        val digits = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
        return if (negative) "-$digits" else digits
    }

    /**
     * 组件点击落回 Activity，再走通知那套 pendingLink：冷启动与已在前台共用一条路。
     * requestCode 按「槽位」分，同一个壳刷新多次也只更新同一条 PendingIntent。
     */
    private fun linkIntent(context: Context, link: NotificationLink, slot: Int): PendingIntent {
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

    // ---- 上一次成功的数字：读不到库时用它顶住，角落标「· 未更新」 ----

    private fun readToday(context: Context): TodayData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TODAY_MONTH)) return null
        val week = prefs.getString(KEY_TODAY_WEEK, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?: return null
        if (week.size != 7) return null
        return TodayData(
            todayExpense = prefs.getLong(KEY_TODAY_EXPENSE, 0L),
            todayCount = prefs.getInt(KEY_TODAY_COUNT, 0),
            monthExpense = prefs.getLong(KEY_TODAY_MONTH, 0L),
            todayStart = prefs.getLong(KEY_TODAY_START, 0L),
            week = week,
            ledgerId = prefs.getString(KEY_TODAY_LEDGER, null),
        )
    }

    private fun writeToday(context: Context, data: TodayData) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TODAY_EXPENSE, data.todayExpense)
            .putInt(KEY_TODAY_COUNT, data.todayCount)
            .putLong(KEY_TODAY_MONTH, data.monthExpense)
            .putLong(KEY_TODAY_START, data.todayStart)
            .putString(KEY_TODAY_WEEK, data.week.joinToString(","))
            .putString(KEY_TODAY_LEDGER, data.ledgerId)
            .apply()
    }

    private fun readMonth(context: Context): MonthData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_MONTH_TAG)) return null
        return MonthData(
            tag = prefs.getString(KEY_MONTH_TAG, null).orEmpty(),
            hasBudget = prefs.getBoolean(KEY_MONTH_HAS_BUDGET, false),
            budgetAmount = prefs.getLong(KEY_BUDGET_AMOUNT, 0L),
            budgetUsed = prefs.getLong(KEY_BUDGET_USED, 0L),
            budgetRatio = prefs.getInt(KEY_BUDGET_RATIO, 0),
            budgetAlert = prefs.getInt(KEY_BUDGET_ALERT, 80),
            budgetRemainingDays = prefs.getInt(KEY_BUDGET_DAYS, 0),
        )
    }

    private fun writeMonth(context: Context, data: MonthData) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MONTH_TAG, data.tag)
            .putBoolean(KEY_MONTH_HAS_BUDGET, data.hasBudget)
            .putLong(KEY_BUDGET_AMOUNT, data.budgetAmount)
            .putLong(KEY_BUDGET_USED, data.budgetUsed)
            .putInt(KEY_BUDGET_RATIO, data.budgetRatio)
            .putInt(KEY_BUDGET_ALERT, data.budgetAlert)
            .putInt(KEY_BUDGET_DAYS, data.budgetRemainingDays)
            .apply()
    }

    private const val PREFS_NAME = "widget_state"
    private const val KEY_TODAY_EXPENSE = "today_expense"
    private const val KEY_TODAY_COUNT = "today_count"
    private const val KEY_TODAY_MONTH = "today_month"
    private const val KEY_TODAY_WEEK = "today_week"
    private const val KEY_TODAY_START = "today_start"
    private const val KEY_TODAY_LEDGER = "today_ledger"
    private const val KEY_MONTH_TAG = "month_tag"
    private const val KEY_MONTH_HAS_BUDGET = "month_has_budget"
    private const val KEY_BUDGET_AMOUNT = "budget_amount"
    private const val KEY_BUDGET_USED = "budget_used"
    private const val KEY_BUDGET_RATIO = "budget_ratio"
    private const val KEY_BUDGET_ALERT = "budget_alert"
    private const val KEY_BUDGET_DAYS = "budget_days"

    private const val TODAY_BAR_INDEX = 6

    private val WEEKDAY_CN = arrayOf("一", "二", "三", "四", "五", "六", "日")

    private val SD_DAY_IDS = intArrayOf(
        R.id.widget_sd_day_0,
        R.id.widget_sd_day_1,
        R.id.widget_sd_day_2,
        R.id.widget_sd_day_3,
        R.id.widget_sd_day_4,
        R.id.widget_sd_day_5,
        R.id.widget_sd_day_6,
    )

    /** 每列的档位视图：[0] 是 0 支出那条底条，[1..3] 是三档墨色柱；今天那列只有底条，柱走 [SD_TODAY_BAR_IDS]。 */
    private val SD_BAR_IDS = arrayOf(
        intArrayOf(R.id.widget_sd_bar_0_zero, R.id.widget_sd_bar_0_l1, R.id.widget_sd_bar_0_l2, R.id.widget_sd_bar_0_l3),
        intArrayOf(R.id.widget_sd_bar_1_zero, R.id.widget_sd_bar_1_l1, R.id.widget_sd_bar_1_l2, R.id.widget_sd_bar_1_l3),
        intArrayOf(R.id.widget_sd_bar_2_zero, R.id.widget_sd_bar_2_l1, R.id.widget_sd_bar_2_l2, R.id.widget_sd_bar_2_l3),
        intArrayOf(R.id.widget_sd_bar_3_zero, R.id.widget_sd_bar_3_l1, R.id.widget_sd_bar_3_l2, R.id.widget_sd_bar_3_l3),
        intArrayOf(R.id.widget_sd_bar_4_zero, R.id.widget_sd_bar_4_l1, R.id.widget_sd_bar_4_l2, R.id.widget_sd_bar_4_l3),
        intArrayOf(R.id.widget_sd_bar_5_zero, R.id.widget_sd_bar_5_l1, R.id.widget_sd_bar_5_l2, R.id.widget_sd_bar_5_l3),
        intArrayOf(R.id.widget_sd_bar_6_zero),
    )

    /** 每档柱前面垫高的空段（7/10 与 4/10）：与柱的权重合起来正好铺满一列，柱高于是跟着组件高度走。 */
    private val SD_SPACER_IDS = arrayOf(
        intArrayOf(R.id.widget_sd_bar_0_s1, R.id.widget_sd_bar_0_s2),
        intArrayOf(R.id.widget_sd_bar_1_s1, R.id.widget_sd_bar_1_s2),
        intArrayOf(R.id.widget_sd_bar_2_s1, R.id.widget_sd_bar_2_s2),
        intArrayOf(R.id.widget_sd_bar_3_s1, R.id.widget_sd_bar_3_s2),
        intArrayOf(R.id.widget_sd_bar_4_s1, R.id.widget_sd_bar_4_s2),
        intArrayOf(R.id.widget_sd_bar_5_s1, R.id.widget_sd_bar_5_s2),
        intArrayOf(R.id.widget_sd_bar_6_s1, R.id.widget_sd_bar_6_s2),
    )

    private val SD_TODAY_BAR_IDS = intArrayOf(
        R.id.widget_sd_bar_6_t1,
        R.id.widget_sd_bar_6_t2,
        R.id.widget_sd_bar_6_t3,
    )

    /** 柱的档数：RemoteViews 改不了权重，所以一列里预置这么多档，按当日占比取最近一档。 */
    private const val MAX_BAR_LEVEL = 3

    private const val SLOT_HOME = 1
    private const val SLOT_STATS = 2
    private const val SLOT_ADD = 3
    private const val SLOT_BUDGET = 4
}

/** 近七日柱状图组件（4×2，WG-06）。 */
class SevenDayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TallyWidgets.updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 预算圆环组件（2×2，WG-07）。 */
class BudgetWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TallyWidgets.updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 当日 + 当月组件（2×2，WG-08）。 */
class DayMonthWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TallyWidgets.updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
