package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.ui.design.tallyColors

/** 键盘的角色：记账页要算式（＋/−），预算页只要数字——运算符在预算页灰置。 */
enum class KeypadRole { BILL, BUDGET }

internal enum class KeypadKeyRole { DIGIT, DELETE, OPERATOR, ACTION, SAVE }

/**
 * 按下这一格的触感。早先只有保存那一格会响，其余十五格按下去手指上什么都没有——
 * 记账键盘是全靠手感连打的，没反馈就不知道刚才那下有没有命中（2026-10-28 走查）。
 */
internal enum class KeypadTone { KEY, ACTION, CONFIRM }

/**
 * 一格的全部信息：标签、它在布局里的角色、按下去的触感。
 * 三个维度一起收在 [KeypadKey] 里，调用方与格子本身都不必再拿 label 反推它是什么（2026-10-28 重构建议）。
 */
internal data class KeypadKey(
    val label: String,
    val role: KeypadKeyRole = KeypadKeyRole.DIGIT,
    val tone: KeypadTone = KeypadTone.KEY,
)

/** 前三排：数字 + 右上三格退格 / 减 / 加。 */
internal val KeypadRows = listOf(
    listOf(KeypadKey("1"), KeypadKey("2"), KeypadKey("3"), KeypadKey("⌫", KeypadKeyRole.DELETE)),
    listOf(KeypadKey("4"), KeypadKey("5"), KeypadKey("6"), KeypadKey("−", KeypadKeyRole.OPERATOR)),
    listOf(KeypadKey("7"), KeypadKey("8"), KeypadKey("9"), KeypadKey("+", KeypadKeyRole.OPERATOR)),
)

private val KEY_HEIGHT = 42.dp
private val KEY_GAP = 5.dp

private val KeypadDigitStyle = TallyType.moneyBody.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
private val KeypadDeleteStyle = TallyType.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium)
private val KeypadOperatorStyle = TallyType.moneyBody.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
private val KeypadActionStyle = TallyType.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)

/**
 * 键盘：4×4 十六格，顺序 123⌫ / 456− / 789＋ / 再记0.保存。
 * 十六格同宽同高，保存固定在右下角；空金额时再记与保存同时置灰。
 * 长按 ⌫ 清空金额与算式，短按删一位。
 *
 * [KeypadRole.BUDGET] 下第四排左格换成「清空」，运算符整列灰置（预算没有算式需求），
 * 其余几何与配色与记账页完全一致——两处是同一个键盘。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TallyKeypad(
    role: KeypadRole = KeypadRole.BILL,
    onKey: (String) -> Unit,
    onClear: () -> Unit,
    onRepeat: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    onSave: () -> Unit,
    editing: Boolean = false,
    pendingOperator: String? = null,
    enabled: Boolean = true,
    saveEnabled: Boolean = true,
) {
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = KEY_GAP),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP),
    ) {
        KeypadRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEach { key ->
                    KeypadKeyCell(
                        key = key,
                        shape = shape,
                        active = key.role == KeypadKeyRole.OPERATOR && key.label == pendingOperator,
                        operatorEnabled = role == KeypadRole.BILL,
                        onClick = { onKey(key.label) },
                        onLongClick = if (key.role == KeypadKeyRole.DELETE) onClear else null,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            if (role == KeypadRole.BUDGET) {
                KeypadKeyCell(
                    key = KeypadKey("清空", KeypadKeyRole.ACTION, KeypadTone.ACTION),
                    shape = shape,
                    onClick = onClear,
                )
            } else {
                KeypadKeyCell(
                    key = KeypadKey(
                        label = if (editing) "放弃" else "再记",
                        role = KeypadKeyRole.ACTION,
                        tone = KeypadTone.ACTION,
                    ),
                    shape = shape,
                    enabled = if (editing) true else enabled,
                    onClick = if (editing) onDiscard ?: onClear else onRepeat ?: onClear,
                )
            }
            KeypadKeyCell(key = KeypadKey("0"), shape = shape, onClick = { onKey("0") })
            KeypadKeyCell(key = KeypadKey("."), shape = shape, onClick = { onKey(".") })
            KeypadKeyCell(
                key = KeypadKey("保存", KeypadKeyRole.SAVE, KeypadTone.CONFIRM),
                shape = shape,
                enabled = saveEnabled,
                onClick = onSave,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.KeypadKeyCell(
    key: KeypadKey,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    operatorEnabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    val effectiveEnabled = enabled && (operatorEnabled || key.role != KeypadKeyRole.OPERATOR)
    val alpha = if (effectiveEnabled) 1f else colors.disabledAlpha
    val background = when {
        key.role == KeypadKeyRole.SAVE -> colors.brand
        key.role == KeypadKeyRole.OPERATOR && active -> colors.brandSurface
        key.role == KeypadKeyRole.OPERATOR || key.role == KeypadKeyRole.DELETE -> colors.ink.copy(alpha = 0.045f)
        key.role == KeypadKeyRole.ACTION -> colors.paper
        else -> colors.card
    }
    val borderColor = when {
        key.role == KeypadKeyRole.SAVE -> colors.brand
        key.role == KeypadKeyRole.OPERATOR && active -> colors.brand.copy(alpha = 0.30f)
        key.role == KeypadKeyRole.OPERATOR || key.role == KeypadKeyRole.DELETE -> Color.Transparent
        else -> colors.line
    }
    val foreground = when {
        key.role == KeypadKeyRole.SAVE -> colors.onBrand
        key.role == KeypadKeyRole.OPERATOR && active -> colors.brand
        key.role == KeypadKeyRole.OPERATOR || key.role == KeypadKeyRole.DELETE -> colors.ink2
        else -> colors.ink
    }
    val style = when (key.role) {
        KeypadKeyRole.DELETE -> KeypadDeleteStyle
        KeypadKeyRole.OPERATOR -> KeypadOperatorStyle
        KeypadKeyRole.ACTION, KeypadKeyRole.SAVE -> KeypadActionStyle
        KeypadKeyRole.DIGIT -> KeypadDigitStyle
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(KEY_HEIGHT)
            .clip(shape)
            .background(background.copy(alpha = background.alpha * alpha))
            .border(1.dp, borderColor.copy(alpha = borderColor.alpha * alpha), shape)
            .combinedClickable(
                enabled = effectiveEnabled,
                onClick = {
                    // 十六格都有反馈：数字/退格/运算符走键盘轻点，动作键更轻一档，保存给一次「完成」。
                    when (key.tone) {
                        KeypadTone.KEY -> haptics.key()
                        KeypadTone.ACTION -> haptics.tap()
                        KeypadTone.CONFIRM -> haptics.confirm()
                    }
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptics.longPress()
                        it()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = key.label, style = style, color = foreground.copy(alpha = foreground.alpha * alpha), maxLines = 1)
    }
}
