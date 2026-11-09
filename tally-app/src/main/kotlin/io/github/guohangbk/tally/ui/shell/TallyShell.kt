package io.github.guohangbk.tally.ui.shell

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.R
import io.github.guohangbk.tally.data.ai.AssistantEntry
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyIconTileShape
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.design.tallyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 底栏已撤除（它只有「账单 / 小莉」两格，小莉搬进 ＋ 的右臂后剩一格不算导航）；高度留给 ＋ 与浮层算让位。 */
val TallyBottomBarHeight = 50.dp

/** 账单页的悬浮 ＋：它是本页的动作，不是导航目的地。 */
/**
 * ＋ 长按放射菜单的三个方向：上＝语音记账、左＝截图记账、右＝小莉。
 * 没有下臂——＋ 贴在屏幕底部，向下的行程会撞系统手势区。右臂常驻：
 * 小莉关着或没配好时仍然出，只是落点变成设置或配置页，入口不会随开关凭空消失。
 *
 * 语音是「按住不放」的连续动作：指尖滑进上臂即开始收音，松手即结束并识别——
 * 这样设计板那句「松手结束并识别」和「滑回 ＋ 原位置松手 = 取消」才同时成立。
 */
enum class TallyFanEvent { VoiceStart, VoiceFinish, VoiceCancel, Screenshot, Xiaoli }

/**
 * 起势阈值。账单行进选择态用的是 combinedClickable 的默认长按时长，
 * 这里取同一个数字，手掌只需要记一种「按住」。
 */
const val TallyLongPressMillis = 500L

/*
 * ＋ 从右下角改到屏幕底部居中（右下角离拇指太近、不跟手，
 * 两臂又被挤在边角上，弹出与拖尾动画根本看不见），臂心距 78→118dp。
 * 下面四个量是一组：两臂落点、文案基线与走廊宽度都由它们导出，不能单独改。
 */
private const val FAB_BOX_DP = 56f
private val FanArmBubble = 52.dp
private val FanArmReach = 118.dp
private val FanArmHitRadius = 40.dp

/** ＋ 到容器底边（有底栏时是底栏顶边）的距离。 */
private val FabBottomPad = 13.dp

/**
 * ＋ 顶边到它所在容器底边的距离。＋ 居中之后的底部浮层（提示条 / 撤销条 / 状态卡）
 * 再靠右边距已经躲不开，只能整体抬到它上方，所以这个数得对外可用。
 */
val TallyFabClearance = FabBottomPad + FAB_BOX_DP.dp + 6.dp

/** 与屏幕底边对齐的浮层要抬到多高：底栏存在时连底栏一起算。 */
fun tallyFabOverlayLift(raised: Boolean): Dp = (if (raised) TallyBottomBarHeight else 0.dp) + TallyFabClearance

/** ＋ 与臂气泡共用同一曲率，与卡片、按钮不成两族。 */
private val FabCorner = 20.dp
private val FabHalf = (FAB_BOX_DP / 2f).dp
private val BubbleHalf = FanArmBubble / 2f

/** 上臂的纵向偏移：臂心 = ＋ 心 + reach ⇒ 28 + 118 - 26。 */
private val FanArmLift = FabHalf + FanArmReach - BubbleHalf

/** 提示文案基线：臂气泡远端再留 14dp，不压在气泡上。 */
private val FanHintLift = FanArmLift + FanArmBubble + 14.dp

/** ＋ 与臂气泡之间那条走廊：拖尾箭头就在这里行军。 */
private val FanCorridor = FanArmLift - FAB_BOX_DP.dp

private const val FabPopMillis = 220
private const val FanPopMillis = 200
private const val FanArmStaggerMillis = 70
private const val TrailMillis = 1200
private const val TrailStaggerMillis = 380
private const val RingMillis = 1500
private const val TrailChevrons = 3

/** 引导光晕一圈走完的时间；三圈错开三分之一周期。 */
private const val GuideRingMillis = 2100

/** 光晕在 72% 处已经消散完，后面那段空转不值得再算。 */
private const val RingFade = 0.72f

