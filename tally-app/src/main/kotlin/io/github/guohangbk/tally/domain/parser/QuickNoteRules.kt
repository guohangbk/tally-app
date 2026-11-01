package io.github.guohangbk.tally.domain.parser

import io.github.guohangbk.tally.domain.ai.AiCategoryRef
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.DefaultCategoryTree
import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.model.TimePrecision
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 速记的规则解析层（离线、始终可用），AI 只是叠加在它之上的增强层。
 *
 * 解析顺序固定为 日期 → 时间 → 金额 → 关键词分类 → 备注，前一步吃掉的字符区间会被扣掉，
 * 所以「10月13日」不会被当成 13 元，「下午3点」也不会当成 3 元。
 * 解析不出来的字段留空并标进 [ParsedQuickNote.missingFields]，草稿页负责让用户补。
 */

/** 关键词 → 分类。优先级高者优先，同级按关键词更长者优先（「午饭」胜过「饭」）。 */
data class KeywordMapping(
    val keyword: String,
    val categoryId: String,
    val priority: Int = 0,
)

/**
 * 内置词组绑定的是默认树的 slug（与 `DefaultCategoryTree.seedId` 同构），不是分类名：
 * 「三餐」改名或挪层级都不该让整组词静默失效，只有分类被删掉或归档才退出。
 */
data class BuiltinWords(val type: CategoryType, val slug: String, val words: List<String>)

/**
 * 内置词组的分组视图，给管理页看。[categoryId] 为 null 表示默认树里已经没有这个节点，
 * 该组当前不参与解析——要的正是这条可见性，而不是静默失效。
 */
data class BuiltinKeywordGroup(
    val type: CategoryType,
    val slug: String,
    val words: List<String>,
    val categoryId: String?,
)

