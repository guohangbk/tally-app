package io.github.guohangbk.tally.ui.design

import androidx.annotation.DrawableRes
import io.github.guohangbk.tally.R

/**
 * `icon_key` → 图标资源、默认名称与语义分组。
 *
 * 图标以键存储，不存图片路径；未收录的键回退到分类首字或 emoji。
 * 默认名只在「新建分类」时用来顺手填名称，写库只写 icon_key，改名不影响图标。
 */
data class IconMeta(
    val key: String,
    @DrawableRes val res: Int,
    val displayName: String,
    val group: String,
)

object IconLibrary {
    /** 12 组语义分组，顺序即图标库左栏顺序。 */
    val groupNames: List<String> = listOf("收入", "理财", "餐饮", "日常", "购物", "服饰", "美妆", "居住", "出行", "娱乐", "运动", "人情")

    private fun icon(key: String, name: String, group: String) = Triple(key, name, group)

    private val catalog: List<Triple<String, String, String>> = listOf(
        // 收入
        icon("coin", "工资", "收入"), icon("salary", "薪资", "收入"), icon("wallet", "奖金", "收入"),
        icon("receipt", "报销", "收入"), icon("refund", "退款", "收入"), icon("spark", "兼职", "收入"),
        icon("chart", "分红", "收入"), icon("cash_person", "外快", "收入"), icon("medal", "中奖", "收入"),
        icon("percent", "利息", "收入"), icon("env_r", "礼金", "收入"), icon("pen", "稿费", "收入"),
        icon("house_yen", "房租收入", "收入"), icon("db_tax", "退税", "收入"), icon("laptop", "接单", "收入"),
        // 理财
        icon("piggy", "储蓄", "理财"), icon("bank", "存款", "理财"), icon("diamond", "贵金属", "理财"),
        icon("shield", "保险", "理财"), icon("umbrella", "投保", "理财"), icon("stock", "股票", "理财"),
        icon("car_shield", "车险", "理财"), icon("ledger", "账本", "理财"), icon("key", "账户", "理财"),
        icon("lock", "定期", "理财"), icon("recycle", "赎回", "理财"), icon("tag", "手续费", "理财"), icon("flag", "期权", "理财"),
        icon("clock", "到期", "理财"),
        // 餐饮
        icon("food", "正餐", "餐饮"), icon("sun", "早餐", "餐饮"), icon("tray", "午餐", "餐饮"),
        icon("moon", "晚餐", "餐饮"), icon("cake", "夜宵", "餐饮"), icon("bread", "面包", "餐饮"),
        icon("milk", "牛奶", "餐饮"), icon("cup", "咖啡", "餐饮"), icon("tea", "茶饮", "餐饮"),
        icon("soda", "汽水", "餐饮"), icon("fruit", "水果", "餐饮"), icon("water", "矿泉水", "餐饮"),
        icon("bag_food", "外卖", "餐饮"), icon("wine", "酒", "餐饮"),
        icon("snack", "零食", "餐饮"), icon("hotpot", "火锅", "餐饮"), icon("noodle", "面食", "餐饮"),
        icon("fastfood", "快餐", "餐饮"), icon("dessert", "甜点", "餐饮"), icon("milk_tea", "奶茶", "餐饮"),
        icon("bbq", "烧烤", "餐饮"), icon("teapot", "茶歇", "餐饮"),
        // 日常
        icon("bolt", "水电煤", "居住"), icon("scissors", "理发", "服饰"), icon("laundry", "洗衣", "服饰"),
        icon("wrench", "维修", "居住"), icon("spray", "清洁", "美妆"), icon("hammer", "装修", "居住"),
        icon("tooth", "口腔", "美妆"), icon("pill", "药品", "日常"), icon("med", "门诊", "日常"),
        icon("book", "学习", "日常"), icon("cap", "学费", "日常"), icon("service", "家政", "日常"),
        icon("trash", "杂项", "日常"), icon("user_heart", "护理", "日常"),
        icon("moving", "搬家", "日常"), icon("cleaning", "保洁", "日常"), icon("printer", "打印", "日常"),
        icon("router", "宽带", "日常"), icon("checkup", "体检", "日常"), icon("member", "付费会员", "日常"),
        // 购物
        icon("cart", "超市", "购物"), icon("bag", "日用品", "购物"), icon("box", "快递", "购物"),
        icon("camera2", "数码", "购物"), icon("plus2", "添置", "购物"),
        icon("headphones", "耳机", "购物"), icon("stationery", "文具", "购物"), icon("toy", "玩具", "购物"),
        // 服饰
        icon("shirt", "衣服", "服饰"), icon("shoe", "鞋子", "服饰"),
        icon("hat", "帽子", "服饰"), icon("dress", "裙子", "服饰"),
        // 美妆
        icon("brush", "彩妆", "美妆"), icon("flower", "香氛", "美妆"),
        icon("lipstick", "口红", "美妆"), icon("perfume", "香水", "美妆"),
        // 居住
        icon("home", "房贷", "居住"), icon("house_rate", "物业", "居住"), icon("sofa", "家具", "居住"),
        icon("chair", "座椅", "居住"), icon("fridge", "家电", "居住"), icon("paint", "刷漆", "居住"),
        icon("gas", "燃气", "居住"),
        // 出行
        icon("bus", "公交", "出行"), icon("metro", "地铁", "出行"), icon("train", "火车", "出行"),
        icon("plane", "机票", "出行"), icon("car", "自驾", "出行"), icon("fuel", "加油", "出行"),
        icon("toll", "过路费", "出行"), icon("park", "停车", "出行"), icon("bike", "骑行", "出行"),
        icon("walk", "步行", "出行"),
        icon("charge", "充电", "出行"), icon("taxi", "打车", "出行"), icon("hotel", "酒店", "出行"),
        icon("ticket", "门票", "出行"), icon("luggage", "行李", "出行"), icon("car_wash", "洗车", "出行"),
        // 娱乐
        icon("game", "游戏", "娱乐"), icon("game2", "手游", "娱乐"), icon("film", "影视", "娱乐"),
        icon("music", "音乐", "娱乐"), icon("camera", "摄影", "娱乐"), icon("book2", "小说", "娱乐"),
        icon("mic", "K歌", "娱乐"), icon("sound", "音响", "娱乐"),
        icon("theater", "演出", "娱乐"), icon("museum", "展览", "娱乐"),
        // 运动
        icon("dumbbell", "健身", "运动"), icon("yoga", "瑜伽", "运动"), icon("swim", "游泳", "运动"),
        icon("run", "跑步", "运动"), icon("ball", "球类", "运动"), icon("ski", "滑雪", "运动"),
        icon("hike", "徒步", "运动"),
        icon("racket", "球拍", "运动"), icon("tent", "露营", "运动"),
        // 人情
        icon("gift", "礼物", "人情"), icon("hongbao", "红包", "人情"), icon("social", "聚会", "人情"),
        icon("user", "同事", "人情"), icon("heart", "恋爱", "人情"),
        icon("treat", "请客", "人情"), icon("wedding", "婚礼", "人情"),
    )

