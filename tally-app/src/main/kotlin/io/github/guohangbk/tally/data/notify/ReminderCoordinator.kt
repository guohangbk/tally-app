package io.github.guohangbk.tally.data.notify

import io.github.guohangbk.tally.data.automation.AutomationService
import io.github.guohangbk.tally.data.db.dao.BillDao
import io.github.guohangbk.tally.data.db.dao.InstallmentPlanDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.dao.RecurringRuleDao
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.data.repository.BudgetRepository
import io.github.guohangbk.tally.data.repository.SettingRepository
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.AutomationEntry
import io.github.guohangbk.tally.domain.model.AutomationOutcome
import io.github.guohangbk.tally.domain.model.BudgetPeriod
import io.github.guohangbk.tally.domain.model.BudgetUsage
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.CatchUpResult
import io.github.guohangbk.tally.domain.schedule.InstallmentSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * 四类提醒的评估与发送。三条不变量都收在这个类里：
 *
 * 1. **一件事每天最多一条**。任务级用两张自动化表上的 `last_reminded_at`，
 *    全机级（未记账）用 DataStore 上的日期游标；回到前台重跑一次不会轰第二遍。
 *    游标只在**真的发了**之后写——开关关掉时不写，否则用户明天打开开关，
 *    今天这一条就被昨天没发出去的那次消费掉了。
 * 2. **提醒不是执行条件**。补执行先跑完、结果已经落库，这里只负责说话；
 *    发送本身异常不改变任何东西（[AutomationService.catchUp] 的成败与它无关）。
 * 3. **同类合并成一条**。一次跑五条规则只发一条「5 笔已记入」，
 *    只有一条时才用任务自己的名字说话（「房租已记入」）；失败更是无论几条都只发一条。
 *
 * 不建通知表、不建消息中心：四类全部是「到点了拿本地数据算一下」，算完就发、发完就忘。
 */
