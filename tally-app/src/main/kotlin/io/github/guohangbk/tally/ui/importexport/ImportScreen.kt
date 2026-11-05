package io.github.guohangbk.tally.ui.importexport

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.DuplicateRow
import io.github.guohangbk.tally.domain.model.ImportBatchStatus
import io.github.guohangbk.tally.domain.model.ImportPreview
import io.github.guohangbk.tally.domain.model.ImportSourceType
import io.github.guohangbk.tally.ui.common.formatDate
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlinx.coroutines.launch

/**
 * P09 导入向导：点卡片即进系统文件管理；「按模板导入」先问一句要不要先把模板存到手机。
 * 主操作固定在底部通栏——「下一步 · 校验表头 / 开始导入」，不在页面中间摆一串按钮。
 */
@Composable
fun ImportScreen(onBack: () -> Unit, onGoHome: () -> Unit = onBack) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val viewModel = tallyViewModel { ImportViewModel(graph, context.applicationContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    var advanced by remember { mutableStateOf(false) }
    var askTemplate by remember { mutableStateOf(false) }
    var askDuplicates by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            advanced = false
            viewModel.selectFile(uri)
        }
    }

    val stage = when {
        state.outcome != null -> 2
        advanced && state.preview != null -> 1
        else -> 0
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "导入账单", onBack = onBack)

        // 提示改成贴底气泡，保留「导入没有完成」这个定性说法。
        // 下面的校验报告是逐行结果，属于内容，仍留在页面里。
        TallyToastEffects(
            notice = null,
            error = state.error,
            errorTitle = "导入没有完成",
            onErrorShown = viewModel::clearError,
        )

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
            StepBar(stage, state.fileName, state.preview?.totalRows)
            state.savedNotice?.let { where ->
                Text("模板已存到 $where", style = TallyType.helper, color = colors.brand)
                Spacer(Modifier.height(8.dp))
            }
            when (stage) {
                0 -> SourceStep(
                    state = state,
                    onPick = { source ->
                        viewModel.selectSource(source)
                        // 模板这一路先问一句：多数人手上还没有模板文件；存过一次就不再啰嗦。
                        if (source == ImportSourceType.TEMPLATE && state.savedNotice == null) askTemplate = true
                        else openPicker(picker)
                    },
                )

                1 -> PreviewSection(
                    state = state,
                    viewModel = viewModel,
                    onToggleDedupe = { enabled ->
                        if (enabled || (state.preview?.duplicateRows ?: 0) == 0) viewModel.setDedupe(enabled)
                        else askDuplicates = true
                    },
                )
                // 「返回账单」得真的回到账单：这一页可能是从引导页 push 上来的，
                // 单纯 popBackStack 会把他退回引导页，那颗按钮就在骗人。
                else -> ReportSection(state, onDone = { advanced = false; viewModel.reset() }, onBack = onGoHome)
            }
            Spacer(Modifier.height(20.dp))
        }

        if (stage != 2) {
            ImportFooter(
                stage = stage,
                state = state,
                onBack = { advanced = false },
                onForward = { advanced = true },
                onImport = viewModel::importNow,
            )
        }
    }

    if (askTemplate) {
        TallyConfirmDialog(
            title = "先把模板存到手机？",
            message = "12 列 CSV 模板会写进「下载/Tally」。已经导好文件了就直接选。",
            confirmLabel = "下载模板",
            dismissLabel = "否，直接导入",
            onConfirm = {
                askTemplate = false
                viewModel.saveTemplate()
            },
            onDismiss = {
                askTemplate = false
                openPicker(picker)
            },
        )
    }

    if (askDuplicates) {
        TallyConfirmDialog(
            title = "仍然导入 ${state.preview?.duplicateRows ?: 0} 笔重复？",
            message = "同一笔会出现两条，支出和收入合计都会变大。",
            confirmLabel = "仍然导入",
            dismissLabel = "继续跳过",
            destructive = true,
            onConfirm = {
                askDuplicates = false
                viewModel.setDedupe(false)
            },
            onDismiss = { askDuplicates = false },
        )
    }
}

