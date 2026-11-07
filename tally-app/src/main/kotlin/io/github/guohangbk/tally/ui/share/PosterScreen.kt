package io.github.guohangbk.tally.ui.share

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.StatsRequest
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallySwitch
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * 海报预览页（设计板 §12 SH-02）。
 *
 * 屏上这张缩略图与分享出去的那张 PNG 是同一个渲染器画的同一份位图——
 * 「看到的」与「发出去的」不是两套实现，也就长不出差别来。
 */
@Composable
fun PosterScreen(
    graph: AppGraph,
    request: StatsRequest?,
    onBack: () -> Unit,
    /** 非空即结算长图模式：这本账的区间与预算由它自己现算，[request] 不用传。 */
    settlementLedgerId: String? = null,
) {
    val colors = tallyColors
    val context = LocalContext.current
    val viewModel = tallyViewModel(key = settlementLedgerId?.let { "poster-settle-$it" } ?: "poster") {
        PosterViewModel(graph, context.applicationContext, request, settlementLedgerId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        // 标题就叫「分享」。账本与期间不在这儿改：那是统计页的事，这一页只把带上来的那一段画出来、发出去，
        // 于是「屏上在看的那段账」与「这张图」永远是同一份口径。
        TallyTopBar(title = if (settlementLedgerId != null) "结算长图" else "分享", onBack = onBack)

        // 海报本身按宽铺满、上下滚：一屏压不下整张，硬压会糊（SH-02）。
        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val preview = state.preview
            val error = state.error
            when {
                error != null && preview == null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error, style = TallyType.body, color = colors.expense)
                    Spacer(Modifier.height(14.dp))
                    TallyButton(label = "重试", onClick = viewModel::retry, variant = ButtonVariant.GHOST, height = 34)
                }

                preview != null -> Image(
                    bitmap = preview,
                    contentDescription = "账单长图预览",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, colors.line, RoundedCornerShape(10.dp)),
                )

                else -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = colors.brand, strokeWidth = 2.dp)
                    Text("正在画…", fontSize = 11.5.sp, lineHeight = 14.4.sp, color = colors.ink3)
                }
            }
        }

        // 脱敏独占一行：这是「发出去之前最后一次改主意」，不该混进按钮排。整行可点。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setMasked(!state.masked) }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("脱敏", style = TallyType.listTitle, color = colors.ink)
                Text("隐去全部金额，保留分类与结构", style = TallyType.helper, color = colors.ink3)
            }
            TallySwitch(checked = state.masked, onCheckedChange = viewModel::setMasked)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            TallyButton(
                label = "保存",
                onClick = viewModel::save,
                variant = ButtonVariant.GHOST,
                enabled = state.canExport,
                height = 40,
                modifier = Modifier.weight(1f),
            )
            TallyButton(
                label = if (state.rendering) "正在画…" else "分享",
                onClick = viewModel::share,
                variant = ButtonVariant.BRAND,
                enabled = state.canExport,
                height = 40,
                modifier = Modifier.weight(1f),
            )
        }
    }

    TallyToastEffects(
        notice = state.message,
        error = state.error.takeIf { state.preview != null },
        onNoticeShown = viewModel::dismissMessage,
    )
}
