package io.github.guohangbk.tally.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.ui.design.IconLibrary
import io.github.guohangbk.tally.ui.design.IconMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 图标库页的进入方式：建一级、建二级、改已有分类的图标、给账本选图标。 */
sealed class IconLibraryMode {
    data class NewLevel1(val type: CategoryType) : IconLibraryMode()
    data class NewLevel2(val parentId: String) : IconLibraryMode()
    data class EditIcon(val categoryId: String) : IconLibraryMode()
    /** 账本图标：只选图标，名称在账本弹层里填。键域只认图标库，不放首字/emoji 兜底。 */
    data object LedgerIcon : IconLibraryMode()
}

data class BatchLine(val name: String, val duplicate: Boolean)

data class IconLibraryUiState(
    val loaded: Boolean = false,
    val title: String = "添加分类",
    val parentHint: String? = null,
    val type: CategoryType = CategoryType.EXPENSE,
    val selectedIcon: String? = null,
    val name: String = "",
    val batch: Boolean = false,
    val batchText: String = "",
    val query: String = "",
    val group: String = IconLibrary.groupNames.first(),
    val existing: Set<String> = emptySet(),
    val notice: String? = null,
    val error: String? = null,
    /** 改已有分类：批量新增没有意义，点图标也不该把用户起的名字盖掉。 */
    val editing: Boolean = false,
    /** 账本图标模式：名称与批量都不出现，保存的键只认图标库。 */
    val ledgerIcon: Boolean = false,
) {
    val results: List<IconMeta> = run {
        val q = query.trim()
        if (q.isEmpty()) IconLibrary.library.filter { it.group == group }
        else IconLibrary.search(q)
    }

    /** 名称为空则保存不可用；批量时至少要有一个不重名的行。 */
    val batchLines: List<BatchLine>
        get() = batchText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_BATCH)
            .map { BatchLine(it, existing.any { used -> used.equals(it, ignoreCase = true) }) }

    val creatableCount: Int get() = if (batch) batchLines.count { !it.duplicate } else 1
    val canSave: Boolean
        get() = selectedIcon != null &&
            (if (batch) batchLines.any { !it.duplicate } else name.isNotBlank() && !isNameInUse(name))

    fun isNameInUse(value: String): Boolean = existing.any { it.equals(value.trim(), ignoreCase = true) }

    companion object {
        const val MAX_BATCH = 20
        const val MAX_NAME = 12
    }
}

