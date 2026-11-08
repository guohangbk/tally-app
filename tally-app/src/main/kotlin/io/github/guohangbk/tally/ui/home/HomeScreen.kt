package io.github.guohangbk.tally.ui.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.ai.AssistantEntry
import io.github.guohangbk.tally.data.notify.NotificationLink
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.bill.BillDetailSheet
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillDayGroup
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.ui.common.BillBatchViewModel
import io.github.guohangbk.tally.ui.common.BillRow
import io.github.guohangbk.tally.ui.common.BillBatchOverlays
import io.github.guohangbk.tally.ui.common.LedgerPickerSheet
import io.github.guohangbk.tally.ui.common.SelectionBar
import io.github.guohangbk.tally.ui.common.TallyMonthPickerDialog
import io.github.guohangbk.tally.ui.common.formatDay
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.assistant.QuickNoteViewModel
import io.github.guohangbk.tally.ui.assistant.ScannedBill
import io.github.guohangbk.tally.ui.assistant.ScreenshotBatchViewModel
import io.github.guohangbk.tally.ui.assistant.ScreenshotConfirmSheet
import io.github.guohangbk.tally.ui.assistant.VoiceCapture
import io.github.guohangbk.tally.ui.assistant.VoiceMaxMillis
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.BudgetUsageRow
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.MoneySign
import io.github.guohangbk.tally.ui.design.MoneyText
import io.github.guohangbk.tally.ui.design.SkeletonBar
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.rememberSkeletonShown
import io.github.guohangbk.tally.ui.design.sheetBody
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.navigation.Routes
import io.github.guohangbk.tally.ui.shell.TallyDrawer
import io.github.guohangbk.tally.ui.shell.TallyFabClearance
import io.github.guohangbk.tally.ui.shell.TallyFanEvent
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyRecordFab
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import io.github.guohangbk.tally.ui.shell.tallyFabOverlayLift
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P02 账单页：月汇总 + 最近 7/15 日支出柱 + 按日分组账单流。
 * 唯一的常驻根页面，所以 ＋ 是本页的悬浮按钮，长按任一行进入批量选择态。
 */
