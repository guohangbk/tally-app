package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.KeywordRuleDao
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.ai.AiCategoryRef
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.parser.BuiltinKeywordGroup
import io.github.guohangbk.tally.domain.parser.KeywordMapping
import io.github.guohangbk.tally.domain.parser.QuickNoteCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/**
 * 端侧关键词表（用户可编辑，内置词表随 App 附带）。
 *
 * 内置词表不落库，只在解析时与用户词表合并，用户词表优先级更高；
 * 这样升级 App 加新词不会覆盖用户自己的习惯，删掉一条用户词也不会连带删掉内置词。
 * 全机一套，不带账本维度。
 */
class KeywordRuleRepository(
    private val dao: KeywordRuleDao,
    private val categoryDao: CategoryDao,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * 管理页的一次性快照。分类同时取「全部未删除」与「当前可用」两份：
     * 前者用来给历史规则念出名字（分类归档了也要有名字），后者决定内置词组是否仍然生效。
     */
    suspend fun snapshot(): KeywordRuleSnapshot {
        val categories = categoryDao.findCategories()
        val active = categoryDao.findActiveAll()
        val activeRefs = active.map { it.toRef() }
        return KeywordRuleSnapshot(
            categories = categories,
            activeCategories = active,
            rules = dao.findAll(),
            builtin = QuickNoteCatalog.builtinGroups(activeRefs),
        )
    }

    /**
     * 一次往同一个分类加多个词；已存在的词直接跳过，不报错也不改绑它的分类。
     * 分组页的「就地加词」一次写一批，重名按界面文案处理成「保存时跳过」。
     */
    suspend fun addMany(keywords: List<String>, categoryId: String, source: String = KeywordSource.MANUAL): AddKeywordsResult {
        categoryDao.findById(categoryId) ?: throw TallyError.CategoryNotFound
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        keywords.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { keyword ->
                if (dao.findKeyword(keyword) != null) {
                    skipped += keyword
                } else {
                    insert(keyword, categoryId, source)
                    added += keyword
                }
            }
        return AddKeywordsResult(added = added, skipped = skipped)
    }

    /**
     * 记账页沉淀：用户把机器预填的分类改掉，并点了「以后都这样」。
     * 同名规则已经存在时只改绑，不再插一条——同名即同一条是全机唯一性的前提。
     */
    suspend fun learn(keyword: String, categoryId: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        categoryDao.findById(categoryId) ?: return
        val existing = dao.findKeyword(trimmed)
        if (existing != null) {
            if (existing.categoryId != categoryId) dao.updateCategory(existing.id, categoryId, now())
            return
        }
        insert(trimmed, categoryId, KeywordSource.LEARNED)
    }

    suspend fun updateCategory(id: String, categoryId: String) {
        categoryDao.findById(categoryId) ?: throw TallyError.CategoryNotFound
        dao.updateCategory(id, categoryId, now())
    }

    /** 整组换分类：一个分类下的词一起搬走。 */
    suspend fun updateCategoryFor(ids: List<String>, categoryId: String) {
        categoryDao.findById(categoryId) ?: throw TallyError.CategoryNotFound
        val timestamp = now()
        ids.forEach { dao.updateCategory(it, categoryId, timestamp) }
    }

    suspend fun remove(id: String) {
        dao.softDelete(id, now())
    }

    /** 整组删除：逐条软删，撤销时按同一批 id 救回来。 */
    suspend fun removeMany(ids: List<String>) {
        val timestamp = now()
        ids.forEach { dao.softDelete(it, timestamp) }
    }

    /** 撤销删除。只认这一批 id，不会把更早删掉的词一起带回来。 */
    suspend fun restore(ids: List<String>) {
        if (ids.isNotEmpty()) dao.restore(ids, now())
    }

    /** 解析前现取一次；一次性读者用（对话页每次抽账前都要拿最新的表）。 */
    suspend fun catalog(): QuickNoteCatalog {
        val categories = categoryDao.findActiveAll().map { it.toRef() }
        val userKeywords = dao.findAll().map { KeywordMapping(it.keyword, it.categoryId, it.priority) }
        return QuickNoteCatalog.of(categories, userKeywords)
    }

    /**
     * 解析用的关键词目录，跟着分类表与关键词表的变化走。
     *
     * 沉淀气泡、关键词管理页写的都是同一张 `keyword_rule`，而语音与截图页活在一次会话里——
     * 只在进入页面时取一次的话，刚点「确定」的规则要等杀进程才生效。Room 在表写入后会让
     * 这两个 Flow 重新发射，所以任何入口写规则，正在用的解析当场就能读到。
     */
    fun observeCatalog(): Flow<QuickNoteCatalog> = combine(
        categoryDao.observeActiveAll(),
        dao.observeAll(),
    ) { categories, rules ->
        QuickNoteCatalog.of(
            categories = categories.map { it.toRef() },
            userKeywords = rules.map { KeywordMapping(it.keyword, it.categoryId, it.priority) },
        )
    }

    private suspend fun insert(keyword: String, categoryId: String, source: String) {
        val timestamp = now()
        dao.upsert(
            KeywordRuleEntity(
                id = newId(),
                keyword = keyword,
                categoryId = categoryId,
                source = source,
                // 用户词表压过内置词表（内置表用的是 priority = 10）。
                priority = USER_PRIORITY,
                createdAt = timestamp,
                updatedAt = timestamp,
                deviceId = preferences.deviceId(),
            ),
        )
    }

    private fun CategoryEntity.toRef(): AiCategoryRef = AiCategoryRef(
        id = id,
        name = name,
        parentId = parentId,
        type = runCatching { CategoryType.valueOf(type) }.getOrDefault(CategoryType.EXPENSE),
    )

    private companion object {
        const val USER_PRIORITY = 20
    }
}

/** 批量加词的结果：重名的词不报错，交给界面说一句「跳过」。 */
data class AddKeywordsResult(
    val added: List<String>,
    val skipped: List<String>,
)

/** 管理页一屏所需的全部只读数据；规则写入后由页面重新取一次。 */data class KeywordRuleSnapshot(
    val categories: List<CategoryEntity>,
    val activeCategories: List<CategoryEntity>,
    val rules: List<KeywordRuleEntity>,
    val builtin: List<BuiltinKeywordGroup>,
) {
    private val byId: Map<String, CategoryEntity> = categories.associateBy { it.id }

    fun category(categoryId: String): CategoryEntity? = byId[categoryId]

    /** 二级写成「一级 · 二级」；分类已被删时返回 null，由页面显示警示态。 */
    fun categoryPath(categoryId: String): String? {
        val child = byId[categoryId] ?: return null
        val parent = child.parentId?.let(byId::get)
        return if (parent != null) "${parent.name} · ${child.name}" else child.name
    }
}

/** `keyword_rule.source` 的取值；`BUILTIN` 预留给以后真的落库的内置词组。 */
object KeywordSource {
    const val MANUAL = "MANUAL"
    const val LEARNED = "LEARNED"
    const val BUILTIN = "BUILTIN"
}
