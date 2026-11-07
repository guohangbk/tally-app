package io.github.guohangbk.tally

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.guohangbk.tally.data.notify.NotificationLink
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
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = sendStream(intent) ?: return
        TallyApplication.from(this).graph.sharedScreenshot.value = uri
    }

    /** 通知里的落点只带了两段字符串，还原成对象后交给导航图消费（T-04）。 */
    private fun intakeNotificationLink(intent: Intent?) {
        val key = intent?.getStringExtra(TallyNotifier.EXTRA_LINK_KEY) ?: return
        val arg = intent.getStringExtra(TallyNotifier.EXTRA_LINK_ARG)
        TallyApplication.from(this).graph.pendingLink.value = NotificationLink.decode(key, arg)
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

    @Suppress("DEPRECATION")
    private fun sendStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private companion object {
        /** 与 `res/xml/shortcuts.xml` 里两条 `<intent android:action>` 保持一致。 */
        const val ACTION_NEW_BILL = "io.github.guohangbk.tally.action.NEW_BILL"
        const val ACTION_STATS = "io.github.guohangbk.tally.action.STATS"
    }
}
