package io.github.guohangbk.tally.data.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.guohangbk.tally.data.storage.DownloadedFile
import io.github.guohangbk.tally.data.storage.PublicDownloads
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.model.StatsGranularity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 海报的两条出口。
 *
 * 「分享」写缓存再经 FileProvider 交出去——凭空往用户相册里塞一张图是最招人烦的行为，
 * 而分享本来只需要一个临时可读的副本；「保存到下载」才是显式落公共目录的动作。
 */
object PosterFiles {
    private const val MIME = "image/png"
    private const val CACHE_DIR = "exports"
    private val month = DateTimeFormatter.ofPattern("yyyy-MM", java.util.Locale.CHINA)
    private val year = DateTimeFormatter.ofPattern("yyyy", java.util.Locale.CHINA)
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.CHINA)

    /** 期间在前，方便在文件管理器里按名排序；账本名不进文件名（同一个人多半有好几本）。 */
    fun name(granularity: StatsGranularity, range: PeriodRange): String {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(range.startAt).atZone(zone).toLocalDate()
        return when (granularity) {
            StatsGranularity.MONTH -> "${month.format(start)} 月度账单"
            StatsGranularity.YEAR -> "${year.format(start)} 年度账单"
            StatsGranularity.RANGE -> {
                val end = Period.toDate(range.endAtExclusive, zone).minusDays(1)
                "${day.format(start)}_至_${day.format(end)} 区间账单"
            }
        } + ".png"
    }

    /** 只读分享用的副本：同一个文件名覆盖上一次，缓存目录里不会堆出几十张海报。 */
    fun cacheUri(context: Context, fileName: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** 保存到下载：同一期间的海报覆盖上一张，改一次脱敏不该多一个文件。 */
    fun save(context: Context, fileName: String, bytes: ByteArray): DownloadedFile =
        PublicDownloads.write(context, fileName, MIME, bytes, replace = true)

    /** 与导出 CSV 同一套 chooser 写法：带读权限授予，落地页各取所需。 */
    fun shareIntent(context: Context, uri: Uri, subject: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "分享账单长图",
        ).also { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
