package io.github.guohangbk.tally.domain.model

/**
 * 皮肤（主题色）。一套皮肤 = 一组会跟着变的槽，浅色档与深色档各一份。
 *
 * 刻意不含语义色：支出红、收入绿、小莉金、中性强调在 [TallySemantic] 里写死，
 * 换肤不动它们——否则「红=出、绿=进」的心智会随皮肤漂移。
 *
 * 数值用 ARGB Int 而不是 Compose `Color`：色表要能在纯 JVM 单测里跑对比度校验
 * （见 `TallySkinTest`），不该把 Android 依赖拖进 domain。
 */
enum class TallySkin(val key: String, val label: String, val art: String) {
    INK("ink", "纸墨", "宣纸底，墨绿落款，旧账簿的气味"),
    WHITE("white", "素白", "一张白纸，什么都不掺"),
    ICE("ice", "雪青", "冬天窗上那层没化的雪"),
    GRAPHITE("graphite", "石墨", "只有明度，没有颜色"),
    SAND("sand", "暖沙", "牛皮纸信封，被太阳晒过"),
    WISTERIA("wisteria", "藤紫", "紫藤花落进纸里的影子"),
    PINE("pine", "松青", "松针上的青，比绿多一口气"),
    JADE("jade", "墨玉", "灯下看是黑，凑近才是绿"),
    AMBER("amber", "暖金", "纸浆里掺了一点旧金"),
    VOYAGE("voyage", "夜航", "后半夜的河面，深蓝发黑"),
    ;

    companion object {
        /** 未知 key 回落到纸墨：偏好里读到脏值（手改、降级安装）不能把界面搞成空白。 */
        fun fromKey(key: String?): TallySkin = entries.firstOrNull { it.key == key } ?: INK
    }
}

/**
 * 一套皮肤在某一档下的显式槽。
 *
 * `line` / `brandSurface` / `expenseSurface` 不在这里：它们是 ink 或 brand 压在
 * paper / card 上的半透明结果，由 UI 层现算，避免同一支颜色在两张表里各写一遍。
 */
data class SkinTone(
    val paper: Int,
    val card: Int,
    val ink: Int,
    val ink2: Int,
    val ink3: Int,
    val brand: Int,
    val onBrand: Int,
    val summaryBar: Int,
    val onSummaryBar: Int,
)

/** 固定语义层：所有皮肤共用，浅色与深色各一档。 */
object TallySemantic {
    val expenseLight = 0xFFBF4E3B.toInt()
    val expenseDark = 0xFFE8917B.toInt()

    /**
     * 收入**不跟 brand 走**。旧实现里 `income == brand`，一旦皮肤换成紫、蓝、棕，
     * 收入数字就跟着变颜色，「绿=进钱」废掉。这里钉死成墨绿 / 薄荷绿两档。
     */
    val incomeLight = 0xFF1F4A38.toInt()
    val incomeDark = 0xFF7ECBA9.toInt()

    val slateLight = 0xFF5E7A8A.toInt()
    val slateDark = 0xFF9FB6C2.toInt()

    val goldLight = 0xFFB98A34.toInt()
    val goldDark = 0xFFE8C57A.toInt()

    /** 金在墨底上的那一档（汇总卡、深色纸面）。 */
    val goldHiLight = 0xFFE8C57A.toInt()
    val goldHiDark = 0xFFF5E3BE.toInt()

    /** 小莉建议区的底色：浅色档是实色米金，深色档由 UI 层按 26% 压在 card 上算。 */
    val glowLight = 0xFFF5E3BE.toInt()
    val onGlowLight = 0xFF7A5A18.toInt()
    val onGlowDark = 0xFFE8C57A.toInt()

    fun expense(dark: Boolean) = if (dark) expenseDark else expenseLight
    fun income(dark: Boolean) = if (dark) incomeDark else incomeLight
    fun slate(dark: Boolean) = if (dark) slateDark else slateLight
    fun gold(dark: Boolean) = if (dark) goldDark else goldLight
    fun goldHi(dark: Boolean) = if (dark) goldHiDark else goldHiLight
    fun onGlow(dark: Boolean) = if (dark) onGlowDark else onGlowLight
}

/**
 * 皮肤色表。
 *
 * 深色档不是「把浅色加一层黑」：每套都重新配平了正文与弱文的明度，
 * brand 在深色档一律提到高亮度档（否则墨绿压在黑纸上等于没有）。
 */
object TallySkins {
    val ordered: List<TallySkin> = TallySkin.entries.toList()

    fun tone(skin: TallySkin, dark: Boolean): SkinTone = if (dark) DARK[skin]!! else LIGHT[skin]!!

