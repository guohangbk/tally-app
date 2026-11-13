package io.github.guohangbk.tally.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 一次收音的四个阶段：两处共用同一组状态与配色，只是小莉用整块底部圆弧，首页只在 ＋ 上方挂一枚浮标。 */
enum class VoicePhase {
    Idle,
    Listening,

    /** 已松手，等引擎把音频转成文字。 */
    Recognizing,

    /** 文字到手、还没发：小莉这边就地给人看一眼、改一改再决定发不发。 */
    Review,
}

/**
 * 收音期间每一帧都在变的那几个值。
 *
 * 单独拎出来是为了「谁读它谁重画」：声纹音量一秒变几十次，若跟着页面状态放，
 * 整页（含长对话列表）会被拉着一起重画，手势跟着变钝。
 */
class VoiceLive {
    var phase by mutableStateOf(VoicePhase.Idle)
    var level by mutableStateOf(0f)
    var partial by mutableStateOf("")
    var elapsedMs by mutableStateOf(0L)
    var cancelArmed by mutableStateOf(false)
    var review by mutableStateOf("")
}

/**
 * 全屏语音蒙层：整页压暗，上方一张大气泡，底部一道圆弧托住按住的动作。
 *
 * 气泡就是「这一句」：录音时显示引擎回传的半截文字（还没有字就只回两点声纹），
 * 松手进入 [VoicePhase.Review] 后同一块地方变成可直接改的文字；
 * 底部圆弧里只有按住时的麦克风与声纹，审核态换成「取消」与小莉的发送圆钮。
 *
 * 蒙层不接管录音中的触摸：这一下按住的触点在输入栏（或 ＋）上，蒙层是后画上去的，
 * 给它加触摸拦截会让人松手都收不到。审核态才把空白处吃掉，避免误触底下的对话。
 */
@Composable
fun VoiceOverlay(
    live: VoiceLive,
    modifier: Modifier = Modifier,
    hint: String? = null,
    showTranscript: Boolean = true,
    onCancel: (() -> Unit)? = null,
    onSend: (() -> Unit)? = null,
) {
    val colors = tallyColors
    // 蒙层淡入：整块突然变暗会让人以为自己点错了地方。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160)) }
    val phase = live.phase
    val armed = live.cancelArmed
    val reviewing = phase == VoicePhase.Review

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(1f)
            .background(if (colors.isDark) Color.Black.copy(alpha = 0.78f) else colors.ink.copy(alpha = 0.66f))
            // 审核态把空白处的点击吃掉，底下的对话不会再被误触；只吞不取消——一句已经识别好的话
            // 不该因为点偏一下就没了。收在父层而不是另铺一层同尺寸的挡板：挡板一旦是兄弟节点，
            // 事件会先落到它身上，气泡里的输入框与按钮就点不动了；父层永远排在子节点之后。
            .then(
                if (reviewing) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer { alpha = appear.value },
    ) {
        // 气泡按屏高定在中间偏上：与底部圆弧之间留出「正在说」的那段空白。
        val bubbleTop = maxHeight * 0.30f
        if (showTranscript || reviewing) {
            VoiceBubble(
                live = live,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 20.dp, end = 20.dp)
                    .padding(top = bubbleTop),
            )
        }

        VoiceDome(
            live = live,
            reviewing = reviewing,
            onCancel = onCancel,
            onSend = onSend,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // 上滑过了取消线就把说法换成「松手就丢」，别让人以为松手还会发出去。
        val shownHint = if (armed && phase == VoicePhase.Listening) "松开手指 · 取消这一次" else hint
        if (shownHint != null) {
            Text(
                text = shownHint,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = if (armed) ArmedHint else PaperOnScrim.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 194.dp),
            )
        }
    }
}

/**
 * 这一句的文字气泡：大块、圆角、纸面，空格时用「···」加一条声纹表示还在听。
 *
 * 审核态这里换成可编辑的文字，光标直接落在气泡里——改字不再另开一个输入框。
 */
