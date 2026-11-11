package io.github.guohangbk.tally.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.rememberCoroutineScope
import io.github.guohangbk.tally.data.debug.Diagnostics
import io.github.guohangbk.tally.ui.design.TallyToast
import io.github.guohangbk.tally.ui.design.ToastTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.preferences.ThemeMode
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyErrorBanner
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/** 项目主页：公开仓里有源码、发行包与文档入口，App 里只留这一个出口，不再重复一遍链接。 */
private const val PROJECT_HOME = "https://github.com/guohangbk/tally-app"

/**
 * 版本号取 APK 自己的，**不在这里手写一份**——手写的那份一定会被忘掉：
 * 上一版就停在 `"0.1.0"`，而产物早就换号了，用户报问题时报的版本号是错的。
 * 括号里带上 versionCode（公式 `major*10000 + minor*100 + patch`），
 * 这样即使 versionName 是 `0.1.1-dev` 这种也能一眼定位到具体构建。
 */
private fun appVersionInfo(context: Context): PackageInfo? = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0)
}.getOrNull()

/** 顶栏那枚 chip 只放版本名（`v0.1.1`），放不下也不该显示成一串号。 */
private fun appVersionLabel(context: Context): String = appVersionInfo(context)?.versionName ?: "-"

/** 关于页那一行带上 versionCode：用户报问题时能一眼定位到具体构建。 */
private fun appVersion(context: Context): String {
    val info = appVersionInfo(context) ?: return "-"
    return "${info.versionName} (${appVersionCode(context)})"
}

/** versionCode 与「检查更新」用的是同一个数：build.gradle 的公式 `major*10000 + minor*100 + patch`。 */
private fun appVersionCode(context: Context): Long {
    val info = appVersionInfo(context) ?: return 0L
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= 33) info.longVersionCode else info.versionCode.toLong()
}

