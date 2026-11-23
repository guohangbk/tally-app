package io.github.guohangbk.tally.ui.assistant

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.ingest.MaterialOutcome
import io.github.guohangbk.tally.data.ingest.BillSavedEvent
import io.github.guohangbk.tally.data.ingest.PendingFailure
import io.github.guohangbk.tally.data.ingest.PendingIngestEntry
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.DraftRow
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.ingest.IngestSource
import io.github.guohangbk.tally.domain.ingest.IngestStage
import io.github.guohangbk.tally.domain.ingest.IngestResult
import io.github.guohangbk.tally.domain.ingest.IngestProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** 一次批量识别里的一张图。[grade] 与 [missing] 来自管线里的本机判级。 */
data class ScannedBill(
    val key: Int,
    val imageIndex: Int,
    /** 这一条从哪个入口来：通知 / 支付页 / 截图 / 语音 / 分享。 */
    val source: IngestSource,
    /** 入队时刻：自动草稿靠它算 30 天过期。 */
    val createdAt: Long,
    /** 入队时首页选中的账本：进记账页与落库都按它走。 */
    val ledgerId: String? = null,
    /** 订单号：跨入口幂等按它认同一笔，卡上来源位并排写。 */
    val orderNo: String? = null,
    /** 自动草稿（通知 / 支付页抓到的）：批次含它时卡头是「收起」而不是「先不处理」。 */
    val auto: Boolean = false,
    val amountMinor: Long,
    val type: BillType,
    val occurredAt: Long?,
    val categoryL1Id: String?,
    val categoryL2Id: String?,
    val categoryLabel: String?,
    val merchant: String?,
    val note: String? = null,
    val grade: String,
    val missing: List<String> = emptyList(),
    /** 日期不是原文给的、按采集时刻落的账：行上直接写日期，不用技术词。 */
    val dateDefaulted: Boolean = false,
    val dateUnresolved: Boolean = false,
    /** 订单号与图片指纹：单张图直达记账页时也要带过去，否则存回来不带去重标识。 */
    val externalId: String? = null,
    val imageSha: String? = null,
    /** 命中分类的那个词，交记账页之后判断要不要沉淀；没命中就是 null。 */
    val keyword: String? = null,
    /** 备注是 AI 写的：沉淀时标成 `LEARNED_AI`。 */
    val noteFromAi: Boolean = false,
    val checked: Boolean = true,
    /** 已交给记账页但还没保存回来：行留在卡上，防止跳页后整张草稿丢失。 */
    val editing: Boolean = false,
) {
    val needsCategory: Boolean get() = categoryL1Id == null
}

/** 一张图什么都没认出来时，要能指名道姓地说清是哪一张、为什么。 */
data class ScanFailure(
    val imageIndex: Int,
    val reason: String,
    val ledgerId: String? = null,
)

data class MaterialBatchUiState(
    val busy: Boolean = false,
    val stage: String? = null,
    val imageTotal: Int = 0,
    val imageDone: Int = 0,
    /** 这批素材从哪来：卡头要据此说「来自 N 张截图」还是「来自一句话」。 */
    val source: IngestSource = IngestSource.SCREENSHOT,
    val bills: List<ScannedBill> = emptyList(),
    val failures: List<ScanFailure> = emptyList(),
    /** 一次给了超过 [MAX_IMAGES] 张时被丢掉的张数：气泡要如实说，不能默默吞掉。 */
    val droppedCount: Int = 0,
    /** 这一批里「之前已经记过」的笔数：只用一条气泡交代，不再挤进待确认列表。 */
    val duplicates: Int = 0,
    /** 每批识别收尾 +1：同样的「1 笔已存在」也要能再弹一次。 */
    val scanSeq: Long = 0,
    val error: String? = null,
    /**
     * 这份清单是从文件里恢复回来的，不是这一轮刚扫出来的。
     * 首页据此决定「进来就摆卡」还是「只留一个待确认入口」——中途断掉的那一批不该每次开 App 都糊在脸上。
     */
    val restored: Boolean = false,
) {
    val checkedBills: List<ScannedBill> get() = bills.filter { it.checked }
    val blockedByMissingCategory: Boolean get() = checkedBills.any { it.needsCategory }
    val done: Boolean get() = !busy && (bills.isNotEmpty() || failures.isNotEmpty() || duplicates > 0)

    /**
     * 卡上已经没有等人处理的行了：全存完、或这一批全是「已记过」。
     * 收卡只看它——[done] 在「全存完列表恰好空掉」时反而是假（真机上撞见过卡一直留着）。
     */
    val settled: Boolean get() = !busy && bills.isEmpty()
}

