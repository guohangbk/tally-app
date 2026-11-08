package io.github.guohangbk.tally.ui.assistant

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.speech.SpeechOutcome
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.AiChatEntry
import io.github.guohangbk.tally.domain.model.AiConversation
import io.github.guohangbk.tally.domain.model.AiMessageRole
import io.github.guohangbk.tally.domain.model.AiMessageType
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.DraftRow
import io.github.guohangbk.tally.ui.common.formatDay
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToast
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.ToastTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 小莉问答。这里不画图、不生成账单，只是把本地数字拿去问一句；
 * 失败的回复留在列表里，旁边带重试，不伪装成「什么都没问题」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantChatScreen(
    initialPrompt: String? = null,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    /** 记账卡上「补分类 / 改一下」：带上已识别的字段去记账页，落库前仍过人手。 */
    onOpenDraft: (ChatBillDraft) -> Unit = {},
) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val viewModel = tallyViewModel { AssistantChatViewModel(graph, initialPrompt) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = tallyColors
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    /** 要改名的那一段对话：弹层关掉后仍留在页上，改名对话框读它。 */
    var pendingRename by remember { mutableStateOf<AiConversation?>(null) }
    /** 点缩略图后全屏看的那个图（存路径，打开时按大尺寸重新解码）。 */
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var showConversations by remember { mutableStateOf(false) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    var holdMs by remember { mutableStateOf(0L) }
    /** 语音走点击开关：idle 未开录 → listening 正在录 → recognizing 正在识别，全程一次点击一步。 */
    var voicePhase by remember { mutableStateOf(VoicePhase.Idle) }
    val scope = rememberCoroutineScope()

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        DebugLog.log("Mic", "权限申请结果 granted=$granted")
        if (!granted) voiceHint = "没有麦克风权限，可以在系统设置里允许；也可以打字或发图。"
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AssistantChatViewModel.MAX_PENDING_IMAGES),
    ) { uris -> viewModel.attachImages(uris) }
    // 本机引擎起不来时（小米回 error=9）改走系统语音输入，识别结果落进输入框，发不发由人决定。
    val systemVoice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        DebugLog.log("Mic", "系统语音输入返回：${if (heard.isBlank()) "空" else "「$heard」"}")
        if (heard.isBlank()) voiceHint = "没有听清，请重试或直接打字" else viewModel.onInputChange(heard)
    }
    fun launchSystemVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说一句要问小莉的话")
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            voiceHint = "这台机器没有可用的系统语音输入，直接打字吧"
        } else {
            systemVoice.launch(intent)
        }
    }

    // 语音是点击开关：点一下开始录，再点一下结束并发送，说满 20 秒自己收尾。
    // 用组合作用域里的协程而不是 LaunchedEffect(voicePhase)——切换状态会改 key，
    // 那会把正在等识别结果的协程一起取消掉。
    var voiceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun startVoice() {
        if (voiceJob?.isActive == true) return
        voicePhase = VoicePhase.Listening
        holdMs = 0L
        voiceJob = scope.launch {
            val listen = async { graph.speechPort.listen() }
            while (voicePhase == VoicePhase.Listening && !listen.isCompleted) {
                delay(100)
                holdMs += 100
                // 到点先自己收尾，不等用户回想起来手还压着这件事。
                if (holdMs >= VoiceMaxMillis) voicePhase = VoicePhase.Recognizing
            }
            graph.speechPort.stopListening()
            val outcome = try {
                listen.await()
            } catch (cancellation: CancellationException) {
                voicePhase = VoicePhase.Idle
                return@launch
            } catch (failure: Exception) {
                SpeechOutcome.NothingHeard("识别失败，可以改用文字")
            }
            voicePhase = VoicePhase.Idle
            when (outcome) {
                is SpeechOutcome.Heard -> viewModel.sendVoice(outcome.text, holdMs)
                is SpeechOutcome.NothingHeard -> voiceHint = outcome.reason
                is SpeechOutcome.Unavailable -> voiceHint = outcome.reason
                is SpeechOutcome.NeedsSystemInput -> launchSystemVoice()
                SpeechOutcome.Cancelled -> Unit
            }
        }
    }

    /** mic 的那一下：没录就开始，正在录就结束送去识别，识别中再点无效（两次识别不该叠在一起）。 */
    fun toggleVoice() {
        when (voicePhase) {
            VoicePhase.Idle -> {
                if (!graph.speechPort.hasPermission()) {
                    DebugLog.log("Mic", "申请权限：RECORD_AUDIO（当前未授权）")
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startVoice()
                }
            }

            VoicePhase.Listening -> voicePhase = VoicePhase.Recognizing
            VoicePhase.Recognizing -> Unit
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        // 右上角空着：配置挪到 app 设置里的「小莉配置」，端侧/云端标签也不需要常驻在这一页。
        // 对话列表（新建 / 重命名 / 删除）改由点标题唤起，不给它留入口就等于删了这些操作。
        TallyTopBar(
            title = "小莉",
            onBack = onBack,
            onTitleClick = { showConversations = true },
        )

        // 提示改成贴底气泡；「重新测试」是真动作，留在气泡上就地可点。
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "小莉暂时联系不上",
            onNoticeShown = viewModel::dismissMessages,
            onErrorShown = viewModel::dismissMessages,
            errorActionLabel = "重新测试",
            onErrorAction = viewModel::refreshAvailability,
        )

        // 语音的提示自己带计时器收起，不走这条通道。
        voiceHint?.let { text ->
            LaunchedEffect(text) {
                delay(3_200)
                voiceHint = null
            }
            Text(text, style = TallyType.helper, color = colors.expense, modifier = Modifier.padding(horizontal = 15.dp))
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.isEmptyConversation) {
                EmptyChat(gold = state.availability?.showsEntry == true)
            } else {
                val listState = rememberLazyListState()
                // 进来就该停在最新一条，而不是停在第一条让人往上翻。
                // 每来一段流式增量都贴回底部：key 用长度而不是「有没有」，否则只滚第一下。
                LaunchedEffect(
                    state.conversationId,
                    state.entries.size,
                    state.working,
                    state.draftPending,
                    state.streaming?.length,
                ) {
                    val extra = if (state.working || state.streaming != null || state.draftPending) 1 else 0
                    val last = state.entries.size + extra - 1
                    if (last >= 0) listState.scrollToItem(last)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    itemsIndexed(state.entries, key = { _, entry -> entry.id }) { index, entry ->
                        val previous = state.entries.getOrNull(index - 1)
                        val showStamp = previous == null || entry.createdAt - previous.createdAt >= STAMP_GAP_MILLIS
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (showStamp) {
                                Text(
                                    text = formatChatStamp(entry.createdAt),
                                    style = TallyType.eyebrow,
                                    color = colors.ink3,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                )
                            }
                            if (entry.isDraft) {
                                // 记账卡本身就是对话里的一条消息，位置天然排在它那条回复之后。
                                DraftCardStack(
                                    drafts = entry.drafts.map { it.toUi() },
                                    onConfirmAll = { viewModel.confirmDraft(entry.id) },
                                    // 「改」只把人带去记账页填那一格，卡片留着——先撤再跳，回来就永远没了。
                                    onEditRow = { draft -> onOpenDraft(draft) },
                                    onDismissRow = { index -> viewModel.dismissRow(entry.id, index) },
                                )
                            } else {
                                // Compose 的 Text 默认不可选，所以长按气泡复制不了；整条消息包一层
                                // SelectionContainer，长按就能选字并走系统的复制菜单。
                                SelectionContainer {
                                    MessageRow(entry = entry, onRetry = { viewModel.retry(entry.id) }, onOpenImage = { viewerPath = it })
                                }
                            }
                        }
                    }
                    state.streaming?.let { text ->
                        item(key = "streaming") {
                            // 流式期间先用一条临时气泡显示半截回复，落库后由正式消息接替。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(13.dp, 13.dp, 13.dp, 4.dp))
                                    .background(colors.card)
                                    .border(1.dp, colors.line, RoundedCornerShape(13.dp, 13.dp, 13.dp, 4.dp))
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                            ) {
                                Text(text + "▍", style = TallyType.body.copy(lineHeight = 22.sp), color = colors.ink)
                            }
                        }
                    }
                    if (state.working) {
                        item(key = "working") {
                            Text("小莉正在看…", style = TallyType.helper, color = colors.ink3)
                        }
                    }
                    if (state.draftPending) {
                        item(key = "draft-pending") {
                            Text("正在生成卡片…", style = TallyType.helper, color = colors.ink3)
                        }
                    }
                }
            }
        }

        InputBar(
            value = state.input,
            onValueChange = viewModel::onInputChange,
            onSend = viewModel::send,
            enabled = state.canSend,
            working = state.working,
            gold = state.availability?.showsEntry == true,
            voicePhase = voicePhase,
            holdMs = holdMs,
            pendingImages = state.pendingImages,
            onOpenPendingImage = { path -> viewerPath = path },
            onPickImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDetachImage = viewModel::detachImage,
            onToggleVoice = ::toggleVoice,
            micEnabled = state.availability?.showsEntry != false,
            // 配置里没勾「支持图片输入」就不给发图，避免把截图发给一个看不了的模型。
            imageEnabled = state.availability?.visionCapable == true,
        )

        bottomBar()
    }

    if (showConversations) {
        TallyBottomSheet(
            onDismissRequest = { showConversations = false },
            containerColor = colors.paper,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                // 新建是这一页的头等动作：加号跟「对话列表」同一行，放在最右。
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "对话列表",
                        style = TallyType.sectionTitle,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    InlineIcon(
                        iconKey = "plus",
                        contentDescription = "新建一段对话",
                        size = 17.dp,
                        tint = colors.ink,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.card)
                            .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                            .clickable {
                                viewModel.newConversation()
                                showConversations = false
                            }
                            .padding(7.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                ConversationList(
                    state = state,
                    onOpen = {
                        viewModel.openConversation(it)
                        showConversations = false
                    },
                    onRename = { id -> pendingRename = state.conversations.firstOrNull { it.id == id } },
                    onDelete = {
                        pendingDelete = it
                        showConversations = false
                    },
                )
            }
        }
    }

    pendingRename?.let { conversation ->
        RenameDialog(
            current = conversation.title,
            onDismiss = { pendingRename = null },
            onConfirm = { title ->
                viewModel.rename(conversation.id, title)
                pendingRename = null
            },
        )
    }

    pendingDelete?.let { id ->
        TallyConfirmDialog(
            title = "删除这段对话",
            message = "只删除对话本身与其中的文字，账单和账单图片都不会受影响。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.delete(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    viewerPath?.let { path ->
        // 打开时按大尺寸重新解码：气泡里那张是 320px 的缩略图，直接放大只会糊成一小块。
        val graph = rememberAppGraph()
        val full = remember(path) {
            graph.attachmentStore.fileAt(path)?.let { decodeSampled(it.absolutePath, 1600) }
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewerPath = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.96f))
                    .clickable { viewerPath = null },
                contentAlignment = Alignment.Center,
            ) {
                if (full != null) {
                    Image(
                        bitmap = full.asImageBitmap(),
                        contentDescription = "大图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    Text("这张图已经不在本机了", style = TallyType.helper, color = tallyColors.paper)
                }
            }
        }
    }
}

