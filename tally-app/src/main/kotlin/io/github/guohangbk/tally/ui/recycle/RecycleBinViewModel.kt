package io.github.guohangbk.tally.ui.recycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.PurgePreview
import io.github.guohangbk.tally.domain.model.RecycleGroup
import io.github.guohangbk.tally.domain.model.RecycleKeepDays
import io.github.guohangbk.tally.domain.model.RecycleSnapshot
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 回收站页的状态。
 *
 * 选择态是**显式**的：只有长按进来之后的行上才有复选框，底部才出操作条。
 * 一屏几十笔的地方默认就画勾选框，会让人以为点「恢复」也要先勾。
 */
data class RecycleBinUiState(
    val snapshot: RecycleSnapshot = RecycleSnapshot(),
    val loading: Boolean = true,
    /** 展开的删除日。默认空 = 全部折叠：一天可能几十笔，全展开既读不动也把转场的首帧拖没。 */
    val expanded: Set<LocalDate> = emptySet(),
    val selected: Set<String> = emptySet(),
    /** 非空就是「彻底删除」确认框开着，框里的代价要说清。 */
    val purgePreview: PurgePreview? = null,
    val showKeepDays: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
    val selectedCount: Int get() = selected.size
}

class RecycleBinViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(RecycleBinUiState())
    val state = mutable.asStateFlow()

    private val repository get() = graph.recycleBinRepository

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.snapshot() }
                .onSuccess { snapshot ->
                    // 选择态里可能躺着已经被上一轮清理掉的账单，刷新时一并丢掉。
                    val alive = snapshot.groups.flatMap { group -> group.items.map { it.billId } }.toSet()
                    mutable.update { it.copy(snapshot = snapshot, loading = false, selected = it.selected.filterIn(alive)) }
                }
                .onFailure { failure ->
                    mutable.update { it.copy(loading = false, error = failure.message ?: "回收站读不出来") }
                }
        }
    }

    fun dismissMessages() = mutable.update { it.copy(notice = null, error = null) }

    // ---- 选择 -------------------------------------------------------------

    fun toggleExpanded(day: LocalDate) = mutable.update { current ->
        current.copy(expanded = if (day in current.expanded) current.expanded - day else current.expanded + day)
    }

    fun toggleSelected(id: String) = mutable.update { current ->
        val next = if (id in current.selected) current.selected - id else current.selected + id
        current.copy(selected = next)
    }

    /** 长按整组：把这一删除日的所有笔次一次选中。 */
    fun selectGroup(group: RecycleGroup) = mutable.update { current ->
        val ids = group.items.map { it.billId }
        current.copy(selected = current.selected + ids)
    }

    fun clearSelection() = mutable.update { it.copy(selected = emptySet()) }

    // ---- 恢复 -------------------------------------------------------------

    fun restoreOne(id: String) = restore(listOf(id), single = true)

    fun restoreGroup(group: RecycleGroup) = restore(group.items.map { it.billId }, single = false)

    fun restoreSelected() = restore(mutable.value.selected.toList(), single = false)

    private fun restore(ids: List<String>, single: Boolean) {
        if (ids.isEmpty()) return
        run("已恢复 ${ids.size} 笔") {
            val result = repository.restore(ids)
            if (result.recategorized > 0) {
                "${result.restored} 笔已恢复 · 其中 ${result.recategorized} 笔的原分类已不存在，先归为未分类"
            } else {
                if (single) "已恢复这一笔" else "已恢复 ${result.restored} 笔"
            }
        }
    }

    // ---- 彻底删除 ---------------------------------------------------------

    /** 开框前先算清代价：几笔、多少钱、回收几张图、几张共用要留下。 */
    fun requestPurge() {
        val ids = mutable.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.previewPurge(ids) }
                .onSuccess { preview -> mutable.update { it.copy(purgePreview = preview, error = null) } }
                .onFailure { failure -> mutable.update { it.copy(error = failure.message ?: "算不出这次删除的代价") } }
        }
    }

    fun dismissPurge() = mutable.update { it.copy(purgePreview = null) }

    fun confirmPurge() {
        val ids = mutable.value.selected.toList()
        mutable.update { it.copy(purgePreview = null) }
        if (ids.isEmpty()) return
        run("已彻底删除 ${ids.size} 笔") {
            val result = repository.purge(ids)
            if (result.deferredBills > 0) {
                "已删除 ${result.purgedBills} 笔 · ${result.deferredBills} 笔的图片暂时删不掉，明天自动再试"
            } else {
                "已彻底删除 ${result.purgedBills} 笔"
            }
        }
    }

    // ---- 保留期 -----------------------------------------------------------

    fun showKeepDays() = mutable.update { it.copy(showKeepDays = true) }

    fun dismissKeepDays() = mutable.update { it.copy(showKeepDays = false) }

    fun setKeepDays(days: RecycleKeepDays) = run("保留期已设为「${days.label}」") {
        repository.setKeepDays(days)
        mutable.update { it.copy(showKeepDays = false, snapshot = it.snapshot.copy(keepDays = days)) }
        null
    }

    /**
     * 写操作统一走这一条：成功就把提示交给气泡，失败就把异常里的用户语言原样带出。
     * 块返回 null 用 [defaultNotice]，返回文字则覆盖它——恢复时要不要提「原分类已不存在」
     * 只有仓库知道，所以那条得由块来说。
     */
    private fun run(defaultNotice: String, block: suspend () -> String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val notice = block()
                reloadQuietly(notice ?: defaultNotice)
            } catch (error: TallyError) {
                mutable.update { it.copy(error = error.message) }
            } catch (failure: Exception) {
                mutable.update { it.copy(error = failure.message ?: "这一步没有完成") }
            }
        }
    }

    /** 写完重读一遍列表，同时把提示带出去——「恢复后回到首页原位」只有重读才看得见。 */
    private suspend fun reloadQuietly(notice: String) {
        val snapshot = runCatching { repository.snapshot() }.getOrNull()
        val alive = snapshot?.groups?.flatMap { group -> group.items.map { it.billId } }?.toSet()
            ?: emptySet()
        mutable.update { current ->
            current.copy(
                snapshot = snapshot ?: current.snapshot,
                selected = current.selected.filterIn(alive),
                notice = notice,
                error = null,
            )
        }
    }

    private fun Set<String>.filterIn(allowed: Set<String>): Set<String> = filterTo(mutableSetOf()) { it in allowed }
}
