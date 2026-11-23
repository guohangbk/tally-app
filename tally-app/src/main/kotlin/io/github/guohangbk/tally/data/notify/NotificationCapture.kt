package io.github.guohangbk.tally.data.notify

import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.ingest.IngestDraftService
import io.github.guohangbk.tally.data.ingest.IdempotencyLedger
import io.github.guohangbk.tally.data.ingest.PendingIngestEntry
import io.github.guohangbk.tally.data.ingest.PendingIngestStore
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.data.repository.BillRepository
import io.github.guohangbk.tally.domain.ingest.IdempotencyDecider
import io.github.guohangbk.tally.domain.ingest.IdempotencyDecision
import io.github.guohangbk.tally.domain.ingest.IdempotencyKey
import io.github.guohangbk.tally.domain.ingest.IdempotencyRecord
import io.github.guohangbk.tally.domain.ingest.IngestRedactor
import io.github.guohangbk.tally.domain.ingest.IngestRequest
import io.github.guohangbk.tally.domain.ingest.IngestSource
import io.github.guohangbk.tally.domain.ingest.BillIngestor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 通知抓取的收口：解析 → 幂等 → 识别管线 → 追加进待确认清单。
 *
 * 抓到**不弹窗、不发通知**，只把解析出的草稿并进首页那张待确认卡，开 App 再逐笔收。
 * 通知原文不落盘、不写日志：日志只记「哪个应用、取到哪几个字段、停在哪一步」。
 */
