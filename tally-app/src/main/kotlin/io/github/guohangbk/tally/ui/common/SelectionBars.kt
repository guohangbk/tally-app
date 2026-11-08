package io.github.guohangbk.tally.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.RowScope
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 选择态动作条的壳：深色、顶部大圆角、左上「N 笔 · 金额」、右侧一句提示。
 *
 * 首页批量与回收站共用这一层，动作区由调用方给——两处都画一套深色 bar 的话，
 * 圆角、内边距、字号迟早会分叉成两套。
 */
@Composable
fun SelectionBarShell(
    count: Int,
    amountMinor: Long?,
    hint: String?,
    modifier: Modifier = Modifier,
    caveat: String? = null,
    secondary: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.ink)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$count",
                style = io.github.guohangbk.tally.ui.design.TallyType.moneyBody.copy(fontSize = 18.sp, lineHeight = 22.5.sp),
                color = colors.paper,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (amountMinor != null) "笔 · ${formatAmount(amountMinor)}" else "笔",
                fontSize = 12.sp, lineHeight = 15.sp,
                color = colors.paper,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (hint != null) Text(hint, fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.paper.copy(alpha = 0.6f), maxLines = 1)
        }
        if (caveat != null) {
            Spacer(Modifier.height(5.dp))
            Text(caveat, fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.goldHi, maxLines = 2)
        }
        Spacer(Modifier.height(9.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), content = actions)
        if (secondary != null) {
            Spacer(Modifier.height(5.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), content = secondary)
        }
    }
}

/** 选择态动作条：常驻底部并取代主导航（F2.9）。 */
@Composable
fun SelectionBar(
    count: Int,
    amountMinor: Long,
    hint: String?,
    onSelectCategory: () -> Unit,
    onReimburse: () -> Unit,
    onExclude: () -> Unit,
    onDelete: () -> Unit,
    onDate: () -> Unit,
    onNote: () -> Unit,
    onMove: () -> Unit,
    modifier: Modifier = Modifier,
    categoryBlockedReason: String? = null,
    categoryEnabled: Boolean = true,
    moveEnabled: Boolean = true,
) {
    SelectionBarShell(
        count = count,
        amountMinor = amountMinor,
        hint = hint,
        modifier = modifier,
        caveat = categoryBlockedReason?.let { "改分类：$it" },
        secondary = {
            BarAction("calendar", "改日期", onClick = onDate)
            BarAction("note", "改备注", onClick = onNote)
            BarAction("switch", "移至账本", enabled = moveEnabled, onClick = onMove)
        },
    ) {
        BarAction("tag", "改分类", highlight = true, enabled = categoryEnabled, onClick = onSelectCategory)
        BarAction("receipt", "报销", onClick = onReimburse)
        BarAction("eyeoff", "不计入", onClick = onExclude)
        BarAction("trash", "删除", danger = true, onClick = onDelete)
    }
}

@Composable
internal fun RowScope.BarAction(
    iconKey: String,
    label: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(10.dp)
    val background = when {
        !enabled -> colors.paper.copy(alpha = 0.06f)
        highlight -> colors.gold
        danger -> colors.expense.copy(alpha = 0.34f)
        else -> colors.paper.copy(alpha = 0.12f)
    }
    val foreground = when {
        !enabled -> colors.paper.copy(alpha = 0.3f)
        highlight -> colors.ink
        else -> colors.paper
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .height(34.dp)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InlineIcon(iconKey = iconKey, contentDescription = label, size = 12.dp, tint = foreground)
        Spacer(Modifier.height(1.dp))
        Text(label, fontSize = 10.5.sp, lineHeight = 13.1.sp, fontWeight = FontWeight.Bold, color = foreground)
    }
}

/**
 * 批量动作的弹层：分类、日期、备注、账本四件。
 *
 * 首页和搜索页本来就是同一套批量语义（§5），弹层再各画一份就会分叉，
 * 所以两边都只调这一个入口。
 */