/** 相邻消息只在跨了一段时间后才插一行居中时间；每条都顶一个名字和时间是表单，不是对话。 */
private const val STAMP_GAP_MILLIS = 5 * 60 * 1000L

@Composable
private fun MessageRow(
    entry: AiChatEntry,
    onRetry: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val colors = tallyColors
    if (entry.isFromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            when (entry.type) {
                AiMessageType.VOICE -> VoiceBubble(durationMs = entry.durationMs, transcript = entry.content)
                AiMessageType.IMAGE -> ImageBubble(entry = entry, onOpen = onOpenImage)
                else -> Box(
                    modifier = Modifier
                        .widthIn(max = 268.dp)
                        .clip(RoundedCornerShape(13.dp, 13.dp, 4.dp, 13.dp))
                        .background(colors.ink)
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                ) {
                    Text(entry.content, style = TallyType.body.copy(lineHeight = 21.sp), color = colors.paper)
                }
            }
        }
    } else {
        if (entry.failed) {
            TallyCard(
                corner = 13,
                contentPadding = 10.dp,
                borderColor = colors.expense.copy(alpha = 0.3f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = entry.content.ifBlank { "这次没有接上" },
                        style = TallyType.helper,
                        color = colors.expense,
                        modifier = Modifier.weight(1f),
                    )
                    TallyButton(label = "重试", onClick = onRetry, variant = ButtonVariant.GHOST, height = 30)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp, 13.dp, 13.dp, 4.dp))
                    .background(colors.card)
                    .border(1.dp, colors.line, RoundedCornerShape(13.dp, 13.dp, 13.dp, 4.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Text(entry.content, style = TallyType.body.copy(lineHeight = 22.sp), color = colors.ink)
            }
        }
    }
}

