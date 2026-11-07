package io.github.guohangbk.tally.ui.ledger

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.github.guohangbk.tally.data.repository.DeleteDisposition
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.IconLibrary
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.RowGap
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyLabeledSwitch
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.design.tallyColors

private enum class BillDisposal {
    MIGRATE,
    DELETE_BILLS,
}

@Composable
internal fun LedgerDialogHost(
    state: LedgerUiState,
    viewModel: LedgerViewModel,
    onPickLedgerIcon: () -> Unit,
) {
    val dialog = state.dialog ?: return
    val pendingItem = state.itemOf(dialog.ledgerId)
    if (dialog.kind != LedgerDialogKind.CREATE && pendingItem == null) {
        // 账本已经不在列表里（例如刚被删除），弹窗没有存在的对象。
        LaunchedEffect(dialog) { viewModel.dismissDialog() }
        return
    }
    val error = state.error

    // key(dialog) 让每次重新打开弹窗都回到初始输入，而不是沿用上一次的草稿。
    key(dialog) {
        when (dialog.kind) {
            LedgerDialogKind.CREATE -> LedgerEditorDialog(
                title = "新建账本",
                confirmLabel = "创建",
                initialName = "",
                initialIcon = state.pendingIcon ?: "ledger",
                reservedNames = state.items.map { it.ledger.name },
                makeDefaultAvailable = false,
                error = error,
                onPickIcon = onPickLedgerIcon,
                onCancel = viewModel::dismissDialog,
                onSubmit = { name, iconKey, _ -> viewModel.create(name, iconKey) },
            )

            LedgerDialogKind.EDIT -> {
                val ledger = pendingItem!!.ledger
                LedgerEditorDialog(
                    title = "编辑账本",
                    confirmLabel = "保存",
                    initialName = ledger.name,
                    initialIcon = state.pendingIcon ?: ledger.iconKey,
                    reservedNames = state.items.filter { it.ledger.id != ledger.id }.map { it.ledger.name },
                    // 当前默认账本没有「取消默认」这条路；已结算的账本也不该被重新推成默认，开关都不出现。
                    makeDefaultAvailable = !ledger.isDefault && !ledger.isArchived,
                    error = error,
                    onPickIcon = onPickLedgerIcon,
                    onCancel = viewModel::dismissDialog,
                    onSubmit = { name, iconKey, makeDefault -> viewModel.update(ledger.id, name, iconKey, makeDefault) },
                )
            }

            LedgerDialogKind.DELETE -> {
                val source = pendingItem!!
                LedgerDeleteDialog(
                    item = source,
                    grouping = state.settings.showThousandsSeparator,
                    // 已结算的账本不能当迁移目标：结算就是锁定，搬进去等于往里记新账。
                    otherLedgers = state.items.filter { it.ledger.id != source.ledger.id && !it.ledger.isArchived },
                    error = error,
                    onCancel = viewModel::dismissDialog,
                    onConfirm = { disposition -> viewModel.delete(source.ledger.id, disposition) },
                )
            }
        }
    }
}

