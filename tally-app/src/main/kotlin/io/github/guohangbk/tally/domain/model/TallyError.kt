package io.github.guohangbk.tally.domain.model

/** 面向 UI 的业务错误，文案直接用于就地提示。 */
sealed class TallyError(override val message: String) : Exception(message) {
    object NoDefaultLedger : TallyError("请先设置默认账本")

    object DefaultLedgerProtected : TallyError("默认账本不能直接删除，请先转移默认身份")

    object MigrationTargetIsSelf : TallyError("不能迁移到自身")

    object MigrationTargetRequired : TallyError("该账本已有账单，请选择迁移目标或连同删除")

    object LedgerNotFound : TallyError("账本不存在")

    object BillNotFound : TallyError("账单不存在")

    object CategoryTypeMismatch : TallyError("分类与收支类型不匹配")

    object CategoryLedgerMismatch : TallyError("分类不属于当前账本")

    object CategoryNotFound : TallyError("分类不存在")

    object CategoryTargetInvalid : TallyError("迁移目标必须与源分类同层级、同收支类型")

    object CategoryIconEmpty : TallyError("请选择一个图标")

    /** 未收录的 icon_key 会让图标退化成名称首字，所以写库前就拒。 */
    object IconKeyUnknown : TallyError("这个图标不在图标库里，换一个再保存")

    object CategoryTreeCorrupted : TallyError("分类数据异常，已恢复系统默认分类")

    object EmptyName : TallyError("名称不能为空")

    object AmountOutOfRange : TallyError("金额需在 0.01 到 99999999.99 之间")

    object InstallmentPeriodInvalid : TallyError("期数需在 1 到 480 之间")

    object InstallmentAmountTooSmall : TallyError("总金额不足以分成这么多期")

    object InstallmentRateInvalid : TallyError("年利率需在 0% 到 30% 之间")

    object InstallmentRateNotEditable : TallyError("只有还款中的房贷计划可以改利率")

    object InstallmentSettled : TallyError("分期已结清，不能再生成账单")

    object InstallmentPlanNotFound : TallyError("分期计划不存在")

    object RecurringRuleNotFound : TallyError("周期任务不存在")

    object RecurringRuleFinished : TallyError("周期任务已结束")

    object ReimbursementNotAllowed : TallyError("仅支出账单支持报销标记")

    object RefundNotAllowed : TallyError("仅支出账单可以标记退款")

    object RefundOriginInvalid : TallyError("这笔账单不能再退款：只有未退款的支出账单可以作为原单")

    /** 退款是一笔钱分批处理：累计不得超过原单，超了就把上限直接说出来。 */
    class RefundExceedsRemaining(remainingMinor: Long) :
        TallyError("最多还能退 ¥" + io.github.guohangbk.tally.domain.formatAmount(remainingMinor))

    class AmountBelowRefunded(refundedMinor: Long) :
        TallyError("这笔已退 ¥" + io.github.guohangbk.tally.domain.formatAmount(refundedMinor) + "，金额不能低于它")

    object BackupPasswordRequired : TallyError("请输入备份密码")

    /** 新建/改口令时的下限；旧包仍按原样可解，不拿这条去锁历史备份。 */
    object BackupPasswordWeak : TallyError("备份密码至少 4 位，字母与数字都可以")

    object BackupPasswordMismatch : TallyError("密码错误，请重新输入")

    object BackupCorrupted : TallyError("备份包已损坏或格式不受支持")

    object BackupVersionUnsupported : TallyError("备份包版本高于当前 App，请先升级")

    object BackupRestoreFailed : TallyError("恢复失败，现有数据未变动")

    object WebDavNotConfigured : TallyError("请先配置 WebDAV 地址与账号")

    object KeywordExists : TallyError("这个关键词已经存在")

    object SpeechNotAvailable : TallyError("本机没有语音识别引擎，已切换为文字输入")
}
