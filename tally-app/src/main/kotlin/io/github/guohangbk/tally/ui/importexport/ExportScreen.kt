package io.github.guohangbk.tally.ui.importexport

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.storage.DownloadedFile
import io.github.guohangbk.tally.data.storage.PublicDownloads
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.ExportFormat
import io.github.guohangbk.tally.domain.model.ExportOptions
import io.github.guohangbk.tally.ui.common.TallyDatePickerDialog
import io.github.guohangbk.tally.ui.common.formatDate
import io.github.guohangbk.tally.ui.common.formatMonth
import io.github.guohangbk.tally.ui.common.monthStartOf
import io.github.guohangbk.tally.ui.common.shiftMonthStart
import io.github.guohangbk.tally.ui.common.startOfDay
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import java.time.Instant
import java.time.ZoneId

private enum class ExportRange { MONTH, YEAR, ALL, CUSTOM }

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

/**
 * P11 导出账单：账本、时间范围（含自定义区间）、收支类型都在这屏决定。
 * 主操作是底部通栏的「导出」——文件写进公共「下载/Tally」，位置当场写在屏上；
 * 分享只是旁边的可选动作，不再是导出的唯一出口。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = tallyViewModel { ExportViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = tallyColors

    var format by remember { mutableStateOf(ExportFormat.CSV) }
    var range by remember { mutableStateOf(ExportRange.MONTH) }
    var billType by remember { mutableStateOf<BillType?>(null) }
    var ledgerId by remember { mutableStateOf<String?>(null) }
    var includeImages by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<DownloadedFile?>(null) }
    var rowCount by remember { mutableStateOf(0) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var customStart by remember { mutableStateOf(monthStartOf()) }
    var customEnd by remember { mutableStateOf(startOfDay(System.currentTimeMillis())) }

    val zone = ZoneId.systemDefault()
    val monthStart = monthStartOf()
    val (startAt, endAtExclusive) = when (range) {
        ExportRange.MONTH -> monthStart to shiftMonthStart(monthStart, 1)
        ExportRange.YEAR -> {
            val start = Instant.ofEpochMilli(monthStart).atZone(zone)
                .toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
            start to shiftMonthStart(start, 12)
        }

        ExportRange.ALL -> null to null
        ExportRange.CUSTOM -> customStart to customEnd + DAY_MILLIS
    }

    fun share(file: DownloadedFile) {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = if (format == ExportFormat.CSV) {
                        "text/csv"
                    } else {
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    }
                    putExtra(Intent.EXTRA_STREAM, file.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享账单导出",
            ),
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "导出账单", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Field("格式") {
                TallySegmented(
                    options = listOf(ExportFormat.CSV to "CSV", ExportFormat.XLSX to "XLSX"),
                    selected = format,
                    onSelect = { format = it },
                )
            }
            Field("账本") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Choice("全部账本", ledgerId == null) { ledgerId = null }
                    state.ledgers.filter { !it.ledger.isArchived }.forEach { item ->
                        Choice(item.ledger.name, ledgerId == item.ledger.id) { ledgerId = item.ledger.id }
                    }
                }
            }
            Field("时间范围") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Choice("本月", range == ExportRange.MONTH) { range = ExportRange.MONTH }
                    Choice("本年", range == ExportRange.YEAR) { range = ExportRange.YEAR }
                    Choice("全部时间", range == ExportRange.ALL) { range = ExportRange.ALL }
                    Choice("自定义", range == ExportRange.CUSTOM) { range = ExportRange.CUSTOM }
                }
                if (range == ExportRange.CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DateChip(label = "起", value = formatDate(customStart), onClick = { pickStart = true })
                        DateChip(label = "止", value = formatDate(customEnd), onClick = { pickEnd = true })
                    }
                } else {
                    Text(
                        when (range) {
                            ExportRange.MONTH -> formatMonth(monthStart)
                            ExportRange.YEAR -> "${Instant.ofEpochMilli(monthStart).atZone(zone).year} 年 1 月 - 12 月"
                            ExportRange.ALL -> "从第一笔到今天"
                            ExportRange.CUSTOM -> ""
                        },
                        style = TallyType.helper,
                        color = colors.ink3,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
            Field("类型") {
                val typeOptions: List<Pair<BillType?, String>> =
                    listOf(null to "全部", BillType.EXPENSE to "支出", BillType.INCOME to "收入")
                TallySegmented(
                    options = typeOptions,
                    selected = billType,
                    onSelect = { billType = it },
                )
            }
            Field("账单图片") {
                // 选项自己把话说明白：这里不再另起一行灰字解释「相对路径」。
                TallySegmented(
                    options = listOf(false to "不写图片列", true to "写图片路径列"),
                    selected = includeImages,
                    onSelect = { includeImages = it },
                )
            }
            Field("13 列标准字段") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    EXPORT_COLUMNS.forEach { name -> ColumnChip(name) }
                }
            }
            failure?.let { Text(it, style = TallyType.helper, color = colors.expense) }
            saved?.let { file ->
                TallyCard(corner = 13, contentPadding = 10.dp, borderColor = colors.brand.copy(alpha = 0.32f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InlineIcon(iconKey = "check", contentDescription = null, size = 14.dp, tint = colors.brand)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("已存到 ${file.location}", style = TallyType.listTitle, color = colors.ink)
                            Text("$rowCount 行 · ${formatSize(file.sizeBytes)}", style = TallyType.helper, color = colors.ink3)
                        }
                        TallyButton(label = "分享", onClick = { share(file) }, variant = ButtonVariant.GHOST, height = 32)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.paper)
                .border(1.dp, colors.line)
                .padding(horizontal = 15.dp, vertical = 10.dp),
        ) {
            TallyButton(
                label = if (busy) "正在导出…" else "导出",
                height = 48,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        failure = null
                        saved = null
                        try {
                            val result = withContext(Dispatchers.IO) {
                                graph.exportService.export(
                                    ExportOptions(
                                        format = format,
                                        ledgerId = ledgerId,
                                        startAt = startAt,
                                        endAtExclusive = endAtExclusive,
                                        type = billType,
                                        includeImages = includeImages,
                                    ),
                                )
                            }
                            saved = withContext(Dispatchers.IO) {
                                PublicDownloads.write(context, result.fileName, result.mimeType, result.bytes)
                            }
                            rowCount = result.rowCount
                        } catch (error: Exception) {
                            failure = error.message ?: "导出失败，请检查存储空间"
                        } finally {
                            busy = false
                        }
                    }
                },
                variant = ButtonVariant.BRAND,
                enabled = !busy,
            )
        }
    }

    if (pickStart) {
        TallyDatePickerDialog(
            initialDate = customStart,
            onDismiss = { pickStart = false },
            onConfirm = { millis ->
                customStart = startOfDay(millis)
                if (customEnd < customStart) customEnd = customStart
                pickStart = false
            },
        )
    }
    if (pickEnd) {
        TallyDatePickerDialog(
            initialDate = customEnd,
            onDismiss = { pickEnd = false },
            onConfirm = { millis ->
                customEnd = startOfDay(millis)
                if (customEnd < customStart) customStart = customEnd
                pickEnd = false
            },
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val EXPORT_COLUMNS = listOf(
    "ID", "时间", "账本", "分类", "二级分类", "类型", "金额", "币种", "备注", "已报销", "记账者", "账单图片", "关联账单",
)

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    val colors = tallyColors
    Column {
        Text(label, style = TallyType.helper, color = colors.ink3, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.brandSurface else colors.card)
            .border(1.dp, if (selected) colors.brand.copy(alpha = 0.42f) else colors.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (selected) InlineIcon(iconKey = "check", contentDescription = null, size = 12.dp, tint = colors.brand)
        Text(label, style = TallyType.helper, color = if (selected) colors.brand else colors.ink2, maxLines = 1)
    }
}

@Composable
private fun DateChip(label: String, value: String, onClick: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = TallyType.helper, color = colors.ink3)
        Text(value, style = TallyType.helper, color = colors.ink, fontWeight = FontWeight.Medium, maxLines = 1)
        InlineIcon(iconKey = "calendar", contentDescription = "选择日期", size = 13.dp, tint = colors.ink3)
    }
}

@Composable
private fun ColumnChip(name: String) {
    val colors = tallyColors
    Text(
        text = name,
        style = TallyType.helper.copy(fontSize = 11.sp, lineHeight = 15.sp),
        color = colors.ink2,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
