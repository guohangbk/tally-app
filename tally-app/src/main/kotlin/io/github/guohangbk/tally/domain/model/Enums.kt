package io.github.guohangbk.tally.domain.model

/** 收支方向。转账暂未开放，只在数据模型中占位。 */
enum class BillType {
    EXPENSE,
    INCOME,
    TRANSFER,
}

enum class BillSource {
    MANUAL,
    VOICE,
    SCREENSHOT,
    RECURRING,
    INSTALLMENT,
    IMPORT,
}

/** 报销状态；只影响报销管理，不改变原始支出的收支统计归属。 */
enum class ReimburseStatus {
    NONE,
    PENDING,
    DONE,
}

enum class TimePrecision {
    DATE,
    DATETIME,
}

/** 分类只分收支两类。 */
enum class CategoryType {
    EXPENSE,
    INCOME,
}

enum class ImportSourceType {
    TEMPLATE,
    QIANJI,
    ALIPAY,
}

enum class ImportBatchStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    ROLLED_BACK,
    CANCELLED,
}

enum class RecurringStatus {
    ACTIVE,
    PAUSED,
    FINISHED,
}

enum class InstallmentStatus {
    ACTIVE,
    SETTLED_EARLY,
    FINISHED,
}

/** 小莉连接状态机：UNCONFIGURED -> READY -> SUSPECTED -> DISCONNECTED。 */
enum class AiStatus {
    UNCONFIGURED,
    READY,
    SUSPECTED,
    DISCONNECTED,
}

enum class AiMessageType {
    TEXT,
    IMAGE,
    /** 按住说话发出去的一条：content 存识别出的文字，duration_ms 存录音长度。 */
    VOICE,
    ANALYSIS,
    /**
     * 小莉抽出来、等人确认的记账卡：content 存一组 DraftRow 的 JSON。
     * 它是对话的一部分，所以跟着对话一起活下来——以前卡片只在内存里，
     * 切个页面回来就没了，连已经记入的那几张也一起丢。
     */
    DRAFT,
}

enum class AiMessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

enum class AiMessageStatus {
    PENDING,
    SUCCESS,
    FAILED,
}