/** 起势时两臂半透明（只给方向），命中哪条哪条才实体化。 */
private const val GhostAlpha = 0.4f

/** 弹出时箭头从 ＋ 那一侧滑过来的行程。 */
private val FanArmEnter = 26.dp

/**
 * 拖尾箭头的可见区间：走廊拉到 64dp 后，三点从 ＋ 边缘行军到臂气泡前，
 * 错峰 380ms 刚好把这段路分成 16dp 左右的间距，看得出在动而不是糊成一片。
 */
private val TrailNear = 26.dp
private val TrailFar = 26.dp
private val TrailChevron = 13.dp
private val TrailChevronThin = 8.dp
private const val TrailFadeIn = 0.22f
private const val TrailPeakAlpha = 0.92f

private val FanPopEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.25f)
private val FabPopEasing = CubicBezierEasing(0.2f, 0.85f, 0.3f, 1.2f)

@Composable
fun BoxScope.TallyRecordFab(
    raised: Boolean,
    onClick: () -> Unit,
    onFanEvent: (TallyFanEvent) -> Unit = {},
    /** 右臂＝小莉。三态常驻：关闭去设置、开启未配置去配置页、可用才进对话。 */
    xiaoliEntry: AssistantEntry = AssistantEntry.Settings,
    /** 首页一次性引导浮着时，＋ 外面走一圈圈扩散波纹，提示按住这里。 */
    guide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    val shape = RoundedCornerShape(FabCorner)
    val gradient = if (colors.isDark) {
        listOf(colors.brand.copy(alpha = 0.92f), colors.brand, colors.brand.copy(alpha = 0.62f))
    } else {
        listOf(colors.brand.copy(alpha = 0.78f), colors.brand, colors.brand.copy(alpha = 1f))
    }
    val bottomPad = if (raised) FabBottomPad + TallyBottomBarHeight else FabBottomPad
    var fan by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf<TallyFanEvent?>(null) }
    var pressing by remember { mutableStateOf(false) }
    val reduced = rememberReducedMotion()
    // 手指按住不动时不会再有指针事件，起势只能由组合侧的计时器来触发。
    LaunchedEffect(pressing) {
        if (pressing) {
            delay(TallyLongPressMillis)
            if (pressing) {
                fan = true
                haptics.longPress()
            }
        }
    }

    // 指尖滑进某一臂时给一记轻响，确认「这一次滑到了」；滑回中心取消则不再响。
    LaunchedEffect(armed) {
        if (armed != null) haptics.tap()
    }

    // 起势的一刻 ＋ 回弹一下（0.84 → 1.06 → 1）并转成 ✕：这次按住已经不是「记一笔」了。
    val fabPop = remember { Animatable(1f) }
    val fabRotation by animateFloatAsState(
        if (fan) 45f else 0f,
        // 减少动态时连旋转都直接到位，不抢那个 220ms。
        if (reduced) snap() else tween(FabPopMillis, easing = FabPopEasing),
        label = "fabRotation",
    )
    LaunchedEffect(fan) {
        if (!fan) {
            fabPop.snapTo(1f)
        } else if (!reduced) {
            fabPop.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = FabPopMillis
                    0.84f at 0 using LinearEasing
                    1.06f at FabPopMillis * 62 / 100 using LinearEasing
                },
            )
        }
    }

    // 拖尾与光晕是循环相位，只在扇区展开时由帧时钟推进；值在 graphicsLayer / drawBehind 里读，
    // 走绘制不走重组——首页为冷启动付过的代价，不该被一个常驻动画抹掉。
    val trailPhase = remember { mutableFloatStateOf(0f) }
    val ringPhase = remember { mutableFloatStateOf(0f) }
    val guidePhase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(fan, reduced) {
        if (!fan || reduced) {
            trailPhase.floatValue = 0f
            ringPhase.floatValue = 0f
            return@LaunchedEffect
        }
        val started = withFrameNanos { it }
        while (isActive) {
            val elapsed = (withFrameNanos { it } - started) / 1_000_000L
            trailPhase.floatValue = (elapsed % TrailMillis) / TrailMillis.toFloat()
            ringPhase.floatValue = (elapsed % RingMillis) / RingMillis.toFloat()
        }
    }

    // 引导波纹是常驻循环，和扇区互斥（扇区一展开就停），相位同样只在绘制里读。
    LaunchedEffect(guide, fan, reduced) {
        if (!guide || fan || reduced) {
            guidePhase.floatValue = 0f
            return@LaunchedEffect
        }
        val started = withFrameNanos { it }
        while (isActive) {
            val elapsed = (withFrameNanos { it } - started) / 1_000_000L
            guidePhase.floatValue = (elapsed % GuideRingMillis) / GuideRingMillis.toFloat()
        }
    }

    if (fan) {
        // .fmask：整屏压暗，两臂从 ＋ 的上侧与左侧浮出，指尖还压在 ＋ 上时先半透明。
        BoxWithConstraints(
            modifier = Modifier
                .matchParentSize()
                .background(colors.ink.copy(alpha = if (colors.isDark) 0.55f else 0.42f)),
        ) {
            // ＋ 在底部居中，所以两臂的横向落点要从屏幕中线量起：气泡中心得压在 ＋ 的正上方 / 正左方。
            val upEnd = maxWidth / 2 - BubbleHalf
            val sideEnd = maxWidth / 2 + FanArmReach - BubbleHalf
            // 右臂从屏幕中线往右量同一段距离；它用 BottomStart 对齐，所以 padding 记的是左边距。
            val rightStart = maxWidth / 2 + FanArmReach - BubbleHalf
            val sideBottom = bottomPad + FabHalf - BubbleHalf
            FanArm(
                iconKey = "mic",
                label = "语音记账",
                hint = if (armed == TallyFanEvent.VoiceStart) "松手即结束" else "↑ 上滑",
                hot = armed == TallyFanEvent.VoiceStart,
                ghost = armed != TallyFanEvent.VoiceStart,
                up = true,
                stacked = false,
                enterDelayMillis = 0,
                reduced = reduced,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = upEnd, bottom = bottomPad + FanArmLift),
            )
            // 左臂的标签不能留在气泡左侧：reach 拉到 118 后，窄屏上那几个字会被屏幕左边裁掉。
            FanArm(
                iconKey = "image",
                label = "截图记账",
                hint = if (armed == TallyFanEvent.Screenshot) "松手即选图" else "← 左滑",
                hot = armed == TallyFanEvent.Screenshot,
                ghost = armed != TallyFanEvent.Screenshot,
                up = false,
                stacked = true,
                enterDelayMillis = FanArmStaggerMillis,
                reduced = reduced,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = sideEnd, bottom = sideBottom),
            )
            // 右臂＝小莉：底部导航那一格搬到这里，入口从此只有一个。
            // 它不随开关消失——没开时这一滑落到设置里的开关区，开了没配好落到配置页。
            FanArm(
                iconKey = "ai",
                label = "问小莉",
                hint = when {
                    armed == TallyFanEvent.Xiaoli -> when (xiaoliEntry) {
                        AssistantEntry.Settings -> "松手去设置"
                        AssistantEntry.Configure -> "松手去配置"
                        AssistantEntry.Chat -> "松手进入"
                    }
                    xiaoliEntry == AssistantEntry.Settings -> "右滑 → 设置"
                    xiaoliEntry == AssistantEntry.Configure -> "右滑 → 配置"
                    else -> "右滑 →"
                },
                hot = armed == TallyFanEvent.Xiaoli,
                ghost = armed != TallyFanEvent.Xiaoli,
                up = false,
                stacked = true,
                enterDelayMillis = FanArmStaggerMillis * 2,
                reduced = reduced,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = rightStart, bottom = sideBottom),
            )
            // 只有「起势但未命中」才需要指路；命中之后各臂自己会说话。
            if (armed == null) {
                FanTrail(
                    up = true,
                    phase = trailPhase,
                    reduced = reduced,
                    tint = colors.paper,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = maxWidth / 2 - FabHalf, bottom = bottomPad + FAB_BOX_DP.dp)
                        .size(FAB_BOX_DP.dp, FanCorridor),
                )
                FanTrail(
                    up = false,
                    phase = trailPhase,
                    reduced = reduced,
                    tint = colors.paper,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = maxWidth / 2 + FabHalf, bottom = bottomPad)
                        .size(FanCorridor, FAB_BOX_DP.dp),
                )
                // 右臂的拖尾：三条手臂都得指路，少一条看起来就像那一臂不能滑。
                FanTrail(
                    up = false,
                    flip = true,
                    phase = trailPhase,
                    reduced = reduced,
                    tint = colors.paper,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = maxWidth / 2 + FabHalf, bottom = bottomPad)
                        .size(FanCorridor, FAB_BOX_DP.dp),
                )
            }
            Text(
                text = when (armed) {
                    TallyFanEvent.VoiceStart -> "正在收音 · 松手结束并识别"
                    TallyFanEvent.Screenshot -> "松手打开相册 · 可一次选多张"
                    TallyFanEvent.Xiaoli -> when (xiaoliEntry) {
                        AssistantEntry.Settings -> "松手去设置 · 打开小莉"
                        AssistantEntry.Configure -> "松手去配置 · 配好就能聊"
                        AssistantEntry.Chat -> "松手进入小莉"
                    }
                    else -> "继续上滑、左滑或右滑 · 松手取消"
                },
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = TallyType.screenTitle.fontFamily,
                letterSpacing = 0.06.em,
                color = colors.paper.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad + FanHintLift),
            )
        }
    }

    Row(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 交互层不挂 graphicsLayer：图层变换会连带把指针坐标反向旋转，
        // 那样上滑 / 左滑的命中圈会跟着转 45°，两个方向就都判不中了。旋转只给下面的视觉层。
        Box(
            modifier = Modifier
                .size(FAB_BOX_DP.dp)
                .semantics {
                    contentDescription = "记一笔"
                    role = Role.Button
                }
                .pointerInput(onClick, onFanEvent) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var hit: TallyFanEvent? = null
                        var voiceStarted = false
                        var released = false
                        pressing = true
                        while (!released) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (fan) {
                                // 命中只看臂中心 40dp 内；滑回 ＋ 原位置即 null = 取消。
                                val next = armAt(change.position)
                                if (next == TallyFanEvent.VoiceStart && !voiceStarted) {
                                    voiceStarted = true
                                    onFanEvent(TallyFanEvent.VoiceStart)
                                }
                                hit = next
                                armed = next
                                event.changes.forEach { it.consume() }
                            }
                            if (!change.pressed) released = true
                        }
                        pressing = false
                        val opened = fan
                        fan = false
                        armed = null
                        if (!released) {
                            if (voiceStarted) onFanEvent(TallyFanEvent.VoiceCancel)
                            return@awaitEachGesture
                        }
                        if (!opened) {
                            haptics.tap()
                            onClick()
                        } else {
                            if (voiceStarted && hit != TallyFanEvent.VoiceStart) {
                                onFanEvent(TallyFanEvent.VoiceCancel)
                            }
                            if (hit != null) haptics.confirm()
                            when (hit) {
                                TallyFanEvent.VoiceStart -> onFanEvent(TallyFanEvent.VoiceFinish)
                                TallyFanEvent.Screenshot -> onFanEvent(TallyFanEvent.Screenshot)
                                // 新增一臂必须在这里也接一次：这个 when 有 else，漏分支不报错，
                                // 只会让那条臂看起来完全没反应（右滑进小莉就是这么"失灵"的）。
                                TallyFanEvent.Xiaoli -> onFanEvent(TallyFanEvent.Xiaoli)
                                else -> Unit
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // shadow 默认 clip = true，会把后面画在 56dp 外沿的波纹连圈裁掉；形状由下面的 clip 管。
                    .shadow(12.dp, shape, clip = false, ambientColor = colors.brand, spotColor = colors.brand)
                    .graphicsLayer {
                        rotationZ = fabRotation
                        scaleX = fabPop.value
                        scaleY = fabPop.value
                    }
                    // 光晕画在 clip 之前，否则那一圈外沿会被盒子切掉。
                    .drawBehind {
                        when {
                            fan -> drawFanHalo(ringPhase.floatValue, reduced, colors.paper)
                            guide -> drawGuideRings(guidePhase.floatValue, reduced, colors.paper)
                        }
                    }
                    .clip(shape)
                    .background(Brush.linearGradient(gradient), shape),
                contentAlignment = Alignment.Center,
            ) {
                InlineIcon(iconKey = "plus", contentDescription = null, size = 26.dp, tint = colors.onBrand)
            }
        }
    }
}

