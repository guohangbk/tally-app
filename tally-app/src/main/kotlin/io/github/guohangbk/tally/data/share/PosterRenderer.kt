package io.github.guohangbk.tally.data.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import androidx.appcompat.content.res.AppCompatResources
import io.github.guohangbk.tally.R
import java.io.ByteArrayOutputStream

/**
 * 海报是**画**出来的，不是把界面截下来：宽固定 1080px、高度按内容、长内容不会断在半行，
 * 状态栏与别人的手机壳也不会入图（《操作逻辑设计思路》§22）。
 *
 * 版式逐屏对齐界面设计板 §12 SH-02 / SH-04 / SH-05；度量按「1dp ≙ 3px」折算，
 * 与 App 同一个字族规则：金额用衬线，标题、分区名、脚注与小结正文一律无衬线。
 */
object PosterRenderer {
    const val WIDTH_PX = 1080
    const val MAX_HEIGHT_PX = 8000
    private const val MARGIN = 72f
    private const val INNER = WIDTH_PX - MARGIN * 2

    /** 一段之前的虚线分隔，占位高度固定——`height()` 与 `draw()` 数的是同一串常数。 */
    private const val RULE = 56f
    private const val TOP = 96f
    private const val BOTTOM = 96f

    // 令牌值取自 ui/design/TallyColors.kt 的纸墨茉莉；bitmap 读不到 Compose 对象，只能同值硬写。
    private val PAPER = Color.rgb(0xF6, 0xF3, 0xEB)
    private val INK = Color.rgb(0x16, 0x21, 0x1D)
    private val INK2 = Color.rgb(0x4C, 0x5A, 0x53)
    private val INK3 = Color.rgb(0x8D, 0x97, 0x8F)
    private val BRAND = Color.rgb(0x1F, 0x4A, 0x38)
    private val GOLD = Color.rgb(0xB9, 0x8A, 0x34)
    private val GOLD_HI = Color.rgb(0xE8, 0xC5, 0x7A)
    private val SLATE = Color.rgb(0x5E, 0x7A, 0x8A)
    private val EXPENSE = Color.rgb(0xBF, 0x4E, 0x3B)