/**
 * 首页那三条机器记账入口（＋ 左滑多选、系统分享、按住说话）共用的一批素材：
 * 逐份走「读图 / 收音 → 规则抽取 → AI 补缺 → 本机判级」，只把结果汇成一份待确认列表。
 * 识别管线不在这里重写；除了判级为高且开关开着的那几笔，落库前必须过人手（V-06 / V-07）。
 */
class MaterialBatchViewModel(private val graph: AppGraph) : ViewModel() {
    private val state = MutableStateFlow(MaterialBatchUiState())
    val uiState: StateFlow<MaterialBatchUiState> = state.asStateFlow()

    /** 当前账本里落盘但还没处理完的笔数：首页那枚「待确认 N 笔」入口只认它。 */
    val pendingCount: StateFlow<Int> = combine(
        graph.pendingIngestStore.entries,
        graph.activeLedger,
        graph.defaultLedgerId,
    ) { entries, active, default ->
        val ledger = active ?: default
        if (ledger == null) 0 else entries.count { belongsTo(it, ledger, default) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 逐笔确认时的下一条：页面接住它自动跳进记账页，保存后由 [onManualSaved] 再给下一条。 */
    private val mutableNext = MutableStateFlow<ScannedBill?>(null)
    val nextToOpen: StateFlow<ScannedBill?> = mutableNext.asStateFlow()

    private val rowsByKey = mutableMapOf<Int, DraftRow>()
    /** 每行的来源图：只在「账单图片」开着时留，落库时写成账单附件。 */
    private val imageBytesByKey = mutableMapOf<Int, ByteArray>()
    private var editingKey: Int? = null
    /** 是不是「逐笔确认」那条路：保存一笔就自动接下一笔。 */
    private var queueMode = false

    init {
        viewModelScope.launch {
            graph.ingestDrafts.materialSaved.collect(::onManualSaved)
        }
        viewModelScope.launch {
            graph.ingestDrafts.materialEditCancelled.collect { cancelEdit() }
        }
        // 上次没处理完的清单：进来就摆回卡上，一行都不丢。
        viewModelScope.launch { restorePending() }
        // 切账本只展示这一本自己的待确认：旧账本的行从卡上撤掉，新账本的行再摆回来。
        viewModelScope.launch {
            combine(graph.activeLedger, graph.defaultLedgerId) { active, default -> active ?: default }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (state.value.busy || state.value.stage != null) return@collect
                    clear()
                    restorePending()
                }
        }
    }

    /** 相册多选。逐张识别。任何一张失败都不影响其余的，但一定要出现在 [MaterialBatchUiState.failures] 里。 */
    fun scan(uris: List<Uri>) = scanImages(uris, IngestSource.SCREENSHOT)

    /** 系统分享进来的图，与相册多选共用同一条收尾；差别只在来源（与多图上限的提示）。 */
    fun scanShared(uris: List<Uri>) = scanImages(uris, IngestSource.SHARE)

    /**
     * 首页语音：一份「只有一句话」的素材，走与截图完全相同的收尾——
     * 规则认不全就交给 AI 兜底，结果一律摆上确认卡等人点。
     */
    fun scanSpoken(text: String) {
        if (text.isBlank()) return
        scanMaterials(1, IngestSource.VOICE) { progress ->
            MaterialOutcome.Ready(graph.materialReader.spokenRequest(text, onStage = progress.callback()))
        }
    }

    private fun scanImages(uris: List<Uri>, source: IngestSource) {
        if (uris.isEmpty()) return
        val accepted = uris.take(MAX_IMAGES)
        scanMaterials(accepted.size, source, dropped = uris.size - accepted.size) { progress ->
            graph.materialReader.requestOf(accepted, source, onStage = progress.callback())
        }
    }

    /**
     * 一批素材的收尾。逐份识别 → 结果一次性摆上确认卡，等人在卡上点头才落库。
     *
     * 上一批**还没确认**的时候又来一批（相册再选几张、系统分享再来一张），结果是**追加**到同一张卡上：
     * 覆盖会把已经识别好、还没确认的那几笔无声丢掉，而那几笔是花过一次识别的。
     */
    private fun scanMaterials(
        count: Int,
        source: IngestSource,
        dropped: Int = 0,
        read: suspend (IngestProgress) -> MaterialOutcome,
    ) {
        if (count <= 0) return
        val before = state.value
        // 图片序号往后排：两批共用同一批号，卡头的「N 张图」和去重就串了。
        val imageBase = (before.bills.map { it.imageIndex } + before.failures.map { it.imageIndex })
            .maxOrNull()?.plus(1) ?: 0
        // 这一批归哪本账：识别期间切账本也不该把旧草稿塞进新账本。
        val ledgerId = effectiveLedgerId()
        editingKey = null
        fun publishStage(stage: IngestStage, done: Int, total: Int) {
            state.update {
                it.copy(
                    stage = stageLabel(source, stage),
                    imageDone = imageBase + done.coerceAtMost(total),
                    imageTotal = imageBase + total,
                )
            }
        }
        val progress = IngestProgress(::publishStage)
        state.update {
            it.copy(
                busy = true,
                stage = stageLabel(source, IngestStage.OCR),
                imageTotal = imageBase + count,
                imageDone = imageBase,
                // 这一批是刚发生的（不是从文件里恢复回来的）：卡该自己摆出来。
                restored = false,
                source = if (it.bills.isEmpty() && it.failures.isEmpty()) source else it.source,
                droppedCount = dropped,
                error = null,
            )
        }
        progress.total(count)
        DebugLog.log(
            "识别管线",
            "素材卡开始识别 $count 份（${source.name}）" + if (dropped > 0) "，另有 $dropped 张超出上限未接收" else "",
        )
        viewModelScope.launch(Dispatchers.IO) {
            val scanStartedAt = System.nanoTime()
            val scanned = mutableListOf<ScannedBill>()
            val failures = mutableListOf<ScanFailure>()
            var duplicates = 0
            try {
                val material = read(progress)
                val requests = when (material) {
                    is MaterialOutcome.Failure -> {
                        failures += ScanFailure(imageBase, material.reason, ledgerId)
                        emptyList()
                    }
                    is MaterialOutcome.Ready -> material.requests.ifEmpty { listOf(material.request) }
                }
                val results = if (requests.isEmpty()) {
                    emptyList()
                } else {
                    graph.billIngestor.ingestBatch(requests)
                }
                // 账单图片：图在取图那一步已经读过一次，这里只把同一份字节留在内存里，
                // 等这一批真的落库时写成附件。开关关着就一张都不留。
                val keepImage = graph.preferences.settings.first().billImageSaved
                val sources = requests.map { request -> request.images.firstOrNull().takeIf { keepImage } }
                results.forEachIndexed { index, result ->
                    duplicates += collect(
                        result,
                        source,
                        imageBase + index,
                        ledgerId,
                        sources.getOrNull(index),
                        scanned,
                        failures,
                    )
                }
                state.update {
                    it.copy(
                        busy = false,
                        stage = null,
                        imageDone = imageBase + count,
                        imageTotal = imageBase + count,
                        // 追加：上一批还没确认的那几笔留在卡上，一起排。
                        bills = (it.bills + scanned)
                            .sortedWith(compareBy<ScannedBill> { bill -> gradeOrder(bill) }.thenBy { it.key }),
                        failures = it.failures + failures,
                        duplicates = duplicates,
                        scanSeq = it.scanSeq + 1,
                    )
                }
                persist()
            } catch (cancellation: CancellationException) {
                state.update { it.copy(busy = false, stage = null, failures = it.failures + failures) }
                throw cancellation
            } catch (failure: Exception) {
                failures += ScanFailure(imageBase, failure.message ?: "识别没有完成", ledgerId)
                state.update {
                    it.copy(
                        busy = false,
                        stage = null,
                        failures = it.failures + failures,
                        duplicates = 0,
                        error = null,
                    )
                }
            } finally {
                val settled = state.updateAndGet { current ->
                    if (current.busy || current.stage != null) {
                        current.copy(busy = false, stage = null)
                    } else {
                        current
                    }
                }
                DebugLog.log(
                    "识别管线",
                    "素材卡识别结束：成功 ${settled.bills.size} 笔、失败 ${settled.failures.size} 张 · " +
                        "${(System.nanoTime() - scanStartedAt) / 1_000_000}ms",
                )
            }
        }
    }

    /** 收一份识别结果：能确认的进列表，已经记过的只计数（[scanned] 里不放）。 */
    private suspend fun collect(
        result: IngestResult,
        source: IngestSource,
        imageIndex: Int,
        /** 这一批归属的账本：行号由清单统一发，不再从内存里数。 */
        ledgerId: String?,
        imageBytes: ByteArray?,
        scanned: MutableList<ScannedBill>,
        failures: MutableList<ScanFailure>,
    ): Int {
        val rows = graph.ingestDrafts.rows(result, source)
        if (rows.isEmpty()) {
            failures += ScanFailure(imageIndex, humanReason(result.reason), ledgerId)
            return 0
        }
        var duplicates = 0
        rows.forEach { row ->
            if (row.duplicate) {
                duplicates += 1
                return@forEach
            }
            val key = graph.pendingIngestStore.reserveKeys(1)
            rowsByKey[key] = row
            if (imageBytes != null) imageBytesByKey[key] = imageBytes
            scanned += scannedOf(
                key = key,
                imageIndex = imageIndex,
                source = source,
                createdAt = System.currentTimeMillis(),
                orderNo = row.externalId,
                auto = false,
                row = row,
                checked = true,
            )
        }
        return duplicates
    }

    /** 卡上一行与落盘一条的唯一构造口：两处各写一遍迟早会漏字段。 */
    private fun scannedOf(
        key: Int,
        imageIndex: Int,
        source: IngestSource,
        createdAt: Long,
        orderNo: String?,
        auto: Boolean,
        row: DraftRow,
        checked: Boolean,
    ) = ScannedBill(
        key = key,
        imageIndex = imageIndex,
        source = source,
        createdAt = createdAt,
        orderNo = orderNo,
        auto = auto,
        amountMinor = row.amountMinor,
        ledgerId = row.ledgerId,
        type = runCatching { BillType.valueOf(row.type) }.getOrDefault(BillType.EXPENSE),
        occurredAt = row.occurredAt,
        categoryL1Id = row.categoryL1Id,
        categoryL2Id = row.categoryL2Id,
        categoryLabel = row.categoryLabel ?: row.categoryL1Id,
        merchant = row.merchant,
        note = row.note,
        grade = row.grade,
        missing = row.missing,
        dateDefaulted = row.dateDefaulted,
        dateUnresolved = row.dateUnresolved,
        externalId = row.externalId,
        imageSha = row.imageSha,
        keyword = row.keyword,
        noteFromAi = row.noteFromAi,
        checked = checked,
    )

    /** 把当前这份清单写进文件：识别完、存掉一笔、探测到空，都要跟着走一次。 */
    private suspend fun persist() {
        val current = state.value
        // 「已经记过」的不在 bills 里，写下来的一律是还要人确认的行；
        // 空清单就把文件收掉，免得首页那枚「待确认」变成点开就消失的死入口。
        val entries = current.bills.mapNotNull { bill ->
            val row = rowsByKey[bill.key] ?: return@mapNotNull null
            PendingIngestEntry(
                key = bill.key,
                imageIndex = bill.imageIndex,
                checked = bill.checked,
                source = bill.source.name,
                ledgerId = bill.ledgerId,
                createdAt = bill.createdAt,
                orderNo = bill.orderNo,
                auto = bill.auto,
                row = row,
            )
        }
        graph.pendingIngestStore.save(
            source = current.source.name,
            droppedCount = current.droppedCount,
            failures = current.failures.map { PendingFailure(it.imageIndex, it.reason, it.ledgerId) },
            entries = entries,
        )
    }

    /**
     * 把文件里的待确认清单摆回内存。[force] 用于点首页入口时：不覆盖内存里已有的行，
     * 只把文件里**新增**的行（比如后台通知追加的自动草稿）并进来，免得入口点开看不到新行。
     */
    private suspend fun restorePending(force: Boolean = false): Boolean {
        val batch = graph.pendingIngestStore.load()
        // 只认还能动的行：文件里若只剩「已经记过」那些，这一批就等于处理完了，顺手把文件也收掉。
        val allLive = batch.entries.filterNot { it.row.duplicate }
        if (batch.entries.isNotEmpty() && allLive.isEmpty()) {
            graph.pendingIngestStore.clear()
            return false
        }
        val ledger = effectiveLedgerId() ?: return false
        val live = allLive.filter { belongsTo(it, ledger, graph.cachedDefaultLedgerId) }
        if (live.isEmpty()) return false
        // 已经开扫了就别再插队：这时 bills 还可能是空的，硬摆回去会盖掉正在跑的那一批。
        val current = state.value
        if (current.busy || current.stage != null) return false
        if (!force && current.bills.isNotEmpty()) return false
        val existingKeys = current.bills.map { it.key }.toSet()
        val incoming = live.filter { it.key !in existingKeys }
        // 新进来的行才补进 rowsByKey，别覆盖内存里已经改过的那一份。
        incoming.forEach { entry -> rowsByKey.putIfAbsent(entry.key, entry.row) }
        val source = runCatching { IngestSource.valueOf(live.first().source) }
            .getOrDefault(IngestSource.SCREENSHOT)
        val failures = batch.failures
            .filter { (it.ledgerId ?: graph.cachedDefaultLedgerId) == ledger }
            .map { failure -> ScanFailure(failure.imageIndex, failure.reason, failure.ledgerId) }
        state.update {
            it.copy(
                busy = false,
                stage = null,
                source = source,
                restored = true,
                droppedCount = batch.droppedCount,
                failures = failures,
                bills = current.bills + incoming.map { entry ->
                    scannedOf(
                        key = entry.key,
                        imageIndex = entry.imageIndex,
                        source = runCatching { IngestSource.valueOf(entry.source) }
                            .getOrDefault(IngestSource.SCREENSHOT),
                        createdAt = entry.createdAt,
                        orderNo = entry.orderNo,
                        auto = entry.auto,
                        row = entry.row,
                        checked = entry.checked,
                    )
                },
            )
        }
        DebugLog.log("识别管线", "恢复待确认清单：${(current.bills + incoming).size} 笔")
        return true
    }

    /**
     * 首页那枚「待确认 N 笔」：先把文件里的清单摆回内存再开卡。
     *
     * 关掉卡只是 [clear]（内存），文件还留着——所以入口亮着的时候内存里可能一行都没有，
     * 那时直接开卡会立刻被 settled 收掉，看起来就是「闪一下什么都没有」。
     */
    fun openPending(onReady: () -> Unit) {
        viewModelScope.launch {
            if (restorePending(force = true)) onReady()
        }
    }

    fun toggle(key: Int) = state.update {
        it.copy(
            bills = it.bills.map { bill ->
                if (bill.key == key) {
                    bill.copy(checked = !bill.checked)
                } else {
                    bill
                }
            },
        )
    }

    /**
     * 卡上某一行右侧的「修改」：只把这一行带进记账页。
     * 每一次跳转都从 [nextToOpen] 走：页面只接一条信号，不必为两条路各写一套导航。
     */
    fun requestEdit(key: Int) {
        queueMode = false
        mutableNext.value = markEditing(key)
    }

    /** 卡底那颗「逐条确认」：从第一笔开始，保存一笔自动接下一笔。 */
    fun requestQueue() {
        val first = state.value.bills.firstOrNull() ?: return
        queueMode = true
        mutableNext.value = markEditing(first.key)
    }

    /** 页面已经把这一条交出去了：清掉信号，等保存回来再给下一条。 */
    fun consumeNext() {
        mutableNext.value = null
    }

    /** 标记这一行进入记账页，但保留整张卡；保存成功才真正移除。 */
    private fun markEditing(key: Int): ScannedBill? {
        val bill = state.value.bills.firstOrNull { it.key == key } ?: return null
        // 已经记过的那行不进记账页：那一页的「保存」是再记一笔，不是改这一笔。
        if (bill.editing) return null
        editingKey = key
        state.update { current ->
            current.copy(bills = current.bills.map { row -> if (row.key == key) row.copy(editing = true) else row })
        }
        // 把这一行的去重标识（以及「账单图片」开着时那张原图）交给记账页，保存时一起落下。
        rowsByKey[key]?.let { row ->
            graph.ingestDrafts.rememberIdentity(row.externalId, row.imageSha)
            graph.ingestDrafts.rememberAttachment(imageBytesByKey[key])
        }
        return bill.copy(editing = true)
    }

    /** 记账页保存成功：移除这一行，逐笔确认时顺手把下一笔交出去。 */
    fun onManualSaved(event: BillSavedEvent) {
        val key = editingKey ?: return
        editingKey = null
        rowsByKey.remove(key)
        imageBytesByKey.remove(key)
        state.update { current ->
            current.copy(bills = current.bills.filterNot { row -> row.key == key })
        }
        // 这一行已经在记账页保存过了，必须精确从文件里摘掉；
        // 用 persist() 会被 PendingIngestStore 的「合并追加」再写回去，回到首页又冒出来。
        viewModelScope.launch { graph.pendingIngestStore.removeKeys(setOf(key)) }
        if (!queueMode) return
        val next = state.value.bills.firstOrNull()
        if (next == null) {
            queueMode = false
            return
        }
        markEditing(next.key)?.let { bill -> mutableNext.value = bill }
    }

    /** 卡上某一行右侧的「×」：这一笔不要了，内存与落盘清单一起摘掉。 */
    fun remove(key: Int) {
        rowsByKey.remove(key)
        imageBytesByKey.remove(key)
        if (editingKey == key) editingKey = null
        state.update { current -> current.copy(bills = current.bills.filterNot { it.key == key }) }
        viewModelScope.launch { graph.pendingIngestStore.removeKeys(setOf(key)) }
    }

    /** 从记账页没保存就返回：解除编辑标记，这一笔重新可点。 */
    fun cancelEdit() {
        val key = editingKey ?: return
        editingKey = null
        state.update { current ->
            current.copy(bills = current.bills.map { row -> if (row.key == key) row.copy(editing = false) else row })
        }
    }

    /** 存入勾选的几笔。分类没认出的那一律挡在这里，不塞进「其他」后静默落库。 */
    fun saveChecked(onSaved: (Int) -> Unit) {
        val current = state.value
        val target = current.checkedBills.filterNot { it.editing }
        if (target.isEmpty() || target.any { it.needsCategory }) return
        state.update { it.copy(busy = true, stage = "正在写入…") }
        viewModelScope.launch(Dispatchers.IO) {
            val failures = current.failures.toMutableList()
            try {
                val jobs = target.mapNotNull { bill -> rowsByKey[bill.key]?.let { bill to it } }
                // 落库结果按行对齐返回：金额、日期、备注都相同的两笔各有各的 id，
                // 按内容对齐会把它们认成同一笔，报出来的笔数与附件都会错位。
                val saved = graph.ingestDrafts.saveBatch(jobs.map { (_, row) -> row }) { index ->
                    imageBytesByKey[jobs[index].first.key]
                }
                val keys = mutableSetOf<Int>()
                jobs.forEachIndexed { index, (bill, _) ->
                    if (saved.getOrNull(index) != null) keys += bill.key
                    else failures += ScanFailure(bill.imageIndex, "这一笔没有写进去", bill.ledgerId)
                }
                state.update {
                    it.copy(
                        busy = false,
                        stage = null,
                        bills = it.bills.filterNot { row -> row.key in keys },
                        failures = failures,
                    )
                }
                keys.forEach(imageBytesByKey::remove)
                // 同上：已保存的行要精确移除，不能走合并写回。
                graph.pendingIngestStore.removeKeys(keys)
                onSaved(keys.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(busy = false, stage = null, error = failure.message ?: "写入没有完成") }
            }
        }
    }

    /** 用户明确不要这一批了：只摘掉卡上正在展示的这几行，以前攒下的待确认不动。 */
    fun discard() {
        val keys = state.value.bills.map { it.key }.toSet()
        viewModelScope.launch { graph.pendingIngestStore.removeKeys(keys) }
        clear()
    }

    /** 只清页面上的这一份状态；文件里的清单留着——关掉弹窗不等于不要了。 */
    fun clear() {
        rowsByKey.clear()
        imageBytesByKey.clear()
        editingKey = null
        queueMode = false
        mutableNext.value = null
        state.value = MaterialBatchUiState()
    }

    private fun effectiveLedgerId(): String? = graph.activeLedgerId ?: graph.cachedDefaultLedgerId

    /** 老缓存里没有账本标记的行按默认账本算，跟「全部账本」下新识别出来的口径一致。 */
    private fun belongsTo(entry: PendingIngestEntry, ledger: String, default: String?): Boolean =
        (entry.ledgerId ?: entry.row.ledgerId ?: default) == ledger

    private fun gradeOrder(bill: ScannedBill): Int = when {
        bill.needsCategory || bill.dateUnresolved || bill.missing.isNotEmpty() -> 0
        else -> 1
    }

    companion object {
        /** 一次最多接几张素材：多图分享与相册多选共用这一条上限，超出的当场丢掉并如实报数。 */
        const val MAX_IMAGES = 9
    }
}

private fun humanReason(reason: String?): String = when (reason) {
    null, "", "没识别到" -> "这张没识别到"
    else -> reason
}

/**
 * 识别阶段那几行字：本机三步 + AI 一步，别在文案里提「小莉」——这里调的是 AI 提取能力。
 *
 * 只报**现在在做哪一步**，进度（`3/9 张`）由弹窗另起一行——两者拼在同一行会忽长忽短地跳。
 */
internal fun stageLabel(source: IngestSource, stage: IngestStage): String {
    if (source == IngestSource.VOICE) return when (stage) {
        IngestStage.LLM -> "AI 提取中"
        else -> "正在识别中"
    }
    return when (stage) {
        IngestStage.OCR -> "本机 OCR 识图中"
        IngestStage.REDACT -> "本机脱敏中"
        IngestStage.RULES -> "规则匹配中"
        IngestStage.LLM -> "AI 提取中"
    }
}