    private val BUILT_IN: Map<String, Int> = mapOf(
        "bag" to R.drawable.ic_bag,
        "bread" to R.drawable.ic_bread,
        "bag_food" to R.drawable.ic_bag_food,
        "ball" to R.drawable.ic_ball,
        "bank" to R.drawable.ic_bank,
        "bike" to R.drawable.ic_bike,
        "bolt" to R.drawable.ic_bolt,
        "book" to R.drawable.ic_book,
        "book2" to R.drawable.ic_book2,
        "box" to R.drawable.ic_box,
        "brush" to R.drawable.ic_brush,
        "bus" to R.drawable.ic_bus,
        "cake" to R.drawable.ic_cake,
        "camera2" to R.drawable.ic_camera2,
        "car" to R.drawable.ic_car,
        "cart" to R.drawable.ic_cart,
        "cash_person" to R.drawable.ic_cash_person,
        "cat" to R.drawable.ic_cat,
        "cap" to R.drawable.ic_cap,
        "clock" to R.drawable.ic_clock,
        "coin" to R.drawable.ic_coin,
        "cup" to R.drawable.ic_cup,
        "db_tax" to R.drawable.ic_db_tax,
        "diamond" to R.drawable.ic_diamond,
        "dog" to R.drawable.ic_dog,
        "dumbbell" to R.drawable.ic_dumbbell,
        "env_r" to R.drawable.ic_env_r,
        "film" to R.drawable.ic_film,
        "flag" to R.drawable.ic_flag,
        "flower" to R.drawable.ic_flower,
        "food" to R.drawable.ic_food,
        "fruit" to R.drawable.ic_fruit,
        "fuel" to R.drawable.ic_fuel,
        "game" to R.drawable.ic_game,
        "game2" to R.drawable.ic_game2,
        "gift" to R.drawable.ic_gift,
        "hammer" to R.drawable.ic_hammer,
        "heart" to R.drawable.ic_heart,
        "hike" to R.drawable.ic_hike,
        "home" to R.drawable.ic_home,
        "hongbao" to R.drawable.ic_hongbao,
        "house_rate" to R.drawable.ic_house_rate,
        "house_yen" to R.drawable.ic_house_yen,
        "key" to R.drawable.ic_key,
        "laptop" to R.drawable.ic_laptop,
        "laundry" to R.drawable.ic_laundry,
        "ledger" to R.drawable.ic_ledger,
        "link" to R.drawable.ic_link,
        "lock" to R.drawable.ic_lock,
        "med" to R.drawable.ic_med,
        "medal" to R.drawable.ic_medal,
        "metro" to R.drawable.ic_metro,
        "milk" to R.drawable.ic_milk,
        "moon" to R.drawable.ic_moon,
        "music" to R.drawable.ic_music,
        "park" to R.drawable.ic_park,
        "percent" to R.drawable.ic_percent,
        "pen" to R.drawable.ic_pen,
        "phone" to R.drawable.ic_phone,
        "piggy" to R.drawable.ic_piggy,
        "pill" to R.drawable.ic_pill,
        "plane" to R.drawable.ic_plane,
        "receipt" to R.drawable.ic_receipt,
        "recycle" to R.drawable.ic_recycle,
        "run" to R.drawable.ic_run,
        "scissors" to R.drawable.ic_scissors,
        "service" to R.drawable.ic_service,
        "shield" to R.drawable.ic_shield,
        "shirt" to R.drawable.ic_shirt,
        "shoe" to R.drawable.ic_shoe,
        "ski" to R.drawable.ic_ski,
        "social" to R.drawable.ic_social,
        "sofa" to R.drawable.ic_sofa,
        "soda" to R.drawable.ic_soda,
        "sound" to R.drawable.ic_sound,
        "spray" to R.drawable.ic_spray,
        "sun" to R.drawable.ic_sun,
        "swim" to R.drawable.ic_swim,
        "tea" to R.drawable.ic_tea,
        "tooth" to R.drawable.ic_tooth,
        "toll" to R.drawable.ic_toll,
        "train" to R.drawable.ic_train,
        "tray" to R.drawable.ic_tray,
        "trash" to R.drawable.ic_trash,
        "user" to R.drawable.ic_user,
        "user_heart" to R.drawable.ic_user_heart,
        "walk" to R.drawable.ic_walk,
        "wallet" to R.drawable.ic_wallet,
        "water" to R.drawable.ic_water,
        "wine" to R.drawable.ic_wine,
        "wrench" to R.drawable.ic_wrench,
        "yoga" to R.drawable.ic_yoga,
        // 追加：把常用二级的语义补齐，合计 136 个
        "snack" to R.drawable.ic_snack,
        "hotpot" to R.drawable.ic_hotpot,
        "noodle" to R.drawable.ic_noodle,
        "fastfood" to R.drawable.ic_fastfood,
        "dessert" to R.drawable.ic_dessert,
        "charge" to R.drawable.ic_charge,
        "taxi" to R.drawable.ic_taxi,
        "gas" to R.drawable.ic_gas,
        "member" to R.drawable.ic_member,
        "checkup" to R.drawable.ic_checkup,
        "car_shield" to R.drawable.ic_car_shield,
        "treat" to R.drawable.ic_treat,
        "salary" to R.drawable.ic_salary,
        "refund" to R.drawable.ic_refund,
        "umbrella" to R.drawable.ic_umbrella,
        "stock" to R.drawable.ic_stock,
        "bbq" to R.drawable.ic_bbq,
        "teapot" to R.drawable.ic_teapot,
        "milk_tea" to R.drawable.ic_milk_tea,
        "moving" to R.drawable.ic_moving,
        "cleaning" to R.drawable.ic_cleaning,
        "printer" to R.drawable.ic_printer,
        "router" to R.drawable.ic_router,
        "headphones" to R.drawable.ic_headphones,
        "stationery" to R.drawable.ic_stationery,
        "toy" to R.drawable.ic_toy,
        "hat" to R.drawable.ic_hat,
        "dress" to R.drawable.ic_dress,
        "lipstick" to R.drawable.ic_lipstick,
        "perfume" to R.drawable.ic_perfume,
        "chair" to R.drawable.ic_chair,
        "fridge" to R.drawable.ic_fridge,
        "paint" to R.drawable.ic_paint,
        "hotel" to R.drawable.ic_hotel,
        "ticket" to R.drawable.ic_ticket,
        "luggage" to R.drawable.ic_luggage,
        "car_wash" to R.drawable.ic_car_wash,
        "theater" to R.drawable.ic_theater,
        "museum" to R.drawable.ic_museum,
        "racket" to R.drawable.ic_racket,
        "tent" to R.drawable.ic_tent,
        "wedding" to R.drawable.ic_wedding,
    )

