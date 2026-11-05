package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/** 诊断包用的一行计数：表名 + 行数。 */
data class TableRows(val name: String, val rowCount: Int)

/**
 * 排障取数：各表行数与已配置的模型名。
 *
 * 单独一个 DAO 而不是塞进业务 DAO——它不属于任何业务口径，也不该被业务代码调到。
 * 加 DAO 不改表结构,Room 的 identity hash 只由 entities 决定,所以不抬版本、不需要迁移。
 * `PRAGMA` 与 `room_master_table` 不在这里查：Room 解析不了 PRAGMA,而那张表要到运行时才存在。
 */
@Dao
interface DiagnosticDao {
    @Query(
        """
        SELECT 'bill' AS name, COUNT(*) AS rowCount FROM bill
        UNION ALL SELECT 'bill_live', COUNT(*) FROM bill WHERE deleted_at IS NULL
        UNION ALL SELECT 'ledger', COUNT(*) FROM ledger
        UNION ALL SELECT 'ledger_live', COUNT(*) FROM ledger WHERE deleted_at IS NULL
        UNION ALL SELECT 'category', COUNT(*) FROM category
        UNION ALL SELECT 'budget', COUNT(*) FROM budget
        UNION ALL SELECT 'attachment', COUNT(*) FROM attachment
        UNION ALL SELECT 'import_batch', COUNT(*) FROM import_batch
        UNION ALL SELECT 'recurring_rule', COUNT(*) FROM recurring_rule
        UNION ALL SELECT 'installment_plan', COUNT(*) FROM installment_plan
        UNION ALL SELECT 'ai_config', COUNT(*) FROM ai_config
        UNION ALL SELECT 'ai_conversation', COUNT(*) FROM ai_conversation
        UNION ALL SELECT 'ai_message', COUNT(*) FROM ai_message
        UNION ALL SELECT 'ai_message_attachment', COUNT(*) FROM ai_message_attachment
        UNION ALL SELECT 'ai_report', COUNT(*) FROM ai_report
        UNION ALL SELECT 'keyword_rule', COUNT(*) FROM keyword_rule
        UNION ALL SELECT 'app_setting', COUNT(*) FROM app_setting
        """,
    )
    suspend fun tableRows(): List<TableRows>

    /**
     * 只取服务商、模型名与失败状态。`base_url` 与 `key_reference` 不进诊断包；
     * `last_error` 存的是失败类型名而不是响应正文，所以不会带进对话内容。
     */
    @Query("SELECT provider, model, last_error AS lastError, consecutive_failures AS consecutiveFailures FROM ai_config WHERE active = 1 LIMIT 1")
    suspend fun aiHealth(): AiHealth?
}

data class AiHealth(
    val provider: String,
    val model: String,
    val lastError: String?,
    val consecutiveFailures: Int,
)
