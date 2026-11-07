package io.github.guohangbk.tally.ui.share

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.share.PosterFiles
import io.github.guohangbk.tally.data.share.PosterRenderer
import io.github.guohangbk.tally.data.share.PosterSpec
import io.github.guohangbk.tally.data.share.budgetPeriodOf
import io.github.guohangbk.tally.data.share.posterPeriodLabel
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.ai.AnalysisField
import io.github.guohangbk.tally.domain.ai.analysisFieldOrFirstSentence
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.model.StatsBucket
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.domain.model.StatsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PosterUiState(
    val granularity: StatsGranularity = StatsGranularity.MONTH,
    val anchorStart: Long = Period.monthRange(System.currentTimeMillis()).startAt,
    val rangeEndExclusive: Long? = null,
    val bucket: StatsBucket? = null,
    val ledgerId: String? = null,
    /** 统计页的口径开关也要跟过来：海报不能比屏上多算待报销。 */
    val excludePendingReimburse: Boolean = false,
    val ledgerName: String = "全部账本",
    val periodLabel: String = "",
    val masked: Boolean = false,
    /** 出图要几百毫秒：这段之间按钮置灰，界面停在上一张，不给按出半张的机会。 */
    val rendering: Boolean = false,
    val preview: ImageBitmap? = null,
    val widthPx: Int = PosterRenderer.WIDTH_PX,
    val heightPx: Int = 0,
    val billCount: Int = 0,
    val startDay: Int = Period.DEFAULT_START_DAY,
    val error: String? = null,
    val message: String? = null,
) {
    val canExport: Boolean get() = preview != null && !rendering
    val aspectRatio: Float get() = if (heightPx > 0) widthPx.toFloat() / heightPx else 0.6f
}

/**
 * 海报页的取数与出图。
 *
 * 维度、区间、账本全部由统计页带进来，这一页一个都不改——
 * 于是「屏上在看的那段账」与「画出来的这张图」永远是同一份 request。
 * 页上唯一能改的是「脱敏」这颗开关。
 */