    /** 界面功能图标：只给顶栏、按钮与角标用，不进分类库。 */
    private val chrome: Map<String, Int> = mapOf(
        "ai" to R.drawable.ic_ai,
        "archive" to R.drawable.ic_archive,
        "arrow_down" to R.drawable.ic_arrow_down,
        "arrow_up" to R.drawable.ic_arrow_up,
        "back" to R.drawable.ic_back,
        "backup" to R.drawable.ic_backup,
        "ban" to R.drawable.ic_ban,
        "battery" to R.drawable.ic_battery,
        "calendar" to R.drawable.ic_calendar,
        "camera" to R.drawable.ic_camera,
        "chart" to R.drawable.ic_chart,
        "check" to R.drawable.ic_check,
        "chevron" to R.drawable.ic_chevron,
        "chevron_down" to R.drawable.ic_chevron_down,
        "clip" to R.drawable.ic_clip,
        "close" to R.drawable.ic_close,
        "cloud" to R.drawable.ic_cloud,
        "document" to R.drawable.ic_document,
        "drag" to R.drawable.ic_drag,
        "export" to R.drawable.ic_export,
        "eye" to R.drawable.ic_eye,
        "eyeoff" to R.drawable.ic_eyeoff,
        "filter" to R.drawable.ic_filter,
        "fingerprint" to R.drawable.ic_fingerprint,
        "home" to R.drawable.ic_home,
        "image" to R.drawable.ic_image,
        "import" to R.drawable.ic_import,
        "info" to R.drawable.ic_info,
        "jasmine" to R.drawable.ic_jasmine,
        "menu" to R.drawable.ic_menu,
        "pencil" to R.drawable.ic_pencil,
        "mic" to R.drawable.ic_mic,
        "more" to R.drawable.ic_more,
        "moss" to R.drawable.ic_moss,
        "note" to R.drawable.ic_note,
        "pause" to R.drawable.ic_pause,
        "pet" to R.drawable.ic_pet,
        "phone" to R.drawable.ic_phone,
        "play" to R.drawable.ic_play,
        "plus" to R.drawable.ic_plus,
        "plus2" to R.drawable.ic_plus2,
        "refresh" to R.drawable.ic_refresh,
        "repeat" to R.drawable.ic_repeat,
        "search" to R.drawable.ic_search,
        "select" to R.drawable.ic_select,
        "settings" to R.drawable.ic_settings,
        "share" to R.drawable.ic_share,
        "slice" to R.drawable.ic_slice,
        "sort" to R.drawable.ic_sort,
        "spark" to R.drawable.ic_spark,
        "switch" to R.drawable.ic_switch,
        "tag" to R.drawable.ic_tag,
        "undo" to R.drawable.ic_undo,
        "warn" to R.drawable.ic_warn,
        "wifi" to R.drawable.ic_wifi,
    )

