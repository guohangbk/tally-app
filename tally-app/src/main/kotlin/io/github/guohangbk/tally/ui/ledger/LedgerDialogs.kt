package io.github.guohangbk.tally.ui.ledger

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.data.repository.DeleteDisposition
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.IconLibrary
import io.github.guohangbk.tally.ui.design.IconMeta
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.RowGap
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
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
            LedgerDialogKind.CREATE -> LedgerEditorSheet(
                title = "新建账本",
                confirmLabel = "创建",
                initialName = "",
                initialIcon = "ledger",
                reservedNames = state.items.map { it.ledger.name },
                makeDefaultAvailable = false,
                error = error,
                onCancel = viewModel::dismissDialog,
                onSubmit = { name, iconKey, _ -> viewModel.create(name, iconKey) },
            )

            LedgerDialogKind.EDIT -> {
                val ledger = pendingItem!!.ledger
                LedgerEditorSheet(
                    title = "编辑账本",
                    confirmLabel = "保存",
                    initialName = ledger.name,
                    initialIcon = ledger.iconKey,
                    reservedNames = state.items.filter { it.ledger.id != ledger.id }.map { it.ledger.name },
                    // 当前默认账本没有「取消默认」这条路；已结算的账本也不该被重新推成默认，开关都不出现。
                    makeDefaultAvailable = !ledger.isDefault && !ledger.isArchived,
                    error = error,
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
private fun LedgerEditorSheet(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialIcon: String,
    reservedNames: List<String>,
    makeDefaultAvailable: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSubmit: (name: String, iconKey: String, makeDefault: Boolean) -> Unit,
) {
    val colors = tallyColors
    var name by remember { mutableStateOf(initialName) }
    var iconKey by remember { mutableStateOf(initialIcon) }
    var makeDefault by remember { mutableStateOf(false) }
    var showIcons by remember { mutableStateOf(false) }
    var iconQuery by remember { mutableStateOf("") }
    // 打开时落在当前图标所在那组：编辑场景下先去翻「出行」比从「收入」翻起省事。
    var iconGroup by remember { mutableStateOf(IconLibrary.groupOf(initialIcon) ?: IconLibrary.groupNames.first()) }
    val trimmed = name.trim()
    val duplicated = trimmed.isNotEmpty() && reservedNames.any { it == trimmed }

    TallyBottomSheet(onDismissRequest = onCancel, containerColor = colors.card) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        ) {
            Text(
                text = title,
                style = TallyType.screenTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = colors.ink,
            )
            Spacer(Modifier.height(12.dp))
            LedgerNameField(
                value = name,
                onValueChange = { name = it },
                placeholder = "账本名称，例如「日常账本」",
                // 新建时名字是空的，直接落键盘；编辑时名字现成，弹键盘反而把图标行挤走。
                autoFocus = initialName.isEmpty(),
            )
            Spacer(Modifier.height(9.dp))
            LedgerIconRow(
                iconKey = iconKey,
                expanded = showIcons,
                onToggle = { showIcons = !showIcons },
            )
            AnimatedVisibility(
                visible = showIcons,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(160)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    LedgerIconLibrary(
                        query = iconQuery,
                        onQueryChange = { iconQuery = it },
                        group = iconGroup,
                        onGroupChange = {
                            iconGroup = it
                            iconQuery = ""
                        },
                        selected = iconKey,
                        onSelect = { iconKey = it },
                    )
                }
            }
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
    autoFocus: Boolean,
) {
    val colors = tallyColors
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
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
private fun LedgerIconRow(iconKey: String, expanded: Boolean, onToggle: () -> Unit) {
    val colors = tallyColors
    val meta = IconLibrary.meta(iconKey)
    val haptics = rememberTallyHaptics()
    // 箭头跟着展开状态转，同一次点击里就能看出「打开了 / 收起了」。
    val arrow by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "ledgerIconArrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.brandSurface)
            .border(1.dp, colors.brand.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .clickable {
                haptics.tap()
                onToggle()
            }
            .padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // 与账本卡同一张纸面瓦：选完图标这里立刻换脸，不用保存后回列表验证。
        IconTile(iconKey = iconKey, label = meta?.displayName ?: "账本", size = 34.dp, corner = 10.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "图标",
                style = TallyType.helper.copy(fontSize = 9.5.sp, lineHeight = 11.9.sp, fontWeight = FontWeight.Bold),
                color = colors.ink,
            )
            Text(
                text = meta?.let { "${it.displayName} · ${it.group}" } ?: "不在图标库里",
                style = TallyType.helper,
                color = colors.ink3,
            )
        }
        // 整行就是入口，右端一枚 chevron 说「点这里展开」；展开后箭头转向上。
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(
                iconKey = "chevron_down",
                contentDescription = if (expanded) "收起图标库" else "展开图标库",
                size = 14.dp,
                tint = colors.brand,
                modifier = Modifier.rotate(arrow),
            )
        }
    }
}

/**
 * 账本弹层里就地展开的全量图标库：搜索、12 组筛选、四列网格。
 * 网格自己封顶并在内部滚动——展开后弹层不该把整屏吃掉。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedgerIconLibrary(
    query: String,
    onQueryChange: (String) -> Unit,
    group: String,
    onGroupChange: (String) -> Unit,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = tallyColors
    val results = remember(query, group) {
        val q = query.trim()
        if (q.isEmpty()) IconLibrary.library.filter { it.group == group } else IconLibrary.search(q)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        IconSearchField(value = query, onValueChange = onQueryChange)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconLibrary.groupNames.forEach { name ->
                // 输入了搜索词时结果是全库的，任何一组都不算「当前」。
                val active = query.isBlank() && name == group
                Text(
                    text = name,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) colors.paper else colors.ink2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) colors.ink else colors.card)
                        .border(1.dp, if (active) colors.ink else colors.line, RoundedCornerShape(20.dp))
                        .clickable { onGroupChange(name) }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 210.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (results.isEmpty()) {
                Text(
                    text = "图标库里没有「${query.trim()}」",
                    style = TallyType.helper,
                    color = colors.ink3,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else {
                results.chunked(5).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { meta ->
                            Box(modifier = Modifier.weight(1f)) {
                                LedgerIconTile(
                                    meta = meta,
                                    selected = meta.key == selected,
                                    onClick = { onSelect(meta.key) },
                                )
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconSearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.paper)
            .border(1.dp, colors.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(iconKey = "search", contentDescription = null, size = 12.dp, tint = colors.ink3)
        Spacer(Modifier.size(5.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("搜索图标或分组", fontSize = 10.5.sp, lineHeight = 13.sp, color = colors.ink3)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TallyType.helper.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            InlineIcon(
                iconKey = "close",
                contentDescription = "清空",
                size = 11.dp,
                tint = colors.ink3,
                modifier = Modifier.clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun LedgerIconTile(meta: IconMeta, selected: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    val shape = RoundedCornerShape(9.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(shape)
                .background(if (selected) colors.ink else colors.paper)
                .border(1.dp, if (selected) colors.ink else colors.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            CategoryGlyph(
                iconKey = meta.key,
                label = meta.displayName,
                size = 18.dp,
                tint = if (selected) colors.paper else colors.ink,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = meta.displayName,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = if (selected) colors.ink else colors.ink2,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
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