@Composable
private fun LedgerEditorDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialIcon: String,
    reservedNames: List<String>,
    makeDefaultAvailable: Boolean,
    error: String?,
    onPickIcon: () -> Unit,
    onCancel: () -> Unit,
    onSubmit: (name: String, iconKey: String, makeDefault: Boolean) -> Unit,
) {
    val colors = tallyColors
    var name by remember { mutableStateOf(initialName) }
    var iconKey by remember { mutableStateOf(initialIcon) }
    var makeDefault by remember { mutableStateOf(false) }
    // 从图标库选完返回时 initialIcon 会换成新键：不跟着回填，用户就看不到自己刚选的图标。
    LaunchedEffect(initialIcon) { iconKey = initialIcon }
    val trimmed = name.trim()
    val duplicated = trimmed.isNotEmpty() && reservedNames.any { it == trimmed }

    Dialog(onDismissRequest = onCancel) {
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
                Text(title, style = TallyType.screenTitle.copy(fontSize = 16.sp, lineHeight = 20.sp), color = colors.ink, modifier = Modifier.weight(1f))
                InlineIcon(
                    iconKey = "close",
                    contentDescription = "关闭",
                    size = 14.dp,
                    tint = colors.ink3,
                    modifier = Modifier.clickable(onClick = onCancel),
                )
            }
            Spacer(Modifier.height(14.dp))
            LedgerNameField(
                value = name,
                onValueChange = { name = it },
                placeholder = "账本名称，例如「日常账本」",
            )
            Spacer(Modifier.height(9.dp))
            LedgerIconRow(iconKey = iconKey, onPickIcon = onPickIcon)
            if (makeDefaultAvailable) {
                TallyLabeledSwitch(
                    checked = makeDefault,
                    onCheckedChange = { makeDefault = it },
                    label = "设为默认账本",
                )
            }
            if (duplicated) {
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "已存在同名账本，建议换个名字便于区分。",
                    style = TallyType.helper,
                    color = colors.slate,
                )
            }
            if (error != null) {
                Spacer(Modifier.height(7.dp))
                LedgerDialogError(error)
            }
            Spacer(Modifier.height(12.dp))
            TallyButton(
                label = confirmLabel,
                onClick = { onSubmit(trimmed, iconKey, makeDefault) },
                variant = ButtonVariant.BRAND,
                height = 34,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LedgerDeleteDialog(
    item: LedgerListItem,
    grouping: Boolean,
    otherLedgers: List<LedgerListItem>,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: (DeleteDisposition) -> Unit,
) {
    val colors = tallyColors
    val ledger = item.ledger
    val billCount = item.stat.billCount
    var disposal by remember { mutableStateOf(BillDisposal.MIGRATE) }
    var targetId by remember { mutableStateOf<String?>(null) }

    val target = targetId
    val disposition: DeleteDisposition = when {
        disposal == BillDisposal.DELETE_BILLS -> DeleteDisposition.DeleteBills
        target != null -> DeleteDisposition.Migrate(target)
        else -> DeleteDisposition.RequireChoice
    }

    TallyConfirmDialog(
        title = "删除「${ledger.name}」",
        message = when {
            ledger.isDefault -> TallyError.DefaultLedgerProtected.message + "，它的账单不受影响。"
            billCount == 0 -> "这个账本还没有账单，删除后不可恢复。"
            else -> "这个账本有 $billCount 笔账单，必须先决定它们去哪里。"
        },
        confirmLabel = "删除账本",
        destructive = true,
        onConfirm = { onConfirm(disposition) },
        onDismiss = onCancel,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (!ledger.isDefault && billCount > 0) {
                    TallySegmented(
                        options = listOf(
                            BillDisposal.MIGRATE to "迁移到其他账本",
                            BillDisposal.DELETE_BILLS to "连同账单删除",
                        ),
                        selected = disposal,
                        onSelect = { disposal = it },
                    )
                    when (disposal) {
                        BillDisposal.MIGRATE -> MigrationTargetList(
                            candidates = otherLedgers,
                            selectedId = targetId,
                            grouping = grouping,
                            onSelect = { targetId = it },
                        )

                        BillDisposal.DELETE_BILLS -> Text(
                            text = "这 $billCount 笔账单会一起消失，无法恢复。",
                            style = TallyType.listTitle,
                            color = colors.expense,
                        )
                    }
                }
                LedgerDialogError(error)
            }
        },
    )
}

@Composable
private fun MigrationTargetList(
    candidates: List<LedgerListItem>,
    selectedId: String?,
    grouping: Boolean,
    onSelect: (String) -> Unit,
) {
    val colors = tallyColors
    if (candidates.isEmpty()) {
        Text(
            text = "没有其他账本可以接收，只能连同账单一起删除。",
            style = TallyType.helper,
            color = colors.slate,
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 190.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        candidates.forEach { candidate ->
            val selected = candidate.ledger.id == selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.brandSurface else colors.paper)
                    .border(
                        width = 1.dp,
                        color = if (selected) colors.brand.copy(alpha = 0.45f) else colors.line,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(candidate.ledger.id) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LedgerRadio(
                    selected = selected,
                    label = "迁移到「${candidate.ledger.name}」",
                    onClick = { onSelect(candidate.ledger.id) },
                )
                RowGap(9)
                Column(modifier = Modifier.weight(1f)) {
                    Text(candidate.ledger.name, style = TallyType.listTitle, color = colors.ink)
                    Text(
                        text = "${candidate.stat.billCount} 笔 · ${formatAmount(candidate.stat.expenseMinor, grouping)}",
                        style = TallyType.helper,
                        color = colors.ink3,
                    )
                }
                if (candidate.ledger.isDefault) {
                    TallyTag(text = "默认", tone = TagTone.BRAND)
                }
            }
        }
    }
}

@Composable
private fun LedgerNameField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = tallyColors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = TallyType.body, color = colors.ink3)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TallyType.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.brand),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun LedgerIconRow(iconKey: String, onPickIcon: () -> Unit) {
    val colors = tallyColors
    val meta = IconLibrary.meta(iconKey)
    val haptics = rememberTallyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.brandSurface)
            .border(1.dp, colors.brand.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .clickable {
                haptics.tap()
                onPickIcon()
            }
            .padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // 与账本卡同一张纸面瓦：选完图标这里立刻换脸，不用保存后回列表验证。
        IconTile(iconKey = iconKey, label = meta?.displayName ?: "账本", size = 34.dp, corner = 10.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text("图标", style = TallyType.helper.copy(fontSize = 9.5.sp, lineHeight = 11.9.sp, fontWeight = FontWeight.Bold), color = colors.ink)
            Text(
                text = meta?.let { "${it.displayName} · ${it.group}" } ?: "不在图标库里",
                style = TallyType.helper,
                color = colors.ink3,
            )
        }
        // 整行就是入口，右端一枚 ⇄ 说「点这里换」，不再摆一个写着「换图标」的按钮。
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(iconKey = "switch", contentDescription = "换图标", size = 14.dp, tint = colors.brand)
        }
    }
}

@Composable
private fun LedgerDialogError(message: String?) {
    if (message == null) return
    Text(
        text = message,
        style = TallyType.listTitle,
        fontWeight = FontWeight.Medium,
        color = tallyColors.expense,
    )
}
