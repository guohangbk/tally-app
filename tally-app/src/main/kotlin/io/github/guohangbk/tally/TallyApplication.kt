package io.github.guohangbk.tally

import android.app.Application
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.work.AutomationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TallyApplication : Application() {
    lateinit var graph: AppGraph
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val createAt = System.currentTimeMillis()
        DebugLog.log("Perf", "Application.onCreate 开始")
        graph = AppGraph(this)
        DebugLog.log("Perf", "AppGraph 构建 +${System.currentTimeMillis() - createAt}ms")
        // 启动即补执行：WorkManager 在国产 ROM 上可能被延迟，启动路径必须自己兜底。
        appScope.launch {
            // 首次触库：SQLite 冷开 + WAL + 建库/迁移校验都折在这一笔里，单拎出来便于定位「打开慢」。
            val bootAt = System.currentTimeMillis()
            val defaultLedger = graph.ledgerRepository.bootstrap()
            DebugLog.log("Perf", "app bootstrap(首次触库) +${System.currentTimeMillis() - bootAt}ms")
            graph.cachedDefaultLedgerId = defaultLedger.id
            graph.cachedDefaultLedgerName = defaultLedger.name
            val catchAt = System.currentTimeMillis()
            graph.reminderCoordinator.runBackground()
            DebugLog.log("Perf", "catchUp+提醒(周期/分期补执行) +${System.currentTimeMillis() - catchAt}ms")
            // 预热首页首帧要用的两条查询，把冷启动聚合成本挪到启动阶段，削掉打开后的空窗。
            val warmAt = System.currentTimeMillis()
            graph.prewarmHome()
            DebugLog.log("Perf", "prewarmHome +${System.currentTimeMillis() - warmAt}ms · 距 onCreate +${System.currentTimeMillis() - createAt}ms")
            // 分类树同样预热：记账页与分类管理页的首帧都靠它，以前是进页才现查。
            graph.prewarmCategories()
            DebugLog.log("Perf", "prewarmCategories +${System.currentTimeMillis() - warmAt}ms · 距 onCreate +${System.currentTimeMillis() - createAt}ms")
        }
        AutomationWorker.schedule(this)
    }

    companion object {
        fun from(context: android.content.Context): TallyApplication =
            context.applicationContext as TallyApplication
    }
}
