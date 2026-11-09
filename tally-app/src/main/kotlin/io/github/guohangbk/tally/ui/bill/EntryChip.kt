package io.github.guohangbk.tally.ui.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 记账页 dock 第二行的那枚胶囊：日期 / 图片 / 报销 / 排除（.chips）。
 * 状态一律靠「点亮」表达：开是墨绿实底配骨白字，关是白底描边。
 * 早先报销与排除还各挂一枚 20×12 的迷你拨钮，在 26dp 高的胶囊里既难按，
 * 也和「点一下就是开、再点一下就是关」这件事重复了一遍，已撤掉（2026-11-09 走查）。
 *
 * 放在这里而不是留在记账页：设置 → 记账键盘要在页顶摆一排同样的胶囊做示例，
 * 示例和真身上必须是同一份画法，不然改了一边另一边就开始漂。
 */
internal enum class ChipState { OFF, ON }

@Composable
internal fun EntryChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: ChipState = ChipState.OFF,
    count: Int = 0,
    enabled: Boolean = true,
    /** 示例态：只画样子、不响应点击（设置页的 dock 预览用），观感仍是正常的点亮状态。 */
    interactive: Boolean = true,
    /** 右上角的移除叉。给「点了会跳到别的界面、因此没法再点一下取消」的 chip 用（眼下只有图片）。 */
    onClear: (() -> Unit)? = null,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(13.dp)
    // 点亮态是实心墨绿底 + 骨白字（深色档反过来）。
    // 原来用的是 brandSurface（墨绿叠 8% 的淡底）配 brand 字，底色跟卡片白几乎一样、
    // 字也不过是从深绿到深绿，点开与没点开只差一点点（2026-11-09 走查：太不明显）。
    val foreground = when (state) {
        ChipState.OFF -> colors.ink2
        ChipState.ON -> colors.onBrand
    }
    val background = when (state) {
        ChipState.OFF -> colors.card
        ChipState.ON -> colors.brand
    }
    val borderColor = when (state) {
        ChipState.OFF -> colors.line
        ChipState.ON -> colors.brand
    }
    val alpha = if (enabled) 1f else colors.disabledAlpha
    // 外层不裁：右上角的叉要压着胶囊角露出去一点，裁掉就只剩半个圆。
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(26.dp)
                .clip(shape)
                // copy(alpha=) 会覆盖底色自带的透明度，这里要乘上去，禁用态才不会被压成实心。
                .background(background.copy(alpha = background.alpha * alpha))
                .border(1.dp, borderColor.copy(alpha = borderColor.alpha * alpha), shape)
                .clickable(enabled = enabled && interactive, onClick = onClick)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                fontSize = 11.5.sp, lineHeight = 14.4.sp,
                fontWeight = if (state == ChipState.OFF) FontWeight.Normal else FontWeight.Bold,
                color = foreground.copy(alpha = foreground.alpha * alpha),
                maxLines = 1,
            )
            if (count > 0) {
                Text(
                    text = count.toString(),
                    fontSize = 10.sp, lineHeight = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = foreground.copy(alpha = 0.75f * alpha),
                    maxLines = 1,
                )
            }
        }
        if (onClear != null && interactive) {
            // 圆心落在胶囊右上角：可点范围 24dp，看得见的圆只有 13dp，够点又不喧宾夺主。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 11.dp, y = (-11).dp)
                    .size(24.dp)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(colors.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    InlineIcon(iconKey = "close", contentDescription = "移除图片", size = 8.dp, tint = colors.card)
                }
            }
        }
    }
}