    /** 没有字形资源的键不进库：图标本身可选，缺失时按名称首字兜底即可。 */
    private val byKey: Map<String, IconMeta> = catalog.mapNotNull { (key, name, group) ->
        val res = chrome[key] ?: BUILT_IN[key] ?: return@mapNotNull null
        key to IconMeta(key, res, name, group)
    }.toMap()

    /** 分类图标库，按组顺序铺开。 */
    val library: List<IconMeta> = groupNames.flatMap { group -> byKey.values.filter { it.group == group } }

    val allKeys: List<String> get() = byKey.keys.sorted()

    /** 全部内置矢量图标，供启动后逐帧预热，避开记账页/分类页首次组合时的集中解析。 */
    val allDrawables: List<Int> = (BUILT_IN.values + chrome.values).distinct()

    @DrawableRes
    fun drawable(key: String): Int? = byKey[key]?.res ?: chrome[key] ?: BUILT_IN[key]

    fun meta(key: String): IconMeta? = byKey[key]

    fun groupOf(key: String): String? = byKey[key]?.group

    fun isEmoji(key: String): Boolean = key.any { !it.isLetterOrDigit() && !it.isWhitespace() && it.code > 0x2199 }

    /** 名称本身即兜底图标：首字 / emoji 都是合法的 icon_key。 */
    fun isFallbackKey(key: String): Boolean = drawable(key) == null

