package io.github.guohangbk.tally.ui.navigation

import io.github.guohangbk.tally.data.notify.NotificationLink

/**
 * 通知点达的映射表（T-04），全站唯一一处认识「去处 → 路由字符串」这层翻译。
 *
 * 数据层只说去处（[NotificationLink]），不抄路由语法：路由的占位符、参数名与拼接顺序
 * 改起来只有这一处要跟着改，不会出现「通知里写的路径导航图没有」这种要到点击那一刻才暴露的错。
 *
 * [NotificationLink.BillDetail] 不在这里落地——账单详情是首页那张底部弹层，不是独立一页，
 * 由首页自己消费（HomeScreen），这里只负责把人送回首页。[NotificationLink.Home] 同理。
 */
internal fun notificationRoute(link: NotificationLink): String? = when (link) {
    NotificationLink.NewBill -> Routes.billNew(l1Id = null, l2Id = null, type = null)
    is NotificationLink.NewBillDraft -> Routes.billNew(
        l1Id = link.l1Id,
        l2Id = link.l2Id,
        type = link.type,
    )
    NotificationLink.Recurring -> Routes.RECURRING
    NotificationLink.Installments -> Routes.INSTALLMENTS
    NotificationLink.Recycle -> Routes.RECYCLE
    NotificationLink.Budget -> Routes.BUDGET
    NotificationLink.NotificationSettings -> Routes.NOTIFICATIONS
    is NotificationLink.Stats -> Routes.stats(ledgerId = link.ledgerId)
    is NotificationLink.BillDetail -> null
    NotificationLink.Home -> null
}
