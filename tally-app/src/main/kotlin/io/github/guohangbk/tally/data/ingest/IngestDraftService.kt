package io.github.guohangbk.tally.data.ingest

import io.github.guohangbk.tally.data.ai.AiConversationRepository
import io.github.guohangbk.tally.data.attachment.AttachmentStore
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.repository.BillRepository
import io.github.guohangbk.tally.domain.ingest.IngestBill
import io.github.guohangbk.tally.domain.ingest.FieldOrigin
import io.github.guohangbk.tally.domain.ingest.IngestKeys
import io.github.guohangbk.tally.domain.ingest.IngestResult
import io.github.guohangbk.tally.domain.ingest.IngestSource
import io.github.guohangbk.tally.domain.ingest.InputKind
import io.github.guohangbk.tally.domain.model.BillDraft
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.DraftRow
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.parser.QuickNoteCatalog
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 卡状态、落库、撤销都收在这一处；导航层不再带路由参数回写。 */
class IngestDraftService(
    private val bills: BillRepository,
    private val conversations: AiConversationRepository,
    private val attachments: AttachmentStore,
    private val catalog: suspend () -> QuickNoteCatalog,
) {
    private val savedEvents = MutableSharedFlow<BillSavedEvent>(extraBufferCapacity = 16)
    val billSaved: SharedFlow<BillSavedEvent> = savedEvents
    private val materialSavedEvents = MutableSharedFlow<BillSavedEvent>(extraBufferCapacity = 8)
    val materialSaved: SharedFlow<BillSavedEvent> = materialSavedEvents.asSharedFlow()
    private val materialEditCancelledEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val materialEditCancelled: SharedFlow<Unit> = materialEditCancelledEvents.asSharedFlow()
    private var pendingEdit: PendingEdit? = null
    private var pendingMaterialEdit = false

    /**
     * 从卡上「改」进记账页时，把这一行的去重标识一并揣起来：
     * 记账页的保存是「再记一笔」，那条路自己看不到订单号与图片指纹；不带上，
     * 同一张图再扫一遍就会被当成新账写第二笔（真机撞到过重复记账）。
     */
    private var pendingIdentity: Pair<String?, String?>? = null
    /** 从卡上「改」进来时一并揣着的那张图；开关关着就是 null。 */
    private var pendingAttachment: ByteArray? = null

    fun beginEdit(
        messageId: String,
        index: Int,
        amountMinor: Long = 0L,
        externalId: String? = null,
        imageSha: String? = null,
    ) {
        pendingEdit = PendingEdit(messageId, index, amountMinor)
        rememberIdentity(externalId, imageSha)
    }

    /** 首页素材卡的一笔跳进记账页：保存成功要回写素材卡，不回写对话卡。 */
    fun beginMaterialEdit() {
        pendingMaterialEdit = true
        pendingEdit = null
    }

    /** 取一次就清：记账页保存后不再影响下一次手工记账，顺带丢掉没用上的去重标识。 */
    fun takeMaterialEdit(): Boolean = pendingMaterialEdit.also {
        pendingMaterialEdit = false
        pendingIdentity = null
        pendingAttachment = null
    }

    /** 没保存就返回：只通知素材卡解除编辑态，别误当成一次保存。 */
    fun cancelMaterialEdit(): Boolean {
        val wasEditingMaterial = takeMaterialEdit()
        if (wasEditingMaterial) materialEditCancelledEvents.tryEmit(Unit)
        return wasEditingMaterial
    }

    /** 是否正在等素材卡的一笔保存回来；导航层据此把事件转给首页卡。 */
    fun hasMaterialEdit(): Boolean = pendingMaterialEdit

    /** 批量卡那条路没有持久化消息，标识只能自己揣。 */
    fun rememberIdentity(externalId: String?, imageSha: String?) {
        if (externalId.isNullOrBlank() && imageSha.isNullOrBlank()) return
        pendingIdentity = externalId to imageSha
    }

    /** 取一次就清：只作用于紧接着的那一次保存，不留给下一次手工记账。 */
    fun takeIdentity(): Pair<String?, String?>? = pendingIdentity?.also { pendingIdentity = null }

    /** 「账单图片」开着时，把这一笔的来源图一起交给记账页：存的是字节，记账页直接落成附件。 */
    fun rememberAttachment(bytes: ByteArray?) {
        pendingAttachment = bytes
    }

    /** 取一次就清：和 [takeIdentity] 同一个时机（记账页保存成功那一刻）。 */
    fun takeAttachment(): ByteArray? = pendingAttachment?.also { pendingAttachment = null }

    /** 记账页保存成功事件：由依赖图统一回写卡上的那一行，导航层不碰卡状态。 */
    suspend fun recordManualSave(event: BillSavedEvent) {
        val target = pendingEdit ?: return
        if (target.amountMinor != 0L && target.amountMinor != event.amountMinor) return
        pendingEdit = null
        conversations.updateDraft(target.messageId) { rows ->
            rows.mapIndexed { i, row ->
                if (i == target.index && !row.saved) {
                    row.copy(saved = true, savedBillId = event.billId, duplicate = false)
                } else {
                    row
                }
            }
        }
    }

    fun emitBillSaved(event: BillSavedEvent) {
        savedEvents.tryEmit(event)
    }

    /** 素材卡的一笔在记账页保存成功：只让首页卡收事件，不再回流对话卡。 */
    fun emitMaterialSaved(event: BillSavedEvent) {
        materialSavedEvents.tryEmit(event)
    }

    suspend fun rows(result: IngestResult, source: IngestSource): List<DraftRow> {
        return result.bills.map { bill -> rowOf(bill, source.billSource, source.inputKind) }
    }

    /** 小莉路：账一律归「小莉」，输入形态看这条消息本身是文字、语音还是图。 */
    suspend fun chatRows(result: IngestResult, inputKind: InputKind): List<DraftRow> {
        return result.bills.map { bill -> rowOf(bill, BillSource.ASSISTANT, inputKind) }
    }

    private suspend fun rowOf(bill: IngestBill, billSource: BillSource, inputKind: InputKind): DraftRow {
        val refs = catalog()
        val fields = bill.fields
        val label = (fields.categoryL2Id ?: fields.categoryL1Id)?.let { id -> refs.categoryOf(id)?.name }
        return DraftRow(
            amountMinor = fields.amountMinor ?: 0L,
            type = (fields.type ?: BillType.EXPENSE).name,
            occurredAt = fields.occurredAt ?: bill.evidence.fallbackMillis,
            timePrecision = fields.timePrecision.name,
            source = billSource.name,
            inputKind = inputKind.name,
            categoryL1Id = fields.categoryL1Id,
            categoryL2Id = fields.categoryL2Id,
            categoryLabel = label,
            note = fields.note ?: fields.merchant,
            merchant = fields.merchant,
            keyword = fields.keyword,
            noteFromAi = fields.origins[IngestKeys.NOTE] == FieldOrigin.LLM,
            externalId = bill.evidence.orderNo,
            imageSha = bill.evidence.imageSha?.let { sha ->
                bill.evidence.itemIndex?.let { "$sha#$it" } ?: sha
            },
            grade = bill.verdict.grade.name,
            missing = bill.verdict.missing,
            dateDefaulted = bill.verdict.dateDefaulted,
            dateUnresolved = bill.verdict.dateUnresolved,
            duplicate = bill.verdict.duplicate,
        )
    }

    suspend fun confirm(messageId: String, rows: List<DraftRow>) {
        writeRows(messageId, rows.filter { !it.saved && !it.dismissed && !it.duplicate }, null)
    }

    suspend fun undo(messageId: String, index: Int): Boolean {
        val rows = currentRows(messageId)
        val billId = rows.getOrNull(index)?.savedBillId ?: return false
        if (!softDelete(billId)) return false
        conversations.updateDraft(messageId) { current ->
            current.mapIndexed { i, row ->
                if (i == index) row.copy(saved = false, savedBillId = null, reverted = true) else row
            }
        }
        return true
    }

    suspend fun dismiss(messageId: String, index: Int) {
        conversations.updateDraft(messageId) { rows ->
            rows.mapIndexed { i, row -> if (i == index) row.copy(dismissed = !row.dismissed) else row }
        }
    }

    suspend fun applyCategory(messageId: String, index: Int, l1Id: String, l2Id: String?, label: String?) {
        conversations.updateDraft(messageId) { rows ->
            rows.mapIndexed { i, row ->
                if (i != index) row else row.copy(categoryL1Id = l1Id, categoryL2Id = l2Id, categoryLabel = label)
            }
        }
    }

    /**
     * 批量确认卡：勾选的行一次性交给统一落库步骤，分类仍是硬条件。
     * 结果与 [rows] **按下标一一对齐**，null 表示这一笔没写进去；卡上因此能准确报出存入几笔。
     * [attachmentAt] 只有「账单图片」开着时才给得起那张来源图，其余一律 null。
     */
    suspend fun saveBatch(
        rows: List<DraftRow>,
        attachmentAt: (Int) -> ByteArray?,
    ): List<String?> = writeRows("", rows, attachmentAt)

    private suspend fun writeRows(
        messageId: String,
        pending: List<DraftRow>,
        attachmentAt: ((Int) -> ByteArray?)?,
    ): List<String?> {
        if (pending.isEmpty()) return emptyList()
        val ledger = bills.defaultLedgerId() ?: return List(pending.size) { null }
        val saved = pending.mapIndexed { index, row ->
            // 分类是硬条件：没认出分类的那一笔到不了这里，真到了也宁可空着，不塞进「其他」。
            if (row.categoryL1Id == null) return@mapIndexed null
            runCatching { bills.save(row.toBillDraft(ledger)) }
                .onSuccess { billId ->
                    attachmentAt?.invoke(index)?.let { bytes -> attach(billId, bytes) }
                }
                .onFailure { failure -> DebugLog.log("识别管线", "有一笔没记上：${failure.message.orEmpty()}") }
                .getOrNull()
        }
        val byKey = pending.mapIndexedNotNull { index, row -> saved[index]?.let { row.key() to it } }.toMap()
        if (byKey.isNotEmpty() && messageId.isNotBlank()) {
            conversations.updateDraft(messageId) { current ->
                current.map { row ->
                    byKey[row.key()]?.let { id -> row.copy(saved = true, savedBillId = id) } ?: row
                }
            }
        }
        val written = saved.count { it != null }
        if (written > 0) {
            DebugLog.log("识别管线", "已写入 $written 笔")
        }
        return saved
    }

    /** 账单图片是这笔账的附属品：存不进去不该让这一笔算失败，记一行日志就够。 */
    private suspend fun attach(billId: String, bytes: ByteArray) {
        runCatching { attachments.attachToBill(billId, bytes) }
            .onFailure { DebugLog.log("识别管线", "账单图片没存上：${it.message.orEmpty()}") }
    }

    private suspend fun currentRows(messageId: String): List<DraftRow> =
        conversations.draftRows(messageId)

    /** 账单已经被人先删掉时，撤销按「退完了」处理；回收站里的软删与手删结果一致。 */
    private suspend fun softDelete(billId: String): Boolean =
        runCatching { bills.delete(billId) }
            .recover { failure -> if (failure is TallyError.BillNotFound) Unit else throw failure }
            .isSuccess

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
        externalId = externalId?.takeIf { !duplicate },
        fingerprint = imageSha,
    )

}

data class BillSavedEvent(
    val billId: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val note: String?,
    val savedAt: Long,
)

private data class PendingEdit(val messageId: String, val index: Int, val amountMinor: Long)