/**
 * 发出去的图片：气泡里就是那几张图本身，一次最多三张，横排点开各自看大图。
 * 之前画的是一块斜纹占位，看上去像黑块，用户会以为图没发出去。
 */
@Composable
private fun ImageBubble(entry: AiChatEntry, onOpen: (String) -> Unit) {
    val colors = tallyColors
    val graph = rememberAppGraph()
    val bitmaps = remember(entry.imagePaths) {
        entry.imagePaths.map { path -> graph.attachmentStore.fileAt(path)?.let { decodeSampled(it.absolutePath, 320) } }
    }
    // 一张时给足尺寸，几张并排就各自收一点，免得第三张被挤出屏幕。
    val cellWidth = if (entry.imagePaths.size > 1) 86.dp else 108.dp
    val cellHeight = if (entry.imagePaths.size > 1) 68.dp else 82.dp
    val cellShape = RoundedCornerShape(10.dp)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (bitmaps.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                bitmaps.forEachIndexed { index, bitmap ->
                    val path = entry.imagePaths[index]
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "已发送的图片，点按看大图",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = cellWidth, height = cellHeight)
                                .clip(cellShape)
                                .border(1.dp, colors.line, cellShape)
                                .clickable { onOpen(path) },
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = cellWidth, height = cellHeight)
                                .clip(cellShape)
                                .border(1.dp, colors.line, cellShape)
                                .background(colors.ink.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            InlineIcon(iconKey = "image", contentDescription = "图片不可见", size = 18.dp, tint = colors.ink3)
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(width = cellWidth, height = cellHeight)
                    .clip(cellShape)
                    .border(1.dp, colors.line, cellShape)
                    .background(colors.ink.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center,
            ) {
                InlineIcon(iconKey = "image", contentDescription = "图片不可见", size = 18.dp, tint = colors.ink3)
            }
        }
        if (entry.content.isNotBlank()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 268.dp)
                    .clip(RoundedCornerShape(13.dp, 13.dp, 4.dp, 13.dp))
                    .background(colors.ink)
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                Text(entry.content, style = TallyType.body.copy(lineHeight = 21.sp), color = colors.paper)
            }
        }
    }
}

