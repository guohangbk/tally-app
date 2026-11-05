package io.github.guohangbk.tally.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.guohangbk.tally.TallyApplication
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.ui.widget.InsightWidgets
import io.github.guohangbk.tally.ui.widget.TallyWidgets
import java.util.concurrent.TimeUnit

/**
 * 周期/分期的后台补执行，顺带把四类提醒算一遍。
 * WorkManager 只负责「什么时候醒」，业务规则全在 AutomationService 与 ReminderCoordinator：
 * 启动、回到前台和 Worker 走同一条路径，幂等键保证不漏不重，每日游标保证同一件事不轰第二遍。
 */
class AutomationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = TallyApplication.from(applicationContext).graph
        return runCatching {
            graph.ledgerRepository.bootstrap()
            // 补执行与提醒一次做完：这一条是后台唯一的自动化入口。
            graph.reminderCoordinator.runBackground()
            // 保留期只有一个：先清超期的软删账单（连带回收它们的图片），
            // 再扫剩下的附件行。分开设会出现「账单还在回收站、图已经被回收」。
            val keepDays = graph.settingRepository.recycleKeepDays().days
            if (keepDays != null) {
                val purged = graph.recycleBinRepository.purgeExpired(keepDays)
                val cleaned = graph.attachmentCleanup.run(keepDays = keepDays)
                if (purged.purgedBills > 0 || cleaned.rows > 0) {
                    val freed = purged.freedBytes + cleaned.freedBytes
                    DebugLog.log("清理", "回收 ${purged.purgedBills} 笔 · 附件 ${cleaned.rows} 条 · 释放 ${(freed + 1023) / 1024} KB")
                    graph.reminderCoordinator.postCleanup(purged.purgedBills, cleaned.rows, freed)
                }
            }
            // 桌面组件每天补刷一次；桌面上没有组件时这一步直接返回，不查库。
            runCatching { TallyWidgets.updateAll(applicationContext) }
            runCatching { InsightWidgets.updateAll(applicationContext) }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val PERIODIC_NAME = "tally_automation_periodic"
        private const val ONE_SHOT_NAME = "tally_automation_now"

        /** 每天一次；国产 ROM 可能延迟，所以启动与回到前台还有同步补执行。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutomationWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** App 启动或回到前台时触发一次即时补执行。 */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutomationWorker>().build(),
            )
        }
    }
}
