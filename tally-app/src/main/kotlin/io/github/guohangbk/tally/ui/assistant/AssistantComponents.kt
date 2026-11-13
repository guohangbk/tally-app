package io.github.guohangbk.tally.ui.assistant

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 小莉页面的公共零件。金晕只允许出现在这里：入口、建议、状态点、光晕，
 * 其余界面保持纸墨基调（设计板 §01 / §03）。
 */

/** 卡片：默认描边带一点金，故障态转陶土，避免「坏了还装亮」。 */
@Composable
fun AiCard(
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    contentPadding: androidx.compose.ui.unit.Dp = 13.dp,
    content: @Composable () -> Unit,
) {
    val colors = tallyColors
    TallyCard(
        modifier = modifier,
        corner = 16,
        contentPadding = contentPadding,
        borderColor = if (glow) colors.gold.copy(alpha = 0.34f) else colors.expense.copy(alpha = 0.3f),
        content = content,
    )
}

/** 三层呼吸同心环 + 茉莉花形：聆听中与分析载入中共用同一段动效语言。 */
@Composable
fun AiBloom(
    active: Boolean,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 96.dp,
    gold: Boolean = true,
) {
    val colors = tallyColors
    // 没有小莉时这一屏连光晕都该是纸墨色：金晕只属于小莉相关的触点。
    val halo = if (gold) colors.glow else colors.brandSurface
    val line = if (gold) colors.gold else colors.brand
    val glyph = if (gold) colors.gold else colors.brand
    val transition = rememberInfiniteTransition(label = "bloom")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Restart),
        label = "phase",
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        val rings = listOf(1f, 0.72f, 0.46f)
        rings.forEachIndexed { index, scale ->
            val animated = if (active) scale - 0.06f * phase else scale
            Box(
                modifier = Modifier
                    .size(size * animated)
                    .clip(CircleShape)
                    .background(
                        when (index) {
                            0 -> halo.copy(alpha = if (active) 0.35f + 0.2f * phase else 0.18f)
                            1 -> halo.copy(alpha = if (active) 0.55f else 0.3f)
                            else -> colors.card
                        },
                    )
                    .border(1.dp, line.copy(alpha = 0.3f + 0.1f * index), CircleShape),
            )
        }
        InlineIcon(iconKey = "jasmine", contentDescription = null, size = size * 0.3f, tint = glyph)
    }
}

/** 带说明与错误提示的输入框，小莉设置与草稿页共用。 */
@Composable
fun AiField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    singleLine: Boolean = true,
    secret: Boolean = false,
    revealed: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = tallyColors
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = TallyType.eyebrow, color = colors.ink3)
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = singleLine,
                textStyle = TallyType.body.copy(color = colors.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.gold),
                visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = TallyType.body, color = colors.ink3.copy(alpha = 0.7f))
                        }
                        inner()
                    }
                },
            )
            if (trailing != null) {
                Spacer(Modifier.size(8.dp))
                trailing()
            }
        }
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, style = TallyType.helper, color = colors.ink3)
        }
    }
}

/** 下拉式的配置行：服务商、模型都用它，避免把只能从列表里选的值做成自由输入框。 */
@Composable
fun AiSelectRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = TallyType.eyebrow, color = colors.ink3)
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .clickable {
                    haptics.tap()
                    onClick()
                }
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.ifBlank { placeholder },
                style = TallyType.body,
                color = if (value.isBlank()) colors.ink3.copy(alpha = 0.7f) else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Spacer(Modifier.size(8.dp))
                trailing()
            }
            Spacer(Modifier.size(6.dp))
            InlineIcon(iconKey = "chevron", contentDescription = null, size = 13.dp, tint = colors.ink3)
        }
    }
}

@Composable
fun AiCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) colors.glow else colors.card)
            .border(1.dp, if (checked) colors.gold.copy(alpha = 0.45f) else colors.line, RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) colors.gold else colors.card)
                .border(1.dp, if (checked) colors.gold else colors.ink3, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) InlineIcon("check", contentDescription = null, size = 11.dp, tint = colors.paper)
        }
        Spacer(Modifier.size(9.dp))
        Text(text, style = TallyType.helper, color = colors.ink2, lineHeight = 17.sp)
    }
}

/** 一行「键 · 值」的说明，配置卡与发送前预览都用它。 */
@Composable
fun AiMetaLine(label: String, value: String, color: Color? = null) {
    val colors = tallyColors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = TallyType.helper, color = colors.ink3)
        Spacer(Modifier.size(6.dp))
        Text(
            text = value,
            style = TallyType.helper,
            color = color ?: colors.ink2,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private const val BREATH_MS = 2400
