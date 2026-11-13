package io.github.guohangbk.tally.ui.keyword

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.ai.KeywordRuleSnapshot
import io.github.guohangbk.tally.data.ai.KeywordSource
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.parser.BuiltinKeywordGroup
import io.github.guohangbk.tally.ui.common.CategoryPickerSheet
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.Hairline
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * KW-01 ~ KW-04：语音与截图共用的关键词表。
 *
 * 展示按分类成组、存储仍一词一行——组只是同一 `category_id` 下的词，表结构不动。
 * 内置词组只读，用户规则展开后能就地加词，改绑与删除都从组 / 词的 ⋯ 进。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun KeywordRuleScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { KeywordRuleViewModel(graph) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = tallyColors
    val snapshot = state.snapshot

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "关键词规则",
            onBack = onBack,
            trailing = {
                TallyIconAction(iconKey = "plus", contentDescription = "加词", onClick = viewModel::openNewGroup)
            },
        )
        TallyToastEffects(
            notice = state.notice,
            error = state.error.takeIf { !state.sheetOpen },
            errorTitle = "这条规则没有保存",
            onNoticeShown = viewModel::dismissNotice,
            onErrorShown = viewModel::dismissError,
            noticeActionLabel = "撤销".takeIf { state.undoIds.isNotEmpty() },
            onNoticeAction = viewModel::undoDelete,
            noticeDurationMillis = if (state.undoIds.isNotEmpty()) 8_000L else null,
        )

        KeywordSearchField(
            query = state.query,
            onQueryChange = viewModel::setQuery,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 4.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            if (!state.loaded) {
                Text(
                    text = "正在读规则…",
                    style = TallyType.helper,
                    color = colors.ink3,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }
            if (snapshot == null) return@Column

            val query = state.query.trim()
            val builtin = snapshot.builtin.filter { it.matches(query, snapshot) }
            BuiltinSection(
                groups = builtin,
                snapshot = snapshot,
                query = query,
                isExpanded = { categoryId -> state.isExpanded(groupKey(builtin = true, categoryId)) },
                onToggle = { categoryId -> viewModel.toggleGroup(builtin = true, categoryId) },
                onOpenGroup = { categoryId -> viewModel.toggleGroup(builtin = true, categoryId) },
            )

            val rules = snapshot.rules.filter { it.matches(query, snapshot) }
            TallySectionLabel(
                text = "我的规则（${if (query.isEmpty()) snapshot.rules.size else rules.size}条）",
            )
            if (snapshot.rules.isEmpty()) {
                TallyCard {
                    TallyListRow(
                        title = "还没有自己的规则",
                        subtitle = "点右上角 ＋ 选个分类，直接往里加词",
                        iconKey = "tag",
                    )
                }
            } else if (rules.isEmpty()) {
                NoMatchRow(text = "没有匹配的规则")
            } else {
                UserRuleSection(
                    groups = userGroups(rules, snapshot, state.adding?.categoryId),
                    snapshot = snapshot,
                    adding = state.adding,
                    searching = query.isNotEmpty(),
                    isExpanded = { categoryId -> state.isExpanded(groupKey(builtin = false, categoryId)) },
                    onToggle = { categoryId -> viewModel.toggleGroup(builtin = false, categoryId) },
                    onGroupMore = viewModel::openGroupActions,
                    onWord = viewModel::openWordActions,
                    onAdd = viewModel::startAdd,
                    onAddText = viewModel::setAddText,
                    onCancelAdd = viewModel::cancelAdd,
                    onSaveAdd = viewModel::saveAdd,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    state.groupSheet?.let { categoryId ->
        KeywordGroupSheet(
            categoryId = categoryId,
            snapshot = snapshot,
            onRebind = { viewModel.openGroupRebind(categoryId) },
            onDelete = { viewModel.requestGroupDelete(categoryId) },
            onDismiss = viewModel::closeGroupActions,
        )
    }

    state.pendingGroupDelete?.let { categoryId ->
        val path = snapshot?.categoryPath(categoryId) ?: "这一组"
        val count = snapshot?.rules.orEmpty().count { it.categoryId == categoryId }
        TallyConfirmDialog(
            title = "删除「$path」分类下的 $count 个词？",
            message = "这些词不再参与自动分类，已经记下的账单不受影响；删除后 8 秒内可以在提示上撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = viewModel::confirmGroupDelete,
            onDismiss = viewModel::cancelGroupDelete,
        )
    }

    state.wordSheet?.let { rule ->
        KeywordWordSheet(
            rule = rule,
            snapshot = snapshot,
            onRebind = { viewModel.openWordRebind(rule) },
            onDelete = { viewModel.deleteWord(rule) },
            onDismiss = viewModel::closeWordActions,
        )
    }

    state.picker?.let { picker ->
        val categories = snapshot?.activeCategories.orEmpty()
        val currentId = when (picker) {
            KeywordPicker.NewGroup -> null
            is KeywordPicker.GroupRebind -> picker.categoryId
            is KeywordPicker.WordRebind -> picker.rule.categoryId
        }
        val current = currentId?.let { id -> categories.firstOrNull { it.id == id } }
        CategoryPickerSheet(
            categories = categories,
            selectedL1 = current?.let { it.parentId ?: it.id },
            selectedL2 = current?.takeIf { it.parentId != null }?.id,
            onDismiss = viewModel::closePicker,
            onConfirm = viewModel::pickCategory,
        )
    }
}

/** 用户组：按 categoryId 归并，外加「刚选好分类、还没词」的那一组。 */
private data class UserWordGroup(
    val categoryId: String,
    val words: List<KeywordRuleEntity>,
)

private fun userGroups(
    rules: List<KeywordRuleEntity>,
    snapshot: KeywordRuleSnapshot,
    addingCategoryId: String?,
): List<UserWordGroup> {
    val groups = rules.groupBy { it.categoryId }
        .map { (categoryId, words) -> UserWordGroup(categoryId, words.sortedBy { it.keyword }) }
        .toMutableList()
    if (addingCategoryId != null && groups.none { it.categoryId == addingCategoryId }) {
        groups += UserWordGroup(addingCategoryId, emptyList())
    }
    return groups.sortedWith(
        compareBy({ snapshot.categoryPath(it.categoryId) == null }, { snapshot.categoryPath(it.categoryId) ?: "" }),
    )
}

@Composable
private fun BuiltinSection(
    groups: List<BuiltinKeywordGroup>,
    snapshot: KeywordRuleSnapshot,
    query: String,
    isExpanded: (String) -> Boolean,
    onToggle: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    TallySectionLabel(text = "内置词组（只读）")
    if (groups.isEmpty()) {
        NoMatchRow(text = "没有匹配的内置词组")
        return
    }
    // 默认只交出一屏，见 FIRST_PAGE：首帧要装下几十行的图标绘制，转场会被吃掉。
    val shown = if (showAll) groups else groups.take(FIRST_PAGE)
    val more = groups.size - shown.size
    TallyCard {
        Column {
            shown.forEachIndexed { index, group ->
                val category = group.categoryId?.let(snapshot::category)
                val resolved = group.categoryId != null && category != null
                val path = group.categoryId?.let(snapshot::categoryPath)
                val key = group.categoryId.orEmpty()
                val open = resolved && (isExpanded(key) || query.isNotEmpty())
                TallyListRow(
                    title = path ?: "该组当前失效",
                    subtitle = if (resolved) {
                        group.sampleWords()
                    } else {
                        "原分类已删 · 该组失效"
                    },
                    iconKey = if (resolved) category?.iconKey ?: "tag" else "warn",
                    iconTone = if (resolved) IconTone.PAPER else IconTone.EXPENSE,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (resolved) WordCount(group.words.size)
                            InlineIcon(
                                iconKey = if (open) "chevron_down" else "chevron",
                                contentDescription = null,
                                size = 13.dp,
                                tint = tallyColors.ink3,
                            )
                        }
                    },
                    // 失效组没有可展开的词，点它只做一个「什么也不会发生」的点击很怪，于是不给 onClick。
                    onClick = if (resolved) ({ onOpenGroup(key) }) else null,
                )
                if (open) {
                    WordChips(words = group.words, learned = emptySet())
                }
                if (index != shown.lastIndex || more > 0) Hairline()
            }
            if (more > 0) ShowAllRow(count = groups.size, unit = "组", onExpand = { showAll = true })
        }
    }
}

@Composable
private fun UserRuleSection(
    groups: List<UserWordGroup>,
    snapshot: KeywordRuleSnapshot,
    adding: AddWordsState?,
    searching: Boolean,
    isExpanded: (String) -> Boolean,
    onToggle: (String) -> Unit,
    onGroupMore: (String) -> Unit,
    onWord: (KeywordRuleEntity) -> Unit,
    onAdd: (String) -> Unit,
    onAddText: (String) -> Unit,
    onCancelAdd: () -> Unit,
    onSaveAdd: () -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll || searching) groups else groups.take(FIRST_PAGE)
    val more = groups.size - shown.size
    TallyCard {
        Column {
            shown.forEachIndexed { index, group ->
                val category = snapshot.category(group.categoryId)
                val path = snapshot.categoryPath(group.categoryId)
                val open = isExpanded(group.categoryId) || searching
                TallyListRow(
                    title = path ?: "原分类已删",
                    subtitle = if (group.words.isEmpty()) "还没有词" else group.sampleWords(),
                    iconKey = category?.iconKey ?: "tag",
                    iconTone = if (category == null) IconTone.EXPENSE else IconTone.PAPER,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (group.words.isNotEmpty()) WordCount(group.words.size)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onGroupMore(group.categoryId) }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                InlineIcon(
                                    iconKey = "more",
                                    contentDescription = "这一组的动作",
                                    size = 16.dp,
                                    tint = tallyColors.ink3,
                                )
                            }
                            InlineIcon(
                                iconKey = if (open) "chevron_down" else "chevron",
                                contentDescription = null,
                                size = 13.dp,
                                tint = tallyColors.ink3,
                            )
                        }
                    },
                    onClick = { onToggle(group.categoryId) },
                )
                if (open) {
                    WordChips(
                        words = group.words.map { it.keyword },
                        learned = group.words.filter { it.source == KeywordSource.LEARNED }.map { it.keyword }.toSet(),
                        onWord = { keyword -> group.words.firstOrNull { it.keyword == keyword }?.let(onWord) },
                        onAdd = if (searching) null else ({ onAdd(group.categoryId) }),
                    )
                    adding?.takeIf { it.categoryId == group.categoryId }?.let { draft ->
                        InlineAddWords(
                            draft = draft,
                            onText = onAddText,
                            onCancel = onCancelAdd,
                            onSave = onSaveAdd,
                        )
                    }
                }
                if (index != shown.lastIndex || more > 0) Hairline()
            }
            if (more > 0) ShowAllRow(count = groups.size, unit = "组", onExpand = { showAll = true })
        }
    }
}

