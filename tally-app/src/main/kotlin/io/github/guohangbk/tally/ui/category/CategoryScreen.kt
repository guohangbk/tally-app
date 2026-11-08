package io.github.guohangbk.tally.ui.category

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.Category
import io.github.guohangbk.tally.domain.model.CategoryNode
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.Hairline
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlin.math.roundToInt

private val SCREEN_PAD = 14.dp
private val CARD_HEIGHT = 46.dp
private val CARD_ICON = 30.dp
private val TRAY_COLUMNS = 5
private val TILE_ICON = 26.dp
private val BUBBLE_WIDTH = 244.dp
private const val EXPANDED_KEY = "category.expanded.ids"
private const val EXPANDED_SEPARATOR = ","

/**
 * P08 分类管理 · 折叠卡片流（设计板 P08a–P08d）。
 * 点行展开二级纸盘，点 ⋯ 进编辑弹层，长按卡片拖动排序；二级点瓦片上浮气泡。
 */
@Composable
fun CategoryScreen(
    onBack: () -> Unit,
    onAddLevel1: (CategoryType) -> Unit = {},
    onAddLevel2: (String) -> Unit = {},
    onEditIcon: (String) -> Unit = {},
    onOpenCategoryStats: (ledgerId: String?, l1: String?, l2: String?) -> Unit = { _, _, _ -> },
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { CategoryViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = io.github.guohangbk.tally.data.preferences.TallySettings.DEFAULT)
    val colors = tallyColors
    val density = LocalDensity.current
    val cardHeightPx = with(density) { CARD_HEIGHT.toPx() }
    val session = remember { CategoryDragSession() }
    val (expandedIds, toggleExpand) = rememberExpandedIds()
    var bubble by remember { mutableStateOf<BubbleAnchor?>(null) }
    var archivedOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TallyTopBar(
                title = "分类管理",
                onBack = onBack,
                trailing = {
                    TallyIconAction(
                        iconKey = "plus",
                        contentDescription = "新建一级分类",
                        onClick = { onAddLevel1(state.type) },
                    )
                },
            )

            // 提示改成贴底气泡，保留「操作没有生效」这个定性说法。
            // 弹窗或编辑弹层打开时错误归它们所有（「这个分类下还有账单」「名字已被占用」都印在原地），
            // 这里不接手，否则气泡的清空回调会把原地那条错误一起抹掉。
            TallyToastEffects(
                notice = state.notice,
                error = state.error.takeIf { state.dialog == null && state.sheet == null },
                errorTitle = "操作没有生效",
                onNoticeShown = viewModel::dismissNotice,
                onErrorShown = viewModel::dismissError,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SCREEN_PAD),
            ) {
                Spacer(Modifier.height(6.dp))
                TallySegmented(
                    options = listOf(
                        CategoryType.EXPENSE to "支出 ${state.expense.size}",
                        CategoryType.INCOME to "收入 ${state.income.size}",
                    ),
                    selected = state.type,
                    onSelect = {
                        bubble = null
                        viewModel.selectType(it)
                    },
                )
                Spacer(Modifier.height(8.dp))
                if (session.draggedId != null) {
                    DraggingBar(dropLabel = session.targetId?.let { target ->
                        state.tree.firstOrNull { it.category.id == target }?.category?.name
                    })
                } else if (!settings.dragGuideSeen) {
                    DragGuide(onDismiss = viewModel::dismissDragGuide)
                }
                Spacer(Modifier.height(6.dp))

                val tree = state.tree
                when {
                    !state.loaded -> Text("正在载入分类树…", style = TallyType.helper, color = colors.ink3)

                    tree.isEmpty() -> TallyEmptyState(
                        title = "还没有分类",
                        description = "可以补齐系统默认分类，或者直接新建一个一级分类。",
                        iconKey = "note",
                        action = {
                            // 只有全机一个一级分类都没有时才需要补齐；另一侧有分类时新建就够了。
                            if (state.expense.isEmpty() && state.income.isEmpty()) {
                                CategoryGhostButton(label = "补齐默认分类", iconKey = "note", onClick = viewModel::loadDefaultTree)
                            }
                        },
                    )

                    else -> {
                        // 过滤与同级 id 都只跟 tree 有关：放在 forEach 里等于每张卡每次重组都重算一遍。
                        val shown = remember(tree) { tree.filterNot { it.category.isArchived } }
                        val siblingIds = remember(tree) { tree.map { it.category.id } }
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            shown.forEach { node ->
                                val id = node.category.id
                                CategoryCard(
                                    node = node,
                                    siblings = siblingIds,
                                    state = state,
                                    session = session,
                                    cardHeightPx = cardHeightPx,
                                    expanded = expandedIds.contains(id),
                                    bubbleId = bubble?.categoryId,
                                    onToggle = { toggleExpand(id) },
                                    onActions = { viewModel.openEditor(id) },
                                    onAddChild = { onAddLevel2(id) },
                                    onMove = viewModel::move,
                                    onOpenBubble = { child, offset ->
                                        bubble = if (bubble?.categoryId == child.id) null else BubbleAnchor(child.id, offset)
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ArchivedSection(
                    // 归档只作用于自身：单独归档的二级也要能在这里被找到并恢复，
                    // 挂在已归档一级下面的二级不重复列——它们随父级一起出现。
                    archived = remember(state.tree) {
                        state.tree.flatMap { node ->
                            if (node.category.isArchived) {
                                listOf(ArchivedEntry(node.category, null, node.children.size))
                            } else {
                                node.children.filter { it.isArchived }
                                    .map { ArchivedEntry(it, node.category.name, 0) }
                            }
                        }
                    },
                    billCountOf = state::billCount,
                    expanded = archivedOpen,
                    onToggle = { archivedOpen = !archivedOpen },
                    onRestore = { viewModel.setArchived(it, false) },
                    onPurge = viewModel::purge,
                )
                Spacer(Modifier.height(18.dp))
            }
        }

        CategoryDialogHost(state = state, viewModel = viewModel)
        CategorySheetHost(
            sheet = state.sheet,
            state = state,
            onDismiss = viewModel::dismissSheet,
            onEditCategory = onEditIcon,
            onStats = { l1, l2 -> onOpenCategoryStats(null, l1, l2) },
            onMoveBy = viewModel::moveBy,
            onArchive = viewModel::setArchived,
            onDelete = { categoryId -> viewModel.openDialog(CategoryDialogKind.DELETE, categoryId) },
            onAddChild = { parentId -> onAddLevel2(parentId) },
            onMoveParent = { categoryId -> viewModel.openMoveParent(categoryId) },
            onMovedToParent = viewModel::moveToParent,
        )

        bubble?.let { anchor ->
            val child = state.categoryOf(anchor.categoryId)
            if (child == null) {
                bubble = null
            } else {
                CategoryBubble(
                    child = child,
                    parentName = state.categoryOf(child.parentId)?.name,
                    billCount = state.billCount(child.id),
                    anchorOffset = anchor.offset,
                    canMoveUp = run {
                        val siblings = state.siblingIdsOf(child.id)
                        siblings.indexOf(child.id) > 0
                    },
                    canMoveDown = run {
                        val siblings = state.siblingIdsOf(child.id)
                        siblings.indexOf(child.id) < siblings.lastIndex
                    },
                    onDismiss = { bubble = null },
                    onEdit = {
                        bubble = null
                        onEditIcon(child.id)
                    },
                    onStats = {
                        bubble = null
                        onOpenCategoryStats(null, child.parentId, child.id)
                    },
                    onMoveBy = { delta -> viewModel.moveBy(child.id, delta) },
                    onMoveParent = {
                        bubble = null
                        viewModel.openMoveParent(child.id)
                    },
                    onArchive = { archived ->
                        bubble = null
                        viewModel.setArchived(child.id, archived)
                    },
                    onDelete = {
                        bubble = null
                        viewModel.openDialog(CategoryDialogKind.DELETE, child.id)
                    },
                )
            }
        }
    }
}

/** 被选中的二级瓦片：气泡要贴着它画，所以记下瓦片在根坐标系里的位置。 */
private data class BubbleAnchor(val categoryId: String, val offset: IntOffset)

@Composable
private fun CategoryCard(
    node: CategoryNode,
    siblings: List<String>,
    state: CategoryUiState,
    session: CategoryDragSession,
    cardHeightPx: Float,
    expanded: Boolean,
    bubbleId: String?,
    onToggle: () -> Unit,
    onActions: () -> Unit,
    onAddChild: () -> Unit,
    onMove: (String, Int) -> Unit,
    onOpenBubble: (Category, IntOffset) -> Unit,
) {
    val colors = tallyColors
    val category = node.category
    // 单独归档的二级不在这一层出现，它们去「已归档分类」区。
    val visibleChildren = node.children.filterNot { it.isArchived }
    val childCount = visibleChildren.size
    val dragging = session.draggedId == category.id
    val shape = RoundedCornerShape(14.dp)
    val selfIndex = siblings.indexOf(category.id)
    val sourceIndex = session.draggedId?.let { siblings.indexOf(it) } ?: -1
    val isDropTarget = !dragging && sourceIndex >= 0 && session.targetId == category.id
    // 展开态的底色跟二级纸盘一起淡入，不然行先变色、内容后出来，看着像两次跳变。
    val headerTint by animateColorAsState(
        targetValue = if (expanded) colors.brand.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = tween(180),
        label = "分类行底色",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 2f else 0f)
            .offset { IntOffset(0, if (dragging) session.offsetPx.roundToInt() else 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.card)
                .border(
                    width = if (dragging) 1.5.dp else 1.dp,
                    color = if (dragging) colors.gold else colors.line,
                    shape = shape,
                )
                .then(
                    if (dragging) {
                        Modifier.shadow(14.dp, shape, clip = false, ambientColor = colors.gold, spotColor = colors.gold)
                    } else {
                        Modifier
                    },
                )
                .longPressDrag(session, category.id, siblings, cardHeightPx, onMove),
        ) {
            if (isDropTarget && sourceIndex > selfIndex) DropEdge("插入到「${category.name}」之前")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CARD_HEIGHT)
                    .background(headerTint)
                    .clickable(onClick = onToggle)
                    .padding(start = 9.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(
                    iconKey = category.iconKey,
                    label = category.name,
                    size = CARD_ICON,
                    corner = 9.dp,
                    tone = when {
                        category.isArchived -> IconTone.SLATE
                        expanded -> IconTone.INK
                        childCount > 0 -> IconTone.BRAND
                        else -> IconTone.PAPER
                    },
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = category.name,
                    fontSize = 15.sp, lineHeight = 18.8.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (category.isArchived) colors.ink3 else colors.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    text = "${state.billCount(category.id)} 笔",
                    style = TallyType.helper.copy(fontSize = 11.5.sp, lineHeight = 14.4.sp),
                    color = if (expanded) colors.brand else colors.ink3,
                )
                Spacer(Modifier.width(9.dp))
                GhostOperation(iconKey = "more", label = "修改「${category.name}」", onClick = onActions)
                Spacer(Modifier.width(6.dp))
                if (childCount > 0) {
                    GhostOperation(
                        iconKey = "chevron_down",
                        label = if (expanded) "收起二级分类" else "展开二级分类",
                        selected = expanded,
                        rotate = expanded,
                        onClick = onToggle,
                    )
                } else {
                    GhostOperation(iconKey = "plus", label = "给「${category.name}」添加子类", onClick = onAddChild)
                }
            }
            if (isDropTarget && sourceIndex < selfIndex) DropEdge("插入到「${category.name}」之后")
            // 二级纸盘从行下面长出来，不是凭空出现：展开/收起是这页最常用的动作。
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(170, easing = FastOutSlowInEasing)) + fadeOut(tween(110)),
            ) {
                CategoryTray(
                    children = visibleChildren,
                    state = state,
                    bubbleId = bubbleId,
                    onOpenBubble = onOpenBubble,
                    onAddChild = onAddChild,
                )
            }
        }
    }
}

