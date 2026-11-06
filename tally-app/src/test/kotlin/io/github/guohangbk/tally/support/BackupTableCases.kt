package io.github.guohangbk.tally.support

import io.github.guohangbk.tally.data.backup.BackupJson
import io.github.guohangbk.tally.data.backup.BackupSnapshot
import org.json.JSONObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 备份编解码的**表级契约**（Strategy + Template Method）。
 *
 * 每张表只声明「样本、软删变体、放进快照哪个字段、从快照哪个字段取回」，
 * 至于「往返保真 / 软删行不丢 / 空表 / 未知字段忽略 / 缺字段用默认值」这些
 * 对所有表都成立的断言，统一写在这里。
 *
 * 好处：新增一张表只加一个 [TableCase]，新增字段只要改 [EntityFixtures] 的样本，
 * 契约自动覆盖——不会出现「某张表漏写一个 put，几个月后恢复才发现」。
 */
data class TableCase<T : Any>(
    val name: String,
    val jsonKey: String,
    val sample: T,
    val softDeleted: (T) -> T,
    val place: (BackupSnapshot, List<T>) -> BackupSnapshot,
    val pick: (BackupSnapshot) -> List<T>,
    /** 有意的字段裁剪（如 AI Key 引用不进备份），往返后按这个换算再比对。 */
    val expected: (T) -> T = { it },
) {
    val supportsSoftDelete: Boolean get() = softDeleted(sample) !== sample
}

/** 15 张表的注册表。顺序与 BackupJson.encode 一致，便于逐项核对。 */
object BackupTableCases {
    val all: List<TableCase<*>> = listOf(
        TableCase(
            name = "ledger", jsonKey = "ledgers",
            sample = EntityFixtures.ledger(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(ledgers = rows) },
            pick = { it.ledgers },
        ),
        TableCase(
            name = "category", jsonKey = "categories",
            sample = EntityFixtures.category(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(categories = rows) },
            pick = { it.categories },
        ),
        TableCase(
            name = "bill", jsonKey = "bills",
            sample = EntityFixtures.bill(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(bills = rows) },
            pick = { it.bills },
        ),
        TableCase(
            name = "attachment", jsonKey = "attachments",
            sample = EntityFixtures.attachment(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(attachments = rows) },
            pick = { it.attachments },
        ),
        TableCase(
            name = "importBatch", jsonKey = "importBatches",
            sample = EntityFixtures.importBatch(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(importBatches = rows) },
            pick = { it.importBatches },
        ),
        TableCase(
            name = "recurringRule", jsonKey = "recurringRules",
            sample = EntityFixtures.recurringRule(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(recurringRules = rows) },
            pick = { it.recurringRules },
        ),
        TableCase(
            name = "installmentPlan", jsonKey = "installmentPlans",
            sample = EntityFixtures.installmentPlan(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(installmentPlans = rows) },
            pick = { it.installmentPlans },
        ),
        TableCase(
            name = "aiConfig", jsonKey = "aiConfigs",
            sample = EntityFixtures.aiConfig(),
            softDeleted = { it },
            place = { s, rows -> s.copy(aiConfigs = rows) },
            pick = { it.aiConfigs },
            // key_reference 是设备凭据，按设计不进备份包。
            expected = { it.copy(keyReference = null) },
        ),
        TableCase(
            name = "aiConversation", jsonKey = "aiConversations",
            sample = EntityFixtures.aiConversation(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(aiConversations = rows) },
            pick = { it.aiConversations },
        ),
        TableCase(
            name = "aiMessage", jsonKey = "aiMessages",
            sample = EntityFixtures.aiMessage(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(aiMessages = rows) },
            pick = { it.aiMessages },
        ),
        TableCase(
            name = "aiMessageAttachment", jsonKey = "aiMessageAttachments",
            sample = EntityFixtures.aiMessageAttachment(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(aiMessageAttachments = rows) },
            pick = { it.aiMessageAttachments },
        ),
        TableCase(
            name = "aiReport", jsonKey = "aiReports",
            sample = EntityFixtures.aiReport(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(aiReports = rows) },
            pick = { it.aiReports },
        ),
        TableCase(
            name = "keywordRule", jsonKey = "keywordRules",
            sample = EntityFixtures.keywordRule(),
            softDeleted = { it.copy(deletedAt = 1L) },
            place = { s, rows -> s.copy(keywordRules = rows) },
            pick = { it.keywordRules },
        ),
        TableCase(
            name = "appSetting", jsonKey = "appSettings",
            sample = EntityFixtures.appSetting(),
            softDeleted = { it },
            place = { s, rows -> s.copy(appSettings = rows) },
            pick = { it.appSettings },
        ),
    )

    /** 用运行时反射式泛型擦除太脆，这里显式做一次「把任意表塞进快照」的分派。 */
    @Suppress("UNCHECKED_CAST")
    fun encodeWith(case: TableCase<*>, rows: List<Any>): String {
        val typed = case as TableCase<Any>
        return BackupJson.encode(typed.place(BackupSnapshot(), rows))
    }

    @Suppress("UNCHECKED_CAST")
    fun decodePick(case: TableCase<*>, json: String): List<Any> {
        val typed = case as TableCase<Any>
        return typed.pick(BackupJson.decode(json))
    }

    @Suppress("UNCHECKED_CAST")
    fun expectedOf(case: TableCase<*>, row: Any): Any {
        val typed = case as TableCase<Any>
        return typed.expected(row)
    }

    @Suppress("UNCHECKED_CAST")
    fun softDeletedOf(case: TableCase<*>, row: Any): Any {
        val typed = case as TableCase<Any>
        return typed.softDeleted(row)
    }
}

/** 契约断言的可复用集合：测试类只负责把它挂到 JUnit 的动态用例上。 */
object BackupContractAssertions {
    fun roundTripIsLossless(case: TableCase<*>) {
        val json = BackupTableCases.encodeWith(case, listOf(case.sample))
        val rows = BackupTableCases.decodePick(case, json)
        assertEquals(1, rows.size, "${case.name}: 往返后行数应为 1")
        assertEquals(
            BackupTableCases.expectedOf(case, case.sample), rows.single(),
            "${case.name}: 编解码丢字段了（检查 BackupJson 的 writer / reader 是否成对）",
        )
    }

    fun softDeletedRowSurvives(case: TableCase<*>) {
        if (!case.supportsSoftDelete) return
        val deleted = BackupTableCases.softDeletedOf(case, case.sample)
        val json = BackupTableCases.encodeWith(case, listOf(deleted))
        val rows = BackupTableCases.decodePick(case, json)
        assertEquals(
            BackupTableCases.expectedOf(case, deleted), rows.single(),
            "${case.name}: 软删行（回收站）必须在备份里原样保留",
        )
    }

    fun emptyTableIsStable(case: TableCase<*>) {
        val json = BackupTableCases.encodeWith(case, emptyList())
        assertTrue(
            BackupTableCases.decodePick(case, json).isEmpty(),
            "${case.name}: 空表往返不该凭空多出行",
        )
    }

    fun unknownFieldsAreIgnored(case: TableCase<*>) {
        val json = BackupTableCases.encodeWith(case, listOf(case.sample))
        val root = JSONObject(json)
        val array = root.getJSONArray(case.jsonKey)
        array.getJSONObject(0).put("future_field_from_v9", "whatever")
        val rows = BackupTableCases.decodePick(case, root.toString())
        assertEquals(
            BackupTableCases.expectedOf(case, case.sample), rows.single(),
            "${case.name}: 高版本包的未知字段必须被忽略",
        )
    }
}
