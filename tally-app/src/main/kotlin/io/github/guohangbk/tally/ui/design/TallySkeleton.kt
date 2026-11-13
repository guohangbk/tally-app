package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

/** 骨架是否该出现：加载在 [delayMillis] 内结束就一直不画灰条，出现与消失都是硬切换。 */
@Composable
fun rememberSkeletonShown(loading: Boolean, delayMillis: Long = 150L): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(delayMillis)
            shown = true
        } else {
            shown = false
        }
    }
    return shown && loading
}

/** 骨架条：数据未到时占住版位，不显示 0 也不显示加载文案。 */
@Composable
fun SkeletonBar(
    width: Dp? = null,
    height: Dp,
    corner: Dp = height / 2,
    color: Color = tallyColors.line.copy(alpha = 0.6f),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(color),
    )
}