/** 二级纸盘：5 列横排瓦片，末尾固定一个「添加子类」。 */
@Composable
private fun CategoryTray(
    children: List<Category>,
    state: CategoryUiState,
    bubbleId: String?,
    onOpenBubble: (Category, IntOffset) -> Unit,
    onAddChild: () -> Unit,
) {
    val colors = tallyColors
    val cells: List<Category?> = children.map { it } + listOf(null)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 9.dp, end = 9.dp, bottom = 9.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.paper)
            .border(1.dp, colors.ink.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 5.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        cells.chunked(TRAY_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { child ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (child == null) {
                            AddSubTile(onClick = onAddChild)
                        } else {
                            SubCategoryTile(
                                child = child,
                                selected = bubbleId == child.id,
                                onClick = { offset -> onOpenBubble(child, offset) },
                            )
                        }
                    }
                }
                repeat(TRAY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SubCategoryTile(
    child: Category,
    selected: Boolean,
    onClick: (IntOffset) -> Unit,
) {
    val colors = tallyColors
    // 气泡要贴着被点的瓦片画。位置留到点击那一刻现算：
    // 挂在 onGloballyPositioned 里写状态，等于滚动时每个可见瓦片每帧重组一次。
    val anchorRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .onGloballyPositioned { anchorRef[0] = it }
            .clickable {
                val box = anchorRef[0]?.boundsInRoot() ?: return@clickable
                onClick(IntOffset(box.left.roundToInt(), box.bottom.roundToInt()))
            }
            .padding(vertical = 4.dp, horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(
            iconKey = child.iconKey,
            label = child.name,
            size = TILE_ICON,
            corner = 8.dp,
            tone = when {
                selected -> IconTone.BRAND
                child.isArchived -> IconTone.SLATE
                else -> IconTone.PAPER
            },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = child.name,
            fontSize = 11.sp,
            lineHeight = 13.2.sp,
            color = if (selected) colors.ink else if (child.isArchived) colors.ink3 else colors.ink2,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddSubTile(onClick: () -> Unit) {
    val colors = tallyColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TILE_ICON)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.line, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(iconKey = "plus", contentDescription = null, size = 13.dp, tint = colors.ink3)
        }
        Spacer(Modifier.height(3.dp))
        Text("添加子类", fontSize = 11.sp, lineHeight = 13.2.sp, color = colors.ink3, maxLines = 1)
    }
}

@Composable
private fun GhostOperation(
    iconKey: String,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    rotate: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = tallyColors
    // 箭头跟着展开翻面，并且是"转"过去的：纸盘已经会长出来了，箭头却瞬间掉头会显得两套动作。
    val chevronAngle by animateFloatAsState(
        targetValue = if (rotate) 180f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "展开箭头",
    )
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    selected -> colors.ink
                    destructive -> colors.expense.copy(alpha = 0.12f)
                    else -> colors.ink.copy(alpha = 0.05f)
                },
            )
            .clickable(onClick = onClick)
            .semanticsLabel(label),
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(
            iconKey = iconKey,
            contentDescription = null,
            modifier = Modifier.rotate(chevronAngle),
            size = 12.5.dp,
            tint = when {
                selected -> colors.onBrand
                destructive -> colors.expense
                else -> colors.ink3
            },
        )
    }
}

