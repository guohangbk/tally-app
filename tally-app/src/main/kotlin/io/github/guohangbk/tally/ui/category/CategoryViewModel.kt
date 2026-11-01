package io.github.guohangbk.tally.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.Category
import io.github.guohangbk.tally.domain.model.CategoryNode
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CategoryDialogKind {
    DELETE,
}

enum class CategorySheetKind {
    /** P08b：改名与换图标就地完成的编辑弹层；categoryId 为空时表示新建一级 / 二级。 */
    EDITOR,

    /** P08c：把二级挪到另一个一级之下。 */
    MOVE_PARENT,
}

data class CategoryDialog(
    val kind: CategoryDialogKind,
    val categoryId: String? = null,
    val parentId: String? = null,
)

/**
 * 弹层状态。弹层只做动作分发：改名与换图标去全屏修改页，弹层里放不下整库。
 */
data class CategorySheet(
    val kind: CategorySheetKind,
    val categoryId: String? = null,
)

/** 删除时的账单去向：未选择 / 显式未分类 / 同层迁移。 */
sealed interface CategoryDeleteTarget {
    data object Pending : CategoryDeleteTarget

    data object Unclassified : CategoryDeleteTarget

    data class Migrate(val targetId: String) : CategoryDeleteTarget
}

data class CategoryUiState(
    val type: CategoryType = CategoryType.EXPENSE,
    val expense: List<CategoryNode> = emptyList(),
    val income: List<CategoryNode> = emptyList(),
    val billCounts: Map<String, Int> = emptyMap(),
    val loaded: Boolean = false,
    val dialog: CategoryDialog? = null,
    val sheet: CategorySheet? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val tree: List<CategoryNode> get() = if (type == CategoryType.EXPENSE) expense else income

    val flat: List<Category> get() = buildList {
        (expense + income).forEach { node ->
            add(node.category)
            addAll(node.children)
        }
    }

    fun categoryOf(id: String?): Category? = id?.let { target -> flat.firstOrNull { it.id == target } }

    fun billCount(id: String?): Int = id?.let { billCounts[it] ?: 0 } ?: 0

    /** 迁移候选：同账本、同收支类型、同层级、未归档、非自身。 */
    fun migrateTargets(id: String?): List<Category> {
        val source = categoryOf(id) ?: return emptyList()
        return flat.filter {
            it.id != source.id && it.type == source.type && it.parentId == source.parentId && !it.isArchived
        }
    }

    fun childCountOf(id: String?): Int = (expense + income).firstOrNull { it.category.id == id }?.children?.size ?: 0

    /** 同层兄弟 id（已按 sort_order 排序），弹层里的上移 / 下移要用。 */
    fun siblingIdsOf(id: String?): List<String> {
        val target = categoryOf(id) ?: return emptyList()
        val level1 = if (target.type == CategoryType.EXPENSE) expense else income
        return if (target.parentId == null) {
            level1.map { it.category.id }
        } else {
            level1.firstOrNull { it.category.id == target.parentId }?.children?.map { it.id }.orEmpty()
        }
    }

    /** 二级可以改挂到哪些一级之下：同收支类型、未归档、排除当前父级。 */
    fun moveParents(id: String?): List<Category> {
        val source = categoryOf(id) ?: return emptyList()
        val level1 = if (source.type == CategoryType.EXPENSE) expense else income
        return level1.map { it.category }.filter { it.id != source.parentId && !it.isArchived }
    }
}

