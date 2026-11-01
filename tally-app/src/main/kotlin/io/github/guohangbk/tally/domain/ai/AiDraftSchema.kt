package io.github.guohangbk.tally.domain.ai

import io.github.guohangbk.tally.domain.Money
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.parseAmount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 校验分类是否存在时用的账本分类目录，只带身份、名字与收支类型，避免 domain 依赖 DAO。 */
data class AiCategoryRef(
    val id: String,
    val name: String,
    val parentId: String?,
    val type: CategoryType,
) {
    val isTopLevel: Boolean get() = parentId == null
}

/**
 * AI 解析出的账单字段。这是「不可信输入」，只有通过本地校验才会变成草稿：
 * 提示注入防护正落在这一层，模型说什么都不算，金额与分类必须对上本地数据。
 */
data class AiBillFields(
    val type: BillType?,
    val amountMinor: Long?,
    val occurredAt: Long,
    val timePrecision: TimePrecision,
    val categoryL1Id: String?,
    val categoryL2Id: String?,
    val note: String?,
    val merchant: String?,
    val paymentMethod: String?,
    val orderNo: String?,
    val confidence: Double?,
)

sealed interface AiDraftVerdict {
    data class Accepted(
        val fields: AiBillFields,
        val needsConfirmation: Set<DraftField>,
    ) : AiDraftVerdict

    /** 结果整体不合格，调用方丢弃 AI 输出并回退端侧规则。 */
    data class Rejected(val reason: String) : AiDraftVerdict

    /** 模型判定这段内容里没有要记的一笔账：不弹卡，也不算出错。 */
    data class NotABill(val reason: String) : AiDraftVerdict
}

/**
 * 账单草稿的字段定义与本地校验。
 *
 * [billsJsonSchema] 是对话抽账那一份字段定义，两条传输路共用：
 * 一是作为 `tools` 里 propose_bills 的参数 schema（首选，见 AiBillTool），
 * 二是作为 `response_format` 的 json_schema（网关不认工具时降级用）。
 * 校验规则与它保持一致：金额必须是正数且在显示上限内，日期必须可解析，
 * 收支类型必须是枚举值，分类必须命中当前收支类型下的有效分类。
 */
object AiDraftSchema {
    const val MAX_NOTE_LENGTH: Int = 200

