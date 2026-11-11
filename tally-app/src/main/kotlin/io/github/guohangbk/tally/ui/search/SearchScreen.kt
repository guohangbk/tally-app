package io.github.guohangbk.tally.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.bill.BillDetailSheet
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillFilter
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillSort
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.ui.common.BatchProgress
import io.github.guohangbk.tally.ui.common.BillBatchViewModel
import io.github.guohangbk.tally.ui.common.BillRow
import io.github.guohangbk.tally.ui.common.BillBatchOverlays
import io.github.guohangbk.tally.ui.common.SelectionBar
import io.github.guohangbk.tally.ui.common.formatDay
import io.github.guohangbk.tally.ui.common.startOfDay
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * P05 搜索账单：筛选并在这里，行组件、标记体系与选择态与首页完全共用一套。
 * 被排除的结果仍然出现在列表里，但不进合计（AC-F6-1 / AC-F6-2）。
 */
@Composable
fun SearchScreen(
    initialLedgerId: String? = null,
    initialCategoryL1Id: String? = null,
    initialCategoryL2Id: String? = null,
    initialTagIds: List<String> = emptyList(),
    onBack: () -> Unit,
    onOpenBillEdit: (String) -> Unit = {},
    onRepeatBill: (String?, String?, BillType?) -> Unit = { _, _, _ -> },
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel {
        SearchViewModel(graph, initialLedgerId, initialCategoryL1Id, initialCategoryL2Id, initialTagIds)
    }
    val batch = tallyViewModel(key = "search-batch") { BillBatchViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by batch.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    val filter = state.filter
    val summary = state.result.summary
    var detailBillId by remember { mutableStateOf<String?>(null) }
    /** 金额区间是 live 的：ViewModel 那条 160ms 防抖替它兜住逐字全表扫描，所以不再要「应用」按钮。 */
    var minText by remember { mutableStateOf("") }
    var maxText by remember { mutableStateOf("") }
    var filtersOpen by remember { mutableStateOf(false) }

    // 勾选态是页面内的一层：系统返回 / 侧滑先退出勾选，不该把人直接甩回上一页。
    BackHandler(enabled = selection.active) { batch.exitSelection() }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        if (selection.active) {
            SearchSelectionBar(
                count = selection.selected.size,
                onExit = batch::exitSelection,
                onSelectAll = { batch.selectAll(state.result.bills) },
            )
        } else {
            TallyTopBar(
                title = "搜索账单",
                onBack = onBack,
                trailing = { TallyTag(text = "${summary.billCount} 笔", tone = TagTone.OUTLINE) },
            )
        }

        // 批量操作的提示改成贴底气泡（「收入不参与报销」「已撤销」这类一次性的话）。
        TallyToastEffects(
            notice = selection.notice,
            error = null,
            onNoticeShown = batch::dismissNotice,
        )

        // 一次导入可能就是几千笔：整页 verticalScroll + 全部展开会直接卡死，
        // 筛选区作为第一个 item，结果按天分组进 LazyColumn，只组合看得见的部分。
        // 按金额排是排行榜，再按天分组会把序打乱，所以那种排法出一段平铺列表。
        val sections = remember(state.result.bills, filter.sort) {
            if (filter.sort.groupsByDay) {
                val order = if (filter.sort == BillSort.TIME_ASC) compareBy<Long> { it } else compareByDescending { it }
                state.result.bills.groupBy { startOfDay(it.bill.occurredAt) }
                    .toSortedMap(order)
                    .map { (day, bills) -> formatDay(day) to bills }
            } else {
                listOf(null to state.result.bills)
            }
        }
        val selectedIds = remember(selection.selected) { selection.selected.mapTo(HashSet()) { it.bill.id } }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 15.dp),
        ) {
            item(key = "filters") {
                Column {
                    Spacer(Modifier.height(6.dp))
                    KeywordField(value = filter.keyword, onValueChange = viewModel::setKeyword)
                    Spacer(Modifier.height(10.dp))
                    FilterLabel("收支类型")
                    TallySegmented(
                        options = listOf(null to "全部", BillType.EXPENSE to "支出", BillType.INCOME to "收入"),
                        selected = filter.type,
                        onSelect = viewModel::setType,
                    )
                    Spacer(Modifier.height(9.dp))
                    // 默认只摊开收支类型，其余六项收进「更多筛选」；收起时生效的条件原样写在开关下面，
                    // 藏在折叠里的条件会改合计，看不见比没有条件更危险。
                    val activeChips = activeFilterChips(viewModel, filter, state.categoryLabel)
                    // 账本与标签不写成 chip（标签在展开后的标签行里），但计数要算上它们，
                    // 否则收起时那枚「N 项」会少一条，看着像没筛。
                    val activeCount = activeChips.size +
                        (if (filter.ledgerId != null) 1 else 0) +
                        filter.tagIds.size
                    FilterToggle(filtersOpen, activeCount) { filtersOpen = !filtersOpen }
                    AnimatedVisibility(
                        visible = filtersOpen,
                        enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(170, easing = FastOutSlowInEasing)) + fadeOut(tween(110)),
                    ) {
                        Column {
                            Spacer(Modifier.height(9.dp))
                            FilterLabel("时间范围")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                FilterButton("全部", filter.startAt == null && filter.endAtExclusive == null, viewModel::setAllTime)
                                FilterButton("本月", viewModel.isThisMonth(), viewModel::setThisMonth)
                                FilterButton("近 30 天", viewModel.isLast30Days(), viewModel::setLast30Days)
                            }
                            // 分类筛选是从分类管理「查看统计数据」带进来的，这里只负责显示与放开。
                            state.categoryLabel?.let { label ->
                                Spacer(Modifier.height(9.dp))
                                FilterLabel("分类")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    FilterButton(label, true, viewModel::clearCategory)
                                    FilterButton("不限分类", false, viewModel::clearCategory)
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            FilterLabel("账本")
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilterButton("全部", filter.ledgerId == null, { viewModel.setLedger(null) })
                                state.ledgers.forEach { ledger ->
                                    FilterButton(ledger.name, filter.ledgerId == ledger.id, { viewModel.setLedger(ledger.id) })
                                }
                            }
                            // 标签排在账本之后：先定范围（时间、账本），再切维度；多选是并集。
                            if (state.tags.isNotEmpty()) {
                                Spacer(Modifier.height(9.dp))
                                FilterLabel("标签 · 多选＝含任一")
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    FilterButton("全部", filter.tagIds.isEmpty(), viewModel::clearTags)
                                    state.tags.forEach { tag ->
                                        FilterButton(
                                            // 归档标签只在被选中时出现在这里：标出来源，点一下即可摘掉。
                                            label = if (tag.archived) "${tag.name} · 已归档" else tag.name,
                                            selected = tag.id in filter.tagIds,
                                            onClick = { viewModel.toggleTag(tag.id) },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            FilterLabel("来源")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                FilterButton("全部", filter.source == null, { viewModel.setSource(null) })
                                FilterButton("手动", filter.source == BillSource.MANUAL, { viewModel.setSource(BillSource.MANUAL) })
                                FilterButton("导入", filter.source == BillSource.IMPORT, { viewModel.setSource(BillSource.IMPORT) })
                            }
                            Spacer(Modifier.height(9.dp))
                            FilterLabel("排序")
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BillSort.entries.forEach { sort ->
                                    FilterButton(sortLabel(sort), filter.sort == sort, { viewModel.setSort(sort) })
                                }
                            }
                            // 真实库里八成账单没有备注，关键词覆盖不到——金额区间是另一条筛路。
                            Spacer(Modifier.height(9.dp))
                            FilterLabel("金额区间（元）")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                AmountField("≥", minText, {
                                    minText = it
                                    viewModel.setAmountRange(it, maxText)
                                }, Modifier.weight(1f))
                                AmountField("≤", maxText, {
                                    maxText = it
                                    viewModel.setAmountRange(minText, it)
                                }, Modifier.weight(1f))
                            }
                        }
                    }
                    // 收起时把生效的条件摊出来，展开就缩回去——两处不能同时空着，也不能同时占两行。
                    AnimatedVisibility(
                        visible = !filtersOpen && activeChips.isNotEmpty(),
                        enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(170, easing = FastOutSlowInEasing)) + fadeOut(tween(110)),
                    ) {
                        Column {
                            Spacer(Modifier.height(7.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                activeChips.forEach { chip -> FilterButton(chip, true) { filtersOpen = true } }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TallyCard(corner = 16, contentPadding = 10.dp) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            SummaryCell("${summary.billCount}", "命中", colors.ink, Modifier.weight(1f))
                            SummaryCell(formatAmount(summary.expenseMinor + summary.incomeMinor), "合计", colors.expense, Modifier.weight(1f))
                            SummaryCell("${summary.excludedCount}", "已排除", colors.slate, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (state.result.bills.isEmpty()) {
                item(key = "empty") {
                    // 冷查询的那几帧不要直接说「未找到」：先闪一条空态再跳出数据，人就分不清是查空了还是没查完。
                    if (state.loaded) {
                        TallyEmptyState(title = "未找到匹配账单", description = "换个关键词或放宽筛选条件试试。", iconKey = "search")
                    } else {
                        Text("正在查…", style = TallyType.helper, color = colors.ink3, modifier = Modifier.padding(top = 18.dp))
                    }
                }
            } else if (filter.sort.groupsByDay) {
                sections.forEach { (label, bills) ->
                    item(key = "day-$label") {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text(
                                    text = label.orEmpty(),
                                    fontSize = 12.sp, lineHeight = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.ink2,
                                    maxLines = 1,
                                )
                                Box(Modifier.weight(1f).height(1.dp).background(colors.ink.copy(alpha = 0.055f)))
                                Text(
                                    text = "${bills.size} 笔",
                                    style = TallyType.moneyList.copy(fontSize = 11.5.sp, lineHeight = 14.4.sp),
                                    color = colors.ink3,
                                )
                            }
                            bills.forEachIndexed { index, item ->
                                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.045f)))
                                BillRow(
                                    item = item,
                                    grouping = true,
                                    showCheckbox = selection.active,
                                    selected = item.bill.id in selectedIds,
                                    onClick = { if (selection.active) batch.toggle(item) else detailBillId = item.bill.id },
                                    onLongClick = { batch.begin(item) },
                                )
                            }
                        }
                    }
                }
            } else {
                // 排行榜不分组：一笔一个 item，一千行也只组合看得见的部分。
                item(key = "flat-head") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = if (filter.sort == BillSort.AMOUNT_DESC) "金额最大" else "金额最小",
                            fontSize = 12.sp, lineHeight = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.ink2,
                        )
                        Box(Modifier.weight(1f).height(1.dp).background(colors.ink.copy(alpha = 0.055f)))
                        Text(
                            text = "${state.result.bills.size} 笔",
                            style = TallyType.moneyList.copy(fontSize = 11.5.sp, lineHeight = 14.4.sp),
                            color = colors.ink3,
                        )
                    }
                }
                itemsIndexed(state.result.bills, key = { _, item -> item.bill.id }) { index, item ->
                    Column {
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.045f)))
                        BillRow(
                            item = item,
                            grouping = true,
                            showCheckbox = selection.active,
                            selected = item.bill.id in selectedIds,
                            onClick = { if (selection.active) batch.toggle(item) else detailBillId = item.bill.id },
                            onLongClick = { batch.begin(item) },
                        )
                    }
                }
            }
            if (state.result.truncated) {
                item(key = "truncated") {
                    // 命中数是全量口径，列表只取最近一批，不写清楚会以为少算了。
                    Text(
                        text = "列出 ${state.result.bills.size} 笔 · 还有 ${(state.result.summary.billCount - state.result.bills.size).coerceAtLeast(0)} 条未列出，用日期、分类或金额收窄。",
                        style = TallyType.helper,
                        color = colors.ink3,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item(key = "tail") { Spacer(Modifier.height(20.dp)) }
        }

        selection.progress?.let { BatchProgress(it, modifier = Modifier.padding(horizontal = 15.dp, vertical = 6.dp)) }

        if (selection.active) {
            SelectionBar(
                count = selection.selected.size,
                amountMinor = selection.amountMinor,
                hint = if (selection.excludedCount > 0) "含 ${selection.excludedCount} 笔已排除" else null,
                categoryBlockedReason = selection.categoryBlockedReason,
                categoryEnabled = selection.categoryBlockedReason == null,
                moveEnabled = selection.canMove,
                onTags = batch::openTagPicker,
                onSelectCategory = batch::openCategoryPicker,
                onReimburse = { batch.reimburse(selection.selected.none { it.bill.reimbursed == ReimburseStatus.PENDING.name }) },
                onExclude = { batch.exclude(selection.selected.none { it.bill.excludedFromStats }) },
                onDelete = batch::delete,
                onDate = batch::openDatePicker,
                onNote = batch::openNoteEditor,
                onMove = batch::openLedgerPicker,
            )
        }
    }

    // 四件弹层与首页同一份实现，见 BillBatchOverlays。
    BillBatchOverlays(selection, batch)

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
}

