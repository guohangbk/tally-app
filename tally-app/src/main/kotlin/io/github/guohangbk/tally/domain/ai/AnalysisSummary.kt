package io.github.guohangbk.tally.domain.ai

import org.json.JSONObject

/** 统计小结的五个字段。顺序就是界面上的展示顺序。 */
enum class AnalysisField {
    MOM,
    YOY,
    TOP,
    ADVICE,
    SUMMARY,
}

/**
 * 字段 → 界面标签。标签列 44dp、10.5sp 加粗，最多四个汉字，所以都卡在四字以内。
 *
 * 两条比较统一叫「区间环比 / 区间同比」：报表支持自由选起止时间，写死「环比上月」
 * 在跨月区间里就是错的。提示词、解析器、界面共用这一份，模型输出的键界面才认得出来。
 */
val analysisLabels: Map<AnalysisField, String> = linkedMapOf(
    AnalysisField.MOM to "区间环比",
    AnalysisField.YOY to "区间同比",
    AnalysisField.TOP to "最多支出",
    AnalysisField.ADVICE to "控制建议",
    AnalysisField.SUMMARY to "总结",
)

/**
 * 小莉返回的小结正文。
 *
 * 模型被要求只输出一个 JSON 对象，但它偶尔会包上 markdown 围栏、或在前面多写一段解释——
 * 所以解析分两层，任何一层成功就算数：
 *
 * 1. [fromJson]：从文本里抠出第一段花括号到最后一个花括号，宽松解析；
 * 2. [fromLines]：抠不到 JSON 就按行认标签，行首叠多少层 markdown 垃圾（`·` `>` `*` `-` `+` `#` `1.`）都认。
 *
 * 两层都失败返回空 Map，调用方退回显示原文——宁可多显示一点，也不把模型的话吞掉。
 */
object AnalysisSummaryParser {

    fun parse(content: String): Map<AnalysisField, String> {
        fromJson(content)?.let { return it }
        return fromLines(content)
    }

    private fun fromJson(content: String): Map<AnalysisField, String>? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(content.substring(start, end + 1)) }.getOrNull() ?: return null
        val parsed = LinkedHashMap<AnalysisField, String>()
        analysisLabels.forEach { (field, label) ->
            val value = runCatching { json.optString(label, "") }.getOrDefault("").trim()
            if (value.isNotEmpty()) parsed[field] = value
        }
        return parsed.ifEmpty { null }
    }

    /** 行首的标签：允许前面有任意层 markdown 装饰，标签后面紧跟中英文冒号。词表直接取自 [analysisLabels]。 */
    private val lineLabel = Regex("""^[\s·>*\-+>#]*(${analysisLabels.values.joinToString("|")})\s*[:：]\s*""")

    private fun fromLines(content: String): Map<AnalysisField, String> {
        val parsed = LinkedHashMap<AnalysisField, String>()
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val match = lineLabel.find(line) ?: return@forEach
                val field = analysisLabels.entries.firstOrNull { it.value == match.groupValues[1] }?.key
                    ?: return@forEach
                val body = line.removeRange(match.range).trim()
                // 同一个字段出现两次（模型重复了）：留第一条，后面的并进正文也不拆。
                parsed.putIfAbsent(field, body)
            }
        return parsed
    }
}

/** 取某个字段；没解析出来就退回「第一句能读的话」，海报上那句点评不能因为格式问题变成空。 */
fun analysisFieldOrFirstSentence(content: String, field: AnalysisField): String? =
    AnalysisSummaryParser.parse(content)[field]?.takeIf { it.isNotBlank() }
        ?: content.lineSequence()
            .map { it.trim().trimStart('#', '>', '-', '*', ' ').trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull()
            ?.substringBefore('。')
            ?.take(96)
            ?.plus("。")
