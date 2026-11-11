package io.github.guohangbk.tally.ui.bill

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.parseAmount
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.ui.common.CategoryPanel
import io.github.guohangbk.tally.ui.common.LedgerPickerSheet
import io.github.guohangbk.tally.ui.common.TagPickerSheet
import io.github.guohangbk.tally.ui.common.TagPill
import io.github.guohangbk.tally.ui.common.TallyDateTimePickerDialog
import io.github.guohangbk.tally.ui.common.formatClock
import io.github.guohangbk.tally.ui.common.millisToLocalDate
import io.github.guohangbk.tally.ui.common.startOfDay
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.KeypadRole
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyKeypad
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToast
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.ToastTone
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import kotlinx.coroutines.delay

/** 键盘那格的底部留白；键盘本体在 `ui/design/TallyKeypad`。 */
private val DOCK_BOTTOM_PADDING = 8.dp


/**
 * P03 记账页。顶部只有支出 / 收入下划线 tab，分类占满中段可滚区，
 * 备注 + 金额同一行、四个 chip、数字键盘一起固定在底部 dock——首笔 ≤ 4 步，第 2 笔起 ≤ 2 步。
 */
@Composable
fun BillEntryScreen(
    billId: String? = null,
    prefillL1Id: String? = null,
    prefillL2Id: String? = null,
    prefillType: BillType? = null,
    prefillAmountMinor: Long? = null,
    prefillNote: String? = null,
    prefillSource: String? = null,
    prefillSourceKind: String? = null,
    prefillOccurredAt: Long? = null,
    prefillKeyword: String? = null,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onAddSubCategory: (String) -> Unit = {},
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "bill-entry-${billId ?: "new"}") {
        BillEntryViewModel(
            graph = graph,
            billId = billId,
            prefillL1Id = prefillL1Id,
            prefillL2Id = prefillL2Id,
            prefillType = prefillType,
            prefillAmountMinor = prefillAmountMinor,
            prefillNote = prefillNote,
            prefillSource = prefillSource,
            prefillSourceKind = prefillSourceKind,
            prefillOccurredAt = prefillOccurredAt,
            prefillKeyword = prefillKeyword,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = tallyColors
    var showLedgerSheet by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTagSheet by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setImage(uri)
    }

    LaunchedEffect(state.savedId) {
        state.savedId?.let(onSaved)
    }
    LaunchedEffect(state.repeatNotice) {
        if (state.repeatNotice != null) {
            delay(2_400)
            viewModel.clearNotice()
        }
    }
    // LN-01：保存后这一页就出栈了，写规则必须活过页面——动作挂在依赖图的
    // KeywordSediment 上，气泡宿主也在应用根部，所以两种时序都不会丢。
    LaunchedEffect(state.sedimentOffer) {
        val offer = state.sedimentOffer ?: return@LaunchedEffect
        // 分类写全路径：只报末级名的话，「打车」这种在两个一级下面都可能出现，认不出是哪个。
        val hit = state.categories.firstOrNull { it.id == offer.categoryId }
        val categoryPath = hit?.let { row ->
            row.parentId?.let { parent -> state.categories.firstOrNull { it.id == parent }?.name }
                ?.let { "$it · ${row.name}" } ?: row.name
        }
        TallyToast.show(
            // INFO 那条色号（金）：正文是墨色，动作要跟「撤回」一样一眼看得出是另一个东西。
            tone = ToastTone.INFO,
            text = "以后「${offer.keyword}」都记到 ${categoryPath ?: "这个分类"}",
            actionLabel = "确定",
            onAction = { graph.keywordSediment.remember(offer.keyword, offer.categoryId) },
            durationMillis = 6_000L,
        )
        viewModel.dismissSediment()
    }

    val askDismiss = {
        val changed = if (state.editing) {
            state.dirty
        } else {
            state.amountText.isNotEmpty() || state.note.isNotEmpty()
        }
        if (changed) confirmDiscard = true else onBack()
    }

    // 输入法弹起时这一页什么都不做：系统自己会把窗口抬到「备注框刚好露出来」的位置，
    // 再按 WindowInsets.ime 手动抬一遍就是双倍位移——那就是之前"整页往上飞"的来处。
    // 同理也不能在键盘弹起时收起数字键盘：那会把备注框推到更靠下的位置，系统反而抬得更高。

    Box(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        Column(modifier = Modifier.fillMaxSize()) {
            EntryHeader(
                editing = state.editing,
                type = state.type,
                onType = viewModel::setType,
                ledgerName = state.ledgerName,
                onLedgerClick = { showLedgerSheet = true },
                onDismiss = askDismiss,
            )

            // 提示改成贴底气泡，保留「还不能保存」这个定性说法。
            TallyToastEffects(
                notice = null,
                error = state.error,
                errorTitle = "还不能保存",
                onErrorShown = viewModel::clearError,
            )

            // .srcbar：识别来源条。撤销只清掉机器填的三项，页面不关，仍可手填。
            state.sourceText?.let { heard ->
                SourceBar(
                    iconKey = if (state.sourceKind == "shot") "image" else "mic",
                    label = if (state.sourceKind == "shot") "截图识别 · 已填充" else "语音识别 · 已填充",
                    heard = heard,
                    onUndo = viewModel::undoSource,
                )
            }

            // 分类区两边收进来 5dp，把宽度还给图标。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp),
            ) {
                Spacer(Modifier.height(3.dp))
                CategoryPanel(
                    categories = state.categories,
                    selectedL1 = state.categoryL1Id,
                    selectedL2 = state.categoryL2Id,
                    onSelect = viewModel::selectCategory,
                    onAddSubCategory = { parentId -> onAddSubCategory(parentId) },
                )
                Spacer(Modifier.height(16.dp))
            }

            // dock 固定在底部；中段可滚区吃掉了所有剩余高度，键盘与保存键永远在屏内。
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.paper),
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                    Column(modifier = Modifier.padding(horizontal = 15.dp).padding(top = 6.dp, bottom = DOCK_BOTTOM_PADDING)) {
                        NoteAmountLine(
                            note = state.note,
                            onNoteChange = viewModel::setNote,
                            canSave = state.canSave,
                            onSave = viewModel::requestSave,
                            amountText = state.amountText,
                            isIncome = state.isIncome,
                            hasOperator = state.hasOperator,
                            resultText = state.expressionResult?.let(::formatKeypadResult),
                            expressionPending = state.expressionPending,
                            negative = (state.expressionResult ?: 0L) < 0L,
                        )
                        // 已选标签单独一行、写全名并带 ✕：选中几枚是「这一笔属于哪件事」，
                        // 比一枚亮着的「标签」chip 说得更清楚（TG-02）。取消到零枚整行消失。
                        if (state.selectedTags.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                state.selectedTags.forEach { tag ->
                                    TagPill(
                                        name = tag.name,
                                        selected = true,
                                        fontSize = 11.sp,
                                        onRemove = { viewModel.toggleTag(tag.id) },
                                    )
                                }
                                TagPill(name = "＋", fontSize = 11.sp, onClick = { showTagSheet = true })
                            }
                        }
                        // chip 行与数字键盘常驻：备注框在它们上面，系统按备注框的位置抬窗口，
                        // 收掉下面这两段只会让焦点框掉得更低，于是被抬得更高。
                        // 五枚 chip 在窄屏或长日期文案下会折成两行，用 FlowRow 兜住，避免被裁掉。
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            EntryChip(
                                label = when {
                                    state.timePrecision == TimePrecision.DATETIME -> {
                                        val prefix = if (startOfDay(state.occurredAt) == startOfDay(System.currentTimeMillis())) {
                                            "今天"
                                        } else {
                                            chipDate(state.occurredAt)
                                        }
                                        "$prefix ${formatClock(state.occurredAt)}"
                                    }
                                    startOfDay(state.occurredAt) == startOfDay(System.currentTimeMillis()) -> "今天"
                                    else -> chipDate(state.occurredAt)
                                },
                                onClick = { showDatePicker = true },
                            )
                            // 图片挪到日期后面，报销挨着排除：两个「标记类」chip 排在一起，扫一眼就是一列开关。
                            if (settings.billImageEnabled) {
                                EntryChip(
                                    label = "图片",
                                    count = state.imageCount,
                                    state = if (state.imageCount > 0) ChipState.ON else ChipState.OFF,
                                    onClick = {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                    // 点它会打开图库，没法「再点一下取消」：用右上角的叉收回还没保存的这一张。
                                    onClear = if (state.imageUri != null) ({ viewModel.setImage(null) }) else null,
                                )
                            }
                            // 报销 / 排除不再挂拨钮：点亮即开、再点即关，和图片同一个样式。
                            // 报销另有总开关（设置 → 记账偏好）：关掉时这枚 chip 与抽屉里那个入口一起消失。
                            if (settings.reimburseEnabled) {
                                EntryChip(
                                    label = "报销",
                                    state = if (state.reimbursed != ReimburseStatus.NONE) ChipState.ON else ChipState.OFF,
                                    enabled = !state.isIncome,
                                    onClick = { viewModel.setReimbursed(state.reimbursed == ReimburseStatus.NONE) },
                                )
                            }
                            if (settings.excludeEnabled) {
                                EntryChip(
                                    label = "排除",
                                    state = if (state.excludedFromStats) ChipState.ON else ChipState.OFF,
                                    onClick = { viewModel.setExcluded(!state.excludedFromStats) },
                                )
                            }
                            // 标签是第五枚开关：点开弹层点选，不挤 keypad，也不占主链路。
                            // chip 上不写数量——选中几枚在上方那一行数得出来（TG-02）。
                            // 开关归设置 → 记账键盘；关掉只是不在记账页露出，管理页与筛选照常。
                            if (settings.tagEnabled) {
                                EntryChip(
                                    label = "标签",
                                    state = if (state.selectedTags.isNotEmpty()) ChipState.ON else ChipState.OFF,
                                    onClick = { showTagSheet = true },
                                )
                            }
                        }
                        TallyKeypad(
                            onKey = viewModel::pressKey,
                            onClear = viewModel::clearAmount,
                            onRepeat = viewModel::requestRepeat,
                            onDiscard = askDismiss,
                            onSave = viewModel::requestSave,
                            editing = state.editing,
                            pendingOperator = state.pendingOperator,
                            enabled = state.canSubmit,
                            saveEnabled = state.canSave,
                        )
                    }
                }

                state.repeatNotice?.let { notice ->
                    SessionToast(
                        text = notice,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-42).dp),
                    )
                }
            }
        }
    }

    if (state.futureConfirm) {
        TallyConfirmDialog(
            title = "这是一笔未来账单",
            message = "保存后会出现在对应日期的账单流中，不会进入当前月汇总。",
            confirmLabel = "仍然保存",
            onConfirm = viewModel::confirmFutureSave,
            onDismiss = viewModel::dismissFutureConfirm,
        )
    }

    if (confirmDiscard) {
        TallyConfirmDialog(
            title = "这笔还没保存",
            message = "丢弃后金额与备注都不会写入账单。",
            confirmLabel = "丢弃",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }

    if (showTagSheet) {
        TagPickerSheet(
            // 归档标签也能出现在这里：编辑旧账单时要能把它取消掉，不然它再也摘不下来。
            tags = (state.tagChoices + state.selectedTags).distinctBy { it.id }.sortedBy { it.sortOrder },
            selectedIds = state.selectedTags.mapTo(HashSet()) { it.id },
            onToggle = { viewModel.toggleTag(it.id) },
            onCreate = viewModel::createTag,
            error = state.tagError,
            onDismiss = {
                showTagSheet = false
                viewModel.clearTagError()
            },
        )
    }

    if (showLedgerSheet) {
        LedgerPickerSheet(
            ledgers = state.ledgers,
            selectedId = state.ledgerId,
            onSelect = { viewModel.setLedger(it) },
            onDismiss = { showLedgerSheet = false },
        )
    }

    if (showDatePicker) {
        TallyDateTimePickerDialog(
            initialMillis = state.occurredAt,
            precision = state.timePrecision,
            onDismiss = { showDatePicker = false },
            onConfirm = { millis ->
                viewModel.setDate(millis)
                showDatePicker = false
            },
        )
    }
}