@Composable
private fun SearchSelectionBar(count: Int, onExit: () -> Unit, onSelectAll: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
        TallyTag(text = "全选结果", tone = TagTone.OUTLINE, modifier = Modifier.clickable(onClick = onSelectAll))
    }
}

@Composable
private fun KeywordField(value: String, onValueChange: (String) -> Unit) {
    val colors = tallyColors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, if (focused) colors.gold else colors.line, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(iconKey = "search", contentDescription = null, size = 13.dp, tint = colors.ink3)
        Spacer(Modifier.width(7.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && !focused) Text("搜索备注、分类或标签…", style = TallyType.helper, color = colors.ink3)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = colors.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusEvent { focused = it.hasFocus },
            )
        }
        if (value.isNotEmpty()) {
            InlineIcon(iconKey = "close", contentDescription = "清空", size = 12.dp, tint = colors.ink3, modifier = Modifier.clickable { onValueChange("") })
        }
    }
}


@Composable
private fun SummaryCell(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = TallyType.moneyBody.copy(fontSize = 17.sp, lineHeight = 21.3.sp), color = color, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        // 显式行高：不写就继承 20sp 的行高，10.5sp 的字会在行框里沉下去，数字与标签之间空出一大截。
        Text(label, fontSize = 10.5.sp, lineHeight = 14.sp, color = tallyColors.ink3)
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(text, style = TallyType.helper, color = tallyColors.ink3, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.brandSurface else colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(label, style = TallyType.helper, color = if (selected) colors.brand else colors.ink2, maxLines = 1)
    }
}

