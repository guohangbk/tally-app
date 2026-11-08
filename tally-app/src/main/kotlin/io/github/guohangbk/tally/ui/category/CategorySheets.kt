package io.github.guohangbk.tally.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.guohangbk.tally.domain.model.Category
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.ui.design.Hairline
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/** 弹层总入口：分类操作、二级改挂父级。改名与换图标都在全屏修改页完成。 */
@Composable
internal fun CategorySheetHost(
    sheet: CategorySheet?,
    state: CategoryUiState,
    onDismiss: () -> Unit,
    onEditCategory: (categoryId: String) -> Unit,
    onStats: (l1: String?, l2: String?) -> Unit,
    onMoveBy: (categoryId: String, delta: Int) -> Unit,
    onArchive: (categoryId: String, archived: Boolean) -> Unit,
    onDelete: (categoryId: String) -> Unit,
    onAddChild: (parentId: String) -> Unit,
    onMoveParent: (categoryId: String) -> Unit,
    onMovedToParent: (categoryId: String, newParentId: String) -> Unit,
) {
    when (sheet?.kind) {
        CategorySheetKind.EDITOR -> {
            val target = state.categoryOf(sheet.categoryId)
            if (target == null) {
                // 弹层只管动作，打开期间被删的分类没有可操作对象。
                onDismiss()
            } else {
                val siblings = state.siblingIdsOf(sheet.categoryId)
                val index = siblings.indexOf(sheet.categoryId)
                TallyBottomSheet(onDismissRequest = onDismiss, containerColor = tallyColors.card) {
                    CategoryEditorSheet(
                        category = target,
                        type = target.type,
                        childCount = state.childCountOf(target.id),
                        billCount = state.billCount(target.id),
                        canMoveUp = index > 0,
                        canMoveDown = index in 0 until siblings.lastIndex,
                        error = state.error,
                        onDismiss = onDismiss,
                        onEdit = { onEditCategory(target.id) },
                        onStats = {
                            // 一级看它自己，二级连父级一起看：搜索页按 l1 / l2 两个维度收。
                            onStats(target.parentId ?: target.id, target.parentId?.let { target.id })
                        },
                        onMoveBy = { delta -> onMoveBy(target.id, delta) },
                        onArchive = { onArchive(target.id, !target.isArchived) },
                        onDelete = { onDelete(target.id) },
                        onAddChild = { onAddChild(target.id) },
                        onMoveParent = { onMoveParent(target.id) },
                    )
                }
            }
        }

        CategorySheetKind.MOVE_PARENT -> {
            val target = state.categoryOf(sheet.categoryId)
            if (target == null) {
                onDismiss()
            } else {
                TallyBottomSheet(onDismissRequest = onDismiss, containerColor = tallyColors.card) {
                    MoveParentSheet(
                        child = target,
                        parents = state.moveParents(target.id),
                        onSelect = { parentId -> onMovedToParent(target.id, parentId) },
                    )
                }
            }
        }

        null -> {}
    }
}

/**
 * P08b 操作弹层：只做动作分发。改名与换图标去全屏修改页——那里有整库、搜索和批量，
 * 弹层里塞一行输入框加一排图标，两头都别扭（F7）。
 */
