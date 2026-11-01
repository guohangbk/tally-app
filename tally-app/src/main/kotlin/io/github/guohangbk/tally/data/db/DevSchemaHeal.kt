package io.github.guohangbk.tally.data.db

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import io.github.guohangbk.tally.data.debug.DebugLog
import java.io.File

/**
 * Debug 构建专用：改了实体但 `version` 仍压在 1 时，让设备上的旧库自动对齐结构并保住数据。
 *
 * 为什么需要它：Room 每次打开库都拿编译期算出的 identity hash 与 `room_master_table` 里存的值比对，
 * 版本相同而 hash 不同就直接抛 `IllegalStateException`。开发期反复改 schema、又不想每次卸装清库，
 * 就会一直撞在这一条上。这里在 Room 建库之前先自检：
 *
 * 1. 读设备上旧库的 `user_version` 与 identity hash；
 * 2. 用一个临时库拿到本次编译的版本与 hash（同一份 entity 声明，建表结果必然一致）；
 * 3. 版本相同、hash 不同 → 旧库整套改名留档，临时库挪成正式库，再按「表名 + 列名交集」
 *    把旧数据逐行搬过去：新列取默认值，删掉的列自然丢弃，改名的列按「新列」处理。
 *
 * 任何一步失败都退回「旧库留档 + 空库启动」，并在调试控制台写明留档文件名；
 * 绝不让结构漂移把 App 卡死在启动。Release 构建完全不进这里：hash 对不上就照旧抛异常，
 * 不清库——`fallbackToDestructiveMigration` 永远不配。
 */
internal object DevSchemaHeal {
    private const val TAG = "SchemaHeal"
    private const val PREF_NAME = "dev_schema_heal"
    private const val PROBE_NAME = "tally-schema-probe.db"
    private const val BACKUP_MARK = ".dev-old-"

    fun prepareIfNeeded(context: Context) {
        if (!isDebuggable(context)) return
        val startedAt = System.currentTimeMillis()
        runCatching { healIfDrifted(context) }
            .onFailure { DebugLog.log(TAG, "自检中断，交给 Room 处理：${it.message}") }
        DebugLog.log(TAG, "自检耗时 +${System.currentTimeMillis() - startedAt}ms")
    }

    private fun healIfDrifted(context: Context) {
        val dbFile = context.getDatabasePath(TallyDatabase.NAME)
        if (!dbFile.exists()) return

        val stored = readHeader(dbFile) ?: run {
            DebugLog.log(TAG, "旧库读不到 room_master_table，跳过自检")
            return
        }
        // 同一个 APK 的编译结果不会变，hash 按安装时间缓存；命中就不用每次启动都建临时库。
        val cached = cachedExpected(context)
        if (cached != null) {
            if (cached.version != stored.version) {
                DebugLog.log(TAG, "库版本 ${stored.version} ≠ 编译版本 ${cached.version}，交给 Room 迁移/报错")
                return
            }
            if (cached.hash == stored.hash) return
        }

        val probe = buildProbe(context) ?: return
        var consumed = false
        try {
            if (stored.version != probe.header.version) {
                DebugLog.log(TAG, "库版本 ${stored.version} ≠ 编译版本 ${probe.header.version}，交给 Room 迁移/报错")
                return
            }
            if (stored.hash == probe.header.hash) {
                cacheExpected(context, probe.header)
                return
            }
            DebugLog.log(TAG, "结构漂移：version=${stored.version}，hash ${stored.hash} → ${probe.header.hash}")
            consumed = heal(dbFile, probe.file)
            cacheExpected(context, probe.header)
        } finally {
            if (!consumed) deleteDbFiles(probe.file)
        }
    }

    /** 返回临时库是否已经挪成正式库（true 时调用方不必再清理它）。 */
    private fun heal(dbFile: File, probeFile: File): Boolean {
        val backup = File(dbFile.parentFile, dbFile.name + BACKUP_MARK + System.currentTimeMillis())
        if (!moveSet(dbFile, backup)) {
            DebugLog.log(TAG, "旧库改名失败，放弃自愈（原文件未动）")
            return false
        }
        if (!probeFile.renameTo(dbFile)) {
            // 旧库挪回去，宁可下次照原样报错，也不留一个半成品的正式库。
            moveSet(backup, dbFile)
            deleteDbFiles(probeFile)
            error("临时库挪不进正式位置")
        }
        runCatching { copyRows(backup, dbFile) }
            .onSuccess { (tables, rows) ->
                DebugLog.log(TAG, "已换库：$tables 张表 / $rows 行；旧库留档 ${backup.name}")
                pruneBackups(dbFile)
            }
            .onFailure { error ->
                DebugLog.log(TAG, "搬数据失败，旧库留档 ${backup.name}，本次从空库启动：${error.message}")
                deleteDbFiles(dbFile)
            }
        return true
    }