/** 「更多筛选」开关：收起状态下把生效条数写在标签上，箭头随展开转 90°。 */
@Composable
private fun FilterToggle(open: Boolean, activeCount: Int, onToggle: () -> Unit) {
    val colors = tallyColors
    val arrowRotation by animateFloatAsState(if (open) 90f else 0f, label = "filterToggleArrow")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(iconKey = "filter", contentDescription = null, size = 13.dp, tint = colors.ink3)
        Spacer(Modifier.width(7.dp))
        Text(if (open) "收起筛选" else "更多筛选", style = TallyType.helper, color = colors.ink)
        if (!open && activeCount > 0) {
            Spacer(Modifier.width(6.dp))
            TallyTag(text = "$activeCount 项", tone = TagTone.OUTLINE)
        }
        Spacer(Modifier.weight(1f))
        InlineIcon(
            iconKey = "chevron",
            contentDescription = if (open) "收起筛选" else "展开筛选",
            size = 13.dp,
            tint = colors.ink3,
            modifier = Modifier.rotate(arrowRotation),
        )
    }
}

/** 金额区间的一个输入位：左侧一个 ≥ / ≤ 说明这框管哪一头，数字键盘直接给，改一下即生效。 */
@Composable
private fun AmountField(prefix: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = tallyColors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, if (focused) colors.gold else colors.line, shape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(prefix, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink3)
        Spacer(Modifier.width(5.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && !focused) Text("不限", style = TallyType.helper, color = colors.ink3)
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= 9 && it.all(Char::isDigit) || it == "") onValueChange(it) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = colors.ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().onFocusEvent { focused = it.hasFocus },
            )
        }
        if (value.isNotEmpty()) {
            InlineIcon(
                iconKey = "close",
                contentDescription = "清空",
                size = 12.dp,
                tint = colors.ink3,
                modifier = Modifier.clickable { onValueChange("") },
            )
        }
    }
}

