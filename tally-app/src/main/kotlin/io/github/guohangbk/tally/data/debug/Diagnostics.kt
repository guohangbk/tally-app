package io.github.guohangbk.tally.data.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.guohangbk.tally.data.storage.DownloadedFile
import io.github.guohangbk.tally.data.storage.PublicDownloads
import io.github.guohangbk.tally.di.AppGraph
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「设置 → 导出诊断包」。
 *
 * 只装排障要看的事实：包与库的版本、机型与 ABI、非敏感开关、各表行数、日志尾巴。
 * 一条账单内容都不进——金额、备注、分类名、账本名、图片、API Key、网关地址全部排除。
 * 行数是唯一带出的"数据量"，它只说明有多少条，不含任何一条的内容。
 */
object Diagnostics {

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun fileName(atMillis: Long = System.currentTimeMillis()): String =
        "tally_diagnostics_${stampFormat.format(Date(atMillis))}.txt"

    suspend fun export(context: Context, graph: AppGraph): DownloadedFile =
        PublicDownloads.write(context, fileName(), "text/plain", build(context, graph))

    @Suppress("DEPRECATION")
    suspend fun build(context: Context, graph: AppGraph): ByteArray {
        val dao = graph.database.diagnosticDao()
        val settings = runCatching { graph.preferences.settings.first() }.getOrNull()
        val startDay = runCatching { graph.settingRepository.monthStartDay.first() }.getOrNull()
        val ai = runCatching { dao.aiHealth() }.getOrNull()
        val rows = runCatching { dao.tableRows() }.getOrDefault(emptyList())
        val db = graph.database.openHelper.readableDatabase
        val userVersion = rawFirst(db, "PRAGMA user_version")
        val journal = rawFirst(db, "PRAGMA journal_mode")
        val hash = rawFirst(db, "SELECT identity_hash FROM room_master_table WHERE id = 42")
        val fileLines = DebugLog.fileTail()
        val crashFiles = DebugLog.crashRecords()
        val logcatSelf = LogcatTail.currentProcess()
        val logcatCrash = LogcatTail.crashBuffer()

        return buildString {
            append("Tally 塔莉 诊断包\n")
            append("生成时间 ").append(timeFormat.format(Date())).append('\n')
            append("内容边界：不含账单金额、备注、分类名、账本名与图片；不含 API Key 与网关地址。\n")
            append("          行数只表示各表有多少条记录，不含任何一条的内容。\n\n")

            append("[app]\n")
            append("versionName = ").append(versionName(context)).append('\n')
            append("versionCode = ").append(versionCode(context)).append('\n')
            append("buildType   = ").append(if (debuggable(context)) "debug" else "release").append('\n')

            append("\n[db]\n")
            append("user_version  = ").append(userVersion ?: "读不到").append('\n')
            append("identity_hash = ").append(hash?.take(8) ?: "读不到").append('\n')
            append("journal_mode  = ").append(journal ?: "读不到").append('\n')
            // 库与 WAL 的字节数：WAL 明显大于主库、或它比主库还"新"，就是没 checkpoint 的信号。
            val dbFile = context.getDatabasePath(io.github.guohangbk.tally.data.db.TallyDatabase.NAME)
            val walFile = java.io.File(dbFile.path + "-wal")
            append("db_bytes      = ").append(if (dbFile.exists()) dbFile.length() else 0).append('\n')
            append("wal_bytes     = ").append(if (walFile.exists()) walFile.length() else 0).append('\n')
            append("free_mb       = ").append(
                runCatching {
                    android.os.StatFs(dbFile.absoluteFile.parent).availableBytes / (1024 * 1024)
                }.getOrDefault(-1L),
            ).append('\n')
            append("说明：App 能打开即代表 user_version 与 identity_hash 都等于安装包编译期那份；\n")
            append("      启动就崩的话，这两行通常已经留着设备库与包不一致的证据。\n")

            append("\n[dev]\n")
            append("brand   = ").append(Build.BRAND).append('\n')
            append("model   = ").append(Build.MODEL).append('\n')
            append("device  = ").append(Build.DEVICE).append('\n')
            append("android = ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("firmware= ").append(Build.DISPLAY).append('\n')
            append("abis    = ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
            append("locale  = ").append(Locale.getDefault()).append('\n')
            append("zone    = ").append(java.util.TimeZone.getDefault().id).append('\n')

            append("\n[set]\n")
            if (settings == null) {
                append("偏好读不到\n")
            } else {
                append("theme           = ").append(settings.themeMode).append('\n')
                append("month_start_day = ").append(startDay?.toString() ?: "-").append('\n')
                append("time_precision  = ").append(settings.timePrecision).append('\n')
                append("reimburse       = ").append(settings.reimburseEnabled).append('\n')
                append("bill_image      = ").append(settings.billImageEnabled).append('\n')
                append("tag             = ").append(settings.tagEnabled).append('\n')
                append("assistant       = ").append(settings.assistantEnabled).append('\n')
                append("currency_symbol = ").append(settings.showCurrencySymbol).append('\n')
                append("thousands_sep   = ").append(settings.showThousandsSeparator).append('\n')
                append("debug_mode      = ").append(settings.debugMode).append('\n')
                append("ai_target       = ").append(ai?.let { "${it.provider} / ${it.model}" } ?: "未配置").append('\n')
            }

            append("\n[row]\n")
            if (rows.isEmpty()) {
                append("行数读不到\n")
            } else {
                rows.forEach { append(it.name.padEnd(22)).append(it.rowCount).append('\n') }
            }

            append("\n[ai]\n")
            if (ai == null) {
                append("未配置激活的模型\n")
            } else {
                append("target      = ").append(ai.provider).append(" / ").append(ai.model).append('\n')
                append("last_error  = ").append(ai.lastError ?: "无").append('\n')
                append("fails_now   = ").append(ai.consecutiveFailures).append('\n')
            }

            // 四段日志各有来源，混在一起就没法判断哪条可信，所以分开标。
            append("\n[trace] 应用内打点（文件尾巴，非系统日志）\n")
            if (fileLines.isEmpty()) {
                append("文件日志为空或还没接上目录。\n")
            } else {
                fileLines.forEach { append(it).append('\n') }
            }

            append("\n[crash] 本应用留档的崩溃\n")
            if (crashFiles.isEmpty()) {
                append("没有留档——自上次装包以来没有被全局异常处理器捕获过的崩溃。\n")
            } else {
                crashFiles.forEach { file ->
                    append("--- ").append(file.name).append(" ---\n")
                    append(DebugLog.crashText(file)).append('\n')
                }
            }

            append("\n[logcat] 系统日志（本进程，尽力而为，已过脱敏）\n")
            if (logcatCrash.isNotEmpty()) {
                append("### crash buffer\n")
                logcatCrash.forEach { append(it).append('\n') }
            }
            if (logcatSelf.isNotEmpty()) {
                append("### 本进程\n")
                logcatSelf.forEach { append(it).append('\n') }
            }
            if (logcatCrash.isEmpty() && logcatSelf.isEmpty()) {
                append("读不到——Android 7 之后只能读本 UID，部分厂商 ROM 连自己的也返回空。\n")
                append("      这一项缺失不影响上面各段有效。\n")
            }
        }.toByteArray()
    }

    /** Room 解析不了 PRAGMA，`room_master_table` 也是运行时才建的，这三项只能走裸连接。 */
    private fun rawFirst(db: SupportSQLiteDatabase, sql: String): String? = runCatching {
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun versionName(context: Context): String = info(context)?.versionName ?: "-"

    private fun versionCode(context: Context): String {
        val info = info(context) ?: return "-"
        return if (Build.VERSION.SDK_INT >= 33) info.longVersionCode.toString() else info.versionCode.toString()
    }

    private fun info(context: Context): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    private fun debuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