/**
 * 小莉读出来的待确认账单。一笔和多笔共用同一张卡：卡头「N 笔 · 合计 · 来源」加一个记入动作，
 * 下面每笔一行，行内独立「改 / 先不记」（设计板 §04 B-01）。
 * 分类没认出来的那一行标红并把人指回记账页——落库会撞「分类不存在」，不能让它混过去。
 * 「先不记」只把那一笔划掉，气泡与卡片都留在对话里；全部表过态之后整卡才收起成一行结果。
 */
@Composable
private fun DraftCardStack(
    drafts: List<ChatBillDraft>,
    onConfirmAll: () -> Unit,
    onEditRow: (ChatBillDraft) -> Unit,
    onDismissRow: (Int) -> Unit,
) {
    val colors = tallyColors
    if (drafts.isEmpty()) return
    val pending = drafts.filter { it.pending }

    if (pending.isEmpty()) {
        val savedCount = drafts.count { it.saved }
        val droppedCount = drafts.size - savedCount
        val savedTotal = drafts.filter { it.saved }.sumOf { it.amountMinor }
        val done = savedCount > 0
        TallyCard(corner = 13, contentPadding = 10.dp, borderColor = if (done) colors.brand.copy(alpha = 0.32f) else colors.line) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InlineIcon(
                    iconKey = if (done) "check" else "close",
                    contentDescription = null,
                    size = 13.dp,
                    tint = if (done) colors.brand else colors.ink3,
                )
                Text(
                    text = buildString {
                        if (done) append("已记入 ${savedCount} 笔 · ¥" + formatAmount(savedTotal, grouping = false))
                        if (droppedCount > 0) {
                            append(if (done) " · 先不记 ${droppedCount} 笔" else "${droppedCount} 笔先不记了")
                        }
                    },
                    style = TallyType.helper,
                    color = if (done) colors.brand else colors.ink3,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        return
    }

    TallyCard(corner = 13, contentPadding = 0.dp, borderColor = colors.gold.copy(alpha = 0.34f)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.gold.copy(alpha = 0.09f))
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    // 只有金额用衬线，前面的「N 笔 · 合计」是文字。
                    text = buildAnnotatedString {
                        append("${pending.size} 笔 · 合计 ")
                        withStyle(SpanStyle(fontFamily = FontFamily.Serif)) {
                            append("¥" + formatAmount(pending.sumOf { it.amountMinor }, grouping = false))
                        }
                    },
                    style = TallyType.listTitle.copy(fontSize = 13.5.sp, lineHeight = 17.sp),
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                // 来源是整条消息的属性，标一次就够，不必每行重复。
                TallyTag(
                    text = when (drafts.first().source) {
                        BillSource.SCREENSHOT -> "图片"
                        BillSource.VOICE -> "语音"
                        else -> "文字"
                    },
                    tone = TagTone.OUTLINE,
                )
                TextAction(
                    label = if (pending.size == 1) "记入这一笔" else "全部记入",
                    onClick = onConfirmAll,
                    color = colors.gold,
                )
            }
            drafts.forEachIndexed { index, draft ->
                if (index > 0) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                DraftRow(
                    draft = draft,
                    onEdit = { onEditRow(draft) },
                    onDismiss = { onDismissRow(index) },
                )
            }
        }
    }
}

