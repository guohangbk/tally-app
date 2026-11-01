package io.github.guohangbk.tally.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.IconLibrary
import io.github.guohangbk.tally.ui.design.IconMeta
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallySwitch
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

private val EMOJI_FALLBACK = listOf("🍜", "🚕", "🎮", "🎁", "💡", "🐟", "🏠", "🚲", "💄", "🎬", "☕", "🧧")

/**
 * P08g / P08h / P08i 图标库全屏页。
 * 「改在弹层，建在全屏库」——建分类要同时装下名称、批量和上百个带名字的图标，弹层装不下。
 */
@Composable
fun IconLibraryPage(
    mode: IconLibraryMode,
    onBack: () -> Unit,
    onSaved: (String?) -> Unit,
) {
    val graph = rememberAppGraph()
    val key = when (mode) {
        is IconLibraryMode.NewLevel1 -> "icon-lib-l1-${mode.type.name}"
        is IconLibraryMode.NewLevel2 -> "icon-lib-l2-${mode.parentId}"
        is IconLibraryMode.EditIcon -> "icon-lib-edit-${mode.categoryId}"
        IconLibraryMode.LedgerIcon -> "icon-lib-ledger"
    }
    val viewModel = tallyViewModel(key = key) { IconLibraryViewModel(graph, mode) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = state.title,
            onBack = onBack,
            trailing = {
                TallyButton(
                    label = if (state.batch) "保存 ${state.creatableCount} 个" else "保存",
                    onClick = { viewModel.save(onSaved) },
                    variant = ButtonVariant.BRAND,
                    enabled = if (state.ledgerIcon) state.selectedIcon != null else state.canSave,
                    height = 32,
                    modifier = Modifier.width(96.dp),
                )
            },
        )

        // 提示改成贴底气泡；错误也走同一条通道，清空用的是同一个 clearNotice。
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "还不能保存",
            onNoticeShown = viewModel::clearNotice,
            onErrorShown = viewModel::clearNotice,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 15.dp),
        ) {
            if (!state.loaded) {
                Text("正在准备图标库…", style = TallyType.helper, color = colors.ink3, modifier = Modifier.padding(top = 16.dp))
                return@Column
            }
            state.parentHint?.let { hint ->
                Text(hint, fontSize = 11.5.sp, lineHeight = 14.4.sp, color = colors.ink3)
                Spacer(Modifier.height(8.dp))
            }

            if (state.batch) {
                BatchNamesField(
                    value = state.batchText,
                    lines = state.batchLines,
                    onValueChange = viewModel::setBatchText,
                )
            } else if (!state.ledgerIcon) {
                TallyCard(corner = 14, contentPadding = 9.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        SelectedIconTile(iconKey = state.selectedIcon, label = state.name.ifBlank { "图标" })
                        NameField(
                            value = state.name,
                            onValueChange = viewModel::setName,
                            duplicate = state.selectedIcon != null && state.isNameInUse(state.name),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 批量是「一次建多个」，改一个已有分类时用不上，留着还会让人以为能批量改名；
            // 账本图标没有名称也没有批量，两种卡都不出现。
            if (!state.editing && !state.ledgerIcon) {
                TallyCard(corner = 14, contentPadding = 9.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("批量添加", style = TallyType.listTitle, color = colors.ink, modifier = Modifier.weight(1f))
                        Text("一次最多 20 个", fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.ink3)
                        Spacer(Modifier.width(8.dp))
                        TallySwitch(
                            checked = state.batch,
                            onCheckedChange = viewModel::setBatch,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (state.ledgerIcon) "账本图标" else "分类图标", style = TallyType.eyebrow, color = colors.gold)
                SearchField(value = state.query, onValueChange = viewModel::setQuery, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(colors.card)
                    .border(1.dp, colors.line, RoundedCornerShape(13.dp)),
            ) {
                if (state.query.isBlank()) {
                    GroupRail(selected = state.group, onSelect = viewModel::setGroup)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    if (state.results.isEmpty()) {
                        NoResultFallback(
                            name = if (state.batch) state.batchLines.firstOrNull()?.name ?: "" else state.name,
                            onFirstLetter = { key -> viewModel.selectFallbackKey(key) },
                            onEmoji = { emoji -> viewModel.selectFallbackKey(emoji) },
                        )
                    } else {
                        state.results.chunked(4).forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                row.forEach { meta ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        LibraryItem(
                                            meta = meta,
                                            selected = meta.key == state.selectedIcon,
                                            showGroupTag = state.query.isNotBlank(),
                                            onClick = { viewModel.selectIcon(meta) },
                                        )
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            PickBar(
                iconKey = state.selectedIcon,
                label = if (state.ledgerIcon) "账本" else if (state.batch) state.batchLines.joinToString(" · ") { it.name }.ifBlank { "未命名" } else state.name.ifBlank { "未命名" },
                status = when {
                    state.ledgerIcon -> if (state.selectedIcon == null) "还没选图标" else "选中后点右上角保存"
                    state.batch -> "创建 ${state.creatableCount} 个 · ${state.batchLines.count { it.duplicate }} 个跳过"
                    state.selectedIcon == null -> "还没选图标"
                    else -> "选中后点右上角保存"
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun GroupRail(selected: String, onSelect: (String) -> Unit) {
    val colors = tallyColors
    Column(
        modifier = Modifier
            .width(58.dp)
            .fillMaxHeight()
            .background(colors.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        IconLibrary.groupNames.forEach { group ->
            val active = group == selected
            Text(
                text = group,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                color = if (active) colors.ink else colors.ink2,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (active) colors.card else colors.paper)
                    .clickable { onSelect(group) }
                    .padding(vertical = 7.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun LibraryItem(
    meta: IconMeta,
    selected: Boolean,
    showGroupTag: Boolean,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(9.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(shape)
                .background(if (selected) colors.ink else colors.paper)
                .border(1.dp, if (selected) colors.ink else colors.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            CategoryGlyph(iconKey = meta.key, label = meta.displayName, size = 15.dp, tint = if (selected) colors.paper else colors.ink)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (showGroupTag) "${meta.displayName}·${meta.group}" else meta.displayName,
            fontSize = 10.5.sp,
            lineHeight = 12.sp,
            color = if (selected) colors.ink else colors.ink2,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SelectedIconTile(iconKey: String?, label: String) {
    val colors = tallyColors
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(shape)
            .background(if (iconKey == null) colors.paper else colors.ink)
            .border(1.dp, if (iconKey == null) colors.line else colors.ink, shape),
        contentAlignment = Alignment.Center,
    ) {
        CategoryGlyph(
            iconKey = iconKey ?: "plus",
            label = label,
            size = 17.dp,
            tint = if (iconKey == null) colors.ink3 else colors.paper,
        )
    }
}

@Composable
private fun RowScope.NameField(value: String, onValueChange: (String) -> Unit, duplicate: Boolean) {
    val colors = tallyColors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier = Modifier.weight(1f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(33.dp)
                .clip(shape)
                .background(colors.card)
                .border(1.dp, if (duplicate) colors.expense else if (focused) colors.gold else colors.line, shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && !focused) {
                    Text("分类名称", fontSize = 12.5.sp, lineHeight = 15.6.sp, color = colors.ink3)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = colors.ink, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusEvent { focused = it.hasFocus },
                )
            }
            if (value.isNotEmpty()) Text("${value.length}/12", fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.ink3)
        }
        if (duplicate) {
            Spacer(Modifier.height(3.dp))
            Text("已存在 · 换一个名字", fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.expense, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BatchNamesField(
    value: String,
    lines: List<BatchLine>,
    onValueChange: (String) -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.paper)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 12.5.sp, color = colors.ink, lineHeight = 20.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isBlank()) Text("每行一个名字", fontSize = 12.5.sp, lineHeight = 15.6.sp, color = colors.ink3)
        Spacer(Modifier.height(6.dp))
        lines.forEachIndexed { index, line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.ink3, modifier = Modifier.width(14.dp))
                Text(line.name, fontSize = 12.sp, lineHeight = 15.sp, color = if (line.duplicate) colors.ink3 else colors.ink, modifier = Modifier.weight(1f))
                if (line.duplicate) Text("已存在 · 跳过", fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.expense, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = tallyColors
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(iconKey = "search", contentDescription = null, size = 12.dp, tint = colors.ink3)
        Spacer(Modifier.width(5.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) Text("搜索图标或分组", fontSize = 11.5.sp, lineHeight = 14.4.sp, color = colors.ink3)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.5.sp, color = colors.ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            InlineIcon(iconKey = "close", contentDescription = "清空", size = 11.dp, tint = colors.ink3, modifier = Modifier.clickable { onValueChange("") })
        }
    }
}

/** 无结果时给两条出路，不空屏（AC-F7-5）。 */
@Composable
private fun NoResultFallback(
    name: String,
    onFirstLetter: (String) -> Unit,
    onEmoji: (String) -> Unit,
) {
    val colors = tallyColors
    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        Text("没有匹配的图标", fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val first = name.trim().firstOrNull()?.toString()
            TallyTag(
                text = if (first != null) "用首字「$first」兜底" else "先填名称再兜底",
                tone = TagTone.OUTLINE,
                modifier = Modifier.clickable(enabled = first != null) { first?.let(onFirstLetter) },
            )
            TallyTag(text = "用 emoji", tone = TagTone.OUTLINE)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            EMOJI_FALLBACK.take(6).forEach { emoji ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.paper)
                        .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                        .clickable { onEmoji(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 14.sp, lineHeight = 17.5.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 5.dp)) {
            EMOJI_FALLBACK.drop(6).forEach { emoji ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.paper)
                        .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                        .clickable { onEmoji(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 14.sp, lineHeight = 17.5.sp)
                }
            }
        }
    }
}

@Composable
private fun PickBar(iconKey: String?, label: String, status: String) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectedIconTile(iconKey = iconKey, label = label)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = TallyType.listTitle.copy(fontSize = 13.sp, lineHeight = 16.3.sp), color = colors.ink, maxLines = 1)
            Text(status, fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.ink3, maxLines = 1)
        }
    }
}

