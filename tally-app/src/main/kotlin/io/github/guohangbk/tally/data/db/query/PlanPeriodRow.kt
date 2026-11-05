package io.github.guohangbk.tally.data.db.query

/**
 * 分期计划下**有效**账单的期号。明细弹层用它核对「这一期到底入没入账」。
 *
 * 为什么要它：`installment_plan.generated_periods` 是个只增不减的水位——它记录
 * 「生成到第几期了」，不等于「账上真的有这几笔」。用户把某一期的账单删掉（软删）之后，
 * 水位不会回退，于是弹层继续报「已入账」，而账本里并没有那笔钱。
 * 弹层要说的实话只能来自 bill 表本身，所以这里单独查一遍有效账单的期号。
 */
data class PlanPeriodRow(
    val planId: String,
    val periodIndex: Int,
)