@Composable
fun TallyHomeScreen(
    currentRoute: String? = "home",
    onOpenLedgers: () -> Unit,
    onOpenStats: (String?, Long) -> Unit = { _, _ -> },
    onOpenBill: (String?, String?, BillType?) -> Unit = { _, _, _ -> },
    onOpenDraftBill: (VoiceCapture) -> Unit = {},
    /** ＋ 右臂＝小莉：关闭去设置、开启未配置去配置页、可用才进对话。 */
    onOpenAssistant: (AssistantEntry) -> Unit = {},
    onOpenShotDraft: (ScannedBill) -> Unit = {},
    onNavigateRoute: (String) -> Unit = {},
    onOpenSearch: (String?) -> Unit = {},
    onOpenBillEdit: (String) -> Unit = {},
    onRepeatBill: (String?, String?, BillType?) -> Unit = { _, _, _ -> },
    onNavigateRoot: (String) -> Unit = {},
) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val viewModel = tallyViewModel { HomeViewModel(graph) }
    val guidance = tallyViewModel(key = "home-guidance") { HomeGuidanceViewModel(graph) }
    val batch = tallyViewModel(key = "home-batch") { BillBatchViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val guidanceState by guidance.state.collectAsStateWithLifecycle()
    // 账本切换弹层的每本笔数才需要整表聚合：点开弹层前是空列表，不占首屏。
    val ledgerStats by viewModel.ledgerStats.collectAsStateWithLifecycle()
    // 卡片下那条预算进度 + 速览弹层的数据源（总额 + 各分类）。
    val budgetOverview by viewModel.budgetSummary.collectAsStateWithLifecycle()
    val selection by batch.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    val snapshot = state.snapshot
    val summary = snapshot?.summary
    var showLedgerSheet by remember { mutableStateOf(false) }
    /** 点年月开的面板：跳远月不必一格一格按箭头。 */
    var monthPicker by remember { mutableStateOf(false) }
    var showBudgetSheet by remember { mutableStateOf(false) }
    var detailBillId by remember { mutableStateOf<String?>(null) }
    // 通知点达的另一半（T-04）：外壳已经把这一页切到前面，这里负责把对应那一笔的弹层打开并收掉链接。
    LaunchedEffect(Unit) {
        graph.pendingLink.collect { link ->
            if (link is NotificationLink.BillDetail) {
                detailBillId = link.billId
                graph.pendingLink.value = null
            }
        }
    }
    var excludedOnly by remember { mutableStateOf(false) }
    // 抽屉状态不进 savedState：rememberDrawerState 内部是 rememberSaveable，
    // 点抽屉里的入口时关闭动画还没跑完就被导航拆走，回到这一页抽屉"还开着"——
    // 六个入口因此有两种返回表现。它不该跨页面活着。
    val drawerState = remember { DrawerState(DrawerValue.Closed) }
    val scope = rememberCoroutineScope()

    // 系统返回（含右滑返回）该先撤当前这一层：勾选态、排除筛选、开着的抽屉，都不该直接把人甩出 App。
    // 注册顺序从低到高——后注册的先接住，所以勾选态排在最后。
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = excludedOnly) { excludedOnly = false }
    BackHandler(enabled = selection.active) { batch.exitSelection() }

    // 从二级页返回：把当初那个抽屉重新展开，返回的落点才跟进入时一致。
    LaunchedEffect(Unit) {
        if (viewModel.takeDrawerReopen()) drawerState.open()
    }

    // 回到前台先复核一次「今天」：零点后解锁点进来，首页应当已经在新的一段里，不等那一分钟的定时器。
    LifecycleResumeEffect(Unit) {
        viewModel.resume()
        onPauseOrDispose { }
    }

    val listState = rememberLazyListState()

    // 每回到一次首页报一次；浮不浮由 ViewModel 按「第几次」和一次性标记判断。
    LaunchedEffect(Unit) { guidance.onHomeShown() }

    // G-01 不自己走：这次没看到就算白教了，只有点掉、点「更多手势」或用掉 ＋ 才收起。
    // 一次性标记在浮出时就落下了，所以下次打开 App 不会再出现。

    // 首页 ＋ 上滑的语音链路：识别复用速记页那套管线，结果交给记账页，不在这里落库。
    val voice = tallyViewModel(key = "home-voice") { QuickNoteViewModel(graph) }
    var recording by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    // ＋ 在不在屏幕上，决定底部那几条浮层要不要抬起来让位。
    val fabShown = !selection.active && !analyzing
    var level by remember { mutableStateOf(0f) }
    var voiceStartedAt by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var voiceFailure by remember { mutableStateOf<VoiceFailure?>(null) }
    var micNotice by remember { mutableStateOf<String?>(null) }
    // 长按手势还没结束就弹系统授权框，窗口一失焦这次手势就废了、对话框也跟着不见
    // （新装上首次用语音就是这么“没反应”的）。先记下来，等松手后再申请。
    var pendingMicPermission by remember { mutableStateOf(false) }
    // 授权请求不在 pointer-up 里同步发起：等这一帧收完再弹，免得被手势/浮层收尾吞掉。
    var micAskToken by remember { mutableIntStateOf(0) }
    var awaitingMicResult by remember { mutableStateOf(false) }
    var micSilentBlocked by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingMicResult = false
        val silent = micSilentBlocked
        micSilentBlocked = false
        val activity = context as? Activity
        val canAskAgain = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        DebugLog.log("Mic", "权限申请结果 granted=$granted · shouldShowRationale=$canAskAgain · 静默拦截=$silent")
        if (granted) {
            // 看门狗可能已经先把「系统没弹框」的交代摆出来了；授权真的到了就收回去。
            if (silent) voiceFailure = null
            micNotice = "麦克风已授权 · 再按住 ＋ 上滑就能说话"
        } else {
            voiceFailure = VoiceFailure(
                reason = if (canAskAgain) {
                    "麦克风权限没有给，语音记账用不了。允许后就能继续说，或者直接手动记一笔。"
                } else {
                    "系统的「录音」权限已被拒绝，系统不会再弹授权框。去应用设置把「录音」打开，再回来按住 ＋ 上滑。"
                },
                openSettings = true,
                appPermission = true,
            )
        }
    }
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // 1.8 秒既没回调、页面还聚焦，说明系统根本没弹授权框（MIUI 把权限设成「拒绝询问」时就是这样），
    // 不能让人干等着：直接给出去应用设置的路。授权框开着时本页会失焦，不会误判。
    LaunchedEffect(micAskToken) {
        if (micAskToken == 0) return@LaunchedEffect
        withFrameNanos { }
        awaitingMicResult = true
        DebugLog.log("Mic", "申请 RECORD_AUDIO（当前未授权）")
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
        delay(1_800)
        if (awaitingMicResult && windowInfo.isWindowFocused) {
            awaitingMicResult = false
            micSilentBlocked = true
            DebugLog.log("Mic", "授权框没有出现，也没有回调：按系统已拒绝处理")
            voiceFailure = VoiceFailure(
                reason = "系统没有弹出麦克风授权框，这台手机大概把「录音」设成了拒绝询问。去应用设置里打开「录音」，再回来按住 ＋ 上滑。",
                openSettings = true,
                appPermission = true,
            )
        }
    }

    // 本机引擎起不来时（小米这类 ROM 回 error=9），改走系统语音输入界面：
    // 麦克风由系统应用持有，不受「语音服务没拿到麦克风」这条限制。
    val systemVoice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        DebugLog.log("Mic", "系统语音输入返回：${if (heard.isBlank()) "空" else "「$heard」"}")
        analyzing = false
        if (heard.isBlank()) {
            voiceFailure = VoiceFailure("没有听清，请重试或改用文字输入")
        } else {
            voice.acceptHeard(heard) { capture ->
                if (capture.failure != null) {
                    voiceFailure = VoiceFailure(capture.failure, openSettings = graph.speechPort.hasPermission())
                } else {
                    onOpenDraftBill(capture)
                }
            }
        }
    }

    fun requestMicPermission() {
        pendingMicPermission = false
        micAskToken += 1
    }

    /** 权限被系统禁掉时只能去应用自己的设置页：那里才有「录音」这一项。 */
    fun openAppPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        DebugLog.log("Mic", "打开应用权限设置")
        systemSettings.launch(intent)
    }

    /** 系统语音设置页：各家 ROM 入口不一，按能解析到的第一个走。 */
    fun openVoiceSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
        val intent = candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
        if (intent == null) {
            voiceFailure = VoiceFailure("这台机器上没有可打开的语音设置页，请在系统设置里搜「语音」")
        } else {
            DebugLog.log("Mic", "打开系统语音设置：${intent.action}")
            systemSettings.launch(intent)
        }
    }

    fun launchSystemVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说一句记账内容")
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            DebugLog.log("Mic", "系统语音输入界面不存在")
            voiceFailure = VoiceFailure("这台机器没有可用的系统语音输入，直接手动记一笔吧")
        } else {
            DebugLog.log("Mic", "转系统语音输入界面")
            systemVoice.launch(intent)
        }
    }

    fun startVoice(deferPermission: Boolean = true) {
        if (recording || analyzing) return
        if (!graph.speechPort.hasPermission()) {
            // 抽屉里点进来是普通点击，可以直接弹；长按链路里要等松手（见 VoiceFinish）。
            if (deferPermission) pendingMicPermission = true else requestMicPermission()
            return
        }
        recording = true
        voiceStartedAt = System.currentTimeMillis()
        elapsedMs = 0L
        level = 0f
        voice.beginCapture(onLevel = { level = it }, onResult = { capture ->
            recording = false
            analyzing = false
            level = 0f
            when {
                capture.cancelled -> Unit
                capture.needsSystemInput -> launchSystemVoice()
                capture.failure != null -> voiceFailure =
                    // 应用已经有录音权限却还是录不了，卡的多半是引擎自己：给条去设置的路。
                    VoiceFailure(capture.failure, openSettings = graph.speechPort.hasPermission())
                capture.heard.isBlank() -> voiceFailure = VoiceFailure("没有听清，请重试或改用文字输入")
                else -> {
                    onOpenDraftBill(capture)
                }
            }
        })
    }

    fun stopVoice() {
        if (!recording) {
            // 没权限时上面根本没开始录：松手这一刻手势已经结束，授权框现在弹才留得住。
            if (pendingMicPermission) requestMicPermission()
            return
        }
        recording = false
        analyzing = true
        voice.endCapture()
    }

    // 计时按起始时刻换算，不是一帧加 200ms（那样会漂），而且录音和识别两段都要继续走：
    // 那根录音条只在松手之后出现，若 key 只跟 recording，协程会在松手这一刻被取消、数字就此冻住。
    // 20s 上限只约束录音那一段：到点自己收尾进识别，不静默截断。
    LaunchedEffect(recording, analyzing) {
        if (!recording && !analyzing) return@LaunchedEffect
        while (recording || analyzing) {
            elapsedMs = System.currentTimeMillis() - voiceStartedAt
            if (recording && elapsedMs >= VoiceMaxMillis) stopVoice()
            delay(200)
        }
    }

    // 首页 ＋ 左滑的截图链路：一次最多 9 张，逐张本机识别后汇成待确认列表。
    val shots = tallyViewModel(key = "home-shot") { ScreenshotBatchViewModel(graph) }
    val shotState by shots.uiState.collectAsStateWithLifecycle()
    var showShotSheet by remember { mutableStateOf(false) }
    var shotNotice by remember { mutableStateOf<String?>(null) }
    val shotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ScreenshotBatchViewModel.MAX_IMAGES),
    ) { uris ->
        if (uris.isNotEmpty()) shots.scan(uris)
    }

    LaunchedEffect(shotState.done, shotState.bills.size) {
        if (!shotState.done) return@LaunchedEffect
        val single = shotState.bills.singleOrNull()
        if (single != null && shotState.failures.isEmpty()) {
            onOpenShotDraft(single)
            shots.clear()
        } else {
            showShotSheet = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TallyDrawer(
                ledgerName = state.ledgerName,
                ledgerIconKey = state.ledgerIconKey,
                ledgerBillCount = state.ledgerBillCount,
                categoryBadge = state.categoryBadge,
                ledgerCount = state.ledgers.size,
                onDismiss = { scope.launch { drawerState.close() } },
                onNavigate = { route ->
                    // 抽屉里只剩二级页：记账类入口走 ＋ 那条链路，不再从抽屉发路由。
                    viewModel.leaveForDrawerPage()
                    onNavigateRoute(route)
                    // 抽屉不跟着转场一起收：它一动，作为源页的首页每帧都要重录一遍显示列表，
                    // 和新页的推入抢同一帧——看着就是「抽屉先没了，页面才闪出来」。
                    // 等新页盖上来再无声收掉，那一下已经看不见了。
                    scope.launch {
                        delay(DRAWER_CLOSE_AFTER_PUSH_MS)
                        drawerState.snapTo(DrawerValue.Closed)
                    }
                },
            )
        },
    ) {

    Box(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selection.active) {
                SelectionTopBar(
                    count = selection.selected.size,
                    onExit = batch::exitSelection,
                    onSelectDay = {
                        val first = selection.selected.firstOrNull()
                        val day = first?.bill?.occurredAt ?: return@SelectionTopBar
                        batch.selectAll(snapshot?.days?.firstOrNull { it.dayStart == io.github.guohangbk.tally.ui.common.startOfDay(day) }?.bills.orEmpty())
                    },
                    onSelectMonth = { batch.selectAll(snapshot?.days?.flatMap { it.bills }.orEmpty()) },
                )
            } else {
                TallyTopBar(
                    title = state.ledgerName,
                    onTitleClick = { viewModel.loadLedgerStats(); showLedgerSheet = true },
                    leading = { TallyIconAction(iconKey = "menu", contentDescription = "菜单", onClick = { scope.launch { drawerState.open() } }) },
                    trailing = {
                        TallyIconAction(iconKey = "chart", contentDescription = "统计", onClick = { onOpenStats(state.ledgerId, state.monthStart) })
                        TallyIconAction(iconKey = "search", contentDescription = "搜索账单", onClick = { onOpenSearch(state.ledgerId) })
                    },
                )
            }

            // 批量操作的提示走贴底气泡；撤销也挂在这条气泡上（以前是列表底下一条常驻横栏，
            // 那条会把整块底部刷白、压住好几行明细）。
            TallyToastEffects(
                notice = selection.notice,
                error = null,
                onNoticeShown = batch::dismissNotice,
            )

            MonthSwitcher(
                label = Period.label(state.monthRange, state.monthStartDay),
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
                onPickMonth = { monthPicker = true },
            )

            SummaryBar(
                expenseMinor = summary?.expenseMinor ?: 0L,
                incomeMinor = summary?.incomeMinor ?: 0L,
                billCount = (summary?.billCount ?: 0) - (summary?.reimbursedCount ?: 0),
                balanceMinor = (summary?.balanceMinor ?: 0L),
                dailyAverageMinor = if (summary == null) 0L else summary.expenseMinor / (state.daysElapsed.coerceAtLeast(1)),
                grouping = state.settings.showThousandsSeparator,
                showCurrency = state.settings.showCurrencySymbol,
                loading = summary == null,
                bar = homeBudgetBar(budgetOverview),
                onOpenBudget = { showBudgetSheet = true },
            )

            // 首帧只组合屏幕内可见的几天：非懒加载的 Column 会把整月几十笔一次性
            // 组合完（每行带图标/徒章/自定义字体），这就是之前主线程被占住 ~900ms 的真因。
            val visibleDays = remember(snapshot, excludedOnly) {
                snapshot?.days
                    ?.map { it.copy(bills = if (excludedOnly) it.bills.filter { b -> b.bill.excludedFromStats } else it.bills) }
                    ?.filter { it.bills.isNotEmpty() }
                    .orEmpty()
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 15.dp,
                    end = 15.dp,
                    // ＋ 居中之后压的是列表中间而不是右边的金额列，留白得按它的高度给，不能只看底栏开没开。
                    bottom = if (fabShown) TallyFabClearance else 20.dp,
                ),
            ) {
                if (summary != null && summary.excludedCount > 0) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        ExcludedFootnote(
                            count = summary.excludedCount,
                            amountMinor = summary.excludedMinor,
                            grouping = state.settings.showThousandsSeparator,
                            expanded = excludedOnly,
                            onToggle = { excludedOnly = !excludedOnly },
                        )
                    }
                }

                if (snapshot == null) {
                    item {
                        Text("正在汇总本月账单…", style = TallyType.helper, color = colors.ink3, modifier = Modifier.padding(top = 18.dp))
                    }
                } else if (snapshot.summary.billCount == 0) {
                    item {
                        TallyEmptyState(
                            title = "本月还没有账单",
                            description = "记一笔吧，30 秒就能完成。",
                            iconKey = "note",
                            action = {
                                TallyButton(
                                    label = "记第一笔",
                                    onClick = { onOpenBill(null, null, null) },
                                    variant = ButtonVariant.BRAND,
                                    leadingIcon = "plus",
                                    modifier = Modifier.width(180.dp),
                                )
                            },
                        )
                    }
                } else {
                    item { Spacer(Modifier.height(6.dp)) }
                    items(visibleDays, key = { it.dayStart }) { day ->
                        DaySection(
                            day = day,
                            grouping = state.settings.showThousandsSeparator,
                            selection = selection,
                            onOpenBillDetail = { id -> detailBillId = id },
                            onBeginSelect = batch::begin,
                            onToggle = batch::toggle,
                        )
                    }
                    if (excludedOnly) {
                        item {
                            Spacer(Modifier.height(10.dp))
                            Text("只显示被排除的账单", style = TallyType.helper, color = colors.ink3)
                        }
                    }
                }
            }

            selection.progress?.let { progress ->
                io.github.guohangbk.tally.ui.common.BatchProgress(progress, modifier = Modifier.padding(horizontal = 15.dp, vertical = 6.dp))
            }

            // 动作条从底下推上来、退回去时再滑下去：进/退选择态是同一对动作，得有方向感，
            // 不能凭空出现又凭空消失。
            AnimatedVisibility(
                visible = selection.active,
                enter = slideInVertically(tween(200, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(150)),
                exit = slideOutVertically(tween(170, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(120)),
            ) {
                SelectionBar(
                    count = selection.selected.size,
                    amountMinor = selection.amountMinor,
                    hint = if (selection.excludedCount > 0) "含 ${selection.excludedCount} 笔已排除" else null,
                    categoryBlockedReason = selection.categoryBlockedReason,
                    categoryEnabled = selection.categoryBlockedReason == null,
                    moveEnabled = selection.canMove,
                    onSelectCategory = batch::openCategoryPicker,
                    onReimburse = { batch.reimburse(selection.selected.none { it.bill.reimbursed == ReimburseStatus.PENDING.name }) },
                    onExclude = { batch.exclude(selection.selected.none { it.bill.excludedFromStats }) },
                    onDelete = batch::delete,
                    onDate = batch::openDatePicker,
                    onNote = batch::openNoteEditor,
                    onMove = batch::openLedgerPicker,
                )
            }

            // 底部导航撤除：那条 bar 只有「账单 / 小莉」两格，小莉搬进 ＋ 的右臂之后它就没有第二个目的地了。
            // 开关关着时本来也是不渲染的，所以现在的形态等于此前「小莉关闭」那一版，不是新布局。
        }

        // 录音期间必须保留 FAB：这次「按住→上滑→松手」手势由 FAB 自己的 pointerInput 持有，
        // 一旦因 recording=true 把 FAB 移出组合，协程被取消，松手（VoiceFinish）永远收不到。
        // 只在 analyzing（已松手、识别中）时收起，改由下面的 VoiceRecordBar 接管。
        val coach = guidanceState.coach?.takeIf { fabShown }

        coach?.let {
            // 蒙层只做两件事：把底下的页面压暗、把空白处的点击吃掉。
            // 不接管关闭——误触空白不该丢引导；关闭只认气泡右上角的 ✕、更多手势、以及真的去用 ＋。
            // 画在 FAB 之前，所以 ＋ 和它外面的引导波纹都浮在蒙层上面，看着就是「点亮这一个按钮」。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (colors.isDark) Color.Black.copy(alpha = 0.58f) else colors.ink.copy(alpha = 0.36f),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    // 它只是个吃点击的挡板，不该被读屏念成一个没有名字的按钮。
                    .clearAndSetSemantics {},
            )
        }

        if (fabShown) {
            TallyRecordFab(
                // 底部 bar 撤了，＋ 不再为它抬高一档。
                raised = false,
                onClick = {
                    guidance.dismissCoach()
                    onOpenBill(null, null, null)
                },
                onFanEvent = { event ->
                    // 用上 ＋ 就说明手势已经学会；右滑更是直接把人送走，气泡不留到下一页。
                    guidance.dismissCoach()
                    when (event) {
                        TallyFanEvent.VoiceStart -> startVoice()
                        TallyFanEvent.VoiceFinish -> stopVoice()
                        TallyFanEvent.VoiceCancel -> {
                            voice.abandonCapture()
                            // 滑回原位置也算手势结束，欠着的授权框该弹还是得弹。
                            if (pendingMicPermission) requestMicPermission()
                        }
                        TallyFanEvent.Screenshot -> shotPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                        // 右臂＝小莉：底部导航那一格搬到这里，入口从此只有一个。
                        TallyFanEvent.Xiaoli -> onOpenAssistant(guidanceState.assistantEntry)
                    }
                },
                xiaoliEntry = guidanceState.assistantEntry,
                guide = coach != null,
            )
        }

        // 卡放在 ＋ 之后画：波纹要往 ＋ 上方散开，不能被卡面整片盖住；尾巴正好压在波纹上。
        coach?.let {
            // 卡底边离 ＋ 顶边（69dp）留出约 23dp，尾巴不至于贴到按钮上。
            HomeCoachBubble(
                coach = it,
                assistantEntry = guidanceState.assistantEntry,
                onDismiss = guidance::dismissCoach,
                onMoreGestures = guidance::openMoreGestures,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 98.dp),
            )
        }

        if (analyzing) {
            VoiceRecordBar(
                elapsedMs = elapsedMs,
                level = level,
                analyzing = analyzing,
                onCancel = {
                    recording = false
                    analyzing = false
                    voice.abandonCapture()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = tallyFabOverlayLift(false)),
            )
        }

        if (shotState.busy) {
            HomeStatusCard(
                title = shotState.stage ?: "正在本机识别…",
                detail = "${shotState.imageDone} / ${shotState.imageTotal} 张 · 图片不离开设备",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = tallyFabOverlayLift(false)),
            )
        }

        shotNotice?.let { text ->
            LaunchedEffect(text) {
                delay(2_400)
                shotNotice = null
            }
            HomeStatusCard(
                title = text,
                detail = null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = tallyFabOverlayLift(false)),
            )
        }

        // 刚授完权，这一次长按已经接不上了：把下一步说在页面上，而不是让用户再猜。
        micNotice?.let { text ->
            LaunchedEffect(text) {
                delay(4_000)
                micNotice = null
            }
            HomeStatusCard(
                title = text,
                detail = null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = tallyFabOverlayLift(false)),
            )
        }
    }

    }

    if (showShotSheet) {
        ScreenshotConfirmSheet(
            state = shotState,
            onToggle = shots::toggle,
            onEdit = { key -> shots.takeForEdit(key)?.let(onOpenShotDraft) },
            onSave = { shots.saveChecked { count -> shotNotice = "已存入 $count 笔" } },
            onDismiss = {
                showShotSheet = false
                shots.clear()
            },
        )
    }

    voiceFailure?.let { failure ->
        VoiceFailureSheet(
            reason = failure.reason,
            openSettings = failure.openSettings,
            settingsLabel = if (failure.appPermission) "去应用设置打开录音" else "去系统设置给语音引擎授权",
            onOpenSettings = {
                voiceFailure = null
                if (failure.appPermission) openAppPermissionSettings() else openVoiceSettings()
            },
            onRetry = {
                voiceFailure = null
                startVoice(deferPermission = false)
            },
            onManual = {
                voiceFailure = null
                onOpenBill(null, null, null)
            },
            onDismiss = { voiceFailure = null },
        )
    }

    detailBillId?.let { id ->
        BillDetailSheet(
            billId = id,
            onDismiss = { detailBillId = null },
            onEdit = { editId ->
                detailBillId = null
                onOpenBillEdit(editId)
            },
            onRepeat = { l1, l2, type ->
                detailBillId = null
                onRepeatBill(l1, l2, type)
            },
            onOpenBill = { detailBillId = it },
        )
    }

    if (monthPicker) {
        TallyMonthPickerDialog(
            currentMonthStart = state.monthStart,
            onDismiss = { monthPicker = false },
            onPick = { start ->
                viewModel.selectMonth(start)
                monthPicker = false
            },
        )
    }

    if (showLedgerSheet) {
        LedgerPickerSheet(
            ledgers = ledgerStats,
            selectedId = state.ledgerId,
            onSelect = { id -> viewModel.selectLedger(id.ifEmpty { null }) },
            onDismiss = { showLedgerSheet = false },
            includeAll = true,
        )
    }

    // 点首页那条进度弹出的预算速览：总额 + 各分类逐条，点任意一行进预算管理页（BU21-11）。
    if (showBudgetSheet) {
        budgetOverview?.let { overview ->
            BudgetOverviewSheet(
                summary = overview,
                grouping = state.settings.showThousandsSeparator,
                onOpen = { showBudgetSheet = false; onNavigateRoute(Routes.BUDGET) },
                onDismiss = { showBudgetSheet = false },
            )
        }
    }

    // 四件弹层与搜索页同一份实现，见 BillBatchOverlays。
    BillBatchOverlays(selection, batch)

    if (guidanceState.sheetOpen) {
        HomeMoreGesturesSheet(
            assistantEntry = guidanceState.assistantEntry,
            onOpenAssistant = onOpenAssistant,
            onDismiss = guidance::closeMoreGestures,
        )
    }
}