private fun Modifier.semanticsLabel(label: String): Modifier = semantics {
    contentDescription = label
}

@Composable
private fun DropEdge(label: String) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .height(3.dp)
            .background(colors.gold),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = label,
            style = TallyType.helper,
            color = colors.gold,
            modifier = Modifier
                .wrapContentSize()
                .background(colors.glow)
                .padding(horizontal = 6.dp),
        )
    }
}

/** 拖动中的金色状态条，与 P08d 一致。 */
@Composable
private fun DraggingBar(dropLabel: String?) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InlineIcon(iconKey = "drag", contentDescription = null, size = 12.dp, tint = colors.gold)
        Text(
            text = "排序中 · 松手插入金线处${dropLabel?.let { "（$it）" } ?: ""}",
            style = TallyType.helper,
            color = colors.gold,
        )
    }
}

/** 隐藏手势只在第一次进入时交代，看过即消失（规格 §3.1）。 */
@Composable
private fun DragGuide(onDismiss: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(colors.glow)
            .border(1.dp, colors.gold.copy(alpha = 0.3f), RoundedCornerShape(11.dp))
            .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        InlineIcon(iconKey = "drag", contentDescription = null, size = 13.dp, tint = colors.gold)
        Text("长按一级卡片可以拖动排序", style = TallyType.helper, color = colors.onGlow, modifier = Modifier.weight(1f))
        InlineIcon(iconKey = "close", contentDescription = "知道了", size = 13.dp, tint = colors.onGlow, modifier = Modifier.clickable(onClick = onDismiss))
    }
}