/**
 * 折叠起来的条件里，哪几条正生效——原样写成 chip，收起状态也能一眼看完。
 *
 * 账本与标签不进这里：账本是跟着首页来的上下文；标签有自己的行，写在折叠框下面
 * 会像从别处漏出来的一枚，而且挑选/摘掉都得回展开态做。两者的生效条数由开关上的
 * 「N 项」承担，收起时仍知道筛过东西。
 */
private fun activeFilterChips(
    viewModel: SearchViewModel,
    filter: BillFilter,
    categoryLabel: String?,
): List<String> {
    val chips = mutableListOf<String>()
    if (filter.startAt != null || filter.endAtExclusive != null) {
        chips += when {
            viewModel.isThisMonth() -> "本月"
            viewModel.isLast30Days() -> "近 30 天"
            else -> "自定义时间"
        }
    }
    categoryLabel?.let { chips += it }
    filter.source?.let { chips += if (it == BillSource.IMPORT) "导入" else "手动" }
    if (filter.sort != BillSort.TIME_DESC) chips += "排序 · ${sortLabel(filter.sort)}"
    val min = filter.amountMinMinor?.let(::trimMoney)
    val max = filter.amountMaxMinor?.let(::trimMoney)
    when {
        min != null && max != null -> chips += "金额 $min–$max"
        min != null -> chips += "金额 ≥$min"
        max != null -> chips += "金额 ≤$max"
    }
    return chips
}

private fun sortLabel(sort: BillSort): String = when (sort) {
    BillSort.TIME_DESC -> "最新"
    BillSort.TIME_ASC -> "最早"
    BillSort.AMOUNT_DESC -> "金额大"
    BillSort.AMOUNT_ASC -> "金额小"
}

private fun trimMoney(minor: Long): String =
    if (minor % 100L == 0L) (minor / 100L).toString() else formatAmount(minor, grouping = false)