/** 选择态顶栏：动作条取代主导航，计数常驻。 */
@Composable
private fun SelectionTopBar(
    count: Int,
    onExit: () -> Unit,
    onSelectDay: () -> Unit,
    onSelectMonth: () -> Unit,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.ink.copy(alpha = 0.05f))
                .clickable(onClick = onExit),
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(iconKey = "close", contentDescription = "退出选择", size = 14.dp, tint = colors.ink)
        }
        Text("已选 $count 笔", style = TallyType.screenTitle, color = colors.ink, modifier = Modifier.weight(1f))
        TallyTag(text = "全选当日", tone = TagTone.OUTLINE, modifier = Modifier.clickable(onClick = onSelectDay))
        TallyTag(text = "全选本月", tone = TagTone.OUTLINE, modifier = Modifier.clickable(onClick = onSelectMonth))
    }
}

/** 汇总口径必须能自证：被排除的笔数与金额常驻在汇总条下方（F6、AC-F6-1）。 */
@Composable
private fun ExcludedFootnote(
    count: Int,
    amountMinor: Long,
    grouping: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = tallyColors
    TallyCard(corner = 15, contentPadding = 10.dp, onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            InlineIcon(iconKey = "eyeoff", contentDescription = null, size = 12.dp, tint = colors.slate)
            Text(
                text = "另有 $count 笔不计入收支 ${formatAmount(amountMinor, grouping)}",
                fontSize = 11.sp, lineHeight = 13.8.sp,
                color = colors.ink2,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "查看全部 ›" else "查看 ›", fontSize = 10.sp, lineHeight = 12.5.sp, color = colors.ink3, maxLines = 1)
        }
    }
}

