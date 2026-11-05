package io.github.guohangbk.tally.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.guohangbk.tally.data.db.dao.AiConfigDao
import io.github.guohangbk.tally.data.db.dao.DiagnosticDao
import io.github.guohangbk.tally.data.db.dao.AiConversationDao
import io.github.guohangbk.tally.data.db.dao.AiMessageAttachmentDao
import io.github.guohangbk.tally.data.db.dao.AiMessageDao
import io.github.guohangbk.tally.data.db.dao.AiReportDao
import io.github.guohangbk.tally.data.db.dao.AppSettingDao
import io.github.guohangbk.tally.data.db.dao.AttachmentDao
import io.github.guohangbk.tally.data.db.dao.BackupDao
import io.github.guohangbk.tally.data.db.dao.BillDao
import io.github.guohangbk.tally.data.db.dao.BudgetDao
import io.github.guohangbk.tally.data.db.dao.ImportBatchDao
import io.github.guohangbk.tally.data.db.dao.ImportBillDao
import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.InstallmentPlanDao
import io.github.guohangbk.tally.data.db.dao.KeywordRuleDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.dao.RecurringRuleDao
import io.github.guohangbk.tally.data.db.dao.ReimbursementDao
import io.github.guohangbk.tally.data.db.entity.AiConfigEntity
import io.github.guohangbk.tally.data.db.entity.AiConversationEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageAttachmentEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageEntity
import io.github.guohangbk.tally.data.db.entity.AiReportEntity
import io.github.guohangbk.tally.data.db.entity.AppSettingEntity
import io.github.guohangbk.tally.data.db.entity.AttachmentEntity
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.entity.BudgetEntity
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.ImportBatchEntity
import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.data.db.entity.RecurringRuleEntity

/**
 * 15 张业务表在此建齐。改实体就改这里的 entity 声明，**不走迁移路径**：version 一直是 1，
 * 手上那台设备的库按备份包重建。没有清库兜底——`fallbackToDestructiveMigration` 永远不配。
 */
@Database(
    entities = [
        LedgerEntity::class,
        CategoryEntity::class,
        BillEntity::class,
        BudgetEntity::class,
        AttachmentEntity::class,
        ImportBatchEntity::class,
        RecurringRuleEntity::class,
        InstallmentPlanEntity::class,
        AiConfigEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
        AiMessageAttachmentEntity::class,
        AiReportEntity::class,
        KeywordRuleEntity::class,
        AppSettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TallyDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun categoryDao(): CategoryDao
    abstract fun billDao(): BillDao
    abstract fun budgetDao(): BudgetDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun importBillDao(): ImportBillDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun installmentPlanDao(): InstallmentPlanDao
    abstract fun reimbursementDao(): ReimbursementDao
    abstract fun backupDao(): BackupDao
    abstract fun aiConfigDao(): AiConfigDao
    abstract fun aiConversationDao(): AiConversationDao
    abstract fun aiMessageDao(): AiMessageDao
    abstract fun aiReportDao(): AiReportDao
    abstract fun aiMessageAttachmentDao(): AiMessageAttachmentDao
    abstract fun keywordRuleDao(): KeywordRuleDao

    /** 只服务「设置 → 导出诊断包」，不参与任何业务口径。 */
    abstract fun diagnosticDao(): DiagnosticDao

    companion object {
        const val NAME = "tally.db"
    }
}

