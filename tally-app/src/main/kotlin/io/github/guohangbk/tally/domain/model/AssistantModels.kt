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
 * 卡片上的一笔待确认账单。[saved] 是它有没有已经落库、[dismissed] 是人有没有把这划掉——
 * 两者都只是行上的状态：卡片留在对话里，下次进来还能看见"这几笔记过了、那笔不要"。
 */
@Serializable
data class DraftRow(
    val amountMinor: Long,
    val type: String,
    val occurredAt: Long,
    /** 模型给到时刻时是 DATETIME：落库要对齐到声明的精度，不能拿默认的日把时刻抹掉。 */
    val timePrecision: String = "DATE",
    val source: String,
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val categoryLabel: String? = null,
    val note: String? = null,
    val merchant: String? = null,
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