/** 当前账本的可命中分类目录 + 用户关键词表。页面每次解析时现取，保证新建分类立刻可用。 */
data class QuickNoteCatalog(
    val categories: List<AiCategoryRef>,
    val keywords: List<KeywordMapping>,
) {
    private val byId: Map<String, AiCategoryRef> = categories.associateBy { it.id }

    /** 关键词命中的分类拆成一二级；命中二级时一级取它的父级。 */
    fun categoryOf(categoryId: String): CategoryMatch? {
        val hit = byId[categoryId] ?: return null
        return if (hit.isTopLevel) {
            CategoryMatch(hit.id, null, hit.name, hit.type)
        } else {
            CategoryMatch(hit.parentId, hit.id, hit.name, hit.type)
        }
    }

    companion object {
        /**
         * 内置常用词表。这里只补「说记账时真会说、又不会误伤」的高频口语词：
         * 同一优先级按关键词长度取胜，所以「洗车」不会被「车」抢走，「洗衣液」不会被「洗衣」抢走。
         * 词组挂到最贴切的那一级：默认树里有「面包 / 充电 / 咖啡」这类二级时就不再退回一级。
         */
        private val builtinWords: List<BuiltinWords> = listOf(
            BuiltinWords(CategoryType.EXPENSE, "food", listOf(
                "早饭", "早餐", "午餐", "午饭", "晚餐", "晚饭", "吃饭", "外卖", "食堂", "餐", "饭", "面条", "包子",
                "菜", "买菜", "夜宵", "宵夜", "早点", "快餐", "盒饭", "火锅", "烧烤", "饺子", "馆子",
            )),
            BuiltinWords(CategoryType.EXPENSE, "food-bread", listOf("面包")),
            BuiltinWords(CategoryType.EXPENSE, "transport", listOf("打车", "滴滴", "出租", "网约车", "地铁", "公交", "高铁", "火车", "动车", "机票", "加油", "单车", "共享单车")),
            BuiltinWords(CategoryType.EXPENSE, "transport-charge", listOf("充电")),
            BuiltinWords(CategoryType.EXPENSE, "transport-park", listOf("停车")),
            BuiltinWords(CategoryType.EXPENSE, "transport-toll", listOf("过路费")),
            BuiltinWords(CategoryType.EXPENSE, "snack", listOf("零食", "水", "雪糕", "冰淇淋")),
            BuiltinWords(CategoryType.EXPENSE, "snack-drink", listOf("饮料", "奶茶", "可乐", "果汁")),
            BuiltinWords(CategoryType.EXPENSE, "snack-coffee", listOf("咖啡")),
            BuiltinWords(CategoryType.EXPENSE, "snack-water", listOf("纯净水")),
            BuiltinWords(CategoryType.EXPENSE, "snack-fruit", listOf("水果")),
            BuiltinWords(CategoryType.EXPENSE, "daily", listOf("日用品", "超市", "便利店", "卫生纸", "洗衣液", "纸巾", "牙膏", "洗发水", "沐浴露")),
            BuiltinWords(CategoryType.EXPENSE, "clothes", listOf("衣服", "裤子", "鞋", "外套", "裙子", "羽绒服")),
            BuiltinWords(CategoryType.EXPENSE, "beauty", listOf("化妆品", "面膜", "口红", "美容")),
            BuiltinWords(CategoryType.EXPENSE, "fun", listOf("电影", "游戏", "演唱会", "门票", "KTV", "按摩", "洗浴")),
            BuiltinWords(CategoryType.EXPENSE, "medical", listOf("药", "医院", "门诊", "看病", "体检", "牙科", "挂号", "诊所", "药店", "疫苗")),
            BuiltinWords(CategoryType.EXPENSE, "utility", listOf("电费", "水费", "燃气", "煤气", "物业", "暖气")),
            BuiltinWords(CategoryType.EXPENSE, "housing", listOf("房租", "租房", "房贷", "押金")),
            BuiltinWords(CategoryType.EXPENSE, "digital", listOf("电器", "数码", "耳机", "手机壳", "充电器")),
            BuiltinWords(CategoryType.EXPENSE, "insurance", listOf("保险", "医疗险")),
            BuiltinWords(CategoryType.EXPENSE, "insurance-car", listOf("车险")),
            BuiltinWords(CategoryType.EXPENSE, "telecom", listOf("话费", "流量", "宽带", "充值")),
            BuiltinWords(CategoryType.EXPENSE, "telecom-member", listOf("会员")),
            BuiltinWords(CategoryType.EXPENSE, "gifting", listOf("送礼", "礼物", "随礼", "份子")),
            BuiltinWords(CategoryType.EXPENSE, "gifting-treat", listOf("请客")),
            BuiltinWords(CategoryType.EXPENSE, "redpacket", listOf("红包")),
            BuiltinWords(CategoryType.EXPENSE, "party", listOf("聚餐", "聚会", "团建")),
            BuiltinWords(CategoryType.EXPENSE, "study", listOf("书", "课程", "学费", "考试", "培训", "文具", "教材", "网课")),
            BuiltinWords(CategoryType.EXPENSE, "shopping", listOf("淘宝", "京东", "拼多多", "网购", "买东西")),
            BuiltinWords(CategoryType.EXPENSE, "service", listOf("理发", "快递", "家政", "维修")),
            BuiltinWords(CategoryType.EXPENSE, "social", listOf("人情", "社交")),
            BuiltinWords(CategoryType.EXPENSE, "appliance", listOf("家具", "家电", "沙发")),
            BuiltinWords(CategoryType.EXPENSE, "car", listOf("洗车", "保养", "修车")),
            BuiltinWords(CategoryType.INCOME, "salary", listOf("工资", "薪水", "发薪", "月薪")),
            BuiltinWords(CategoryType.INCOME, "bonus", listOf("奖金", "补贴", "年终奖")),
            BuiltinWords(CategoryType.INCOME, "reimburse", listOf("报销")),
            BuiltinWords(CategoryType.INCOME, "parttime", listOf("兼职", "外快")),
            BuiltinWords(CategoryType.INCOME, "finance", listOf("理财", "收益", "利息", "分红")),
        )

        fun of(categories: List<AiCategoryRef>, userKeywords: List<KeywordMapping>): QuickNoteCatalog {
            val builtIn = builtinGroups(categories).flatMap { group ->
                val categoryId = group.categoryId ?: return@flatMap emptyList()
                group.words.map { KeywordMapping(keyword = it, categoryId = categoryId, priority = 10) }
            }
            // 用户自己维护的词表优先于内置表：他知道「楼下的面馆」该算哪一类。
            return QuickNoteCatalog(categories = categories, keywords = builtIn + userKeywords)
        }

        /** 管理页与解析共用同一份内置词表；解析只取命中分类的组，页面连失效组一起展示。 */
        fun builtinGroups(categories: List<AiCategoryRef>): List<BuiltinKeywordGroup> {
            val available = categories.map { it.id }.toSet()
            return builtinWords.map { group ->
                BuiltinKeywordGroup(
                    type = group.type,
                    slug = group.slug,
                    words = group.words,
                    categoryId = DefaultCategoryTree.seedId(group.type, group.slug).takeIf { it in available },
                )
            }
        }
    }
}

