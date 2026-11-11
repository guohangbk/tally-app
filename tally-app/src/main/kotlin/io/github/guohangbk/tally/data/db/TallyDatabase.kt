package io.github.guohangbk.tally.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import io.github.guohangbk.tally.data.db.dao.TagDao
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
import io.github.guohangbk.tally.data.db.entity.TagEntity
import io.github.guohangbk.tally.data.db.entity.BillTagEntity

/**
 * 17 张业务表在此建齐。库版本只在发版时抬：当前 2，只承载「新增 `tag` / `bill_tag`」这一条
 * 1→2 的净迁移（见 [MIGRATION_1_2]，`AppGraph` 里注册）。开发期再改实体不逐个抬版本：
 * debug 构建由 `DevSchemaHeal` 对齐结构，攒到下次发版再写一条净迁移。
 * 没有清库兜底——`fallbackToDestructiveMigration` 永远不配。
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
        TagEntity::class,
        BillTagEntity::class,
    ],
    version = 2,
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
    abstract fun tagDao(): TagDao

    /** 只服务「设置 → 导出诊断包」，不参与任何业务口径。 */
    abstract fun diagnosticDao(): DiagnosticDao

    companion object {
        const val NAME = "tally.db"

        /**
         * 1 → 2：只新增标签两张表与索引，旧表一列不动、旧数据一行不搬。
         * DDL 与 `docs/release/db/2.json` 里 Room 导出的 createSql 逐字一致。
         * 用 `IF NOT EXISTS`：debug 设备上被 `DevSchemaHeal` 提前对齐过结构的库也能安全跑。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tag` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`sort_order` REAL NOT NULL, `archived_at` INTEGER, `created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `device_id` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_sort_order` ON `tag` (`sort_order`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_deleted_at` ON `tag` (`deleted_at`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bill_tag` (`bill_id` TEXT NOT NULL, " +
                        "`tag_id` TEXT NOT NULL, PRIMARY KEY(`bill_id`, `tag_id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_tag_tag_id` ON `bill_tag` (`tag_id`)")
            }
        }
    }
}

