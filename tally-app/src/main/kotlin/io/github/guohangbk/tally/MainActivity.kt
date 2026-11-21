package io.github.guohangbk.tally

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.guohangbk.tally.data.notify.NotificationLink
import io.github.guohangbk.tally.data.notify.PendingBadgeOverlay
import io.github.guohangbk.tally.data.notify.TallyNotifier
import io.github.guohangbk.tally.ui.navigation.TallyAppRoot
import io.github.guohangbk.tally.ui.widget.InsightWidgets
import io.github.guohangbk.tally.ui.widget.TallyWidgets
import io.github.guohangbk.tally.work.AutomationWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TallyAppRoot() }
        intakeSharedImage(intent)
        intakeNotificationLink(intent)
        intakePendingBadge(intent)
        intakeShortcut(intent)
    }

    /** 分享进来的截图、点开的通知与快捷方式都走这条：App 已在前台时由系统复用同一个 Activity。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 从桌面图标点回来的 intent 是 MAIN/LAUNCHER；分享、通知、快捷方式都带各自的动作，不会命中。
        if (intent.action == Intent.ACTION_MAIN) {
            TallyApplication.from(this).graph.launcherOpened.tryEmit(Unit)
        }
        intakeSharedImage(intent)
        intakeNotificationLink(intent)
        intakePendingBadge(intent)
        intakeShortcut(intent)
    }

    /**
     * 回到前台补一次：WorkManager 在国产 ROM 上可能被延迟，
     * 启动与前台路径自己兜底，保证周期/分期不漏、当日提醒不丢。
     * 顺便把桌面组件刷一遍——没有组件实例时这一步会直接返回。
     */
    override fun onResume() {
        super.onResume()
        AutomationWorker.runNow(this)
        refreshWidgets()
    }

    /**
     * 离开前台再刷一次：账是在 App 里记的，回到桌面时若还拿着进来之前那份数，
     * 看起来就像「组件根本不更新」——只靠 onResume 刷新，改动要等下一次进来才生效。
     */
    override fun onStop() {
        super.onStop()
        refreshWidgets()
    }

    private fun refreshWidgets() {
        TallyWidgets.refresh(this)
        InsightWidgets.refresh(this)
    }

    private fun intakeSharedImage(intent: Intent?) {
        val uris = sendStreams(intent) ?: return
        uris.forEach(::retainReadPermission)
        TallyApplication.from(this).graph.emitSharedMaterial(uris)
    }

    /** 通知里的落点只带了两段字符串，还原成对象后交给导航图消费（T-04）。 */
    private fun intakeNotificationLink(intent: Intent?) {
        val key = intent?.getStringExtra(TallyNotifier.EXTRA_LINK_KEY) ?: return
        val arg = intent.getStringExtra(TallyNotifier.EXTRA_LINK_ARG)
        TallyApplication.from(this).graph.pendingLink.value = NotificationLink.decode(key, arg)
    }

    /** 待确认角标点进来：把落点交给导航图，首页会把卡摆出来。 */
    private fun intakePendingBadge(intent: Intent?) {
        if (intent?.getBooleanExtra(PendingBadgeOverlay.EXTRA_OPEN_PENDING, false) != true) return
        intent.removeExtra(PendingBadgeOverlay.EXTRA_OPEN_PENDING)
        TallyApplication.from(this).graph.pendingLink.value = NotificationLink.PendingIngest
    }

    /**
     * 快捷方式：两条静态动作都在这里接，落点都复用通知那套 pendingLink。
     * 处理完把 action 清掉：Activity 因配置变化重建时不能把同一条捷径再走一遍。
     */
    private fun intakeShortcut(intent: Intent?) {
        when (intent?.action) {
            ACTION_NEW_BILL -> {
                TallyApplication.from(this).graph.pendingLink.value = NotificationLink.NewBill
                intent.action = null
            }
            ACTION_STATS -> {
                // 桌面图标没有账本上下文：落到「全部账本」最直白，要单看一家就进 App 从首页切。
                TallyApplication.from(this).graph.pendingLink.value = NotificationLink.Stats()
                intent.action = null
            }
        }
    }

    /**
     * 分享进来的图：单张（ACTION_SEND）与多张（ACTION_SEND_MULTIPLE）都走这里。
     * EXTRA_STREAM 有两种常见形状（单张是一个 Uri、多张是 Uri 列表），两种都按整批读；
     * 再兜那些只塞 clipData 的分享方——图库一次选好几张分享过来才不会被截成一张。
     */
    private fun sendStreams(intent: Intent?): List<Uri>? {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val fromExtra = when (val stream = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> listOf(stream)
            is ArrayList<*> -> stream.filterIsInstance<Uri>()
            else -> emptyList()
        }
        val fromClip = intent.clipData?.let { data -> (0 until data.itemCount).map { data.getItemAt(it).uri } }.orEmpty()
        val uris = fromExtra.ifEmpty { fromClip }
        if (uris.isEmpty()) {
            // 声明了分享动作却一张图都取不到：留一条日志，别静默什么都不做。
            io.github.guohangbk.tally.data.debug.DebugLog.log("分享", "收到 $action，但没有可读的图片 uri")
            return null
        }
        return uris
    }

    private fun retainReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        /** 与 `res/xml/shortcuts.xml` 里两条 `<intent android:action>` 保持一致。 */
        const val ACTION_NEW_BILL = "io.github.guohangbk.tally.action.NEW_BILL"
        const val ACTION_STATS = "io.github.guohangbk.tally.action.STATS"
    }
}
