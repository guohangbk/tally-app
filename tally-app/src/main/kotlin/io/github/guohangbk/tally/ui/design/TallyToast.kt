package io.github.guohangbk.tally.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全站唯一的一条提示通道。
 *
 * 以前每条提示都长在页面顶部：`state.notice` 是一行 Text，`state.error` 是一块 TallyErrorBanner，
 * 它们插在内容流里，弹出来时把整页往下顶一下——而提示说的是刚才那一下操作，
 * 却出现在离手指最远的地方。这里改成浮在内容之上、贴底居中的气泡，几秒自动收起，
 * 不占布局、不打断正在看的列表。
 *
 * ViewModel 侧照旧把 notice / error 放在 UiState 里（这是状态，不是事件，页面重建也该看得到），
 * 屏幕用 [TallyToastEffects] 把它们转成一次气泡并回调清空，好让同样的文案第二次也能再弹。
 */
enum class ToastTone {
    SUCCESS,
    ERROR,
    INFO,
}

/** [sequence] 让"同样的文案再弹一次"能被识别成一条新提示，而不是没变化的旧值。 */
data class TallyToastMessage(
    val sequence: Long,
    val text: String,
    val tone: ToastTone = ToastTone.SUCCESS,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** 带动作的气泡要留够反应时间（例如 8 秒撤销窗口），按 [durationOf] 那套默认值收得太快。 */
    val durationMillis: Long? = null,
)

object TallyToast {
    private val counter = AtomicLong(0L)
    private val mutable = MutableStateFlow<TallyToastMessage?>(null)
    val current = mutable.asStateFlow()

    /** 时长按内容量给：错误通常比成功长一句，需要更多时间读完。 */
    fun durationOf(tone: ToastTone): Long = when (tone) {
        ToastTone.ERROR -> 4_200L
        ToastTone.SUCCESS -> 2_400L
        ToastTone.INFO -> 3_000L
    }

    fun show(
        text: String,
        tone: ToastTone = ToastTone.SUCCESS,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        durationMillis: Long? = null,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        mutable.value = TallyToastMessage(
            sequence = counter.incrementAndGet(),
            text = trimmed,
            tone = tone,
            actionLabel = actionLabel,
            onAction = onAction,
            durationMillis = durationMillis,
        )
    }

    /** 只收回自己那一条：迟到的 dismiss 不能把刚弹出的新提示带走。 */
    fun dismiss(sequence: Long) {
        if (mutable.value?.sequence == sequence) mutable.value = null
    }
}

/**
 * 把 UiState 上的一次性提示转成气泡。
 *
 * [errorTitle] 保留原来横幅的定性说法（「还不能保存」「操作没有完成」），
 * 没有它的话，一句 "分类不存在" 会被读成系统坏了而不是这一笔填错了。
 * [noticeActionLabel] 给成功提示挂一个动作（撤销）——软删除那条 8 秒窗口以前长在列表底下的
 * 一条横栏里，那条横栏会把整块底部刷白、压住好几行明细。
 */
@Composable
fun TallyToastEffects(
    notice: String?,
    error: String?,
    errorTitle: String? = null,
    onNoticeShown: () -> Unit = {},
    onErrorShown: () -> Unit = {},
    errorActionLabel: String? = null,
    onErrorAction: (() -> Unit)? = null,
    noticeActionLabel: String? = null,
    onNoticeAction: (() -> Unit)? = null,
    noticeDurationMillis: Long? = null,
) {
    LaunchedEffect(notice) {
        if (!notice.isNullOrBlank()) {
            TallyToast.show(notice, ToastTone.SUCCESS, noticeActionLabel, onNoticeAction, noticeDurationMillis)
            onNoticeShown()
        }
    }
    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) {
            val text = if (errorTitle.isNullOrBlank()) error else "$errorTitle · $error"
            TallyToast.show(text, ToastTone.ERROR, errorActionLabel, onErrorAction)
            onErrorShown()
        }
    }
}

/**
 * 挂在应用根 Box 上的一份气泡宿主。它铺满整层但不消费点击，
 * 所以除了那枚气泡本身，页面各处该点还是能点。
 */
@Composable
fun TallyToastHost(contentBottomPadding: PaddingValues = PaddingValues(bottom = 96.dp, start = 20.dp, end = 20.dp)) {
    val message by TallyToast.current.collectAsStateWithLifecycle()
    // 退出动画期间 current 已经是 null，留一份最后的文案才收得出那一下淡出。
    var shown by remember { mutableStateOf<TallyToastMessage?>(null) }

    LaunchedEffect(message?.sequence) {
        val incoming = message
        if (incoming == null) return@LaunchedEffect
        shown = incoming
        delay(incoming.durationMillis ?: TallyToast.durationOf(incoming.tone))
        TallyToast.dismiss(incoming.sequence)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null && shown != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.padding(contentBottomPadding),
        ) {
            shown?.let { ToastBubble(message = it) }
        }
    }
}

@Composable
private fun ToastBubble(message: TallyToastMessage) {
    val colors = tallyColors
    val accent = when (message.tone) {
        ToastTone.ERROR -> colors.expense
        ToastTone.SUCCESS -> colors.brand
        ToastTone.INFO -> colors.gold
    }
    val iconKey = when (message.tone) {
        ToastTone.ERROR -> "warn"
        ToastTone.SUCCESS -> "check"
        ToastTone.INFO -> "info"
    }
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.card)
            .border(1.dp, accent.copy(alpha = 0.34f), shape)
            .clickable { TallyToast.dismiss(message.sequence) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InlineIcon(iconKey = iconKey, contentDescription = null, size = 15.dp, tint = accent)
        Text(
            text = message.text,
            style = TallyType.helper,
            color = colors.ink,
            textAlign = TextAlign.Start,
            maxLines = 3,
            modifier = Modifier.widthIn(max = 296.dp).weight(1f, fill = false),
        )
        // 少数错误需要就地给一个动作（重新测试、重新准备），其余靠文案自己说清楚。
        // 动作靠**色号**与正文分开（正文 ink，动作取该 tone 的强调色），不做形状：
        // 试过实心药丸，气泡本来就不大，塞一枚按钮进去整条都脏了。
        message.actionLabel?.takeIf { message.onAction != null }?.let { label ->
            Text(
                text = label,
                style = TallyType.helper,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        message.onAction?.invoke()
                        TallyToast.dismiss(message.sequence)
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}