    /** 一次抽账最多认几笔：说十几笔的场景本该走导入，卡片不兜批量数据。 */
    const val MAX_BILLS: Int = 6
    private const val MAX_LABEL_LENGTH = 60
    private const val INVALID = "返回的字段不合格"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 对话抽账的输出：**一次调用交一批**。
     *
     * 单对象的形状在两条路上都会把多笔压成一笔——网关不支持 `parallel_tool_calls` 时只回一次调用，
     * 降级到 `response_format` 更是结构上就只有一个格子。所以根上就是数组：
     * 「早20 中5 地铁30」和一张里两单订单的截图，都是一次调用带两笔。
     */
    val billsJsonSchema: String = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "bills": {
              "type": "array",
              "maxItems": $MAX_BILLS,
              "items": ${buildBillSchema()}
            }
          },
          "required": ["bills"]
        }
    """.trimIndent()

    /**
     * 分类只传一个 [category_id]：二级是独立节点，命中二级时一级由本地目录反查父级，
     * 不让模型同时挑两级——那会多一个「一级和二级不匹配」的出错面。
     */
    private fun buildBillSchema(): String {
        val fields = buildList {
            add("\"type\": {\"type\": \"string\", \"enum\": [\"EXPENSE\", \"INCOME\"], \"description\": \"花钱是 EXPENSE，进账是 INCOME\"}")
            add("\"amount\": {\"type\": [\"number\", \"string\", \"null\"], \"description\": \"以元为单位的正数，例如 35.5；原文没有金额就填 null\"}")
            add("\"date\": {\"type\": [\"string\", \"number\", \"null\"], \"description\": \"yyyy-MM-dd；昨天、上周三这类相对说法按给出的今天换算；没提到日期填 null，不要默认今天\"}")
            add("\"time\": {\"type\": [\"string\", \"null\"], \"description\": \"24 小时制 HH:mm；原文没有明确时刻填 null\"}")
            add("\"category_id\": {\"type\": [\"string\", \"null\"], \"description\": \"必须逐字复制给定分类表里的 id；选得出二级就填二级，选不出填一级；表里没有对应分类就填 null\"}")
            add("\"note\": {\"type\": [\"string\", \"null\"], \"description\": \"这笔账的名字，20 字以内，用原文里剩下的词；商户、支付方式这类信息也并到这里；没有就填 null\"}")
            add("\"confidence\": {\"type\": [\"number\", \"null\"], \"description\": \"0 到 1 的自评把握\"}")
        }
        return """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                ${fields.joinToString(",\n                ")}
              },
              "required": ["type", "amount"]
            }
        """.trimIndent()
    }

    /** 从 `{"bills": […]}` 里取出每一笔；形状不对就是空列表，由调用方判不合格。 */
    internal fun billItemsOf(root: JsonObject): List<JsonObject> =
        (root["bills"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** 一批账单逐笔过本地校验：某一笔不合格只丢那一笔，不整批作废。 */
    fun readBills(
        items: List<JsonObject>,
        categoryNames: List<AiCategoryRef>,
        fallbackMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        handles: Map<String, AiCategoryRef> = emptyMap(),
    ): List<AiDraftVerdict> = items.take(MAX_BILLS)
        .map { readObject(it, categoryNames, fallbackMillis, zone, handles) }

    /** 降级到纯文本那一档的形状：剥围栏 → 取 bills → 逐笔校验。 */
    fun readBillsFromText(
        rawText: String,
        categoryNames: List<AiCategoryRef>,
        fallbackMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        handles: Map<String, AiCategoryRef> = emptyMap(),
    ): List<AiDraftVerdict> {
        val root = parseObject(rawText) ?: return listOf(AiDraftVerdict.Rejected(INVALID))
        val items = billItemsOf(root)
        if (items.isEmpty()) return listOf(AiDraftVerdict.Rejected(INVALID))
        return readBills(items, categoryNames, fallbackMillis, zone, handles)
    }

    /** 模型常把 JSON 包在 ```json 围栏里；降级到纯文本时靠这一步兜住。 */
    internal fun stripJsonFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val bodyStart = trimmed.indexOf('\n').takeIf { it >= 0 } ?: return trimmed
        val fenceEnd = trimmed.lastIndexOf("```")
        return if (fenceEnd > bodyStart) {
            trimmed.substring(bodyStart + 1, fenceEnd)
        } else {
            trimmed.substring(bodyStart + 1)
        }.trim()
    }

    /** 把一段 JSON 原文解析成对象；不是对象或解析不了就返回 null。 */
    internal fun parseObject(rawText: String): JsonObject? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { json.parseToJsonElement(stripJsonFence(trimmed)) }.getOrNull() as? JsonObject
    }

    /** 已经拿到结构化字段对象后的本地校验：工具调用与降级 JSON 两条路都汇到这里。 */
    internal fun readObject(
        root: JsonObject,
        categoryNames: List<AiCategoryRef>,
        fallbackMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        handles: Map<String, AiCategoryRef> = emptyMap(),
    ): AiDraftVerdict {
        val amount = root.field("amount").asAmount()
        val type = root.field("type").asBillType()

        val day = root.field("date").asLocalDate(zone)
        val minuteOfDay = root.field("time").asMinuteOfDay()
        val timestamp = day?.let { date ->
            if (minuteOfDay == null) {
                date.atStartOfDay(zone).toInstant().toEpochMilli()
            } else {
                date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()
            }
        } ?: root.field("date").asEpochMillis()

        val categoryId = root.field("category_id", "categoryId").asLabel(MAX_LABEL_LENGTH)
        // 三条认法按可靠度排：本次请求发出去的短柄 → 真实分类 id → 它抄成了分类名。
        // 短柄每次请求现场编号，只在这一次请求里有意义，所以映射表不能缓存。
        val hit = categoryId?.let { token ->
            handles[token]
                ?: categoryNames.firstOrNull { it.id == token }
                ?: categoryNames.firstOrNull { it.name.equals(token, ignoreCase = true) }
        }

        return AiDraftVerdict.Accepted(
            fields = AiBillFields(
                type = type,
                amountMinor = amount,
                occurredAt = timestamp ?: fallbackMillis,
                timePrecision = if (day != null && minuteOfDay != null) TimePrecision.DATETIME else TimePrecision.DATE,
                categoryL1Id = hit?.let { if (it.isTopLevel) it.id else it.parentId },
                categoryL2Id = hit?.takeIf { !it.isTopLevel }?.id,
                note = root.field("note").asLabel(MAX_NOTE_LENGTH),
                merchant = root.field("merchant").asLabel(MAX_LABEL_LENGTH),
                paymentMethod = root.field("payment_method", "paymentMethod").asLabel(MAX_LABEL_LENGTH),
                orderNo = root.field("order_no", "orderNo").asLabel(MAX_LABEL_LENGTH),
                confidence = root.field("confidence").asDouble(),
            ),
            // 单个字段读不出来只标成「待确认」，不整条作废：卡片上补一格比重新说一遍便宜。
            needsConfirmation = buildSet {
                if (amount == null) add(DraftField.AMOUNT)
                if (type == null) add(DraftField.TYPE)
                if (timestamp == null) add(DraftField.DATE)
                // 模型给了分类但本地目录对不上时，宁可让用户重选，也不猜一个。
                if (categoryId != null && hit == null) add(DraftField.CATEGORY)
            },
        )
    }

    /** 网关与模型对 snake_case / camelCase 各执一词，取值时两种都认。 */
    private fun JsonObject.field(vararg names: String): JsonElement? =
        names.firstNotNullOfOrNull { name -> this[name] }

    private fun JsonElement?.asAmount(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return if (it.inRange()) it * 100 else null }
        return primitive.contentOrNull?.let { parseAmount(it) }?.takeIf { it.inRange() }
    }

    private fun JsonElement?.asBillType(): BillType? {
        val value = (this as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase() ?: return null
        return when (value) {
            BillType.EXPENSE.name, "支出" -> BillType.EXPENSE
            BillType.INCOME.name, "收入" -> BillType.INCOME
            else -> null
        }
    }

    /** 日期只认 `yyyy-MM-dd`、ISO 时刻与毫秒时间戳三种写法，其它一律视为没给日期。 */
    private fun JsonElement?.asLocalDate(zone: ZoneId): LocalDate? {
        val primitive = this as? JsonPrimitive ?: return null
        val content = primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
            ?: return null
        runCatching { return LocalDate.parse(content.take(10)) }
        return runCatching { return Instant.parse(content).atZone(zone).toLocalDate() }.getOrNull()
    }

    private fun JsonElement?.asEpochMillis(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return it.takeIf { millis -> millis > 0 } }
        val content = primitive.contentOrNull?.trim()?.takeIf { it.all(Char::isDigit) } ?: return null
        return content.toLongOrNull()?.takeIf { it > 0 }
    }

    /** 时刻只接受 `HH:mm` 与 `HH:mm:ss`；超出 0..1439 分钟的一律当作没给。 */
    private fun JsonElement?.asMinuteOfDay(): Int? {
        val content = (this as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        val parts = content.split(":", "：")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun JsonElement?.asDouble(): Double? =
        (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonElement?.asLabel(maxLength: Int = MAX_NOTE_LENGTH): String? =
        (this as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            ?.take(maxLength)

    /** 0 与超出显示上限都属于「模型算错了」，按缺失处理交给用户确认。 */
    private fun Long.inRange(): Boolean = this > 0L && this <= Money.MAX_MINOR / 100
}
