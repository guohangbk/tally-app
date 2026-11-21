package io.github.guohangbk.tally.domain.ingest

import kotlinx.serialization.Serializable

/**
 * 跨入口幂等：同一笔只留一个入口。
 *
 * 判定不依赖 Android，纯函数式，方便单测把四档窗口与优先级钉死。
 * 规则见《自动记账-通知与待确认方案》§2：从上到下取第一个命中的键，不合并就各出各的。
 */

/** 一条待判定的素材（通知或支付页抓到的那一笔）。 */
data class IdempotencyKey(
    val orderNo: String?,
    val app: String,
    val title: String,
    val body: String,
    val amountMinor: Long?,
    val merchant: String?,
    val occurredAt: Long,
    /** 抓到这一笔的入口（通知 / 支付页），合并后来源两枚都留住。 */
    val source: String,
)

/** 幂等台账里的一条：先到者保留，后到者只补空字段、来源都留住。 */
@Serializable
data class IdempotencyRecord(
    val orderNo: String?,
    val app: String,
    val title: String,
    val body: String,
    val amountMinor: Long?,
    val merchant: String?,
    val occurredAt: Long,
    /** 抓到这一笔的入口，合并后会并排留在一行。 */
    val sources: List<String>,
    val createdAt: Long,
)

enum class IdempotencyKeyKind {
    /** 完整订单号：30 天内命中即同一笔，最强键。 */
    ORDER_NO,
    /** 同一条通知被重放（进程重启后的补收）：postTime 一模一样，间距不限。 */
    REPLAY,
    /** 同一渠道的重复通知：同应用、同标题、同正文，60 秒内。 */
    SAME_CHANNEL,
    /** 跨渠道同笔：同金额 + 商户能对上（不看订单号），前后 5 分钟。 */
    CROSS_CHANNEL_MERCHANT,
    /** 同一渠道换了文案重推：同应用 + 同金额，前后 90 秒。 */
    CROSS_CHANNEL_APP,
    /** 跨渠道只对得上金额：不同应用 + 同金额 + 商户不冲突，前后 3 分钟（美团下单 → 支付宝付款这种）。 */
    CROSS_CHANNEL_AMOUNT,
}

sealed interface IdempotencyDecision {
    data class Match(val record: IdempotencyRecord, val kind: IdempotencyKeyKind) : IdempotencyDecision
    data object NoMatch : IdempotencyDecision
}

/**
 * 幂等窗口（毫秒）。订单号窗与自动草稿 30 天过期同长，两者是一件事的两半。
 */
object IdempotencyWindows {
    const val ORDER_NO_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val SAME_CHANNEL_MILLIS = 60_000L
    const val CROSS_MERCHANT_MILLIS = 5 * 60_000L
    const val SAME_APP_MILLIS = 90_000L
    const val CROSS_AMOUNT_MILLIS = 3 * 60_000L
}

class IdempotencyDecider(private val now: () -> Long = System::currentTimeMillis) {

