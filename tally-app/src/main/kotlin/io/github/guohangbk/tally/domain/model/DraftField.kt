package io.github.guohangbk.tally.domain.model

/**
 * 草稿里没能自动确定、必须由用户在确认页补上的字段。
 * 规则解析层与 AI 解析层共用这一套口径，页面提示才不会两边不一样。
 */
enum class DraftField(val label: String) {
    AMOUNT("金额"),
    TYPE("收支方向"),
    DATE("日期"),
    CATEGORY("分类"),
}
