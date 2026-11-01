package io.github.guohangbk.tally.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.guohangbk.tally.data.notify.NotificationLink
import io.github.guohangbk.tally.data.preferences.ThemeMode
import io.github.guohangbk.tally.di.AppGraphProvider
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.model.StatsBucket
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.domain.model.StatsRequest
import io.github.guohangbk.tally.ui.bill.BillDetailSheet
import io.github.guohangbk.tally.ui.bill.BillEntryScreen
import io.github.guohangbk.tally.ui.budget.BudgetHistoryScreen
import io.github.guohangbk.tally.ui.budget.BudgetScreen
import io.github.guohangbk.tally.ui.backup.BackupScreen
import io.github.guohangbk.tally.ui.assistant.AssistantChatScreen
import io.github.guohangbk.tally.ui.assistant.AssistantConfigScreen
import io.github.guohangbk.tally.ui.assistant.ScreenshotScreen
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.ui.category.CategoryScreen
import io.github.guohangbk.tally.ui.category.IconLibraryMode
import io.github.guohangbk.tally.ui.category.IconLibraryPage
import io.github.guohangbk.tally.ui.design.TallyTheme
import io.github.guohangbk.tally.ui.design.WarmIconPainters
import io.github.guohangbk.tally.ui.design.IconLibrary
import io.github.guohangbk.tally.ui.design.TallyToastHost
import io.github.guohangbk.tally.ui.home.TallyHomeScreen
import io.github.guohangbk.tally.ui.share.PosterScreen
import io.github.guohangbk.tally.ui.importexport.ExportScreen
import io.github.guohangbk.tally.ui.importexport.ImportScreen
import io.github.guohangbk.tally.ui.installment.InstallmentFormScreen
import io.github.guohangbk.tally.ui.installment.InstallmentScreen
import io.github.guohangbk.tally.ui.ledger.LedgerScreen
import io.github.guohangbk.tally.ui.ledger.LedgerViewModel
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.onboarding.OnboardingScreen
import io.github.guohangbk.tally.ui.recurring.RecurringFormScreen
import io.github.guohangbk.tally.ui.recurring.RecurringScreen
import io.github.guohangbk.tally.ui.reimbursement.ReimbursementScreen
import io.github.guohangbk.tally.ui.recycle.RecycleBinScreen
import io.github.guohangbk.tally.ui.search.SearchScreen
import io.github.guohangbk.tally.ui.settings.GuideScreen
import io.github.guohangbk.tally.ui.settings.LicenseScreen
import io.github.guohangbk.tally.ui.settings.NotificationSettingsScreen
import io.github.guohangbk.tally.ui.settings.SettingsScreen
import io.github.guohangbk.tally.ui.stats.TallyStatsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val STATS = "stats?ledgerId={ledgerId}"
    /** 使用指南（设置 → 关于）：怎么操作 + 按什么口径算，收在同一页。 */
    const val GUIDE = "settings/guide"
    /** 许可与依赖（设置 → 关于）：BUSL-1.1 源码可用许可 + 第三方依赖清单。 */
    const val LICENSE = "settings/license"
    const val SETTINGS = "settings"
    /** 提醒与通知独立一页（T-01）：四类开关与时刻都在这，设置主页只留一个入口。 */
    const val NOTIFICATIONS = "settings/notifications"
    const val KEYWORD_RULES = "keyword-rules"
    const val RECYCLE = "recycle"
    const val LEDGERS = "ledgers"
    const val LEDGER_ICON = "ledger/icon"
    const val CATEGORIES = "categories"
    const val BILL_NEW = "bill/new?l1={l1}&l2={l2}&type={type}&amount={amount}&note={note}&source={source}&sk={sk}&at={at}&kw={kw}"
    const val SEARCH = "search?ledgerId={ledgerId}&categoryL1={categoryL1}&categoryL2={categoryL2}"
    const val IMPORT = "import"
    const val EXPORT = "export"
    const val RECURRING = "recurring"
    const val RECURRING_NEW = "recurring/new"
    const val RECURRING_EDIT = "recurring/edit/{ruleId}"
    const val INSTALLMENTS = "installments"
    const val INSTALLMENT_NEW = "installments/new"
    const val INSTALLMENT_EDIT = "installments/edit/{planId}"
    const val REIMBURSEMENT = "reimbursement"
    const val BACKUP = "backup"
    /** 预算总览；编辑是页内状态，不单独占路由。 */
    const val BUDGET = "budget"
    /** 预算历史（BU21-15）：只读回看本账本最近若干段的额度与完成，从总览底部入口进。 */
    const val BUDGET_HISTORY = "budget/history"
    const val ASSISTANT = "assistant"
    const val ASSISTANT_SCREENSHOT = "assistant/screenshot"
    const val ASSISTANT_CHAT = "assistant/chat?prompt={prompt}"
    /** 小莉配置独立成一页：从 app 设置里的「小莉配置」进，不再是对话页右上角压出来的弹层。 */
    const val ASSISTANT_CONFIG = "assistant/config"
    /** 海报页只带那一段账的口径（账本 / 维度 / 锚点 / 区间），不带别的——口径换了就重画。 */
    const val POSTER = "poster?ledgerId={ledgerId}&granularity={granularity}&anchor={anchor}" +
        "&rangeEnd={rangeEnd}&bucket={bucket}&excludePending={excludePending}"

    const val BILL_EDIT = "bill/edit/{billId}"
    const val CATEGORY_NEW_L1 = "category/new-l1?type={type}"
    const val CATEGORY_NEW_L2 = "category/new-l2/{parentId}"
    const val CATEGORY_ICON = "category/icon/{categoryId}"

    fun billEdit(id: String) = "bill/edit/$id"

    /** 首页选中的账本要跟着进统计与搜索，空串表示「全部账本」。 */
    fun stats(ledgerId: String?) = "stats?ledgerId=${ledgerId.orEmpty()}"

    /**
     * 统计页那段账整个搬进海报页：维度、锚点、区间的右边界与分桶都跟着走，
     * 于是海报取的数与屏上那四段是同一份 request，不存在「海报算多了一遍」。
     */
    fun poster(request: io.github.guohangbk.tally.domain.model.StatsRequest) =
        "poster?ledgerId=${request.ledgerId.orEmpty()}" +
            "&granularity=${request.granularity.name}" +
            "&anchor=${request.periodStart}" +
            "&rangeEnd=${request.rangeEndExclusive ?: ""}" +
            "&bucket=${request.bucket?.name ?: ""}" +
            "&excludePending=${if (request.excludePendingReimburse) 1 else 0}"

    /** 分类管理的「查看统计数据」带着分类进搜索，空串即该维度不限。 */
    fun search(ledgerId: String?, categoryL1: String? = null, categoryL2: String? = null) =
        "search?ledgerId=${ledgerId.orEmpty()}&categoryL1=${categoryL1.orEmpty()}&categoryL2=${categoryL2.orEmpty()}"

    /** 统计页「问小莉」把这段结论带进对话页当上下文，不自动发送。 */
    fun assistantChat(prompt: String? = null) = "assistant/chat?prompt=" + android.net.Uri.encode(prompt.orEmpty())

    /**
     * 「再记一笔」与快捷新建都走这一条路由：只带分类与类型，不带金额、备注、日期。
     * 语音与截图链路另外带上识别结果与来源原话——落库前必须过人手，但不用人手再敲一遍。
     * [occurredAt] 只在机器已经认出落账日（「昨天打车 30」）时带上，否则记账页会把它写回今天。
     * [keyword] 是这次命中分类的那个词：保存时若分类被改掉，才问「以后都记到新的分类？」
     * （LN-01）。没有命中词的纯手动记账不带它，也就不会冒出沉淀提示。
     */
    fun billNew(
        l1Id: String?,
        l2Id: String?,
        type: BillType?,
        amountMinor: Long? = null,
        note: String? = null,
        source: String? = null,
        sourceKind: String? = null,
        occurredAt: Long? = null,
        keyword: String? = null,
    ): String {
        val base = "bill/new"
        val query = listOfNotNull(
            l1Id?.let { "l1=$it" },
            l2Id?.let { "l2=$it" },
            type?.let { "type=$it" },
            amountMinor?.let { "amount=$it" },
            note?.takeIf { it.isNotBlank() }?.let { "note=" + android.net.Uri.encode(it) },
            source?.takeIf { it.isNotBlank() }?.let { "source=" + android.net.Uri.encode(it) },
            sourceKind?.takeIf { it.isNotBlank() }?.let { "sk=$it" },
            occurredAt?.let { "at=$it" },
            keyword?.takeIf { it.isNotBlank() }?.let { "kw=" + android.net.Uri.encode(it) },
        )
        return if (query.isEmpty()) base else "$base?" + query.joinToString("&")
    }

    fun recurringEdit(id: String) = "recurring/edit/$id"

    fun installmentEdit(id: String) = "installments/edit/$id"

    fun categoryNewL1(type: String) = "category/new-l1?type=$type"
    fun categoryNewL2(parentId: String) = "category/new-l2/$parentId"
    fun categoryIcon(categoryId: String) = "category/icon/$categoryId"
}