/** 批量卡里的一笔：金额 + 「日期 · 分类 · 备注」+ 两个行内动作。缺一格只标这一行，不连累其余。 */
@Composable
private fun DraftRow(draft: ChatBillDraft, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val colors = tallyColors
    val dropped = draft.dismissed
    val decoration = if (dropped) TextDecoration.LineThrough else TextDecoration.None
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = (if (draft.type == BillType.INCOME) "+" else "−") + "¥" + formatAmount(draft.amountMinor, grouping = false),
            style = TallyType.moneyDisplay.copy(fontSize = 14.sp, lineHeight = 18.sp, textDecoration = decoration),
            color = when {
                dropped -> colors.ink3
                draft.type == BillType.INCOME -> colors.income
                else -> colors.expense
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(
                    draft.occurredAt.takeIf { isNotToday(it) }?.let { formatDraftDay(it) },
                    draft.categoryLabel ?: "分类未认出",
                    draft.merchant?.takeIf { it.isNotBlank() && it != draft.note },
                    draft.note?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "备注待补" },
                style = TallyType.helper.copy(textDecoration = decoration),
                color = if (dropped) colors.ink3 else colors.ink2,
                maxLines = 2,
            )
        }
        when {
            // 记入与划掉都只影响这一行：留一个能看懂的状态，比让整卡消失更有用。
            draft.saved -> StaticAction(label = "已记入", color = colors.brand)
            dropped -> TextAction(label = "恢复", onClick = onDismiss, color = colors.ink2)
            else -> {
                TextAction(
                    label = if (draft.needsCategory) "补分类" else "改",
                    onClick = onEdit,
                    color = if (draft.needsCategory) colors.expense else colors.ink2,
                )
                TextAction(label = "先不记", onClick = onDismiss, color = colors.ink3)
            }
        }
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit, color: Color = tallyColors.ink2) {
    Text(
        text = label,
        style = TallyType.helper,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    )
}

/** 已经定下来的那一行不给点击：同一张卡反复点会记出重复账单。 */
@Composable
private fun StaticAction(label: String, color: Color) {
    Text(
        text = label,
        style = TallyType.helper,
        color = color,
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
    )
}

/**
 * 语音点击开关的三段。recognizing 期间 mic 不给点，避免两次识别叠在一起。
 */
private enum class VoicePhase {
    Idle,
    Listening,
    Recognizing,
}

/** 卡片的存储行 → 界面草稿：字段一样，只是界面上还要还原成枚举并判断「要不要补分类」。 */
private fun DraftRow.toUi(): ChatBillDraft = ChatBillDraft(
    amountMinor = amountMinor,
    type = runCatching { BillType.valueOf(type) }.getOrDefault(BillType.EXPENSE),
    categoryL1Id = categoryL1Id,
    categoryL2Id = categoryL2Id,
    categoryLabel = categoryLabel,
    note = note,
    occurredAt = occurredAt,
    source = runCatching { BillSource.valueOf(source) }.getOrDefault(BillSource.MANUAL),
    merchant = merchant,
    saved = saved,
    dismissed = dismissed,
)

/** 卡片上的落账日期只在不是今天时显示：今天本来就是默认落账日，多写一段反而占字。 */
private fun isNotToday(millis: Long): Boolean = dayKey(millis) != dayKey(System.currentTimeMillis())