/** 指尖落在哪一臂的命中圈（半径 40dp）内；三臂都不在就是取消。坐标以 ＋ 自身为原点，所以能直接算。 */
private fun PointerInputScope.armAt(position: Offset): TallyFanEvent? {
    val center = FAB_BOX_DP.dp.toPx() / 2f
    val reach = FanArmReach.toPx()
    val radius = FanArmHitRadius.toPx()
    val inside: (Offset) -> Boolean = { arm ->
        val dx = position.x - arm.x
        val dy = position.y - arm.y
        dx * dx + dy * dy <= radius * radius
    }
    return when {
        inside(Offset(center, center - reach)) -> TallyFanEvent.VoiceStart
        inside(Offset(center - reach, center)) -> TallyFanEvent.Screenshot
        inside(Offset(center + reach, center)) -> TallyFanEvent.Xiaoli
        else -> null
    }
}

/**
 * 一臂：起势时从 ＋ 缩放滑出，上臂先、左臂晚 70ms；未命中的一臂压到 40%，命中那条才实体化并填墨绿。
 * 上臂沿用板上的「标签在左、气泡在右」；左臂改成标签压在气泡上方——＋ 居中后
 * 左臂气泡已在屏幕左半，标签再往左伸，窄屏上就会被左边裁掉。
 */