@Composable
private fun ColumnScope.CategoryEditorSheet(
    category: Category,
    type: CategoryType,
    childCount: Int,
    billCount: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onStats: () -> Unit,
    onMoveBy: (delta: Int) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onAddChild: () -> Unit,
    onMoveParent: () -> Unit,
) {
    val colors = tallyColors
    val isLevel1 = category.parentId == null

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                iconKey = category.iconKey,
                label = category.name,
                size = 38.dp,
                corner = 12.dp,
                tone = IconTone.INK,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = TallyType.listTitle,
                    color = colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(type.label())
                        append(if (isLevel1) " · 一级 · " else " · 二级 · ")
                        if (isLevel1) append("$childCount 个二级 · ")
                        append("$billCount 笔")
                    },
                    style = TallyType.helper,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(11.dp))

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // 三个要跳页的动作先收弹层：回来时不该还压着一层弹层。
            SheetAction(
                label = "修改",
                iconKey = "brush",
                hint = "名称与图标",
                accent = SheetAccent.PRIMARY,
                wide = true,
                onClick = {
                    onDismiss()
                    onEdit()
                },
            )
            SheetAction(
                label = "查看统计数据",
                iconKey = "chart",
                hint = "$billCount 笔",
                wide = true,
                onClick = {
                    onDismiss()
                    onStats()
                },
            )
            if (isLevel1) {
                SheetAction(
                    label = "添加二级分类",
                    iconKey = "plus",
                    hint = "现有 $childCount 个",
                    wide = true,
                    onClick = {
                        onDismiss()
                        onAddChild()
                    },
                )
            } else {
                SheetAction(
                    label = "移到其他一级",
                    iconKey = "repeat",
                    hint = "两级为限",
                    wide = true,
                    onClick = onMoveParent,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SheetAction(
                    label = "上移",
                    iconKey = "arrow_up",
                    enabled = canMoveUp,
                    onClick = { onMoveBy(-1) },
                    modifier = Modifier.weight(1f),
                )
                SheetAction(
                    label = "下移",
                    iconKey = "arrow_down",
                    enabled = canMoveDown,
                    onClick = { onMoveBy(1) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SheetAction(
                    label = if (category.isArchived) "取消归档" else "归档",
                    iconKey = "eye",
                    accent = SheetAccent.MUTED,
                    onClick = onArchive,
                    modifier = Modifier.weight(1f),
                )
                SheetAction(
                    label = "删除",
                    iconKey = "trash",
                    hint = if (billCount > 0) "$billCount 笔待迁移" else null,
                    accent = SheetAccent.DANGER,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
            error?.let {
                Spacer(Modifier.height(9.dp))
                Text(it, style = TallyType.helper, color = colors.expense)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

internal enum class SheetAccent { DEFAULT, PRIMARY, MUTED, DANGER }

@Composable
internal fun SheetAction(
    label: String,
    iconKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    accent: SheetAccent = SheetAccent.DEFAULT,
    wide: Boolean = false,
    enabled: Boolean = true,
    cellHeight: Dp = 42.dp,
    corner: Dp = 12.dp,
    iconSize: Dp = 16.dp,
) {
    val colors = tallyColors
    val background = when (accent) {
        SheetAccent.PRIMARY -> colors.brand
        SheetAccent.DANGER -> colors.expenseSurface
        else -> colors.card
    }
    val borderColor = when (accent) {
        SheetAccent.PRIMARY -> colors.brand
        SheetAccent.DANGER -> colors.expense.copy(alpha = 0.3f)
        else -> colors.line
    }
    val foreground = when (accent) {
        SheetAccent.PRIMARY -> colors.onBrand
        SheetAccent.DANGER -> colors.expense
        SheetAccent.MUTED -> colors.ink3
        else -> colors.ink
    }
    val alpha = if (enabled) 1f else colors.disabledAlpha
    val shape = RoundedCornerShape(corner)
    Row(
        modifier = modifier
            .then(if (wide) Modifier.fillMaxWidth() else Modifier)
            .height(cellHeight)
            .clip(shape)
            .background(background.copy(alpha = background.alpha * alpha))
            .border(1.dp, borderColor.copy(alpha = borderColor.alpha * alpha), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(iconKey = iconKey, contentDescription = null, size = iconSize, tint = foreground.copy(alpha = alpha))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = TallyType.listTitle,
            fontWeight = FontWeight.SemiBold,
            color = foreground.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hint != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = hint,
                style = TallyType.helper,
                color = foreground.copy(alpha = alpha * 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun MoveParentSheet(
    child: Category,
    parents: List<Category>,
    onSelect: (String) -> Unit,
) {
    val colors = tallyColors
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("移到其他一级", style = TallyType.screenTitle, color = colors.ink)
        Text(
            text = "把「${child.name}」挂到另一个一级分类下 · 两级为上限",
            style = TallyType.helper,
            color = colors.ink3,
        )
        Spacer(Modifier.height(6.dp))
        if (parents.isEmpty()) {
            Text("已经没有可选的一级分类了。", style = TallyType.body, color = colors.ink3)
        }
        Column(modifier = Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
            parents.forEach { parent ->
                TallyListRow(
                    title = parent.name,
                    subtitle = "${parent.type.label()} · ${parent.iconKey}",
                    iconKey = parent.iconKey,
                    iconTone = if (parent.iconKey == child.iconKey) IconTone.BRAND else IconTone.PAPER,
                    onClick = { onSelect(parent.id) },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
