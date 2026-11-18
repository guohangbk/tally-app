package io.github.guohangbk.tally.domain.ingest

/**
 * 识别结果的本机判级：**只描述这一笔的字段完整度**，给卡排顺序、写缺项用。
 *
 * 判级只看**能在本地复核的事实**：金额有没有、金额是不是真的在原文里、
 * 分类有没有对上真实分类、这一轮是不是只有一笔、有没有已经记过。
 *
 * **判级不再决定任何自动写入**：落库一律要人在卡上点一下（V-06），
 * 所以「够格」只表示卡上这一行字段齐、排在最前面，人扫一眼就能勾上。
 *
 * **没提日期不影响字段完整度**：口语和大多数截图本来就不带日期，原文没提时按采集时刻落账是既定口径。
 * 提了日期却没解析出来，只在卡上标一句（[IngestVerdict.dateUnresolved]），也不降级——
 * 人本来就看得见日期、点一下就改。
 *
 * **订单号与商户不参与判级**：订单号是去重用的（有就写进账单、没有照样记），商户只是卡片上的说明。
 *
 * 为什么不用模型自评的 `confidence`：那是它自己报的一个数，没有校准，错得最自信的往往是
 * 「分类名编得像真的」。这里每一条都能拿原文与数据库复核，同一批输入重跑结果一致。
 */
enum class IngestGrade {
    /** 字段齐全、单一笔：卡上排最前，人一眼扫过就能勾「存入」。 */
    HIGH,

    /** 金额在，但有话要说：缺哪项写在卡上。 */
    MEDIUM,

    /** 连金额都没有：这一笔不出行，只记一句「这张没识别到」。 */
    LOW,
}

/** 判级要的全部输入。每一条都由调用方在本机算出来，不做任何猜测。 */
data class IngestSignals(
    val hasAmount: Boolean,

    /** 金额能在原文里找到（容错 35.5 / 35.50 / 1,280.00 这些写法）。 */
    val amountFromText: Boolean,

    /** 日期是原文给的，不是落成采集时刻的（`ExtractedBill.dateFromText`）。 */
    val dateFromText: Boolean,

    /** 原文提到了日期词或日期数字：没解析出来时不能自动入账。 */
    val dateMentioned: Boolean,

    /** 分类落到了真实存在的分类 id。 */
    val hasCategory: Boolean,

    /** 这一轮只出了一笔。多笔时订单号与图片指纹无法一一对应，一律不自动。 */
    val singleBill: Boolean,

    /** 同一订单号或同一张图已经记过。 */
    val duplicate: Boolean,
)

/**
 * 判级结果。[missing] 是给人看的短句，直接写在确认卡上——「缺哪项」比「不确定」有用得多。
 */
data class IngestVerdict(
    val grade: IngestGrade,
    val missing: List<String>,
    val duplicate: Boolean,
    /** 日期不是原文给的、已按采集时刻落账：卡与气泡要如实说出来，别让人以为是认出来的。 */
    val dateDefaulted: Boolean = false,

    /** 提到日期但没认出来：卡上给人一个改日期的提示。 */
    val dateUnresolved: Boolean = false,
)

object IngestGrader {
    fun judge(signals: IngestSignals): IngestVerdict {
        val dateDefaulted = !signals.dateFromText
        val dateUnresolved = signals.dateMentioned && !signals.dateFromText
        if (!signals.hasAmount) {
            return IngestVerdict(IngestGrade.LOW, emptyList(), signals.duplicate, dateDefaulted, dateUnresolved)
        }
        val missing = buildList {
            if (!signals.amountFromText) add("金额不在原文里")
            if (!signals.hasCategory) add("分类没对上")
            if (!signals.singleBill) add("这一轮不止一笔")
            if (dateUnresolved) add("没识别出日期 · 默认今天")
        }
        val grade = if (missing.isEmpty()) IngestGrade.HIGH else IngestGrade.MEDIUM
        return IngestVerdict(grade, missing, signals.duplicate, dateDefaulted, dateUnresolved)
    }
}
