package io.github.guohangbk.tally.ui.assistant

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.ai.AiAvailability
import io.github.guohangbk.tally.data.ai.BillDraftOutcome
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.ai.AiTurn
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.AiAnswer
import io.github.guohangbk.tally.domain.model.AiChatEntry
import io.github.guohangbk.tally.domain.model.AiConversation
import io.github.guohangbk.tally.domain.model.AiMessageType
import io.github.guohangbk.tally.domain.model.BillDraft
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.DraftRow
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.parser.QuickNoteCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小莉从一条消息里读出来、等人确认的一笔账。
 * 识别由云端完成：模型调用 `propose_bills` 工具，回传收支方向、日期、分类 id、
 * 备注与金额这几个固定参数（见 AiBillTool），代码只做逐条校验；端侧不再有关键词与正则匹配。
 * 校验合格也只是「弹一张卡」，点确认才落库。
 */
data class ChatBillDraft(
    val amountMinor: Long,
    val type: BillType,
    val categoryL1Id: String?,
    val categoryL2Id: String?,
    val categoryLabel: String?,
    val note: String?,
    val occurredAt: Long,
    val source: BillSource,
    val merchant: String?,
    val saved: Boolean = false,
    val dismissed: Boolean = false,
) {
    val needsCategory: Boolean get() = categoryL1Id == null

    /** 还等人表态的那一笔：已记入和已不要的都不该再被点一次。 */
    val pending: Boolean get() = !saved && !dismissed
}

/**
 * 选好、还没发出的图片。
 * [thumbPath] 是选中那一刻就压好的缩略图（相对路径），待发条与发送后的气泡共用同一张——
 * 当场解 content:// URI 在某些机型上会静默失败，只剩一枚灰图标，看着就像"图没选上"。
 */
data class PendingImage(val uri: Uri, val thumbPath: String? = null)

/**
 * 小莉对话。
 *
 * 上下文只带会话摘要 + 最近若干条，由 AiConversationRepository 负责裁剪；
 * 调用失败不会丢消息——它会以 FAILED 状态留在列表里，旁边永远带着重试入口。
 * 每条消息并行发两次调用：一次回话（流式），一次抽账（工具调用，非流式）。
 * 抽账成功就在该条消息下面挂一张待确认记账卡，点了确认才落库（[confirmDraft]）。
 */
