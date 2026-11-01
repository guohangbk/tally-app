package io.github.guohangbk.tally.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.ui.design.BottomSheetGrabber
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 全应用唯一的分类选择器：记账页内嵌、批量改分类、周期与分期创建都走这一套瓦片与气泡。
 * 一级与二级同一套规格——二级必须是「图标 + 名称」，不允许纯文字胶囊（AC-F1-7）。
 */
private const val COLUMNS = 5

/** [CategoryPanel] 的一次布局结果：整份都按 (categories, selectedL1) 记住，避免每次重组重排这棵树。 */
private class CategoryLayout(
    val parents: List<CategoryEntity>,
    val activeIndex: Int,
    val children: List<CategoryEntity>,
    val rows: List<List<CategoryEntity>>,
)

@Composable
fun CategoryPanel(
    categories: List<CategoryEntity>,
    selectedL1: String?,
    selectedL2: String?,
    onSelect: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    onAddSubCategory: ((String) -> Unit)? = null,
) {
    // 这棵树 30 多个节点，而记账页每敲一个数字都会重组一次这里：过滤、找下标、分行
    // 全部按 (categories, selectedL1) 记住，只在分类真变了时重算。
    val layout = remember(categories, selectedL1) {
        val parents = categories.filter { it.parentId == null }
        val index = parents.indexOfFirst { it.id == selectedL1 }
        CategoryLayout(
            parents = parents,
            activeIndex = index,
            children = if (index >= 0) categories.filter { it.parentId == parents[index].id } else emptyList(),
            rows = parents.chunked(COLUMNS),
        )
    }
    val parents = layout.parents
    val activeIndex = layout.activeIndex
    val children = layout.children

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        layout.rows.forEach { row ->
            // 气泡插在「它的一级」所在那一行的正下方，而不是所有一级的最底部。
            val anchorInRow = row.indexOfFirst { it.id == selectedL1 }
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { category ->
                        Box(modifier = Modifier.weight(1f)) {
                            CategoryTile(
                                category = category,
                                selected = category.id == selectedL1,
                                onClick = { onSelect(category.id, null) },
                            )
                        }
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
                // 一级无二级时不浮出空气泡（AC-F1-9）。气泡从那一行下面长出来，跟分类页的展开一个动作。
                AnimatedVisibility(
                    visible = anchorInRow >= 0 && children.isNotEmpty(),
                    enter = expandVertically(tween(190, easing = FastOutSlowInEasing)) + fadeIn(tween(150)),
                    exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) + fadeOut(tween(110)),
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val columnWidth = maxWidth / COLUMNS
                        SubCategoryBubble(
                            arrowX = columnWidth * (anchorInRow + 0.5f),
                            children = children,
                            selectedL2 = selectedL2,
                            onSelectChild = { onSelect(parents[activeIndex].id, it.id) },
                            onAddSubCategory = onAddSubCategory?.let { add -> { add(parents[activeIndex].id) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    category: CategoryEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .background(if (selected) colors.ink else colors.card)
                .border(1.dp, if (selected) colors.ink else colors.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            CategoryGlyph(
                iconKey = category.iconKey,
                label = category.name,
                size = 18.dp,
                tint = if (selected) colors.paper else colors.ink,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = category.name,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = if (selected) colors.ink else colors.ink2,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 66.dp),
        )
    }
}

/**
 * 二级浮出气泡。尖角不是贴在卡片上的另一个三角形，而是卡片轮廓本身的一部分：
 * 圆角矩形与尖角共用一条连续路径来填充和描边，所以既没有压在框上的接缝，
 * 也不会有「箭头在框内」的错觉（板子 P03：尖角指回它的一级）。
 * 二级多了不往右滑：按一级那 5 列往下铺，格子与上面对齐，省掉「后面还有没有」的猜测。
 */
@Composable
private fun SubCategoryBubble(
    arrowX: androidx.compose.ui.unit.Dp,
    children: List<CategoryEntity>,
    selectedL2: String?,
    onSelectChild: (CategoryEntity) -> Unit,
    onAddSubCategory: (() -> Unit)?,
) {
    val colors = tallyColors
    val shape = CalloutShape(corner = 14.dp, tailHeight = 8.dp, tailWidth = 16.dp, tailCenterX = arrowX)
    // null 这一格是「添加」：跟着二级排到末尾，单独占一行也比整条气泡往右滚好找。
    val cells: List<CategoryEntity?> = children +
        if (onAddSubCategory != null) listOf(null) else emptyList()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, clip = true, ambientColor = colors.ink, spotColor = colors.ink)
            .background(colors.card, shape)
            .border(1.dp, colors.line, shape)
            .padding(start = 4.dp, end = 4.dp, top = 15.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        cells.chunked(COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { child ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (child != null) {
                            SubTile(
                                label = child.name,
                                iconKey = child.iconKey,
                                selected = child.id == selectedL2,
                                dashed = false,
                                onClick = { onSelectChild(child) },
                            )
                        } else if (onAddSubCategory != null) {
                            SubTile(label = "添加", iconKey = "plus", selected = false, dashed = true, onClick = onAddSubCategory)
                        }
                    }
                }
                repeat((COLUMNS - row.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 顶部带尖角的连续轮廓：尖角占用的那段高度算在轮廓内部，描边绕它一圈。 */
private class CalloutShape(
    private val corner: androidx.compose.ui.unit.Dp,
    private val tailHeight: androidx.compose.ui.unit.Dp,
    private val tailWidth: androidx.compose.ui.unit.Dp,
    private val tailCenterX: androidx.compose.ui.unit.Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { corner.toPx() }
        val ty = with(density) { tailHeight.toPx() }
        val half = with(density) { tailWidth.toPx() } / 2f
        val left = (with(density) { tailCenterX.toPx() } - half).coerceIn(r * 0.5f, (size.width - r * 1.5f).coerceAtLeast(r * 0.5f))
        val right = (left + half * 2f).coerceAtMost(size.width - r * 0.5f)
        val apex = ((left + right) / 2f).coerceIn(half, (size.width - half).coerceAtLeast(half))
        val bottom = size.height
        val path = Path().apply {
            moveTo(left, ty)
            lineTo(apex, 0f)
            lineTo(right, ty)
            lineTo(size.width - r, ty)
            arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * r, ty, size.width, ty + 2 * r), 270f, 90f, false)
            lineTo(size.width, bottom - r)
            arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * r, bottom - 2 * r, size.width, bottom), 0f, 90f, false)
            lineTo(r, bottom)
            arcTo(androidx.compose.ui.geometry.Rect(0f, bottom - 2 * r, 2 * r, bottom), 90f, 90f, false)
            lineTo(0f, ty + r)
            arcTo(androidx.compose.ui.geometry.Rect(0f, ty, 2 * r, ty + 2 * r), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun SubTile(
    label: String,
    iconKey: String,
    selected: Boolean,
    dashed: Boolean,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(9.dp)
    Column(
        modifier = Modifier
            .width(47.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(27.dp)
                .clip(shape)
                .background(if (selected) colors.ink else colors.paper)
                .then(
                    if (dashed) {
                        Modifier.drawBehind {
                            val inset = 0.5.dp.toPx()
                            drawRoundRect(
                                color = colors.ink3.copy(alpha = 0.55f),
                                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                                size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx(), 9.dp.toPx()),
                                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                            )
                        }
                    } else {
                        Modifier.border(1.dp, if (selected) colors.ink else colors.line, shape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            CategoryGlyph(
                iconKey = iconKey,
                label = label,
                size = 14.dp,
                tint = if (selected) colors.paper else if (dashed) colors.ink3 else colors.ink,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 10.5.sp,
            lineHeight = 12.sp,
            color = if (selected) colors.ink else if (dashed) colors.ink3 else colors.ink2,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 分类选择弹层：批量改分类与周期 / 分期创建共用，内嵌同一套 [CategoryPanel]。
 * 选中结果由底部按钮一次性提交，所以按钮上要写清影响范围（F2.3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<CategoryEntity>,
    selectedL1: String?,
    selectedL2: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
    confirmLabel: String = "确定",
) {
    val colors = tallyColors
    var activeL1 by remember(selectedL1) { mutableStateOf(selectedL1) }
    var activeL2 by remember(selectedL2) { mutableStateOf(selectedL2) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            BottomSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(10.dp))
            Text("选择分类", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(12.dp))
            Column(modifier = Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                CategoryPanel(
                    categories = categories,
                    selectedL1 = activeL1,
                    selectedL2 = activeL2,
                    onSelect = { l1, l2 ->
                        activeL1 = l1
                        activeL2 = l2
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("取消", style = TallyType.listTitle, color = colors.ink3)
                }
                Spacer(Modifier.weight(1f))
                TallyButton(
                    label = confirmLabel,
                    onClick = { activeL1?.let { onConfirm(it, activeL2) } },
                    enabled = activeL1 != null,
                    height = 40,
                    modifier = Modifier.width(150.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerPickerSheet(
    ledgers: List<LedgerListItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    includeAll: Boolean = false,
) {
    val colors = tallyColors
    // 选中即收起：让每个调用方自己记得关，就会有地方忘了关。
    fun pick(id: String) {
        onSelect(id)
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            BottomSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Text("切换账本", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(4.dp))
            // 账本会越加越多：给弹层一个上限高度，内部滚动，不要顶满整屏。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (includeAll) {
                    TallyListRow(
                        title = "全部账本",
                        iconKey = "select",
                        onClick = { pick("") },
                        trailing = if (selectedId == null) {
                            { TallyTag(text = "当前", tone = TagTone.BRAND) }
                        } else {
                            null
                        },
                    )
                }
                ledgers.forEach { item ->
                    TallyListRow(
                        title = item.ledger.name,
                        subtitle = if (item.ledger.isDefault) "默认账本" else "${item.stat.billCount} 笔",
                        iconKey = item.ledger.iconKey,
                        onClick = { pick(item.ledger.id) },
                        trailing = if (item.ledger.id == selectedId) {
                            { TallyTag(text = "当前", tone = TagTone.BRAND) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
