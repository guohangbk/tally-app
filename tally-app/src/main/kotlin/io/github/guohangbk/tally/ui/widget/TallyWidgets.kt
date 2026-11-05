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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「近七日」桌面组件（WG-06，4×2）的取数与刷新。
 *
 * 组件只读：算出含今天的 7 天日支出后写进 RemoteViews，不碰任何写库路径。
 * 刷新只有三个来源——组件自己被系统要求更新、App 回到前台、每日那次
 * [io.github.guohangbk.tally.work.AutomationWorker]。桌面上没有组件时一次库都不查。
 *
 * 取数失败与「这几天真的没花钱」必须分开：失败时用 SharedPreferences 里上一次成功的数字并标
 * 「· 未更新」，一次都没成功过才显示「—」与「数据没读到」——绝不拿 ¥0.00 冒充（WG-04）。
 */
object TallyWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 前台/后台的即发即忘入口：没有组件实例时连协程都不起。 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        if (manager.getAppWidgetIds(ComponentName(app, SevenDayWidgetProvider::class.java)).isEmpty()) return
        scope.launch { runCatching { updateAll(app) } }
    }

    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        val graph = TallyApplication.from(app).graph
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, SevenDayWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val fresh = runCatching { loadWeek(graph, System.currentTimeMillis()) }.getOrNull()
        val cached = readWeek(app)
        if (fresh != null) writeWeek(app, fresh)
        val data = fresh ?: cached
        val views = sevenDayViews(app, data, stale = fresh == null && cached != null)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    /** 近七日这一份数据：含今天的 7 天支出（从左到右），以及点进统计要带的那本账。 */
    private data class WeekData(
        /** 今天的 0 点时间戳，用来推每一列的日期标签。 */
        val todayStart: Long,
        val week: List<Long>,
        /** 这一份数算的是哪本账：点组件进统计要带同一本，否则组件与统计各说一个数。 */
        val ledgerId: String?,
    )

    private suspend fun loadWeek(graph: AppGraph, now: Long): WeekData {
        val todayStart = Period.dayRange(now, now).startAt
        val ledgerId = graph.billRepository.defaultLedgerId()
        val weekRange = PeriodRange(Period.shiftDays(todayStart, -6), Period.shiftDays(todayStart, 1))
        val week = graph.billRepository.observeMonth(ledgerId, weekRange).first()
        val byDay = week.days.associateBy { it.dayStart }
        val weekValues = (0..6).map { offset ->
            byDay[Period.shiftDays(todayStart, (offset - 6).toLong())]?.expenseMinor ?: 0L
        }
        return WeekData(ledgerId = ledgerId, todayStart = todayStart, week = weekValues)
    }

    /**
     * 4×2「近七日柱状图」（WG-06）：标题用品牌绿、合计 / 日均各放大一档，中间 7 根柱按权重铺满图表区，
     * 今天陶土、其余墨色，0 支出压到底条。数据与首页同一份日聚合（只算净支出）。
     */
    private fun sevenDayViews(context: Context, data: WeekData?, stale: Boolean): RemoteViews {
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

    private fun readWeek(context: Context): WeekData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val week = prefs.getString(KEY_WEEK, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?: return null
        if (week.size != 7) return null
        return WeekData(
            todayStart = prefs.getLong(KEY_TODAY_START, 0L),
            week = week,
            ledgerId = prefs.getString(KEY_LEDGER, null),
        )
    }

    private fun writeWeek(context: Context, data: WeekData) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TODAY_START, data.todayStart)
            .putString(KEY_WEEK, data.week.joinToString(","))
            .putString(KEY_LEDGER, data.ledgerId)
            .apply()
    }

    private const val PREFS_NAME = "widget_state"
    private const val KEY_TODAY_START = "today_start"
    private const val KEY_WEEK = "week"
    private const val KEY_LEDGER = "ledger"

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