private fun formatDraftDay(millis: Long): String =
    java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(millis))

private fun dayKey(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(millis))

private fun formatChatStamp(millis: Long): String {
    val date = java.util.Date(millis)
    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(date)
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(System.currentTimeMillis()))
    val dayOfMessage = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(date)
    return if (dayOfMessage == today) clock else "$dayOfMessage $clock"
}

/** 按目标边长采样解码，避免把一张几 M 的原图整个塞进气泡。 */
private fun decodeSampled(path: String, targetPx: Int): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx || bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** 按住说话的成品：时长 + 小波形，识别出的文字原样跟在下面，看得见机器听懂了什么。 */
@Composable
private fun VoiceBubble(durationMs: Long?, transcript: String) {
    val colors = tallyColors
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(13.dp))
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InlineIcon(iconKey = "mic", contentDescription = null, size = 11.dp, tint = colors.ink2)
            Text(
                text = "语音 " + formatClock(durationMs ?: 0L),
                fontSize = 11.5.sp, lineHeight = 14.4.sp,
                color = colors.ink2,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.height(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf(4, 9, 12, 6, 10, 5).forEach { height ->
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(height.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(colors.ink3),
                    )
                }
            }
        }
        if (transcript.isNotBlank()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 268.dp)
                    .clip(RoundedCornerShape(13.dp, 13.dp, 4.dp, 13.dp))
                    .background(colors.ink)
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                Text(transcript, style = TallyType.body.copy(lineHeight = 21.sp), color = colors.paper)
            }
        }
    }
}

private fun formatClock(millis: Long): String {
    val total = (millis / 1000L).toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun EmptyChat(gold: Boolean) {
    val colors = tallyColors
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AiBloom(active = false, size = 74.dp)
        Spacer(Modifier.height(10.dp))
        Text("想问什么都可以", style = TallyType.sectionTitle, color = colors.ink)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "比如「上个月打车花了多少」「今年哪一类涨得最快」。回答只用本地算好的数字，不会替你保存账单。",
            style = TallyType.helper,
            color = colors.ink3,
        )
        if (!gold) {
            Spacer(Modifier.height(8.dp))
            TallyTag(text = "小莉未启用", tone = TagTone.SLATE)
        }
    }
}

/**
 * 待发位上的一张图：整块点开看大图，右上角那枚 ✕ 只撤这一张。
 * 读不出来就留一枚灰图标占位——几张并排，谁没读出来一眼看得出来，不用再配一句说明。
 */
@Composable
private fun PendingThumb(
    bitmap: android.graphics.Bitmap?,
    thumbPath: String?,
    onOpen: () -> Unit,
    onDetach: () -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(8.dp)
    Box(modifier = Modifier.size(40.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.ink.copy(alpha = 0.06f))
                .border(1.dp, colors.line, shape)
                // 缩略图本身就是一颗按钮：发之前还能再看一眼图里有什么。
                .then(if (thumbPath != null) Modifier.clickable(onClick = onOpen) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "待发送的图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                InlineIcon(iconKey = "image", contentDescription = null, size = 14.dp, tint = colors.ink3)
            }
        }
        // 视觉只有 18dp，点却按 28dp 收：三张并排时那颗 ✕ 太不容易点中。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .clickable(onClick = onDetach),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(bottomStart = 8.dp, topEnd = 8.dp))
                    .background(colors.ink),
                contentAlignment = Alignment.Center,
            ) {
                InlineIcon(iconKey = "close", contentDescription = "移除这张图片", size = 10.dp, tint = colors.paper)
            }
        }
    }
}