data class CategoryMatch(
    val level1Id: String?,
    val level2Id: String?,
    val name: String,
    val type: CategoryType,
)

/** 一条速记的解析结果。[amountCandidates] 大于 1 即「检测到多笔」，提示拆分。 */
data class ParsedQuickNote(
    val amountMinor: Long?,
    val type: BillType,
    val occurredAt: Long?,
    val timePrecision: TimePrecision,
    val category: CategoryMatch?,
    val matchedKeyword: String?,
    val note: String?,
    val amountCandidates: Int,
    val missingFields: Set<DraftField>,
) {
    val hasMultipleAmounts: Boolean get() = amountCandidates > 1
}

object QuickNoteParser {
    private const val MAX_NOTE_LENGTH = 200

    private val incomeMarkers = listOf("工资", "薪水", "奖金", "补贴", "报销", "兼职", "收益", "利息", "分红", "到账", "收款", "收了", "赚了", "收入")

    /**
     * 金额片段。阿拉伯写法必须一路吃进 块/元/圆 → 角/毛 → 分，否则「29块8毛5」会被切成
     * 「29块8」加一条孤立的「5」：金额少 5 分，还被当成两笔要拆分。
     * 「半」也得在字符类里，不然「五块半」只能拿到「五块」。
     */
    private val amountRun = Regex("""[0-9０-９]+(?:[.,，][0-9０-９]+)*(?:\s*[块元圆]\s*[0-9０-９]*)*(?:\s*[角毛]\s*[0-9０-９]*)*(?:\s*分)?|[〇零一二两三四五六七八九十百千万亿点块元圆角毛分半壹贰叁肆伍陆柒捌玖拾佰仟]+""")

    private val relativeDays: List<Pair<String, Long>> = listOf(
        "大前天" to -3L, "前天" to -2L, "昨天" to -1L, "昨日" to -1L, "今天" to 0L, "今日" to 0L,
    )

    private val monthDayRegex =
        Regex("""(?:(\d{4}|[〇零一二三四五六七八九十百千]{1,6})\s*年)?\s*(\d{1,2}|[〇零一二三四五六七八九十百]{1,4})\s*月\s*(\d{1,2}|[〇零一二三四五六七八九十百]{1,4})\s*[日号]""")
    private val isoDateRegex = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val lastMonthDayRegex = Regex("""上(?:个)?月\s*(\d{1,2}|[〇零一二三四五六七八九十百]{1,4})\s*[日号]""")
    private val weekdayRegex = Regex("""(上|这|本|下)?\s*(?:星期|礼拜|周|曜日)\s*([一二三四五六日天1-7])""")
    private val dayOfMonthRegex = Regex("""(\d{1,2}|[〇零一二三四五六七八九十百廿]{1,3})\s*[日号]""")
    private val clockRegex = Regex("""(凌晨|上午|下午|中午|晚上)?\s*(\d{1,2}|[〇零一二三四五六七八九十百]{1,3})\s*[:：]\s*(\d{2})""")
    private val hourRegex = Regex("""(凌晨|上午|下午|中午|晚上)?\s*(\d{1,2}|[〇零一二三四五六七八九十百]{1,3})\s*点\s*(半|[〇零一二三四五六七八九十]{1,3}分?|\d{1,2}分?)?""")

