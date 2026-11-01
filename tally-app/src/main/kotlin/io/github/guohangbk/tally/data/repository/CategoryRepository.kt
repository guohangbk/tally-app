package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.KeywordRuleDao
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.SortOrder
import io.github.guohangbk.tally.domain.model.Category
import io.github.guohangbk.tally.domain.model.CategoryNode
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.DefaultCategoryTree
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.buildTree
import io.github.guohangbk.tally.domain.model.toDomain
import io.github.guohangbk.tally.data.db.dao.BillDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 分类职责：两级树、增删改名、同层排序、图标键、归档与删除迁移。
 * 分类是全机共用的基础数据，与账本无关；二级分类的父级必须与它同收支类型。
 */
class CategoryRepository(
    private val categoryDao: CategoryDao,
    private val billDao: BillDao,
    private val keywordRuleDao: KeywordRuleDao,
    private val preferences: TallyPreferences,
    /**
     * 图标键是否收录在图标库里。未收录的键会静默退化成名称首字 tile，
     * 所以写库之前就拒；默认放开是为了让测试与备份路径不必造出整个图标库。
     */
    private val isKnownIconKey: (String) -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private fun requireKnownIcon(iconKey: String) {
        if (!isKnownIconKey(iconKey)) throw TallyError.IconKeyUnknown
    }

    fun tree(type: CategoryType): Flow<List<CategoryNode>> =
        categoryDao.observeTree(type.name).map { it.map(CategoryEntity::toDomain).buildTree() }

    suspend fun categories(type: CategoryType): List<Category> =
        categoryDao.findTreeOnce(type.name).map { it.toDomain() }

    suspend fun find(id: String): Category? = categoryDao.findById(id)?.toDomain()

    /** 全机还没有一级分类时回载系统默认分类树。 */
    suspend fun ensureDefaultTree(): Boolean {
        if (categoryDao.countLevel1() > 0) return false
        categoryDao.insertAll(DefaultCategoryTree.build(preferences.deviceId(), now()))
        return true
    }

    suspend fun addLevel1(type: CategoryType, name: String, iconKey: String): Category {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw TallyError.EmptyName
        requireKnownIcon(iconKey)
        val timestamp = now()
        val last = categoryDao.maxSortOrder(type.name, parentId = null)
        val entity = CategoryEntity(
            id = newId(),
            parentId = null,
            name = trimmed,
            type = type.name,
            iconKey = iconKey,
            color = null,
            sortOrder = SortOrder.afterLast(last),
            isSystem = false,
            isArchived = false,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        categoryDao.insert(entity)
        return entity.toDomain()
    }

    /**
     * 二级有自己的图标，不再继承父级（F4.10）；没选图标时用名称首字兜底。
     */
    suspend fun addLevel2(parentId: String, name: String, iconKey: String? = null): Category {
        val parent = categoryDao.findById(parentId) ?: throw TallyError.CategoryNotFound
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw TallyError.EmptyName
        // 没选图标时落名称首字是设计好的兜底，只有「选了但不认识」才拒。
        iconKey?.takeIf { it.isNotBlank() }?.let(::requireKnownIcon)
        val timestamp = now()
        val last = categoryDao.maxSortOrder(parent.type, parentId)
        val entity = CategoryEntity(
            id = newId(),
            parentId = parent.id,
            name = trimmed,
            type = parent.type,
            iconKey = iconKey?.takeIf { it.isNotBlank() } ?: trimmed.first().toString(),
            color = parent.color,
            sortOrder = SortOrder.afterLast(last),
            isSystem = false,
            isArchived = false,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        categoryDao.insert(entity)
        return entity.toDomain()
    }

    /** 同级同类型重名要在提交前就标出来，不弹全局提示（F7.6）。 */
    suspend fun namesInUse(type: CategoryType, parentId: String?): Set<String> =
        categoryDao.findTreeOnce(type.name)
            .filter { it.parentId == parentId }
            .mapTo(mutableSetOf()) { it.name.trim() }

    /**
     * 一次性存量映射：旧二级全部继承父级图标，只有名称精确命中图标库默认名才替换，
     * 未命中保持原样——名字对不上却换了图，比继续丑更糟。lookup 返回 null 表示不动这一条。
     */
    suspend fun remapChildIcons(lookup: (name: String, childKey: String, parentKey: String) -> String?): Int {
        var changed = 0
        categoryDao.findAllChildren().forEach { child ->
            val parent = child.parentId?.let { categoryDao.findById(it) } ?: return@forEach
            val mapped = lookup(child.name, child.iconKey, parent.iconKey) ?: return@forEach
            if (mapped == child.iconKey) return@forEach
            categoryDao.update(child.copy(iconKey = mapped, updatedAt = now()))
            changed++
        }
        return changed
    }

    /** 默认一级图标的修正：只在仍是旧默认键时替换，用户换过的不动。 */
    suspend fun remapLevel1Icon(name: String, from: String, to: String): Int {
        var changed = 0
        categoryDao.findAllParents().forEach { parent ->
            if (parent.name != name || parent.iconKey != from) return@forEach
            categoryDao.update(parent.copy(iconKey = to, updatedAt = now()))
            changed++
        }
        return changed
    }

    suspend fun rename(id: String, name: String) {
        val entity = categoryDao.findById(id) ?: throw TallyError.CategoryNotFound
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw TallyError.EmptyName
        categoryDao.update(entity.copy(name = trimmed, updatedAt = now(), version = entity.version + 1))
    }

    /** 图标只写 `icon_key`，不写图片路径。 */
    suspend fun setIcon(id: String, iconKey: String) {
        val entity = categoryDao.findById(id) ?: throw TallyError.CategoryNotFound
        val key = iconKey.trim()
        if (key.isEmpty()) throw TallyError.CategoryIconEmpty
        requireKnownIcon(key)
        categoryDao.update(entity.copy(iconKey = key, updatedAt = now(), version = entity.version + 1))
    }

    /**
     * 归档只作用于自身：一级归档时子级不再被连带写 1，
     * 它们看不见是因为查询侧按父级状态判定（`CategoryDao.findActive`）。
     * 这样二级才能单独归档、单独恢复，恢复一级也不会把用户自己归档的二级偷偷放出来。
     */
    suspend fun setArchived(id: String, archived: Boolean) {
        val entity = categoryDao.findById(id) ?: throw TallyError.CategoryNotFound
        categoryDao.setArchived(entity.id, archived, now())
    }

    suspend fun billCount(id: String): Int = billDao.countBillsUsing(id)

    /** 分类管理首屏用：一条聚合查询给出全部分类的笔数，口径同 [billCount]。 */
    fun observeBillCounts(): Flow<Map<String, Int>> =
        categoryDao.observeBillCounts().map { rows -> rows.associate { it.id to it.count } }

    /** 同层拖拽排序；层级内值出现重复时重新编号该层。 */
    suspend fun move(id: String, targetIndex: Int) {
        val entity = categoryDao.findById(id) ?: throw TallyError.CategoryNotFound
        val siblings = siblingsOf(entity)
        if (SortOrder.needsRenumber(siblings.map { it.id to it.sortOrder })) {
            val reordered = siblings.toMutableList().apply { add(targetIndex.coerceIn(0, lastIndex), removeAt(indexOfFirst { it.id == id })) }
            val values = SortOrder.renumber(reordered.size)
            categoryDao.updateAll(
                reordered.mapIndexed { index, sibling ->
                    sibling.copy(sortOrder = values[index], updatedAt = now(), version = sibling.version + 1)
                },
            )
            return
        }
        val updates = SortOrder.reorder(siblings.map { it.id to it.sortOrder }, id, targetIndex)
        if (updates.isEmpty()) return
        val timestamp = now()
        categoryDao.updateAll(
            siblings.mapNotNull { sibling ->
                updates[sibling.id]?.let { value ->
                    sibling.copy(sortOrder = value, updatedAt = timestamp, version = sibling.version + 1)
                }
            },
        )
    }

    /**
     * 二级改挂到另一个一级之下，排序落到新父级末尾。
     * 两级为上限，所以目标必须是一级，且收支类型一致。
     */
    suspend fun moveToParent(childId: String, newParentId: String) {
        val child = categoryDao.findById(childId) ?: throw TallyError.CategoryNotFound
        val parent = categoryDao.findById(newParentId) ?: throw TallyError.CategoryNotFound
        if (child.parentId == null || parent.parentId != null || parent.type != child.type) {
            throw TallyError.CategoryTargetInvalid
        }
        if (child.parentId == newParentId) return
        val last = categoryDao.maxSortOrder(parent.type, parentId = newParentId)
        categoryDao.update(
            child.copy(
                parentId = newParentId,
                sortOrder = SortOrder.afterLast(last),
                updatedAt = now(),
                version = child.version + 1,
            ),
        )
    }

    /**
     * 删除分类。存在账单引用时必须给出同层同类型的迁移目标，null 表示「未分类」。
     * 一级分类的二级子分类与账单按同一规则级联处理；挂在这些分类上的关键词规则一并软删。
     */
    suspend fun delete(id: String, targetId: String?): Int {
        val source = categoryDao.findById(id) ?: throw TallyError.CategoryNotFound
        val timestamp = now()
        var affected = 0
        if (source.parentId == null) {
            val children = categoryDao.findChildren(id)
            if (targetId != null) {
                val target = categoryDao.findById(targetId) ?: throw TallyError.CategoryNotFound
                requireSameLevelAndType(source, target, parentId = null)
                affected += billDao.reassignCategory(id, target.id, null, timestamp)
            } else {
                affected += billDao.detachCategoryL1(listOf(id), timestamp)
            }
            if (children.isNotEmpty()) {
                affected += billDao.reassignCategoryL2(children.map { it.id }, null, timestamp)
                categoryDao.softDeleteSubtree(id, timestamp)
            } else {
                categoryDao.softDelete(id, timestamp)
            }
            // 关键词规则按分类 id 绑定；分类没了，挂着它的词也不该再参与解析。
            keywordRuleDao.softDeleteByCategories(listOf(id) + children.map { it.id }, timestamp)
        } else {
            val l2Target = if (targetId != null) {
                val target = categoryDao.findById(targetId) ?: throw TallyError.CategoryNotFound
                requireSameLevelAndType(source, target, parentId = source.parentId)
                target.id
            } else {
                null
            }
            affected += billDao.reassignCategoryL2(listOf(id), l2Target, timestamp)
            categoryDao.softDelete(id, timestamp)
            keywordRuleDao.softDeleteByCategories(listOf(id), timestamp)
        }
        return affected
    }

    private fun requireSameLevelAndType(source: CategoryEntity, target: CategoryEntity, parentId: String?) {
        val sameLevel = source.parentId == target.parentId && target.parentId == parentId
        if (!sameLevel || source.type != target.type) throw TallyError.CategoryTargetInvalid
    }

    private suspend fun siblingsOf(entity: CategoryEntity): List<CategoryEntity> =
        if (entity.parentId == null) {
            categoryDao.findTreeOnce(entity.type).filter { it.parentId == null }
        } else {
            categoryDao.findChildren(entity.parentId)
        }
}
