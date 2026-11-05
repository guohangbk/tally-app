package io.github.guohangbk.tally.data.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 应用的日志出口：内存环给页面上的控制台看，文件给「导出诊断包」带走，崩溃单独留档。
 *
 * 为什么要落文件：内存环随进程一起没。真崩溃时用户能做的只有重开 App，
 * 那一刻上一次为什么死已经查不到了——除非死之前同步写进过磁盘。
 *
 * 为什么全项目只有这一个出口：日志的隐私边界只能在写日志这一层守。
 * 只要这里不接收账单内容，导出时就不需要猜哪行带了金额或备注。
 */
object DebugLog {

    private const val RING_LINES = 300
    private const val FILE_CAP_BYTES = 512 * 1024
    private const val CRASH_KEEP = 5
    private const val LOG_NAME = "tally.log"
    private const val LOG_ROLLED = "tally.log.1"
    private const val CRASH_PREFIX = "crash-"

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val fileClock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val stampClock = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = mutable.asStateFlow()

    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AppLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Volatile
    private var dir: File? = null

    /** [attach] 之前写的行先攒着，接上目录一次补落盘。 */
    private val pending = ArrayList<String>()

    /**
     * 由 [TallyApplication] 在 `onCreate` 最开头调用。
     * 超上限就把当前文件滚成 `.1`（旧的 `.1` 直接丢），只保留两份，磁盘上限 1 MB。
     */
    fun attach(storage: File) {
        runCatching {
            if (!storage.exists()) storage.mkdirs()
            val current = File(storage, LOG_NAME)
            if (current.length() > FILE_CAP_BYTES) {
                File(storage, LOG_ROLLED).delete()
                current.renameTo(File(storage, LOG_ROLLED))
            }
            dir = storage
            pending.forEach { appendToFile(it) }
            pending.clear()
        }
    }

    fun log(tag: String, message: String) = emit("I", tag, message)

    /** 错误也走同一条路：级别标 E，栈由调用方决定带不带，避免每处自己拼字符串。 */
    fun error(tag: String, message: String, cause: Throwable? = null) {
        emit("E", tag, if (cause == null) message else "$message :: ${flatten(cause)}")
    }

    private fun emit(level: String, tag: String, message: String) {
        val now = Date()
        val line = "${clock.format(now)} [$level/$tag] $message"
        mutable.update { previous -> (previous + line).takeLast(RING_LINES) }
        val text = "${fileClock.format(now)} [$level/$tag] $message"
        val storage = dir
        if (storage == null) {
            synchronized(pending) { if (pending.size < RING_LINES) pending.add(text) }
        } else {
            io.execute { appendToFile(text) }
        }
    }

    private fun appendToFile(text: String) {
        val storage = dir ?: return
        runCatching {
            File(storage, LOG_NAME).appendText("$text\n")
        }
    }

    /** 崩溃记录：必须同步写，进程下一秒就没了，排队等于什么都没留下。 */
    fun recordCrash(thread: Thread, cause: Throwable) {
        val storage = dir ?: return
        runCatching {
            storage.mkdirs()
            val newest = File(storage, "$CRASH_PREFIX${stampClock.format(Date())}.log")
            newest.writeText(
                buildString {
                    append("crash at ").append(fileClock.format(Date())).append('\n')
                    append("thread   = ").append(thread.name).append('\n')
                    append("version  = ").append(CrashInfo.version).append('\n')
                    append("---\n")
                    append(stackOf(cause))
                },
            )
            // 同一秒内重复崩溃会撞名，撞了就加后缀；无论如何只留最近 [CRASH_KEEP] 份。
            pruneOldest(storage, CRASH_PREFIX, CRASH_KEEP)
        }
    }

    /** 文件日志尾巴，新在后。读不到就返回空，不抛——诊断包不能因为读日志再崩一次。 */
    fun fileTail(maxLines: Int = 400): List<String> {
        val storage = dir ?: return emptyList()
        return runCatching {
            val rolled = File(storage, LOG_ROLLED).readLinesOrEmpty()
            val current = File(storage, LOG_NAME).readLinesOrEmpty()
            (rolled + current).takeLast(maxLines)
        }.getOrDefault(emptyList())
    }

    fun crashRecords(): List<File> {
        val storage = dir ?: return emptyList()
        return runCatching {
            storage.listFiles { file -> file.name.startsWith(CRASH_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(3)
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun crashText(file: File, maxLines: Int = 60): String = runCatching {
        file.readLines().take(maxLines).joinToString("\n")
    }.getOrDefault("(读不到)")

    fun clear() = mutable.update { emptyList() }

    /** 分享用：整段文本，带设备与版本信息，方便对号入座。 */
    fun dump(header: String): String = buildString {
        appendLine(header)
        appendLine("---")
        mutable.value.forEach { appendLine(it) }
    }

    private fun pruneOldest(storage: File, prefix: String, keep: Int) {
        storage.listFiles { file -> file.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    private fun File.readLinesOrEmpty(): List<String> = if (exists()) readLines() else emptyList()

    private fun stackOf(cause: Throwable?): String = buildString {
        var cursor = cause
        var depth = 0
        while (cursor != null && depth < 6) {
            append(cursor.javaClass.name).append(": ").append(cursor.message).append('\n')
            cursor.stackTrace.take(24).forEach { append("\tat ").append(it).append('\n') }
            cursor = cursor.cause
            depth++
        }
    }

    private fun flatten(cause: Throwable): String =
        "${cause.javaClass.simpleName}: ${cause.message} @ ${cause.stackTrace.firstOrNull()?.toString().orEmpty()}"
}

/** 崩溃记录里想带上包版本，但 `DebugLog` 不该依赖 `Context`，所以启动时注入一次。 */
object CrashInfo {
    @Volatile
    var version: String = "-"
}