@Composable
private fun FanArm(
    iconKey: String,
    label: String,
    hint: String,
    hot: Boolean,
    ghost: Boolean,
    up: Boolean,
    stacked: Boolean,
    enterDelayMillis: Int,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    // 两臂只在 fan 为 true 时才进组合，所以这一帧就是「起势的那一次」。
    LaunchedEffect(Unit) {
        if (!reduced) {
            delay(enterDelayMillis.toLong())
            appear.animateTo(1f, tween(FanPopMillis, easing = FanPopEasing))
        }
    }
    val enter = Modifier.graphicsLayer {
        val t = appear.value
        // 弹性曲线会过冲（t 能 >1）：透明度钳一下，缩放与位移留着那个回弹。
        alpha = (if (ghost) GhostAlpha else 1f) * t.coerceIn(0f, 1f)
        scaleX = 0.62f + 0.38f * t
        scaleY = 0.62f + 0.38f * t
        val slide = (1f - t) * FanArmEnter.toPx()
        if (up) translationY = slide else translationX = slide
    }
    if (stacked) {
        Column(
            modifier = modifier.then(enter),
            // 右端对齐气泡：命中圈是按 arm 的右边缘算的，标签比气泡宽时居中会把气泡往左顶出命中区。
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FanArmLabel(label = label, hint = hint, hot = hot, centered = true)
            FanArmIconTile(iconKey = iconKey, label = label, hot = hot)
        }
    } else {
        Row(
            modifier = modifier.then(enter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FanArmLabel(label = label, hint = hint, hot = hot, centered = false)
            FanArmIconTile(iconKey = iconKey, label = label, hot = hot)
        }
    }
}

@Composable
private fun FanArmLabel(label: String, hint: String, hot: Boolean, centered: Boolean) {
    val colors = tallyColors
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.End) {
        Text(
            text = label,
            fontSize = 12.5.sp, lineHeight = 15.6.sp,
            fontWeight = FontWeight.Bold,
            color = if (hot) colors.goldHi else colors.paper.copy(alpha = 0.94f),
            maxLines = 1,
        )
        Text(text = hint, fontSize = 10.sp, lineHeight = 12.5.sp, color = colors.paper.copy(alpha = 0.62f), maxLines = 1)
    }
}

