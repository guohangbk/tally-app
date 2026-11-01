package io.github.guohangbk.tally.data.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志环（只存在内存里，最多 [MAX_LINES] 行）。
 *
 * 存在的理由很具体：麦克风权限这类问题在 logcat 里要连电脑才能看，
 * 而用户手上的手机不一定连得上 adb。调试模式打开后，每个页面都能调出控制台，
 * 把「申请了什么权限、系统回了什么、识别引擎报了什么」直接念出来。
 */
object DebugLog {
    private const val MAX_LINES = 300
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)

    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = mutable.asStateFlow()

    fun log(tag: String, message: String) {
        val line = "${clock.format(Date())} [$tag] $message"
        mutable.update { previous ->
            (previous + line).takeLast(MAX_LINES)
        }
    }

    fun clear() = mutable.update { emptyList() }

    /** 分享用：整段文本，带设备与版本信息，方便对号入座。 */
    fun dump(header: String): String = buildString {
        appendLine(header)
        appendLine("---")
        mutable.value.forEach { appendLine(it) }
    }
}