    fun parse(
        text: String,
        catalog: QuickNoteCatalog,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ParsedQuickNote {
        val source = text.trim()
        val consumed = mutableListOf<IntRange>()

        val date = findDate(source, nowMillis, zone)
        if (date != null) consumed += date.range
        val clock = findClock(source, consumed)
        if (clock != null) consumed += clock.range

        val amounts = findAmounts(source, consumed)
        amounts.forEach { consumed += it.range }

        val category = findCategory(source, catalog)
        val missing = buildSet {
            if (amounts.isEmpty()) add(DraftField.AMOUNT)
            if (date == null) add(DraftField.DATE)
            if (category == null) add(DraftField.CATEGORY)
        }

        return ParsedQuickNote(
            amountMinor = amounts.firstOrNull()?.minor,
            type = resolveType(category, source),
            occurredAt = combine(date, clock, nowMillis, zone),
            timePrecision = if (clock != null) TimePrecision.DATETIME else TimePrecision.DATE,
            category = category,
            matchedKeyword = matchedKeywordOf(source, catalog, category),
            note = buildNote(source, consumed),
            amountCandidates = amounts.size,
            missingFields = missing,
        )
    }

    // ---------- 日期与时间 ----------

    private data class DateHit(val day: LocalDate, val range: IntRange)

    private data class ClockHit(val hour: Int, val minute: Int, val range: IntRange)

    private fun findDate(text: String, nowMillis: Long, zone: ZoneId): DateHit? {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        for ((word, offset) in relativeDays) {
            val index = text.indexOf(word)
            if (index >= 0) return DateHit(today.plusDays(offset), index until index + word.length)
        }

        lastMonthDayRegex.find(text)?.let { match ->
            val monthStart = today.withDayOfMonth(1).minusMonths(1)
            val day = match.groupValues[1].asOrdinal()?.toInt()?.coerceIn(1, monthStart.lengthOfMonth()) ?: return@let
            return DateHit(monthStart.withDayOfMonth(day), match.spanToIntRange())
        }

        monthDayRegex.find(text)?.let { match ->
            val year = match.groupValues[1].asOrdinal()?.toInt() ?: today.year
            val month = match.groupValues[2].asOrdinal()?.toInt() ?: return@let
            val day = match.groupValues[3].asOrdinal()?.toInt() ?: return@let
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let {
                return DateHit(it, match.spanToIntRange())
            }
        }

        isoDateRegex.find(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            val day = match.groupValues[3].toIntOrNull() ?: return@let
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let {
                return DateHit(it, match.spanToIntRange())
            }
        }

        weekdayRegex.find(text)?.let { match ->
            val weekday = match.groupValues[2].asWeekday() ?: return@let
            val day = when (match.groupValues[1]) {
                "上" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(weekday))).minusWeeks(1)
                "下" -> today.with(TemporalAdjusters.next(DayOfWeek.of(weekday)))
                else -> {
                    val candidate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(weekday)))
                    // 只说「周三」时取最近一个已经过去的周三：记账不会记录未来的饭钱。
                    if (candidate.isAfter(today)) candidate.minusWeeks(1) else candidate
                }
            }
            return DateHit(day, match.spanToIntRange())
        }

        // 只说「15号」：按本月理解；本月还没到就退到上月——同样是记账不记未来。
        dayOfMonthRegex.find(text)?.let { match ->
            val day = match.groupValues[1].asOrdinal()?.toInt() ?: return@let
            if (day in 1..31) {
                val candidate = runCatching { today.withDayOfMonth(day) }.getOrNull() ?: return@let
                return DateHit(if (candidate.isAfter(today)) candidate.minusMonths(1) else candidate, match.spanToIntRange())
            }
        }

        return null
    }

    private fun findClock(text: String, consumed: List<IntRange>): ClockHit? {
        clockRegex.find(text)?.takeUnless { match -> consumed.overlaps(match.spanToIntRange()) }?.let { match ->
            val hour = match.groupValues[2].asOrdinal()?.toInt() ?: return@let
            val minute = match.groupValues[3].toIntOrNull() ?: return@let
            return ClockHit(applyMeridiem(match.groupValues[1], hour), minute, match.spanToIntRange())
        }
        hourRegex.find(text)?.takeUnless { match -> consumed.overlaps(match.spanToIntRange()) }?.let { match ->
            val hour = match.groupValues[2].asOrdinal()?.toInt() ?: return@let
            val marker = match.groupValues[3]
            val minute = when {
                marker.isEmpty() -> 0
                marker.startsWith("半") -> 30
                else -> marker.removeSuffix("分").asOrdinal()?.toInt() ?: return@let
            }
            return ClockHit(applyMeridiem(match.groupValues[1], hour), minute, match.spanToIntRange())
        }
        return null
    }

    private fun applyMeridiem(marker: String, hour: Int): Int = when {
        hour >= 12 -> hour
        marker == "下午" || marker == "晚上" -> hour + 12
        marker == "中午" && hour < 11 -> hour + 12
        else -> hour
    }.coerceIn(0, 23)

    private fun combine(date: DateHit?, clock: ClockHit?, nowMillis: Long, zone: ZoneId): Long? {
        if (date == null && clock == null) return null
        val day = date?.day ?: Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return day.atStartOfDay(zone)
            .plusHours((clock?.hour ?: 0).toLong())
            .plusMinutes((clock?.minute ?: 0).toLong())
            .toInstant()
            .toEpochMilli()
    }

    // ---------- 金额 ----------

    private data class AmountHit(val minor: Long, val range: IntRange)

    private fun findAmounts(text: String, consumed: List<IntRange>): List<AmountHit> =
        amountRun.findAll(text)
            .filter { match -> consumed.none { it.overlaps(match.spanToIntRange()) } }
            // 「一共120元」「第三次」打头的那个「一」不是金额：单个裸数字词不单独成账。
            .filterNot { match -> match.value.singleOrNull()?.let(ChineseNumber::isBareDigitWord) == true }
            .mapNotNull { match ->
                ChineseNumber.parseMinor(match.value)?.let { AmountHit(it, match.spanToIntRange()) }
            }
            .toList()

    // ---------- 分类与收支 ----------

    private fun findCategory(text: String, catalog: QuickNoteCatalog): CategoryMatch? {
        val hits = catalog.keywords.mapNotNull { mapping ->
            if (!text.contains(mapping.keyword, ignoreCase = true)) return@mapNotNull null
            catalog.categoryOf(mapping.categoryId)?.let { mapping to it }
        }
        return hits.maxWithOrNull(
            compareBy<Pair<KeywordMapping, CategoryMatch>> { it.first.priority }.thenBy { it.first.keyword.length },
        )?.second
    }

    private fun matchedKeywordOf(text: String, catalog: QuickNoteCatalog, match: CategoryMatch?): String? {
        if (match == null) return null
        val ids = setOfNotNull(match.level1Id, match.level2Id)
        return catalog.keywords
            .filter { it.categoryId in ids && text.contains(it.keyword, ignoreCase = true) }
            .maxByOrNull { it.keyword.length }
            ?.keyword
    }

    /** 命中收入类分类就是收入；否则看有没有收钱的说法；再否则按支出。 */
    private fun resolveType(category: CategoryMatch?, text: String): BillType = when {
        category != null -> if (category.type == CategoryType.INCOME) BillType.INCOME else BillType.EXPENSE
        incomeMarkers.any { text.contains(it) } -> BillType.INCOME
        else -> BillType.EXPENSE
    }

    // ---------- 备注 ----------

    /** 去掉日期、时间、金额片段与连接词，剩下的就是备注；什么都不剩就不硬造备注。 */
    private fun buildNote(text: String, consumed: List<IntRange>): String? {
        val remainder = buildString {
            text.forEachIndexed { index, ch -> if (consumed.none { index in it }) append(ch) }
        }
        val cleaned = remainder
            .replace(Regex("""[，,、;；。\s]+"""), " ")
            .replace(Regex("""(的|了|花|费|用|一共|总共|计|支付|付款|大约|左右|买|在)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.takeIf { it.isNotEmpty() }?.take(MAX_NOTE_LENGTH)
    }

    // ---------- 小工具 ----------

    private fun List<IntRange>.overlaps(range: IntRange): Boolean = any { it.overlaps(range) }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

    private fun MatchResult.spanToIntRange(): IntRange = range.first..range.last

    /** 阿拉伯数字或中文数字都能读出序号值。 */
    private fun String.asOrdinal(): Long? {
        val trimmed = trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.all { it in '0'..'9' || it in '０'..'９' }) return ChineseNumber.parseInt(trimmed)
        return ChineseNumber.parseInt(trimmed)
    }

    private fun String.asWeekday(): Int? = when (trim().firstOrNull()) {
        '一' -> 1
        '二' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '日', '天', '七' -> 7
        else -> trim().toIntOrNull()?.takeIf { it in 1..7 }
    }
}
