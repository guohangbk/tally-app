package io.github.guohangbk.tally.data.db.entity

/**
 * 所有业务表共有的字段：UUID 主键、时间戳、软删除墓碑、设备标识和本地版本号，
 * 为多设备同步预留，不做自动合并。
 */
interface TallyRow {
    val id: String
    val createdAt: Long
    val updatedAt: Long
    val deletedAt: Long?
    val deviceId: String
    val version: Int
}
