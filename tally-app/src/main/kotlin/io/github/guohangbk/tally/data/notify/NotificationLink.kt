package io.github.guohangbk.tally.data.notify

import io.github.guohangbk.tally.domain.model.BillType

/** 带参数的那个落点单独一份键：`decode` 与 [NotificationLink.BillDetail] 必须用同一个字符串。 */
private const val BILL_DETAIL_KEY = "bill_detail"

/** 桌面组件的三枚按钮带收支与分类，同样是一段配好的字符串。 */
private const val BILL_DRAFT_KEY = "bill_draft"

/** 统计页的落点带一段可选参数（账本 id），所以它不在「无参数落点」那张表里。 */
private const val STATS_KEY = "stats"

/**
 * 一条通知点开后该去哪儿。
 *
 * 这里只说**去处**，不说路由字符串：`Routes` 的语法（占位符、可选参数、拼接顺序）属于导航层，
 * 数据层抄一份迟早和 `TallyNavigation` 对不上。映射表在 `ui/navigation/NavDeepLink.kt`，
 * 全站只有那一处认识 `[key]` 与 `[arg]` 这两段编码。
 */
sealed interface NotificationLink {
    /** 稳定标识，随 intent extra 进出；新增落点只改这一处枚举与那张映射表。 */
    val key: String

    /**
     * 落点需要的参数（账单 id）；没有参数的落点为 null。
     *
     * 必须每个实现各自声明，不能给默认实现：接口一旦带默认方法，实现类的类初始化就会先触发
     * 接口初始化，而接口的 companion 又要读各实现类的单例——冷启动第一条入口（快捷方式）
     * 会正好撞上这个环，读到尚未赋值的 INSTANCE。
     */
    val arg: String?

    /** 记一笔——未记账提醒的落点。 */
    data object NewBill : NotificationLink {
        override val key = "bill_new"
        override val arg: String? = null
    }

    /**
     * 记一笔，并带上桌面组件那一枚按钮已经知道的收支与分类。
     * 三段用「|」拼成一条 arg：intent extra 只能放字符串，空段留空即可。
     */
    data class NewBillDraft(
        val type: BillType? = null,
        val l1Id: String? = null,
        val l2Id: String? = null,
    ) : NotificationLink {
        override val key = BILL_DRAFT_KEY
        override val arg = listOf(type?.name, l1Id, l2Id).joinToString("|") { it.orEmpty() }
    }

    /** 某一笔账单的详情弹层。 */
    data class BillDetail(val billId: String) : NotificationLink {
        override val key = BILL_DETAIL_KEY
        override val arg = billId
    }

    /** 回首页——本月概览组件的点击落点。 */
    data object Home : NotificationLink {
        override val key = "home"
        override val arg: String? = null
    }

    /**
     * 统计页——快捷方式与桌面组件的落点。
     *
     * [ledgerId] 为空就是「全部账本」：长按图标的查看统计没有账本上下文，落到全部最直白。
     * 组件不一样——它画的就是某一家的数，所以把自己显示的那本账带过去，点开的统计与组件上的数字才是同一个数。
     */
    data class Stats(val ledgerId: String? = null) : NotificationLink {
        override val key = STATS_KEY
        override val arg: String? = ledgerId
    }

    /** 周期记账页——待确认与执行失败都落这儿。 */
    data object Recurring : NotificationLink {
        override val key = "recurring"
        override val arg: String? = null
    }

    /** 分期计划页。 */
    data object Installments : NotificationLink {
        override val key = "installments"
        override val arg: String? = null
    }

    /** 回收站。 */
    data object Recycle : NotificationLink {
        override val key = "recycle"
        override val arg: String? = null
    }

    /** 预算页——阈值与超支提醒的落点。 */
    data object Budget : NotificationLink {
        override val key = "budget"
        override val arg: String? = null
    }

    /** 提醒与通知设置页——系统没给权限时那条要走这里。 */
    data object NotificationSettings : NotificationLink {
        override val key = "notification_settings"
        override val arg: String? = null
    }

    companion object {
        private val fixed = listOf(NewBill, Home, Recurring, Installments, Recycle, Budget, NotificationSettings)
            .associateBy { it.key }

        /** intent 只能带字符串，所以按 key + arg 复原；认不出就当「打开 App」，不做跳转。 */
        fun decode(key: String?, arg: String?): NotificationLink? = when (key) {
            BILL_DETAIL_KEY -> arg?.let(::BillDetail)
            STATS_KEY -> Stats(ledgerId = arg?.ifEmpty { null })
            BILL_DRAFT_KEY -> arg?.split('|', limit = 3)?.let { parts ->
                NewBillDraft(
                    type = parts.getOrNull(0)?.let { raw -> BillType.entries.firstOrNull { it.name == raw } },
                    l1Id = parts.getOrNull(1)?.ifEmpty { null },
                    l2Id = parts.getOrNull(2)?.ifEmpty { null },
                )
            }
            else -> key?.let { fixed[it] }
        }
    }
}