// 不能只给 text/csv：各家文件管理器给 .csv 标的 MIME 五花八门
// （text/comma-separated-values、application/vnd.ms-excel 都有），
// 按类型筛会把文件整个藏起来，选完由解析器自己认表头。
private fun openPicker(picker: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    picker.launch(arrayOf("*/*"))
}

/** 底部常驻通栏主操作：没选到文件之前「下一步」是灰的，选完就能直接进预览。 */
@Composable
private fun ImportFooter(
    stage: Int,
    state: ImportUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onImport: () -> Unit,
) {
    val colors = tallyColors
    val preview = state.preview
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper)
            .border(1.dp, colors.line)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (stage == 0) {
            TallyButton(
                label = if (state.busy) "正在解析…" else "下一步 · 校验表头",
                onClick = onForward,
                variant = ButtonVariant.BRAND,
                height = 48,
                enabled = preview != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TallyButton(
                label = if (state.busy) "正在导入…" else "开始导入",
                onClick = onImport,
                variant = ButtonVariant.BRAND,
                height = 48,
                enabled = !state.busy && preview != null &&
                    (if (state.dedupe) preview.importableRows else preview.validRows) > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            TallyButton(
                label = "上一步",
                onClick = onBack,
                variant = ButtonVariant.GHOST,
                height = 40,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SourceStep(state: ImportUiState, onPick: (ImportSourceType) -> Unit) {
    val colors = tallyColors
    Column {
        SourceCard(
            title = "按模板导入",
            subtitle = "CSV · 12 列标准表头 · 含 ID 可回读",
            selected = state.sourceType == ImportSourceType.TEMPLATE,
            onClick = { onPick(ImportSourceType.TEMPLATE) },
        )
        SourceCard(
            title = "钱迹数据导入",
            subtitle = "19 列 · ID 首列 · 报销记录自动排除统计",
            selected = state.sourceType == ImportSourceType.QIANJI,
            badge = "推荐",
            onClick = { onPick(ImportSourceType.QIANJI) },
        )
        SourceCard(
            title = "支付宝数据导入",
            subtitle = "GB18030 · 自动跳过前 8 行 · 必须指定账本",
            selected = state.sourceType == ImportSourceType.ALIPAY,
            onClick = { onPick(ImportSourceType.ALIPAY) },
        )
        state.fileName?.let { name ->
            Spacer(Modifier.height(6.dp))
            Text("已选文件：$name", style = TallyType.helper, color = colors.ink2)
        }
    }
}

/** 三步而不是六步：文件是在选来源那一下一起选掉的，中间没有独立一步。 */
@Composable
private fun StepBar(stage: Int, fileName: String?, totalRows: Int?) {
    val colors = tallyColors
    val labels = listOf("来源与文件", "预览与目标账本", "对账报告")
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            labels.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index <= stage) colors.brand else colors.line),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        val detail = if (stage == 0) fileName?.let { " · 已识别" } ?: " · 未选择文件" else if (totalRows != null) " · $totalRows 行" else ""
        Text("${stage + 1} / 3 · ${labels[stage]}$detail", style = TallyType.helper, color = colors.ink3)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SourceCard(title: String, subtitle: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.brandSurface else colors.card)
            .border(1.dp, if (selected) colors.brand.copy(alpha = 0.42f) else colors.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = TallyType.listTitle, color = colors.ink)
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    TallyTag(text = badge, tone = TagTone.GOLD)
                }
            }
            Text(subtitle, style = TallyType.helper, color = colors.ink3)
        }
        // 选中用一个勾说明就够了；未选中什么都不放，卡片本身就是可点的。
        if (selected) {
            InlineIcon(iconKey = "check", contentDescription = null, size = 15.dp, tint = colors.brand)
        }
    }
}

