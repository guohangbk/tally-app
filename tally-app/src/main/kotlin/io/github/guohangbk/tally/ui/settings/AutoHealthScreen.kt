package io.github.guohangbk.tally.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.notify.NotificationAccess
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一项系统状态：真实读出来的值，不猜、不假装正常。
 *
 * [jump] 是点这一行要打开的 action；需要定位到本 App 详情页的那几项带 [data]，
 * 否则系统设置会打开一个没有目标的空页。
 */
internal data class HealthItem(
    val label: String,
    val description: String,
    /** 右端展示的状态；自启动这类系统查不到的留空，只给箭头。 */
    val status: String?,
    val jump: String,
    val data: String? = null,
)

internal data class HealthState(
    val notification: HealthItem,
    val overlay: HealthItem,
    val battery: HealthItem,
    val selfStart: HealthItem,
)

/**
 * 权限与检查（A2）：通知使用权、悬浮窗、电池优化、自启动。
 * 自启动没有公开接口，就直说读不到，只给跳转——不猜、不假装正常。
 */
@Composable
fun AutoHealthScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "auto-health") { AutoHealthViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    val openSystemSetting = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refresh() }

    // 从系统设置页回来要重新读一遍：这几项的状态全在系统手里，缓存会显示成旧值。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "权限与检查", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            TallyCard(corner = 16, contentPadding = 9.dp) {
                Column {
                    healthRow(state.notification, openSystemSetting)
                    SettingsDivider()
                    healthRow(state.overlay, openSystemSetting)
                    SettingsDivider()
                    healthRow(state.battery, openSystemSetting)
                    SettingsDivider()
                    healthRow(state.selfStart, openSystemSetting)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun healthRow(
    item: HealthItem,
    openSystemSetting: ManagedActivityResultLauncher<Intent, androidx.activity.result.ActivityResult>,
) {
    SettingsStatusRow(
        title = item.label,
        description = item.description,
        status = item.status,
        onClick = {
            runCatching {
                val intent = Intent(item.jump).apply {
                    item.data?.let { data = Uri.parse(it) }
                }
                openSystemSetting.launch(intent)
            }
        },
    )
}

/** 这一页只读系统状态，不写任何偏好；状态一律来自系统，不缓存、不猜测。 */
class AutoHealthViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(
        HealthState(
            notification = HealthItem(
                label = "通知使用权",
                description = "读取支付类通知，自动生成待确认账单",
                status = "读取中",
                jump = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            ),
            overlay = HealthItem(
                label = "悬浮窗",
                description = "角标不出现时，点这一行去系统里开一次",
                status = "读取中",
                jump = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            ),
            battery = HealthItem(
                label = "电池优化",
                description = "忽略省电限制，后台抓通知不被冻结",
                status = "读取中",
                jump = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            ),
            selfStart = HealthItem(
                label = "自启动",
                description = "系统不提供查询，需要到设置里自行开启",
                status = null,
                jump = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
        ),
    )
    internal val state: StateFlow<HealthState> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val context = graph.context
            val packageName = context.packageName
            mutable.value = mutable.value.copy(
                notification = notification(context),
                overlay = overlay(context),
                battery = battery(packageName),
                selfStart = selfStart(packageName),
            )
        }
    }

    private fun notification(context: Context) = HealthItem(
        label = "通知使用权",
        description = "读取支付类通知，自动生成待确认账单",
        status = if (NotificationAccess.enabled(context)) "已授权" else "未授权",
        jump = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
    )

    private fun overlay(context: Context) = HealthItem(
        label = "悬浮窗",
        description = "角标不出现时，点这一行去系统里开一次",
        status = if (Settings.canDrawOverlays(context)) "已授权" else "未授权",
        jump = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        data = "package:${context.packageName}",
    )

    private fun battery(packageName: String) = HealthItem(
        label = "电池优化",
        description = "忽略省电限制，后台抓通知不被冻结",
        status = if (isBatteryExempted()) "已设置" else "未设置",
        jump = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        data = "package:$packageName",
    )

    private fun selfStart(packageName: String) = HealthItem(
        label = "自启动",
        description = "系统不提供查询，需要到设置里自行开启",
        status = null,
        jump = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        data = "package:$packageName",
    )

    private fun isBatteryExempted(): Boolean = runCatching {
        val context = graph.context
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
}
