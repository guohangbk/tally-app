package io.github.guohangbk.tally.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 弹层里的紧凑输入行：33dp 高、10dp 圆角，替代 Material OutlinedTextField。
 */
@Composable
fun TallyDialogField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String = "",
) {
    val colors = tallyColors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(if (focused) colors.card else colors.paper)
            .border(1.dp, if (focused) colors.gold else colors.line, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(label, fontSize = 12.sp, lineHeight = 15.sp, color = colors.ink3, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = colors.ink, fontWeight = FontWeight.Medium),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brand),
                visualTransformation = if (password) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (password) {
                        androidx.compose.ui.text.input.KeyboardType.Password
                    } else {
                        androidx.compose.ui.text.input.KeyboardType.Text
                    },
                ),
                modifier = Modifier.fillMaxWidth().onFocusEvent { focused = it.isFocused },
            )
        }
    }
}

/**
 * 规则表单里的纸面输入框：Material OutlinedTextField 的配色和留白与设计板对不上，
 * 周期与分期两处新建共用这一份。
 */
@Composable
fun TallyFormInput(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    numeric: Boolean = false,
) {
    val colors = tallyColors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    // 数字框沿用金额字面；名称这类文字输入必须是正文字面，不然中文会带衬线。
    val inputStyle = if (numeric) {
        TallyType.moneyList.copy(fontSize = 15.sp, lineHeight = 18.8.sp)
    } else {
        TallyType.listTitle.copy(lineHeight = 18.8.sp)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(1.dp, if (focused) colors.gold else colors.line, shape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 10.sp, lineHeight = 12.5.sp, letterSpacing = 0.06.em, color = colors.ink3)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (prefix != null) {
                Text(prefix, style = inputStyle, color = colors.ink3, modifier = Modifier.padding(end = 5.dp))
            }
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = inputStyle, color = colors.ink3)
                BasicTextField(
                    value = value,
                    onValueChange = { next -> onValueChange(if (numeric) next.filter { it.isDigit() || it == '.' } else next) },
                    singleLine = true,
                    textStyle = inputStyle.copy(color = colors.ink, fontWeight = FontWeight.Medium),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brand),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusEvent { focused = it.isFocused },
                )
            }
        }
    }
}