/** 页面底部的已归档区：可展开、可恢复，0 笔的才能彻底删除（F4.9）。 */
/** 归档区的一行：自身被归档的分类。`parentName` 非空表示它是单独归档的二级。 */
private data class ArchivedEntry(val category: Category, val parentName: String?, val childCount: Int)

@Composable
private fun ArchivedSection(
    archived: List<ArchivedEntry>,
    billCountOf: (String?) -> Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
) {
    val colors = tallyColors
    if (archived.isEmpty()) return
    var pendingRestore by remember { mutableStateOf<ArchivedEntry?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(colors.slate.copy(alpha = 0.07f))
            .border(1.dp, colors.slate.copy(alpha = 0.34f), RoundedCornerShape(13.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InlineIcon(iconKey = "archive", contentDescription = null, size = 14.dp, tint = colors.slate)
        Column(modifier = Modifier.weight(1f)) {
            Text("已归档分类 · ${archived.size}", style = TallyType.listTitle, color = colors.slate)
            Text("归档只作用于自身 · 历史账单仍计入统计", style = TallyType.helper, color = colors.ink3)
        }
        InlineIcon(
            iconKey = if (expanded) "arrow_up" else "arrow_down",
            contentDescription = if (expanded) "收起" else "展开",
            size = 13.dp,
            tint = colors.slate,
        )
    }
    if (!expanded) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(colors.slate.copy(alpha = 0.04f))
            .border(1.dp, colors.slate.copy(alpha = 0.28f), RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        archived.forEach { entry ->
            val count = billCountOf(entry.category.id)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconTile(
                    iconKey = entry.category.iconKey,
                    label = entry.category.name,
                    size = 24.dp,
                    corner = 7.dp,
                    tone = IconTone.SLATE,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.category.name,
                        style = TallyType.listTitle,
                        color = colors.ink3,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        maxLines = 1,
                    )
                    Text(
                        text = buildString {
                            if (entry.parentName != null) append("在「${entry.parentName}」下 · ")
                            else if (entry.childCount > 0) append("含 ${entry.childCount} 个二级 · ")
                            append(if (count == 0) "0 笔 · 可彻底删除" else "$count 笔")
                        },
                        style = TallyType.helper,
                        color = colors.ink3,
                    )
                }
                CategoryActionButton(label = "恢复", onClick = { pendingRestore = entry })
                if (count == 0) {
                    GhostOperation(iconKey = "trash", label = "彻底删除「${entry.category.name}」", destructive = true, onClick = { onPurge(entry.category.id) })
                }
            }
        }
    }

    // 归档只作用于自身，恢复也就只恢复这一条。
    pendingRestore?.let { entry ->
        TallyConfirmDialog(
            title = "恢复「${entry.category.name}」？",
            message = "恢复后它会重新出现在记账页的分类面板。",
            confirmLabel = "恢复",
            onConfirm = {
                pendingRestore = null
                onRestore(entry.category.id)
            },
            onDismiss = { pendingRestore = null },
        )
    }
}