    fun search(query: String): List<IconMeta> {
        val q = query.trim()
        if (q.isEmpty()) return library
        return library.filter { it.displayName.contains(q, true) || it.group.contains(q, true) || it.key.contains(q, true) }
    }

    /**
     * 存量二级图标的默认映射：只认精确名称与这张白名单别名，
     * 名字对不上就保持原样交给用户自己改——误判比丑更糟。
     */
    private val nameAliases = mapOf(
        "饮料" to "tea",
        "纯净水" to "water",
        "矿泉水" to "water",
        "停车费" to "park",
        "充电" to "charge",
        "请客" to "treat",
        "打车" to "taxi",
        "网购" to "cart",
        "美容" to "brush",
        "旅行" to "plane",
    )

    fun keyForName(name: String): String? {
        val trimmed = name.trim()
        nameAliases[trimmed]?.let { return it }
        return byKey.values.firstOrNull { it.displayName == trimmed }?.key
    }

    /** 写库前的闸门：未收录的键会静默退化成名称首字 tile，与其退化不如拒绝。 */
    fun isKnown(key: String): Boolean = byKey.containsKey(key)

    /** 校验失败时给几条候选：先按名称精确命中那一个，命中不了就给同组或全库前几条。 */
    fun suggestions(name: String, group: String? = null, limit: Int = 6): List<IconMeta> {
        keyForName(name)?.let { hit -> byKey[hit]?.let { return listOf(it) } }
        val pool = group?.let { g -> byKey.values.filter { it.group == g } }.orEmpty()
            .ifEmpty { byKey.values }
        return pool.take(limit)
    }

    /** 上一轮迁移给默认二级留下的键；v2 只把这些当作「仍是默认」，用户后来换过的键不认。 */
    private val legacyChildKeys = mapOf(
        "充电" to "bolt",
        "请客" to "social",
        "付费会员" to "phone",
        "车险" to "shield",
    )

    /**
     * 存量二级图标的 v2 映射：只有还长着父级的图、或仍是上一轮迁移的结果时才按名称换，
     * 用户手动选过的图标一律不动；返回 null 表示保持原样。
     */
    fun remapChildKey(name: String, childKey: String, parentKey: String): String? {
        val trimmed = name.trim()
        val inherited = childKey == parentKey || legacyChildKeys[trimmed] == childKey
        if (!inherited) return null
        return keyForName(trimmed)
    }

}
