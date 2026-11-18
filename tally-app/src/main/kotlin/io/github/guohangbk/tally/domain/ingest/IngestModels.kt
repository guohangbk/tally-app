package io.github.guohangbk.tally.domain.ingest

import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.ai.AiTurn

/** 这条素材是怎么进来的：它是**消息的属性**，不是账的来源——小莉说文字、说语音、发图，都算小莉记的。 */
enum class InputKind { TEXT, VOICE, IMAGE }

/**
 * 采集入口只负责把素材交出来，差别不在识别方式。
 * [billSource] 是这笔账最终记在谁名下，[inputKind] 只用来在卡上标一句「图片 / 语音 / 文字」。
 */
enum class IngestSource(val billSource: BillSource, val inputKind: InputKind) {
    VOICE(BillSource.VOICE, InputKind.VOICE),
    SCREENSHOT(BillSource.SCREENSHOT, InputKind.IMAGE),
    SHARE(BillSource.SCREENSHOT, InputKind.IMAGE),
    CHAT(BillSource.ASSISTANT, InputKind.TEXT),
}

/** 每个字段从哪里来：本机规则抽的标 `RULE`，AI 全量覆盖过的标 `LLM`，没抽到标 `MISSING`。 */
enum class FieldOrigin { RULE, LLM, MISSING }

data class IngestRequest(
    val source: IngestSource,
    /** 用户亲手打的字：不脱敏、不改写，原样给模型。 */
    val userText: String = "",
    /**
     * 语音转写出来的那句话的**脱敏版**：转写是机器出的，同样可能带上卡号、手机号，
     * 外发一律用它。人亲手打的字不走这里——他要发给自己的服务商，原样给。
     */
    val redactedUserText: String = "",
    /** 图片 OCR 出的完整原文，只留在本机做判级与去重。 */
    val ocrText: String = "",
    val images: List<ByteArray> = emptyList(),
    val nowMillis: Long,
    val fallbackMillis: Long = nowMillis,
    val singleMaterial: Boolean = true,
    /** 图片 OCR 的脱敏外发文本；用户手打的字永远走 [userText]，不改写。 */
    val redactedOcrText: String = "",
    /** 只有对话通道带结构化上下文；素材通道不进对话历史。 */
    val history: List<AiTurn> = emptyList(),
    /** 规则缺金额或缺分类时才问 AI；三路入口的开关各管一段（见 `MaterialReader`）。 */
    val allowLlm: Boolean = true,
    /** 多图批量：每段文字属于第几张图，模型回填时按它拆回去。 */
    val materialIndex: Int? = null,
    /** 只在批量补缺时挂：本机阶段推进给首页状态卡，不给页面造假进度。 */
    val onStage: ((IngestStage, Int, Int) -> Unit)? = null,
)

/** 识别管线的真实阶段；[index] / [total] 从 1 开始，只对一份份素材有意义。 */
enum class IngestStage { OCR, REDACT, RULES, LLM }

/** 规则与小莉共用的字段形状；[origins] 逐格标出来源。 */
data class ExtractedBill(
    val amountMinor: Long?,
    val type: BillType?,
    val occurredAt: Long?,
    val timePrecision: TimePrecision,
    val categoryL1Id: String?,
    val categoryL2Id: String?,
    val note: String?,
    val merchant: String?,
    val orderNo: String?,
    val origins: Map<String, FieldOrigin>,
    /** 模型自己标「没把握」的字段；判级只在这里认日期是不是原文给的。 */
    val needsConfirmation: Set<String> = emptySet(),
    /** 批量补缺时，这一笔属于第几张图（0 起）；单张与对话为 null。 */
    val materialIndex: Int? = null,
    /** 同一张图里的第几笔订单（0 起）；单笔与对话为 null。 */
    val itemIndex: Int? = null,
    /** 本机关键词表命中分类的那个词：记账页保存时据此决定要不要沉淀（命中过就不用教）。 */
    val keyword: String? = null,
) {
    /**
     * 日期是原文（含模型从原文推出来的相对说法）给的，而不是补出来的：
     * 判级据此区分「认出来的日期」与「按采集时刻落的账」。
     */
    val dateFromText: Boolean
        get() = origins[IngestKeys.DATE] != null &&
            origins[IngestKeys.DATE] != FieldOrigin.MISSING &&
            DraftField.DATE.name !in needsConfirmation
}

/** [ExtractedBill.origins] 的键。规则与小莉两条路共用同一套，别再各写一份字符串。 */
object IngestKeys {
    const val AMOUNT = "amountMinor"
    const val TYPE = "type"
    const val DATE = "occurredAt"
    const val CATEGORY = "category"
    const val NOTE = "note"
    const val MERCHANT = "merchant"
    const val ORDER = "orderNo"
}

/** 本机复核与去重所需的原始事实，不进外发请求，也不进日志。 */
data class IngestEvidence(
    val rawText: String,
    val orderNo: String?,
    val imageSha: String?,
    val redactedCount: Int,
    val singleBill: Boolean,
    val fallbackMillis: Long,
    /** 同一张图里的行序号：多笔订单查重时不能互相认成同一笔。 */
    val itemIndex: Int? = null,
)

/** 管线产出：同一张卡上的每一笔，带本机判级与去重标记。 */
data class IngestBill(
    val fields: ExtractedBill,
    val verdict: IngestVerdict,
    val evidence: IngestEvidence,
)

data class IngestResult(
    val bills: List<IngestBill>,
    /** 没有可确认的一笔时给页面一句人话；有卡时为空。 */
    val reason: String? = null,
)

sealed interface RuleBill {
    data class Single(val bill: ExtractedBill) : RuleBill
    data class Multiple(val bills: List<ExtractedBill>) : RuleBill
    /** 本机拆不出有金额的账单、或订单块拆不开：才交给 AI 整理成确认草稿。 */
    data object NeedsLlm : RuleBill
    data object None : RuleBill
}

interface RuleExtractor {
    fun extract(request: IngestRequest, text: String): RuleBill
}

interface LlmExtractor {
    suspend fun extract(
        request: IngestRequest,
        outboundText: String,
        rawText: String,
        missing: List<String>,
    ): List<ExtractedBill>

    /** 对话通道：不加规则过滤，由模型判意图；亲手发的图保持原有对话行为。 */
    suspend fun extractChat(
        request: IngestRequest,
        message: String,
        rawText: String,
        history: List<AiTurn>,
    ): List<ExtractedBill>
}

interface BillIngestor {
    suspend fun ingest(request: IngestRequest): IngestResult
    suspend fun ingestBatch(requests: List<IngestRequest>): List<IngestResult>
}

/** 一条素材的管线可观察阶段。多个入口共享同一套文案，别各写一份假进度。 */
class IngestProgress(
    private val publish: (IngestStage, Int, Int) -> Unit,
) {
    private var total = 1

    fun total(value: Int) {
        total = value.coerceAtLeast(1)
        publish(IngestStage.OCR, 0, total)
    }

    /** 管线每次报告真实阶段；批量里的每一份素材共用同一个总数。 */
    fun callback(): (IngestStage, Int, Int) -> Unit = { stage, done, count ->
        val at = if (stage == IngestStage.LLM || done >= count) total else done
        publish(stage, at.coerceAtMost(total), total)
    }
}

/** 完整订单号只用来在本机查重，不进外发文本。 */
interface DuplicateLookup {
    suspend fun isDuplicate(orderNo: String?, imageSha: String?, itemIndex: Int? = null): Boolean
}
