package io.github.guohangbk.tally.domain.parser

/**
 * 语音 / 截图草稿的「静默沉淀」判据。
 *
 * 场景：**机器**跳进记账页（语音或截图，本机规则和 AI 都算），人保存了，
 * 备注又像个词——就把这条备注当新规则写进规则表。**不弹任何提示、不弹撤销**，
 * 长期可见性靠关键词规则页那枚金色「沉淀」标。
 *
 * 口径刻意做得简单：来源是机器 + 有备注 + 人真的存了。以前还额外要求「机器没命中关键词」
 * 且「人手动改过分类」，那两条把最常见的一路（AI 认出来的备注）整个漏掉了，
 * 规则永远沉淀不下来；规则越全，后面问 AI 的次数才越少。
 *
 * 与「命中关键词又改分类 → 底部气泡问一次」是两条互斥的路：
 * 那条只在 [candidate] 看到 `prefillKeyword` 非空时走，本判据只在它为空时成立。
 *
 * 判定矩阵全部集中在这个纯函数里，便于逐格单测（本文件不含任何 Android 依赖）。
 */
object KeywordLearning {

    /** 记账页来源条的机器标记：语音与截图。手工「记一笔」是 null，不在其中。 */
    private val MACHINE_SOURCES = setOf("voice", "shot")

    private const val MIN_CHARS = 2
    private const val MAX_CHARS = 6

    /** 日期里会出现的中文标记；带这些字且去掉数字与分隔符后不剩别的字，就当成日期。 */
    private val DATE_MARKERS = setOf('年', '月', '日', '号')
    private val DATE_SEPARATORS = setOf('-', '/', '.', '·')

    /**
     * 学到的那个词，以及它是从谁的备注里学来的：
     * [fromAi] 为真时标成 `LEARNED_AI`，规则页与以后排查才分得清
     * 「人自己填的词」与「AI 写的词」。
     */
    data class Candidate(val word: String, val fromAi: Boolean)

    /**
     * 该不该把这次保存的备注静默写进规则表。
     *
     * @param note 最终保存的备注（语音说「买床单花了50」，解析后的备注就是「床单」）
     * @param sourceKind 机器来源标记；手工记一笔为 null
     * @param prefillKeyword 这次命中的关键词；非空说明已经命中过，交给原有的沉淀气泡
     * @param editing 是否在编辑一笔已有账单（编辑不学）
     * @param fromAi 这条备注是 AI 写出来的（标成 `LEARNED_AI`，仍照样学）
     * @return 可以写进规则表的词；任何一条不满足都是 null
     */
    fun candidate(
        note: String?,
        sourceKind: String?,
        prefillKeyword: String?,
        editing: Boolean,
        fromAi: Boolean = false,
    ): Candidate? {
        if (editing) return null
        if (sourceKind !in MACHINE_SOURCES) return null
        if (!prefillKeyword.isNullOrBlank()) return null

        val word = note?.trim().orEmpty()
        if (word.length !in MIN_CHARS..MAX_CHARS) return null
        if (word.any { it.isWhitespace() }) return null
        if (word.none { it.isCjkIdeograph() }) return null
        if (word.looksLikeAmountOrDate()) return null
        return Candidate(word, fromAi)
    }

    /** 中文表意文字：只认最常用的那段，够覆盖备注里会出现的词。 */
    private fun Char.isCjkIdeograph(): Boolean = code in 0x4E00..0x9FFF

    /**
     * 纯数字或日期：把数字与日期分隔符去掉之后不剩任何字，就算。
     * 「11月14日」「2026-11-14」「11/14」「14号」都命中；「3号店」「便利店」不命中。
     */
    private fun String.looksLikeAmountOrDate(): Boolean {
        val rest = filterNot { it.isDigit() || it in DATE_SEPARATORS || it in DATE_MARKERS || it.isWhitespace() }
        return rest.isEmpty() && any { it.isDigit() }
    }
}
