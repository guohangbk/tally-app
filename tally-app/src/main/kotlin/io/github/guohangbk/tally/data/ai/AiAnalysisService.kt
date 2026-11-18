package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.db.dao.AiReportDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.entity.AiReportEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.data.repository.BudgetRepository
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.ai.BudgetCategoryDigest
import io.github.guohangbk.tally.domain.ai.BudgetDigest
import io.github.guohangbk.tally.domain.ai.MonthlyDigest
import io.github.guohangbk.tally.domain.ai.AiErrorKind
import io.github.guohangbk.tally.domain.ai.AiPrompts
import io.github.guohangbk.tally.domain.ai.assistantPlainText
import io.github.guohangbk.tally.domain.ai.AiRequest
import io.github.guohangbk.tally.domain.ai.AiResult
import io.github.guohangbk.tally.domain.ai.AiTurn
import io.github.guohangbk.tally.domain.ai.MonthlyDigestBuilder
import io.github.guohangbk.tally.domain.model.AiReport
import io.github.guohangbk.tally.domain.model.BillFilter
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillRepositoryContract
import io.github.guohangbk.tally.domain.model.BillSort
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.BudgetPeriod
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.domain.model.StatsRequest
import io.github.guohangbk.tally.domain.model.StatisticsSnapshot
import io.github.guohangbk.tally.domain.sha256Hex
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** 分析结果分三种：命中缓存、真的生成了一张新报告、小莉不可用。 */
sealed interface AiAnalysisOutcome {
    data class Generated(val report: AiReport, val tokens: Int?, val latencyMillis: Long) : AiAnalysisOutcome

    data class FromCache(val report: AiReport) : AiAnalysisOutcome

    /** 本地这个周期根本没什么数据，不该为此花额度。 */
    data class NothingToAnalyze(val reason: String) : AiAnalysisOutcome

    data class Failed(val reason: String) : AiAnalysisOutcome
}

/**
 * 周期消费分析（S-01…04）。月维度和年维度走同一条路：
 * 周期边界、口径开关都与统计页那一次查询同源，报告里的数字才是屏上那格数字。
 *
 * 顺序是硬约束：本地查询层先算金额、合计、环比和排行 → 拼结构化摘要 → 才发给小莉解读。
 * 报告按 (账本, 周期键, 输入摘要哈希) 缓存：再点开这张弹层、输入没变就直接复用；
 * 「重新生成」传的是 force = true，一定重新调模型，不走缓存也不省额度。
 * 失败只影响这一屏的解读文字，统计页的数字一个都不会变（降级表）。
 */