@Composable
private fun VoiceBubble(live: VoiceLive, modifier: Modifier = Modifier) {
    val colors = tallyColors
    val phase = live.phase
    val armed = live.cancelArmed
    val reviewing = phase == VoicePhase.Review
    val shape = RoundedCornerShape(20.dp)
    val textColor = if (armed) colors.expense else colors.ink
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = colors.ink.copy(alpha = 0.12f),
                spotColor = colors.ink.copy(alpha = 0.18f),
            )
            .clip(shape)
            .background(if (armed) colors.expenseSurface else colors.card)
            .border(1.dp, if (armed) colors.expense.copy(alpha = 0.55f) else colors.line, shape)
            .heightIn(min = 168.dp, max = 320.dp)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        when {
            reviewing -> ReviewText(text = live.review, onTextChange = { live.review = it })
            live.partial.isNotBlank() -> Text(
                text = live.partial,
                fontSize = 15.5.sp,
                lineHeight = 24.sp,
                color = textColor,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )

            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "···",
                    fontSize = 15.5.sp,
                    lineHeight = 24.sp,
                    letterSpacing = 3.sp,
                    color = if (armed) colors.expense.copy(alpha = 0.7f) else colors.ink3,
                )
                VoiceWaveform(
                    level = live.level,
                    tint = if (armed) colors.expense else colors.gold,
                    height = 26.dp,
                )
            }
        }
    }
}

/** 审核态的那段字：点进去就能改，改完按底部的发送圆钮才真的发出去。 */
@Composable
private fun ReviewText(text: String, onTextChange: (String) -> Unit) {
    val colors = tallyColors
    val scroll = rememberScrollState()
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 250.dp)
            .verticalScroll(scroll),
        textStyle = TextStyle(fontSize = 15.5.sp, lineHeight = 24.sp, color = colors.ink),
        cursorBrush = SolidColor(colors.gold),
        decorationBox = { inner ->
            if (text.isEmpty()) {
                Text("说吧，文字会出现在这里", fontSize = 15.5.sp, lineHeight = 24.sp, color = colors.ink3.copy(alpha = 0.75f))
            }
            inner()
        },
    )
}

/**
 * 底部圆弧：按住时托住麦克风与声纹，松手后换成「取消」与发送圆钮。
 *
 * 弧顶是一段半径等于半屏宽的正圆，所以看起来像从屏幕底边长出来的一口碗。
 * 弧内是实心灰底：底下压着的对话与输入栏不再透上来，图标和按钮才看得清。
 */
@Composable
private fun VoiceDome(
    live: VoiceLive,
    reviewing: Boolean,
    onCancel: (() -> Unit)?,
    onSend: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    val armed = live.cancelArmed
    val tint = PaperOnScrim
    val fill = when {
        armed && colors.isDark -> Color(0xFF4E332D)
        armed -> Color(0xFF72463D)
        colors.isDark -> Color(0xFF2A322E)
        else -> Color(0xFF4C5A53)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(VoiceDomeShape)
            .background(fill)
            .border(1.dp, Color.White.copy(alpha = 0.14f), VoiceDomeShape),
    ) {
        if (reviewing) {
            val sendEnabled = live.review.isNotBlank() && onSend != null
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 30.dp)
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(colors.brand.copy(alpha = if (sendEnabled) 1f else 0.4f))
                    .clickable(enabled = sendEnabled) { onSend?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                InlineIcon(iconKey = "check", contentDescription = "发送", size = 30.dp, tint = colors.onBrand)
            }
            // 取消挪到发送圆钮左边：两个动作同一水平线，误触面比上下叠着小。
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp)
                    .offset(x = (-104).dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = onCancel != null) { onCancel?.invoke() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InlineIcon(iconKey = "undo", contentDescription = "取消这一次", size = 22.dp, tint = tint)
                Spacer(Modifier.height(4.dp))
                Text(text = "取消", fontSize = 13.sp, lineHeight = 17.sp, color = tint)
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InlineIcon(iconKey = "mic", contentDescription = null, size = 44.dp, tint = tint)
                Spacer(Modifier.height(10.dp))
                VoiceWaveform(level = live.level, tint = tint, height = 22.dp)
                if (armed) {
                    Spacer(Modifier.height(10.dp))
                    Text(text = "松手丢弃", fontSize = 11.5.sp, lineHeight = 15.sp, color = tint.copy(alpha = 0.85f))
                }
            }
        }
    }
}