@Composable
private fun FanArmIconTile(iconKey: String, label: String, hot: Boolean) {
    val colors = tallyColors
    val bubbleShape = RoundedCornerShape(FabCorner)
    Box(
        modifier = Modifier
            .size(FanArmBubble)
            .graphicsLayer {
                if (hot) {
                    scaleX = 1.14f
                    scaleY = 1.14f
                }
            }
            .shadow(10.dp, bubbleShape, ambientColor = colors.ink, spotColor = colors.ink)
            .clip(bubbleShape)
            .background(if (hot) colors.brand else colors.card)
            .border(1.dp, if (hot) colors.brand else colors.line, bubbleShape),
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(iconKey = iconKey, contentDescription = label, size = 22.dp, tint = if (hot) colors.onBrand else colors.ink)
    }
}

/**
 * 方向拖尾：＋ 与臂气泡之间那条走廊（FanCorridor，现在 64dp）里，三个箭头沿上 / 左循环行军
 * （1.2s，错峰 0.38s）。
 * 不靠读字也能看懂该往哪儿滑。减少动态时不跑循环，停在均分的三个位置。
 */
@Composable
private fun FanTrail(
    up: Boolean,
    phase: MutableFloatState,
    reduced: Boolean,
    tint: Color,
    /** 横向拖尾默认指向左（←）；右臂要指右，翻 180° 并把行程反向。 */
    flip: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        repeat(TrailChevrons) { index ->
            val stagger = TrailStaggerMillis * index / TrailMillis.toFloat()
            Canvas(
                modifier = Modifier
                    .size(
                        width = if (up) TrailChevron else TrailChevronThin,
                        height = if (up) TrailChevronThin else TrailChevron,
                    )
                    .graphicsLayer {
                        val t = if (reduced) {
                            (index + 1f) / (TrailChevrons + 1f)
                        } else {
                            ((phase.floatValue - stagger) % 1f + 1f) % 1f
                        }
                        // 从 ＋ 那一侧出发（+TrailNear）滑向臂气泡（-TrailFar）。
                        val travel = (TrailNear - (TrailNear + TrailFar) * t).toPx()
                        alpha = if (reduced) {
                            0.5f
                        } else {
                            val fade = if (t < TrailFadeIn) t / TrailFadeIn else (1f - t) / (1f - TrailFadeIn)
                            TrailPeakAlpha * fade.coerceIn(0f, 1f)
                        }
                        if (up) translationY = travel else {
                            translationX = if (flip) -travel else travel
                            rotationZ = if (flip) 180f else 0f
                        }
                    },
            ) {
                val path = Path().apply {
                    if (up) {
                        moveTo(0f, size.height)
                        lineTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height)
                    } else {
                        moveTo(size.width, 0f)
                        lineTo(0f, size.height / 2f)
                        lineTo(size.width, size.height)
                    }
                }
                drawPath(path, tint, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * 引导光晕：三圈从 ＋ 外沿依次散开，淡下去再从头来，像往水里丢了一颗石子。
 * 扩展止于 30dp——再高就碰到引导卡的尾巴了（卡底在 98dp，尾巴尖 92dp）。
 */
private fun DrawScope.drawGuideRings(phase: Float, reduced: Boolean, color: Color) {
    if (reduced) {
        drawGuideRing(color.copy(alpha = 0.34f), 9.dp.toPx())
        return
    }
    repeat(3) { index ->
        val p = (phase + index / 3f) % 1f
        val eased = 1f - (1f - p) * (1f - p)
        drawGuideRing(color.copy(alpha = 0.58f * (1f - p)), 3.dp.toPx() + 27.dp.toPx() * eased)
    }
}

private fun DrawScope.drawGuideRing(color: Color, grow: Float) {
    drawRoundRect(
        color = color,
        topLeft = Offset(-grow, -grow),
        size = Size(size.width + grow * 2f, size.height + grow * 2f),
        cornerRadius = CornerRadius(FabCorner.toPx() + grow),
        style = Stroke(width = 1.6.dp.toPx()),
    )
}

/** 光晕：起势期间从 ＋ 外沿呼出并消散（1.5s 一轮），就是「按住了，还有东西要出来」那句确认。 */
private fun DrawScope.drawFanHalo(phase: Float, reduced: Boolean, color: Color) {
    val inset = 7.dp.toPx()
    val progress = if (reduced) 0f else (phase / RingFade).coerceIn(0f, 1f)
    val scale = if (reduced) 1f else 0.82f + 0.34f * progress
    val w = (size.width + inset * 2f) * scale
    val h = (size.height + inset * 2f) * scale
    drawRoundRect(
        color = color.copy(alpha = if (reduced) 0.34f else 0.62f * (1f - progress)),
        topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius((FabCorner + 7.dp).toPx() * scale),
        style = Stroke(width = 1.4.dp.toPx()),
    )
}

/**
 * 系统把「动画时长缩放」调到 0（或开了减少动态）：起势只留静态终态，
 * 扇区直接出现、拖尾常显三点，命中与手势完全不变。
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * 侧抽屉：品牌头 + 当前账本 + 管理与系统入口。没有关闭按钮——点遮罩或左滑收起，
 * 底部只留一句「统计在哪里」的口径说明。
 * 记账类入口（记一笔 / 语音 / 截图）不再进抽屉，统一收在首页 ＋ 长按的放射菜单里。
 */
@Composable
fun TallyDrawer(
    ledgerName: String,
    ledgerIconKey: String?,
    ledgerBillCount: Int,
    categoryBadge: String?,
    ledgerCount: Int,
    /** 报销开关（设置 → 记账偏好）：关掉时抽屉里不留这个入口，和记账页那枚 chip 一起消失。 */
    showReimburse: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val colors = tallyColors
    ModalDrawerSheet(
        drawerContainerColor = colors.card,
        drawerShape = RectangleShape,
        modifier = Modifier.width(268.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // ModalDrawerSheet 自己已经按系统栏留过内边距，这里再 statusBarsPadding 就是两段状态栏高度。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 13.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 抽屉招牌直接用桌面图标那一套：墨绿实底板 + 金晕茉莉，不再画一枚透明底的图形。
                // 前景那张 108 画布外圈 18 是自适应图标的留白，启动器显示时会裁掉它——
                // 这里按同样的比例放大 1.5 倍再让底板裁掉，茉莉才和桌面上的图标一样大。
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorResource(id = R.color.ic_launcher_background)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Tally 塔莉",
                        modifier = Modifier.requiredSize(60.dp),
                    )
                }
                Column {
                    Text("Tally 塔莉", style = TallyType.listTitle, color = colors.ink)
                    Text("小莉帮你管好每一笔收支", fontSize = 11.5.sp, lineHeight = 14.4.sp, color = colors.ink3)
                }
            }
            Spacer(Modifier.height(12.dp))
            // 当前账本换成一块与「管理」入口同一套画法的卡片行：胶囊太小、太圆，看着像个标签而不是「我现在在哪儿」（2026-10-28 走查）。
            val haptics = rememberTallyHaptics()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.brandSurface)
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                    .clickable {
                        haptics.tap()
                        onNavigate("ledgers")
                    }
                    // 与下面的入口行统一：点击区给到 54dp，别只有薄薄一条。
                    .heightIn(min = 54.dp)
                    .padding(start = 11.dp, end = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                InlineIcon(
                    iconKey = ledgerIconKey ?: "ledger",
                    contentDescription = null,
                    size = 19.dp,
                    tint = colors.brand,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ledgerName,
                        fontSize = 14.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                        maxLines = 1,
                    )
                    Text(
                        text = if (ledgerBillCount > 0) "$ledgerBillCount 笔账单" else "还没有账单",
                        fontSize = 11.5.sp,
                        lineHeight = 14.5.sp,
                        color = colors.ink3,
                        maxLines = 1,
                    )
                }
                Text("切换", fontSize = 12.sp, lineHeight = 15.sp, color = colors.brand)
                InlineIcon(iconKey = "chevron", contentDescription = "我的账本", size = 13.dp, tint = colors.brand)
            }
            DrawerGroup("管理")
            DrawerEntry("ledger", "我的账本", "ledgers", onNavigate, trailing = "$ledgerCount")
            DrawerEntry("tag", "分类管理", "categories", onNavigate, trailing = categoryBadge)
            DrawerEntry("coin", "预算管理", "budget", onNavigate)
            // 报销关掉就连入口一起收：它和记账页那枚 chip 共用一个开关，
            // 不能出现「抽屉里还进得去、记账页却没有这回事」。
            if (showReimburse) {
                DrawerEntry("receipt", "报销管理", "reimbursement", onNavigate)
            }
            DrawerEntry("repeat", "周期记账", "recurring", onNavigate)
            DrawerEntry("slice", "分期管理", "installments", onNavigate)
            DrawerGroup("系统")
            DrawerEntry("settings", "设置 · 关于", "settings", onNavigate)
        }
    }
}

