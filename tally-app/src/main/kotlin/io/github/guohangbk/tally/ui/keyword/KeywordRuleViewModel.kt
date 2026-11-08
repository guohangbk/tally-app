package io.github.guohangbk.tally.ui.keyword

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.ai.KeywordRuleRepository
import io.github.guohangbk.tally.data.ai.KeywordRuleSnapshot
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 组内就地加词：输入框只服务于一个分类，保存后收起。 */
data class AddWordsState(
    val categoryId: String,
    val text: String = "",
    val saving: Boolean = false,
    val error: String? = null,
)

/** 分类面板只做一件事，打开它的人和拿回来的选中值决定后续动作。 */
sealed interface KeywordPicker {
    /** 页头 ＋：选分类后落到那一组，直接进入加词。 */
    data object NewGroup : KeywordPicker

    /** 组 ⋯ 换分类：这组词一起搬。 */
    data class GroupRebind(val categoryId: String) : KeywordPicker

    /** 词 ⋯ 改绑：只动这一个词。 */
    data class WordRebind(val rule: KeywordRuleEntity) : KeywordPicker
}

data class KeywordRuleUiState(
    val loaded: Boolean = false,
    val query: String = "",
    val snapshot: KeywordRuleSnapshot? = null,
    /** 展开的分组键，见 [groupKey]：内置与我的是两套行，同名分类各开各的。 */
    val expanded: Set<String> = emptySet(),
    val adding: AddWordsState? = null,
    val picker: KeywordPicker? = null,
    val groupSheet: String? = null,
    val wordSheet: KeywordRuleEntity? = null,
    /** 等确认的整组删除：删一组词动的是好几行，先问一次再走撤销。 */
    val pendingGroupDelete: String? = null,
    /** 刚被删掉的那一批 id：气泡上的「撤销」只认这一批。 */
    val undoIds: List<String> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
) {
    val sheetOpen: Boolean get() = picker != null || groupSheet != null || wordSheet != null || pendingGroupDelete != null

    fun isExpanded(key: String): Boolean = key in expanded
}

/** 内置组与用户组可能落在同一个分类上，展开状态要用前缀分开。 */
fun groupKey(builtin: Boolean, categoryId: String): String =
    (if (builtin) "b:" else "u:") + categoryId

class KeywordRuleViewModel(private val graph: AppGraph) : ViewModel() {
    private val repository: KeywordRuleRepository = graph.keywordRuleRepository
    private val state = MutableStateFlow(KeywordRuleUiState())
    val uiState: StateFlow<KeywordRuleUiState> = state.asStateFlow()

    init {
        refresh()
    }

    fun setQuery(value: String) = state.update { it.copy(query = value) }

    fun toggleGroup(builtin: Boolean, categoryId: String) = state.update {
        val key = groupKey(builtin, categoryId)
        val expanded = if (key in it.expanded) it.expanded - key else it.expanded + key
        it.copy(expanded = expanded)
    }

    /** 页头 ＋：先选分类，选完直接展开那一组并聚焦加词框。 */
    fun openNewGroup() = state.update { it.copy(picker = KeywordPicker.NewGroup, error = null) }

    fun openGroupActions(categoryId: String) = state.update {
        it.copy(groupSheet = categoryId, error = null)
    }

    fun closeGroupActions() = state.update { it.copy(groupSheet = null) }

    fun openWordActions(rule: KeywordRuleEntity) = state.update { it.copy(wordSheet = rule) }

    fun closeWordActions() = state.update { it.copy(wordSheet = null) }

    fun openGroupRebind(categoryId: String) = state.update {
        it.copy(groupSheet = null, picker = KeywordPicker.GroupRebind(categoryId))
    }

    fun openWordRebind(rule: KeywordRuleEntity) = state.update {
        it.copy(wordSheet = null, picker = KeywordPicker.WordRebind(rule))
    }

    fun closePicker() = state.update { it.copy(picker = null) }

    fun startAdd(categoryId: String) = state.update {
        it.copy(
            adding = AddWordsState(categoryId),
            expanded = it.expanded + groupKey(builtin = false, categoryId),
            error = null,
        )
    }

    fun setAddText(value: String) = state.update {
        it.copy(adding = it.adding?.copy(text = value, error = null))
    }

    fun cancelAdd() = state.update { it.copy(adding = null) }