class ReminderCoordinator(
    private val automationService: AutomationService,
    private val billDao: BillDao,
    private val ruleDao: RecurringRuleDao,
    private val planDao: InstallmentPlanDao,
    private val ledgerDao: LedgerDao,
    private val budgetRepository: BudgetRepository,
    private val settingRepository: SettingRepository,
    private val notifier: TallyNotifier,
    private val preferences: TallyPreferences,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * 后台唯一入口：补执行 + 提醒。启动、回到前台与 WorkManager 都调这一个方法，
     * 把两步拆给调用方各写一遍，迟早出现「补了账没发通知」。
     */
    suspend fun runBackground(): CatchUpResult {
        val result = automationService.catchUp()
        // 预算自动续期：进入新的每月周期、本月还没跑过时，把上月开了续期的预算复制过来。不依赖开没开预算页。
        runCatching { ensureBudgetRollover() }
            .onFailure { DebugLog.log("通知", "预算续期失败：${it.message ?: it.javaClass.simpleName}") }
        // 提醒算失败不影响已经落库的补执行，但要在控制台留一句，否则「没收到通知」无从查起。
        runCatching { notify(result, LocalDate.now(zoneId())) }
            .onFailure { DebugLog.log("通知", "提醒评估失败：${it.message ?: it.javaClass.simpleName}") }
        return result
    }

    /** 每月只续期一次：拿当前月周期段的 ISO 当游标，同月再进 App 不重复复制（删光本月后也不会又长回来）。 */
    private suspend fun ensureBudgetRollover() {
        val startDay = runCatching { settingRepository.monthStartDay() }.getOrNull() ?: Period.DEFAULT_START_DAY
        val segment = Period.toDate(Period.monthRange(now(), startDay, zoneId()).startAt, zoneId()).toString()
        if (preferences.lastBudgetRolloverAt() == segment) return
        budgetRepository.rolloverMonthly(startDay, now())
        preferences.markBudgetRolloverAt(segment)
    }

    /** 回收站清理的结果（欠的那条通知），只在真的清了东西时发。 */
    suspend fun postCleanup(purgedBills: Int, purgedAttachments: Int, freedBytes: Long) {
        if (purgedBills + purgedAttachments == 0) return
        val settings = preferences.settings.first()
        if (!settings.notificationsEnabled || !notifier.canPost) return
        val freed = (freedBytes + 1023) / 1024
        val title = if (purgedBills > 0) "回收站清理了 $purgedBills 笔" else "回收了 $purgedAttachments 张凭证"
        notifier.post(
            TallyNotice(
                channel = TallyChannel.SYSTEM,
                title = title,
                text = "释放 $freed KB · 到期后不再能找回",
                publicText = title,
                tag = "${TAG_CLEANUP}_${LocalDate.now(zoneId())}",
                link = NotificationLink.Recycle,
            ),
        )
    }

    private suspend fun notify(result: CatchUpResult, today: LocalDate) {
        val settings = preferences.settings.first()
        // 权限没给就整批跳过：不发也就不写游标，用户授权后当天该补的还能补上。
        if (!settings.notificationsEnabled || !notifier.canPost) return
        val reminders = buildList {
            // 没有失败或这一类被关掉，都把手上那条收掉：挂着一条「已经解决」的通知比不发更坏。
            val failures = result.entriesOf(AutomationOutcome.FAILED).takeIf { settings.notifyFailure }
            if (failures.isNullOrEmpty()) notifier.cancel(TAG_FAILURE) else add(failureReminder(failures, settings))
            addAll(generatedReminders(result.entriesOf(AutomationOutcome.GENERATED), settings, today))
            result.entriesOf(AutomationOutcome.PENDING).takeIf { it.isNotEmpty() }
                ?.let { add(pendingReminder(it, settings, today)) }
            addAll(upcomingReminders(settings, today))
            addAll(budgetReminders(settings, today))
            unloggedReminder(settings, today)?.let { add(it) }
        }
        reminders.forEach { send(it) }
    }

    // ---- 四类提醒 --------------------------------------------------------

    /**
     * 「已记入」按来源各发一条：周期与分期是两个独立开关，混在一条里就没法只关一边。
     * 单条用任务名，多条合并，点达分别落到那一笔的详情与对应的列表页。
     */
    private suspend fun generatedReminders(
        entries: List<AutomationEntry>,
        settings: TallySettings,
        today: LocalDate,
    ): List<Reminder> = entries.groupBy { it.source }.mapNotNull { (source, group) ->
        val enabled = when (source) {
            BillSource.RECURRING -> settings.notifyRecurring
            BillSource.INSTALLMENT -> settings.notifyInstallment
            else -> false
        }
        if (!enabled) return@mapNotNull null
        val one = group.singleOrNull()
        val periods = group.sumOf { it.count }
        val text = if (one == null) {
            "$periods 笔 · ¥${formatAmount(group.sumOf { it.amountMinor })}"
        } else {
            "¥${formatAmount(one.amountMinor)} · ${ledgerName(one)} · ${dayLabel(one.occurredAt)}"
        }
        val ids = group.splitIds()
        Reminder(
            notice = TallyNotice(
                channel = TallyChannel.AUTOMATION,
                title = if (one == null) "$periods 笔自动账单已记入" else "${one.name}已记入",
                text = text,
                publicText = lockText(settings, text, "已自动记入 $periods 笔"),
                tag = "${TAG_GENERATED}_${source.name.lowercase()}_$today",
                link = one?.billId?.let { NotificationLink.BillDetail(it) } ?: listLink(source),
            ),
            ruleIds = ids.first,
            planIds = ids.second,
        )
    }

    /** 超过自动阈值那一批：一条都不落库，所以这条通知是用户唯一的知情的渠道。 */
    private fun pendingReminder(entries: List<AutomationEntry>, settings: TallySettings, today: LocalDate): Reminder {
        val periods = entries.sumOf { it.count }
        val text = "共 $periods 笔到期 · 去周期与分期页确认"
        val ids = entries.splitIds()
        return Reminder(
            notice = TallyNotice(
                channel = TallyChannel.AUTOMATION,
                title = "$periods 笔到期，等你确认",
                text = text,
                publicText = lockText(settings, text, "$periods 笔到期待确认"),
                tag = "${TAG_PENDING}_$today",
                link = linkOf(entries),
            ),
            ruleIds = ids.first,
            planIds = ids.second,
        )
    }

    /** 失败永远聚合成一条：十条各发一条只会让人把渠道静音。 */
    private fun failureReminder(entries: List<AutomationEntry>, settings: TallySettings): Reminder {
        val first = entries.first()
        val text = "${first.name}：${first.problem.orEmpty()}"
        val ids = entries.splitIds()
        return Reminder(
            notice = TallyNotice(
                channel = TallyChannel.FAILURE,
                title = if (entries.size == 1) "自动记账没能执行" else "${entries.size} 条任务没能执行",
                text = text,
                publicText = lockText(settings, text, "自动记账没能执行"),
                tag = TAG_FAILURE,
                link = linkOf(entries),
            ),
            ruleIds = ids.first,
            planIds = ids.second,
        )
    }

    /**
     * 分期到期前 1 天与当天各提一次（设计板 T-02）。在补执行**之后**算：
     * 当天这一期如果已经自动记入，`generated_periods` 推进了，下一期还在一个月以后，
     * 这里自然什么都说不出——不需要为「已记入的就不再提醒」另写一条规则。
     */
    private suspend fun upcomingReminders(settings: TallySettings, today: LocalDate): List<Reminder> {
        if (!settings.notifyInstallment) return emptyList()
        return planDao.findActive().mapNotNull { plan ->
            if (isSameDay(plan.lastRemindedAt, today)) return@mapNotNull null
            val next = InstallmentSchedule.periods(plan, zoneId())
                .firstOrNull { it.index > plan.generatedPeriods }
                ?: return@mapNotNull null
            val dueDate = dateOf(next.dueAt)
            val daysLeft = dueDate.toEpochDay() - today.toEpochDay()
            // 只说明天与今天。已经过期的那一期是「等确认」或「执行失败」的口径，
            // 由另外两条通知说，不该在这里被写成「今天到期」。
            if (daysLeft !in 0L..1L) return@mapNotNull null
            val dueWord = if (daysLeft == 0L) "今天" else "明天"
            val text = "¥${formatAmount(next.amountMinor)} · 应还 ${dayLabel(next.dueAt)}"
            Reminder(
                notice = TallyNotice(
                    channel = TallyChannel.AUTOMATION,
                    title = "${plan.name}第 ${next.index} 期${dueWord}到期",
                    text = text,
                    publicText = lockText(settings, text, "${plan.name}有一期即将到期"),
                    tag = "${TAG_DUE}_${plan.id}",
                    link = NotificationLink.Installments,
                ),
                planIds = listOf(plan.id),
            )
        }
    }

    /**
     * 当天没有任何有效账单就算未记账（口径由用户定）。到点没到点是按本地时刻比的，
     * 所以 ROM 把定时任务拖过了 20:00 也算数——回前台第一次评估就补上，
     * 这就是设计板 T-01 那句「错过则进 App 补发」的实现位置。
     */
    private suspend fun budgetReminders(settings: TallySettings, today: LocalDate): List<Reminder> {
        val startDay = runCatching { settingRepository.monthStartDay() }.getOrNull() ?: Period.DEFAULT_START_DAY
        val alerts = budgetRepository.activeAlerts(startDay)
        val withAlerts = alerts.groupBy { it.item.ledgerId }
        val sent = preferences.budgetAlertsSent()
        ledgerDao.findAllOnce().forEach { ledger ->
            if (ledger.id in withAlerts) return@forEach
            notifier.cancel(budgetTag(ledger.id, "threshold"))
            notifier.cancel(budgetTag(ledger.id, "over"))
        }
        return withAlerts.mapNotNull { (ledgerId, list) ->
            val worst = list.maxByOrNull { severityOf(it) } ?: return@mapNotNull null
            val over = worst.isOver
            val tier = if (over) "over" else "threshold"
            // 一段一档只发一次：同账本·同周期·同档位在本周期段发过就跳过，划掉也不再回前台重弹（BU21-15）。
            val key = "$ledgerId|${worst.item.period.name}|$tier|${dateOf(worst.range.startAt)}"
            if (key in sent) return@mapNotNull null
            val name = if (worst.item.isTotal) ledgerName(ledgerId) else worst.item.categoryName ?: ledgerName(ledgerId)
            val pct = (worst.ratio * 100).toInt().coerceIn(0, 999)
            notifier.cancel(budgetTag(ledgerId, if (over) "threshold" else "over"))
            Reminder(
                notice = TallyNotice(
                    channel = TallyChannel.REMINDER,
                    title = if (over) {
                        "${budgetSegment(worst)}${name}已超 ¥${formatAmount(worst.usedMinor - worst.item.amountMinor)}"
                    } else {
                        "${budgetSegment(worst)}${name}已用 $pct%"
                    },
                    text = "¥${formatAmount(worst.usedMinor)} / ¥${formatAmount(worst.item.amountMinor)}" +
                        " · 还剩 ${worst.remainingDays} 天",
                    publicText = if (over) "本月预算已超支" else "本月预算已用 $pct%",
                    tag = budgetTag(ledgerId, tier),
                    link = NotificationLink.Budget,
                ),
                budgetAlertKey = key,
            )
        }
    }

    /** 预算通知的 tag 不带日期：同一账本同一档位反复评估只更新同一条。 */
    private fun budgetTag(ledgerId: String, tier: String): String = "${TAG_BUDGET}_${ledgerId}_$tier"

    private fun severityOf(usage: BudgetUsage): Int = when {
        usage.isOver -> 2
        usage.reachedThreshold -> 1
        else -> 0
    }

    /** 通知里的周期前缀：月预算「10 月」，年预算「2026 年」。 */
    private fun budgetSegment(usage: BudgetUsage): String {
        val start = dateOf(usage.range.startAt)
        return if (usage.item.period == BudgetPeriod.MONTHLY) "${start.monthValue} 月" else "${start.year} 年"
    }

    private suspend fun unloggedReminder(settings: TallySettings, today: LocalDate): Reminder? {
        if (!settings.notifyUnlogged) return null
        if (LocalTime.now(zoneId()).hour < settings.dailyReminderHour) return null
        val tag = "${TAG_UNLOGGED}_$today"
        val sentToday = preferences.lastUnloggedReminderDate() == today.toString()
        val endOfToday = today.plusDays(1).atStartOfDay(zoneId()).toInstant().toEpochMilli()
        val lastAt = billDao.lastOccurredBefore(endOfToday)
        if (lastAt != null && dateOf(lastAt) == today) {
            // 记过了就把当天那条收掉：补执行与用户记账都可能在提醒之后发生，
            // 挂着一条「今天还没记账」比不发更坏。
            if (sentToday) notifier.cancel(tag)
            return null
        }
        if (sentToday) return null
        val gapDays = lastAt?.let { today.toEpochDay() - dateOf(it).toEpochDay() }
        val text = when {
            gapDays == null -> "还没有一条账单"
            gapDays <= 1L -> "昨天记过账，今天还没有"
            else -> "已经 $gapDays 天没有新账单"
        }
        return Reminder(
            notice = TallyNotice(
                channel = TallyChannel.REMINDER,
                title = "今天还没记账",
                text = text,
                publicText = text,
                tag = tag,
                link = NotificationLink.NewBill,
            ),
            unloggedDay = today.toString(),
        )
    }

    // ---- 发送与游标 ------------------------------------------------------

    private suspend fun send(reminder: Reminder) {
        notifier.post(reminder.notice)
        val at = now()
        reminder.ruleIds.forEach { ruleDao.markReminded(it, at) }
        reminder.planIds.forEach { planDao.markReminded(it, at) }
        reminder.unloggedDay?.let { preferences.markUnloggedReminder(it) }
        reminder.budgetAlertKey?.let { preferences.markBudgetAlertSent(it) }
    }

    private suspend fun ledgerName(entry: AutomationEntry): String =
        ledgerDao.findById(entry.ledgerId)?.name ?: "未知账本"

    private suspend fun ledgerName(ledgerId: String): String =
        ledgerDao.findById(ledgerId)?.name ?: "未知账本"

    private fun listLink(source: BillSource): NotificationLink =
        if (source == BillSource.INSTALLMENT) NotificationLink.Installments else NotificationLink.Recurring

    /** 混合批次只要涉及分期就落分期页：那边的钱是要真掏的。 */
    private fun linkOf(entries: List<AutomationEntry>): NotificationLink =
        listLink(if (entries.any { it.source == BillSource.INSTALLMENT }) BillSource.INSTALLMENT else BillSource.RECURRING)

    /** 周期与分期是两张表、两套游标，写回之前先按来源分成两份 id 清单。 */
    private fun List<AutomationEntry>.splitIds(): Pair<List<String>, List<String>> {
        val (rules, plans) = partition { it.source == BillSource.RECURRING }
        return rules.map { it.taskId } to plans.map { it.taskId }
    }

    private fun isSameDay(at: Long?, date: LocalDate) = at != null && dateOf(at) == date

    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zoneId()).toLocalDate()

    private fun dayLabel(millis: Long): String {
        val date = dateOf(millis)
        return "${date.monthValue} 月 ${date.dayOfMonth} 日"
    }

    /** 锁屏那份要么带金额、要么一句没信息量的话，由「锁屏显示金额」这一个开关决定。 */
    private fun lockText(settings: TallySettings, withAmount: String, withoutAmount: String) =
        if (settings.showAmountOnLockscreen) withAmount else withoutAmount

    /** 一条待发通知，外加它代表哪些任务——发出去才写游标。 */
    private data class Reminder(
        val notice: TallyNotice,
        val ruleIds: List<String> = emptyList(),
        val planIds: List<String> = emptyList(),
        val unloggedDay: String? = null,
        /** 预算提醒的段·档游标，发过才写，下段同档不再重弹。 */
        val budgetAlertKey: String? = null,
    )

    companion object {
        // tag 决定「重复触发是更新而不是再堆一条」；带日期的 tag 每天换一根，旧的自动过期。
        private const val TAG_GENERATED = "tally_generated"
        private const val TAG_PENDING = "tally_pending"
        private const val TAG_DUE = "tally_due"
        private const val TAG_UNLOGGED = "tally_unlogged"
        private const val TAG_CLEANUP = "tally_cleanup"
        /** 预算通知：tag 不带日期，同一账本同一类型反复评估只更新同一条。 */
        private const val TAG_BUDGET = "tally_budget"

        /** 不带日期：失败恢复了就 `cancel`，同一件事在整个渠道里只应该有一份。 */
        private const val TAG_FAILURE = "tally_failure"
    }
}