/**
 * 月份步进：与统计页同一套 .tbar 语言（衬线月份 + 两侧箭头）。
 * 年月本身也是按钮——箭头一次只走一格，跳去半年前不该按十几下，点年月直接开年月选择器。
 */
@Composable
private fun MonthSwitcher(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickMonth: () -> Unit,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MonthStep(direction = -1, onClick = onPrevious)
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .clickable(onClick = onPickMonth)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TallyType.listTitle.copy(lineHeight = 18.8.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            InlineIcon(iconKey = "chevron_down", contentDescription = "选择年月", size = 11.dp, tint = colors.ink3)
        }
        MonthStep(direction = 1, onClick = onNext)
    }
}

@Composable
private fun MonthStep(direction: Int, onClick: () -> Unit) {
    val colors = tallyColors
    // 热区给到 44dp，但不再画方框：带边框的图标按钮是顶栏那套语言，
    // 月份这一行再排两颗方框就跟右上角的菜单按钮打架了。
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(
            iconKey = "chevron",
            contentDescription = if (direction < 0) "上一月" else "下一月",
            size = 19.dp,
            tint = colors.ink2,
            modifier = if (direction < 0) Modifier.rotate(180f) else Modifier,
        )
    }
}

@Composable
private fun DaySection(
    day: BillDayGroup,
    grouping: Boolean,
    selection: io.github.guohangbk.tally.ui.common.BatchState,
    onOpenBillDetail: (String) -> Unit,
    onBeginSelect: (BillListItem) -> Unit,
    onToggle: (BillListItem) -> Unit,
) {
    val colors = tallyColors
    val selectedInDay = selection.selected.count { item -> day.bills.any { it.bill.id == item.bill.id } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = formatDay(day.dayStart),
            fontSize = 12.sp, lineHeight = 15.sp,
            fontWeight = FontWeight.Bold,
            color = colors.ink2,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.ink.copy(alpha = 0.055f)))
        if (selection.active) {
            Text(
                text = "已选 $selectedInDay / ${day.bills.size}",
                fontSize = 11.sp, lineHeight = 13.8.sp,
                color = colors.brand,
                fontWeight = FontWeight.SemiBold,
            )
        }
        val net = day.incomeMinor - day.expenseMinor
        Text(
            text = (if (net < 0) "－" else "+") + formatAmount(abs(net), grouping),
            style = TallyType.moneyList.copy(fontSize = 11.5.sp, lineHeight = 14.4.sp),
            color = colors.ink3,
        )
    }
    day.bills.forEachIndexed { index, item ->
        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.045f)))
        val isSelected = selection.selected.any { it.bill.id == item.bill.id }
        BillRow(
            item = item,
            grouping = grouping,
            showCheckbox = selection.active,
            selected = isSelected,
            onClick = {
                if (selection.active) {
                    onToggle(item)
                } else {
                    onOpenBillDetail(item.bill.id)
                }
            },
            onLongClick = { onBeginSelect(item) },
        )
    }
}