/**
 * .cbar：图片、输入框、话筒、发送全部包在同一颗圆角里。
 * 三个按钮各占 48dp 触摸框（视觉上只是一个小图标），发送用香槟金箭头而不是「发送」两个字。
 * 输入框随内容长高，最多 3.5 行，再多就自己滚。
 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    working: Boolean,
    gold: Boolean,
    voicePhase: VoicePhase,
    holdMs: Long,
    pendingImages: List<PendingImage>,
    onOpenPendingImage: (String) -> Unit,
    onPickImage: () -> Unit,
    onDetachImage: (Int) -> Unit,
    onToggleVoice: () -> Unit,
    micEnabled: Boolean,
    imageEnabled: Boolean,
) {
    val colors = tallyColors
    val context = LocalContext.current
    val shape = RoundedCornerShape(17.dp)
    val fieldScroll = rememberScrollState()
    val graph = rememberAppGraph()
    // 优先用选中那一刻压好的缩略图；压不出来才退回按 URI 现场解码。
    val pendingBitmaps = remember(pendingImages) {
        pendingImages.map { pending ->
            pending.thumbPath?.let { path ->
                graph.attachmentStore.fileAt(path)?.let { decodeSampled(it.absolutePath, 160) }
            } ?: decodeUriSampled(context, pending.uri, 240)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper)
            .border(width = 1.dp, color = colors.line),
    ) {
        // 待发图条从输入框上方展开，移除时收回去：整条突然消失会让人怀疑图是不是没撤掉。
        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(110)),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    pendingImages.forEachIndexed { index, pending ->
                        PendingThumb(
                            bitmap = pendingBitmaps.getOrNull(index),
                            thumbPath = pending.thumbPath,
                            onOpen = { pending.thumbPath?.let(onOpenPendingImage) },
                            onDetach = { onDetachImage(index) },
                        )
                    }
                    // 还留得住位置就再给一颗加号；选满三张它就自己消失。
                    if (pendingImages.size < AssistantChatViewModel.MAX_PENDING_IMAGES) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                                .clickable(enabled = imageEnabled, onClick = onPickImage),
                            contentAlignment = Alignment.Center,
                        ) {
                            InlineIcon(iconKey = "plus", contentDescription = "再加一张", size = 15.dp, tint = colors.ink3)
                        }
                    }
                }
                // 缩略图正常时不写任何说明；读不出来才说一句，免得看着像图没选上。
                if (pendingBitmaps.any { it == null }) {
                    Text("有图片读不出来 · 仍可发送，或换一张", style = TallyType.helper, color = colors.expense)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(colors.card)
                    .border(1.dp, colors.line, shape)
                    .padding(start = 3.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BarButton(
                    iconKey = "image",
                    label = if (imageEnabled) "发图片" else "当前模型未开启图片输入",
                    enabled = imageEnabled && voicePhase == VoicePhase.Idle,
                    // 置灰但可点：看起来是禁用的，点一下告诉人为什么，而不是按了没反应。
                    clickWhenDisabled = !imageEnabled && voicePhase == VoicePhase.Idle,
                    onClick = {
                        if (imageEnabled) {
                            onPickImage()
                        } else {
                            TallyToast.show("当前模型不支持图片识别，换成支持图片的模型后再试", ToastTone.INFO)
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp, max = 63.dp)
                        .padding(vertical = 11.dp),
                ) {
                    when (voicePhase) {
                        // 秒数用起始时刻换算、在录制与识别两段都继续走，避免"看着像卡住"。
                        VoicePhase.Listening -> Text(
                            text = "正在聆听 · ${holdMs / 1000}秒 · 再点一次结束",
                            fontSize = 12.5.sp, lineHeight = 15.6.sp,
                            color = colors.onGlow,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )

                        VoicePhase.Recognizing -> Text(
                            text = "正在识别… ${formatClock(holdMs)}",
                            fontSize = 12.5.sp, lineHeight = 15.6.sp,
                            color = colors.ink2,
                            maxLines = 1,
                        )

                        VoicePhase.Idle -> BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth().verticalScroll(fieldScroll),
                            textStyle = TallyType.body.copy(fontSize = 13.5.sp, lineHeight = 18.sp, color = colors.ink),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.gold),
                            decorationBox = { inner ->
                                if (value.isEmpty()) {
                                    Text("问点什么…", style = TallyType.body, color = colors.ink3.copy(alpha = 0.6f))
                                }
                                inner()
                            },
                        )
                    }
                }
                BarButton(
                    iconKey = "mic",
                    label = when (voicePhase) {
                        VoicePhase.Idle -> "点一下说话"
                        VoicePhase.Listening -> "点一下结束"
                        VoicePhase.Recognizing -> "正在识别"
                    },
                    // 识别中不给点：两次识别叠在一起，回来的文字会互相盖掉。
                    enabled = micEnabled && voicePhase != VoicePhase.Recognizing,
                    tintIfActive = voicePhase == VoicePhase.Listening,
                    onClick = onToggleVoice,
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(enabled = enabled && voicePhase == VoicePhase.Idle && !working, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (enabled && voicePhase == VoicePhase.Idle && !working) colors.ink else colors.paper),
                        contentAlignment = Alignment.Center,
                    ) {
                        InlineIcon(
                            iconKey = "chevron",
                            contentDescription = "发送",
                            size = 15.dp,
                            tint = if (enabled && voicePhase == VoicePhase.Idle && !working) colors.gold else colors.ink3,
                        )
                    }
                }
            }
        }
    }
}

/** 输入栏里的小方块按钮：48dp 见方只用来保证点得中，视觉上仍是一枚小图标。 */
@Composable
private fun BarButton(
    iconKey: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit = {},
    /** 视觉上仍置灰，但允许点：用于把「为什么点不了」讲给人听。 */
    clickWhenDisabled: Boolean = false,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    tintIfActive: Boolean = false,
) {
    val colors = tallyColors
    // 按住松手必须用 pointerInput(Unit)：若把 enabled 当 key，按下置 holding=true 会立刻
    // 让 enabled 变 false → pointerInput 重启 → awaitRelease()/onRelease() 被取消，松手永远不触发。
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(
                if (onPress != null && onRelease != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (!currentEnabled) return@detectTapGestures
                                currentOnPress?.invoke()
                                awaitRelease()
                                currentOnRelease?.invoke()
                            },
                        )
                    }
                } else {
                    Modifier.clickable(enabled = enabled || clickWhenDisabled, onClick = onClick)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (tintIfActive) colors.glow else colors.paper)
                .border(1.dp, if (tintIfActive) colors.gold.copy(alpha = 0.4f) else colors.line, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(
                iconKey = iconKey,
                contentDescription = label,
                size = 14.dp,
                tint = if (tintIfActive) colors.onGlow else if (enabled) colors.ink2 else colors.ink3,
            )
        }
    }
}

