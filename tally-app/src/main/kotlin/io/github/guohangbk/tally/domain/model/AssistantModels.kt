package io.github.guohangbk.tally.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 小莉对话与报告的读取模型。页面只认这几个类型，不直接碰 Room 实体。
 */
data class AiConversation(
    val id: String,
    val title: String,
    val contextSummary: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AiChatEntry(
    val id: String,
    val role: AiMessageRole,
    val content: String,
    val type: AiMessageType,
    val status: AiMessageStatus,
    val errorCode: String?,
    /** 语音消息的录音时长；气泡上的「语音 0:04」与小波形用它画。 */
    val durationMs: Long? = null,
    /** 发出去的图片在本机的缩略图相对路径，按用户选图的顺序；没有就是没图。 */
    val imagePaths: List<String> = emptyList(),
    /** [AiMessageType.DRAFT] 那条才有：一组待确认/已记入的账单。 */
    val drafts: List<DraftRow> = emptyList(),
    val sequence: Int,
    val createdAt: Long,
) {
    val isFromUser: Boolean get() = role == AiMessageRole.USER

    /** SYSTEM 消息只用于拼上下文，不展示给用户（技术设计 §8.4 约束 2）。 */
    val isVisible: Boolean get() = role != AiMessageRole.SYSTEM

    val failed: Boolean get() = status == AiMessageStatus.FAILED

    /** 记账卡不算对话文本：拼上下文、折叠摘要时都要跳过，否则会把一坨 JSON 喂回模型。 */
    val isDraft: Boolean get() = type == AiMessageType.DRAFT
}

/**
 * 卡片上的一笔账单。[saved] 是它有没有已经落库、[dismissed] 是人有没有把这划掉——
 * 两者都只是行上的状态：卡片留在对话里，下次进来还能看见"这几笔记过了、那笔不要"。
 *
 * 判级与去重的痕迹也留在行上，不留在内存里：卡片是持久化的对话消息，
 * 隔天再打开这张卡，「这是自动记入的、可以撤销」「这笔已经记过了」都还要看得见。
 */
@Serializable
data class DraftRow(
    val amountMinor: Long,
    val type: String,
    val occurredAt: Long,
    /** 模型给到时刻时是 DATETIME：落库要对齐到声明的精度，不能拿默认的日把时刻抹掉。 */
    val timePrecision: String = "DATE",
    /** 这笔账记在谁名下：MANUAL / VOICE / SCREENSHOT / ASSISTANT。 */
    val source: String,
    /**
     * 这条消息是文字、语音还是图片进来的——只用于卡上那个小标签。
     * 空字符串是升级前的老卡：那时候拿 [source] 兼当输入形态，读的时候按它回推。
     */
    val inputKind: String = "",
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val categoryLabel: String? = null,
    val note: String? = null,
    val merchant: String? = null,
    /** 截图里的订单号：有就写入账单的 `external_id`，同一笔第二次识别靠它跳过。 */
    val externalId: String? = null,
    /** 这一笔的来源图片指纹（只有「一张图一笔账」时才对得上）：同一张图重复分享靠它跳过。 */
    val imageSha: String? = null,
    /** 本机关键词表命中分类的那个词；命中过就不必再教一次（见 `KeywordLearning`）。 */
    val keyword: String? = null,
    /** 这条备注是 AI 写出来的：静默沉淀时据此标成 `LEARNED_AI`。 */
    val noteFromAi: Boolean = false,
    /** 本机判级：HIGH / MEDIUM / LOW。卡上按它决定「已自动记入 + 撤销」还是「等你点一下」。 */
    val grade: String = "MEDIUM",
    /** 判级时缺的那几项，直接写在卡上——比一句「不确定」有用得多。 */
    val missing: List<String> = emptyList(),
    /** 日期不是原文给的、按采集时刻落的账：卡上直接写日期，不用技术词。 */
    val dateDefaulted: Boolean = false,
    /** 提到日期却没解析出来：卡片要提示人改日期，不能自动按今天写入。 */
    val dateUnresolved: Boolean = false,
    /** 落库的那笔账单 id：撤销要它。人工记入与自动记入都会填。 */
    val savedBillId: String? = null,
    /** 同一订单号或同一张图已经记过：不写，卡上写明，避免"再不弹卡，其实也没记上"。 */
    val duplicate: Boolean = false,
    /** 已经撤销过：账单在回收站里，卡上留一句痕。 */
    val reverted: Boolean = false,
    val saved: Boolean = false,
    val dismissed: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(serializer())

        /** 卡片整组存进 ai_message.content；解不出来就当没有卡，不炸页面。 */
        fun encode(rows: List<DraftRow>): String = json.encodeToString(listSerializer, rows)

        fun decode(content: String): List<DraftRow> =
            runCatching { json.decodeFromString(listSerializer, content) }.getOrDefault(emptyList())
    }
}

data class AiReport(
    val id: String,
    val ledgerId: String,
    val monthKey: String,
    val content: String,
    val summaryJson: String,
    val createdAt: Long,
)

/** 一次问答的结果：成功给文本，失败保留失败消息并给出重试入口。 */
sealed interface AiAnswer {
    /** [question] 是这次提问那条消息：本机的记账识别卡片要挂在它下面，而不是挂在回答下面。 */
    data class Reply(val question: AiChatEntry, val entry: AiChatEntry, val usageTokens: Int?) : AiAnswer

    /** [reason] 直接可用于就地提示；小莉不可用时对话仍会留下一条失败消息。 */
    data class Unavailable(val reason: String, val questionId: String? = null) : AiAnswer
}