/**
 * 设计板 .sum：墨底卡片、右上金晕、左下纸光，两格等宽、左对齐、1px 竖线分栏；预算态挂在卡片正下方。
 *
 * 数据没到时按最终版式占位：标签与卡片先画，金额位置留骨架，不拿 ¥0.00 冒充结果；
 * 数字到了再淡入，黑框的尺寸与位置从头到尾不变。
 */
@Composable
private fun SummaryBar(
    expenseMinor: Long,
    incomeMinor: Long,
    billCount: Int,
    balanceMinor: Long,
    dailyAverageMinor: Long,
    grouping: Boolean,
    showCurrency: Boolean,
    loading: Boolean,
    bar: HomeBudgetBar? = null,
    onOpenBudget: () -> Unit = {},
) {
    val colors = tallyColors
    // 数字与灰条都硬切换：不做淡入淡出，只保留「超过 150ms 才出现骨架」。
    val skeletonAlpha = if (rememberSkeletonShown(loading)) 1f else 0f
    val contentAlpha = if (loading) 0f else 1f
    val skeletonColor = colors.onSummaryBar.copy(alpha = 0.16f)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.summaryBar)
                .drawBehind {
                    // .sum::before 的两团椭圆光晕：右上金、左下纸
                    scale(1f, 0.48f, Offset(size.width * 0.88f, 0f)) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(colors.goldHi.copy(alpha = 0.34f), Color.Transparent),
                                center = Offset(size.width * 0.88f, 0f),
                                radius = size.width * 0.66f,
                            ),
                            radius = size.width * 0.66f,
                            center = Offset(size.width * 0.88f, 0f),
                        )
                    }
                    scale(1f, 0.48f, Offset(size.width * 0.04f, size.height)) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(colors.onSummaryBar.copy(alpha = 0.10f), Color.Transparent),
                                center = Offset(size.width * 0.04f, size.height),
                                radius = size.width * 0.56f,
                            ),
                            radius = size.width * 0.56f,
                            center = Offset(size.width * 0.04f, size.height),
                        )
                    }
                }
                .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 15.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top,
        ) {
            SummaryCell(
                label = "本月支出",
                amountMinor = expenseMinor,
                note = summaryExpenseNote(billCount, dailyAverageMinor, grouping),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                grouping = grouping,
                showCurrency = showCurrency,
                contentAlpha = contentAlpha,
                skeletonAlpha = skeletonAlpha,
                skeletonColor = skeletonColor,
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.onSummaryBar.copy(alpha = 0.18f)),
            )
            SummaryCell(
                label = "本月收入",
                amountMinor = incomeMinor,
                note = "结余 " + (if (balanceMinor < 0) "－" else "+") + formatAmount(abs(balanceMinor), grouping),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                grouping = grouping,
                showCurrency = showCurrency,
                contentAlpha = contentAlpha,
                skeletonAlpha = skeletonAlpha,
                skeletonColor = skeletonColor,
            )
        }
        // BU21-01 / 02：预算不往支出格里堆字，也不在账单明细上方起横栏——它是黑框卡片下紧贴的一条进度。
        if (bar != null) BudgetBar(bar = bar, onOpenBudget = onOpenBudget)
    }
}

