package io.github.guohangbk.tally.data.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** 公共下载目录里的一个文件。[location] 是给人看的位置，[uri] 是给分享用的。 */
data class DownloadedFile(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val writtenAt: Long,
    val location: String,
)

/**
 * 导出与备份一律写公共「下载/Tally」：藏在应用私有目录里，用户在文件管理器里看不见，
 * 等于没保存。API 28 没有 MediaStore.Downloads，退回应用外部目录并用 FileProvider 分享。
 */
object PublicDownloads {
    const val DIR_NAME = "Tally"
    private val RELATIVE = Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME

    /**
     * [replace] 是给「同一件东西重出一遍」的：MediaStore 默认同名会另存 `xxx (1)`，
     * 改一次脱敏就在下载目录里堆一份尸体。API 28 那条走文件覆盖，本来就是这个语义。
     */
    fun write(context: Context, name: String, mimeType: String, bytes: ByteArray, replace: Boolean = false): DownloadedFile {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            if (replace) {
                // MediaStore 存的是带尾斜杠的 "Download/Tally/"，比对时要补齐，否则一条也删不掉。
                val stored = RELATIVE.trimEnd('/') + "/"
                val doomed = mutableListOf<Uri>()
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf(name, stored),
                    null,
                )?.use { cursor ->
                    val column = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    while (cursor.moveToNext()) {
                        doomed += ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(column))
                    }
                }
                doomed.forEach { runCatching { resolver.delete(it, null, null) } }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("系统不允许写入下载目录")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("系统不允许写入下载目录")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            DownloadedFile(
                name = name,
                uri = uri,
                sizeBytes = bytes.size.toLong(),
                writtenAt = System.currentTimeMillis(),
                location = "$RELATIVE/$name",
            )
        } else {
            val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DIR_NAME).apply { mkdirs() }
            val file = File(dir, name).apply { writeBytes(bytes) }
            DownloadedFile(
                name = name,
                uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file),
                sizeBytes = file.length(),
                writtenAt = file.lastModified(),
                location = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/$name",
            )
        }
    }

    /** 备份历史就是这个目录里的 .tally 文件，不再另建一张表。 */
    fun list(context: Context, suffix: String): List<DownloadedFile> {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val rows = appContext.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf("$RELATIVE/%", "%$suffix"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        add(
                            DownloadedFile(
                                name = cursor.getString(nameColumn).orEmpty(),
                                uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                                sizeBytes = cursor.getLong(sizeColumn),
                                writtenAt = cursor.getLong(modifiedColumn) * 1000L,
                                location = "$RELATIVE/${cursor.getString(nameColumn)}",
                            ),
                        )
                    }
                }
            }.orEmpty()
            rows
        } else {
            val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DIR_NAME)
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(suffix) }
                ?.sortedByDescending { it.lastModified() }
                ?.map { file ->
                    DownloadedFile(
                        name = file.name,
                        uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file),
                        sizeBytes = file.length(),
                        writtenAt = file.lastModified(),
                        location = file.absolutePath,
                    )
                }
                .orEmpty()
        }
    }
}
