package io.github.guohangbk.tally.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.common.TallyHourPickerDialog
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * T-01 提醒与通知：这一页只回答两件事——**开不开、几点开**。
 * 渠道、声音、横幅与打扰等级一律由系统渠道管，点「系统通知设置」跳过去改，App 不复制一套。
 *
 * 权限只在这一页要（T-03）：首次启动不问，被拒后也不自动重弹系统框，
 * 只把「开启通知」这颗按钮留在这儿等人主动按。
 */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val colors = tallyColors
    val viewModel = tallyViewModel(key = "notification-settings") { SettingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val notifier = graph.tallyNotifier

    var canPost by remember { mutableStateOf(notifier.canPost) }
    var hourPicker by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canPost = granted
    }
    // 从系统页回来再读一次：在那边把通知关掉，这一页得如实显示「发不出去」。
    val systemChannels = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canPost = notifier.canPost
    }

    // 渠道先建出来，系统通知设置里才看得到那四条，而不是只有「Tally」一项。
    LaunchedEffect(Unit) { notifier.ensureChannels() }

    TallyToastEffects(
        notice = state.notice,
        error = state.error,
        errorTitle = "这项设置没有保存",
        onNoticeShown = viewModel::dismissNotice,
        onErrorShown = viewModel::dismissError,
    )

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "提醒与通知", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (!canPost) {
                PermissionNotice(
                    onEnable = { askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            SettingsSection(title = "总开关") {
                SettingsSwitchRow(
                    checked = settings.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled,
                    label = "通知总开关",
                    description = if (settings.notificationsEnabled) {
                        "已开启 · 下面四类各自可关"
                    } else {
                        "已关闭 · 四类全部不发，补执行照常在后台跑"
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            SettingsSection(title = "四类") {
                SettingsSwitchRow(
                    checked = settings.notifyUnlogged,
                    onCheckedChange = viewModel::setNotifyUnlogged,
                    label = "当日未记账",
                    description = "每天 ${hourLabel(settings.dailyReminderHour)} · 当天没有任何账单才发",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.notifyRecurring,
                    onCheckedChange = viewModel::setNotifyRecurring,
                    label = "周期任务到期",
                    description = "已记入 / 待执行 各一条",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.notifyInstallment,
                    onCheckedChange = viewModel::setNotifyInstallment,
                    label = "分期到期",
                    description = "应还前 1 天与当天",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.notifyFailure,
                    onCheckedChange = viewModel::setNotifyFailure,
                    label = "执行失败",
                    description = "聚合为一条 · 每条任务每日一发",
                )
            }

            Spacer(Modifier.height(10.dp))
            SettingsSection(title = "其他") {
                SettingsNavRow(
                    title = "每日提醒时刻",
                    subtitle = "${hourLabel(settings.dailyReminderHour)} · 错过则进 App 补发",
                    iconKey = "clock",
                    onClick = { hourPicker = true },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.showAmountOnLockscreen,
                    onCheckedChange = viewModel::setShowAmountOnLockscreen,
                    label = "锁屏显示金额",
                    description = "关闭时锁屏只显示「今天还没记账」这类句子",
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "系统通知设置",
                    subtitle = "渠道、声音与打扰等级由系统管",
                    iconKey = "settings",
                    onClick = {
                        systemChannels.launch(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "提醒不承诺准点，只承诺「今天之内」",
                style = TallyType.helper,
                color = colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (hourPicker) {
        TallyHourPickerDialog(
            initialHour = settings.dailyReminderHour,
            onDismiss = { hourPicker = false },
            onConfirm = {
                hourPicker = false
                viewModel.setDailyReminderHour(it)
            },
        )
    }
}

/**
 * T-03 未授权态：说清楚「发不出去，但账照常补」，免得以为自动记账停了。
 * 被拒后系统可能不再弹框，所以这一颗按钮按下去只是「再问一次」，不做定时骚扰。
 */
// 这张卡只在 `canPost == false` 时出现，而那在 33 以下恒为真，所以按权限的一定为 33+。
@SuppressLint("InlinedApi")
@Composable
private fun PermissionNotice(
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    TallyCard(borderColor = colors.expense.copy(alpha = 0.28f), contentPadding = 12.dp, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(iconKey = "warn", label = "提醒", size = 26.dp, corner = 9.dp, tone = IconTone.EXPENSE)
            Spacer(Modifier.width(9.dp))
            Text(
                text = "系统通知未授权",
                style = TallyType.listTitle.copy(fontSize = 13.sp, lineHeight = 16.3.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = "四类提醒都发不出去，但周期与分期照常补执行——提醒是通知，不是执行条件。",
            style = TallyType.helper,
            color = colors.ink2,
        )
        Spacer(Modifier.height(10.dp))
        TallyButton(
            label = "开启通知",
            onClick = onEnable,
            variant = ButtonVariant.BRAND,
            height = 32,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