@Composable
private fun CategoryActionButton(label: String, onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(label, style = TallyType.listTitle.copy(fontSize = 12.sp, lineHeight = 15.sp), color = colors.ink)
    }
}

@Composable
private fun CategoryGhostButton(label: String, iconKey: String, onClick: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        InlineIcon(iconKey = iconKey, contentDescription = null, size = 15.dp, tint = colors.ink2)
        Spacer(Modifier.width(7.dp))
        Text(label, style = TallyType.listTitle, color = colors.ink)
    }
}

@Composable
private fun CategoryDialogHost(
    state: CategoryUiState,
    viewModel: CategoryViewModel,
) {
    val dialog = state.dialog ?: return
    val category = state.categoryOf(dialog.categoryId)
    if (dialog.kind == CategoryDialogKind.DELETE && category != null) {
        CategoryDeleteDialog(
            category = category,
            billCount = state.billCount(category.id),
            childCount = state.childCountOf(category.id),
            targets = state.migrateTargets(category.id),
            error = state.error,
            onDismiss = viewModel::dismissDialog,
            onConfirm = { target -> viewModel.delete(category.id, target) },
        )
    }
}

/**
 * P08c 二级气泡：点瓦片上浮出气泡，尖角指回被选中的那一格。
 * 二级不缩进、不开新页；动作与一级的操作弹层同一套——修改与看数据，加排序、归档、删除。
 */
