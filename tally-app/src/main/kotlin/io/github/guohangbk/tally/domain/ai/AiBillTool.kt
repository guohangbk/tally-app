package io.github.guohangbk.tally.domain.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId

/**
 * 小莉抽账用的两个工具：`propose_bills` 与 `not_a_bill`。
 *
 * 之所以是「工具」而不是「让模型回一段 JSON 再解析」：参数字段由 schema 定义，模型只能填这几格，
 * 不会自己发明「类别：打车费」这种没法定位的写法；分类直接回我们发过去的那个 id，代码精确比对，
 * 不再拿关键词去猜「打车」算不算「交通」。判断这笔账是不是账、算哪一类，全部交给模型。
 *
 * 一次调用交**一批**（`bills` 数组），不是每笔调一次：网关不支持 `parallel_tool_calls` 时，
 * 后者只会拿到第一笔，「早20 中5 地铁30」当场变一张卡——那正是这个数组存在的理由。
 *
 * 名字叫 propose 而不是 create：工具被调用也只是**提案**，落库永远要人在卡片上点一下。
 * 名字准确还能压住模型回那句「已经帮你记好了」。
 */
object AiBillTool {
    const val PROPOSE_BILLS: String = "propose_bills"
    const val SKIP: String = "not_a_bill"

    /** 分类多到这个程度就不再塞 enum：请求体不该比分类表本身更长。 */
    private const val MAX_ENUM_CATEGORIES = 120