/** 顶栏：关闭 + 居中下划线 tab + 账本标签，没有大标题（设计板 .ab / .tabs）。 */
@Composable
private fun EntryHeader(
    editing: Boolean,
    type: BillType,
    onType: (BillType) -> Unit,
    ledgerName: String,
    onLedgerClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(44.dp).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        TallyIconAction(
            iconKey = if (editing) "back" else "close",
            contentDescription = "关闭",
            onClick = onDismiss,
        )
        Row(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        ) {
            UnderlineTab(label = "支出", selected = type == BillType.EXPENSE) { onType(BillType.EXPENSE) }
            UnderlineTab(label = "收入", selected = type == BillType.INCOME) { onType(BillType.INCOME) }
        }
        Box(modifier = Modifier.clickable(onClick = onLedgerClick)) {
            TallyTag(text = "$ledgerName ▾", tone = TagTone.OUTLINE)
        }
    }
}

/** .srcbar：金晕渐变条，图标 + 「识别方式 · 已填充」+ 原话 + 撤销。 */
@Composable
private fun SourceBar(
    iconKey: String,
    label: String,
    heard: String,
    onUndo: () -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp)
            .padding(top = 6.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(colors.goldHi.copy(alpha = 0.34f), colors.goldHi.copy(alpha = 0.12f)),
                ),
                shape,
            )
            .border(1.dp, colors.gold.copy(alpha = 0.3f), shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        InlineIcon(iconKey = iconKey, contentDescription = null, size = 13.dp, tint = colors.onGlow)
        Text(
            text = "$label  \"$heard\"",
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = colors.onGlow,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "撤销",
            fontSize = 11.sp, lineHeight = 13.8.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onGlow,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onUndo)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/** 下划线 tab：选中态是墨色文字 + 2.5dp 墨条，比文字左右各宽 1dp（.tabs a.on::after）。 */
@Composable
private fun UnderlineTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 16.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.ink else colors.ink3,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 1.dp)
                .drawBehind {
                    if (!selected) return@drawBehind
                    val barHeight = 3.dp.toPx()
                    val gap = 2.dp.toPx()
                    val inset = 1.dp.toPx()
                    drawRoundRect(
                        color = colors.ink,
                        topLeft = Offset(-inset, size.height + gap - barHeight),
                        size = Size(size.width + inset * 2, barHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                },
        )
    }
}

