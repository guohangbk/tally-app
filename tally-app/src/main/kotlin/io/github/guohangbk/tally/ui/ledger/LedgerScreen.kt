package io.github.guohangbk.tally.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.ui.design.CardActionTone
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.MoneySign
import io.github.guohangbk.tally.ui.design.MoneyText
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyCardAction
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallyMenuEntry
import io.github.guohangbk.tally.ui.design.TallyMoreMenu
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** P07 我的账本：列表外露账单数与最近记账时间，操作全部就地反馈。 */
@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel,
    onBack: () -> Unit,
    onPickLedgerIcon: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "我的账本",
            onBack = onBack,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TallyTag(text = "${state.totalBillCount} 笔合计")
                    TallyIconAction(
                        iconKey = "plus",
                        contentDescription = "新建账本",
                        onClick = { viewModel.openDialog(LedgerDialogKind.CREATE) },
                    )
                }
            },
        )

        // 提示改成贴底气泡，保留「这一步没有完成」这个定性说法。
        // 弹窗打开时错误属于弹窗内容，不在外面再弹一次。
        TallyToastEffects(
            notice = null,
            error = state.error.takeIf { state.dialog == null },
            errorTitle = "这一步没有完成",
            onErrorShown = viewModel::dismissError,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 15.dp, end = 15.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (state.loaded && state.items.isEmpty()) {
                TallyEmptyState(
                    title = "还没有账本",
                    description = "账本是账单的容器，先建一个日常账本，默认分类会一并生成。",
                    iconKey = "ledger",
                )
            }

            state.active.forEach { item ->
                LedgerCard(
                    item = item,
                    grouping = state.settings.showThousandsSeparator,
                    actions = LedgerCardActions(
                        onSetDefault = { viewModel.setDefault(item.ledger.id) },
                        onEdit = { viewModel.openDialog(LedgerDialogKind.EDIT, item.ledger.id) },
                        onToggleArchive = { viewModel.setArchived(item.ledger.id, archived = true) },
                        onDelete = { viewModel.openDialog(LedgerDialogKind.DELETE, item.ledger.id) },
                    ),
                )
            }

            if (state.archived.isNotEmpty()) {
                TallySectionLabel("已归档 ${state.archived.size}")
                state.archived.forEach { item ->
                    LedgerCard(
                        item = item,
                        grouping = state.settings.showThousandsSeparator,
                        actions = LedgerCardActions(
                            onSetDefault = { viewModel.setDefault(item.ledger.id) },
                            onEdit = { viewModel.openDialog(LedgerDialogKind.EDIT, item.ledger.id) },
                            onToggleArchive = { viewModel.setArchived(item.ledger.id, archived = false) },
                            onDelete = { viewModel.openDialog(LedgerDialogKind.DELETE, item.ledger.id) },
                        ),
                    )
                }
            }
        }
    }

    LedgerDialogHost(state, viewModel, onPickLedgerIcon)
}

