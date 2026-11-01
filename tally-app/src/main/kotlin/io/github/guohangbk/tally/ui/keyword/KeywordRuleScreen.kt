package io.github.guohangbk.tally.ui.keyword

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.ai.KeywordRuleSnapshot
import io.github.guohangbk.tally.data.ai.KeywordSource
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.parser.BuiltinKeywordGroup
import io.github.guohangbk.tally.ui.common.CategoryPickerSheet
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.BottomSheetGrabber
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.Hairline
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * KW-01 / KW-02：语音与截图共用的关键词表。
 *
 * 内置词组只读——它们要绑的分类由默认分类树决定，树里没这个节点时该组直接标明失效，
 * 而不是悄悄不参与解析。用户规则可增、可改绑、可删；同名即同一条，
 * 改绑走同一张弹层，避免出现「同一个词两条规则」这种解析时无解的状态。
 */
@Composable
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
                TallyIconAction(iconKey = "plus", contentDescription = "新增规则", onClick = viewModel::openAdd)
            },
        )
        TallyToastEffects(
            notice = state.notice,
            error = state.error.takeIf { state.sheet == null && state.actionRule == null && state.pendingDelete == null },
            errorTitle = "这条规则没有保存",
            onNoticeShown = viewModel::dismissNotice,
            onErrorShown = viewModel::dismissError,
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
            } else if (snapshot != null) {
                val query = state.query.trim()
                val builtin = snapshot.builtin.filter { it.matches(query, snapshot) }
                BuiltinSection(groups = builtin, snapshot = snapshot)

                val rules = snapshot.rules.filter { it.matches(query, snapshot) }
                TallySectionLabel(
                    text = if (query.isEmpty()) "我的规则 · ${snapshot.rules.size} 条" else "我的规则 · ${rules.size} 条",
                )
                if (rules.isEmpty() && query.isNotEmpty()) {
                    NoMatchRow(text = "没有匹配的规则")
                } else {
                    UserRuleSection(rules = rules, snapshot = snapshot, onMore = viewModel::openActions)
                }

                // 全页唯一一句说明：两套词表谁优先，以及它们被谁读。
                Text(
                    text = "语音与截图都读这张表；我的规则优先于内置",
                    style = TallyType.helper,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 14.dp, bottom = 24.dp),
                )
            }
        }
    }

    state.sheet?.let { KeywordSheet(state = state, viewModel = viewModel) }

    state.actionRule?.let { rule ->
        KeywordActionSheet(
            rule = rule,
            onRebind = { viewModel.openRebind(rule) },
            onDelete = { viewModel.requestDelete(rule) },
            onDismiss = viewModel::closeActions,
        )
    }

    state.pendingDelete?.let { rule ->
        TallyConfirmDialog(
            title = "删除「${rule.keyword}」？",
            message = "删除后这句话不再自动落到「${state.snapshot?.categoryPath(rule.categoryId) ?: "原分类"}」，已经记下的账单不受影响。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun BuiltinSection(groups: List<BuiltinKeywordGroup>, snapshot: KeywordRuleSnapshot) {
    var opened by remember { mutableStateOf<BuiltinKeywordGroup?>(null) }
    var expanded by remember { mutableStateOf(false) }
    TallySectionLabel(text = "内置词组 · 只读")
    if (groups.isEmpty()) {
        NoMatchRow(text = "没有匹配的内置词组")
        return
    }
    // 默认只交出一屏，见 FIRST_PAGE。展开是一次有意的点击，那一下慢不慢都与转场无关了；
    // 与回收站默认折叠是同一个道理。
    val shown = if (expanded) groups else groups.take(FIRST_PAGE)
    val more = groups.size - shown.size
    TallyCard {
        Column {
            shown.forEachIndexed { index, group ->
                val category = group.categoryId?.let(snapshot::category)
                val resolved = group.categoryId != null && category != null
                TallyListRow(
                    title = group.sampleWords(),
                    subtitle = if (resolved) {
                        "→ ${group.categoryId?.let(snapshot::categoryPath) ?: category.name}"
                    } else {
                        "原分类已删 · 该组失效"
                    },
                    iconKey = if (resolved) category?.iconKey ?: "tag" else "warn",
                    iconTone = if (resolved) IconTone.PAPER else IconTone.EXPENSE,
                    trailing = { TallyTag(text = "内置", tone = TagTone.NEUTRAL) },
                    onClick = { opened = group },
                )
                if (index != shown.lastIndex || more > 0) Hairline()
            }
            if (more > 0) ShowAllRow(count = groups.size, unit = "组", onExpand = { expanded = true })
        }
    }

    // 行上只放前四个代表词，整表点开才看得到——一行挤二十个词会把这页变成词云。
    opened?.let { group ->
        val path = group.categoryId?.let(snapshot::categoryPath)
        TallyConfirmDialog(
            title = path ?: "该组当前失效",
            message = "${group.words.size} 个内置词",
            dismissLabel = "关闭",
            onDismiss = { opened = null },
            content = {
                Text(
                    text = group.words.joinToString(" · "),
                    fontSize = 13.5.sp,
                    lineHeight = 22.sp,
                    color = tallyColors.ink,
                )
            },
        )
    }
}

@Composable
private fun UserRuleSection(
    rules: List<KeywordRuleEntity>,
    snapshot: KeywordRuleSnapshot,
    onMore: (KeywordRuleEntity) -> Unit,
) {
    if (rules.isEmpty()) {
        TallyCard {
            TallyListRow(
                title = "还没有自己的规则",
                subtitle = "点右上角 ＋ 添加，或在记账页改分类时沉淀",
                iconKey = "tag",
            )
        }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) rules else rules.take(FIRST_PAGE)
    val more = rules.size - shown.size
    TallyCard {
        Column {
            shown.forEachIndexed { index, rule ->
                val category = snapshot.category(rule.categoryId)
                TallyListRow(
                    title = rule.keyword,
                    subtitle = "→ ${snapshot.categoryPath(rule.categoryId) ?: "原分类已删"}",
                    iconKey = category?.iconKey ?: "tag",
                    iconTone = if (category == null) IconTone.EXPENSE else IconTone.PAPER,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TallyTag(
                                text = if (rule.source == KeywordSource.LEARNED) "沉淀" else "手动",
                                tone = if (rule.source == KeywordSource.LEARNED) TagTone.GOLD else TagTone.NEUTRAL,
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onMore(rule) }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                InlineIcon(
                                    iconKey = "more",
                                    contentDescription = "改绑或删除「${rule.keyword}」",
                                    size = 16.dp,
                                    tint = tallyColors.ink3,
                                )
                            }
                        }
                    },
                )
                if (index != shown.lastIndex || more > 0) Hairline()
            }
            if (more > 0) ShowAllRow(count = rules.size, unit = "条", onExpand = { expanded = true })
        }
    }
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

