package io.github.guohangbk.tally.data.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 保存后沉淀（LN-01）。记账页在保存成功后会立刻出栈、ViewModel 随之销毁，
 * 而「以后都记到 X」是一次点按才发生的写入，所以放在应用级作用域里，不挂在页面生命周期上。
 *
 * 失败不打扰用户：这只是一条锦上添花的规则，账单本身已经落库。
 */
class KeywordSediment(
    private val rules: KeywordRuleRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    fun remember(keyword: String, categoryId: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        scope.launch { runCatching { rules.learn(trimmed, categoryId) } }
    }
}