data class AssistantChatUiState(
    val loaded: Boolean = false,
    val conversationId: String? = null,
    val title: String = "",
    val entries: List<AiChatEntry> = emptyList(),
    val conversations: List<AiConversation> = emptyList(),
    val input: String = "",
    val working: Boolean = false,
    val showList: Boolean = false,
    val availability: AiAvailability? = null,
    val notice: String? = null,
    val error: String? = null,
    /** 已经选好、还没发出的图片，最多 [AssistantChatViewModel.MAX_PENDING_IMAGES] 张；发出去就清空。 */
    val pendingImages: List<PendingImage> = emptyList(),
    /** 流式回复进行中的半截文字；落库后清空，列表里用临时气泡显示。 */
    val streaming: String? = null,
    /** 话已答完、卡片还在生成中的那一段：不给个状态，人会以为这一句根本不会出卡。 */
    val draftPending: Boolean = false,
) {
    val canSend: Boolean get() = !working && (input.isNotBlank() || pendingImages.isNotEmpty())
    val isEmptyConversation: Boolean get() = entries.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantChatViewModel(
    private val graph: AppGraph,
    initialPrompt: String? = null,
) : ViewModel() {
    private val store = graph.aiConversationRepository
    // 统计页「问小莉」带过来的结论只填进输入框，不自动发送——发不发由人决定。
    private val state = MutableStateFlow(AssistantChatUiState(input = initialPrompt.orEmpty()))
    val uiState: StateFlow<AssistantChatUiState> = state.asStateFlow()

    private val conversationId = MutableStateFlow<String?>(null)
    private var ledgerId: String? = null
    private var catalog: QuickNoteCatalog = QuickNoteCatalog(emptyList(), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // 分类与关键词表都是全机一套，取目录不再需要默认账本 id。
            ledgerId = graph.ledgerRepository.defaultLedger()?.id
            catalog = graph.keywordRuleRepository.catalog()
            try {
                val latest = store.ensureConversation(null)
                conversationId.value = latest
                state.update {
                    it.copy(
                        loaded = true,
                        conversationId = latest,
                        availability = graph.aiGateway.availability(),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(loaded = true, error = failure.message ?: "对话开不起来") }
            }
        }
        // 配置页把「图片输入」关掉时，这页的图片入口要立刻跟着禁用，不等重进。
        viewModelScope.launch {
            graph.aiConfigRepository.observe().collect {
                state.update { previous -> previous.copy(availability = graph.aiGateway.availability()) }
            }
        }
        viewModelScope.launch {
            store.observeConversations().collect { rows ->
                state.update { previous ->
                    previous.copy(
                        conversations = rows,
                        title = rows.firstOrNull { it.id == previous.conversationId }?.title ?: previous.title,
                    )
                }
            }
        }
        viewModelScope.launch {
            conversationId.flatMapLatest { id ->
                if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else store.observeEntries(id)
            }.collect { entries -> state.update { it.copy(entries = entries) } }
        }
    }

    fun onInputChange(value: String) = state.update { it.copy(input = value) }

    fun toggleList() = state.update { it.copy(showList = !it.showList) }

    fun openConversation(id: String) {
        conversationId.value = id
        state.update { previous ->
            previous.copy(
                conversationId = id,
                showList = false,
                error = null,
                title = previous.conversations.firstOrNull { it.id == id }?.title ?: previous.title,
            )
        }
    }

    /**
     * 加号：另起一段全新对话。当前这段还一句话都没有就别再叠一条空的进去，
     * 否则点两下就多两条「新的对话」，列表里全是没说过话的壳。
     */
    fun newConversation() = runOp("已开始新的对话") {
        val current = state.value.conversationId
        val id = if (current != null && state.value.entries.isEmpty()) current else store.startConversation()
        conversationId.value = id
        state.update { it.copy(conversationId = id, showList = false) }
    }

    fun rename(id: String, title: String) = runOp("已重命名") { store.rename(id, title) }

    /** 删除对话只动对话本身，账单与账单附件不受影响。 */
    fun delete(id: String) = runOp("已删除这段对话") {
        store.delete(id)
        val next = store.ensureConversation(null)
        conversationId.value = next
        state.update { it.copy(conversationId = next) }
    }

    /**
     * 选完图先挂到待发位，每张当场压一张缩略图；压不出来不挡住发消息（待发条退回按 URI 现解）。
     * 一次最多留 [MAX_PENDING_IMAGES] 张：再多就该人在相册里自己挑，而不是让小莉一次看五张。
     */
    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = MAX_PENDING_IMAGES - state.value.pendingImages.size
        if (room <= 0) {
            state.update { it.copy(notice = "一次最多发 $MAX_PENDING_IMAGES 张") }
            return
        }
        val picked = uris.take(room)
        state.update { previous ->
            previous.copy(pendingImages = previous.pendingImages + picked.map { PendingImage(it) }, error = null)
        }
        picked.forEach { uri ->
            viewModelScope.launch(Dispatchers.IO) {
                val path = runCatching {
                    graph.attachmentStore.readSource(uri)?.let { graph.attachmentStore.writeChatThumb(it) }
                }.getOrNull()
                if (path != null) {
                    state.update { previous ->
                        val index = previous.pendingImages.indexOfFirst { it.uri == uri }
                        if (index < 0) previous else previous.withPendingAt(index) { it.copy(thumbPath = path) }
                    }
                }
            }
        }
    }

    fun detachImage(index: Int) =
        state.update { previous -> previous.copy(pendingImages = previous.pendingImages.filterIndexed { i, _ -> i != index }) }

    /** 待发位是列表，改其中一张得整条复制一遍；这个口子只给状态更新用。 */
    private fun AssistantChatUiState.withPendingAt(index: Int, edit: (PendingImage) -> PendingImage): AssistantChatUiState {
        val rows = pendingImages.toMutableList()
        rows[index] = edit(rows[index])
        return copy(pendingImages = rows)
    }

    fun send() {
        val current = state.value
        // 带图的消息必须记成 IMAGE：记成 TEXT 会让气泡按文字渲染，空文字就塌成一个黑块。
        val type = if (current.pendingImages.isNotEmpty()) AiMessageType.IMAGE else AiMessageType.TEXT
        sendInternal(text = current.input, type = type, durationMs = null)
    }

    /** 按住说话、松手发送：识别出的文字就是消息内容，时长留在气泡上。 */
    fun sendVoice(transcript: String, durationMs: Long) {
        sendInternal(text = transcript, type = AiMessageType.VOICE, durationMs = durationMs)
    }

    private fun sendInternal(text: String, type: AiMessageType, durationMs: Long?) {
        val current = state.value
        val id = current.conversationId ?: return
        val question = text.trim()
        if ((question.isEmpty() && current.pendingImages.isEmpty()) || current.working) return
        val uris = current.pendingImages.map { it.uri }
        state.update { it.copy(working = true, input = "", pendingImages = emptyList(), error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // 抽账那一次调用要看得见上文（含上一张待确认卡）：追问「第二笔记一下」本身没有金额，
            // 没有历史模型只能回 not_a_bill，卡片就永远推不出来。趁用户消息还没落库前先取。
            val history = runCatching { store.extractionContext(id) }.getOrDefault(emptyList())
            val bytes = uris.mapNotNull { uri ->
                withContext(Dispatchers.IO) { graph.attachmentStore.readSource(uri) }
            }
            // 抽账与回话是两次独立调用，并行跑：卡片不等回说完才出，回话也不因抽账失败而少一段。
            // runCatching 兜住抽账自身的任何异常——它挂了只意味着这条消息没有卡片，不该带崩对话。
            val draftTask = if (question.isNotEmpty() || bytes.isNotEmpty()) {
                async { runCatching { draftOf(question, type, bytes, history) }.getOrNull() }
            } else {
                null
            }

            suspend fun attachCard(drafts: List<DraftRow>) {
                // 卡片落成对话里的一条 DRAFT 消息，而不是 ViewModel 里的一张 map：
                // 切走再回来、进程被回收之后，卡片和"这几笔已经记过了"都还在。
                if (drafts.isEmpty()) return
                runCatching { store.appendDraft(id, drafts) }
            }
            try {
                val answer = store.ask(id, question, type, durationMs, bytes) { chunk ->
                    state.update { previous -> previous.copy(streaming = (previous.streaming ?: "") + chunk) }
                }
                // 回话一落地就收掉流式气泡：抽账那次调用通常更慢（多带一份分类表），
                // 若等它一起收尾，这段时间里同一段文字会在正式消息和临时气泡上各显示一遍。
                state.update { previous ->
                    previous.copy(
                        working = false,
                        streaming = null,
                        // 抽账通常比回话慢（多带一份分类表）：这一段没有气泡也没有卡，
                        // 不给个状态就会让人以为这一句不出卡了。
                        draftPending = draftTask != null,
                        error = (answer as? AiAnswer.Unavailable)?.reason,
                    )
                }
                attachCard(draftTask?.await().orEmpty())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(working = false, streaming = null, draftPending = false, error = failure.message ?: "小莉暂时联系不上") }
            } finally {
                state.update { previous -> if (previous.draftPending) previous.copy(draftPending = false) else previous }
            }
        }
    }

    /** 卡片上的「记入」：逐条落到默认账本，某一条失败不影响其它条；人已不要的那几笔跳过。 */
    fun confirmDraft(messageId: String) {
        val rows = state.value.entries.firstOrNull { it.id == messageId }?.drafts.orEmpty()
        val ledger = ledgerId ?: return
        val pending = rows.filterNot { it.saved || it.dismissed }
        if (pending.isEmpty()) return
        if (pending.any { it.categoryL1Id == null }) {
            state.update { it.copy(error = "有 ${pending.count { row -> row.categoryL1Id == null }} 笔没认出分类，补上才能记入") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val savedIds = mutableSetOf<String>()
            val failures = mutableListOf<String>()
            pending.forEach { row ->
                try {
                    graph.billRepository.save(row.toBillDraft(ledger))
                    savedIds += row.key()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    failures += failure.message ?: "有一笔没记上"
                }
            }
            // 记上的那一笔在卡上留「已记入」，没记上的留在原地带着原因——半成功要说清楚，不整卡回滚。
            if (savedIds.isNotEmpty()) {
                runCatching {
                    store.updateDraft(messageId, rows.map { row -> if (row.key() in savedIds) row.copy(saved = true) else row })
                }
            }
            val total = pending.filter { it.key() in savedIds }.sumOf { it.amountMinor }
            state.update { previous ->
                previous.copy(
                    notice = if (savedIds.isNotEmpty()) "已记入 ${savedIds.size} 笔 · ¥" + formatAmount(total, grouping = false) else null,
                    error = failures.firstOrNull() ?: if (savedIds.isEmpty()) "这几笔都没有记上" else null,
                )
            }
        }
    }

    /**
     * 单行「先不记」：把这一笔在卡上划掉，气泡与卡片都留着——直接删掉整条会让人怀疑
     * 刚才那句话也一起没了。再点一次「恢复」把它放回待确认。
     */
    fun dismissRow(messageId: String, index: Int) {
        val rows = state.value.entries.firstOrNull { it.id == messageId }?.drafts.orEmpty()
        if (rows.getOrNull(index) == null) return
        val next = rows.mapIndexed { i, row -> if (i == index) row.copy(dismissed = !row.dismissed) else row }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.updateDraft(messageId, next) }
        }
    }

    private fun DraftRow.key(): String = "$amountMinor-$occurredAt-${note.orEmpty()}"

    private fun DraftRow.toBillDraft(ledgerId: String) = BillDraft(
        ledgerId = ledgerId,
        categoryL1Id = requireNotNull(categoryL1Id),
        categoryL2Id = categoryL2Id,
        type = runCatching { BillType.valueOf(type) }.getOrDefault(BillType.EXPENSE),
        amountMinor = amountMinor,
        occurredAt = occurredAt,
        timePrecision = runCatching { TimePrecision.valueOf(timePrecision) }.getOrDefault(TimePrecision.DATE),
        note = note,
        source = runCatching { BillSource.valueOf(source) }.getOrDefault(BillSource.MANUAL),
    )

    /**
     * 让小莉抽账：文字、语音识别出的文字、截图都走同一个工具调用，
     * 模型回传收支方向 / 日期 / 分类 id / 备注 / 金额，代码校验后才成卡。一句话里有几笔就出几笔。
     * 认不出金额、或模型判定「这不是账」就不推卡片——宁可不推，也不推一张要人手改三处的假账。
     */
    private suspend fun draftOf(
        question: String,
        type: AiMessageType,
        images: List<ByteArray>,
        history: List<AiTurn> = emptyList(),
    ): List<DraftRow> {
        // 分类表每次发送前重取一次：开着这页改了分类，下一句就该按新表认，
        // 不能拿进页时那份旧目录——新加的类目认不出、删掉的还在往里塞。
        catalog = graph.keywordRuleRepository.catalog()
        val outcome = graph.aiBillDraftService.extract(question, images, catalog, history)
        if (outcome !is BillDraftOutcome.Draft) return emptyList()
        val source = when {
            images.isNotEmpty() -> BillSource.SCREENSHOT
            type == AiMessageType.VOICE -> BillSource.VOICE
            else -> BillSource.MANUAL
        }
        return outcome.fields.mapNotNull { fields ->
            val amount = fields.amountMinor ?: return@mapNotNull null
            DraftRow(
                amountMinor = amount,
                // 方向由模型定，它给不出时按支出：记账场景里花钱远多于进账，猜错的这一眼在卡片上看得见。
                type = (fields.type ?: BillType.EXPENSE).name,
                occurredAt = fields.occurredAt,
                timePrecision = fields.timePrecision.name,
                source = source.name,
                categoryL1Id = fields.categoryL1Id,
                categoryL2Id = fields.categoryL2Id,
                categoryLabel = (fields.categoryL2Id ?: fields.categoryL1Id)?.let { catalog.categoryOf(it)?.name },
                note = fields.note ?: fields.merchant,
                merchant = fields.merchant,
            )
        }
    }

    fun retry(messageId: String) {
        state.update { it.copy(working = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val answer = store.retry(messageId)) {
                    is AiAnswer.Reply -> {
                        state.update { it.copy(working = false, draftPending = true) }
                        // 重试只重发了回话，卡片要跟着补一次：上次多半正是因为网络才连抽账一起没成。
                        // 重试拿不到原图，只按图上的那句话说文字，认不出就不推卡。
                        val drafts = runCatching {
                            draftOf(answer.question.content, answer.question.type, emptyList())
                        }.getOrDefault(emptyList())
                        val conversation = conversationId.value
                        if (drafts.isNotEmpty() && conversation != null) {
                            runCatching { store.appendDraft(conversation, drafts) }
                        }
                        state.update { it.copy(draftPending = false) }
                    }

                    is AiAnswer.Unavailable -> state.update { it.copy(working = false, error = answer.reason) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(working = false, error = failure.message ?: "重试也没有接上") }
            }
        }
    }

    fun refreshAvailability() = viewModelScope.launch {
        state.update { it.copy(availability = graph.aiGateway.availability()) }
    }

    fun dismissMessages() = state.update { it.copy(notice = null, error = null) }

    private fun runOp(notice: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                state.update { it.copy(notice = notice, error = null) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(error = failure.message ?: "这一步没有完成") }
            }
        }
    }

    companion object {
        /**
         * 一条消息最多带三张图：够把「水费、电费、燃气」一次问完，再多就该在相册里先挑一轮，
         * 而且每张都要压一遍、模型也要多看一张，抽账会更慢。
         */
        const val MAX_PENDING_IMAGES = 3
    }
}