/** 打不开浏览器不是错误路径（可能只是没装浏览器），提示一句就够，不添第二套 UI。 */
private fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { TallyToast.show("打不开浏览器：${it.message ?: "未知原因"}", ToastTone.ERROR) }
}

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
    /** 标签管理：全局跨账本的胶囊墙，与关键词规则同在「归类」组。 */
    onOpenTags: () -> Unit,
    /** 回收站：删除找回从气泡搬到页面，所以入口跟数据放在一起。 */
    onOpenRecycle: () -> Unit,
    /** 记账键盘：记账页备注下面那排 chip 的开关（图片 / 账单时间 / 报销 / 排除 / 标签）。 */
    onOpenBillKeypad: () -> Unit,
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    // 生成 → 落进公共下载目录 → 直接顶系统分享。诊断包不是留着给自己看的东西，
    // 发出去才算完成；写不进去（权限被拒 / 空间不足）就地报错，不躲气泡。
    fun exportDiagnostics() {
        if (exporting) return
        exporting = true
        scope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { Diagnostics.export(context, graph) } }
            exporting = false
            outcome.onSuccess { file ->
                TallyToast.show("诊断包已生成 · ${file.name}", ToastTone.INFO)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, file.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "发送诊断包",
                    ),
                )
            }.onFailure {
                TallyToast.show("诊断包没写出去：${it.message ?: "未知原因"}", ToastTone.ERROR)
            }
        }
    }
    var monthStartPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "设置",
            onBack = onBack,
            trailing = {
                TallyTag(text = "v${appVersionLabel(context)}")
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
                    checked = settings.quickOpenEnabled,
                    onCheckedChange = viewModel::setQuickOpenEnabled,
                    label = "秒开模式",
                    description = if (settings.quickOpenEnabled) {
                        "已开启 · 打开应用直接进记账页"
                    } else {
                        "已关闭 · 打开应用先进首页"
                    },
                )
                SettingsDivider()
                // 记账页备注下面那排 chip 的开关都收进这一页（图片 / 账单时间 / 报销 / 排除 / 标签），
                // 主页面不再一条一条铺开。
                SettingsNavRow(
                    title = "记账键盘",
                    // 不再把四个开关的名字铺在这里：点进去就是那四个，重复一遍只是把副标题撑成两行。
                    // 留一句说清「控的是哪一排」，也顺带和 4×4 金额键盘区分开。
                    subtitle = "备注下面那排按钮的开关",
                    iconKey = "switch",
                    onClick = onOpenBillKeypad,
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

            // 标签与关键词规则都是「账目怎么归类」的配置：低频、不挂某一本账，
            // 所以不跟导入导出混在「数据」里，单独一小段。
            SettingsSection(title = "归类") {
                // 标签是全局的，不挂在某一本账下面；入口放这里，不占抽屉「管理」那一排高频对象。
                SettingsNavRow(
                    title = "标签",
                    subtitle = "跨账本通用 · 点胶囊出操作，长按拖动排序",
                    iconKey = "tag",
                    onClick = onOpenTags,
                )
                SettingsDivider()
                // 关键词规则是语音与截图共用的那一张表，属于「怎么落成账单」而不是数据。
                SettingsNavRow(
                    title = "关键词规则",
                    subtitle = "内置词只读 · 我的规则优先",
                    iconKey = "search",
                    onClick = onOpenKeywordRules,
                )
            }

            // 小莉分组：只有开启后才有金色触点；＋ 右臂那三个字常驻，但没开时只是去设置的路口。
            SettingsSection(title = "小莉（AI）", gold = assistantOn) {
                SettingsSwitchRow(
                    checked = assistantOn,
                    onCheckedChange = viewModel::setAssistantEnabled,
                    label = "小莉（AI）",
                    description = if (assistantOn) {
                        "已开启 · 长按 ＋ 右滑，配好就能聊"
                    } else {
                        "已关闭 · 右滑仍会带你到这里的开关"
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
                SettingsNavRow(
                    title = "导入账单",
                    subtitle = "模板 / 钱迹 / 支付宝 · 选文件后预览与对账",
                    iconKey = "import",
                    onClick = onOpenImport,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "导出账单",
                    subtitle = "14 列 CSV / XLSX · 可原样读回",
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
                VersionRow(
                    version = appVersion(context),
                    enabled = settings.debugMode,
                    checking = state.checkingUpdate,
                    onCheckUpdate = {
                        viewModel.checkForUpdate(appVersionCode(context), appVersionLabel(context))
                    },
                    onToggle = viewModel::setDebugMode,
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "项目主页",
                    subtitle = "https://github.com/guohangbk/tally-app",
                    iconKey = "link",
                    onClick = { openInBrowser(context, PROJECT_HOME) },
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "使用指南",
                    subtitle = "怎么用、从哪儿进，以及每个数字按什么口径算",
                    iconKey = "book",
                    onClick = onOpenGuide,
                )
                SettingsDivider()
                // 诊断包是给作者排障用的，跟版本、机型同一语境；它不是账单数据，不进「数据」组。
                TallyListRow(
                    title = "导出诊断包",
                    subtitle = if (exporting) "正在生成…" else "版本 · 机型 · 日志尾巴 · 不含任何账单内容",
                    iconKey = "note",
                    iconSize = 30,
                    onClick = ::exportDiagnostics,
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

    state.updateAvailable?.let { update ->
        TallyConfirmDialog(
            title = "发现新版本 ${update.latest}",
            message = "当前 v${appVersionLabel(context)}。升级不会影响账单、分类和图片，去 GitHub 看看这次改了什么？",
            confirmLabel = "去下载",
            dismissLabel = "稍后",
            onConfirm = {
                viewModel.dismissUpdate()
                openInBrowser(context, update.pageUrl)
            },
            onDismiss = viewModel::dismissUpdate,
        )
    }

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
            message = "记账键盘（图片 / 账单时间 / 报销 / 排除 / 标签）、主题与显示会回到出厂默认，小莉保持关闭。账单、账本、分类和图片附件都不受影响。",
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
 * 版本这一行是后门：1.2 秒内连点 5 次切换调试模式。
 * 不开调试时它就是一行普通信息，看不出任何入口。
 */
@Composable
private fun VersionRow(
    version: String,
    enabled: Boolean,
    checking: Boolean,
    onCheckUpdate: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text("版本", style = TallyType.listTitle, color = colors.ink2)
            // 版本号放描述位：右侧留给能点的「检查更新」，一行里两个动作不混在一起。
            Text(version, style = TallyType.helper, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        // 软按钮：不抢版本号的注意力，但一眼看得出能点。检查中压暗并吞掉点击。
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(if (checking) colors.card else colors.brandSurface)
                .clickable { if (!checking) onCheckUpdate() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineIcon(
                iconKey = "refresh",
                contentDescription = null,
                size = 11.dp,
                tint = if (checking) colors.ink3 else colors.brand,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = if (checking) "检查中…" else "检查更新",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = if (checking) colors.ink3 else colors.brand,
                maxLines = 1,
            )
        }
        if (enabled) {
            Spacer(Modifier.width(6.dp))
            TallyTag(text = "调试", tone = TagTone.GOLD)
        }
    }
}

private const val PRIVACY_NOTE =    "隐私说明：账单、分类、图片与偏好都存在本地。" +
        "小莉关闭时不会向任何服务发送数据；手动检查更新只查版本号，不带账本内容；密钥不进备份包。"