class AiAnalysisService(
    private val reports: AiReportDao,
    private val bills: BillRepositoryContract,
    private val ledgers: LedgerDao,
    private val budgets: BudgetRepository,
    private val gateway: AiGateway,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** 「全部账本」也要能分析：报告行需要一个非空 ledger_id，用保留键代表不限账本。 */
    private fun ledgerKey(ledgerId: String?): String = ledgerId ?: ALL_LEDGERS

    /**
     * 周期键：三种粒度同一个形状 `2026-10-01_2026-10-20`，两端都是实际取数的日子，
     * 右端不越过今天。落库的键与发给小莉的那一行区间因此是同一串字。
     */
    private fun periodKey(request: StatsRequest): String = Period.key(boundsOf(request), zone)

    suspend fun cached(request: StatsRequest): AiReport? =
        reports.findLatest(ledgerKey(request.ledgerId), periodKey(request))?.toDomain()

    /**
     * 本地摘要：不联网也算得出来。
     * 「异常与提示」这些条目直接由本地数据渲染，小莉挂了这一屏也不空（降级表）。
     */
    suspend fun preview(request: StatsRequest, includeNotes: Boolean = false): MonthlyDigest {
        val statistics = bills.observeStatistics(request).first()
        return digestOf(request, statistics)
    }

    suspend fun analyze(
        request: StatsRequest,
        includeNotes: Boolean = false,
        force: Boolean = false,
        /** 非空时走流式：模型每吐一段就回调一次，界面边收边显示。 */
        onDelta: ((String) -> Unit)? = null,
    ): AiAnalysisOutcome {
        val startedAt = System.nanoTime()
        val statistics = bills.observeStatistics(request).first()
        val statisticsAt = System.nanoTime()
        if (statistics.summary.billCount == 0) {
            return AiAnalysisOutcome.NothingToAnalyze(
                when (request.granularity) {
                    StatsGranularity.YEAR -> "这一年还没有账单，先去记一笔再来让小莉看看。"
                    StatsGranularity.RANGE -> "这段区间还没有账单，换一段日期或先去记一笔。"
                    StatsGranularity.MONTH -> "这个月还没有账单，先去记一笔再来让小莉看看。"
                },
            )
        }
        val digest = digestOf(request, statistics)
        val preparedAt = System.nanoTime()
        val digestHash = sha256Hex(digest.canonical)

        if (!force) {
            reports.findByDigest(digestHash)?.let {
                DebugLog.log("AI 分析", "缓存命中 · 准备 ${(preparedAt - startedAt) / 1_000_000}ms")
                return AiAnalysisOutcome.FromCache(it.toDomain())
            }
        }

        val aiRequest = AiRequest(
            instruction = AiPrompts.analysisInstruction(request.granularity),
            turns = listOf(AiTurn.user(digest.promptBlock(includeNotes))),
        )
        var emitted = false
        // 流式优先：网关不认流式（SCHEMA）且一个字都没吐时，退回一次性请求，界面不会因此拿不到结果。
        val result = if (onDelta == null) {
            gateway.call(aiRequest)
        } else {
            val streamed = gateway.stream(aiRequest) { piece ->
                emitted = true
                onDelta(piece)
            }
            if (streamed is AiResult.Failure && streamed.kind == AiErrorKind.SCHEMA && !emitted) {
                gateway.call(aiRequest)
            } else {
                streamed
            }
        }
        return when (result) {
            is AiResult.Success -> {
                DebugLog.log(
                    "AI 分析",
                    "完成 · 准备 ${(preparedAt - startedAt) / 1_000_000}ms" +
                        "（统计 ${(statisticsAt - startedAt) / 1_000_000}ms）· " +
                        "生成 ${result.value.latencyMillis}ms · 合计 ${(System.nanoTime() - startedAt) / 1_000_000}ms",
                )
                val entity = AiReportEntity(
                    id = newId(),
                    ledgerId = ledgerKey(request.ledgerId),
                    // 与 [cached] 同一个 periodKey：落库换成别的写法，海报就永远查不到这一行。
                    monthKey = periodKey(request),
                    inputSha256 = digestHash,
                    summaryJson = digest.summaryJson(),
                    content = assistantPlainText(result.value.text),
                    createdAt = now(),
                    updatedAt = now(),
                    deviceId = preferences.deviceId(),
                )
                reports.upsert(entity)
                AiAnalysisOutcome.Generated(entity.toDomain(), result.value.usage.totalTokens, result.value.latencyMillis)
            }

            is AiResult.Failure -> {
                DebugLog.log("AI 分析", "失败 ${result.kind?.name ?: "-"} ${result.failure.detail.orEmpty().take(160)}")
                AiAnalysisOutcome.Failed(result.failure.detail ?: result.kind?.userMessage ?: "小莉暂时联系不上")
            }
        }
    }

    /**
     * 大额支出候选：只取过了金额门槛的支出，按金额倒序；分类笔数走统计结果，
     * 不再为「这一期有哪些分类」整段拉一遍账单。
     */
    private suspend fun largeExpensesOf(
        request: StatsRequest,
        averageExpenseMinor: Long,
    ): List<BillListItem> {
        if (averageExpenseMinor <= 0L) return emptyList()
        val bounds = boundsOf(request)
        return bills.observeSearch(
            BillFilter(
                startAt = bounds.startAt,
                endAtExclusive = bounds.endAtExclusive,
                type = BillType.EXPENSE,
                ledgerId = request.ledgerId,
                amountMinMinor = averageExpenseMinor * MonthlyDigestBuilder.LARGE_EXPENSE_MULTIPLES,
                sort = BillSort.AMOUNT_DESC,
            ),
        ).first().bills.take(MonthlyDigestBuilder.TOP_LARGE_COUNT * 4)
    }

    /** 与 BillRepository 同一套边界：月维度按月起止日，区间用调用方给的右边界。 */
    private fun boundsOf(request: StatsRequest): PeriodRange = when (request.granularity) {
        StatsGranularity.MONTH -> Period.monthRange(request.periodStart, request.startDay, zone)
        StatsGranularity.YEAR -> Period.yearRange(request.periodStart, zone)
        StatsGranularity.RANGE -> {
            val start = Period.dayRange(request.periodStart, request.periodStart, zone).startAt
            val end = request.rangeEndExclusive?.let { Period.dayRange(it, it, zone).startAt }
                ?: Period.shiftDays(start, 1, zone)
            PeriodRange(start, maxOf(end, Period.shiftDays(start, 1, zone)))
        }
    }

    private suspend fun digestOf(
        request: StatsRequest,
        statistics: StatisticsSnapshot,
    ): MonthlyDigest = coroutineScope {
        // 账本名、预算、大额支出互不依赖，并发取；统计结果已经在手上。
        val ledgerName = async { request.ledgerId?.let { ledgers.findById(it)?.name } ?: "全部账本" }
        val budget = async { budgetOf(request) }
        val largeExpenses = async { largeExpensesOf(request, statistics.averageExpenseMinor) }
        MonthlyDigestBuilder.build(
            statistics = statistics,
            largeExpenses = largeExpenses.await(),
            periodKey = periodKey(request),
            ledgerName = ledgerName.await(),
            excludePendingReimburse = request.excludePendingReimburse,
            budget = budget.await(),
            zone = zone,
        )
    }

    /**
     * 本期对照的预算。预算只有月/年两种周期、且按账本存，所以区间维度和「全部账本」拿不到——
     * 这两种情况下摘要里直说没有设预算，不硬凑数字。
     */
    private suspend fun budgetOf(request: StatsRequest): BudgetDigest? {
        val ledgerId = request.ledgerId ?: return null
        val period = when (request.granularity) {
            StatsGranularity.MONTH -> BudgetPeriod.MONTHLY
            StatsGranularity.YEAR -> BudgetPeriod.YEARLY
            else -> return null
        }
        // rangeAnchor 用本期锚点：翻到历史月/年时，预算那一段也要跟着落到那一段。
        val summary = budgets.observeSummary(ledgerId, period, request.startDay, request.periodStart).first()
        val total = summary.total ?: return null
        return BudgetDigest(
            totalLimitMinor = total.item.amountMinor,
            usedMinor = total.usedMinor,
            ratio = total.ratio,
            isOver = total.isOver,
            categories = summary.categories.take(BUDGET_CATEGORY_COUNT).map { usage ->
                BudgetCategoryDigest(
                    name = usage.item.categoryName.orEmpty(),
                    limitMinor = usage.item.amountMinor,
                    usedMinor = usage.usedMinor,
                    ratio = usage.ratio,
                    isOver = usage.isOver,
                )
            },
        )
    }

    private fun AiReportEntity.toDomain(): AiReport = AiReport(
        id = id,
        ledgerId = ledgerId,
        monthKey = monthKey,
        content = content,
        summaryJson = summaryJson,
        createdAt = createdAt,
    )

    private companion object {
        const val ALL_LEDGERS = "__all__"
        /** 摘要里带几条分类预算：够模型说出「哪类超了」，又不至于把预算表抄一遍。 */
        const val BUDGET_CATEGORY_COUNT = 3
    }
}

