package io.github.guohangbk.tally.data.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.guohangbk.tally.MainActivity
import io.github.guohangbk.tally.R

/**
 * 四类通知共用的一条出口。
 *
 * 三条硬规则写在这里，页面不再各说一遍：
 *
 * 1. **不建通知表、不建消息中心**：四类提醒全部是"到点了拿本地数据算一下"，
 *    算完就发、发完就忘。唯一留下的痕迹是两张自动化表上的 `last_reminded_at`，
 *    它的含义是"这条规则今天已经提醒过一次"，用来做到"同一规则每天最多一条"。
 * 2. **锁屏不带金额与分类名**：每条通知都配一份 `publicText`，经 `setPublicVersion`
 *    挂在私密通知上——解锁前能看到的只有"今天还没记账"这种没有信息量的句子，
 *    也不给跳转意图。这条不依赖应用锁（已砍），任何时候都成立。
 * 3. **声音、打扰等级、横幅一律交给系统渠道**：App 里不复制一套设置，
 *    设置页只回答"要不要发、几点发"，其余跳系统通知设置。
 */
enum class TallyChannel(val id: String, val title: String, val blurb: String) {
    REMINDER("tally_reminder", "每日提醒", "当天还没记账时提醒一次"),
    AUTOMATION("tally_automation", "自动记账", "周期与分期到期、已自动记入"),
    FAILURE("tally_failure", "执行失败", "自动记账没跑成，需要你去处理"),
    SYSTEM("tally_system", "系统", "回收站清理与备份提醒"),
}

/**
 * 一条要发出去的通知。
 *
 * [link] 是点开后要落到的去处，null 表示这条不需要跳转（比如清理结果）。
 * [tag] 决定"同一件事重复触发是更新而不是再堆一条"。
 */
data class TallyNotice(
    val channel: TallyChannel,
    val title: String,
    val text: String,
    val publicText: String,
    val tag: String,
    val link: NotificationLink? = null,
)

class TallyNotifier(private val context: Context) {

    /** 安卓 13 以下没有运行时通知权限，视为可发；13+ 必须由用户在通知设置页授予。 */
    val canPost: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 渠道只在第一次创建，之后由用户在系统设置里改，App 不再覆盖回去。minSdk 28 起这段代码一直有。 */
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        TallyChannel.entries.forEach { channel ->
            if (manager.getNotificationChannel(channel.id) != null) return@forEach
            manager.createNotificationChannel(
                NotificationChannel(channel.id, channel.title, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = channel.blurb
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * 发一条。权限没给就直接返回——提醒是通知，不是执行条件，
     * 调用方不需要为"这条没发出去"改变任何行为。
     */
    fun post(notice: TallyNotice) {
        if (!canPost) return
        ensureChannels()
        val publicCopy = NotificationCompat.Builder(context, notice.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice.publicText)
            .setContentText("")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val builder = NotificationCompat.Builder(context, notice.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicCopy)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(notice.channel.id)
        notice.link?.let { builder.setContentIntent(pendingIntentFor(it)) }
        manager().notify(notice.tag, notice.tag.hashCode(), builder.build())
    }

    /** 事情已经解决了（比如失败那条规则被手动补跑成功），就把还挂着的通知收掉。 */
    fun cancel(tag: String) = manager().cancel(tag, tag.hashCode())

    /** 总开关关掉：挂在通知栏的一条都不留。这里只收本 App 自己的通知。 */
    fun cancelAll() = manager().cancelAll()

    private fun manager(): NotificationManager = context.getSystemService(NotificationManager::class.java)

    private fun pendingIntentFor(link: NotificationLink): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_LINK_KEY, link.key)
            link.arg?.let { putExtra(EXTRA_LINK_ARG, it) }
        }
        return PendingIntent.getActivity(
            context,
            link.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /**
         * MainActivity 读到这两个 extra 就还原成 [NotificationLink] 交给一个应用级待跳转状态，
         * 导航图就绪后消费——与分享截图 `sharedScreenshot` 用的是同一套解耦方式，
         * 冷启动和已在前台都能走。
         */
        const val EXTRA_LINK_KEY = "io.github.guohangbk.tally.extra.LINK_KEY"
        const val EXTRA_LINK_ARG = "io.github.guohangbk.tally.extra.LINK_ARG"
    }
}