/** 待发送的图按目标边长采样解码：整张原图塞进组合阶段会当场卡一下。 */
private fun decodeUriSampled(context: android.content.Context, uri: android.net.Uri, targetPx: Int): android.graphics.Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openFileDescriptor(uri, "r")?.use { handle ->
        android.graphics.BitmapFactory.decodeFileDescriptor(handle.fileDescriptor, null, bounds)
    } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx || bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    resolver.openFileDescriptor(uri, "r")?.use { handle ->
        android.graphics.BitmapFactory.decodeFileDescriptor(
            handle.fileDescriptor,
            null,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}.getOrNull()

@Composable
private fun ConversationList(
    state: AssistantChatUiState,
    onOpen: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = tallyColors
    TallyCard(corner = 15, contentPadding = 8.dp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            if (state.conversations.isEmpty()) {
                Text("还没有对话。", style = TallyType.helper, color = colors.ink3, modifier = Modifier.padding(8.dp))
            }
            state.conversations.forEach { conversation ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(conversation.id) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = conversation.title,
                            style = TallyType.listTitle,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatDay(conversation.updatedAt), style = TallyType.eyebrow, color = colors.ink3)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 3.dp)) {
                        MiniAction("重命名") { onRename(conversation.id) }
                        MiniAction("删除", danger = true) { onDelete(conversation.id) }
                    }
                }
            }
        }
    }
}

/** 改名只动标题这一行；空名不给保存，否则列表里会出现一段没有名字的对话。 */
@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val colors = tallyColors
    var text by remember(current) { mutableStateOf(current) }
    val trimmed = text.trim()
    val shape = RoundedCornerShape(11.dp)
    TallyConfirmDialog(
        title = "重命名这段对话",
        message = "只是换个名字，里面的问答与已经记入的账单都不动。",
        confirmLabel = "保存",
        onConfirm = { if (trimmed.isNotEmpty()) onConfirm(trimmed) },
        onDismiss = onDismiss,
        content = {
            Column {
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= 24) text = it },
                    singleLine = true,
                    textStyle = TallyType.body.copy(color = colors.ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.gold),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colors.card)
                        .border(1.dp, if (trimmed.isEmpty()) colors.expense.copy(alpha = 0.45f) else colors.line, shape)
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text("对话标题", style = TallyType.body, color = colors.ink3)
                        inner()
                    },
                )
                if (trimmed.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("名字不能空着。", style = TallyType.helper, color = colors.expense)
                }
            }
        },
    )
}

@Composable
private fun MiniAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = tallyColors
    Text(
        text = label,
        style = TallyType.helper,
        color = if (danger) colors.expense else colors.ink3,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 1.dp),
    )
}