@Composable
private fun PreviewSection(state: ImportUiState, viewModel: ImportViewModel, onToggleDedupe: (Boolean) -> Unit) {
    val colors = tallyColors
    val preview = state.preview ?: return
    Column {
        Text(state.fileName ?: "已选择文件", style = TallyType.listTitle, color = colors.ink)
        Text(
            "编码 ${preview.encoding} · 表头第 ${preview.headerRow} 行 · ${preview.totalRows} 行",
            style = TallyType.helper,
            color = colors.ink3,
        )
        Spacer(Modifier.height(8.dp))
        TallyCard(corner = 15, contentPadding = 10.dp) {
            Row {
                StatCell("${if (state.dedupe) preview.importableRows else preview.validRows}", "可导入")
                StatCell("${preview.duplicateRows}", "重复", colors.gold)
                StatCell("${preview.invalidRows}", "异常行", colors.expense)
                StatCell("${preview.reimbursementRows}", "报销记录", colors.slate)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("导入到", style = TallyType.helper, color = colors.ink3)
        Spacer(Modifier.height(5.dp))
        // 账本数量没有上限，横向滚动全部列出，不要只给四个然后让人以为只有四个。
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.ledgers.forEach { item ->
                ChoicePill(item.ledger.name, state.targetLedgerId == item.ledger.id) { viewModel.selectTargetLedger(item.ledger.id) }
            }
        }
        Spacer(Modifier.height(10.dp))
        TallyCard(corner = 15, contentPadding = 8.dp) {
            Column {
                preview.previewRows.take(6).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.occurredAt?.let(::formatDate) ?: "—", style = TallyType.helper, color = colors.ink3, modifier = Modifier.width(78.dp))
                        Text(row.categoryL1Name ?: "未分类", style = TallyType.helper, color = colors.ink, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            text = (if (row.type == io.github.guohangbk.tally.domain.model.BillType.INCOME) "+" else "−") + formatAmount(row.amountMinor ?: 0L),
                            style = TallyType.moneyList.copy(fontSize = 11.sp, lineHeight = 13.8.sp),
                            color = if (row.type == io.github.guohangbk.tally.domain.model.BillType.INCOME) colors.income else colors.expense,
                        )
                    }
                }
                if (preview.errorRows.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("异常行：第 ${preview.errorRows.take(3).joinToString("、") { it.rowNumber.toString() }} 行", style = TallyType.helper, color = colors.expense)
                }
            }
        }
        if (preview.duplicateRows > 0) {
            Spacer(Modifier.height(8.dp))
            DuplicateBlock(preview)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("重复账单", style = TallyType.helper, color = colors.ink2, modifier = Modifier.weight(1f))
            TallySegmented(
                options = listOf(true to "跳过", false to "仍然导入"),
                selected = state.dedupe,
                onSelect = onToggleDedupe,
                modifier = Modifier.width(136.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("跳过异常行继续", style = TallyType.helper, color = colors.ink2, modifier = Modifier.weight(1f))
            TallySegmented(
                options = listOf(false to "回滚", true to "跳过"),
                selected = state.skipInvalid,
                onSelect = viewModel::setSkipInvalid,
                modifier = Modifier.width(96.dp),
            )
        }
    }
}

@Composable
private fun DuplicateBlock(preview: ImportPreview) {
    val colors = tallyColors
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, colors.gold.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineIcon(iconKey = "repeat", contentDescription = null, size = 14.dp, tint = colors.gold)
            Spacer(Modifier.width(7.dp))
            val imported = preview.duplicateItems.count { !it.withinFile }
            val withinFile = preview.duplicateRows - imported
            Text(
                text = "${preview.duplicateRows} 笔重复 · 已导过 $imported · 文件内 $withinFile",
                style = TallyType.helper,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            InlineIcon(
                iconKey = "chevron_down",
                contentDescription = if (expanded) "收起重复明细" else "展开重复明细",
                size = 12.dp,
                tint = colors.ink3,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                preview.duplicateItems.take(20).forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.row.occurredAt?.let(::formatDate) ?: "—", style = TallyType.helper, color = colors.ink3, modifier = Modifier.width(78.dp))
                        Text(
                            text = item.row.note?.takeIf { it.isNotBlank() } ?: item.row.categoryL1Name ?: "未分类",
                            style = TallyType.helper,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatAmount(item.row.amountMinor ?: 0L),
                            style = TallyType.helper,
                            color = colors.ink2,
                            modifier = Modifier.width(70.dp).padding(end = 6.dp),
                            textAlign = TextAlign.End,
                        )
                        TallyTag(text = item.duplicateLabel(), tone = TagTone.OUTLINE)
                    }
                }
            }
        }
    }
}