/** 未设预算时支出格的那一行说明：笔数与日均。 */
private fun summaryExpenseNote(billCount: Int, dailyAverageMinor: Long, grouping: Boolean): String =
    "$billCount 笔 · 日均 ${formatAmount(dailyAverageMinor, grouping)}"

@Composable
private fun SummaryCell(
    label: String,
    amountMinor: Long,
    note: String,
    modifier: Modifier = Modifier,
    grouping: Boolean,
    showCurrency: Boolean,
    contentAlpha: Float,
    skeletonAlpha: Float,
    skeletonColor: Color,
) {
    val colors = tallyColors
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TallyType.eyebrow.copy(
                fontSize = 11.5.sp, lineHeight = 14.4.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.16.em,
            ),
            color = colors.onSummaryBar.copy(alpha = 0.6f),
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        // 金额始终按最终版式排版，只做透明度：数字与骨架换着画也不会改变黑框高度。
        Box {
            MoneyText(
                amountMinor = amountMinor,
                sign = MoneySign.NONE,
                style = TallyType.moneyBody.copy(fontSize = 23.sp, lineHeight = 25.sp),
                color = colors.onSummaryBar,
                grouping = grouping,
                showCurrency = showCurrency,
                modifier = Modifier.alpha(contentAlpha),
            )
            if (skeletonAlpha > 0f) {
                SkeletonBar(
                    width = 88.dp,
                    height = 22.dp,
                    color = skeletonColor,
                    modifier = Modifier.align(Alignment.CenterStart).alpha(skeletonAlpha),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Box {
            Text(
                note,
                fontSize = 11.5.sp, lineHeight = 14.4.sp,
                color = colors.onSummaryBar.copy(alpha = 0.55f),
                maxLines = 1,
                modifier = Modifier.alpha(contentAlpha),
            )
            if (skeletonAlpha > 0f) {
                SkeletonBar(
                    width = 70.dp,
                    height = 11.dp,
                    color = skeletonColor,
                    modifier = Modifier.align(Alignment.CenterStart).alpha(skeletonAlpha),
                )
            }
        }
    }
}

/**
 * BU21-01 / 02：预算进度条落在黑框卡片「正下方、紧贴着」，右侧只一句「…已用 x%」。
 * 有总额读总额、只有分类时读聚合（BU21-10）；颜色按档位：超支陶土、达阈值 80% 金晕、其余墨绿。
 */
@Composable
private fun BudgetBar(bar: HomeBudgetBar, onOpenBudget: () -> Unit) {
    val colors = tallyColors
    val ratio = bar.ratio
    val percent = (ratio * 100).toInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onOpenBudget)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.ink.copy(alpha = 0.10f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            bar.isOver -> colors.expense
                            ratio >= 0.8f -> colors.goldHi
                            else -> colors.brand
                        },
                    ),
            )
        }
        Text(
            text = "${bar.label} $percent%",
            fontSize = 10.sp, lineHeight = 12.5.sp,
            color = if (bar.isOver) colors.expense else colors.ink3,
            maxLines = 1,
        )
    }
}

