package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.guohangbk.tally.data.db.entity.AiConfigEntity
import io.github.guohangbk.tally.data.db.entity.AiConversationEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageAttachmentEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageEntity
import io.github.guohangbk.tally.data.db.entity.AiReportEntity
import io.github.guohangbk.tally.data.db.entity.AppSettingEntity
import io.github.guohangbk.tally.data.db.entity.AttachmentEntity
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.ImportBatchEntity
import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.data.db.entity.RecurringRuleEntity

/**
 * 备份网关：整表快照与整表替换集中在这里。
 * 业务 DAO 保持局部查询职责，备份语义不渗进日常读写。
 */
@Dao
interface BackupDao {
    // ---- 快照 ---------------------------------------------------------

    @Query("SELECT * FROM ledger ORDER BY sort_order ASC")
    suspend fun ledgers(): List<LedgerEntity>

    @Query("SELECT * FROM category ORDER BY sort_order ASC")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM bill ORDER BY occurred_at ASC")
    suspend fun bills(): List<BillEntity>

    @Query("SELECT * FROM attachment ORDER BY created_at ASC")
    suspend fun attachments(): List<AttachmentEntity>

    @Query("SELECT * FROM import_batch ORDER BY started_at ASC")
    suspend fun importBatches(): List<ImportBatchEntity>

    @Query("SELECT * FROM recurring_rule ORDER BY created_at ASC")
    suspend fun recurringRules(): List<RecurringRuleEntity>

    @Query("SELECT * FROM installment_plan ORDER BY created_at ASC")
    suspend fun installmentPlans(): List<InstallmentPlanEntity>

    @Query("SELECT * FROM ai_config")
    suspend fun aiConfigs(): List<AiConfigEntity>

    @Query("SELECT * FROM ai_conversation")
    suspend fun aiConversations(): List<AiConversationEntity>

    @Query("SELECT * FROM ai_message")
    suspend fun aiMessages(): List<AiMessageEntity>

    @Query("SELECT * FROM ai_message_attachment")
    suspend fun aiMessageAttachments(): List<AiMessageAttachmentEntity>

    @Query("SELECT * FROM ai_report")
    suspend fun aiReports(): List<AiReportEntity>

    @Query("SELECT * FROM keyword_rule ORDER BY created_at ASC")
    suspend fun keywordRules(): List<KeywordRuleEntity>

    @Query("SELECT * FROM app_setting ORDER BY setting_key ASC")
    suspend fun appSettings(): List<AppSettingEntity>

    // ---- 写入 ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgers(rows: List<LedgerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(rows: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBills(rows: List<BillEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(rows: List<AttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportBatches(rows: List<ImportBatchEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurringRules(rows: List<RecurringRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstallmentPlans(rows: List<InstallmentPlanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiConfigs(rows: List<AiConfigEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiConversations(rows: List<AiConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiMessages(rows: List<AiMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiMessageAttachments(rows: List<AiMessageAttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiReports(rows: List<AiReportEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeywordRules(rows: List<KeywordRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppSettings(rows: List<AppSettingEntity>)

    // ---- 清空（覆盖式恢复）--------------------------------------------

    @Query("DELETE FROM bill")
    suspend fun clearBills()

    @Query("DELETE FROM attachment")
    suspend fun clearAttachments()

    @Query("DELETE FROM category")
    suspend fun clearCategories()

    @Query("DELETE FROM ledger")
    suspend fun clearLedgers()

    @Query("DELETE FROM import_batch")
    suspend fun clearImportBatches()

    @Query("DELETE FROM recurring_rule")
    suspend fun clearRecurringRules()

    @Query("DELETE FROM installment_plan")
    suspend fun clearInstallmentPlans()

    @Query("DELETE FROM ai_config")
    suspend fun clearAiConfigs()

    @Query("DELETE FROM ai_message_attachment")
    suspend fun clearAiMessageAttachments()

    @Query("DELETE FROM ai_message")
    suspend fun clearAiMessages()

    @Query("DELETE FROM ai_conversation")
    suspend fun clearAiConversations()

    @Query("DELETE FROM ai_report")
    suspend fun clearAiReports()

    @Query("DELETE FROM keyword_rule")
    suspend fun clearKeywordRules()

    @Query("DELETE FROM app_setting")
    suspend fun clearAppSettings()
}