private fun DuplicateRow.duplicateLabel(): String =
    if (withinFile) "文件内重复" else "已导过"

@Composable
private fun RowScope.StatCell(value: String, label: String, color: androidx.compose.ui.graphics.Color? = null) {
    val colors = tallyColors
    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = TallyType.moneyList, color = color ?: colors.ink)
        Text(label, style = TallyType.helper, color = colors.ink3, fontSize = 10.sp, lineHeight = 12.5.sp)
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.brandSurface else colors.card)
            .border(1.dp, if (selected) colors.brand.copy(alpha = 0.42f) else colors.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (selected) InlineIcon(iconKey = "check", contentDescription = null, size = 12.dp, tint = colors.brand)
        Text(label, style = TallyType.helper, color = if (selected) colors.brand else colors.ink2, maxLines = 1)
    }
}

@Composable
private fun ReportSection(state: ImportUiState, onDone: () -> Unit, onBack: () -> Unit) {
    val colors = tallyColors
    val outcome = state.outcome ?: return
    val report = outcome.report
    val balanced = report.status == ImportBatchStatus.SUCCESS
    val success = !outcome.rolledBack && balanced
    val title = when {
        outcome.rolledBack -> "导入未完成"
        balanced -> "导入完成"
        else -> "已写入，对账不平"
    }
    Column(modifier = Modifier.alpha(if (state.busy) 0.5f else 1f)) {
        TallyCard(
            corner = 16,
            contentPadding = 14.dp,
            borderColor = if (success) colors.brand.copy(alpha = 0.3f) else colors.expense.copy(alpha = 0.35f),
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = TallyType.sectionTitle, color = if (success) colors.brand else colors.expense)
                Text(
                    "共 ${report.totalRows} 行 · 写入 ${report.importedRows} · 跳过 ${report.skippedRows} · 异常 ${report.failedRows}",
                    style = TallyType.helper,
                    color = colors.ink3,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TallyCard(corner = 15, contentPadding = 8.dp) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("月份", style = TallyType.helper, color = colors.ink3, modifier = Modifier.weight(1f))
                    Text("笔数", style = TallyType.helper, color = colors.ink3, modifier = Modifier.width(46.dp))
                    Text("支出差异", style = TallyType.helper, color = colors.ink3, modifier = Modifier.width(78.dp))
                }
                report.months.forEach { month ->
                    val diff = month.importedExpenseMinor - month.sourceExpenseMinor
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(month.monthKey, style = TallyType.helper, color = colors.ink, modifier = Modifier.weight(1f))
                        Text("${month.importedCount}/${month.sourceCount}", style = TallyType.helper, color = colors.ink2, modifier = Modifier.width(46.dp))
                        Text(
                            text = if (diff == 0L) "0.00 ✓" else formatAmount(diff),
                            style = TallyType.helper,
                            color = if (diff == 0L) colors.brand else colors.expense,
                            modifier = Modifier.width(78.dp),
                        )
                    }
                }
                report.ignoredColumns.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("忽略列：${it.joinToString(" / ")}", style = TallyType.helper, color = colors.ink3)
                }
                report.message?.let {
                    Text(it, style = TallyType.helper, color = colors.ink2)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            TallyButton(
                label = "再导入一个文件",
                onClick = onDone,
                variant = ButtonVariant.BRAND,
                height = 48,
                modifier = Modifier.fillMaxWidth(),
            )
            TallyButton(
                label = "返回账单",
                onClick = onBack,
                variant = ButtonVariant.GHOST,
                height = 40,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