/** 首页那条预算进度的渲染数据：总额那条或分类聚合那一条都用它（label 区分「预算已用 / 分类预算 已用」）。 */
private data class HomeBudgetBar(val ratio: Float, val isOver: Boolean, val label: String)

/** 有总额读总额；只有分类时读「全部分类预算合计」（BU21-10）。都没有→null，整条不渲染。
 * 合计不排「提醒已关闭」的那些：开关只管提醒，进度照算——与速览弹层、预算页同一个口径。 */
private fun homeBudgetBar(summary: io.github.guohangbk.tally.domain.model.BudgetSummary?): HomeBudgetBar? {
    if (summary == null) return null
    summary.total?.let { return HomeBudgetBar(it.ratio, it.isOver, "预算已用") }
    val activeBudgets = summary.categories
    if (activeBudgets.isEmpty()) return null
    val amount = activeBudgets.sumOf { it.item.amountMinor }
    val used = activeBudgets.sumOf { it.usedMinor }
    if (amount <= 0L) return null
    return HomeBudgetBar(used.toFloat() / amount, used > amount, "分类预算 已用")
}

/** 点首页那条进度弹出的速览：总额一段 + 各分类逐条；无底部按钮、无引导文案，点任意一行进预算管理页（BU21-11）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetOverviewSheet(
    summary: io.github.guohangbk.tally.domain.model.BudgetSummary,
    grouping: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().sheetBody().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("预算速览 · 本月", style = TallyType.sectionTitle, color = colors.ink)
            summary.total?.let {
                BudgetUsageRow(usage = it, title = "总额", iconKey = "coin", grouping = grouping, onClick = onOpen)
            }
            val cats = summary.categories
            if (cats.isNotEmpty()) {
                Text(
                    "分类预算(${cats.size}条)",
                    fontSize = 10.5.sp, lineHeight = 13.1.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink2,
                    modifier = Modifier.padding(top = if (summary.total != null) 4.dp else 0.dp),
                )
                cats.forEach {
                    BudgetUsageRow(
                        usage = it,
                        title = it.item.categoryName ?: "该分类",
                        iconKey = it.item.categoryIconKey ?: "tag",
                        grouping = grouping,
                        onClick = onOpen,
                    )
                }
            }
        }
    }
}

/** .recbar：录音条。首页在其后仍可见，识别在本机，松手结束并识别。 */
@Composable
private fun VoiceRecordBar(
    elapsedMs: Long,
    level: Float,
    analyzing: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(15.dp)
    val seconds = (elapsedMs / 1000L).toInt()
    val maxSeconds = (VoiceMaxMillis / 1000L).toInt()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoiceWaveform(level = if (analyzing) 0f else level)
            Text(
                text = if (analyzing) "正在识别…" else "正在录音",
                fontSize = 13.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = TallyType.screenTitle.fontFamily,
                color = colors.ink,
                maxLines = 1,
            )
            Text(
                // 录音段给「已用/上限」这一对；识别段的数字走的是总耗时，
                // 再挂着「最长 20 秒」会让人以为超时了，所以那里只报秒数。
                text = if (analyzing) "${seconds}秒" else "${seconds}秒 / 最长 ${maxSeconds}秒",
                fontSize = 10.5.sp, lineHeight = 13.1.sp,
                color = colors.ink3,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                InlineIcon(iconKey = "close", contentDescription = "取消录音", size = 14.dp, tint = colors.ink3)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = if (analyzing) "识别完成后会进记账页，金额与分类由你确认后才落库" else "松手结束并识别 · 滑回中间取消",
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = colors.ink2,
        )
    }
}