    /** 分类面板回来：新增就开加词框，换绑就整组 / 单词搬走。 */
    fun pickCategory(level1Id: String, level2Id: String?) {
        val categoryId = level2Id ?: level1Id
        val current = state.value
        when (val picker = current.picker) {
            null -> Unit

            KeywordPicker.NewGroup -> {
                state.update {
                    it.copy(
                        picker = null,
                        adding = AddWordsState(categoryId),
                        expanded = it.expanded + groupKey(builtin = false, categoryId),
                    )
                }
            }

            is KeywordPicker.GroupRebind -> {
                val ids = current.snapshot?.rules.orEmpty()
                    .filter { it.categoryId == picker.categoryId }
                    .map { it.id }
                val name = current.snapshot?.categoryPath(categoryId) ?: "新分类"
                state.update { it.copy(picker = null) }
                runWrite(
                    notice = "已把 ${ids.size} 个词搬到「$name」",
                    operation = { repository.updateCategoryFor(ids, categoryId) },
                )
            }

            is KeywordPicker.WordRebind -> {
                val name = current.snapshot?.categoryPath(categoryId) ?: "新分类"
                state.update { it.copy(picker = null) }
                runWrite(
                    notice = "已把「${picker.rule.keyword}」改绑到「$name」",
                    operation = { repository.updateCategory(picker.rule.id, categoryId) },
                )
            }
        }
    }

    fun saveAdd() {
        val adding = state.value.adding ?: return
        val words = adding.text.split(wordSeparators).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (words.isEmpty()) {
            state.update { it.copy(adding = adding.copy(error = "先写一个词")) }
            return
        }
        state.update { it.copy(adding = adding.copy(saving = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = repository.addMany(words, adding.categoryId)
                val snapshot = repository.snapshot()
                val message = when {
                    result.added.isEmpty() -> "这些词都已经有了，没有新增"
                    result.skipped.isEmpty() -> "已加 ${result.added.size} 个词"
                    else -> "已加 ${result.added.size} 个，跳过 ${result.skipped.size} 个已有的"
                }
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(snapshot = snapshot, adding = null, notice = message) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: TallyError) {
                withContext(Dispatchers.Main.immediate) {
                    state.update {
                        it.copy(
                            adding = it.adding?.copy(saving = false, error = failure.message),
                        )
                    }
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    state.update {
                        it.copy(
                            adding = it.adding?.copy(saving = false, error = failure.message ?: "这些词没有保存"),
                        )
                    }
                }
            }
        }
    }

    /** 组 ⋯ 里的删除：先收起弹层，再由页面弹确认。 */
    fun requestGroupDelete(categoryId: String) = state.update {
        it.copy(groupSheet = null, pendingGroupDelete = categoryId)
    }

    fun cancelGroupDelete() = state.update { it.copy(pendingGroupDelete = null) }

    fun confirmGroupDelete() {
        val categoryId = state.value.pendingGroupDelete ?: return
        state.update { it.copy(pendingGroupDelete = null) }
        deleteGroup(categoryId)
    }

    fun deleteWord(rule: KeywordRuleEntity) {
        state.update { it.copy(wordSheet = null) }
        runWrite(
            notice = "已删除关键词「${rule.keyword}」",
            undoIds = listOf(rule.id),
            operation = { repository.remove(rule.id) },
        )
    }

    fun deleteGroup(categoryId: String) {
        val snapshot = state.value.snapshot ?: return
        val ids = snapshot.rules.filter { it.categoryId == categoryId }.map { it.id }
        if (ids.isEmpty()) return
        val name = snapshot.categoryPath(categoryId) ?: "这一组"
        state.update { it.copy(groupSheet = null) }
        runWrite(
            notice = "已删除「$name」分类下的 ${ids.size} 个词",
            undoIds = ids,
            operation = { repository.removeMany(ids) },
        )
    }

    /** 撤销删除：气泡上的动作，8 秒内有效。 */
    fun undoDelete() {
        val ids = state.value.undoIds
        if (ids.isEmpty()) return
        state.update { it.copy(undoIds = emptyList()) }
        runWrite(
            notice = if (ids.size == 1) "已恢复这条词" else "已恢复 ${ids.size} 个词",
            operation = { repository.restore(ids) },
        )
    }

    fun dismissNotice() = state.update { it.copy(notice = null) }

    fun dismissError() = state.update { it.copy(error = null) }

    /** 写完重取快照；快照是页面唯一的读来源，UI 不自己拼状态。 */
    private fun runWrite(notice: String, undoIds: List<String> = emptyList(), operation: suspend () -> Unit) {
        if (undoIds.isNotEmpty()) {
            state.update { it.copy(undoIds = undoIds) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                operation()
                val snapshot = repository.snapshot()
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(snapshot = snapshot, notice = notice, error = null) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(error = failure.message ?: "操作没有完成") }
                }
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = repository.snapshot()
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(snapshot = snapshot, loaded = true, error = null) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(loaded = true, error = failure.message ?: "规则读不出来") }
                }
            }
        }
    }

    private companion object {
        /** 空格与顿号都能分词：键盘上没有顿号时也不会把所有词粘成一条。 */
        val wordSeparators = Regex("[\\s、,，]+")
    }
}