/**
 * 折叠时那一下展开。放在卡片里当最后一行，不另起按钮：
 * 卡片本来就是一个整体，多一颗胶囊会把「这一组是一张表」读成「这里是几个控件」。
 */
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

/** 新增与改绑共用一张弹层：新增要填词，改绑只换分类——同一个词永远只有一条规则。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeywordSheet(state: KeywordRuleUiState, viewModel: KeywordRuleViewModel) {
    val sheet = state.sheet ?: return
    val colors = tallyColors
    val selected = state.sheetCategory
    val canSave = !state.saving && sheet.categoryId != null && sheet.keyword.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = viewModel::closeSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            BottomSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(iconKey = "tag", label = "关键词", size = 34.dp, corner = 11.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (sheet.editing) "改绑规则" else "新增规则",
                        style = TallyType.sectionTitle,
                        color = colors.ink,
                    )
                    Text(
                        text = if (sheet.editing) "换一个分类，词本身不变" else "说这句话时，自动记到哪个分类",
                        style = TallyType.helper,
                        color = colors.ink3,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (sheet.editing) {
                ReadonlyKeyword(text = sheet.keyword)
            } else {
                KeywordInput(value = sheet.keyword, onValueChange = viewModel::setKeyword)
            }

            Spacer(Modifier.height(10.dp))
            TallySegmented(
                options = listOf(CategoryType.EXPENSE to "支出", CategoryType.INCOME to "收入"),
                selected = sheet.type,
                onSelect = viewModel::setType,
            )

            TallySectionLabel(text = "绑定分类")
            TallyCard {
                TallyListRow(
                    title = state.sheetCategoryPath ?: "选择一个分类",
                    subtitle = if (selected?.parentId != null) "二级分类 · 更精确" else "一级或二级分类",
                    iconKey = selected?.iconKey ?: "plus",
                    iconTone = if (selected == null) IconTone.SLATE else IconTone.PAPER,
                    trailing = { TallyRowChevron() },
                    onClick = viewModel::openPicker,
                )
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = TallyType.helper,
                    color = colors.expense,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TallyButton(
                    label = "取消",
                    onClick = viewModel::closeSheet,
                    variant = ButtonVariant.GHOST,
                    height = 36,
                    modifier = Modifier.weight(1f),
                )
                TallyButton(
                    label = if (state.saving) "保存中…" else "保存",
                    onClick = viewModel::save,
                    variant = ButtonVariant.BRAND,
                    enabled = canSave,
                    height = 36,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (sheet.showPicker) {
        CategoryPickerSheet(
            categories = state.sheetCategories,
            selectedL1 = selected?.let { it.parentId ?: it.id },
            selectedL2 = selected?.takeIf { it.parentId != null }?.id,
            onDismiss = viewModel::closePicker,
            onConfirm = viewModel::pickCategory,
        )
    }
}

@Composable
private fun KeywordInput(value: String, onValueChange: (String) -> Unit) {
    val colors = tallyColors
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.gold, shape)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("例如：打车、生椰拿铁", fontSize = 14.sp, lineHeight = 17.5.sp, color = colors.ink3, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = colors.ink),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("${value.length}/${KEYWORD_MAX}", fontSize = 11.sp, lineHeight = 13.8.sp, color = colors.ink3)
    }
}

@Composable
private fun ReadonlyKeyword(text: String) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(11.dp))
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 14.sp, lineHeight = 17.5.sp, color = colors.ink, modifier = Modifier.weight(1f), maxLines = 1)
        TallyTag(text = "不可改", tone = TagTone.SLATE)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeywordActionSheet(
    rule: KeywordRuleEntity,
    onRebind: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            BottomSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Text("「${rule.keyword}」", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(8.dp))
            TallyCard {
                Column {
                    TallyListRow(
                        title = "改绑分类",
                        subtitle = "同一个词换到别的分类",
                        iconKey = "tag",
                        trailing = { TallyRowChevron() },
                        onClick = onRebind,
                    )
                    Hairline()
                    TallyListRow(
                        title = "删除规则",
                        iconKey = "warn",
                        iconTone = IconTone.EXPENSE,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

private const val KEYWORD_MAX = 20

/** 内置词组只取前四个代表词做一行标题，整表太长会把一屏挤成词云。 */
private fun BuiltinKeywordGroup.sampleWords(): String =
    if (words.size <= 4) words.joinToString(" · ")
    else words.take(4).joinToString(" · ") + "…"

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