private class LedgerCardActions(
    val onSetDefault: () -> Unit,
    val onEdit: () -> Unit,
    val onToggleArchive: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * 账本卡（走查第 8 条统一语法）：主行给身份与支出，meta 给笔数与最近记账，
 * 动作固定四格「默认 · 编辑 · 归档 · ⋯」，删除收进 ⋯。
 * 右侧那个数**已经是不计入「不计入收支」的口径**（`BillDao.observeLedgerStats` 的 `excluded_from_stats = 0`），
 * 所以这一版不再单独显示「不计入收支」——它是口径，不是要人再看一眼的第二笔钱。
 */
@Composable
private fun LedgerCard(
    item: LedgerListItem,
    grouping: Boolean,
    actions: LedgerCardActions,
) {
    val colors = tallyColors
    val ledger = item.ledger
    val archived = ledger.isArchived
    TallyCard(
        corner = 16,
        contentPadding = 12.dp,
        borderColor = if (ledger.isDefault) colors.brand.copy(alpha = 0.32f) else colors.line,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(
                    iconKey = ledger.iconKey,
                    label = ledger.name,
                    size = 40.dp,
                    corner = 13.dp,
                    tone = if (ledger.isDefault && !archived) IconTone.BRAND else IconTone.PAPER,
                )
                Text(
                    text = ledger.name,
                    style = TallyType.listTitle.copy(fontSize = 15.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = if (archived) colors.ink2 else colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MoneyText(
                    amountMinor = item.stat.expenseMinor,
                    sign = MoneySign.NONE,
                    style = TallyType.moneyBody.copy(fontSize = 22.sp, lineHeight = 24.sp),
                    color = when {
                        archived -> colors.ink2
                        item.isEmpty -> colors.ink3
                        else -> colors.expense
                    },
                    grouping = grouping,
                    showCurrency = false,
                )
            }
            Spacer(Modifier.height(5.dp))
            when {
                archived -> LedgerStatusRow(
                    tag = { TallyTag(text = "已归档", tone = TagTone.SLATE) },
                    right = "${item.stat.billCount} 笔" +
                        (item.stat.lastOccurredAt?.let { " · 最近 ${formatLastBillTime(it)}" } ?: ""),
                    rightColor = colors.ink3,
                )

                item.isEmpty -> LedgerStatusRow(
                    tag = { TallyTag(text = "空账本", tone = TagTone.GOLD) },
                    right = "${formatCreated(ledger.createdAt)} 建立",
                    rightColor = colors.ink3,
                )

                else -> Text(
                    text = "${item.stat.billCount} 笔 · 最近 ${formatLastBillTime(item.stat.lastOccurredAt ?: ledger.createdAt)}",
                    style = TallyType.helper,
                    color = colors.ink3,
                    maxLines = 1,
                )
            }
            Box(Modifier.fillMaxWidth().height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line.copy(alpha = 0.6f)))
            Box(Modifier.fillMaxWidth().height(9.dp))
            if (archived) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TallyCardAction(
                        label = "编辑",
                        iconKey = "pencil",
                        onClick = actions.onEdit,
                        modifier = Modifier.weight(1f),
                    )
                    TallyCardAction(
                        label = "取消归档",
                        iconKey = "archive",
                        tone = CardActionTone.BRAND,
                        onClick = actions.onToggleArchive,
                        modifier = Modifier.weight(1f),
                    )
                    TallyMoreMenu(
                        entries = listOf(TallyMenuEntry("删除账本", danger = true, onClick = actions.onDelete)),
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TallyCardAction(
                        label = if (ledger.isDefault) "默认" else "设为默认",
                        onClick = actions.onSetDefault,
                        enabled = !ledger.isDefault,
                        tone = if (ledger.isDefault) CardActionTone.BRAND else CardActionTone.NEUTRAL,
                        leading = { DefaultDot(selected = ledger.isDefault) },
                        modifier = Modifier.weight(1f),
                    )
                    TallyCardAction(
                        label = "编辑",
                        iconKey = "pencil",
                        onClick = actions.onEdit,
                        modifier = Modifier.weight(1f),
                    )
                    TallyCardAction(
                        label = "归档",
                        iconKey = "archive",
                        onClick = actions.onToggleArchive,
                        // 默认账本不能归档：先把默认身份交出去。
                        enabled = !ledger.isDefault,
                        modifier = Modifier.weight(1f),
                    )
                    TallyMoreMenu(
                        entries = listOf(TallyMenuEntry("删除账本", danger = true, onClick = actions.onDelete)),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 状态行：左边最多一枚标签，右边只一个锚点。 */
@Composable
private fun LedgerStatusRow(tag: @Composable () -> Unit, right: String, rightColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        tag()
        Spacer(Modifier.weight(1f))
        Text(right, fontSize = 11.5.sp, lineHeight = 15.sp, color = rightColor, maxLines = 1)
    }
}

/**
 * 默认身份的小圆点：选中 = 墨绿实心带纸心，未选 = 描边空心。
 * 26dp 的单选圈塞不进 32dp 的动作格，而这一格本来就是「点它就成了默认」，圆点足以表达唯一性。
 */
@Composable
private fun DefaultDot(selected: Boolean) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .size(13.dp)
            .clip(CircleShape)
            .background(if (selected) colors.brand else colors.paper)
            .border(1.4.dp, if (selected) colors.brand else colors.ink3, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(colors.onBrand))
        }
    }
}

/** 单选圆圈：既表达「默认账本全局唯一」，也用于删除时挑选迁移目标；图标字形未交付，选中态用实心点而不是 check。 */
@Composable
internal fun LedgerRadio(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(enabled = !selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .clip(CircleShape)
                .background(if (selected) colors.brand else colors.card)
                .border(
                    width = 1.6.dp,
                    color = if (selected) colors.brand else colors.ink3,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.onBrand),
                )
            }
        }
    }
}

/** 跨年的账本要带年份，否则「最近 01-05」会被误读成本年。 */
private fun formatLastBillTime(timestamp: Long): String {
    val bill = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val pattern = if (bill.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

/** 空账本唯一可看的数就是它是什么时候建的：右侧锚点用「10-02 建立」。 */
private fun formatCreated(timestamp: Long): String =
    SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