/**
 * 分组标题。原来 10.5sp，大屏上小得几乎读不出层级（2026-11-09 走查），提到 12sp，
 * 上下留白一并给足，跟放大的入口行成一套。
 */
@Composable
private fun DrawerGroup(label: String) {
    Text(
        text = label,
        style = TallyType.eyebrow,
        fontSize = 12.sp, lineHeight = 15.sp,
        color = tallyColors.ink3,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 2.dp),
    )
}

/**
 * 抽屉入口行。点击区抬到 48dp：原来是 8dp 上下内边距配 16.9sp 行高，整行只有 33dp 高，
 * 指头常落在两行之间（2026-11-09 走查）。高度用 min height 撑，不靠再加内边距，
 * 图标、标题、右侧数字同步放大一档，整行才不会显得下重上轻。
 */
@Composable
private fun DrawerEntry(
    iconKey: String,
    label: String,
    route: String?,
    onNavigate: (String) -> Unit,
    trailing: String? = null,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(enabled = route != null) {
                haptics.tap()
                route?.let(onNavigate)
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InlineIcon(iconKey = iconKey, contentDescription = label, size = 17.dp, tint = if (route == null) colors.ink3 else colors.ink2)
        Text(
            text = label,
            fontSize = 14.5.sp, lineHeight = 18.sp,
            color = if (route == null) colors.ink3 else colors.ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (trailing != null) Text(trailing, fontSize = 12.sp, lineHeight = 15.sp, color = colors.ink3, maxLines = 1)
    }
}

/**
 * 顶栏按设计板 .ab 自绘：40px 高的单行，标题衬线 16.5px，
 * Material 的 TopAppBar 固定 64dp，比板上高出一截，所以不用它。
 */
@Composable
fun TallyTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 外壳不再吃状态栏，顶栏自己让开：这样抽屉拉开时能一直铺到屏幕顶。
            .statusBarsPadding()
            .then(if (subtitle == null) Modifier.height(44.dp) else Modifier.padding(vertical = 7.dp))
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        when {
            onBack != null -> TallyIconAction(iconKey = "back", contentDescription = "返回", onClick = onBack)
            leading != null -> leading()
        }
        Column(modifier = Modifier.weight(1f)) {
            if (onTitleClick != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            haptics.tap()
                            onTitleClick()
                        },
                ) {
                    Text(title, style = TallyType.screenTitle, color = colors.ink, maxLines = 1)
                    InlineIcon(iconKey = "chevron_down", contentDescription = "切换", size = 12.dp, tint = colors.ink3)
                }
            } else {
                Text(title, style = TallyType.screenTitle, color = colors.ink, maxLines = 1)
            }
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.5.sp, lineHeight = 14.4.sp, color = colors.ink3, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

/** 顶栏方形图标按钮，对应设计板的 .iB：29dp、9dp 圆角、14px 线性图标。 */
@Composable
fun TallyIconAction(
    iconKey: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(TallyIconTileShape)
            .background(if (selected) colors.brand else colors.card)
            .border(1.dp, if (selected) colors.brand else colors.line, TallyIconTileShape)
            .clickable {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(
            iconKey = iconKey,
            contentDescription = contentDescription,
            size = 16.dp,
            tint = if (selected) colors.onBrand else colors.ink,
        )
    }
}


