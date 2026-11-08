package io.github.guohangbk.tally.di

import android.content.Context
import android.net.Uri
import androidx.room.Room
import io.github.guohangbk.tally.data.ai.AiAnalysisService
import io.github.guohangbk.tally.data.ai.AiBillDraftService
import io.github.guohangbk.tally.data.ai.AiConfigRepository
import io.github.guohangbk.tally.data.ai.AiConversationRepository
import io.github.guohangbk.tally.data.ai.AiGateway
import io.github.guohangbk.tally.data.ai.AiKeyVault
import io.github.guohangbk.tally.data.ai.KeywordRuleRepository
import io.github.guohangbk.tally.data.ai.KeywordSediment
import io.github.guohangbk.tally.data.ai.OpenAiCompatibleClient
import io.github.guohangbk.tally.data.attachment.AttachmentCleanup
import io.github.guohangbk.tally.data.attachment.AttachmentStore
import io.github.guohangbk.tally.data.automation.AutomationService
import io.github.guohangbk.tally.data.backup.BackupService
import io.github.guohangbk.tally.data.backup.SecretVault
import io.github.guohangbk.tally.data.backup.WebDavClient
import io.github.guohangbk.tally.data.backup.WebDavRepository
import io.github.guohangbk.tally.data.db.DevSchemaHeal
import io.github.guohangbk.tally.data.db.TallyDatabase
import io.github.guohangbk.tally.data.notify.NotificationLink
import io.github.guohangbk.tally.data.notify.ReminderCoordinator
import io.github.guohangbk.tally.data.notify.TallyNotifier
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.data.importexport.ExportService
import io.github.guohangbk.tally.data.ocr.MlKitTextReader
import io.github.guohangbk.tally.data.ocr.ScreenshotTextReader
import io.github.guohangbk.tally.data.importexport.ImportService
import io.github.guohangbk.tally.data.repository.BillRepository
import io.github.guohangbk.tally.data.repository.BudgetRepository
import io.github.guohangbk.tally.data.repository.CategoryRepository
import io.github.guohangbk.tally.data.repository.InstallmentRepository
import io.github.guohangbk.tally.data.repository.LedgerRepository
import io.github.guohangbk.tally.data.repository.RecurringRepository
import io.github.guohangbk.tally.data.repository.ReimbursementRepository
import io.github.guohangbk.tally.data.repository.RecycleBinRepository
import io.github.guohangbk.tally.data.repository.SettingRepository
import io.github.guohangbk.tally.data.speech.SpeechPort
import io.github.guohangbk.tally.data.speech.SystemSpeechPort
import io.github.guohangbk.tally.data.update.UpdateChecker
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.ai.AiClient
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.domain.model.StatsRequest
import io.github.guohangbk.tally.ui.design.IconLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 不引入 DI 框架，手工装配单一依赖图；包一层仅为 ViewModel 与测试提供注入点。
 * UI 测试传入内存库，生产代码只用 context。
 */
