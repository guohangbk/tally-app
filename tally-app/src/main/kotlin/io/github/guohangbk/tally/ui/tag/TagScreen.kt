package io.github.guohangbk.tally.ui.tag

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.dao.TagUsageRow
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.common.TagPill
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class TagScreenState(
    val active: List<TagUsageRow> = emptyList(),
    val archived: List<TagUsageRow> = emptyList(),
    val showArchived: Boolean = false,
    val actionTag: TagUsageRow? = null,
    val editorOpen: Boolean = false,
    val editorTagId: String? = null,
    val editorText: String = "",
    /** 表单里的撞名提示只显示在弹层里，不抢气泡。 */
    val editorError: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val pendingDelete: TagUsageRow? = null,
    /** 记账页那枚「标签」chip 的开关；关掉时页面顶部留一条回设置的路。 */
    val tagEnabled: Boolean = true,
)

class TagsViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(TagScreenState())
    val state: StateFlow<TagScreenState> = mutable

    init {
        viewModelScope.launch {
            combine(graph.tagRepository.observeActive(), graph.tagRepository.observeArchived()) { active, archived ->
                active to archived
            }.collect { (active, archived) ->
                mutable.value = mutable.value.copy(active = active, archived = archived)
            }
        }
        viewModelScope.launch {
            graph.preferences.settings.collect { settings ->
                if (mutable.value.tagEnabled != settings.tagEnabled) {
                    mutable.value = mutable.value.copy(tagEnabled = settings.tagEnabled)
                }
            }
        }
    }

    fun showArchivedTab(show: Boolean) {
        mutable.value = mutable.value.copy(showArchived = show)
    }

    fun openActions(tag: TagUsageRow) {
        mutable.value = mutable.value.copy(actionTag = tag)
    }

    fun closeActions() {
        mutable.value = mutable.value.copy(actionTag = null)
    }

    fun openCreate() {
        mutable.value = mutable.value.copy(actionTag = null, editorOpen = true, editorTagId = null, editorText = "", editorError = null)
    }

    fun openRename(tag: TagUsageRow) {
        mutable.value = mutable.value.copy(actionTag = null, editorOpen = true, editorTagId = tag.id, editorText = tag.name, editorError = null)
    }

    fun closeEditor() {
        mutable.value = mutable.value.copy(editorOpen = false, editorError = null)
    }

    fun setEditorText(text: String) {
        mutable.value = mutable.value.copy(editorText = text, editorError = null)
    }

    /** 名称唯一、判重不忽略 emoji：撞名由仓库的 require 拦下，就地显示在表单里。 */
    fun saveEditor() {
        val current = mutable.value
        val name = current.editorText.trim()
        if (name.isEmpty()) {
            mutable.value = current.copy(editorError = "标签名不能为空")
            return
        }
        viewModelScope.launch {
            try {
                val id = current.editorTagId
                if (id == null) {
                    graph.tagRepository.create(name)
                    mutable.value = mutable.value.copy(editorOpen = false, editorError = null, notice = "已新建「$name」")
                } else {
                    graph.tagRepository.rename(id, name)
                    mutable.value = mutable.value.copy(editorOpen = false, editorError = null, notice = "已改名为「$name」")
                }
            } catch (error: IllegalArgumentException) {
                mutable.value = mutable.value.copy(editorError = error.message)
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(editorError = error.message ?: "标签没有保存")
            }
        }
    }

    fun setArchived(tag: TagUsageRow, archived: Boolean) {
        mutable.value = mutable.value.copy(actionTag = null)
        viewModelScope.launch {
            try {
                graph.tagRepository.archive(tag.id, archived)
                mutable.value = mutable.value.copy(notice = if (archived) "已归档「${tag.name}」" else "已恢复「${tag.name}」")
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(error = error.message ?: "没有改成")
            }
        }
    }

    fun askDelete(tag: TagUsageRow) {
        mutable.value = mutable.value.copy(actionTag = null, pendingDelete = tag)
    }

    fun cancelDelete() {
        mutable.value = mutable.value.copy(pendingDelete = null)
    }

    /** 删除只解绑：标签软删、关联清掉，账单一行不动。 */
    fun confirmDelete() {
        val tag = mutable.value.pendingDelete ?: return
        mutable.value = mutable.value.copy(pendingDelete = null)
        viewModelScope.launch {
            try {
                graph.tagRepository.delete(tag.id)
                mutable.value = mutable.value.copy(notice = "已删除「${tag.name}」")
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(error = error.message ?: "没有删掉")
            }
        }
    }

    /** 松手即存：选择器、筛选与管理墙读的是同一列 sort_order。 */
    fun reorder(orderedIds: List<String>) {
        viewModelScope.launch {
            runCatching { graph.tagRepository.reorder(orderedIds) }
                .onFailure { mutable.value = mutable.value.copy(error = it.message ?: "顺序没有保存") }
        }
    }

    fun dismissNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }

    fun dismissError() {
        mutable.value = mutable.value.copy(error = null)
    }
}

