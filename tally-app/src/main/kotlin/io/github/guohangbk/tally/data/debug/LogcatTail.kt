package io.github.guohangbk.tally.data.debug

import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * 尽力而为地读系统日志。Android 7 之后第三方 App 只读得到自己 UID 的输出，
 * 所以这里拿到的就是我们进程写进 logcat 的那些——包括我们没往 [DebugLog] 里写的
 * 框架与三方库日志（Room/SQLite、WorkManager、Coil、协程默认异常处理器）。
 *
 * 厂商 ROM 可能直接返回空，因此调用方必须能接受"读不到"，不能把它当可靠来源。
 *
 * 这一段必须过脱敏：logcat 不归我们管，SQL 与绑定值、图片 URI 都可能出现在里面。
 * 诊断包承诺了不含账单内容，所以宁可少给几行。
 */
object LogcatTail {

    private const val MAX_LINE_CHARS = 240
    private const val TIMEOUT_SECONDS = 4L

    private val RED_FLAGS = Regex(
        "INSERT |REPLACE INTO|UPDATE \\S+ SET|SELECT .+ FROM|" +
            "/storage/|/sdcard|content://|file://|\\.(jpg|jpeg|png|heic|webp)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 白名单，不是黑名单。我们自己的代码从不走 `android.util.Log`（全进 [DebugLog]），
     * 所以 logcat 里唯一有价值的是框架与三方库的话：崩溃栈、SQL 报错、WorkManager、Coil。
     *
     * 反例很有说服力：按"本进程全收"抓一次，300 行里约 150 行是 MIUI 的
     * `InsetsSource`，其余是 `MIUIInput`/`HWUI`/`BLASTBufferQueue`——全是桌面渲染与触摸噪声，
     * 把真正那两条 SQLiteLog 埋掉了。所以宁可少收。
     */
    private val KEEP_TAGS = Regex(
        "^(AndroidRuntime|System\\.err|StrictMode|" +
            "SQLiteLog|SQLiteDatabase|CursorWindow|DefaultDatabase|" +
            "WM-\\S+|WorkManager\\S*|WorkContext|" +
            "coil\\S*|OkHttp\\S*|EGL\\S*|vulkan|Adreno\\S*|" +
            "OpenGLRenderer)$" +
            "|guohangbk\\.tally$",
    )

    /** threadtime 形如 `11-02 23:05:43.606  6580  6580 I MIUIInput: [MotionEvent] ...`。 */
    private val LEVEL = Regex("""^\d\d-\d\d [\d:.]+ +\d+ +\d+ +([VDIWEFA]) +([^:]+):""")

    /** 只取本进程：跨进程读不到，也不该把别人的噪声塞进诊断包。 */
    fun currentProcess(maxLines: Int = 120): List<String> =
        read(listOf("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}"), maxLines)

    /** crash buffer 由崩溃进程自己写出且跨进程存活，是"上一次为什么死"的第二来源。 */
    fun crashBuffer(maxLines: Int = 120): List<String> =
        read(listOf("logcat", "-d", "-b", "crash", "-v", "threadtime", "-t", maxLines.toString()), maxLines)

    private fun read(args: List<String>, maxLines: Int): List<String> = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroy()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && worthKeeping(it) }
            .map { it.take(MAX_LINE_CHARS) }
            .toList()
            .takeLast(maxLines)
    }.getOrDefault(emptyList())

    private fun worthKeeping(line: String): Boolean {
        if (RED_FLAGS.containsMatchIn(line)) return false
        val matched = LEVEL.find(line) ?: return false
        val level = matched.groupValues[1]
        val tag = matched.groupValues[2].trim()
        // E 一律留：真出问题时级别比 tag 诚实。
        return level == "E" || KEEP_TAGS.containsMatchIn(tag)
    }
}