class AppGraph(
    context: Context,
    val database: TallyDatabase = fileDatabase(context.applicationContext),
    val preferences: TallyPreferences = TallyPreferences.from(context.applicationContext),
    /** 测试里塞 FakeAiClient，就能在没有网络的情况下跑完小莉的全部分支。 */
    val injectedAiClient: AiClient? = null,
) {
    val categoryRepository: CategoryRepository = CategoryRepository(
        categoryDao = database.categoryDao(),
        billDao = database.billDao(),
        keywordRuleDao = database.keywordRuleDao(),
        preferences = preferences,
        // 图标库在 ui.design（键要映射到 drawable），组合根引一次就够了。
        isKnownIconKey = IconLibrary::isKnown,
    )

    val ledgerRepository: LedgerRepository = LedgerRepository(
        ledgerDao = database.ledgerDao(),
        billDao = database.billDao(),
        categories = categoryRepository,
        preferences = preferences,
    )

    val billRepository: BillRepository = BillRepository(
        database = database,
        billDao = database.billDao(),
        ledgerDao = database.ledgerDao(),
        categoryDao = database.categoryDao(),
        attachmentDao = database.attachmentDao(),
        preferences = preferences,
    )

    val budgetRepository: BudgetRepository = BudgetRepository(
        budgetDao = database.budgetDao(),
        categoryDao = database.categoryDao(),
        billRepository = billRepository,
        preferences = preferences,
    )

    val importService: ImportService = ImportService(
        database = database,
        billDao = database.billDao(),
        ledgerDao = database.ledgerDao(),
        categoryDao = database.categoryDao(),
        attachmentDao = database.attachmentDao(),
        preferences = preferences,
        importBatchDao = database.importBatchDao(),
        importBillDao = database.importBillDao(),
    )

    val exportService: ExportService = ExportService(
        billDao = database.billDao(),
        ledgerDao = database.ledgerDao(),
        categoryDao = database.categoryDao(),
        attachmentDao = database.attachmentDao(),
    )

    val settingRepository: SettingRepository = SettingRepository(database.appSettingDao())

    val recurringRepository: RecurringRepository = RecurringRepository(
        ruleDao = database.recurringRuleDao(),
        ledgerDao = database.ledgerDao(),
        categoryDao = database.categoryDao(),
        preferences = preferences,
    )

    val installmentRepository: InstallmentRepository = InstallmentRepository(
        planDao = database.installmentPlanDao(),
        ledgerDao = database.ledgerDao(),
        categoryDao = database.categoryDao(),
        // 明细弹层要说清「这一期到底入没入账」，得回 bill 表看真实账单，不能只信生成水位
        billDao = database.billDao(),
        preferences = preferences,
    )

    val reimbursementRepository: ReimbursementRepository = ReimbursementRepository(database.reimbursementDao())

    val automationService: AutomationService = AutomationService(
        database = database,
        recurringRepository = recurringRepository,
        installmentRepository = installmentRepository,
        billRepository = billRepository,
    )

    /** 通知的唯一出口：渠道、锁屏脱敏与点达 intent 都在它里面。 */
    val tallyNotifier: TallyNotifier = TallyNotifier(context.applicationContext)

    /**
     * 「补执行 + 提醒」的组合入口。启动、回到前台与 WorkManager 三条路都只调它，
     * 免得谁漏了一步就出现「账补了、人不知道」。
     */
    val reminderCoordinator: ReminderCoordinator = ReminderCoordinator(
        automationService = automationService,
        billDao = database.billDao(),
        ruleDao = database.recurringRuleDao(),
        planDao = database.installmentPlanDao(),
        ledgerDao = database.ledgerDao(),
        budgetRepository = budgetRepository,
        settingRepository = settingRepository,
        notifier = tallyNotifier,
        preferences = preferences,
    )

    val secretVault: SecretVault = SecretVault(context.applicationContext)

    val backupService: BackupService = BackupService(
        context = context.applicationContext,
        database = database,
        backupDao = database.backupDao(),
    )

    val webDavRepository: WebDavRepository = WebDavRepository(
        preferences = preferences,
        secrets = secretVault,
        client = WebDavClient(),
    )

    /** 设置页「检查更新」的唯一出口：用户点一次才打一次公开仓的 latest release。 */
    val updateChecker: UpdateChecker = UpdateChecker()

    // ---- 小莉：AI 是增强层，任何一条都要能在没有小莉的时候被跳过 ----

    val aiClient: AiClient = injectedAiClient ?: OpenAiCompatibleClient()

    val aiKeyVault: AiKeyVault = AiKeyVault(secretVault)

    val aiConfigRepository: AiConfigRepository = AiConfigRepository(
        dao = database.aiConfigDao(),
        keys = aiKeyVault,
        preferences = preferences,
        client = aiClient,
    )

    val aiGateway: AiGateway = AiGateway(client = aiClient, configs = aiConfigRepository)

    val keywordRuleRepository: KeywordRuleRepository = KeywordRuleRepository(
        dao = database.keywordRuleDao(),
        categoryDao = database.categoryDao(),
        preferences = preferences,
    )

    /** 记账页出栈后仍要能落一条沉淀规则，所以作用域在依赖图，不跟着页面销毁。 */
    val keywordSediment: KeywordSediment = KeywordSediment(keywordRuleRepository)

    val aiAnalysisService: AiAnalysisService = AiAnalysisService(
        reports = database.aiReportDao(),
        bills = billRepository,
        ledgers = database.ledgerDao(),
        budgets = budgetRepository,
        gateway = aiGateway,
        preferences = preferences,
    )

    val attachmentStore: AttachmentStore = AttachmentStore(
        context = context.applicationContext,
        dao = database.attachmentDao(),
        preferences = preferences,
    )

    /** 附件回收：挂在每日 AutomationWorker 上，保留期与回收站共用同一个设置。 */
    val attachmentCleanup: AttachmentCleanup = AttachmentCleanup(
        store = attachmentStore,
        dao = database.attachmentDao(),
    )

    /** 回收站：读的就是一直在写的 `deleted_at`，没有新表。 */
    val recycleBinRepository: RecycleBinRepository = RecycleBinRepository(
        billDao = database.billDao(),
        attachmentDao = database.attachmentDao(),
        store = attachmentStore,
        settings = settingRepository,
    )

    val aiConversationRepository: AiConversationRepository = AiConversationRepository(
        conversations = database.aiConversationDao(),
        messages = database.aiMessageDao(),
        attachments = database.aiMessageAttachmentDao(),
        gateway = aiGateway,
        preferences = preferences,
        thumbWriter = attachmentStore::writeChatThumb,
        // 问答要答得上来，所以带上本月本地汇总；与月度分析同一份数据最小化口径。
        localContext = {
            val ledger = ledgerRepository.defaultLedger()
            if (ledger == null) {
                ""
            } else {
                val startDay = runCatching { settingRepository.monthStartDay() }
                    .getOrDefault(Period.DEFAULT_START_DAY)
                val monthStart = Period.monthRange(System.currentTimeMillis(), startDay).startAt
                aiAnalysisService.preview(
                    StatsRequest(
                        ledgerId = ledger.id,
                        periodStart = monthStart,
                        granularity = StatsGranularity.MONTH,
                        startDay = startDay,
                    ),
                ).promptBlock(includeNotes = false)
            }
        },
    )

    // 语音识别引擎走系统 SpeechRecognizer，识别率优于此前自建的端侧 sherpa-onnx。
    // 自建那套（SherpaOnnxSpeechPort / SpeechModelManager / 下载弹框）已随 sherpa-onnx 依赖一并摘除，
    // 既省掉打进 APK 的 runtime .so，也省掉按需下载模型的门控；需要时从 git 历史恢复。
    val speechPort: SpeechPort = SystemSpeechPort(context.applicationContext)

    val screenshotTextReader: ScreenshotTextReader = MlKitTextReader(context.applicationContext)

    /**
     * 小莉对话页的抽账调用：文字、语音识别出的文字、截图（视觉模型直接看图，
     * 不支持图片时先 OCR 成文字）都走这一次工具调用，由模型回传分类 id 等固定参数。
     */
    val aiBillDraftService: AiBillDraftService = AiBillDraftService(
        gateway = aiGateway,
        textReader = screenshotTextReader,
    )

    /**
     * 系统分享进来的截图。用一条流把「Activity 收到 intent」与「导航到截图草稿」解耦，
     * Activity 不需要认识 NavController，冷启动与 singleTask 复用两条路径共用同一入口。
     */
    val sharedScreenshot: MutableStateFlow<Uri?> = MutableStateFlow(null)

    /**
     * 通知点进来的去处（T-04）。与 [sharedScreenshot] 同一套解耦：
     * Activity 只写这一个值，谁在导航图就绪后消费由导航层决定，冷启动与已在前台都能走。
     */
    val pendingLink: MutableStateFlow<NotificationLink?> = MutableStateFlow(null)

    /**
     * 从桌面图标打开、但进程还活着（页面停在某处，不是冷启动）。起点只在进程内定一次，
     * 这种「热启动」不会被重新走一遍，所以单独把这一下告诉导航层，由它决定要不要切到记账页。
     */
    val launcherOpened = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 应用启动 bootstrap 后写入的默认账本 id。首页据此初始化，避免「先查全部账本、
     * bootstrap 完再查默认账本」的第二次查询。未就绪时为 null，首页按原逻辑兜底。
     */
    @Volatile
    var cachedDefaultLedgerId: String? = null

    /** 默认账本名称，随 id 一起缓存；首页初始态拿它显示标题，避免开场先闪一下「全部账本」。 */
    @Volatile
    var cachedDefaultLedgerName: String? = null

    /**
     * prewarmHome 跑完顺手缓存首页首帧要用的「本月快照」与「这本账笔数」。
     * HomeViewModel 拿它做 state 的初始值：首帧直接画真实数据，不再先空一帧「正在汇总本月账单」。
     */
    @Volatile
    var cachedHomeSnapshot: io.github.guohangbk.tally.domain.model.BillMonthSnapshot? = null

    @Volatile
    var cachedHomeBillCount: Int = 0

    /**
     * 首页黑框下面那条预算进度也要在首帧就有：它晚到一步，整块内容会往下跳一行，
     * 看起来像黑框自己「展开」了一下。没有预算时缓存的是空 summary，那条本来就不渲染。
     */
    @Volatile
    var cachedHomeBudget: io.github.guohangbk.tally.domain.model.BudgetSummary? = null

    /**
     * prewarmCategories 跑完缓存的两份分类数据，都是页面首帧要用的：
     * 记账页的「常用分类」与分类管理页的整棵树。两处以前都是进页才现查，
     * 首帧先画个空壳（分类页就是那句「正在载入分类树…」），数据一到就得当场组合几十枚磁贴。
     */
    @Volatile
    var cachedCategories: Map<io.github.guohangbk.tally.domain.model.CategoryType, List<io.github.guohangbk.tally.data.db.entity.CategoryEntity>> = emptyMap()

    @Volatile
    var cachedCategoryTree = io.github.guohangbk.tally.domain.model.CategoryTreeSeed()

    /**
     * 备份恢复会整库替换，但 AppGraph 与已组合的页面还活着：旧账本 id、旧快照都失效了。
     * 恢复成功后递增版本，首页据此重新解析默认账本，避免停留在「全部账本 / 本月 0 笔」。
     */
    val dataGeneration = MutableStateFlow(0L)

    fun notifyDataReplaced() {
        cachedDefaultLedgerId = null
        cachedDefaultLedgerName = null
        cachedHomeSnapshot = null
        cachedHomeBillCount = 0
        cachedHomeBudget = null
        cachedCategories = emptyMap()
        cachedCategoryTree = io.github.guohangbk.tally.domain.model.CategoryTreeSeed()
        dataGeneration.value += 1
    }

    /**
     * 首屏预热：只把首页首帧真正要用的「当前月明细/汇总」和「抽屉标题的单账本笔数」各跑一遍，
     * 让 Room 查询计划与 WAL 页缓存进入热态，并把结果缓存供首页首帧播种。绝不预热
     * observeLedgerStats 那种整表 GROUP BY——账本切换弹层的每本笔数改成用户点开时按需加载。
     */
    suspend fun prewarmHome() {
        val ledger = cachedDefaultLedgerId
        val startDay = runCatching { settingRepository.monthStartDay() }.getOrDefault(Period.DEFAULT_START_DAY)
        val range = Period.monthRange(System.currentTimeMillis(), startDay)
        runCatching { billRepository.observeMonth(ledger, range).first() }
            .onSuccess { cachedHomeSnapshot = it }
        runCatching { billRepository.observeBillCount(ledger).first() }
            .onSuccess { cachedHomeBillCount = it }
        if (ledger != null) {
            runCatching {
                budgetRepository.observeSummary(
                    ledger,
                    io.github.guohangbk.tally.domain.model.BudgetPeriod.MONTHLY,
                    startDay,
                    range.startAt,
                ).first()
            }.onSuccess { cachedHomeBudget = it }
        }
    }

    /**
     * 分类树预热：记账页的「常用分类」和分类管理页的整棵树各跑一遍，缓存下来给两个页面首帧播种。
     *
     * 两处以前都是进页才现查——首帧只画一个空壳（分类页就是那句「正在载入分类树…」），
     * 数据一到就得当场组合几十枚磁贴，而那一整片组合正好落在转场动画的头几帧里。
     * 这里把查询挪到启动阶段（分类树是一张小表，单条 COUNT 就能取回），页面进来第一帧就有数据。
     */
    suspend fun prewarmCategories() {
        val expenseType = io.github.guohangbk.tally.domain.model.CategoryType.EXPENSE
        val incomeType = io.github.guohangbk.tally.domain.model.CategoryType.INCOME
        cachedCategories = mapOf(
            expenseType to runCatching { billRepository.activeCategories(expenseType) }.getOrDefault(emptyList()),
            incomeType to runCatching { billRepository.activeCategories(incomeType) }.getOrDefault(emptyList()),
        )
        cachedCategoryTree = io.github.guohangbk.tally.domain.model.CategoryTreeSeed(
            expense = runCatching { categoryRepository.tree(expenseType).first() }.getOrDefault(emptyList()),
            income = runCatching { categoryRepository.tree(incomeType).first() }.getOrDefault(emptyList()),
        )
    }

    private companion object {
        fun fileDatabase(context: Context): TallyDatabase {
            // Debug 构建先自检结构漂移：改实体但 version 仍是 1 时，旧库自动换结构并搬数据。
            // Release 不进这里——hash 对不上照旧抛异常，绝不清库。
            DevSchemaHeal.prepareIfNeeded(context)
            /**
             * 排锁探针：包住 Room 的两个 executor，只在「排队」或「执行」超过阈值时写一行。
             * 查的是"读被谁挡住"——事务单线程串行，一条长事务会让后面所有读写一起等，
             * 光看业务侧的取数耗时只能看到"都慢"，看不到是谁持着锁。
             * 这也是全应用唯一可信的读写耗时来源：业务侧那些 Flow 是失效驱动的，一条订阅会发很多次。
             */
            val probeMs = 200L
            fun probe(name: String, delegate: java.util.concurrent.Executor): java.util.concurrent.Executor =
                java.util.concurrent.Executor { task ->
                    val queued = System.currentTimeMillis()
                    delegate.execute {
                        val started = System.currentTimeMillis()
                        try {
                            task.run()
                        } finally {
                            val waited = started - queued
                            val ran = System.currentTimeMillis() - started
                            if (waited > probeMs || ran > probeMs) {
                                io.github.guohangbk.tally.data.debug.DebugLog.log(
                                    "Lock",
                                    "$name 排队${waited}ms 执行${ran}ms",
                                )
                            }
                        }
                    }
                }
            return Room.databaseBuilder(context, TallyDatabase::class.java, TallyDatabase.NAME)
                // 刻意不配 `fallbackToDestructiveMigration`：打开会抛异常，而不是静默清库。
                // version 一直是 1，改实体直接改 entity、不走迁移路径；Release 里 Room 打开仍会比对编译期
                // identity hash 与库里 `room_master_table` 的值，对不上就抛 IllegalStateException——
                // 正式设备的库要按备份包重建，覆盖安装救不了。
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // 读取并行：Room 不显式配置时，会把「查询」和「事务」都压在同一个单线程 IO executor 上。
                // 首页同时有本月 / 账本统计 / 分类树等多条 Flow，全在这一根线程上排队，一条慢就全慢。
                // 事务必须单线程（保证写一致），查询给一个固定线程池并行跑，冷启动首屏明显更快。
                .setTransactionExecutor(probe("事务", java.util.concurrent.Executors.newSingleThreadExecutor()))
                .setQueryExecutor(probe("查询", java.util.concurrent.Executors.newFixedThreadPool(4)))
                .build()
        }
    }
}