class NotificationIngestCoordinator(
    private val ingestor: BillIngestor,
    private val drafts: IngestDraftService,
    private val pending: PendingIngestStore,
    private val ledger: IdempotencyLedger,
    private val bills: BillRepository,
    private val preferences: TallyPreferences,
    private val decider: IdempotencyDecider = IdempotencyDecider(),
    private val now: () -> Long = System::currentTimeMillis,
    /** 抓进待确认以后通知界面浮角标；没有悬浮窗权限时由浮窗自己静默跳过。 */
    private val onPendingAdded: (Int) -> Unit = {},
) {

    /**
     * 通知是一条一条、按到达顺序收的，但两条几乎同时到达时协程会并发跑：
     * 「读台账 → 判定 → 追加待确认 → 写台账」必须是一段不可打断的流程，否则两笔都会判成新账。
     */
    private val gate = Mutex()

    /** 处理一条通知。返回是否接住了这条（false 表示丢弃，不必再做任何事）。 */
    suspend fun capture(snapshot: PaymentSnapshot): Boolean = gate.withLock { captureLocked(snapshot) }

    private suspend fun captureLocked(snapshot: PaymentSnapshot): Boolean {
        val parsed = PaymentNotificationParser.parse(snapshot) ?: return false
        val key = IdempotencyKey(
            orderNo = parsed.orderNo,
            app = snapshot.packageName,
            title = snapshot.title,
            body = snapshot.text,
            amountMinor = parsed.amountMinor,
            merchant = parsed.merchant,
            occurredAt = parsed.occurredAt,
            source = IngestSource.NOTIFICATION.name,
        )

        val seen = ledger.load()
        val decision = decider.decide(key, seen)
        if (decision is IdempotencyDecision.Match) {
            // 同一笔已经进过台账：只补空字段、并来源，不新建待确认行。
            ledger.commit(decider.merge(decision.record, key))
            DebugLog.log("通知抓取", "命中幂等（${decision.kind.name}），不新建待确认")
            return true
        }

        // 已入账的订单号不再入队；没订单号的那一种按「疑似重复仍可记」处理，不在这里拦。
        if (key.orderNo != null && bills.findByExternalId(key.orderNo) != null) {
            ledger.commit(recordOf(key))
            DebugLog.log("通知抓取", "订单号已入账，丢弃")
            return true
        }

        val rows = runPipeline(snapshot, parsed)
        if (rows.isEmpty()) {
            DebugLog.log("通知抓取", "规则与补全都没抽出账单，丢弃")
            return false
        }

        // 日志只留「哪个应用、多少钱、哪几样字段有值」，通知原文与商户名不进日志。
        DebugLog.log(
            "通知抓取",
            "未命中幂等 · ${snapshot.packageName} 金额=${parsed.amountMinor} " +
                "商户=${parsed.merchant != null} 单号=${parsed.orderNo != null} " +
                "台账=${seen.size}条 → 新建待确认",
        )
        val keyBase = pending.reserveKeys(rows.size)
        val entries = rows.mapIndexed { index, row ->
            PendingIngestEntry(
                key = keyBase + index,
                imageIndex = 0,
                checked = true,
                source = IngestSource.NOTIFICATION.name,
                ledgerId = row.ledgerId,
                createdAt = now(),
                orderNo = parsed.orderNo,
                auto = true,
                row = row,
            )
        }
        pending.append(entries)
        ledger.commit(recordOf(key))
        DebugLog.log("通知抓取", "追加 ${entries.size} 笔待确认 · 金额 ${parsed.amountMinor}")
        if (preferences.settings.first().pendingBadgeEnabled) onPendingAdded(pending.count.value)
        return true
    }

    /**
     * 通知文本走与语音/截图同一条管线：规则先跑，开了「通知补全」才把脱敏文本交给 AI。
     *
     * 规则与 AI 看的是**两份不同的文字**：
     * - 规则只吃「商户 + 金额」这一句，整句通知里的「立省 10.81 元」「点击查看详情」会被当成备注、甚至当成第二笔金额；
     * - AI 要的是「应用 + 标题 + 正文 + 时间」四行：只给它「2.90 元」它认不出这是买菜还是打车，
     *   而「抖音省省」「美团」这类应用名本身就是最强的分类线索。整份脱敏后才外发。
     */
    private suspend fun runPipeline(
        snapshot: PaymentSnapshot,
        parsed: ParsedPayment,
    ): List<io.github.guohangbk.tally.domain.model.DraftRow> {
        val settings = preferences.settings.first()
        val ruleText = buildString {
            if (INCOME_MARKERS.any { parsed.text.contains(it) }) append("收入 ")
            parsed.merchant?.let { append(it).append(' ') }
            append(yuanText(parsed.amountMinor)).append('元')
        }
        val request = IngestRequest(
            source = IngestSource.NOTIFICATION,
            ocrText = ruleText,
            // 外发的是拼好并脱敏的那一份；原文只在本机做判级与去重，不落盘、不进日志。
            redactedOcrText = IngestRedactor.redact(llmText(snapshot, parsed)).text,
            nowMillis = parsed.occurredAt,
            fallbackMillis = parsed.occurredAt,
            singleMaterial = true,
            allowLlm = settings.aiNotificationComplete,
        )
        val result = ingestor.ingest(request)
        return drafts.rows(result, IngestSource.NOTIFICATION).filterNot { it.duplicate }
    }

    /** 给 AI 的那一份：应用 / 标题 / 内容 / 时间，四行说清「在哪、买了什么、什么时候」。 */
    private fun llmText(snapshot: PaymentSnapshot, parsed: ParsedPayment): String = buildString {
        snapshot.appName.takeIf { it.isNotBlank() }?.let { append("应用：").append(it).append('\n') }
        snapshot.title.trim().takeIf { it.isNotBlank() }?.let { append("标题：").append(it).append('\n') }
        parsed.body.takeIf { it.isNotBlank() }?.let { append("内容：").append(it).append('\n') }
        append("时间：").append(STAMP.format(Instant.ofEpochMilli(parsed.occurredAt).atZone(ZoneId.systemDefault())))
    }

    private fun yuanText(minor: Long): String {
        val cents = (minor % 100).toString().padStart(2, '0')
        return "${minor / 100}.$cents"
    }

    private companion object {
        val INCOME_MARKERS = listOf("收入", "收款", "到账", "退款")
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    private fun recordOf(key: IdempotencyKey): IdempotencyRecord = IdempotencyRecord(
        orderNo = key.orderNo,
        app = key.app,
        title = key.title,
        body = key.body,
        amountMinor = key.amountMinor,
        merchant = key.merchant,
        occurredAt = key.occurredAt,
        sources = listOf(key.source),
        createdAt = now(),
    )
}