/**
 * 标签管理：胶囊墙 + 就地拖排序（TG-07 / TG-12）。
 * 搜索框不做——这个量级不该需要它；所有操作收在点开的那张单里，墙上不放 ⋯。
 */
@Composable
fun TagsScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenBillKeypad: () -> Unit,
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { TagsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    val tags = if (state.showArchived) state.archived else state.active

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "标签",
            onBack = onBack,
            // 新建只在「使用中」给：在归档栏建出来的标签不在这栏里，点了会像没反应。
            trailing = if (state.showArchived) {
                null
            } else {
                { TallyIconAction(iconKey = "plus", contentDescription = "新建标签", onClick = viewModel::openCreate) }
            },
        )
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "标签没有改成功",
            onNoticeShown = viewModel::dismissNotice,
            onErrorShown = viewModel::dismissError,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            if (state.tagEnabled) {
                Text(
                    "标签只标值得单独看的事，够用就好",
                    style = TallyType.helper,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                // 关掉的是记账页那枚 chip，不是标签本身；给一条直接回开关的路。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onOpenBillKeypad)
                        .padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "记账页的「标签」按钮已关闭",
                        style = TallyType.helper,
                        color = colors.ink2,
                        modifier = Modifier.weight(1f),
                    )
                    Text("去开启", style = TallyType.helper, color = colors.brand)
                    Spacer(Modifier.width(3.dp))
                    InlineIcon(iconKey = "chevron", contentDescription = null, size = 11.dp, tint = colors.brand)
                }
            }
            Spacer(Modifier.height(9.dp))
            TallySegmented(
                options = listOf(
                    false to "使用中 · ${state.active.size}",
                    true to "已归档 · ${state.archived.size}",
                ),
                selected = state.showArchived,
                onSelect = viewModel::showArchivedTab,
            )
            Spacer(Modifier.height(14.dp))
            if (tags.isEmpty()) {
                TallyEmptyState(
                    title = if (state.showArchived) "还没有归档的标签" else "还没有标签",
                    description = if (state.showArchived) {
                        "归档的标签会从记账和筛选里隐藏，账单仍然保留。"
                    } else {
                        "点右上角 ＋ 新建一个"
                    },
                    iconKey = "tag",
                )
            } else {
                TagWall(
                    tags = tags,
                    draggable = !state.showArchived,
                    onTap = viewModel::openActions,
                    onReorder = viewModel::reorder,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    state.actionTag?.let { tag ->
        TallyBottomSheet(onDismissRequest = viewModel::closeActions, containerColor = colors.paper) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tag.name, style = TallyType.sectionTitle, color = colors.ink, modifier = Modifier.weight(1f))
                    InlineIcon(
                        iconKey = "close",
                        contentDescription = "关闭",
                        size = 14.dp,
                        tint = colors.ink3,
                        modifier = Modifier.clickable(onClick = viewModel::closeActions),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TallyListRow(
                    title = "查看统计",
                    iconKey = "chart",
                    onClick = {
                        viewModel.closeActions()
                        onOpenDetail(tag.id)
                    },
                )
                TallyListRow(
                    title = "修改",
                    iconKey = "pen",
                    onClick = { viewModel.openRename(tag) },
                )
                TallyListRow(
                    title = if (tag.archivedAt == null) "归档" else "恢复",
                    subtitle = if (tag.archivedAt == null) "从记账和筛选里隐藏，账单保留" else "重新出现在记账和筛选里",
                    iconKey = "archive",
                    onClick = { viewModel.setArchived(tag, tag.archivedAt == null) },
                )
                TallyListRow(
                    title = "删除",
                    subtitle = "解绑 ${tag.billCount} 笔账单，账单不动",
                    iconKey = "trash",
                    onClick = { viewModel.askDelete(tag) },
                )
            }
        }
    }

    if (state.editorOpen) {
        TagNameSheet(
            title = if (state.editorTagId == null) "新建标签" else "修改名称",
            value = state.editorText,
            error = state.editorError,
            onValueChange = viewModel::setEditorText,
            onSave = viewModel::saveEditor,
            onDismiss = viewModel::closeEditor,
        )
    }

    state.pendingDelete?.let { tag ->
        TallyConfirmDialog(
            title = "删除「${tag.name}」？",
            message = "解绑 ${tag.billCount} 笔账单，账单本身不动。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/** 胶囊墙：按住（长按）那枚会晃一下、抬起来跟手走，其余胶囊让位，松手即存。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagWall(
    tags: List<TagUsageRow>,
    draggable: Boolean,
    onTap: (TagUsageRow) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    // 拖动期间不能被数据回流重置顺序：DB 只在松手后才会再发一版。
    var order by remember(tags) { mutableStateOf(tags) }
    val latest by rememberUpdatedState(order)
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    // 起拖那一刻原图的位置：换位只改槽位，浮起来那枚以它 + 手指位移来画。
    var dragOrigin by remember { mutableStateOf<Rect?>(null) }
    val haptics = LocalHapticFeedback.current
    val lifted by animateFloatAsState(if (draggingId != null) 1.06f else 1f, tween(110), label = "lift")
    val wiggle = remember { Animatable(0f) }
    val dragging = draggingId?.let { id -> order.firstOrNull { it.id == id } }

    LaunchedEffect(draggingId) {
        if (draggingId == null) {
            wiggle.snapTo(0f)
        } else {
            wiggle.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 340
                    -4f at 55
                    3f at 135
                    -2f at 215
                    0f at 340
                },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(draggable) {
                if (!draggable) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val hit = latest.firstOrNull { bounds[it.id]?.contains(offset) == true }
                        draggingId = hit?.id
                        if (hit != null) {
                            dragStart = offset
                            dragPosition = offset
                            dragOrigin = bounds[hit.id]
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, _ ->
                        val id = draggingId ?: return@detectDragGesturesAfterLongPress
                        dragPosition = change.position
                        val target = latest.firstOrNull {
                            it.id != id && bounds[it.id]?.contains(change.position) == true
                        } ?: return@detectDragGesturesAfterLongPress
                        val from = order.indexOfFirst { it.id == id }
                        val to = order.indexOfFirst { it.id == target.id }
                        if (from >= 0 && to >= 0) {
                            order = order.toMutableList().apply { add(to, removeAt(from)) }
                        }
                    },
                    onDragEnd = {
                        val moved = draggingId != null
                        draggingId = null
                        dragOrigin = null
                        if (moved) onReorder(order.map { it.id })
                    },
                    onDragCancel = {
                        draggingId = null
                        dragOrigin = null
                    },
                )
            },
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            order.forEach { tag ->
                WallTagPill(
                    tag = tag,
                    draggable = draggable,
                    modifier = Modifier
                        .onGloballyPositioned { bounds[tag.id] = it.boundsInParent() }
                        // 原地留个浅影当槽位，浮起来那枚才是手指抓着的东西。
                        .alpha(if (draggingId == tag.id) 0.18f else 1f),
                    onClick = { if (draggingId == null) onTap(tag) },
                )
            }
        }

        val origin = dragOrigin
        if (dragging != null && origin != null) {
            val delta = dragPosition - dragStart
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (origin.left + delta.x).roundToInt(),
                            (origin.top + delta.y).roundToInt(),
                        )
                    }
                    .zIndex(1f)
                    .graphicsLayer {
                        scaleX = lifted
                        scaleY = lifted
                        rotationZ = wiggle.value
                        shadowElevation = 9.dp.toPx()
                        shape = RoundedCornerShape(50)
                    },
            ) {
                WallTagPill(tag = dragging, draggable = draggable, onClick = {})
            }
        }
    }
}

