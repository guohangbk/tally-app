package io.github.guohangbk.tally.domain.model

import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.LedgerEntity

/**
 * 默认分类树来自 实测数据分布，不凭猜测：
 * 钱迹 232 条的 18 个一级分类 + 支付宝补充的独立一级分类，二级取钱迹实测 11 个取值。
 * 生活日用 / 水果零食 / 穿搭美容 与日用品 / 零食 / 衣服+美妆 合并去重。
 */
data class CategorySpec(
    val slug: String,
    val name: String,
    val iconKey: String,
    val children: List<ChildSpec> = emptyList(),
)

/** 二级默认自带图标，不再复制父级的键——同父同图正是二级千篇一律的根因。 */
data class ChildSpec(val slug: String, val name: String, val iconKey: String)

object DefaultCategoryTree {
    /** 相邻排序值留出中点空间，重排不重写全表。 */
    const val SORT_STEP = 100.0

    val expense: List<CategorySpec> = listOf(
        CategorySpec("food", "三餐", "food", listOf(ChildSpec("bread", "面包", "bread"))),
        CategorySpec(
            "transport", "交通", "bus",
            listOf(
                ChildSpec("charge", "充电", "charge"),
                ChildSpec("park", "停车费", "park"),
                ChildSpec("toll", "过路费", "toll"),
            ),
        ),
        CategorySpec("daily", "日用品", "bag"),
        CategorySpec(
            "snack", "零食", "snack",
            listOf(
                ChildSpec("drink", "饮料", "tea"),
                ChildSpec("coffee", "咖啡", "cup"),
                ChildSpec("water", "纯净水", "water"),
                ChildSpec("fruit", "水果", "fruit"),
            ),
        ),
        CategorySpec("fun", "娱乐", "game"),
        CategorySpec("medical", "医疗", "med"),
        CategorySpec("utility", "水电煤", "bolt"),
        CategorySpec("digital", "电器数码", "diamond"),
        CategorySpec("insurance", "保险", "shield", listOf(ChildSpec("car", "车险", "car_shield"))),
        CategorySpec("housing", "住房", "home"),
        CategorySpec("gifting", "请客送礼", "gift", listOf(ChildSpec("treat", "请客", "treat"))),
        CategorySpec("beauty", "美妆", "brush"),
        CategorySpec("redpacket", "发红包", "hongbao"),
        CategorySpec("telecom", "话费网费", "phone", listOf(ChildSpec("member", "付费会员", "member"))),
        CategorySpec("party", "聚会", "cake"),
        CategorySpec("clothes", "衣服", "shirt"),
        CategorySpec("study", "学习", "book"),
        CategorySpec("other", "其它", "note"),
        // 支付宝侧补充的独立一级分类
        CategorySpec("appliance", "家居家电", "sofa"),
        CategorySpec("car", "爱车", "car"),
        CategorySpec("shopping", "购物", "cart"),
        CategorySpec("service", "生活服务", "service"),
        CategorySpec("social", "人情社交", "social"),
    )

    val income: List<CategorySpec> = listOf(
        CategorySpec("salary", "工资", "coin"),
        CategorySpec("bonus", "奖金补贴", "wallet"),
        CategorySpec("reimburse", "报销", "receipt"),
        CategorySpec("parttime", "兼职", "spark"),
        CategorySpec("finance", "理财", "chart"),
        CategorySpec("other", "其它收入", "note"),
    )

    fun expenseCount(): Int = expense.size
    fun incomeCount(): Int = income.size

    /**
     * 种子行用确定性 id（`seed-expense-food` 这种），并树与重复恢复才是幂等的；
     * 用户自建分类仍走 UUID。
     */
    fun build(deviceId: String, now: Long): List<CategoryEntity> = buildList {
        addAll(specEntities(deviceId, now, CategoryType.EXPENSE, expense))
        addAll(specEntities(deviceId, now, CategoryType.INCOME, income))
    }

    fun seedId(type: CategoryType, slug: String): String = "seed-${type.name.lowercase()}-$slug"

    private fun specEntities(
        deviceId: String,
        now: Long,
        type: CategoryType,
        specs: List<CategorySpec>,
    ): List<CategoryEntity> = buildList {
        specs.forEachIndexed { index, spec ->
            val parentId = seedId(type, spec.slug)
            add(
                entity(
                    id = parentId,
                    parentId = null,
                    name = spec.name,
                    type = type,
                    iconKey = spec.iconKey,
                    sortOrder = (index + 1) * SORT_STEP,
                    deviceId = deviceId,
                    now = now,
                ),
            )
            spec.children.forEachIndexed { childIndex, child ->
                add(
                    entity(
                        id = seedId(type, "${spec.slug}-${child.slug}"),
                        parentId = parentId,
                        name = child.name,
                        type = type,
                        iconKey = child.iconKey,
                        sortOrder = (childIndex + 1) * SORT_STEP,
                        deviceId = deviceId,
                        now = now,
                    ),
                )
            }
        }
    }

    private fun entity(
        id: String,
        parentId: String?,
        name: String,
        type: CategoryType,
        iconKey: String,
        sortOrder: Double,
        deviceId: String,
        now: Long,
    ) = CategoryEntity(
        id = id,
        parentId = parentId,
        name = name,
        type = type.name,
        iconKey = iconKey,
        color = null,
        sortOrder = sortOrder,
        isSystem = true,
        isArchived = false,
        createdAt = now,
        updatedAt = now,
        deviceId = deviceId,
    )

    fun dailyLedger(deviceId: String, now: Long, id: String): LedgerEntity = LedgerEntity(
        id = id,
        name = DEFAULT_LEDGER_NAME,
        iconKey = "ledger",
        isDefault = true,
        isArchived = false,
        sortOrder = 0.0,
        createdAt = now,
        updatedAt = now,
        deviceId = deviceId,
    )

    const val DEFAULT_LEDGER_NAME = "日常账本"
}
