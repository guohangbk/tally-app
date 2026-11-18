package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.image.TallyImages
import io.github.guohangbk.tally.domain.ai.AiBillFields
import io.github.guohangbk.tally.domain.ai.AiBillTool
import io.github.guohangbk.tally.domain.ai.AiDraftSchema
import io.github.guohangbk.tally.domain.ai.AiPrompts
import io.github.guohangbk.tally.domain.ai.AiRequest
import io.github.guohangbk.tally.domain.ai.AiToolChoice
import io.github.guohangbk.tally.domain.ai.AiTurn
import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.parser.QuickNoteCatalog
import java.time.ZoneId

/**
 * 小莉的抽账调用：一句话或一张图进去，一笔待确认的账单出来。
 *
 * 这里没有任何关键词与正则匹配——收支方向、日期、分类、备注全部由模型通过 `propose_bills`
 * 工具返回，代码只做三件事：给模型全机分类 id 表、校验它给的 id 真的存在、
 * 把合格结果变成草稿。判断错的代价由用户在确认卡片上一眼改掉，不静默落库。
 *
 * 走 [AiGateway.enhance] 而不是 call：小莉不可用时静默跳过，对话本身绝不因此中断。
 */
class AiBillDraftService(
    private val gateway: AiGateway,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** 对话通道：用户亲手发的图直接交给模型看。 */
    suspend fun extract(
        message: String,
        images: List<ByteArray>,
        catalog: QuickNoteCatalog,
        /** 最近几句话 + 上一张待确认卡：追问「第二笔记一下」全靠它，没有就只能回 not_a_bill。 */
        history: List<AiTurn> = emptyList(),
        fallbackMillis: Long = now(),
        localRawText: String? = null,
    ): BillDraftOutcome {
        return extractInternal(message, images, catalog, history, fallbackMillis, localRawText)
    }

    /** 管线的小莉补缺入口：只拿脱敏文本，原图不出设备。 */
    suspend fun extractFields(
        message: String,
        catalog: QuickNoteCatalog,
        fallbackMillis: Long = now(),
        localRawText: String? = null,
        batch: Boolean = false,
    ): BillDraftOutcome {
        return extractInternal(
            message = message,
            images = emptyList(),
            catalog = catalog,
            history = emptyList(),
            fallbackMillis = fallbackMillis,
            localRawText = localRawText,
            batch = batch,
        )
    }

    private suspend fun extractInternal(
        message: String,
        images: List<ByteArray>,
        catalog: QuickNoteCatalog,
        history: List<AiTurn>,
        fallbackMillis: Long = now(),
        localRawText: String? = null,
        batch: Boolean = false,
    ): BillDraftOutcome {
        val startedAt = System.nanoTime()
        val availability = gateway.availability()
        if (!availability.showsEntry) {
            return BillDraftOutcome.Unavailable(AiUnavailable.NotConfigured.reason)
        }
        val refs = catalog.categories
        if (refs.isEmpty()) return BillDraftOutcome.Unavailable("还没有可选的分类")

        // 压不出来的图会被 mapNotNull 静默丢掉：日志按"发出去几张"记，不再按"用户选了几张"记。
        val prepared = images.mapNotNull { TallyImages.forModel(it) }
        if (prepared.size != images.size) {
            DebugLog.log("AI 抽账", "有 ${images.size - prepared.size} 张图压不出来，这一轮只发了 ${prepared.size} 张")
        }
        val vision = availability.visionCapable && prepared.isNotEmpty()
        // 本机 OCR 由识别管线统一做一次；这里拿到的原文只用于判级复核与文本形态的抽账。
        val rawText = localRawText ?: message
        val textForModel = message
        val turn = when {
            message.isBlank() && prepared.isEmpty() -> return BillDraftOutcome.Unavailable("先说一句要记的内容。")
            prepared.isEmpty() -> AiTurn.user(textForModel)
            // 视觉模型一次看完这几张图；不支持图片时 OCR 只负责「把图变成字」，判断仍然交给模型。
            vision -> AiTurn.userWithImages(imageLead(message, prepared.size), prepared.map { it.dataUrl() })
            textForModel.isBlank() -> null
            else -> AiTurn.user(textForModel)
        }
        if (turn == null) return BillDraftOutcome.Unavailable("这些图里一个字都没读到")
        val timestamp = now()
        DebugLog.log(
            "AI 抽账",
            when {
                images.isEmpty() -> "文字请求"
                vision -> "${prepared.size} 张图 · ${prepared.sumOf { it.bytes.size } / 1024}KB · 原图离开设备"
                else -> "模型不看图，先 OCR 成字"
            },
        )
        val toolCatalog = AiBillTool.catalog(refs)
        val request = AiRequest(
            instruction = AiPrompts.billExtractionInstruction(
                timeAnchor = AiPrompts.timeAnchor(timestamp, zone),
                categoryTable = toolCatalog.table(),
                historyNote = if (history.isEmpty()) "" else HISTORY_NOTE,
                keywordTable = toolCatalog.keywordTable(catalog.keywords),
            ),
            // 上文放在消息里，不塞进指令：模型按"接着上一句说"来理解「第二笔」这种指代。
            turns = history + turn,
            // 网关不认 tools 时，同一份字段定义改走 response_format；文本形态由 AiBillTool 剥围栏后解析。
            jsonSchema = AiDraftSchema.billsJsonSchema(),
            tools = toolCatalog.tools,
            // Required 而不是指定 propose_bills：提问与闲聊要走 not_a_bill，否则每条消息都会弹一张假账卡。
            toolChoice = AiToolChoice.Required,
            // 一句「早20 中5 地铁30」要出三笔：并行必须开着，关掉就等于替模型决定"一次只让记一笔"。
            allowParallelTools = true,
        )
        return when (val outcome = gateway.enhance(request)) {
            is AiEnhancement.Skipped -> {
                val reason = outcome.failure?.let { failure -> failure.detail ?: failure.kind.userMessage }
                    ?: AiUnavailable.Disconnected.reason
                DebugLog.log("AI 抽账", "跳过：$reason")
                BillDraftOutcome.Unavailable(reason)
            }

            is AiEnhancement.Applied -> {
                val completion = outcome.completion
                DebugLog.log(
                    "AI 抽账",
                    "完成 ${(System.nanoTime() - startedAt) / 1_000_000}ms · 模型 ${completion.latencyMillis}ms · " +
                        if (completion.toolCalls.isEmpty()) "按文本解析 · ${completion.text.take(160)}"
                        else completion.toolCalls.joinToString(" · ") { call ->
                            "工具 ${call.name}（${call.arguments.length}字${if (completion.finishReason == "length") "，已截断" else ""}）"
                        },
                )
                when (val verdict = AiBillTool.readAll(completion, toolCatalog, fallbackMillis, zone, batch = batch)) {
                    // 没有金额就没有可确认的一笔：卡片宁可不出，也不要出一张要人手补三格的空卡。
                    is AiBillTool.BillsVerdict.Some -> {
                        val accepted = verdict.accepted
                        val drafts = accepted.filter { it.fields.amountMinor != null }
                        verdict.droppedReasons.forEach { DebugLog.log("AI 抽账", "有一笔被丢掉：$it") }
                        // 模型给了这一笔却因为没金额被丢：以前一声不吭，用户只看到"少了一张卡"。
                        accepted.filter { it.fields.amountMinor == null }.forEach { bill ->
                            DebugLog.log(
                                "AI 抽账",
                                "有一笔没带金额，只能丢掉：分类 ${bill.fields.categoryL1Id ?: "未认出"} · " +
                                    "备注 ${bill.fields.note ?: bill.fields.merchant ?: "-"}",
                            )
                        }
                        if (drafts.isEmpty()) {
                            DebugLog.log("AI 抽账", "没有金额，不弹卡")
                            return BillDraftOutcome.NotABill
                        }
                        DebugLog.log("AI 抽账", "草稿 ${drafts.size} 笔 · " + drafts.joinToString("；") { bill ->
                            "${bill.fields.type?.name ?: "方向未定"} ${bill.fields.amountMinor}分 · " +
                                "分类 ${bill.fields.categoryL1Id ?: "未认出"}"
                        })
                        BillDraftOutcome.Draft(
                            bills = drafts.map { ExtractedBill(it.fields, it.needsConfirmation) },
                            // 判级要拿它复核金额与订单号，所以一路带到 ViewModel。
                            rawText = rawText,
                        )
                    }

                    is AiBillTool.BillsVerdict.NotABill -> {
                        DebugLog.log("AI 抽账", "not_a_bill：${verdict.reason}")
                        BillDraftOutcome.NotABill
                    }

                    is AiBillTool.BillsVerdict.Rejected -> {
                        DebugLog.log("AI 抽账", "不合格：${verdict.reason}")
                        BillDraftOutcome.Rejected(verdict.reason)
                    }
                }
            }
        }
    }

    /** 图文消息里用户自己那句话也要带上，它常常就是分类与备注的来源（「这张是上周的团建」）。 */
    private fun imageLead(message: String, count: Int): String {
        val lead = if (count == 1) IMAGE_LEAD else IMAGE_LEADS
        return if (message.isBlank()) lead else "$lead\n用户附的话：$message"
    }

    companion object {
        private const val IMAGE_LEAD = "这是用户发来的一张图，把其中实际支付或收到的每一笔账整理成待确认草稿。"

        /** 多图同一条消息：几张可能是同一个人在不同平台付的钱，要分开记，不许合并成一笔。 */
        private const val IMAGE_LEADS =
            "这是用户发来的几张图，逐张看：每张里有几笔实际收支就整理几笔，不要把不同订单的金额合并成一笔。"

        private const val HISTORY_NOTE =
            "本轮之前还有对话与一张待确认的账单卡，它们作为消息一起给出，只用来判断「第二笔」「那笔打车」这类指代；" +
                "卡上已经列出的那些笔不要重复记，除非本轮就是要补记其中某一笔。\n"
    }
}

/** 一次抽账的结局。只有 [Draft] 会弹卡，里面可以是一笔到 [AiDraftSchema.MAX_BILLS] 笔。 */
sealed interface BillDraftOutcome {
    data class Draft(
        val bills: List<ExtractedBill>,
        /** 这一轮识别用过的原文（用户那句话 + 图里的字）。本机判级拿它复核金额与订单号。 */
        val rawText: String,
    ) : BillDraftOutcome

    /** 模型判定这条消息里没有一笔账：不弹卡，也不算出错。 */
    data object NotABill : BillDraftOutcome

    data class Rejected(val reason: String) : BillDraftOutcome

    data class Unavailable(val reason: String) : BillDraftOutcome
}

/**
 * 模型读出的一笔：[fields] 是它填的字段，[needsConfirmation] 是它自己标的「哪几项没把握」。
 *
 * 后者不能丢——判级里「日期是原文给的」与「分类真的对上了」两条都从它来，
 * 丢掉了就只能退回去猜正则，那还不如不判。
 */
data class ExtractedBill(
    val fields: AiBillFields,
    val needsConfirmation: Set<DraftField>,
)