    private const val NOT_A_BILL = "这条内容里没有要记的一笔账"
    private const val INVALID = "返回的字段不合格"
    private const val TRUNCATED = "输出被截断（工具参数没写完）"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private val skipParameters: String = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "reason": {"type": "string", "description": "为什么这段内容不是一笔账，20 字以内"}
          },
          "required": ["reason"]
        }
    """.trimIndent()

    /**
     * 一次请求的分类对照表。
     *
     * 分类 id 用 `c01`…`cNN` 这种现场短柄而不是数据库里的 UUID：真机上量出来的账是——
     * 一句 30 字的话，prompt 要 4663 token，其中同一批 36 字 UUID 在分类表和参数 enum 里各出现一次。
     * 换成短柄并把 enum 去掉重复，这部分能砍掉八成。
     * 短柄**只在这次请求里有意义**，所以对照表不缓存、不复用，回来必须先映射回真实分类。
     */
    class Catalog(refs: List<AiCategoryRef>) {
        private val ordered: List<Pair<String, AiCategoryRef>> = refs.mapIndexed { index, ref -> handleOf(index) to ref }

        /** 短柄 → 分类，交给校验层做精确命中。 */
        val handles: Map<String, AiCategoryRef> = ordered.toMap()

        val tools: List<AiToolSpec> = listOf(
            AiToolSpec(
                name = PROPOSE_BILLS,
                description = "登记这段内容里要记的账：有几笔就在 bills 里放几笔，只有一笔也照样给一个数组。" +
                    "金额、日期、分类都来自这段内容本身；" +
                    "category_id 必须逐字复制给定分类表里的 id，选不出二级就选一级，都对不上就填 null。",
                parametersSchema = withCategoryEnum(AiDraftSchema.billsJsonSchema, handles.keys.toList()),
            ),
            AiToolSpec(
                name = SKIP,
                description = "这段内容里没有一笔需要记录的收支：它在提问、闲聊、" +
                    "或者只是描述而没有具体金额。拿不准也用这个，不要凑一笔。",
                parametersSchema = skipParameters,
            ),
        )

        /** 分类表：`id | 收支 | 一级 / 二级`。二级写成带父级的一行，让模型看得见层级但仍只回一个 id。 */
        fun table(): String {
            if (ordered.isEmpty()) return "当前没有任何可用分类，category_id 一律填 null。"
            val emitted = ordered.filter { it.second.isTopLevel }.map { it.first }.toSet()
            val lines = buildList {
                ordered.filter { it.second.isTopLevel }.forEach { (handle, parent) ->
                    add(row(handle, parent.type, parent.name))
                    ordered.filter { it.second.parentId == parent.id }.forEach { (childHandle, child) ->
                        add(row(childHandle, child.type, "${parent.name} / ${child.name}"))
                    }
                }
                // 父级被归档或删掉的分类也要列出来，否则模型只能在错的分类里挑一个。
                ordered.filter { !it.second.isTopLevel && !emitted.contains(it.second.parentId) }
                    .forEach { (handle, orphan) -> add(row(handle, orphan.type, orphan.name)) }
            }
            return buildString {
                append("可选分类（id | 收支 | 分类）：\n")
                lines.forEach { append(it).append('\n') }
                append("只有以上 id 可以用。")
            }.trimEnd()
        }

        private fun row(handle: String, type: io.github.guohangbk.tally.domain.model.CategoryType, label: String): String =
            "$handle | ${if (type == io.github.guohangbk.tally.domain.model.CategoryType.INCOME) "收入" else "支出"} | $label"

        private companion object {
            fun handleOf(index: Int): String = "c%02d".format(index + 1)
        }
    }

    fun catalog(refs: List<AiCategoryRef>): Catalog = Catalog(refs)

    /**
     * 把短柄塞进 `bills.items.category_id.enum`：支持约束解码的服务端因此不可能让模型编出目录外的分类。
     * 一份 enum 只有百来字符，值得留着——塞 UUID 时它比分类表本身还大一倍。
     * 分类太多（> [MAX_ENUM_CATEGORIES]）时不再塞，请求体不该比表本身更长。
     */
    private fun withCategoryEnum(schema: String, handles: List<String>): String {
        if (handles.isEmpty() || handles.size > MAX_ENUM_CATEGORIES) return schema
        val root = runCatching { json.parseToJsonElement(schema).jsonObject }.getOrNull() ?: return schema
        val properties = root.obj("properties") ?: return schema
        val bills = properties.obj("bills") ?: return schema
        val item = bills.obj("items") ?: return schema
        val itemProperties = item.obj("properties") ?: return schema
        val categoryField = itemProperties.obj("category_id") ?: return schema
        val enum = JsonArray(handles.map { JsonPrimitive(it) })
        val itemWithEnum = JsonObject(itemProperties + mapOf("category_id" to JsonObject(categoryField + mapOf("enum" to enum))))
        val billsWithItem = JsonObject(bills + mapOf("items" to JsonObject(item + mapOf("properties" to itemWithEnum))))
        val rootWithBills = JsonObject(properties + mapOf("bills" to billsWithItem))
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(root + mapOf("properties" to rootWithBills)),
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /**
     * 一次返回读成 N 笔草稿判定。
     *
     * 优先用工具调用：一次 propose_bills 的 `bills` 数组里就是这几笔，不依赖网关支持并行调用；
     * 只调了 not_a_bill 就判「不是账」；一个工具都没调时退回读文本里的 JSON——
     * 那是网关不支持 tools、走 response_format 降级时的正常形态，形状同样是 `{"bills": […]}`。
     */
    fun readAll(
        completion: AiCompletion,
        catalog: Catalog,
        fallbackMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BillsVerdict {
        val calls = completion.toolCalls
        if (calls.isEmpty()) return verdictOf(fromText(completion.text, catalog, fallbackMillis, zone))
        val creates = calls.filter { it.name == PROPOSE_BILLS }
        if (creates.isEmpty()) return BillsVerdict.NotABill(NOT_A_BILL)
        val refs = catalog.handles.values.toList()
        val verdicts = creates.flatMap { call ->
            val items = AiDraftSchema.parseObject(call.arguments)?.let(AiDraftSchema::billItemsOf).orEmpty()
            if (items.isEmpty()) {
                // 拼不出数组：被截断和写坏了要分开报，前者加预算能治，后者才该改提示词。
                listOf(AiDraftVerdict.Rejected(if (completion.finishReason == "length") TRUNCATED else INVALID))
            } else {
                AiDraftSchema.readBills(items, refs, fallbackMillis, zone, catalog.handles)
            }
        }
        return verdictOf(verdicts)
    }

    private fun fromText(
        text: String,
        catalog: Catalog,
        fallbackMillis: Long,
        zone: ZoneId,
    ): List<AiDraftVerdict> =
        AiDraftSchema.readBillsFromText(text, catalog.handles.values.toList(), fallbackMillis, zone, catalog.handles)

    /** 逐笔判定收成一次结果：合格的都留下，一笔没成才算「不是账」。 */
    private fun verdictOf(verdicts: List<AiDraftVerdict>): BillsVerdict {
        val accepted = verdicts.filterIsInstance<AiDraftVerdict.Accepted>().map { it.fields }.take(AiDraftSchema.MAX_BILLS)
        val rejected = verdicts.filterIsInstance<AiDraftVerdict.Rejected>().map { it.reason }
        return when {
            accepted.isNotEmpty() -> BillsVerdict.Some(accepted, rejected)
            rejected.isNotEmpty() -> BillsVerdict.Rejected(rejected.first())
            else -> BillsVerdict.NotABill(NOT_A_BILL)
        }
    }

    /** 一次抽账的读取结果。[Some] 里的顺序就是模型给的顺序，超过 [AiDraftSchema.MAX_BILLS] 笔已被截断。 */
    sealed interface BillsVerdict {
        data class Some(val fields: List<AiBillFields>, val droppedReasons: List<String>) : BillsVerdict
        data class NotABill(val reason: String) : BillsVerdict
        data class Rejected(val reason: String) : BillsVerdict
    }
}