@Composable
private fun WallTagPill(
    tag: TagUsageRow,
    draggable: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    TagPill(
        name = tag.name,
        modifier = modifier,
        fontSize = 12.sp,
        verticalPadding = 4.dp,
        onClick = onClick,
        leading = if (draggable) {
            { InlineIcon(iconKey = "drag", contentDescription = null, size = 11.dp, tint = colors.ink3) }
        } else {
            null
        },
        trailing = {
            Text(
                text = "${tag.billCount}",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = colors.ink3,
            )
        },
    )
}

/** 新建与修改共用一张表单：只填名称，emoji 由输入法打进去。 */
@Composable
private fun TagNameSheet(
    title: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(10.dp)
    TallyBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = TallyType.sectionTitle, color = colors.ink, modifier = Modifier.weight(1f))
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
                    .clip(shape)
                    .background(colors.paper)
                    .border(1.dp, colors.line, shape)
                    .padding(horizontal = 11.dp, vertical = 11.dp),
            ) {
                if (value.isEmpty()) Text("名称，例如「🏠 装修」", style = TallyType.body, color = colors.ink3)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TallyType.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.brand),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = TallyType.helper, color = colors.expense)
            }
            Spacer(Modifier.height(14.dp))
            TallyButton(
                label = "保存",
                onClick = onSave,
                variant = ButtonVariant.BRAND,
                height = 36,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