/**
 * 摘要 JSON 手工拼接：字段顺序与转义固定，才能保证同一份输入永远算出同一个哈希。
 * （用 org.json 会让这段逻辑只能在设备上跑，单测里直接是 stub。）
 */
private fun MonthlyDigest.summaryJson(): String = buildString {
    append('{')
    append("\"month\":").append(jsonString(monthKey)).append(',')
    append("\"ledger\":").append(jsonString(ledgerName)).append(',')
    append("\"expenseMinor\":").append(expenseMinor).append(',')
    append("\"incomeMinor\":").append(incomeMinor).append(',')
    append("\"billCount\":").append(billCount).append(',')
    append("\"previousExpenseMinor\":").append(previousExpenseMinor).append(',')
    append("\"changePercent\":").append(expenseChangePercent?.toInt()?.toString() ?: "null").append(',')
    append("\"yearAgoExpenseMinor\":").append(yearAgoExpenseMinor).append(',')
    append("\"yearAgoChangePercent\":").append(yearAgoChangePercent?.toInt()?.toString() ?: "null").append(',')
    append("\"previousCategories\":[")
    previousCategories.forEachIndexed { index, category ->
        if (index > 0) append(',')
        append("{\"name\":").append(jsonString(category.name))
            .append(",\"amountMinor\":").append(category.amountMinor)
            .append(",\"count\":").append(category.count).append('}')
    }
    append("],")
    append("\"budget\":").append(budget?.let { b ->
        buildString {
            append('{')
            append("\"totalLimit\":").append(b.totalLimitMinor).append(',')
            append("\"used\":").append(b.usedMinor).append(',')
            append("\"ratio\":").append(b.ratio).append(',')
            append("\"over\":").append(b.isOver).append(',')
            append("\"categories\":[")
            b.categories.forEachIndexed { index, category ->
                if (index > 0) append(',')
                append("{\"name\":").append(jsonString(category.name))
                    .append(",\"limit\":").append(category.limitMinor)
                    .append(",\"used\":").append(category.usedMinor).append('}')
            }
            append(']')
            append('}')
        }
    } ?: "null").append(',')
    append("\"categories\":[")
    categories.forEachIndexed { index, category ->
        if (index > 0) append(',')
        append("{\"name\":").append(jsonString(category.name))
            .append(",\"amountMinor\":").append(category.amountMinor)
            .append(",\"count\":").append(category.count).append('}')
    }
    append(']')
    append('}')
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch.code < 0x20 -> append("\\u").append(String.format("%04x", ch.code))
            else -> append(ch)
        }
    }
    append('"')
}
