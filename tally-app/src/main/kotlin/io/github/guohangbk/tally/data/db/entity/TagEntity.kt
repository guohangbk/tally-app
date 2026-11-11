package io.github.guohangbk.tally.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签：全机一份、不按账本拆。名称本身就是显示文本（可带 emoji），
 * 不设颜色 / 图标 / 层级；排序只靠 `sort_order`，归档写 `archived_at`。
 */
@Entity(
    tableName = "tag",
    indices = [Index("sort_order"), Index("deleted_at")],
)
data class TagEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("sort_order") val sortOrder: Double,
    @ColumnInfo("archived_at") val archivedAt: Long? = null,
    @ColumnInfo("created_at") override val createdAt: Long,
    @ColumnInfo("updated_at") override val updatedAt: Long,
    @ColumnInfo("deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo("device_id") override val deviceId: String,
    @ColumnInfo("version") override val version: Int = 1,
) : TallyRow

/**
 * 账单与标签的多对多关联。一行就是「这笔挂了这个标签」，没有金额与口径字段：
 * 标签只切分「看哪批账」，合计仍由账单自己的报销 / 排除 / 退款规则决定。
 */
@Entity(
    tableName = "bill_tag",
    primaryKeys = ["bill_id", "tag_id"],
    indices = [Index("tag_id")],
)
data class BillTagEntity(
    @ColumnInfo("bill_id") val billId: String,
    @ColumnInfo("tag_id") val tagId: String,
)
