package io.github.guohangbk.tally.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.preferences.ThemeMode
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyErrorBanner
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

private const val APP_VERSION = "0.1.0"

/**
 * P20 设置与关于：功能开关 / 小莉 / 外观 / 数据 / 恢复默认 / 关于。
 * 每次改动立刻写 DataStore 或 app_setting，界面状态回流自 settings Flow，因此重启后保持。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenBackup: () -> Unit = {},
    /** 「小莉配置」的去向：独立配置页，不是小莉对话页。这一条不给默认值，接错就编译不过。 */
    onOpenAssistantConfig: () -> Unit,
    onOpenKeywordRules: () -> Unit,
    /** 回收站：删除找回从气泡搬到页面，所以入口跟数据放在一起。 */
    onOpenRecycle: () -> Unit,
    /** 提醒与通知独立一页（T-01）：这一页只管开关与时刻，声音与打扰等级归系统渠道。 */
    onOpenNotifications: () -> Unit,
    /** 使用指南：把「怎么操作」和「按什么口径算」收在同一页，统计页右上角不再挂口径入口。 */
    onOpenGuide: () -> Unit,
    /** 许可与依赖：BUSL-1.1 是源码可用许可，不是开源，页上把这两个词分清楚。 */
    onOpenLicense: () -> Unit,
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { SettingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    val settings = state.settings
    val assistantOn = settings.assistantEnabled
    var monthStartPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "设置",
            onBack = onBack,
            trailing = {
                TallyTag(text = "v$APP_VERSION")
            },
        )

        // 提示改成贴底气泡，错误文案带上前缀，保留「这项设置没有保存」这个定性说法。
        // 弹窗打开时错误属于弹窗内容（恢复默认那份逐条结果仍显示在弹窗里），不重复弹气泡。
        TallyToastEffects(
            notice = state.notice,
            error = state.error.takeIf { state.dialog == null },
            errorTitle = "这项设置没有保存",
            onNoticeShown = viewModel::dismissNotice,
            onErrorShown = viewModel::dismissError,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsSection(title = "记账偏好") {
                SettingsSwitchRow(
                    checked = settings.billImageEnabled,
                    onCheckedChange = viewModel::setBillImageEnabled,
                    label = "账单图片",
                    description = if (settings.billImageEnabled) {
                        "已开启 · 记账页常驻图片入口"
                    } else {
                        "已关闭 · 记账页不显示图片入口"
                    },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.reimburseEnabled,
                    onCheckedChange = viewModel::setReimburseEnabled,
                    label = "报销",
                    description = if (settings.reimburseEnabled) {
                        "已开启 · 记账页与详情出现报销状态"
                    } else {
                        "已关闭 · 记账不出现报销字段"
                    },
                )
                SettingsDivider()
                SettingsChoiceRow(
                    label = "账单时间精度",
                    description = "切换「到时和分」，可保存账单的具体时间",
                    options = listOf(
                        TimePrecision.DATE to "精确到日",
                        TimePrecision.DATETIME to "到时和分",
                    ),
                    selected = settings.timePrecision,
                    onSelect = viewModel::setTimePrecision,
                )
                SettingsDivider()
                // 发薪日不是 1 号时，首页与统计的「本月」必须一起挪，否则两边对不上。
                SettingsNavRow(
                    title = "每月起始日",
                    subtitle = "${state.monthStartDay} 号 · 可选 1–28",
                    iconKey = "cal",
                    onClick = { monthStartPicker = true },
                )
            }

            // 小莉分组：只有开启后才有金色触点；未开启时界面里没有任何人格化入口或装饰。
            SettingsSection(title = "小莉（AI）", gold = assistantOn) {
                SettingsSwitchRow(
                    checked = assistantOn,
                    onCheckedChange = viewModel::setAssistantEnabled,
                    label = "小莉（AI）",
                    description = if (assistantOn) {
                        "首页长按 + ，往右划进入小莉"
                    } else {
                        "已关闭 · 界面不显示 AI 入口"
                    },
                    gold = assistantOn,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "小莉配置",
                    subtitle = "服务商、模型与连接测试",
                    iconKey = "ai",
                    onClick = onOpenAssistantConfig,
                )
            }

            SettingsSection(title = "提醒") {
                SettingsNavRow(
                    title = "提醒与通知",
                    subtitle = "未记账 · 周期与分期到期 · 执行失败",
                    iconKey = "clock",
                    onClick = onOpenNotifications,
                )
            }

            SettingsSection(title = "外观") {
                SettingsChoiceRow(
                    label = "主题",
                    description = "深色只做同一套 token 的换色，不改布局",
                    options = listOf(
                        ThemeMode.SYSTEM to "跟随系统",
                        ThemeMode.LIGHT to "浅色",
                        ThemeMode.DARK to "深色",
                    ),
                    selected = settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.showCurrencySymbol,
                    onCheckedChange = viewModel::setShowCurrencySymbol,
                    label = "货币符号",
                    description = "金额前显示 ¥",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.showThousandsSeparator,
                    onCheckedChange = viewModel::setShowThousandsSeparator,
                    label = "千分位",
                    description = "金额每三位加逗号，例如 2,780.45",
                )
                SettingsDivider()
                SettingsInfoRow(label = "默认货币", value = "${state.defaultCurrency} · 固定人民币")
            }

            // 数据分组只留「这里才有」的东西：账本、分类、周期、分期在侧边栏「管理」组里已经有入口。
            SettingsSection(title = "数据") {
                // 关键词规则是语音与截图共用的那一张表，属于数据而不是小莉配置。
                SettingsNavRow(
                    title = "关键词规则",
                    subtitle = "内置词只读 · 我的规则优先",
                    iconKey = "tag",
                    onClick = onOpenKeywordRules,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "导入账单",
                    subtitle = "模板 / 钱迹 / 支付宝 · 选文件后预览与对账",
                    iconKey = "import",
                    onClick = onOpenImport,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "导出账单",
                    subtitle = "13 列 CSV / XLSX · 可原样读回",
                    iconKey = "export",
                    onClick = onOpenExport,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "备份与同步",
                    subtitle = ".tally 加密备份、恢复与 WebDAV 手动快照",
                    iconKey = "cloud",
                    onClick = onOpenBackup,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "回收站",
                    subtitle = "删掉的账单先留着 · 可恢复或彻底删除",
                    iconKey = "recycle",
                    onClick = onOpenRecycle,
                )
            }

            SettingsSection(title = "恢复默认") {
                TallyListRow(
                    title = "恢复默认偏好",
                    subtitle = "设置重置到出厂默认，不影响账单数据",
                    iconKey = "warn",
                    iconSize = 30,
                    onClick = viewModel::requestResetPreferences,
                    trailing = { TallyTag(text = "需确认", tone = TagTone.NEUTRAL) },
                )
            }

            SettingsSection(title = "关于") {
                SettingsInfoRow(label = "版本", value = APP_VERSION)
                SettingsDivider()
                DebugEntry(
                    packageName = LocalContext.current.packageName,
                    enabled = settings.debugMode,
                    onToggle = viewModel::setDebugMode,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "使用指南",
                    subtitle = "怎么用、从哪儿进，以及每个数字按什么口径算",
                    iconKey = "book",
                    onClick = onOpenGuide,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "许可与依赖",
                    subtitle = "BUSL-1.1 · 源码可用许可 · 第三方依赖清单",
                    iconKey = "shield",
                    onClick = onOpenLicense,
                )
                SettingsDivider()
                Text(
                    text = PRIVACY_NOTE,
                    style = TallyType.helper,
                    color = colors.ink2,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Tally 塔莉 · 数据只存在这台手机里",
                style = TallyType.helper,
                color = colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    SettingsDialogHost(state, viewModel)

    if (monthStartPicker) {
        MonthStartDayDialog(
            selected = state.monthStartDay,
            onDismiss = { monthStartPicker = false },
            onConfirm = { day ->
                monthStartPicker = false
                viewModel.setMonthStartDay(day)
            },
        )
    }
}

@Composable
private fun SettingsDialogHost(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    if (state.dialog == SettingsDialogKind.RESET_PREFERENCES) {
        TallyConfirmDialog(
            title = "恢复默认偏好？",
            message = "账单图片、报销、主题、显示与时间精度会回到出厂默认，小莉保持关闭。账单、账本、分类和图片附件都不受影响。",
            confirmLabel = "恢复默认",
            dismissLabel = "取消",
            destructive = true,
            onConfirm = viewModel::confirmResetPreferences,
            onDismiss = viewModel::dismissDialog,
            content = {
                state.error?.let { detail ->
                    TallyErrorBanner(title = "有项目没有恢复默认", detail = detail)
                }
            },
        )
    }
}

/**
 * 包名这一行是后门：1.2 秒内连点 5 次切换调试模式。
 * 不开调试时它就是一行普通信息，看不出任何入口。
 */
@Composable
private fun DebugEntry(packageName: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = tallyColors
    var taps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val now = android.os.SystemClock.elapsedRealtime()
                taps = if (now - lastTapAt > 1_200L) 1 else taps + 1
                lastTapAt = now
                if (taps >= 5) {
                    taps = 0
                    onToggle(!enabled)
                }
            }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("数据包名", style = TallyType.listTitle, color = colors.ink2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(
            text = packageName,
            style = TallyType.listTitle.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (enabled) {
            Spacer(Modifier.width(6.dp))
            TallyTag(text = "调试", tone = TagTone.GOLD)
        }
    }
}

private const val PRIVACY_NOTE =    "隐私说明：账单、分类、图片与偏好都存在本地。" +
        "小莉关闭时不会向任何服务发送数据；开启后发送哪些内容由您配置，密钥不进备份包。"


