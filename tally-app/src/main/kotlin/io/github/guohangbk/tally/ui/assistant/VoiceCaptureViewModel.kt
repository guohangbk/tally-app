package io.github.guohangbk.tally.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.speech.SpeechOutcome
import io.github.guohangbk.tally.di.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 录音上限：超过自动结束并进入识别，不静默截断（V-03）。 */
const val VoiceMaxMillis = 20_000L

/**
 * 一次「按住说」的产出。
 *
 * 只管「有没有拿到话」：金额、分类、日期都由管线从 [heard] 里抽，收音这一层
 * 不该有第二套解析。原文也只用于失败提示与「再说一次」。
 *
 * [failure] 非空表示没拿到可记账的内容，页面必须显式说清原因并给出路，
 * 不许静默回到首页（V-07 / AC-V-7）。
 */
data class VoiceCapture(
    val heard: String = "",
    val cancelled: Boolean = false,
    val failure: String? = null,
    /**
     * 失败只是「没听清」这一档：再来一次或改成打字就行，不必占一层弹层。
     * 要给设置入口的（权限、引擎起不来）不看这个标记，页面照样弹层。
     */
    val heardNothing: Boolean = false,
    /** 本机引擎起不来，页面应改走系统语音输入界面。 */
    val needsSystemInput: Boolean = false,
)

/**
 * 「按住说话」的收音器：按下开始、松手结束，识别结果从回调出去。
 * 不抽字段、不落库、不留草稿——收尾交给调用方（首页丢给素材卡，小莉丢给对话）。
 */
class VoiceCaptureViewModel(private val graph: AppGraph) : ViewModel() {
    private var listening = false
    private var onResult: ((VoiceCapture) -> Unit)? = null

    /** 按住开始收音：按下调这里，松手调 [endCapture]；结果仍从这个协程回来。 */
    fun beginCapture(
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit = {},
        onResult: (VoiceCapture) -> Unit,
    ) {
        if (listening) return
        listening = true
        this.onResult = onResult
        viewModelScope.launch {
            val outcome = try {
                graph.speechPort.listen(onLevel, onPartial)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                SpeechOutcome.NothingHeard("没有听清，请重试或改用文字输入")
            }
            listening = false
            val callback = this@VoiceCaptureViewModel.onResult
            this@VoiceCaptureViewModel.onResult = null
            callback?.invoke(outcome.toCapture())
        }
    }

    /** 松手：立刻让引擎收尾，不等它自己判断静音。 */
    fun endCapture() {
        if (listening) graph.speechPort.stopListening()
    }

    /** 手势被中途打断：丢掉这一次，不要事后弹结果。 */
    fun abandonCapture() {
        onResult = null
        if (listening) graph.speechPort.stopListening()
        listening = false
    }

    /**
     * 系统语音输入界面返回的文字：与端侧识别同一个出口，
     * 只是音频是在系统应用里录的（小米这类 ROM 上这是唯一能用的路）。
     */
    fun acceptHeard(text: String, onResult: (VoiceCapture) -> Unit) {
        val trimmed = text.trim()
        onResult(
            if (trimmed.isEmpty()) {
                VoiceCapture(failure = "没有听清，请重试或输入文字", heardNothing = true)
            } else {
                VoiceCapture(heard = trimmed)
            },
        )
    }
}

private fun SpeechOutcome.toCapture(): VoiceCapture = when (this) {
    is SpeechOutcome.Heard -> VoiceCapture(heard = text)
    is SpeechOutcome.NothingHeard -> VoiceCapture(failure = reason, heardNothing = true)
    is SpeechOutcome.Unavailable -> VoiceCapture(failure = reason)
    is SpeechOutcome.NeedsSystemInput -> VoiceCapture(failure = reason, needsSystemInput = true)
    SpeechOutcome.Cancelled -> VoiceCapture(cancelled = true)
}