@Composable
fun BillBatchOverlays(selection: BatchState, batch: BillBatchViewModel) {
    if (selection.showCategoryPicker) {
        CategoryPickerSheet(
            categories = selection.categories,
            selectedL1 = null,
            selectedL2 = null,
            onDismiss = batch::closeCategoryPicker,
            onConfirm = { l1, l2 -> batch.applyCategory(l1, l2) },
            confirmLabel = "应用到 ${selection.selected.size} 笔",
        )
    }

    if (selection.showDatePicker) {
        TallyDatePickerDialog(
            initialDate = selection.dateAnchor,
            onDismiss = batch::closeDatePicker,
            onConfirm = batch::applyDate,
        )
    }

    if (selection.showNoteEditor) {
        BatchNoteDialog(
            count = selection.selected.size,
            notedCount = selection.notedCount,
            onDismiss = batch::closeNoteEditor,
            onConfirm = batch::applyNote,
        )
    }

    if (selection.showLedgerPicker) {
        BatchLedgerSheet(
            options = selection.ledgerOptions,
            sourceIds = selection.sourceLedgerIds,
            generatedCount = selection.generatedCount,
            onDismiss = batch::closeLedgerPicker,
            onPick = batch::applyLedger,
        )
    }
}

/** 备注是整批覆盖，所以落笔前先把「有几笔会被盖掉」说清楚；留空即清空。 */
@Composable
private fun BatchNoteDialog(
    count: Int,
    notedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = tallyColors
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("改备注", style = TallyType.screenTitle.copy(fontSize = 16.sp, lineHeight = 20.sp), color = colors.ink, modifier = Modifier.weight(1f))
                InlineIcon(
                    iconKey = "close",
                    contentDescription = "关闭",
                    size = 14.dp,
                    tint = colors.ink3,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.paper)
                    .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 10.dp),
            ) {
                if (text.isEmpty()) Text("备注内容，例如「信用卡还款」", style = TallyType.body, color = colors.ink3)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TallyType.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.brand),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = when {
                    text.isBlank() -> "留空保存会清掉这 $count 笔的备注"
                    notedCount > 0 -> "将覆盖其中 $notedCount 笔已有的备注"
                    else -> "写入这 $count 笔"
                },
                style = TallyType.helper,
                color = if (text.isNotBlank() && notedCount > 0) colors.slate else colors.ink3,
            )
            Spacer(Modifier.height(12.dp))
            TallyButton(
                label = "应用到 $count 笔",
                onClick = { onConfirm(text) },
                variant = ButtonVariant.BRAND,
                height = 34,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 迁移目标只有没归档的账本；源账本上标「已在」，因为选中的这几笔可能本来就住在那儿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchLedgerSheet(
    options: List<LedgerEntity>,
    sourceIds: Set<String>,
    generatedCount: Int,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = tallyColors
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("移到其他账本", style = TallyType.sectionTitle, color = colors.ink)
            if (generatedCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "周期 / 分期生成的 $generatedCount 笔仍留在原计划里，不跟着搬。",
                    style = TallyType.helper,
                    color = colors.slate,
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { ledger ->
                    TallyListRow(
                        title = ledger.name,
                        subtitle = if (ledger.isDefault) "默认账本" else null,
                        iconKey = ledger.iconKey,
                        onClick = { onPick(ledger.id) },
                        trailing = if (ledger.id in sourceIds) {
                            { TallyTag(text = "已在", tone = TagTone.OUTLINE) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** 批量提交进度。 */
@Composable
fun BatchProgress(label: String, modifier: Modifier = Modifier) {
    val colors = tallyColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(colors.glow)
            .border(1.dp, colors.gold.copy(alpha = 0.3f), RoundedCornerShape(11.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(colors.gold))
        Text(label, fontSize = 11.5.sp, lineHeight = 14.4.sp, color = colors.onGlow, fontWeight = FontWeight.SemiBold)
    }
}