class IconLibraryViewModel(
    private val graph: AppGraph,
    private val mode: IconLibraryMode,
) : ViewModel() {
    private val mutable = MutableStateFlow(IconLibraryUiState())
    val state: StateFlow<IconLibraryUiState> = mutable.asStateFlow()

init {
        viewModelScope.launch {
            graph.ledgerRepository.bootstrap()
            val context = resolveContext()
            val existing = graph.categoryRepository.namesInUse(context.type, context.parentId)
            val preset = graph.categoryRepository.find(context.categoryId ?: "")
            mutable.value = IconLibraryUiState(
                loaded = true,
                title = when (mode) {
                    is IconLibraryMode.NewLevel1 -> "添加一级分类"
                    is IconLibraryMode.NewLevel2 -> "添加二级分类"
                    is IconLibraryMode.EditIcon -> "修改分类"
                    IconLibraryMode.LedgerIcon -> "选择账本图标"
                },
                parentHint = context.parentName?.let { "上级「$it」 · 建完直接进它的纸盘" },
                type = context.type,
                selectedIcon = preset?.iconKey,
                name = preset?.name.orEmpty(),
                // 自己那条名字要留在集合外：否则改名页里名字一个字没动也判「已存在该名字」，永远保存不了。
                existing = preset?.name?.let { existing - it.trim() } ?: existing,
                editing = preset != null,
                ledgerIcon = mode is IconLibraryMode.LedgerIcon,
                // 改分类时开在它自己那一组：进来先看得见当前选中的图标，不用从左边的栏里找。
                group = preset?.iconKey?.let { IconLibrary.groupOf(it) } ?: IconLibrary.groupNames.first(),
            )
        }
    }

    private data class Context(
        val type: CategoryType,
        val parentId: String?,
        val parentName: String?,
        val categoryId: String?,
    )

    private suspend fun resolveContext(): Context = when (mode) {
        is IconLibraryMode.NewLevel1 -> Context(
            type = mode.type,
            parentId = null,
            parentName = null,
            categoryId = null,
        )

        is IconLibraryMode.NewLevel2 -> {
            val parent = graph.categoryRepository.find(mode.parentId)
            Context(
                type = parent?.type ?: CategoryType.EXPENSE,
                parentId = mode.parentId,
                parentName = parent?.name,
                categoryId = null,
            )
        }

        is IconLibraryMode.EditIcon -> {
            val category = graph.categoryRepository.find(mode.categoryId)
            Context(
                type = category?.type ?: CategoryType.EXPENSE,
                parentId = category?.parentId,
                parentName = null,
                categoryId = mode.categoryId,
            )
        }

        IconLibraryMode.LedgerIcon -> Context(
            type = CategoryType.EXPENSE,
            parentId = null,
            parentName = null,
            categoryId = null,
        )
    }



    fun selectIcon(meta: IconMeta) {
        // 建分类时点图标即把默认名填进名称框，改过的名字也一起覆盖（AC-F7-2）；
        // 改分类时名字是用户自己的，换图不该顺手改名。
        val current = mutable.value
        mutable.value = current.copy(
            selectedIcon = meta.key,
            name = if (current.editing) current.name else meta.displayName,
            error = null,
        )
    }

    fun selectFallbackKey(key: String) {
        if (mutable.value.ledgerIcon && !IconLibrary.isKnown(key)) {
            mutable.value = mutable.value.copy(error = "账本图标只能从图标库里选")
            return
        }
        mutable.value = mutable.value.copy(selectedIcon = key, error = null)
    }

    fun setName(value: String) {
        mutable.value = mutable.value.copy(name = value.take(IconLibraryUiState.MAX_NAME))
    }

    fun setBatchText(value: String) {
        val lines = value.split('\n')
        val tooMany = lines.size > IconLibraryUiState.MAX_BATCH
        mutable.value = mutable.value.copy(
            batchText = if (tooMany) lines.take(IconLibraryUiState.MAX_BATCH).joinToString("\n") else value,
            notice = if (tooMany) "一次最多 ${IconLibraryUiState.MAX_BATCH} 个" else null,
        )
    }

    fun setBatch(on: Boolean) {
        mutable.value = mutable.value.copy(batch = on, notice = null)
    }

    fun setQuery(value: String) {
        mutable.value = mutable.value.copy(query = value)
    }

    fun setGroup(group: String) {
        mutable.value = mutable.value.copy(group = group, query = "")
    }

    fun clearNotice() {
        mutable.value = mutable.value.copy(notice = null, error = null)
    }

    /** 保存才写库；返回即丢弃（F7.9）。账本模式把选中的键交给调用方，其余模式不需要。 */
    fun save(onSaved: (String?) -> Unit) {
        val current = mutable.value
        if (current.ledgerIcon && current.selectedIcon == null) {
            mutable.value = current.copy(error = "先选一个图标")
            return
        }
        if (!current.ledgerIcon && !current.canSave) {
            mutable.value = current.copy(error = "先选一个图标并填名称")
            return
        }
        viewModelScope.launch {
            try {
                when (mode) {
                    is IconLibraryMode.NewLevel1 -> createLevels1(current)
                    is IconLibraryMode.NewLevel2 -> createLevels2(mode.parentId, current)
                    is IconLibraryMode.EditIcon -> applyEdit(mode.categoryId, current)
                    IconLibraryMode.LedgerIcon -> {}
                }
                onSaved(current.selectedIcon)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: TallyError) {
                mutable.value = mutable.value.copy(error = error.message)
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(error = error.message ?: "没有保存成功")
            }
        }
    }

    private suspend fun createLevels1(current: IconLibraryUiState) {
        names(current).forEach { (name, index) ->
            graph.categoryRepository.addLevel1(
                type = current.type,
                name = name,
                iconKey = if (index == 0) current.selectedIcon ?: name.first().toString() else name.first().toString(),
            )
        }
    }

    private suspend fun createLevels2(parentId: String, current: IconLibraryUiState) {
        names(current).forEach { (name, index) ->
            graph.categoryRepository.addLevel2(
                parentId = parentId,
                name = name,
                iconKey = if (index == 0) current.selectedIcon else null,
            )
        }
    }

    private suspend fun applyEdit(categoryId: String, current: IconLibraryUiState) {
        val icon = current.selectedIcon ?: return
        graph.categoryRepository.setIcon(categoryId, icon)
        val target = graph.categoryRepository.find(categoryId)
        val trimmed = current.name.trim()
        if (target != null && trimmed.isNotEmpty() && trimmed != target.name) graph.categoryRepository.rename(categoryId, trimmed)
    }

    /** 批量时跳过重名行，图标只作用于首项，其余按首字兜底（F7.6）。 */
    private fun names(current: IconLibraryUiState): List<Pair<String, Int>> =
        if (current.batch) {
            current.batchLines.filterNot { it.duplicate }.mapIndexed { index, line -> line.name to index }
        } else {
            listOf(current.name.trim() to 0)
        }
}