/** 圆弧顶边是半径等于半屏宽的正圆；屏窄于一屏高时才退化成压扁的弧。 */
private object VoiceDomeShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = min(size.width / 2f, size.height)
        val path = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, radius)
            arcTo(
                rect = Rect(0f, 0f, size.width, radius * 2f),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(size.width, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * 声纹：13 根柱子跟着音量起伏，再叠一条慢速相位差做「活着的抖动」——
 * 只靠音量的话，安静时整排一起趴下，看起来像卡住了。
 */
@Composable
private fun VoiceWaveform(
    level: Float,
    tint: Color,
    height: Dp = 40.dp,
    barWidth: Dp = 3.dp,
    gap: Dp = 2.5.dp,
) {
    val bases = listOf(0.22f, 0.46f, 0.72f, 0.38f, 0.9f, 0.3f, 0.62f, 1f, 0.34f, 0.78f, 0.44f, 0.88f, 0.28f)
    val pulse = rememberInfiniteTransition(label = "voice-wave")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "phase",
    )
    val smoothed by animateFloatAsState(level, tween(90), label = "level")
    val barMax = height.value
    Row(
        modifier = Modifier.height(height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        bases.forEachIndexed { index, base ->
            val wobble = 0.66f + 0.34f * sin(phase + index * 0.62f)
            // 音量只往上叠、不决定有没有动作：小米这类引擎一帧 rms 都不回，
            // 按音量算高度整排会趴成一条线，看着像没开始录音。
            val energy = 0.55f + 0.45f * smoothed
            val barHeight = barMax * base * energy * wobble
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight.coerceAtLeast(4f).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint.copy(alpha = 0.9f)),
            )
        }
    }
}

/** 蒙层上的纸色：两套主题下底子都被压暗了，字与图标反而必须一直是浅的。 */
private val PaperOnScrim = Color(0xFFF6F3EB)

/** 上滑取消时的那一句提示：比纸色偏红一档，跟气泡、圆弧的取消色对得上。 */
private val ArmedHint = Color(0xFFF1B4A6)

/**
 * 首页的收音浮标：＋ 上方一枚胶囊，只报这一句当下的状态。
 *
 * 首页的手势就发生在 ＋ 上，不能用小莉那口 180dp 的大碗把手指和整个工作区盖住；
 * 起势那层压暗由扇面自己铺，这里也不再叠第二层蒙层。
 * 秒数在 [capMillis] 的最后五秒转红：到点会自动收尾，得让人看见它快到点了。
 */
@Composable
fun VoicePill(
    live: VoiceLive,
    modifier: Modifier = Modifier,
    capMillis: Long? = null,
) {
    val colors = tallyColors
    val armed = live.cancelArmed
    val recognizing = live.phase == VoicePhase.Recognizing
    val remaining = capMillis?.minus(live.elapsedMs)
    val runningOut = remaining != null && remaining in 1L..5_000L
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(140)) }
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .graphicsLayer {
                alpha = appear.value
                val grow = 0.92f + 0.08f * appear.value
                scaleX = grow
                scaleY = grow
            }
            .shadow(10.dp, shape, ambientColor = colors.ink.copy(alpha = 0.10f), spotColor = colors.ink.copy(alpha = 0.16f))
            .clip(shape)
            .background(if (armed) colors.expenseSurface else colors.card)
            .border(1.dp, if (armed) colors.expense.copy(alpha = 0.45f) else colors.line, shape)
            .heightIn(min = 40.dp)
            .widthIn(min = 106.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        when {
            armed -> {
                InlineIcon(iconKey = "undo", contentDescription = null, size = 15.dp, tint = colors.expense)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "松开取消",
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.expense,
                    maxLines = 1,
                )
            }

            recognizing -> {
                Text(text = "···", fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 2.sp, color = colors.ink3)
                Spacer(Modifier.width(8.dp))
                Text(text = "识别中", fontSize = 12.5.sp, lineHeight = 16.sp, color = colors.ink2, maxLines = 1)
            }

            else -> {
                VoiceWaveform(
                    level = live.level,
                    tint = if (runningOut) colors.expense else colors.gold,
                    height = 18.dp,
                    barWidth = 2.dp,
                    gap = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = voiceClock(live.elapsedMs),
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    color = if (runningOut) colors.expense else colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun voiceClock(millis: Long): String {
    val total = (millis / 1_000L).coerceAtLeast(0L)
    return "${(total / 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
}