    fun decide(candidate: IdempotencyKey, existing: List<IdempotencyRecord>): IdempotencyDecision {
        // 按优先级从上到下，取第一个命中的；命中即返回，不合并就各出各的。
        // 1. 完整订单号：同一系统里的唯一编号，30 天内命中就是同一笔。
        existing.firstOrNull { record ->
            record.orderNo != null && record.orderNo == candidate.orderNo &&
                within(record, candidate, IdempotencyWindows.ORDER_NO_MILLIS)
        }?.let { return IdempotencyDecision.Match(it, IdempotencyKeyKind.ORDER_NO) }

        // 重连补收会把还挂在通知栏上的通知再读一遍：同一条通知 postTime 分毫不差，直接认重放，
        // 不受 60 秒窗口限制（进程被回收后隔几分钟才重连很常见）。
        existing.firstOrNull { record ->
            record.app == candidate.app && record.title == candidate.title && record.body == candidate.body &&
                kotlin.math.abs(record.occurredAt - candidate.occurredAt) <= REPLAY_TOLERANCE_MILLIS &&
                !differentOrder(record, candidate)
        }?.let { return IdempotencyDecision.Match(it, IdempotencyKeyKind.REPLAY) }

        // 同渠道两笔都带订单号又对不上，就是两笔不同的交易（连买两杯同价咖啡）——只在这一层用单号判断。
        existing.firstOrNull { record ->
            record.app == candidate.app && record.title == candidate.title && record.body == candidate.body &&
                !differentOrder(record, candidate) &&
                within(record, candidate, IdempotencyWindows.SAME_CHANNEL_MILLIS)
        }?.let { return IdempotencyDecision.Match(it, IdempotencyKeyKind.SAME_CHANNEL) }

        // 2. 跨渠道同笔：同金额 + 商户能对上，前后 5 分钟。单号不参与：美团单号与支付宝交易号本来就不是一回事。
        //    同一个应用内部则收紧到 90 秒——同一家店同价的两笔隔几分钟是两杯咖啡，不是同一笔。
        existing.firstOrNull { record ->
            record.amountMinor != null && record.amountMinor == candidate.amountMinor &&
                merchantsMatch(record.merchant, candidate.merchant) &&
                !(record.app == candidate.app && differentOrder(record, candidate)) &&
                within(
                    record,
                    candidate,
                    if (record.app == candidate.app) IdempotencyWindows.SAME_APP_MILLIS
                    else IdempotencyWindows.CROSS_MERCHANT_MILLIS,
                )
        }?.let { return IdempotencyDecision.Match(it, IdempotencyKeyKind.CROSS_CHANNEL_MERCHANT) }

        // 3. 同一渠道、同金额，但标题正文换了（同一条支付被重推一遍）：90 秒内认同一笔。
        existing.firstOrNull { record ->
            record.app == candidate.app && record.amountMinor != null &&
                record.amountMinor == candidate.amountMinor &&
                !differentOrder(record, candidate) &&
                within(record, candidate, IdempotencyWindows.SAME_APP_MILLIS)
        }?.let { return IdempotencyDecision.Match(it, IdempotencyKeyKind.CROSS_CHANNEL_APP) }

        // 4. 跨渠道只对得上金额（订单应用 + 支付应用，两边常常只解析出一边商户）：前后 3 分钟。
        // 两边商户都认得出、又明显不是一家时不动，避免把两家店同价的两笔吞成一笔。
        existing.firstOrNull { record ->
            record.app != candidate.app && record.amountMinor != null &&
                record.amountMinor == candidate.amountMinor &&
                merchantsCompatible(record.merchant, candidate.merchant) &&
                within(record, candidate, IdempotencyWindows.CROSS_AMOUNT_MILLIS)
        }?.let { return IdempotencyDecision.Match(it, IdempotencyKeyKind.CROSS_CHANNEL_AMOUNT) }

        return IdempotencyDecision.NoMatch
    }

    /**
     * 合并：先到者保留，后到者**只补空字段**，不覆盖已有值；来源两枚都留住。
     * 返回一条新的台账记录（不改动入参）。
     */
    fun merge(earlier: IdempotencyRecord, candidate: IdempotencyKey): IdempotencyRecord =
        earlier.copy(
            orderNo = earlier.orderNo ?: candidate.orderNo,
            merchant = earlier.merchant ?: candidate.merchant,
            amountMinor = earlier.amountMinor ?: candidate.amountMinor,
            occurredAt = earlier.occurredAt,
            sources = (earlier.sources + candidate.source).distinct(),
            createdAt = earlier.createdAt,
        )

    private fun differentOrder(record: IdempotencyRecord, candidate: IdempotencyKey): Boolean =
        record.orderNo != null && candidate.orderNo != null && record.orderNo != candidate.orderNo

    private fun within(record: IdempotencyRecord, candidate: IdempotencyKey, window: Long): Boolean {
        // 窗口比的是**发生时刻**：两笔支付在时间上挨得够近才算同一笔，与先到后到无关。
        val diff = candidate.occurredAt - record.occurredAt
        return diff <= window && diff >= -window
    }

    private companion object {
        /** 同一条通知重放时 postTime 完全相同；留 2 秒容忍正文里解析出的时刻。 */
        const val REPLAY_TOLERANCE_MILLIS = 2_000L
    }
}

/** 商户名归一化：只留字和数字，去掉空格、括号、标点（「肯德基（望京店）」→「肯德基望京店」）。 */
internal fun normalizeMerchant(raw: String): String = raw.filter { it.isLetterOrDigit() }

/** 两边都认得出商户时，是否可能是一家：归一化后相等，或一方包含另一方（「肯德基」与「肯德基望京店」）。 */
internal fun merchantsMatch(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    val left = normalizeMerchant(a)
    val right = normalizeMerchant(b)
    if (left.isEmpty() || right.isEmpty()) return false
    val shorter = if (left.length <= right.length) left else right
    val longer = if (left.length <= right.length) right else left
    return shorter.length >= 2 && longer.contains(shorter)
}

/** 商户不冲突：有一边没解析出来，或者两边能对上。 */
private fun merchantsCompatible(a: String?, b: String?): Boolean =
    a == null || b == null || merchantsMatch(a, b)