@Composable
fun TallyAppRoot() {
    val graph = rememberAppGraph()
    // DataStore 第一帧还没读到偏好：先给一张干净的纸面，
    // 否则 onboardingCompleted 的默认 false 会先把引导页画出来，读到值再整树重建，
    // 看起来就是「闪一下初始化页 + 掉帧」。
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = null)
    AppGraphProvider(graph) {
        TallyTheme(darkTheme = isDark(settings?.themeMode ?: ThemeMode.SYSTEM)) {
            val loaded = settings
            if (loaded == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(io.github.guohangbk.tally.ui.design.tallyColors.paper),
                )
            } else {
                TallyAppShell(
                    onboardingDone = loaded.onboardingCompleted,
                    assistantEnabled = loaded.assistantEnabled,
                    debugMode = loaded.debugMode,
                )
            }
        }
    }
}

@Composable
private fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
private fun TallyAppShell(onboardingDone: Boolean, assistantEnabled: Boolean, debugMode: Boolean = false) {
    val graph = rememberAppGraph()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showShell = onboardingDone

    // 系统分享进来的截图只负责把用户送到草稿页；uri 由那一页自己取用并清空。
    // 二级独立图标第二轮：先改子级再改「零食」这一级，避免父级先换掉后认不出继承关系。
    LaunchedEffect(graph) {
        if (!graph.preferences.childIconsMigratedV2()) {
            graph.categoryRepository.remapChildIcons(IconLibrary::remapChildKey)
            graph.categoryRepository.remapLevel1Icon(name = "零食", from = "cup", to = "snack")
            graph.preferences.markChildIconsMigratedV2()
        }
    }

    LaunchedEffect(graph) {
        graph.sharedScreenshot.collect { uri ->
            if (uri != null) navController.navigate(Routes.ASSISTANT_SCREENSHOT)
        }
    }

    // 通知点达（T-04）：Activity 只写下去处，这里在导航图就绪之后翻译成一跳。
    // BillDetail 不清空——账单详情是首页那张弹层，链接留给首页自己消费并清空。
    // 回首页只能整栈回退：switchTab 那套 launchSingleTop 在首页已在栈底时等于什么都不做。
    // 其余回首页的落点（组件点了「本月概览」）用完即清：StateFlow 重复写同一个值不会再发，
    // 留着上一条就等于下一次点同一枚按钮没反应。
    LaunchedEffect(graph) {
        graph.pendingLink.collect { link ->
            if (link == null) return@collect
            val route = notificationRoute(link)
            if (route != null) {
                navController.navigate(route) {
                    // 记账页复用栈里那张就只换路由不换参数：组件上点「支出」再点「收入」会都落在支出。
                    // 收到首页再压一页，参数一定是最新的一份，回退也只回首页。
                    if (route.startsWith("bill/new")) popUpTo(Routes.HOME)
                    launchSingleTop = true
                }
                graph.pendingLink.value = null
            } else {
                // 已经在首页就别再 navigate 一页，否则回退会退到「另一个首页」。
                if (navController.currentDestination?.route != Routes.HOME &&
                    !navController.popBackStack(Routes.HOME, inclusive = false)
                ) {
                    navController.navigate(Routes.HOME)
                }
                if (link !is NotificationLink.BillDetail) graph.pendingLink.value = null
            }
        }
    }

    WarmIconPainters()

    Scaffold { padding ->
        // 状态栏不再由外壳吃掉：整页要真·edge-to-edge，抽屉与弹层才铺得到屏幕顶。
        // 让开状态栏这件事交回各页自己的顶栏（TallyTopBar / 记账页 EntryHeader / 引导页）。
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                ),
        ) {
                NavHost(
                    navController = navController,
                    startDestination = if (onboardingDone) Routes.HOME else Routes.ONBOARDING,
                    enterTransition = pushEnter,
                    exitTransition = pushExit,
                    popEnterTransition = popEnter,
                    popExitTransition = popExit,
                ) {
                    composable(Routes.ONBOARDING) {
                        OnboardingScreen(
                            onImport = { navController.navigate(Routes.IMPORT) },
                            onImportBackup = { navController.navigate(Routes.BACKUP) },
                            onStart = {
                                navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                            },
                        )
                    }
                    composable(Routes.HOME) {
                        TallyHomeScreen(
                            currentRoute = currentRoute,
                            onOpenLedgers = { navController.navigate(Routes.LEDGERS) },
                            onOpenAssistant = { navController.navigate(Routes.ASSISTANT) },
                            onOpenStats = { ledgerId -> navController.navigate(Routes.stats(ledgerId)) },
                            onOpenBill = { l1, l2, type -> navController.navigate(Routes.billNew(l1, l2, type)) },
                            onOpenShotDraft = { bill ->
                                navController.navigate(
                                    Routes.billNew(
                                        l1Id = bill.categoryL1Id,
                                        l2Id = bill.categoryL2Id,
                                        type = bill.type,
                                        amountMinor = bill.amountMinor,
                                        note = bill.merchant,
                                        source = "第 ${bill.imageIndex + 1} 张截图",
                                        sourceKind = "shot",
                                        occurredAt = bill.occurredAt,
                                        keyword = bill.keyword,
                                    ),
                                )
                            },
                            onOpenDraftBill = { capture ->
                                navController.navigate(
                                    Routes.billNew(
                                        l1Id = capture.categoryL1Id,
                                        l2Id = capture.categoryL2Id,
                                        type = capture.type,
                                        amountMinor = capture.amountMinor,
                                        note = capture.note,
                                        source = capture.heard,
                                        sourceKind = "voice",
                                        occurredAt = capture.occurredAt,
                                        keyword = capture.matchedKeyword,
                                    ),
                                )
                            },
                            onOpenSearch = { ledgerId -> navController.navigate(Routes.search(ledgerId)) },
                            onOpenBillEdit = { navController.navigate(Routes.billEdit(it)) },
                            onRepeatBill = { l1, l2, type -> navController.navigate(Routes.billNew(l1, l2, type)) },
                            onNavigateRoot = { navController.switchTab(it) },
                            onNavigateRoute = { route -> navController.navigate(if (route.startsWith("bill/new")) Routes.billNew(null, null, null) else route) },
                        )
                    }
                    composable(Routes.STATS) { entry ->
                        TallyStatsScreen(
                            initialLedgerId = entry.arguments?.getString("ledgerId")?.ifEmpty { null },
                            onBack = { navController.popBackStack() },
                            // 海报入口靠它跳转：漏传就走默认的空实现，点了什么也不会发生。
                            onNavigateRoute = { route -> navController.navigate(route) },
                            onOpenReimbursement = { navController.navigate(Routes.REIMBURSEMENT) },
                            onAskAssistant = { report -> navController.navigate(Routes.assistantChat(report)) },
                            onOpenBillEdit = { navController.navigate(Routes.billEdit(it)) },
                            onRepeatBill = { l1, l2, type -> navController.navigate(Routes.billNew(l1, l2, type)) },
                        )
                    }
                    composable(Routes.GUIDE) { GuideScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.LICENSE) { LicenseScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.POSTER) { entry ->
                        val arguments = entry.arguments
                        PosterScreen(
                            graph = graph,
                            request = StatsRequest(
                                ledgerId = arguments?.getString("ledgerId")?.ifEmpty { null },
                                periodStart = arguments?.getString("anchor")?.toLongOrNull()
                                    ?: Period.monthRange(System.currentTimeMillis()).startAt,
                                granularity = arguments?.getString("granularity")
                                    ?.let { runCatching { StatsGranularity.valueOf(it) }.getOrNull() }
                                    ?: StatsGranularity.MONTH,
                                rangeEndExclusive = arguments?.getString("rangeEnd")?.toLongOrNull(),
                                bucket = arguments?.getString("bucket")
                                    ?.let { runCatching { StatsBucket.valueOf(it) }.getOrNull() },
                                // 统计页那颗「不计待报销」也得跟过来，否则海报的数会比屏上多一截。
                                excludePendingReimburse = arguments?.getString("excludePending") == "1",
                            ),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.LEDGERS) {
                        LedgerScreen(
                            viewModel = tallyViewModel { LedgerViewModel(graph) },
                            onBack = { navController.popBackStack() },
                            onPickLedgerIcon = { navController.navigate(Routes.LEDGER_ICON) },
                        )
                    }
                    composable(Routes.LEDGER_ICON) { entry ->
                        // 挂在账本页的宿主上取同一份 ViewModel：图标库保存的键才落得进编辑弹窗的草稿。
                        val ledgerEntry = remember(entry) { navController.getBackStackEntry(Routes.LEDGERS) }
                        val ledgerViewModel = tallyViewModel(ledgerEntry) { LedgerViewModel(graph) }
                        IconLibraryPage(
                            mode = IconLibraryMode.LedgerIcon,
                            onBack = { navController.popBackStack() },
                            onSaved = { key ->
                                ledgerViewModel.setPendingIcon(key)
                                navController.popBackStack()
                            },
                        )
                    }
                    composable(Routes.CATEGORIES) {
                        CategoryScreen(
                            onBack = { navController.popBackStack() },
                            onAddLevel1 = { type -> navController.navigate(Routes.categoryNewL1(type.name)) },
                            onAddLevel2 = { parentId -> navController.navigate(Routes.categoryNewL2(parentId)) },
                            onEditIcon = { categoryId -> navController.navigate(Routes.categoryIcon(categoryId)) },
                            onOpenCategoryStats = { ledgerId, l1, l2 ->
                                navController.navigate(Routes.search(ledgerId, l1, l2))
                            },
                        )
                    }
                    composable(Routes.CATEGORY_NEW_L1) { entry ->
                        val type = entry.arguments?.getString("type")
                        IconLibraryPage(
                            mode = IconLibraryMode.NewLevel1(if (type == "INCOME") CategoryType.INCOME else CategoryType.EXPENSE),
                            onBack = { navController.popBackStack() },
                            onSaved = { _ -> navController.popBackStack() },
                        )
                    }
                    composable(Routes.CATEGORY_NEW_L2) { entry ->
                        val parentId = entry.arguments?.getString("parentId").orEmpty()
                        IconLibraryPage(
                            mode = IconLibraryMode.NewLevel2(parentId),
                            onBack = { navController.popBackStack() },
                            onSaved = { _ -> navController.popBackStack() },
                        )
                    }
                    composable(Routes.CATEGORY_ICON) { entry ->
                        val categoryId = entry.arguments?.getString("categoryId").orEmpty()
                        IconLibraryPage(
                            mode = IconLibraryMode.EditIcon(categoryId),
                            onBack = { navController.popBackStack() },
                            onSaved = { _ -> navController.popBackStack() },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenImport = { navController.navigate(Routes.IMPORT) },
                            onOpenExport = { navController.navigate(Routes.EXPORT) },
                            onOpenBackup = { navController.navigate(Routes.BACKUP) },
                            onOpenAssistantConfig = { navController.navigate(Routes.ASSISTANT_CONFIG) },
                            onOpenKeywordRules = { navController.navigate(Routes.KEYWORD_RULES) },
                            onOpenRecycle = { navController.navigate(Routes.RECYCLE) },
                            onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                            onOpenGuide = { navController.navigate(Routes.GUIDE) },
                            onOpenLicense = { navController.navigate(Routes.LICENSE) },
                        )
                    }
                    composable(Routes.NOTIFICATIONS) {
                        NotificationSettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.KEYWORD_RULES) {
                        io.github.guohangbk.tally.ui.keyword.KeywordRuleScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.RECYCLE) {
                        RecycleBinScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        Routes.BILL_NEW,
                        enterTransition = sheetEnter,
                        exitTransition = sheetExit,
                        popEnterTransition = sheetPopEnter,
                        popExitTransition = sheetPopExit,
                    ) { entry ->
                        BillEntryScreen(
                            prefillL1Id = entry.arguments?.getString("l1")?.ifEmpty { null },
                            prefillL2Id = entry.arguments?.getString("l2")?.ifEmpty { null },
                            prefillType = entry.arguments?.getString("type").toBillType(),
                            prefillAmountMinor = entry.arguments?.getString("amount")?.toLongOrNull(),
                            prefillNote = entry.arguments?.getString("note")?.ifEmpty { null },
                            prefillSource = entry.arguments?.getString("source")?.ifEmpty { null },
                            prefillSourceKind = entry.arguments?.getString("sk")?.ifEmpty { null },
                            prefillOccurredAt = entry.arguments?.getString("at")?.toLongOrNull(),
                            prefillKeyword = entry.arguments?.getString("kw")?.ifEmpty { null },
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() },
                            onAddSubCategory = { parentId -> navController.navigate(Routes.categoryNewL2(parentId)) },
                        )
                    }
                    composable(
                        Routes.BILL_EDIT,
                        enterTransition = sheetEnter,
                        exitTransition = sheetExit,
                        popEnterTransition = sheetPopEnter,
                        popExitTransition = sheetPopExit,
                    ) { entry ->
                        val id = entry.arguments?.getString("billId").orEmpty()
                        BillEntryScreen(
                            billId = id,
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() },
                            // 改这笔时同样要能加二级：这一页和新建是同一个页面，入口不该少一条。
                            onAddSubCategory = { parentId -> navController.navigate(Routes.categoryNewL2(parentId)) },
                        )
                    }
                    composable(Routes.SEARCH) { entry ->
                        SearchScreen(
                            initialLedgerId = entry.arguments?.getString("ledgerId")?.ifEmpty { null },
                            initialCategoryL1Id = entry.arguments?.getString("categoryL1")?.ifEmpty { null },
                            initialCategoryL2Id = entry.arguments?.getString("categoryL2")?.ifEmpty { null },
                            onBack = { navController.popBackStack() },
                            onOpenBillEdit = { navController.navigate(Routes.billEdit(it)) },
                            onRepeatBill = { l1, l2, type -> navController.navigate(Routes.billNew(l1, l2, type)) },
                        )
                    }
                    composable(Routes.IMPORT) { ImportScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.EXPORT) { ExportScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.RECURRING) {
                        RecurringScreen(
                            onBack = { navController.popBackStack() },
                            onCreate = { navController.navigate(Routes.RECURRING_NEW) },
                            onEdit = { id -> navController.navigate(Routes.recurringEdit(id)) },
                        )
                    }
                    composable(Routes.RECURRING_NEW) { RecurringFormScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.RECURRING_EDIT) { entry ->
                        RecurringFormScreen(
                            onBack = { navController.popBackStack() },
                            ruleId = entry.arguments?.getString("ruleId"),
                        )
                    }
                    composable(Routes.INSTALLMENTS) {
                        InstallmentScreen(
                            onBack = { navController.popBackStack() },
                            onCreate = { navController.navigate(Routes.INSTALLMENT_NEW) },
                            onEdit = { id -> navController.navigate(Routes.installmentEdit(id)) },
                        )
                    }
                    composable(Routes.INSTALLMENT_NEW) { InstallmentFormScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.INSTALLMENT_EDIT) { entry ->
                        InstallmentFormScreen(
                            planId = entry.arguments?.getString("planId").orEmpty(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.REIMBURSEMENT) {
                        ReimbursementScreen(
                            onBack = { navController.popBackStack() },
                            onOpenBillEdit = { navController.navigate(Routes.billEdit(it)) },
                            onRepeatBill = { l1, l2, type -> navController.navigate(Routes.billNew(l1, l2, type)) },
                        )
                    }
                    composable(Routes.BACKUP) { BackupScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.BUDGET) {
                        BudgetScreen(
                            onBack = { navController.popBackStack() },
                            onOpenHistory = { navController.navigate(Routes.BUDGET_HISTORY) },
                        )
                    }
                    composable(Routes.BUDGET_HISTORY) {
                        BudgetHistoryScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.ASSISTANT) {
                        AssistantChatScreen(
                            onBack = { navController.popBackStack() },
                            onOpenDraft = { draft -> navController.openChatDraft(draft) },
                        )
                    }
                    composable(Routes.ASSISTANT_CONFIG) {
                        AssistantConfigScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.ASSISTANT_SCREENSHOT) {
                        ScreenshotScreen(
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.switchTab(Routes.HOME) },
                        )
                    }
                    composable(Routes.ASSISTANT_CHAT) { entry ->
                        AssistantChatScreen(
                            initialPrompt = entry.arguments?.getString("prompt")?.ifEmpty { null },
                            onBack = { navController.popBackStack() },
                            onOpenDraft = { draft -> navController.openChatDraft(draft) },
                        )
                    }
                }
            // 全站唯一的提示通道：贴底居中的气泡，几秒自动收起，不占内容布局。
            // 调试模式下抬高一层，免得和左下角的控制台胶囊挤在同一条带上。
            TallyToastHost(
                contentBottomPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = if (debugMode) 136.dp else 96.dp,
                    start = 20.dp,
                    end = 20.dp,
                ),
            )
            // 调试模式：每个页面右下角都能调出控制台（设置里连点包名 5 次开启）。
            if (debugMode) {
                io.github.guohangbk.tally.ui.shell.DebugConsoleOverlay(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 96.dp),
                )
            }
        }
    }
}