/** dock 第一行：左边备注直接输入；右边算式行在上，大字永远是待保存的结果。 */
@Composable
private fun NoteAmountLine(
    note: String,
    onNoteChange: (String) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    amountText: String,
    isIncome: Boolean,
    hasOperator: Boolean,
    resultText: String?,
    expressionPending: Boolean,
    negative: Boolean,
) {
    val colors = tallyColors
    val keyboard = LocalSoftwareKeyboardController.current
    val amountColor = when {
        amountText.isEmpty() -> colors.ink3
        negative -> colors.expense
        isIncome -> colors.income
        else -> colors.expense
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InlineIcon(iconKey = "note", contentDescription = null, size = 12.dp, tint = colors.ink3)
            Box(modifier = Modifier.weight(1f)) {
                if (note.isEmpty()) {
                    Text("备注", fontSize = 12.sp, lineHeight = 15.sp, color = colors.ink3, maxLines = 1)
                }
                BasicTextField(
                    value = note,
                    onValueChange = { if (it.length <= 60) onNoteChange(it) },
                    textStyle = TextStyle(fontSize = 12.sp, color = colors.ink, fontWeight = FontWeight.Medium),
                    // 金额已输，键盘那颗「完成」就是右下那枚保存键（同一个 requestSave，新建走「再记」、编辑走「保存」）；
                    // 还没输金额时只收起，免得撞出一条「还不能保存」。
                    // 中文输入法第一下回车是上屏候选词、第二下才是完成，这是 IME 自己的规定，不做抢跳。
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboard?.hide()
                            if (canSave) onSave()
                        },
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (note.isNotEmpty()) {
                Text(
                    text = "${note.length}/60",
                    fontSize = 10.sp, lineHeight = 12.5.sp,
                    color = colors.ink3,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (hasOperator) {
            val total = resultText ?: "0.00"
            Column(
                modifier = Modifier.padding(bottom = 1.dp),
                horizontalAlignment = Alignment.End,
            ) {
                // 算式行解释过程，大字只给结果：输 12 时大字已经是 30.00，不会出现「输 12 存 30」的意外。
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 1.dp),
                ) {
                    Text(
                        text = formatExpression(amountText),
                        style = TallyType.moneyBody.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        fontWeight = FontWeight.Bold,
                        color = if (negative) colors.expense else colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    if (!expressionPending) {
                        Text(
                            text = "＝ $total",
                            style = TallyType.moneyBody.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (negative) colors.expense else colors.brand,
                            maxLines = 1,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "¥",
                        style = TallyType.moneyField.copy(fontSize = 14.sp, lineHeight = 17.5.sp),
                        color = amountColor.copy(alpha = 0.55f),
                    )
                    Text(
                        text = integerPart(total),
                        style = TallyType.moneyField.copy(fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
                        color = amountColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                    Text(
                        text = decimalPart(total),
                        style = TallyType.moneyField.copy(fontSize = 18.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.SemiBold),
                        color = amountColor.copy(alpha = 0.72f),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(bottom = 3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "¥",
                    style = TallyType.moneyField.copy(fontSize = 14.sp, lineHeight = 17.5.sp),
                    color = amountColor.copy(alpha = 0.55f),
                )
                Text(
                    text = integerPart(amountText),
                    style = TallyType.moneyField.copy(fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
                    color = amountColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (amountText.isEmpty() || amountText.contains('.')) {
                    Text(
                        text = decimalPart(amountText),
                        style = TallyType.moneyField.copy(fontSize = 18.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.SemiBold),
                        color = amountColor.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

private fun integerPart(amountText: String): String =
    if (amountText.isEmpty()) "0" else amountText.substringBefore('.').ifEmpty { "0" }

private fun decimalPart(amountText: String): String = when {
    amountText.isEmpty() -> ".00"
    !amountText.contains('.') -> ""
    else -> "." + amountText.substringAfter('.')
}

/** 键盘结果统一两位小数、不分组，负数用真正的减号。 */
private fun formatKeypadResult(minor: Long): String =
    formatAmount(minor, grouping = false).replace('-', '−')

/** 18+12.5− → 「18.00 + 12.50 −」，右操作数没输完时保留末尾运算符。 */
private fun formatExpression(raw: String): String {
    val out = StringBuilder()
    val term = StringBuilder()
    fun flush() {
        if (term.isEmpty()) return
        val minor = parseAmount(term.toString())
        out.append(if (minor != null) formatKeypadResult(minor) else term)
        term.clear()
    }
    raw.forEach { ch ->
        if (ch == '+' || ch == '−') {
            flush()
            out.append(' ').append(ch).append(' ')
        } else {
            term.append(ch)
        }
    }
    flush()
    return out.toString().trimEnd()
}

/** 「已存 ¥…」气泡浮在 dock 上方，不占位、不常驻（P03·③）。 */
@Composable
private fun SessionToast(text: String, modifier: Modifier = Modifier) {
    val colors = tallyColors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.ink.copy(alpha = 0.86f))
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(colors.goldHi))
        Text(text, fontSize = 11.sp, lineHeight = 13.8.sp, color = colors.paper, maxLines = 1)
    }
}


/** chip 上的短日期：不是今天就写 10-14，省得把 dock 挤宽。 */
private fun chipDate(epochMillis: Long): String {
    val date = millisToLocalDate(epochMillis)
    return "${date.monthValue.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
}
