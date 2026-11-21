package io.github.guohangbk.tally.data.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.guohangbk.tally.TallyApplication
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知抓取服务：所有应用的通知都过一遍内容规则，只接支付类。
 *
 * 抓到不弹窗、不发通知，直接交给 [NotificationIngestCoordinator] 解析并并进待确认清单。
 * 原文不落盘、不写日志；开关关着、或不是支付类通知，一律不动。
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 包名 → 应用名：一条通知一次查询太浪费，服务活多久缓存多久。 */
    private val appNames = ConcurrentHashMap<String, String>()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val graph = runCatching { TallyApplication.from(this).graph }.getOrNull() ?: return
        scope.launch {
            if (!enabled(graph)) return@launch
            handle(graph, sbn)
        }
    }

    /**
     * 进程被系统回收、隔了一阵才被重新拉起时，断开期间的通知不会补发。
     * 这里把「还挂在通知栏上」且是最近十分钟的再读一遍——钱是真花出去的，不能指望人开着 App。
     *
     * 重放靠幂等台账的 REPLAY 那一档认出来（同一条通知 postTime 分毫不差），不会记成两笔。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        val graph = runCatching { TallyApplication.from(this).graph }.getOrNull() ?: return
        val active = runCatching { activeNotifications ?: emptyArray() }.getOrDefault(emptyArray())
        if (active.isEmpty()) return
        scope.launch {
            if (!enabled(graph)) return@launch
            val cutoff = System.currentTimeMillis() - CATCH_UP_MILLIS
            val recent = active.filter { it.postTime >= cutoff }.sortedBy { it.postTime }
            if (recent.isEmpty()) return@launch
            recent.forEach { handle(graph, it) }
            DebugLog.log("通知抓取", "重连补收通知栏上的 ${recent.size} 条")
        }
    }

    private suspend fun enabled(graph: AppGraph): Boolean =
        runCatching { graph.preferences.settings.first().notificationCaptureEnabled }.getOrDefault(false)

    private suspend fun handle(graph: AppGraph, sbn: StatusBarNotification) {
        // 自己发的通知、常驻通知、分组摘要都不看：它们不是一笔新的支付。
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val snapshot = snapshotOf(sbn) ?: return
        runCatching { graph.notificationCapture.capture(snapshot) }
            .onFailure { failure ->
                // 只留异常类型与第一帧位置：通知原文与账单内容都不进日志。
                val where = failure.stackTrace.firstOrNull()?.let { "${it.className}:${it.lineNumber}" }.orEmpty()
                DebugLog.log(
                    "通知抓取",
                    "处理这条通知出错：${failure.javaClass.simpleName} $where ${failure.message.orEmpty()}".trim(),
                )
            }
    }

    private companion object {
        /** 补收只看最近十分钟：再往前的是昨天的事，不该在今天的清单里冒出来。 */
        const val CATCH_UP_MILLIS = 10 * 60 * 1000L
    }

    /** 只取解析需要的那几个字段；通知对象本身不保留、不上传。 */
    private fun snapshotOf(sbn: StatusBarNotification): PaymentSnapshot? {
        val notification: Notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val title = extras.getString(Notification.EXTRA_TITLE).orEmpty()
        val text = extras.getString(Notification.EXTRA_TEXT).orEmpty()
        val map = mutableMapOf<String, String>()
        extras.keySet().forEach { key ->
            runCatching { extras.getString(key) }.getOrNull()?.let { map[key] = it }
        }
        return PaymentSnapshot(
            packageName = sbn.packageName,
            title = title,
            text = text,
            extras = map,
            atMillis = sbn.postTime,
            appName = appName(sbn.packageName),
        )
    }

    private fun appName(packageName: String): String = appNames.getOrPut(packageName) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault("")
    }
}