@Composable
private fun CategoryBubble(
    child: Category,
    parentName: String?,
    billCount: Int,
    anchorOffset: IntOffset,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onStats: () -> Unit,
    onMoveBy: (Int) -> Unit,
    onMoveParent: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = tallyColors
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.roundToInt()
    val bubblePx = with(density) { BUBBLE_WIDTH.toPx() }.roundToInt()
    val pad = with(density) { 8.dp.toPx() }.roundToInt()
    val left = (anchorOffset.x - bubblePx / 3)
        .coerceIn(pad, (screenWidthPx - bubblePx - pad).coerceAtLeast(pad))
    val top = anchorOffset.y + with(density) { 4.dp.toPx() }.roundToInt()
    val shape = RoundedCornerShape(16.dp)

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(left, top),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(BUBBLE_WIDTH)
                .shadow(18.dp, shape, clip = true)
                .background(colors.paper)
                .border(1.dp, colors.line, shape)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    iconKey = child.iconKey,
                    label = child.name,
                    size = 28.dp,
                    corner = 9.dp,
                    tone = if (child.isArchived) IconTone.SLATE else IconTone.BRAND,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = child.name,
                        style = TallyType.listTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                        maxLines = 1,
                    )
                    Text(
                        text = "二级 · 属于「${parentName ?: "未分类"}」 · $billCount 笔",
                        style = TallyType.helper,
                        color = colors.ink3,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    InlineIcon(iconKey = "close", contentDescription = "关闭", size = 13.dp, tint = colors.ink3)
                }
            }
            Spacer(Modifier.height(6.dp))
            Hairline()
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SheetAction(
                    label = "修改",
                    iconKey = "brush",
                    accent = SheetAccent.PRIMARY,
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    cellHeight = 34.dp,
                    corner = 10.dp,
                    iconSize = 13.dp,
                )
                SheetAction(
                    label = "查看统计",
                    iconKey = "chart",
                    onClick = onStats,
                    modifier = Modifier.weight(1f),
                    cellHeight = 34.dp,
                    corner = 10.dp,
                    iconSize = 13.dp,
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SheetAction(
                    label = "上移",
                    iconKey = "arrow_up",
                    enabled = canMoveUp,
                    onClick = { onMoveBy(-1) },
                    modifier = Modifier.weight(1f),
                    cellHeight = 34.dp,
                    corner = 10.dp,
                    iconSize = 13.dp,
                )
                SheetAction(
                    label = "下移",
                    iconKey = "arrow_down",
                    enabled = canMoveDown,
                    onClick = { onMoveBy(1) },
                    modifier = Modifier.weight(1f),
                    cellHeight = 34.dp,
                    corner = 10.dp,
                    iconSize = 13.dp,
                )
            }
            Spacer(Modifier.height(5.dp))
            SheetAction(
                label = "移到其他一级",
                iconKey = "repeat",
                hint = "两级为限",
                wide = true,
                onClick = onMoveParent,
                cellHeight = 34.dp,
                corner = 10.dp,
                iconSize = 13.dp,
            )
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SheetAction(
                    label = if (child.isArchived) "取消归档" else "归档",
                    iconKey = "eye",
                    accent = SheetAccent.MUTED,
                    onClick = { onArchive(!child.isArchived) },
                    modifier = Modifier.weight(1f),
                    cellHeight = 34.dp,
                    corner = 10.dp,
                    iconSize = 13.dp,
                )
                SheetAction(
                    label = "删除",
                    iconKey = "trash",
                    hint = if (billCount > 0) "$billCount 笔" else null,
                    accent = SheetAccent.DANGER,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    cellHeight = 34.dp,
                    corner = 10.dp,
                    iconSize = 13.dp,
                )
            }
        }
    }
}

/** 展开状态跟随导航回退栈保存；ID 为 UUID，用逗号拼接成可保存的 String。 */
@Composable
private fun rememberExpandedIds(): Pair<Set<String>, (String) -> Unit> {
    var serialized by rememberSaveable(EXPANDED_KEY) { mutableStateOf("") }
    val current = if (serialized.isEmpty()) {
        emptySet()
    } else {
        serialized.split(EXPANDED_SEPARATOR).filter { it.isNotBlank() }.toSet()
    }
    val toggle: (String) -> Unit = { id ->
        val next = if (current.contains(id)) current - id else current + id
        serialized = next.joinToString(EXPANDED_SEPARATOR)
    }
    return current to toggle
}
