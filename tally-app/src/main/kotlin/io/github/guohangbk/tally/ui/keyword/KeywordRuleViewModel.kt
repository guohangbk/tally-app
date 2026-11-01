package io.github.guohangbk.tally.ui.keyword

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.ai.KeywordRuleRepository
import io.github.guohangbk.tally.data.ai.KeywordRuleSnapshot
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 新增与改绑共用一张弹层：只有分类必填，关键词在改绑时不可改（同名即同一条）。 */
data class KeywordSheetState(
    val ruleId: String? = null,
    val keyword: String = "",
    val type: CategoryType = CategoryType.EXPENSE,
    val categoryId: String? = null,
    val showPicker: Boolean = false,
) {
    val editing: Boolean get() = ruleId != null
}

data class KeywordRuleUiState(
    val loaded: Boolean = false,
    val query: String = "",
    val snapshot: KeywordRuleSnapshot? = null,
    val sheet: KeywordSheetState? = null,
    val actionRule: KeywordRuleEntity? = null,
    val pendingDelete: KeywordRuleEntity? = null,
    val saving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    /** 弹层里的分类候选只给当前收支类型，避免把支出词绑到收入分类。 */
    val sheetCategories: List<CategoryEntity>
        get() {
            val current = sheet ?: return emptyList()
            return snapshot?.activeCategories.orEmpty().filter { it.type == current.type.name }
        }

    val sheetCategory: CategoryEntity?
        get() = sheet?.categoryId?.let { id -> snapshot?.categories?.firstOrNull { it.id == id } }

    val sheetCategoryPath: String?
        get() = sheet?.categoryId?.let { id -> snapshot?.categoryPath(id) }
}

class KeywordRuleViewModel(private val graph: AppGraph) : ViewModel() {
    private val repository: KeywordRuleRepository = graph.keywordRuleRepository
    private val state = MutableStateFlow(KeywordRuleUiState())
    val uiState: StateFlow<KeywordRuleUiState> = state.asStateFlow()

    init {
        refresh()
    }

    fun setQuery(value: String) = state.update { it.copy(query = value) }

    fun openAdd() = state.update { it.copy(sheet = KeywordSheetState()) }

    fun openActions(rule: KeywordRuleEntity) = state.update { it.copy(actionRule = rule) }

    fun closeActions() = state.update { it.copy(actionRule = null) }

    fun openRebind(rule: KeywordRuleEntity) {
        val type = state.value.snapshot?.category(rule.categoryId)?.type
            ?.let { runCatching { CategoryType.valueOf(it) }.getOrNull() }
            ?: CategoryType.EXPENSE
        state.update {
            it.copy(
                actionRule = null,
                sheet = KeywordSheetState(ruleId = rule.id, keyword = rule.keyword, type = type, categoryId = rule.categoryId),
            )
        }
    }

    fun closeSheet() = state.update { it.copy(sheet = null) }

    fun setKeyword(value: String) = state.update {
        it.copy(error = null, sheet = it.sheet?.copy(keyword = value.take(KEYWORD_MAX)))
    }

    fun setType(type: CategoryType) = state.update {
        val sheet = it.sheet ?: return@update it
        // 收支一换，之前选的分类必然不再成立。
        it.copy(sheet = sheet.copy(type = type, categoryId = null))
    }

    fun openPicker() = state.update { it.copy(sheet = it.sheet?.copy(showPicker = true)) }

    fun closePicker() = state.update { it.copy(sheet = it.sheet?.copy(showPicker = false)) }

    fun pickCategory(level1Id: String, level2Id: String?) = state.update {
        it.copy(error = null, sheet = it.sheet?.copy(categoryId = level2Id ?: level1Id, showPicker = false))
    }

    fun save() {
        val sheet = state.value.sheet ?: return
        val keyword = sheet.keyword.trim()
        val categoryId = sheet.categoryId
        if (keyword.isEmpty()) {
            state.update { it.copy(error = "关键词不能为空") }
            return
        }
        if (categoryId == null) {
            state.update { it.copy(error = "先选一个分类") }
            return
        }
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ruleId = sheet.ruleId
                if (ruleId == null) {
                    repository.add(keyword, categoryId)
                } else {
                    repository.updateCategory(ruleId, categoryId)
                }
                val snapshot = repository.snapshot()
                val notice = if (ruleId == null) "已加一条「$keyword」" else "已改绑「$keyword」"
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(snapshot = snapshot, sheet = null, saving = false, notice = notice) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: TallyError) {
                withContext(Dispatchers.Main.immediate) {
                    state.update {
                        it.copy(
                            saving = false,
                            error = when (failure) {
                                TallyError.EmptyName -> "关键词不能为空"
                                TallyError.KeywordExists -> "已经有「$keyword」了，改绑那条就行"
                                else -> failure.message
                            },
                        )
                    }
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(saving = false, error = failure.message ?: "这条规则没有保存") }
                }
            }
        }
    }

    fun requestDelete(rule: KeywordRuleEntity) = state.update {
        it.copy(actionRule = null, pendingDelete = rule)
    }

    fun cancelDelete() = state.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val rule = state.value.pendingDelete ?: return
        state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.remove(rule.id)
                val snapshot = repository.snapshot()
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(snapshot = snapshot, notice = "已删除「${rule.keyword}」") }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(error = failure.message ?: "这条规则没有删掉") }
                }
            }
        }
    }

    fun dismissNotice() = state.update { it.copy(notice = null) }

    fun dismissError() = state.update { it.copy(error = null) }

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
        /** 与新增弹层的计数器一致。 */
        const val KEYWORD_MAX = 20
    }
}
