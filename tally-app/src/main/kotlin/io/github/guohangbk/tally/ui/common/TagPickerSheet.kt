package io.github.guohangbk.tally.ui.common

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.data.db.dao.TagUsageRow
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/** 空词库时给的三枚示例：名称即全部，点一下即建即选。 */
private val SAMPLE_TAGS = listOf("🏠 装修", "✈️ 旅行", "🧾 出差")

/**
 * 标签胶囊：记账页已选行、列表行、筛选与选择器共用同一套外形。
 * 选中是品牌淡底 + 描边，未选是白底描边；点选、✕、拖把手都由调用方给。
 */
@Composable
fun TagPill(
    name: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    fontSize: TextUnit = 10.5.sp,
    verticalPadding: Dp = 3.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(50)
    val foreground = if (selected) colors.brand else colors.ink2
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.brand.copy(alpha = 0.1f) else colors.card)
            .border(1.dp, if (selected) colors.brand.copy(alpha = 0.45f) else colors.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke()
        Text(
            text = name,
            fontSize = fontSize,
            lineHeight = (fontSize.value * 1.2f).sp,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRemove != null) {
            InlineIcon(
                iconKey = "close",
                contentDescription = "取消 $name",
                size = 9.dp,
                tint = foreground.copy(alpha = 0.7f),
                modifier = Modifier.clickable(onClick = onRemove),
            )
        }
        trailing?.invoke()
    }
}

/**
 * 标签选择弹层（TG-03 / TG-04）：记账页与批量补标共用。
 *
 * 点选立刻改进草稿，没有「确定」；批量模式才在底部收一个「应用到 N 笔」，
 * 因为整批覆盖的语义要先看清已经勾了哪几枚。输入先模糊匹配，匹配不到才给新建。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(
    tags: List<TagUsageRow>,
    selectedIds: Set<String>,
    onToggle: (TagUsageRow) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
) {
    val colors = tallyColors
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val trimmed = query.trim()
    val selected = tags.filter { it.id in selectedIds }
    val recent = tags
        .filter { it.lastUsedAt != null }
        .sortedByDescending { it.lastUsedAt }
        .take(6)
    val matches = if (trimmed.isEmpty()) emptyList() else tags.filter { it.name.contains(trimmed, ignoreCase = true) }
    val exact = tags.any { it.name == trimmed }

    fun create(raw: String) {
        onCreate(raw)
        query = ""
    }

    TallyBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("标签", style = TallyType.sectionTitle, color = colors.ink)
                Spacer(Modifier.width(8.dp))
                Text("跨账本通用", style = TallyType.helper, color = colors.ink3, modifier = Modifier.weight(1f))
                InlineIcon(
                    iconKey = "close",
                    contentDescription = "关闭",
                    size = 14.dp,
                    tint = colors.ink3,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            Spacer(Modifier.height(9.dp))
            TagSearchField(
                value = query,
                onValueChange = { query = it },
                onCreate = { if (trimmed.isNotEmpty() && !exact) create(trimmed) },
                focusRequester = focusRequester,
            )
            if (error != null) {
                Spacer(Modifier.height(5.dp))
                Text(error, style = TallyType.helper, color = colors.expense)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                if (trimmed.isNotEmpty()) {
                    if (!exact) {
                        Section(label = "还没有这个标签", hint = "回车新建并选中") {
                            TagPill(
                                name = "＋ 建「$trimmed」",
                                onClick = { create(trimmed) },
                            )
                        }
                    }
                    if (matches.isNotEmpty()) {
                        Section(label = if (exact) "匹配" else "也许你想找") {
                            matches.forEach { tag ->
                                TagPill(
                                    name = tag.name,
                                    selected = tag.id in selectedIds,
                                    onClick = { onToggle(tag) },
                                )
                            }
                        }
                    }
                } else {
                    if (selected.isNotEmpty()) {
                        Section(label = "已选 ${selected.size} 枚") {
                            selected.forEach { tag ->
                                TagPill(
                                    name = tag.name,
                                    selected = true,
                                    onRemove = { onToggle(tag) },
                                )
                            }
                        }
                    }
                    if (tags.isEmpty()) {
                        Section(label = "还没有标签", hint = "点一下即建即选") {
                            SAMPLE_TAGS.forEach { sample ->
                                TagPill(name = sample, onClick = { create(sample) })
                            }
                        }
                    } else {
                        if (recent.isNotEmpty()) {
                            Section(label = "最近使用") {
                                recent.forEach { tag ->
                                    TagPill(
                                        name = tag.name,
                                        selected = tag.id in selectedIds,
                                        onClick = { onToggle(tag) },
                                    )
                                }
                            }
                        }
                        Section(label = "全部 ${tags.size}") {
                            tags.forEach { tag ->
                                TagPill(
                                    name = tag.name,
                                    selected = tag.id in selectedIds,
                                    onClick = { onToggle(tag) },
                                )
                            }
                            TagPill(name = "＋ 新建", onClick = { focusRequester.requestFocus() })
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InlineIcon(iconKey = "info", contentDescription = null, size = 12.dp, tint = colors.ink3)
                Spacer(Modifier.width(5.dp))
                Text("只改变看哪批账，不改任何合计", style = TallyType.helper, color = colors.ink3)
            }
            if (confirmLabel != null && onConfirm != null) {
                Spacer(Modifier.height(12.dp))
                TallyButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    variant = ButtonVariant.BRAND,
                    height = 34,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TagSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onCreate: () -> Unit,
    focusRequester: FocusRequester,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(colors.paper)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InlineIcon(iconKey = "search", contentDescription = null, size = 13.dp, tint = colors.ink3)
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("搜索或新建标签", style = TallyType.body, color = colors.ink3, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TallyType.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreate() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (value.isNotEmpty()) {
            InlineIcon(
                iconKey = "close",
                contentDescription = "清空",
                size = 11.dp,
                tint = colors.ink3,
                modifier = Modifier.clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun Section(
    label: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, lineHeight = 13.2.sp, fontWeight = FontWeight.Bold, color = colors.ink3)
            if (hint != null) {
                Spacer(Modifier.weight(1f))
                Text(hint, fontSize = 10.sp, lineHeight = 12.sp, color = colors.ink3.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}