class PosterViewModel(
    private val graph: AppGraph,
    private val appContext: Context,
    initial: StatsRequest?,
    /** 结算长图模式：只带一本账，区间与预算由这本账自己现算，不再从统计页搬。 */
    private val settlementLedgerId: String? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(
        PosterUiState(
            granularity = initial?.granularity ?: StatsGranularity.MONTH,
            anchorStart = initial?.periodStart ?: Period.monthRange(System.currentTimeMillis()).startAt,
            rangeEndExclusive = initial?.rangeEndExclusive,
            bucket = initial?.bucket,
            ledgerId = initial?.ledgerId ?: settlementLedgerId,
            excludePendingReimburse = initial?.excludePendingReimburse == true,
        ),
    )
    val state: StateFlow<PosterUiState> = mutable.asStateFlow()

    private var renderJob: Job? = null

    /** Bitmap 与上屏的 ImageBitmap 共享像素，只留一份；重画时才回收上一张。 */
    private var bitmap: Bitmap? = null
    private var startDay: Int = Period.DEFAULT_START_DAY
    private var grouping: Boolean = true

    init {
        viewModelScope.launch {
            startDay = runCatching { graph.settingRepository.monthStartDay() }.getOrDefault(Period.DEFAULT_START_DAY)
            grouping = runCatching { graph.preferences.settings.first().showThousandsSeparator }.getOrDefault(true)
            val masked = runCatching { graph.preferences.posterMasked.first() }.getOrDefault(false)
            mutable.value = mutable.value.copy(
                masked = masked,
                startDay = startDay,
            )
            refresh()
        }
    }

    /** 出图失败只给一次重画的机会——不是重试网络，是重跑同一份本地数。 */
    fun retry() {
        refresh()
    }

    /** 脱敏是一颗开关，跨次保留：反复发图给同一个人时不该每次重开。 */
    fun setMasked(masked: Boolean) {
        if (masked == mutable.value.masked) return
        mutable.value = mutable.value.copy(masked = masked)
        viewModelScope.launch { graph.preferences.setPosterMasked(masked) }
        refresh()
    }

    fun dismissMessage() {
        mutable.value = mutable.value.copy(message = null)
    }

    /** 分享：写缓存 → FileProvider → 系统面板。不落相册、不留副本。 */
    fun share() {
        val source = bitmap ?: return
        export(share = true, snapshot = source.copy(Bitmap.Config.ARGB_8888, false))
    }

    /** 保存到下载才是显式写公共目录的动作；同名覆盖，不产生 `(1)`。 */
    fun save() {
        val source = bitmap ?: return
        export(share = false, snapshot = source.copy(Bitmap.Config.ARGB_8888, false))
    }

    private fun export(share: Boolean, snapshot: Bitmap) {
        val state = mutable.value
        viewModelScope.launch {
            val fileName = if (settlementLedgerId != null) {
                PosterFiles.settlementName(state.ledgerName)
            } else {
                PosterFiles.name(state.granularity, rangeOf(state))
            }
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) { PosterRenderer.png(snapshot) }
                if (share) {
                    val uri = withContext(Dispatchers.IO) { PosterFiles.cacheUri(appContext, fileName, bytes) }
                    appContext.startActivity(PosterFiles.shareIntent(appContext, uri, fileName))
                    null
                } else {
                    withContext(Dispatchers.IO) { PosterFiles.save(appContext, fileName, bytes) }.location
                }
            }
            snapshot.recycle()
            mutable.value = mutable.value.copy(
                message = when {
                    result.isFailure -> (if (share) "没能分享出去：" else "没能保存：") +
                        (result.exceptionOrNull()?.message ?: "文件写不出去")
                    share -> null
                    else -> "已存到 ${result.getOrNull()}"
                },
            )
        }
    }

    private fun rangeOf(state: PosterUiState): PeriodRange = when (state.granularity) {
        StatsGranularity.MONTH -> Period.monthRange(state.anchorStart, startDay)
        StatsGranularity.YEAR -> Period.yearRange(state.anchorStart)
        StatsGranularity.RANGE -> Period.dayRange(
            state.anchorStart,
            state.rangeEndExclusive ?: Period.shiftDays(state.anchorStart, 1),
        )
    }

    private fun refresh() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            if (settlementLedgerId != null) refreshSettlement() else refreshPeriod()
        }
    }

    /**
     * 结算长图：没有周期参数可搬，全从这本账的跨度现算。
     * 合计与分类占比仍走统计仓库的区间口径，预算读的是这本账各周期段的总额行之和。
     */
    private suspend fun refreshSettlement() {
        val state = mutable.value
        val id = settlementLedgerId ?: return
        mutable.value = state.copy(rendering = true, error = null)
        val built = runCatching {
            val core = graph.ledgerRepository.observeOverview(id).first()
                ?: throw IllegalStateException("账本已不存在")
            val today = System.currentTimeMillis()
            val start = core.span.startAt ?: today
            val end = core.span.endAt ?: today
            val snapshot = graph.billRepository.observeStatistics(
                StatsRequest(
                    ledgerId = id,
                    periodStart = start,
                    granularity = StatsGranularity.RANGE,
                    rangeEndExclusive = Period.shiftDays(end, 1),
                    bucket = StatsBucket.MONTH,
                ),
            ).first()
            val days = Period.daysBetween(start, end) + 1
            val limit = graph.budgetRepository.observeItems(id).first()
                .filter { it.categoryId == null }
                .sumOf { it.amountMinor }
                .takeIf { it > 0L }
            val spec = PosterSpec.buildSettlement(
                snapshot = snapshot,
                ledgerName = core.ledger.name,
                // 结算图的抬头写完整日期：这张图会被单独发出去，脱离 App 后「3/15」认不出是哪一年。
                spanLabel = Period.toDate(start).toString() + " ～ " + Period.toDate(end).toString() +
                    " · ${core.billCount} 笔",
                dayCount = days,
                monthCount = monthsInclusive(start, end),
                budgetLimitMinor = limit,
                grouping = grouping,
                masked = state.masked,
            )
            spec to (core.ledger.name to core.billCount)
        }
        val rendered = built.getOrNull()?.first?.let { spec ->
            runCatching { withContext(Dispatchers.IO) { PosterRenderer.render(appContext, spec) } }.getOrNull()
        }
        bitmap?.recycle()
        bitmap = rendered
        mutable.value = mutable.value.copy(
            rendering = false,
            ledgerName = built.getOrNull()?.second?.first ?: state.ledgerName,
            billCount = built.getOrNull()?.second?.second ?: 0,
            preview = rendered?.asImageBitmap(),
            heightPx = rendered?.height ?: 0,
            error = built.exceptionOrNull()?.let { "这张没画出来：${it.message}" }
                ?: if (rendered == null && built.isSuccess) "这张没画出来：位图分配失败" else null,
        )
    }

    /** 跨到的自然月都算：07-03 ～ 08-12 是两个月，不是 1.3 个月。 */
    private fun monthsInclusive(startAt: Long, endAt: Long): Int {
        val start = Period.toDate(startAt)
        val end = Period.toDate(endAt)
        return (end.year - start.year) * 12 + end.monthValue - start.monthValue + 1
    }

    private suspend fun refreshPeriod() {
        val state = mutable.value
        val label = posterPeriodLabel(state.granularity, rangeOf(state), startDay)
        mutable.value = state.copy(rendering = true, periodLabel = label, error = null)
        val request = StatsRequest(
                ledgerId = state.ledgerId,
                periodStart = state.anchorStart,
                granularity = state.granularity,
                excludePendingReimburse = state.excludePendingReimburse,
                startDay = startDay,
                rangeEndExclusive = state.rangeEndExclusive.takeIf { state.granularity == StatsGranularity.RANGE },
                bucket = state.bucket.takeIf { state.granularity == StatsGranularity.RANGE },
            )
            val built = runCatching {
                val snapshot = graph.billRepository.observeStatistics(request).first()
                val ledgerName = state.ledgerId?.let { id ->
                    graph.ledgerRepository.active.first().firstOrNull { it.id == id }?.name
                } ?: "全部账本"
                val budget = state.ledgerId?.let {
                    runCatching {
                        graph.budgetRepository.observeSummary(
                            ledgerId = it,
                            period = budgetPeriodOf(state.granularity),
                            startDay = startDay,
                            rangeAnchor = state.anchorStart,
                        ).first()
                    }.getOrNull()
                }
                PosterSpec.build(
                    snapshot = snapshot,
                    ledgerName = ledgerName,
                    rangeLabel = label,
                    digestLine = digestLine(request),
                    budget = budget,
                    grouping = grouping,
                    masked = state.masked,
                ) to (snapshot.summary.billCount to ledgerName)
            }
            val rendered = built.getOrNull()?.let { (pair, _) ->
                runCatching { withContext(Dispatchers.IO) { PosterRenderer.render(appContext, pair) } }.getOrNull()
            }
            bitmap?.recycle()
            bitmap = rendered
            val counts = built.getOrNull()?.second
            mutable.value = mutable.value.copy(
                rendering = false,
                ledgerName = counts?.second ?: state.ledgerName,
                billCount = counts?.first ?: 0,
                preview = rendered?.asImageBitmap(),
                heightPx = rendered?.height ?: 0,
                error = built.exceptionOrNull()?.let { "这张没画出来：${it.message}" }
                    ?: if (rendered == null && built.isSuccess) "这张没画出来：位图分配失败" else null,
            )
    }

    /**
     * 结论段：这段账算过 AI 小结才取「总结」那一句；没算过就返回 null，整段不画。
     * 不拿统计口径那句顶上——读图的人会以为这是小莉给的结论。
     */
    private suspend fun digestLine(request: StatsRequest): String? =
        runCatching { graph.aiAnalysisService.cached(request) }.getOrNull()
            ?.content
            ?.let { analysisFieldOrFirstSentence(it, AnalysisField.SUMMARY) }

    override fun onCleared() {
        bitmap?.recycle()
        bitmap = null
    }
}
