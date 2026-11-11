package io.github.guohangbk.tally.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.ui.bill.EntryChip
import io.github.guohangbk.tally.ui.common.formatClock
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * 设置 → 记账键盘：记账页备注下面那一排 chip，开哪几个由这里定。
 *
 * 页顶先摆一排**同一个** EntryChip 做示例（只是不响应点击），拨完开关当场能看出
 * 那一行会变成什么样——示例与真身共用一份画法，改了一边另一边不会漂。
 *
 * 「标签」同样归这一页：它也是备注下面的一枚 chip。
 */
@Composable
fun BillKeypadSettingsScreen(
    onBack: () -> Unit,
    onManageTags: () -> Unit,
) {
    val graph = rememberAppGraph()
    val colors = tallyColors
    val viewModel = tallyViewModel(key = "bill-keypad-settings") { SettingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val withClock = settings.timePrecision == TimePrecision.DATETIME

    TallyToastEffects(
        notice = state.notice,
        error = state.error,
        errorTitle = "这项设置没有保存",
        onNoticeShown = viewModel::dismissNotice,
        onErrorShown = viewModel::dismissError,
    )

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "记账键盘", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "记账界面备注下方显示的按钮",
                style = TallyType.helper,
                color = colors.ink3,
                modifier = Modifier.padding(bottom = 9.dp),
            )

            TallyCard(corner = 16, contentPadding = 12.dp) {
                // 窄屏或长日期文案下会折成两行，和记账页 dock 一样用 FlowRow 兜住。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    EntryChip(
                        label = if (withClock) "今天 ${formatClock(System.currentTimeMillis())}" else "今天",
                        onClick = {},
                        interactive = false,
                    )
                    if (settings.billImageEnabled) {
                        EntryChip(label = "图片", onClick = {}, interactive = false)
                    }
                    if (settings.reimburseEnabled) {
                        EntryChip(label = "报销", onClick = {}, interactive = false)
                    }
                    if (settings.excludeEnabled) {
                        EntryChip(label = "排除", onClick = {}, interactive = false)
                    }
                    if (settings.tagEnabled) {
                        EntryChip(label = "标签", onClick = {}, interactive = false)
                    }
                }
            }

            SettingsSection(title = "按钮") {
                SettingsSwitchRow(
                    checked = settings.billImageEnabled,
                    onCheckedChange = viewModel::setBillImageEnabled,
                    label = "账单图片",
                    description = "记账时可以为这一笔加一张图片",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = withClock,
                    onCheckedChange = { on ->
                        viewModel.setTimePrecision(if (on) TimePrecision.DATETIME else TimePrecision.DATE)
                    },
                    label = "账单时间",
                    description = "记账可以精确到具体时间",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.reimburseEnabled,
                    onCheckedChange = viewModel::setReimburseEnabled,
                    label = "报销",
                    description = "抽屉与记账页都出现报销",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.excludeEnabled,
                    onCheckedChange = viewModel::setExcludeEnabled,
                    label = "排除",
                    description = "可把某笔标为不计入，不进收支统计与预算",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = settings.tagEnabled,
                    onCheckedChange = viewModel::setTagEnabled,
                    label = "标签",
                    description = "把账目归到标签，按标签统计",
                    // 开关只管记账页那枚 chip；管理标签不跟着开关走，入口就挂在说明末尾。
                    descriptionAction = {
                        SettingsInlineLink("管理标签 ›", onManageTags)
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}