    /** 把旧库的表按列名交集搬进新库；返回搬了多少张表、多少行。 */
    private fun copyRows(oldFile: File, newFile: File): Pair<Int, Long> {
        val db = SQLiteDatabase.openDatabase(newFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            // 外键在复制期间必须关掉：表按名字逐个搬，顺序不保证，父子行可能短暂失配。
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.execSQL("ATTACH DATABASE ? AS old", arrayOf(oldFile.path))
            val tables = db.rawQuery(
                "SELECT name FROM main.sqlite_master WHERE type = 'table'" +
                    " AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'",
                null,
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            var tablesCopied = 0
            var rowsCopied = 0L
            db.beginTransaction()
            try {
                tables.forEach { table ->
                    val columns = commonColumns(db, table)
                    if (columns.isEmpty()) {
                        DebugLog.log(TAG, "跳过 $table：新旧库没有可共用的列")
                        return@forEach
                    }
                    val list = columns.joinToString(", ") { quote(it) }
                    db.execSQL("INSERT OR REPLACE INTO main.${quote(table)} ($list) SELECT $list FROM old.${quote(table)}")
                    rowsCopied += DatabaseUtils.longForQuery(db, "SELECT changes()", null)
                    tablesCopied++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("DETACH DATABASE old")
            return tablesCopied to rowsCopied
        } finally {
            runCatching { db.close() }
        }
    }

    private fun commonColumns(db: SQLiteDatabase, table: String): List<String> {
        val fresh = columnsOf(db, "main", table)
        val old = columnsOf(db, "old", table)
        return fresh.filter { it in old }
    }

    private fun columnsOf(db: SQLiteDatabase, schema: String, table: String): List<String> =
        db.rawQuery("PRAGMA $schema.table_info(${quote(table)})", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private fun buildProbe(context: Context): Probe? {
        val file = context.getDatabasePath(PROBE_NAME)
        deleteDbFiles(file)
        return runCatching {
            // 回滚日志：关闭后全部内容都在主文件里，挪成正式库时不会留下独立的 -wal 陪跑。
            Room.databaseBuilder(context.applicationContext, TallyDatabase::class.java, PROBE_NAME)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .build()
                .also { database ->
                    database.openHelper.writableDatabase
                    database.close()
                }
            Probe(readHeader(file) ?: error("临时库没写出 room_master_table"), file)
        }.onFailure {
            deleteDbFiles(file)
            DebugLog.log(TAG, "建临时库失败，跳过自检：${it.message}")
        }.getOrNull()
    }

    private fun readHeader(file: File): Header? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val hash = db.rawQuery("SELECT identity_hash FROM room_master_table LIMIT 1", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            hash?.let { Header(version = db.version, hash = it) }
        }
    }.onFailure {
        DebugLog.log(TAG, "读 ${file.name} 失败：${it.message}")
    }.getOrNull()

    /** 整套改名（主文件 + WAL + SHM）；中途失败就把已挪的挪回去，不留半套。 */
    private fun moveSet(from: File, to: File): Boolean {
        val moved = mutableListOf<Pair<File, File>>()
        for (suffix in listOf("", "-wal", "-shm")) {
            val source = File(from.path + suffix)
            if (!source.exists()) continue
            val target = File(to.path + suffix)
            if (!source.renameTo(target)) {
                moved.asReversed().forEach { (src, dst) -> runCatching { dst.renameTo(src) } }
                return false
            }
            moved += source to target
        }
        return true
    }

    private fun deleteDbFiles(base: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            runCatching { File(base.path + suffix).delete() }
        }
    }

    /** 只留最近一次留档，调试设备上不至于越攒越多。 */
    private fun pruneBackups(dbFile: File) {
        val dir = dbFile.parentFile ?: return
        val prefix = dbFile.name + BACKUP_MARK
        val backups = dir.listFiles { file ->
            file.name.startsWith(prefix) && !file.name.endsWith("-wal") && !file.name.endsWith("-shm")
        }?.sortedByDescending { it.name } ?: return
        backups.drop(1).forEach { deleteDbFiles(it) }
    }

    private fun cachedExpected(context: Context): Header? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stamp = lastUpdateTime(context) ?: return null
        if (prefs.getLong("stamp", -1L) != stamp) return null
        val hash = prefs.getString("hash", null) ?: return null
        val version = prefs.getInt("version", -1)
        if (version < 0) return null
        return Header(version, hash)
    }

    private fun cacheExpected(context: Context, header: Header) {
        val stamp = lastUpdateTime(context) ?: return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putLong("stamp", stamp)
            .putString("hash", header.hash)
            .putInt("version", header.version)
            .apply()
    }

    private fun lastUpdateTime(context: Context): Long? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrNull()

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private data class Header(val version: Int, val hash: String)

    private class Probe(val header: Header, val file: File)
}
