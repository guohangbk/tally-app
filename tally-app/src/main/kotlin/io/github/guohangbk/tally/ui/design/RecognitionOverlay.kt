package io.github.guohangbk.tally.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RecognitionOverlay(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    val transition = rememberInfiniteTransition(label = "recognition")
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
        label = "pulse",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800), repeatMode = RepeatMode.Restart),
        label = "rotation",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (colors.isDark) Color.Black.copy(alpha = 0.62f) else colors.ink.copy(alpha = 0.42f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier
                // 宽度定死：标题在「本机 OCR 识图中 1/3」与「AI 提取中」之间切换时，
                // 自适应宽度会让整块弹窗忽宽忽窄地跳一下。
                .width(244.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.card.copy(alpha = 0.96f))
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(rotation)
                        .drawBehind {
                            val radius = size.minDimension * 0.38f
                            drawCircle(
                                color = colors.gold.copy(alpha = 0.22f),
                                radius = radius,
                                center = center,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                            drawArc(
                                color = colors.brand,
                                startAngle = -90f,
                                sweepAngle = 76f,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                            )
                            drawCircle(
                                color = colors.onGlow,
                                radius = 3.dp.toPx(),
                                center = Offset(center.x + radius, center.y),
                            )
                        },
                )
                InlineIcon(iconKey = "ai", contentDescription = null, size = 34.dp, tint = colors.onGlow)
            }
            Text(
                text = title,
                style = TallyType.sectionTitle,
                color = colors.ink,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.ink2,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(1.dp))
        }
    }
}
