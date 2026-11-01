package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.data.db.dao.AiConversationDao
import io.github.guohangbk.tally.data.db.dao.AiMessageDao
import io.github.guohangbk.tally.data.db.dao.AiMessageAttachmentDao
import io.github.guohangbk.tally.data.db.entity.AiConversationEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageAttachmentEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageEntity
import io.github.guohangbk.tally.data.image.TallyImages
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.ai.AiErrorKind
import io.github.guohangbk.tally.domain.ai.AiPart
import io.github.guohangbk.tally.domain.ai.AiPrompts
import io.github.guohangbk.tally.domain.ai.assistantPlainText
import io.github.guohangbk.tally.domain.ai.AiRequest
import io.github.guohangbk.tally.domain.ai.AiResult
import io.github.guohangbk.tally.domain.ai.AiTurn
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.AiAnswer
import io.github.guohangbk.tally.domain.model.AiChatEntry
import io.github.guohangbk.tally.domain.model.DraftRow
import io.github.guohangbk.tally.domain.model.AiConversation
import io.github.guohangbk.tally.domain.model.AiMessageRole
import io.github.guohangbk.tally.domain.model.AiMessageType
import io.github.guohangbk.tally.domain.model.AiMessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 小莉对话历史。
 *
 * 上下文只带「会话摘要 + 最近若干条 + 当前问题」，绝不把整段历史无限发给模型（技术设计 §8.4 约束 3）。
 * 摘要溢出时在本机折叠，不额外花一次调用额度。
 * 失败也要落一条 FAILED 消息：用户回来能看到「上次没接上」，并就地重试。
 */