    private val LIGHT = mapOf(
        TallySkin.INK to SkinTone(
            paper = 0xFFF6F3EB.toInt(), card = 0xFFFFFDF8.toInt(),
            ink = 0xFF16211D.toInt(), ink2 = 0xFF4C5A53.toInt(), ink3 = 0xFF818B84.toInt(),
            brand = 0xFF1F4A38.toInt(), onBrand = 0xFFF6F3EB.toInt(),
            summaryBar = 0xFF16211D.toInt(), onSummaryBar = 0xFFF6F3EB.toInt(),
        ),
        TallySkin.WHITE to SkinTone(
            paper = 0xFFFFFFFF.toInt(), card = 0xFFF6F6F5.toInt(),
            ink = 0xFF17181A.toInt(), ink2 = 0xFF4A4D51.toInt(), ink3 = 0xFF888D93.toInt(),
            brand = 0xFF1F4A38.toInt(), onBrand = 0xFFFFFFFF.toInt(),
            summaryBar = 0xFF17181A.toInt(), onSummaryBar = 0xFFFFFFFF.toInt(),
        ),
        TallySkin.ICE to SkinTone(
            paper = 0xFFF5F8FB.toInt(), card = 0xFFFFFFFF.toInt(),
            ink = 0xFF16202B.toInt(), ink2 = 0xFF475768.toInt(), ink3 = 0xFF7F8E9C.toInt(),
            brand = 0xFF2C4A6E.toInt(), onBrand = 0xFFFFFFFF.toInt(),
            summaryBar = 0xFF16202B.toInt(), onSummaryBar = 0xFFFFFFFF.toInt(),
        ),
        TallySkin.GRAPHITE to SkinTone(
            paper = 0xFFF1F1F0.toInt(), card = 0xFFFAFAF9.toInt(),
            ink = 0xFF1B1B1A.toInt(), ink2 = 0xFF4E4E4C.toInt(), ink3 = 0xFF878784.toInt(),
            brand = 0xFF1C1C1B.toInt(), onBrand = 0xFFFAFAF9.toInt(),
            summaryBar = 0xFF1C1C1B.toInt(), onSummaryBar = 0xFFFAFAF9.toInt(),
        ),
        TallySkin.SAND to SkinTone(
            paper = 0xFFF8F1E6.toInt(), card = 0xFFFFFDF7.toInt(),
            ink = 0xFF231A10.toInt(), ink2 = 0xFF574634.toInt(), ink3 = 0xFF94836D.toInt(),
            brand = 0xFF6B4A2E.toInt(), onBrand = 0xFFFFF8EE.toInt(),
            summaryBar = 0xFF231A10.toInt(), onSummaryBar = 0xFFFFF8EE.toInt(),
        ),
        TallySkin.WISTERIA to SkinTone(
            paper = 0xFFF6F3F9.toInt(), card = 0xFFFEFCFF.toInt(),
            ink = 0xFF1E1826.toInt(), ink2 = 0xFF4E4658.toInt(), ink3 = 0xFF8B8396.toInt(),
            brand = 0xFF4A3560.toInt(), onBrand = 0xFFFFFFFF.toInt(),
            summaryBar = 0xFF1E1826.toInt(), onSummaryBar = 0xFFFFFFFF.toInt(),
        ),
        TallySkin.PINE to SkinTone(
            paper = 0xFFEDF4F1.toInt(), card = 0xFFFAFDFC.toInt(),
            ink = 0xFF12241E.toInt(), ink2 = 0xFF3F5C52.toInt(), ink3 = 0xFF728E85.toInt(),
            brand = 0xFF1E6B5A.toInt(), onBrand = 0xFFFFFFFF.toInt(),
            summaryBar = 0xFF12241E.toInt(), onSummaryBar = 0xFFFFFFFF.toInt(),
        ),
        TallySkin.JADE to SkinTone(
            paper = 0xFFE7EDE9.toInt(), card = 0xFFF3F6F3.toInt(),
            ink = 0xFF0F1F19.toInt(), ink2 = 0xFF3C5148.toInt(), ink3 = 0xFF708983.toInt(),
            brand = 0xFF143028.toInt(), onBrand = 0xFFEAF2EE.toInt(),
            summaryBar = 0xFF0F1F19.toInt(), onSummaryBar = 0xFFEAF2EE.toInt(),
        ),
        TallySkin.AMBER to SkinTone(
            paper = 0xFFFAF3E1.toInt(), card = 0xFFFFFAEB.toInt(),
            ink = 0xFF241C08.toInt(), ink2 = 0xFF5C4F2A.toInt(), ink3 = 0xFF94855A.toInt(),
            brand = 0xFF8A6A18.toInt(), onBrand = 0xFFFFFDF5.toInt(),
            summaryBar = 0xFF241C08.toInt(), onSummaryBar = 0xFFFFFDF5.toInt(),
        ),
        TallySkin.VOYAGE to SkinTone(
            paper = 0xFFEEF1F6.toInt(), card = 0xFFFBFCFE.toInt(),
            ink = 0xFF131A26.toInt(), ink2 = 0xFF3F4A5C.toInt(), ink3 = 0xFF7A8598.toInt(),
            brand = 0xFF33517A.toInt(), onBrand = 0xFFFFFFFF.toInt(),
            summaryBar = 0xFF131A26.toInt(), onSummaryBar = 0xFFFFFFFF.toInt(),
        ),
    )