/** P08 分类管理：树、排序、图标与删除迁移的操作入口。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModel(private val graph: AppGraph) : ViewModel() {
    private val typeState = MutableStateFlow(CategoryType.EXPENSE)
    private val dialogState = MutableStateFlow<CategoryDialog?>(null)
    private val sheetState = MutableStateFlow<CategorySheet?>(null)
    private val errorState = MutableStateFlow<String?>(null)
    private val noticeState = MutableStateFlow<String?>(null)

    private data class Trees(
        val expense: List<CategoryNode> = emptyList(),
        val income: List<CategoryNode> = emptyList(),
    )

    private val trees: Flow<Trees> = combine(
        graph.categoryRepository.tree(CategoryType.EXPENSE),
        graph.categoryRepository.tree(CategoryType.INCOME),
    ) { expense, income -> Trees(expense, income) }.flowOn(Dispatchers.IO)

    /** 笔数一次聚合取回；以前逐分类 COUNT，分类一多首帧就得等几十个往返，会闪「正在载入」。 */
    private val billCounts: Flow<Map<String, Int>> =
        graph.categoryRepository.observeBillCounts().flowOn(Dispatchers.IO)

    private data class Local(
        val type: CategoryType,
        val dialog: CategoryDialog?,
        val sheet: CategorySheet?,
        val error: String?,
        val notice: String?,
    )

    private val local: Flow<Local> = combine(
        typeState,
        dialogState,
        sheetState,
        errorState,
        noticeState,
    ) { type, dialog, sheet, error, notice -> Local(type, dialog, sheet, error, notice) }

    val state: StateFlow<CategoryUiState> = combine(trees, billCounts, local) { tree, counts, ui ->
        CategoryUiState(
            type = ui.type,
            expense = tree.expense,
            income = tree.income,
            billCounts = counts,
            loaded = true,
            dialog = ui.dialog,
            sheet = ui.sheet,
            error = ui.error,
            notice = ui.notice,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // 分类树启动时已经预热过：首帧直接播种，不再先画一句「正在载入分类树…」。
        // 树真有变化时 combine 会推新值覆盖，不会停在缓存上。
        CategoryUiState(
            expense = graph.cachedCategoryTree.expense,
            income = graph.cachedCategoryTree.income,
            loaded = !graph.cachedCategoryTree.isEmpty,
        ),
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // 从引导或抽屉直达本页时，默认账本与默认分类树可能还没建立；仓储内幂等。
            graph.ledgerRepository.bootstrap()
        }
    }

    fun selectType(type: CategoryType) {
        typeState.value = type
    }

    fun openDialog(kind: CategoryDialogKind, categoryId: String? = null, parentId: String? = null) {
        sheetState.value = null
        errorState.value = null
        noticeState.value = null
        dialogState.value = CategoryDialog(kind, categoryId, parentId)
    }

    fun openSheet(kind: CategorySheetKind, categoryId: String? = null) {
        dialogState.value = null
        errorState.value = null
        noticeState.value = null
        sheetState.value = CategorySheet(kind, categoryId)
    }


    /** P08b：一级卡片右上角 ⋯ 唤起操作弹层——修改、看数据、加二级、排序、归档、删除。 */
    fun openEditor(categoryId: String) = openSheet(CategorySheetKind.EDITOR, categoryId = categoryId)


    fun openMoveParent(categoryId: String) = openSheet(CategorySheetKind.MOVE_PARENT, categoryId = categoryId)

    fun dismissSheet() {
        sheetState.value = null
        errorState.value = null
    }

    fun dismissDialog() {
        dialogState.value = null
        errorState.value = null
    }

    fun dismissNotice() {
        noticeState.value = null
    }

    fun dismissError() {
        errorState.value = null
    }

    /** 上移 / 下移一格；`targetIndex` 按「取出后插入」的口径生效。 */
    fun moveBy(categoryId: String, delta: Int) {
        val siblings = state.value.siblingIdsOf(categoryId)
        val index = siblings.indexOf(categoryId)
        val target = index + delta
        if (index < 0 || target !in siblings.indices) return
        runOperation(keepSheet = true) {
            graph.categoryRepository.move(categoryId, target)
            null
        }
    }

    /** 二级改挂到另一个一级下：两级为上限，所以目标必须是一级且收支类型一致。 */
    fun moveToParent(categoryId: String, newParentId: String) {
        runOperation {
            val child = graph.categoryRepository.find(categoryId) ?: throw TallyError.CategoryNotFound
            val parent = graph.categoryRepository.find(newParentId) ?: throw TallyError.CategoryNotFound
            if (child.parentId == null || parent.parentId != null || parent.type != child.type) {
                throw TallyError.CategoryTargetInvalid
            }
            graph.categoryRepository.moveToParent(categoryId, newParentId)
            "已把「${child.name}」移到「${parent.name}」下"
        }
    }

    fun setArchived(categoryId: String, archived: Boolean) {
        runOperation {
            graph.categoryRepository.setArchived(categoryId, archived)
            "已${if (archived) "归档" else "恢复"}该分类"
        }
    }

    fun dismissDragGuide() {
        viewModelScope.launch { graph.preferences.setDragGuideSeen(true) }
    }

    /** 拖拽落点提交；仓储内部只做排序值插拔，不重写全表。 */
    fun move(categoryId: String, targetIndex: Int) {
        runOperation {
            graph.categoryRepository.move(categoryId, targetIndex)
            null
        }
    }

    /** 有账单引用时必须显式给出去向，否则就地报错并保留弹窗。 */
    fun delete(categoryId: String, target: CategoryDeleteTarget) {
        runOperation {
            val category = graph.categoryRepository.find(categoryId) ?: throw TallyError.CategoryNotFound
            val affected = graph.categoryRepository.billCount(categoryId)
            if (affected > 0 && target is CategoryDeleteTarget.Pending) {
                throw IllegalStateException("「${category.name}」还有 $affected 笔账单，请先选择迁移目标")
            }
            val moved = graph.categoryRepository.delete(
                categoryId,
                (target as? CategoryDeleteTarget.Migrate)?.targetId,
            )
            if (moved > 0) "已删除「${category.name}」，$moved 笔账单已迁移" else "已删除「${category.name}」"
        }
    }

    /** 归档区里只有 0 笔的分类可以彻底删除；有账单的只能恢复（F4.9）。 */
    fun purge(categoryId: String) {
        runOperation {
            val category = graph.categoryRepository.find(categoryId) ?: throw TallyError.CategoryNotFound
            if (graph.categoryRepository.billCount(categoryId) > 0) {
                throw IllegalStateException("「${category.name}」还有账单，只能恢复，不能彻底删除")
            }
            graph.categoryRepository.delete(categoryId, targetId = null)
            "已彻底删除「${category.name}」"
        }
    }

    fun loadDefaultTree() {
        runOperation {
            if (graph.categoryRepository.ensureDefaultTree()) "已补齐默认分类" else "已有分类，无需重建"
        }
    }

    /** `keepSheet` 用于弹层里的就地操作：只报错，不收起弹层也不刷提示。 */
    private fun runOperation(keepSheet: Boolean = false, block: suspend () -> String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val notice = block()
                dialogState.value = null
                if (!keepSheet) sheetState.value = null
                errorState.value = null
                noticeState.value = notice
            } catch (tallyError: TallyError) {
                errorState.value = tallyError.message
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                errorState.value = failure.message ?: "操作没有完成，请稍后重试"
            }
        }
    }

    private companion object {
        /** 新建一级时用户没挑图标就落这个键，保证图标永远非空。 */
    }
}

internal fun CategoryType.label(): String = when (this) {
    CategoryType.EXPENSE -> "支出"
    CategoryType.INCOME -> "收入"
}