/** 记账卡上「补分类 / 改一下」：把已识别的字段带进记账页，落库仍由人在那里确认。 */
private fun NavHostController.openChatDraft(draft: io.github.guohangbk.tally.ui.assistant.ChatBillDraft) {
    navigate(
        Routes.billNew(
            l1Id = draft.categoryL1Id,
            l2Id = draft.categoryL2Id,
            type = draft.type,
            amountMinor = draft.amountMinor,
            note = draft.note ?: draft.merchant,
            source = draft.merchant,
            sourceKind = if (draft.source == io.github.guohangbk.tally.domain.model.BillSource.SCREENSHOT) "shot" else "voice",
            // 卡上写着「昨天」，跳进记账页却落回今天，等于把人刚确认的那一格又抹掉。
            occurredAt = draft.occurredAt,
        ),
    )
}

/** 推入页：淡入 + 从右侧轻推一小段，返回时原路收回去。 */
private val pushEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(200)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 12 }
}
private val pushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(140))
}
private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(200))
}
private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(160)) + slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 12 }
}

/**
 * 记账页：从下往上弹起，返回时对称收束。
 * 不再叠 scale——整页是一张铺满屏幕的重组图，缩放等于每帧重绘一次整页，
 * 而这正是进入记账页那 240ms 里掉帧的主因；淡入 + 上移已经能读出"面板升起"。
 */
private val sheetEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(180)) +
        slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 14 }
}
private val sheetExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(120))
}
private val sheetPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(180))
}
private val sheetPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(160)) +
        slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 16 }
}

private fun String?.toBillType(): BillType? = when (this) {
    "EXPENSE" -> BillType.EXPENSE
    "INCOME" -> BillType.INCOME
    else -> null
}

/** 主导航切换保持栈稳定；详情页用普通 navigate。 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}