    private val DARK = mapOf(
        TallySkin.INK to SkinTone(
            paper = 0xFF131A17.toInt(), card = 0xFF1A2320.toInt(),
            ink = 0xFFE9E5DA.toInt(), ink2 = 0xFFB9C2BB.toInt(), ink3 = 0xFF93A09A.toInt(),
            brand = 0xFF7ECBA9.toInt(), onBrand = 0xFF131A17.toInt(),
            summaryBar = 0xFFE9E5DA.toInt(), onSummaryBar = 0xFF131A17.toInt(),
        ),
        TallySkin.WHITE to SkinTone(
            paper = 0xFF0E0F11.toInt(), card = 0xFF17181B.toInt(),
            ink = 0xFFF2F3F4.toInt(), ink2 = 0xFFC3C7CB.toInt(), ink3 = 0xFF8C9197.toInt(),
            brand = 0xFF6FD3A6.toInt(), onBrand = 0xFF0E0F11.toInt(),
            summaryBar = 0xFFF2F3F4.toInt(), onSummaryBar = 0xFF0E0F11.toInt(),
        ),
        TallySkin.ICE to SkinTone(
            paper = 0xFF0F1620.toInt(), card = 0xFF16202C.toInt(),
            ink = 0xFFE4EAF1.toInt(), ink2 = 0xFFB3BEC9.toInt(), ink3 = 0xFF7C8996.toInt(),
            brand = 0xFF7FB6E0.toInt(), onBrand = 0xFF0F1620.toInt(),
            summaryBar = 0xFFE4EAF1.toInt(), onSummaryBar = 0xFF0F1620.toInt(),
        ),
        TallySkin.GRAPHITE to SkinTone(
            paper = 0xFF121212.toInt(), card = 0xFF1B1B1B.toInt(),
            ink = 0xFFEDEDEB.toInt(), ink2 = 0xFFC0C0BD.toInt(), ink3 = 0xFF8B8B88.toInt(),
            brand = 0xFFD6D6D2.toInt(), onBrand = 0xFF121212.toInt(),
            summaryBar = 0xFFEDEDEB.toInt(), onSummaryBar = 0xFF121212.toInt(),
        ),
        TallySkin.SAND to SkinTone(
            paper = 0xFF171208.toInt(), card = 0xFF211A0F.toInt(),
            ink = 0xFFEFE3D0.toInt(), ink2 = 0xFFC6B69E.toInt(), ink3 = 0xFF95876E.toInt(),
            brand = 0xFFD8A96A.toInt(), onBrand = 0xFF171208.toInt(),
            summaryBar = 0xFFEFE3D0.toInt(), onSummaryBar = 0xFF171208.toInt(),
        ),
        TallySkin.WISTERIA to SkinTone(
            paper = 0xFF14121A.toInt(), card = 0xFF1D1A25.toInt(),
            ink = 0xFFEAE6F1.toInt(), ink2 = 0xFFC0B9CD.toInt(), ink3 = 0xFF8B8497.toInt(),
            brand = 0xFFB79BDB.toInt(), onBrand = 0xFF14121A.toInt(),
            summaryBar = 0xFFEAE6F1.toInt(), onSummaryBar = 0xFF14121A.toInt(),
        ),
        TallySkin.PINE to SkinTone(
            paper = 0xFF0C1512.toInt(), card = 0xFF13211C.toInt(),
            ink = 0xFFDDEDE6.toInt(), ink2 = 0xFFADC9C0.toInt(), ink3 = 0xFF7C9B90.toInt(),
            brand = 0xFF63C9A8.toInt(), onBrand = 0xFF0C1512.toInt(),
            summaryBar = 0xFFDDEDE6.toInt(), onSummaryBar = 0xFF0C1512.toInt(),
        ),
        TallySkin.JADE to SkinTone(
            paper = 0xFF0A100D.toInt(), card = 0xFF101814.toInt(),
            ink = 0xFFD9E5DE.toInt(), ink2 = 0xFFA9BDB4.toInt(), ink3 = 0xFF78908A.toInt(),
            brand = 0xFF6FC4A0.toInt(), onBrand = 0xFF0A100D.toInt(),
            summaryBar = 0xFFD9E5DE.toInt(), onSummaryBar = 0xFF0A100D.toInt(),
        ),
        TallySkin.AMBER to SkinTone(
            paper = 0xFF16120A.toInt(), card = 0xFF1F1A0E.toInt(),
            ink = 0xFFF1E7CC.toInt(), ink2 = 0xFFC9BB97.toInt(), ink3 = 0xFF94875D.toInt(),
            brand = 0xFFE3C06A.toInt(), onBrand = 0xFF16120A.toInt(),
            summaryBar = 0xFFF1E7CC.toInt(), onSummaryBar = 0xFF16120A.toInt(),
        ),
        TallySkin.VOYAGE to SkinTone(
            paper = 0xFF0B1018.toInt(), card = 0xFF121A26.toInt(),
            ink = 0xFFE0E6F0.toInt(), ink2 = 0xFFAFBACB.toInt(), ink3 = 0xFF7A8697.toInt(),
            brand = 0xFF8AB4E0.toInt(), onBrand = 0xFF0B1018.toInt(),
            summaryBar = 0xFFE0E6F0.toInt(), onSummaryBar = 0xFF0B1018.toInt(),
        ),
    )
}
