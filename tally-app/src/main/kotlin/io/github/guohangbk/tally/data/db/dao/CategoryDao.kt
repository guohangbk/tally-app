package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query(
        "SELECT * FROM category WHERE deleted_at IS NULL AND type = :type " +
            "ORDER BY sort_order ASC",
    )
    fun observeTree(type: String): Flow<List<CategoryEntity>>

    @Query(
        "SELECT * FROM category WHERE deleted_at IS NULL AND type = :type " +
            "ORDER BY sort_order ASC",
    )
    suspend fun findTreeOnce(type: String): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE deleted_at IS NULL ORDER BY sort_order ASC")
    suspend fun findCategories(): List<CategoryEntity>

    /** 一次性存量映射要用：所有二级分类。 */
    @Query("SELECT * FROM category WHERE deleted_at IS NULL AND parent_id IS NOT NULL ORDER BY sort_order ASC")
    suspend fun findAllChildren(): List<CategoryEntity>

    /** 默认一级图标的存量修正要用：所有一级分类。 */
    @Query("SELECT * FROM category WHERE deleted_at IS NULL AND parent_id IS NULL ORDER BY sort_order ASC")
    suspend fun findAllParents(): List<CategoryEntity>

    /**
     * 自动化列表一次取全部分类做名称映射：这里**故意包含已归档的**——
     * 历史账单挂的分类归档了也要能念出名字，不然列表里会出现空分类。
     */
    @Query("SELECT * FROM category WHERE deleted_at IS NULL ORDER BY sort_order ASC")
    fun observeForNames(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM category WHERE deleted_at IS NULL AND parent_id IS NULL")
    suspend fun countLevel1(): Int

    @Query("SELECT * FROM category WHERE id = :id AND deleted_at IS NULL")
    suspend fun findById(id: String): CategoryEntity?

    @Query(
        "SELECT * FROM category WHERE deleted_at IS NULL AND parent_id = :parentId ORDER BY sort_order ASC",
    )
    suspend fun findChildren(parentId: String): List<CategoryEntity>

    @Query("SELECT MAX(sort_order) FROM category WHERE deleted_at IS NULL AND type = :type AND parent_id IS :parentId")
    suspend fun maxSortOrder(type: String, parentId: String?): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun updateAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query(
        "UPDATE category SET deleted_at = :now, updated_at = :now WHERE id = :id",
    )
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE category SET is_archived = :archived, updated_at = :now WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, now: Long)

    @Query("UPDATE category SET deleted_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun softDeleteIds(ids: List<String>, now: Long)

    /** 归档一级分类时，其下二级按同一规则级联归档。 */
    @Transaction
    suspend fun softDeleteSubtree(parentId: String, now: Long) {
        softDelete(parentId, now)
        val children = findChildren(parentId)
        if (children.isNotEmpty()) softDeleteIds(children.map { it.id }, now)
    }

    /**
     * 记账页能选到的分类。归档只作用于自身，所以「可用」＝自身未归档 **且** 父级未归档；
     * 这条继承判定收在 SQL 里，界面层不再各自 `filterNot { isArchived }`。
     */
    @Query(
        """
        SELECT c.* FROM category c
        LEFT JOIN category p ON p.id = c.parent_id
        WHERE c.deleted_at IS NULL AND c.is_archived = 0
          AND (c.parent_id IS NULL OR p.is_archived = 0) AND c.type = :type
        ORDER BY c.sort_order ASC
        """,
    )
    fun observeActive(type: String): Flow<List<CategoryEntity>>

    @Query(
        """
        SELECT c.* FROM category c
        LEFT JOIN category p ON p.id = c.parent_id
        WHERE c.deleted_at IS NULL AND c.is_archived = 0
          AND (c.parent_id IS NULL OR p.is_archived = 0) AND c.type = :type
        ORDER BY c.sort_order ASC
        """,
    )
    suspend fun findActive(type: String): List<CategoryEntity>

    /** 同上，但不分收支类型：关键词目录要把一级与二级一次配好。 */
    @Query(
        """
        SELECT c.* FROM category c
        LEFT JOIN category p ON p.id = c.parent_id
        WHERE c.deleted_at IS NULL AND c.is_archived = 0
          AND (c.parent_id IS NULL OR p.is_archived = 0)
        ORDER BY c.sort_order ASC
        """,
    )
    suspend fun findActiveAll(): List<CategoryEntity>

    /** [findActiveAll] 的可订阅版：解析用的关键词目录要跟着分类表的变化走。 */
    @Query(
        """
        SELECT c.* FROM category c
        LEFT JOIN category p ON p.id = c.parent_id
        WHERE c.deleted_at IS NULL AND c.is_archived = 0
          AND (c.parent_id IS NULL OR p.is_archived = 0)
        ORDER BY c.sort_order ASC
        """,
    )
    fun observeActiveAll(): Flow<List<CategoryEntity>>

    /**
     * 分类管理首屏要的「每个分类有多少笔账单」。以前是逐个分类发一次 COUNT，
     * 分类一多首帧就要等几十个往返；这里一次聚合完，首屏不再闪载入态。
     * 口径与 [BillDao.countBillsUsing] 一致：只算未删账单，一级/二级引用都算。
     *
     * 一次扫两张索引再相加，而不是拿 category 去 LEFT JOIN 一张 OR 条件的明细表：
     * `a = x OR b = x` 用不上索引，几千笔时每个分类都要把明细表走一遍。没有引用行的分类
     * 不出现在结果里，调用方按「查不到 = 0」读。
     */
    @Query(
        """
        SELECT category_id AS id, SUM(count) AS count
        FROM (
            SELECT category_l1_id AS category_id, COUNT(*) AS count
            FROM bill
            WHERE deleted_at IS NULL AND category_l1_id IS NOT NULL
            GROUP BY category_l1_id
            UNION ALL
            SELECT category_l2_id AS category_id, COUNT(*) AS count
            FROM bill
            WHERE deleted_at IS NULL AND category_l2_id IS NOT NULL
            GROUP BY category_l2_id
        )
        GROUP BY category_id
        """,
    )
    fun observeBillCounts(): Flow<List<CategoryBillCount>>
}

/** 一个分类被多少笔（未删）账单引用。 */
data class CategoryBillCount(val id: String, val count: Int)
