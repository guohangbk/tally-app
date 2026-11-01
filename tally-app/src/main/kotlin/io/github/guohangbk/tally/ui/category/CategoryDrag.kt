package io.github.guohangbk.tally.ui.category

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

/**
 * 同层拖拽会话：只记录被拖行与位移，落点在松手时一次性换算成索引提交，
 * 避免每帧重排整列表带来的抖动与错序。
 */
@Stable
internal class CategoryDragSession {
    var draggedId by mutableStateOf<String?>(null)
        private set

    var offsetPx by mutableFloatStateOf(0f)
        private set

    var targetId by mutableStateOf<String?>(null)
        private set

    private var siblings: List<String> = emptyList()
    private var rowHeightPx = 1f
    private var commit: (String, Int) -> Unit = { _, _ -> }

    fun begin(
        draggedId: String,
        siblings: List<String>,
        rowHeightPx: Float,
        commit: (String, Int) -> Unit,
    ) {
        this.siblings = siblings
        this.rowHeightPx = rowHeightPx.coerceAtLeast(1f)
        this.commit = commit
        this.draggedId = draggedId
        this.targetId = draggedId
        this.offsetPx = 0f
    }

    fun drag(dy: Float) {
        val dragged = draggedId ?: return
        val ids = siblings
        val index = ids.indexOf(dragged)
        if (index < 0) {
            cancel()
            return
        }
        val last = ids.lastIndex.coerceAtLeast(0)
        offsetPx = (offsetPx + dy).coerceIn(-index * rowHeightPx, (last - index) * rowHeightPx)
        val rawIndex = (index + (offsetPx / rowHeightPx).roundToInt()).coerceIn(0, last)
        targetId = ids.getOrElse(rawIndex) { dragged }
    }

    fun end() {
        val dragged = draggedId ?: return
        val sourceIndex = siblings.indexOf(dragged)
        val dropIndex = targetId?.let { siblings.indexOf(it) } ?: -1
        cancel()
        if (sourceIndex >= 0 && dropIndex >= 0 && dropIndex != sourceIndex) {
            commit(dragged, dropIndex)
        }
    }

    fun cancel() {
        draggedId = null
        targetId = null
        offsetPx = 0f
    }
}

/**
 * 行前缘把手：按住上下移动即进入拖拽；`siblings` 变化会重启手势作用域，
 * 保证落点索引始终对应提交前的那份同层顺序。
 */
internal fun Modifier.dragHandle(
    session: CategoryDragSession,
    categoryId: String,
    siblings: List<String>,
    rowHeightPx: Float,
    commit: (String, Int) -> Unit,
): Modifier = pointerInput(categoryId, siblings) {
    detectVerticalDragGestures(
        onDragStart = { session.begin(categoryId, siblings, rowHeightPx, commit) },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            session.drag(dragAmount)
        },
        onDragEnd = { session.end() },
        onDragCancel = { session.cancel() },
    )
}

/**
 * 整卡长按拖拽（设计板 P08d）：轻点仍归卡片自身的 clickable，
 * 只有按住后再移动才由拖拽接管，所以「点行展开」与「长按排序」互不干扰。
 */
internal fun Modifier.longPressDrag(
    session: CategoryDragSession,
    categoryId: String,
    siblings: List<String>,
    rowHeightPx: Float,
    commit: (String, Int) -> Unit,
): Modifier = pointerInput(categoryId, siblings) {
    detectDragGesturesAfterLongPress(
        onDragStart = { session.begin(categoryId, siblings, rowHeightPx, commit) },
        onDrag = { change: PointerInputChange, dragAmount: Offset ->
            change.consume()
            session.drag(dragAmount.y)
        },
        onDragEnd = { session.end() },
        onDragCancel = { session.cancel() },
    )
}