/** 波形：11 根柱子按实时音量起伏，安静时保留一条底高，不至于看起来像卡住。 */
@Composable
private fun VoiceWaveform(level: Float) {
    val colors = tallyColors
    val bases = listOf(6, 14, 20, 11, 17, 7, 13, 19, 9, 15, 5)
    Row(
        modifier = Modifier.height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        bases.forEach { base ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((base * (0.35f + 0.65f * level)).dp.coerceAtLeast(2.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.brand.copy(alpha = 0.85f)),
            )
        }
    }
}

/** 底部状态卡：识别进行中与「已存入 N 笔」共用一条，位置与录音条同一个。 */
@Composable
private fun HomeStatusCard(title: String, detail: String?, modifier: Modifier = Modifier) {
    val colors = tallyColors
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = TallyType.screenTitle.fontFamily,
            color = colors.ink,
        )
        if (detail != null) {
            Spacer(Modifier.height(5.dp))
            Text(detail, fontSize = 11.5.sp, lineHeight = 16.sp, color = colors.ink2)
        }
    }
}

/**
 * 语音没记上时的显式交代（V-07）：说清为什么、库里没有新账单，再给两条出路。
 * [openSettings] 只在「应用能做的都做了还是录不了」时才置位；
 * [appPermission] 区分是去本应用的权限页（麦克风被禁）还是系统语音引擎设置页。
 */
private data class VoiceFailure(
    val reason: String,
    val openSettings: Boolean = false,
    val appPermission: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceFailureSheet(
    reason: String,
    openSettings: Boolean,
    settingsLabel: String,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("这段语音没有记上账单", style = TallyType.sectionTitle, color = colors.ink, modifier = Modifier.weight(1f))
                InlineIcon(iconKey = "warn", contentDescription = null, size = 15.dp, tint = colors.expense)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$reason\n这段话没有被记成任何账单，库里没有新增。",
                fontSize = 11.5.sp,
                lineHeight = 19.sp,
                color = colors.ink2,
            )
            Spacer(Modifier.height(11.dp))
            if (openSettings) {
                TallyButton(
                    label = settingsLabel,
                    onClick = onOpenSettings,
                    variant = ButtonVariant.GOLD,
                    leadingIcon = "set",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(7.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TallyButton(label = "再试一次", onClick = onRetry, variant = ButtonVariant.GHOST, modifier = Modifier.weight(1f))
                TallyButton(
                    label = "手动记一笔",
                    onClick = onManual,
                    variant = ButtonVariant.BRAND,
                    leadingIcon = "plus2",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 抽屉里点了二级页之后，等多久再无声收抽屉。
 *
 * 推入转场是淡入 200ms + 轻推 260ms，等它跑完新页已经盖满屏幕，这一下收抽屉谁也看不见。
 * 提前收就等于让抽屉动画和转场抢同几帧，那正是「闪一下」的来源。
 */
private const val DRAWER_CLOSE_AFTER_PUSH_MS = 320L