class AiConversationRepository(
    private val conversations: AiConversationDao,
    private val messages: AiMessageDao,
    private val attachments: AiMessageAttachmentDao,
    private val gateway: AiGateway,
    private val preferences: TallyPreferences,
    /**
     * 账单问答要能答得上来，所以每次请求附带一份「本地已算好」的结构化汇总。
     * 与月度分析共用同一份数据最小化口径：只有月份 / 分类 / 金额 / 环比，不含备注与图片。
     */
    private val localContext: suspend () -> String = { "" },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** 发出去的图片留一张气泡回显用的缩略图；写不了也不能把发消息这件事带崩。 */
    private val thumbWriter: suspend (ByteArray) -> String? = { null },
) {
    fun observeConversations(): Flow<List<AiConversation>> =
        conversations.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeEntries(conversationId: String): Flow<List<AiChatEntry>> = combine(
        messages.observeForConversation(conversationId),
        attachments.observeThumbsForConversation(conversationId),
    ) { rows, thumbs ->
        val byMessage = thumbs.groupBy({ it.messageId }, { it.relativePath })
        rows.filter { it.isVisibleRole() }.map { it.toDomain(byMessage[it.id].orEmpty()) }
    }

    /**
     * 没有指定会话时接着最近一段未归档的对话往下说；
     * 只有真的没有历史会话才新开一段，否则每次进来都会多出一条「新的对话」。
     */
    suspend fun ensureConversation(conversationId: String?): String {
        conversationId?.let { existing -> conversations.findById(existing)?.let { return it.id } }
        conversations.findLatest()?.let { latest -> return latest.id }
        return createConversation()
    }

    /** 加号点下去就是要一段全新对话：这里绝不复用最近那条，否则换了半天还在同一段里。 */
    suspend fun startConversation(): String = createConversation()

    private suspend fun createConversation(): String {
        val timestamp = now()
        val entity = AiConversationEntity(
            id = newId(),
            title = NEW_TITLE,
            contextSummary = null,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        conversations.insert(entity)
        return entity.id
    }

    suspend fun rename(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        conversations.rename(conversationId, trimmed.take(MAX_TITLE), now())
    }

    /** 删除对话只软删会话与消息；账单和账单附件另有生命周期，绝不受影响。 */
    suspend fun delete(conversationId: String) {
        val timestamp = now()
        attachments.softDeleteByConversation(conversationId, timestamp)
        messages.softDeleteByConversation(conversationId, timestamp)
        conversations.softDelete(conversationId, timestamp)
    }

    /**
     * 一次提问。[images] 非空时走视觉：每张图先在设备内压到模型用的尺寸，一起放进同一条消息；
     * 原文件与账单备注都不进请求体（X-02 那条「发送了什么」要说得出口）。
     */
    suspend fun ask(
        conversationId: String,
        question: String,
        type: AiMessageType = AiMessageType.TEXT,
        durationMs: Long? = null,
        images: List<ByteArray> = emptyList(),
        /** 非空时走流式：每段增量回调一次，界面边收边显示。 */
        onDelta: ((String) -> Unit)? = null,
    ): AiAnswer {
        val text = question.trim()
        if (text.isEmpty() && images.isEmpty()) return AiAnswer.Unavailable("先说一句你想问的。")

        val userEntry = appendMessage(conversationId, AiMessageRole.USER, text, type, durationMs = durationMs)
        images.forEach { bytes -> keepThumb(userEntry.id, bytes) }
        val history = contextWindow(conversationId, excludingId = userEntry.id)
        val conversation = conversations.findById(conversationId)
        val prepared = images.mapNotNull { TallyImages.forModel(it) }
        val turn = if (prepared.isEmpty()) AiTurn.user(text)
        else AiTurn.userWithImages(text, prepared.map { it.dataUrl() })
        val request = AiRequest(
            instruction = AiPrompts.chatInstruction + summaryBlock(conversation?.contextSummary) + localDataBlock(),
            turns = history + turn,
        )
        // 有回调就走流式；网关不认流式时退回一次性请求（已经吐过字就不重来，免得重复两遍）。
        val result = if (onDelta == null) {
            gateway.call(request)
        } else {
            var emitted = false
            val streamed = gateway.stream(request) { chunk ->
                emitted = true
                onDelta(chunk)
            }
            if (streamed is AiResult.Failure && streamed.kind == AiErrorKind.SCHEMA && !emitted) {
                gateway.call(request)
            } else {
                streamed
            }
        }
        return when (result) {
            is AiResult.Success -> {
                val entry = appendMessage(
                    conversationId = conversationId,
                    role = AiMessageRole.ASSISTANT,
                    content = assistantPlainText(result.value.text),
                    type = AiMessageType.TEXT,
                    status = AiMessageStatus.SUCCESS,
                )
                maybeFoldHistory(conversationId)
                autoTitle(conversationId, text)
                AiAnswer.Reply(userEntry, entry, result.value.usage.totalTokens)
            }

            is AiResult.Failure -> {
                appendMessage(
                    conversationId = conversationId,
                    role = AiMessageRole.ASSISTANT,
                    content = result.failure.detail ?: result.kind?.userMessage ?: "小莉暂时联系不上",
                    type = AiMessageType.TEXT,
                    status = AiMessageStatus.FAILED,
                    errorCode = result.kind?.name,
                )
                // 云端没接上也要把这条提问的 id 带回去：本机的记账识别不依赖模型。
                AiAnswer.Unavailable(result.kind?.userMessage ?: "小莉暂时联系不上", questionId = userEntry.id)
            }
        }
    }

    /**
     * 把一组待确认账单落成对话里的一条 DRAFT 消息，返回它的 id。
     *
     * 卡片以前只活在 ViewModel 的一张 map 里：切走再回来、进程被回收，卡片连同"这几笔已经记过了"
     * 一起消失。落成消息就跟着对话一起活，位置也天然排在它那条回复之后。
     */
    suspend fun appendDraft(conversationId: String, rows: List<DraftRow>): String =
        appendMessage(
            conversationId = conversationId,
            role = AiMessageRole.ASSISTANT,
            content = DraftRow.encode(rows),
            type = AiMessageType.DRAFT,
        ).id

    /** 记入或撤掉其中一笔后改写整条：状态是整组一起存的，不做逐行更新。 */
    suspend fun updateDraft(messageId: String, rows: List<DraftRow>) {
        val existing = messages.findById(messageId) ?: return
        messages.update(existing.copy(content = DraftRow.encode(rows), updatedAt = now()))
    }

    /** 重试一条失败回复：拿它前面那条用户消息再问一次，成功就把失败消息改写掉。 */
    suspend fun retry(failedMessageId: String): AiAnswer {
        val failed = messages.findById(failedMessageId) ?: return AiAnswer.Unavailable("这条消息已经不在了。")
        val prior = messages.latestForConversation(failed.conversationId, WINDOW_MESSAGES + 1)
            .filter { it.sequence < failed.sequence && it.role == AiMessageRole.USER.name }
            .maxByOrNull { it.sequence }
            ?: return AiAnswer.Unavailable("找不到要重试的问题。")

        val history = messages.latestForConversation(failed.conversationId, WINDOW_MESSAGES)
            .filter { it.sequence < failed.sequence && it.id != prior.id && it.isVisibleRole() && it.isDraftRow().not() }
            .sortedBy { it.sequence }
            .map { AiTurn(role = AiMessageRole.valueOf(it.role), parts = listOf(AiPart.Text(it.content))) }

        val result = gateway.call(
            AiRequest(
                instruction = AiPrompts.chatInstruction + localDataBlock(),
                turns = history + AiTurn.user(prior.content),
            ),
        )
        return when (result) {
            is AiResult.Success -> {
                messages.finish(
                    id = failedMessageId,
                    content = assistantPlainText(result.value.text),
                    status = AiMessageStatus.SUCCESS.name,
                    errorCode = null,
                    messageType = AiMessageType.TEXT.name,
                    now = now(),
                )
                AiAnswer.Reply(
                    question = prior.toDomain(),
                    entry = messages.findById(failedMessageId)!!.toDomain(),
                    usageTokens = result.value.usage.totalTokens,
                )
            }

            is AiResult.Failure -> {
                messages.finish(
                    id = failedMessageId,
                    content = result.failure.detail ?: result.kind?.userMessage.orEmpty(),
                    status = AiMessageStatus.FAILED.name,
                    errorCode = result.kind?.name,
                    messageType = AiMessageType.TEXT.name,
                    now = now(),
                )
                AiAnswer.Unavailable(result.kind?.userMessage ?: "小莉暂时联系不上")
            }
        }
    }

    private suspend fun appendMessage(
        conversationId: String,
        role: AiMessageRole,
        content: String,
        type: AiMessageType,
        status: AiMessageStatus = AiMessageStatus.SUCCESS,
        errorCode: String? = null,
        durationMs: Long? = null,
    ): AiChatEntry {
        val timestamp = now()
        val entity = AiMessageEntity(
            id = newId(),
            conversationId = conversationId,
            role = role.name,
            content = content,
            messageType = type.name,
            status = status.name,
            sequence = messages.maxSequence(conversationId) + 1,
            errorCode = errorCode,
            durationMs = durationMs,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        messages.insert(entity)
        conversations.findById(conversationId)?.let { conversations.update(it.copy(updatedAt = timestamp)) }
        return entity.toDomain()
    }

    /** 每次请求只带最近 WINDOW_MESSAGES 条，其余靠摘要。DRAFT 那条是卡片的数据，不是对话文本。 */
    private suspend fun contextWindow(conversationId: String, excludingId: String): List<AiTurn> =
        messages.latestForConversation(conversationId, WINDOW_MESSAGES + 1)
            .filter { it.id != excludingId && it.isVisibleRole() && it.isDraftRow().not() && it.status == AiMessageStatus.SUCCESS.name }
            .sortedBy { it.sequence }
            .map { AiTurn(role = AiMessageRole.valueOf(it.role), parts = listOf(AiPart.Text(it.content))) }

    /**
     * 历史超出窗口时，把丢掉的消息在本机折成摘要尾巴。
     * 不做「让模型重写摘要」，那是又一次额度消耗，而且本地折叠已能满足上下文限制要求。
     */
    private suspend fun maybeFoldHistory(conversationId: String) {
        val total = messages.countForConversation(conversationId)
        if (total <= WINDOW_MESSAGES) return
        val dropped = messages.latestForConversation(conversationId, total).dropLast(WINDOW_MESSAGES)
        val gist = dropped.filter { it.isDraftRow().not() }.joinToString(" ") { entry -> "${entry.role.take(1)}:${entry.content.take(GLANCE_CHARS)}" }
        val conversation = conversations.findById(conversationId) ?: return
        val merged = ((conversation.contextSummary ?: "") + " " + gist).trim().take(MAX_SUMMARY)
        conversations.updateSummary(conversationId, merged.ifEmpty { null }, now())
    }

    /** 第一条用户问题就是会话标题，省掉一次「重命名」操作。 */
    private suspend fun autoTitle(conversationId: String, question: String) {
        val conversation = conversations.findById(conversationId) ?: return
        if (conversation.title != NEW_TITLE) return
        conversations.rename(conversationId, question.take(MAX_TITLE), now())
    }

    /**
     * 抽账那一次调用要看得见的上下文：最近几句话，加上上一张还没确认的卡（翻成人话）。
     * 追问「第二笔记一下」本身没有金额也没有分类，看不见上文的模型只能回 not_a_bill，
     * 于是卡片永远推不出来——这条流就是为它开的。
     */
    suspend fun extractionContext(conversationId: String): List<AiTurn> =
        messages.latestForConversation(conversationId, EXTRACT_WINDOW_MESSAGES)
            .filter { it.isVisibleRole() && it.status == AiMessageStatus.SUCCESS.name }
            .sortedBy { it.sequence }
            .map { row ->
                if (row.isDraftRow()) AiTurn.assistant(draftNote(DraftRow.decode(row.content)))
                else AiTurn(role = AiMessageRole.valueOf(row.role), parts = listOf(AiPart.Text(row.content)))
            }
            .filter { it.plainText.isNotBlank() }

    /** 卡片进上下文时念成一行：模型要能回答"上一张卡有几笔、分别是什么、哪笔还没记"。 */
    private fun draftNote(rows: List<DraftRow>): String {
        if (rows.isEmpty()) return ""
        val list = rows.mapIndexed { index, row ->
            val label = if (row.saved) "已记入" else if (row.dismissed) "已被划掉" else "待确认"
            "${index + 1}) ¥${formatAmount(row.amountMinor, grouping = false)} " +
                "${row.categoryLabel ?: "未分类"} ${row.note ?: row.merchant ?: ""} · $label"
        }.joinToString("；")
        return "（上一轮识别出的账单：$list）"
    }

    private fun summaryBlock(summary: String?): String =
        if (summary.isNullOrBlank()) "" else "\n\n之前的对话摘要（只作背景，不要复述）：$summary"

    private suspend fun localDataBlock(): String {
        val data = localContext()
        if (data.isBlank()) return ""
        return "\n\n本地统计（数字已算好，只读；不要重算，也不要把每个数字都念一遍）：\n$data"
    }

    private fun AiMessageEntity.isVisibleRole(): Boolean = role != AiMessageRole.SYSTEM.name

    /** 记账卡那条存的是 DraftRow 的 JSON：既不能进上下文，也不能进折叠摘要。 */
    private fun AiMessageEntity.isDraftRow(): Boolean = messageType == AiMessageType.DRAFT.name

    /** 缩略图只是回显用的，写失败就当这条消息没图，绝不影响消息本身。 */
    private suspend fun keepThumb(messageId: String, imageBytes: ByteArray) {
        val path = runCatching { thumbWriter(imageBytes) }.getOrNull() ?: return
        val timestamp = now()
        runCatching {
            attachments.insert(
                AiMessageAttachmentEntity(
                    id = newId(),
                    messageId = messageId,
                    relativePath = path,
                    sha256 = path.substringAfterLast('/'),
                    sizeBytes = 0L,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    deviceId = preferences.deviceId(),
                ),
            )
        }
    }

    private fun AiConversationEntity.toDomain(): AiConversation = AiConversation(
        id = id,
        title = title,
        contextSummary = contextSummary,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AiMessageEntity.toDomain(imagePaths: List<String> = emptyList()): AiChatEntry = AiChatEntry(
        id = id,
        role = runCatching { AiMessageRole.valueOf(role) }.getOrDefault(AiMessageRole.ASSISTANT),
        content = content,
        type = runCatching { AiMessageType.valueOf(messageType) }.getOrDefault(AiMessageType.TEXT),
        drafts = if (isDraftRow()) DraftRow.decode(content) else emptyList(),
        status = runCatching { AiMessageStatus.valueOf(status) }.getOrDefault(AiMessageStatus.SUCCESS),
        errorCode = errorCode,
        durationMs = durationMs,
        imagePaths = imagePaths,
        sequence = sequence,
        createdAt = createdAt,
    )

    private companion object {
        const val NEW_TITLE = "新的对话"
        const val MAX_TITLE = 24
        const val WINDOW_MESSAGES = 12

        /** 抽账只要够认出指代就行，不必带上对话那 12 条：越少越不容易把旧账再记一遍。 */
        const val EXTRACT_WINDOW_MESSAGES = 8
        const val GLANCE_CHARS = 40
        const val MAX_SUMMARY = 1_200
    }
}
