package io.github.guohangbk.tally.domain.model

import io.github.guohangbk.tally.data.db.entity.CategoryEntity

data class Category(
    val id: String,
    val parentId: String?,
    val name: String,
    val type: CategoryType,
    val iconKey: String,
    val color: String? = null,
    val sortOrder: Double,
    val isSystem: Boolean = false,
    val isArchived: Boolean = false,
)

/** 一级分类及其二级子分类。 */
data class CategoryNode(
    val category: Category,
    val children: List<Category>,
)

/** 分类管理页的整棵树，按收支两路分开；启动预热跑一遍之后，首帧直接拿它播种。 */
data class CategoryTreeSeed(
    val expense: List<CategoryNode> = emptyList(),
    val income: List<CategoryNode> = emptyList(),
) {
    val isEmpty: Boolean get() = expense.isEmpty() && income.isEmpty()
}

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    parentId = parentId,
    name = name,
    type = CategoryType.valueOf(type),
    iconKey = iconKey,
    color = color,
    sortOrder = sortOrder,
    isSystem = isSystem,
    isArchived = isArchived,
)

fun Category.toEntity(createdAt: Long, updatedAt: Long, deviceId: String, version: Int = 1): CategoryEntity =
    CategoryEntity(
        id = id,
        parentId = parentId,
        name = name,
        type = type.name,
        iconKey = iconKey,
        color = color,
        sortOrder = sortOrder,
        isSystem = isSystem,
        isArchived = isArchived,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deviceId = deviceId,
        version = version,
    )

/** 平铺列表组装成两级树；父级缺失的二级按一级展示，不产生孤儿。 */
fun List<Category>.buildTree(): List<CategoryNode> {
    val parents = filter { it.parentId == null }.sortedBy { it.sortOrder }
    val childMap = groupBy { it.parentId }
    return parents.map { node(it, childMap) }
}

private fun node(parent: Category, childMap: Map<String?, List<Category>>) = CategoryNode(
    category = parent,
    children = childMap[parent.id].orEmpty().sortedBy { it.sortOrder },
)