/** 组内词瓦片：一行平铺，不缩进成树；末位虚线「＋ 加词」直接往这一组加。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun WordChips(
    words: List<String>,
    learned: Set<String>,
    onWord: ((String) -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    val colors = tallyColors
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 45.dp, end = 4.dp, top = 1.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        words.forEach { word ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.paper)
                    .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                    .then(if (onWord != null) Modifier.clickable { onWord(word) } else Modifier)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (word in learned) {
                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(colors.gold))
                }
                Text(word, fontSize = 11.5.sp, lineHeight = 14.sp, color = colors.ink2, maxLines = 1)
            }
        }
        if (onAdd != null) AddWordChip(onClick = onAdd)
    }
}

@Composable
private fun AddWordChip(onClick: () -> Unit) {
    val colors = tallyColors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .drawBehind {
                drawRoundRect(
                    color = colors.ink3.copy(alpha = 0.55f),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("＋ 加词", fontSize = 11.5.sp, lineHeight = 14.sp, color = colors.ink3)
    }
}

/** KW-02：加词不再开表单弹窗，就在这一组里写。 */
@Composable
private fun InlineAddWords(
    draft: AddWordsState,
    onText: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(10.dp)
    val focus = remember { FocusRequester() }
    LaunchedEffect(draft.categoryId) { focus.requestFocus() }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 45.dp, end = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .background(colors.card)
                .border(1.dp, colors.gold, shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (draft.text.isEmpty()) {
                    Text("空格分隔，可一次加多个", fontSize = 12.5.sp, lineHeight = 16.sp, color = colors.ink3, maxLines = 1)
                }
                BasicTextField(
                    value = draft.text,
                    onValueChange = onText,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = colors.ink),
                    cursorBrush = SolidColor(colors.brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        Text(
            text = draft.error ?: "空格或顿号分隔；重名的词保存时跳过",
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            color = if (draft.error != null) colors.expense else colors.ink3,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TallyButton(
                label = "取消",
                onClick = onCancel,
                variant = ButtonVariant.GHOST,
                height = 30,
                modifier = Modifier.width(72.dp),
            )
            TallyButton(
                label = if (draft.saving) "保存中…" else "保存",
                onClick = onSave,
                variant = ButtonVariant.BRAND,
                enabled = !draft.saving && draft.text.isNotBlank(),
                height = 30,
                modifier = Modifier.width(72.dp),
            )
        }
    }
}

/** 组 ⋯：只放整组动作，改绑与删除都问一次影响范围。 */
@Composable
private fun KeywordGroupSheet(
    categoryId: String,
    snapshot: KeywordRuleSnapshot?,
    onRebind: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    val category = snapshot?.category(categoryId)
    val path = snapshot?.categoryPath(categoryId) ?: "原分类已删"
    val count = snapshot?.rules?.count { it.categoryId == categoryId } ?: 0
    TallyBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(iconKey = category?.iconKey ?: "tag", label = path, size = 34.dp, corner = 10.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text("$path（$count 个词）", style = TallyType.sectionTitle, color = colors.ink)
                }
            }
            Spacer(Modifier.height(12.dp))
            TallyCard {
                Column {
                    TallyListRow(
                        title = "换分类",
                        subtitle = "这 $count 个词一起搬走",
                        iconKey = "switch",
                        trailing = { TallyRowChevron() },
                        onClick = onRebind,
                    )
                    Hairline()
                    TallyListRow(
                        title = "删除整组",
                        subtitle = "$count 个词一起删，8 秒内可撤销",
                        iconKey = "trash",
                        iconTone = IconTone.EXPENSE,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

/** KW-04：词级弹层只放动作，词本身已经在组里看得见。 */
@Composable
private fun KeywordWordSheet(
    rule: KeywordRuleEntity,
    snapshot: KeywordRuleSnapshot?,
    onRebind: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    val learned = rule.source == KeywordSource.LEARNED
    val category = snapshot?.category(rule.categoryId)
    val path = snapshot?.categoryPath(rule.categoryId) ?: "原分类已删"
    TallyBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(iconKey = "key", label = "关键词", size = 34.dp, corner = 10.dp)
                Text(
                    text = "关键词：${rule.keyword}",
                    style = TallyType.sectionTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (learned) TallyTag(text = "沉淀", tone = TagTone.GOLD)
            }
            Spacer(Modifier.height(12.dp))
            TallyCard {
                Column {
                    TallyListRow(
                        title = "改绑分类",
                        subtitle = path,
                        iconKey = category?.iconKey ?: "tag",
                        iconTone = if (category == null) IconTone.EXPENSE else IconTone.PAPER,
                        trailing = { TallyRowChevron() },
                        onClick = onRebind,
                    )
                    Hairline()
                    TallyListRow(
                        title = "删除这个词",
                        subtitle = "不影响同组的其他词",
                        iconKey = "trash",
                        iconTone = IconTone.EXPENSE,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun WordCount(count: Int) {
    Text(
        text = "$count 个词",
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        color = tallyColors.ink3,
    )
}

/**
 * 折叠时先交出几行。
 *
 * 取 12 是按帧预算算的，不是随手定的：一屏放得下十几行，而整表 38 行排在首帧里组合实测要
 * 300～495ms（成本几乎全在每枚矢量图标的首次绘制上，同样 38 行去掉图标只剩 101ms），
 * 推入转场只有 260ms，这一帧会把整个转场吃掉，看着就是「这一页没有动画，直接闪出来」。
 * 12 行约 100ms，转场跑得完，一屏也是满的。
 */
private const val FIRST_PAGE = 12

@Composable
private fun ShowAllRow(count: Int, unit: String, onExpand: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("展开全部 $count $unit", style = TallyType.helper, color = colors.brand)
        Spacer(Modifier.width(4.dp))
        InlineIcon(iconKey = "chevron_down", contentDescription = null, size = 13.dp, tint = colors.brand)
    }
}

@Composable
private fun NoMatchRow(text: String) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InlineIcon(iconKey = "search", contentDescription = null, size = 14.dp, tint = colors.ink3)
        Text(text, style = TallyType.helper, color = colors.ink3)
    }
}

@Composable
private fun KeywordSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(iconKey = "search", contentDescription = null, size = 13.dp, tint = colors.ink3)
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索关键词或分类", fontSize = 13.sp, lineHeight = 16.3.sp, color = colors.ink3, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = colors.ink),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            InlineIcon(
                iconKey = "close",
                contentDescription = "清空搜索",
                size = 12.dp,
                tint = colors.ink3,
                modifier = Modifier.clickable { onQueryChange("") },
            )
        }
    }
}

/** 内置词组只取前四个代表词做一行摘要，整表太长会把一屏挤成词云。 */
private fun BuiltinKeywordGroup.sampleWords(): String =
    if (words.size <= 4) words.joinToString(" · ")
    else words.take(4).joinToString(" · ") + "…"

/** 用户组同样只给前三个词做摘要。 */
private fun UserWordGroup.sampleWords(): String =
    if (words.size <= 3) words.joinToString(" · ") { it.keyword }
    else words.take(3).joinToString(" · ") { it.keyword } + "…"

/** 搜索同时看词与它落到的分类：记得住「打车」也想得起「交通」的人都能找到。 */
private fun BuiltinKeywordGroup.matches(query: String, snapshot: KeywordRuleSnapshot): Boolean {
    if (query.isEmpty()) return true
    if (words.any { it.contains(query, ignoreCase = true) }) return true
    return categoryId?.let(snapshot::categoryPath)?.contains(query, ignoreCase = true) == true
}

private fun KeywordRuleEntity.matches(query: String, snapshot: KeywordRuleSnapshot): Boolean {
    if (query.isEmpty()) return true
    if (keyword.contains(query, ignoreCase = true)) return true
    return snapshot.categoryPath(categoryId)?.contains(query, ignoreCase = true) == true
}