    /** 出图：spec → ARGB Bitmap。调用方负责在 IO 线程执行并用完 `recycle()`。 */
    fun render(context: Context, spec: PosterSpec): Bitmap {
        val ink = Ink()
        val fitted = fit(context, spec, ink)
        val digest = fitted.digest?.let { ink.paragraph(it, INNER.toInt()) }
        val bitmap = Bitmap.createBitmap(WIDTH_PX, height(fitted, digest?.height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)
        draw(context, canvas, fitted, digest, ink)
        return bitmap
    }

    /** PNG 字节。只出 PNG：JPG 的压缩噪点在纯色纸底上肉眼可见，而体积不是这张图要省的东西。 */
    fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /**
     * 超上限时的取舍：先砍趋势段，再把排行截到 5 条，最后才动结论的字数。
     * 抬头、大数字、落款永不砍——那几段才是「这段账讲了什么」的下限。
     */
    private fun fit(context: Context, spec: PosterSpec, ink: Ink): PosterSpec {
        fun tooDeep(candidate: PosterSpec): Boolean =
            height(candidate, candidate.digest?.let { ink.paragraph(it, INNER.toInt()).height }) > MAX_HEIGHT_PX

        if (!tooDeep(spec)) return spec
        val withoutTrend = spec.copy(trendTitle = null, bars = emptyList(), averageRatio = null, trendCaption = null)
        if (!tooDeep(withoutTrend)) return withoutTrend
        val shorter = withoutTrend.copy(ranking = withoutTrend.ranking.take(5))
        if (!tooDeep(shorter)) return shorter
        return shorter.copy(digest = shorter.digest?.let { it.take(120) + "…" })
    }

    private fun height(spec: PosterSpec, digestHeight: Int?): Int {
        var y = TOP + 44f + 92f + 44f + RULE + 60f + 112f + 78f
        if (spec.rankingTitle != null) y += RULE + 60f + spec.ranking.size * 96f + 16f
        if (spec.trendTitle != null) {
            y += RULE + 60f + 210f + (if (spec.trendCaption != null) 52f else 0f) + 34f
        }
        if (spec.budgetText != null) y += RULE + 60f + 58f + 34f + 22f
        digestHeight?.let { y += RULE + 60f + it + 12f }
        y += RULE + 96f
        return (y + BOTTOM).toInt().coerceAtMost(MAX_HEIGHT_PX)
    }

    private fun draw(context: Context, canvas: Canvas, spec: PosterSpec, digest: StaticLayout?, ink: Ink) {
        var y = TOP
        ink.text(canvas, spec.eyebrow, MARGIN, y + 30f, ink.eyebrow)
        y += 44f
        ink.text(canvas, spec.periodTitle, MARGIN, y + 74f, ink.title)
        y += 92f
        ink.text(canvas, spec.subline, MARGIN, y + 32f, ink.sub)
        y += 44f

        y = section(canvas, ink, y, if (spec.expenseLabel == "收入") "收入" else "支出")
        drawHero(canvas, spec, ink, y)
        y += 112f
        drawStats(canvas, spec.stats, ink, y)
        y += 78f

        if (spec.rankingTitle != null) {
            y = section(canvas, ink, y, spec.rankingTitle)
            spec.ranking.forEach { line ->
                drawRankRow(canvas, line, ink, y)
                y += 96f
            }
            y += 16f
        }

        if (spec.trendTitle != null) {
            y = section(canvas, ink, y, spec.trendTitle)
            drawBars(canvas, spec, ink, y)
            y += 210f
            spec.trendCaption?.let {
                ink.text(canvas, it, MARGIN, y + 36f, ink.caption)
                y += 52f
            }
            y += 34f
        }

        if (spec.budgetText != null) {
            y = section(canvas, ink, y, "预算")
            ink.text(canvas, spec.budgetText, MARGIN, y + 40f, ink.budget)
            y += 58f
            drawBudgetBar(canvas, spec, ink, y)
            y += 34f + 22f
        }

        digest?.let { layout ->
            y = section(canvas, ink, y, "结论")
            canvas.save()
            canvas.translate(MARGIN, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 12f
        }

        y += RULE
        ink.rule(canvas, MARGIN, y - RULE / 2, MARGIN + INNER, y - RULE / 2)
        drawFooter(context, canvas, spec, ink, y)
    }

    /** 虚线 + 段名，返回正文起始的 y。 */
    private fun section(canvas: Canvas, ink: Ink, y: Float, title: String): Float {
        val line = y + RULE / 2
        ink.rule(canvas, MARGIN, line, MARGIN + INNER, line)
        val next = y + RULE
        ink.text(canvas, title, MARGIN, next + 40f, ink.section)
        return next + 60f
    }

    /** 「¥31,864.00」：符号缩到 .55em 并留在基线上，与 App 里 `.cur{font-size:.52em}` 同一做法。 */
    private fun drawHero(canvas: Canvas, spec: PosterSpec, ink: Ink, y: Float) {
        val income = spec.expenseLabel == "收入"
        val value = if (spec.expenseValue.startsWith("¥")) spec.expenseValue.drop(1) else spec.expenseValue
        val shownSymbol = spec.expenseValue.startsWith("¥")
        var x = MARGIN
        if (shownSymbol) {
            ink.text(canvas, "¥", x, y + 60f, ink.heroSymbol)
            x += ink.heroSymbol.measureText("¥") + 8f
        }
        val paint = if (income) ink.heroIncome else ink.heroExpense
        ink.text(canvas, value, x, y + 60f, paint)
        x += paint.measureText(value)
        spec.change?.let { ink.text(canvas, it, x + 22f, y + 60f, ink.change) }
    }

    private fun drawStats(canvas: Canvas, stats: List<PosterStat>, ink: Ink, y: Float) {
        val slot = INNER / stats.size.coerceAtLeast(1)
        stats.forEachIndexed { index, stat ->
            val left = MARGIN + index * slot
            ink.text(canvas, stat.label, left, y + 28f, ink.statLabel)
            ink.text(canvas, stat.value, left, y + 72f, ink.statValue)
        }
    }

    private fun drawRankRow(canvas: Canvas, line: PosterLine, ink: Ink, y: Float) {
        ink.text(canvas, line.label, MARGIN, y + 34f, ink.rankLabel)
        val valueWidth = ink.rankValue.measureText(line.value)
        ink.text(canvas, line.value, MARGIN + INNER - valueWidth, y + 34f, ink.rankValue)
        val bar = RectF(MARGIN, y + 54f, MARGIN + INNER * line.ratio.coerceIn(0f, 1f), y + 68f)
        if (line.lead) {
            barPaint(ink.bar, BRAND, 190)
            canvas.drawRoundRect(bar, 7f, 7f, ink.bar)
        } else {
            ink.bar.shader = LinearGradient(
                MARGIN, y, MARGIN + INNER, y,
                intArrayOf(BRAND, SLATE), null, Shader.TileMode.CLAMP,
            )
            barPaint(ink.bar, SLATE, 110)
            canvas.drawRoundRect(bar, 7f, 7f, ink.bar)
            ink.bar.shader = null
        }
    }

    private fun drawBars(canvas: Canvas, spec: PosterSpec, ink: Ink, y: Float) {
        if (spec.bars.isEmpty()) return
        val bottom = y + 210f
        val gap = if (spec.bars.size > 16) 5f else 14f
        val width = (INNER - gap * (spec.bars.size - 1)) / spec.bars.size
        spec.bars.forEachIndexed { index, bar ->
            val left = MARGIN + index * (width + gap)
            val height = (210f * bar.ratio).coerceAtLeast(6f)
            val rect = RectF(left, bottom - height, left + width.coerceAtLeast(4f), bottom)
            if (bar.highlight) {
                ink.bar.shader = LinearGradient(
                    0f, bottom - height, 0f, bottom,
                    intArrayOf(GOLD_HI, GOLD), null, Shader.TileMode.CLAMP,
                )
                barPaint(ink.bar, GOLD, 255)
                canvas.drawRoundRect(rect, 6f, 6f, ink.bar)
                ink.bar.shader = null
            } else {
                barPaint(ink.bar, BRAND, if (height <= 8f) 56 else 74)
                canvas.drawRoundRect(rect, 4f, 4f, ink.bar)
            }
        }
        // 月均那根虚线：「平均」最省字法的表达，柱区里不写字也能读出来。
        spec.averageRatio?.let { ratio ->
            val line = bottom - 210f * ratio.coerceIn(0f, 1f)
            ink.dash.color = GOLD
            ink.dash.alpha = 200
            canvas.drawLine(MARGIN, line, MARGIN + INNER, line, ink.dash)
            ink.text(canvas, "月均", MARGIN + INNER - ink.mini.measureText("月均"), line - 14f, ink.mini)
        }
    }

    private fun drawBudgetBar(canvas: Canvas, spec: PosterSpec, ink: Ink, y: Float) {
        val ratio = spec.budgetRatio ?: return
        val track = RectF(MARGIN, y, MARGIN + INNER, y + 16f)
        barPaint(ink.track, INK, 24)
        canvas.drawRoundRect(track, 8f, 8f, ink.track)
        val fill = INNER * ratio.coerceIn(0f, 1f)
        if (fill > 0f) {
            barPaint(
                ink.bar,
                when {
                    spec.budgetOver -> EXPENSE
                    ratio >= 0.8f -> GOLD
                    else -> BRAND
                },
                255,
            )
            canvas.drawRoundRect(RectF(MARGIN, y, MARGIN + fill.coerceAtLeast(18f), y + 16f), 8f, 8f, ink.bar)
        }
    }

    private fun drawFooter(context: Context, canvas: Canvas, spec: PosterSpec, ink: Ink, y: Float) {
        val glyph = runCatching { AppCompatResources.getDrawable(context, R.drawable.ic_jasmine) }.getOrNull()
        val textWidth = ink.footer.measureText(spec.footer)
        val total = textWidth + if (glyph != null) 56f else 0f
        var x = MARGIN + (INNER - total) / 2f
        if (glyph != null) {
            glyph.setTint(BRAND)
            glyph.setBounds(x.toInt(), (y + 4f).toInt(), (x + 36f).toInt(), (y + 40f).toInt())
            glyph.draw(canvas)
            x += 56f
        }
        ink.text(canvas, spec.footer, x, y + 34f, ink.footer)
    }

    private fun barPaint(paint: Paint, color: Int, alpha: Int) {
        paint.shader = null
        paint.color = color
        paint.alpha = alpha
    }

    /** 画笔集合：字号只在这里定义一次，量高与绘制才不会用两套数。 */
    private class Ink {
        private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        private val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

        val eyebrow = paint(sansBold, 30f, INK3).apply { letterSpacing = 0.18f }
        // 海报里只有金额用衬线，标题、分区名、脚注、小莉小结都是文字，跟 App 内的字族规则一致。
        val title = paint(sansBold, 76f, INK)
        val sub = paint(sans, 32f, INK3)
        val section = paint(sansBold, 36f, INK).apply { letterSpacing = 0.08f }
        val heroExpense = paint(serifBold, 96f, EXPENSE)
        val heroIncome = paint(serifBold, 96f, BRAND)
        val heroSymbol = paint(serif, 52f, INK2)
        val change = paint(sansBold, 34f, GOLD)
        val statLabel = paint(sans, 28f, INK3)
        val statValue = paint(serif, 46f, INK)
        val rankLabel = paint(sans, 34f, INK2)
        val rankValue = paint(serif, 38f, INK)
        val caption = paint(sans, 32f, INK2)
        val budget = paint(serif, 40f, INK)
        val footer = paint(sans, 30f, INK3)
        val mini = paint(sans, 26f, GOLD)
        val digest = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = sans
            textSize = 38f
            color = INK2
        }

        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val track = Paint(Paint.ANTI_ALIAS_FLAG)
        val dash = stroke(3f, floatArrayOf(14f, 10f))
        private val rulePaint = stroke(2f, floatArrayOf(12f, 12f)).apply {
            color = INK
            alpha = 30
        }

        fun rule(canvas: Canvas, fromX: Float, atY: Float, toX: Float, toY: Float) {
            canvas.drawLine(fromX, atY, toX, toY, rulePaint)
        }

        fun text(canvas: Canvas, value: String, x: Float, baseline: Float, paint: Paint) {
            canvas.drawText(value, x, baseline, paint)
        }

        /** 长句断行交给 StaticLayout：中文没有空格可切，自己数字符宽一定会断错。 */
        fun paragraph(value: String, width: Int): StaticLayout =
            StaticLayout.Builder
                .obtain(value, 0, value.length, digest, width)
                .setLineSpacing(14f, 1f)
                .setIncludePad(false)
                .build()

        private fun stroke(width: Float, effect: FloatArray): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                pathEffect = DashPathEffect(effect, 0f)
            }

        private fun paint(typeface: Typeface, size: Float, color: Int): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = size
                this.color = color
            }
    }
}
