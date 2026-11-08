package io.github.guohangbk.tally.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.data.ai.AssistantEntry
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.Hairline
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/** 设计板 coach 的气泡底：浅色用墨底，深色反过来用纸面卡片底，两种主题都是「深色浮片」。 */
private data class CoachColors(val bubble: Color, val text: Color, val accent: Color)

/**
 * G-01 / G-02：＋ 上方的一次性引导卡。外面还有一层蒙层（见 HomeScreen），
 * 空白处点击不会关，右上角 ✕ 才是关闭；「更多手势」另开 G-01b。
 * 小莉没开启时多一行「右滑先去设置」，开启未配置写「右滑去配置」，可用时省掉。
 */
@Composable
fun HomeCoachBubble(
    coach: HomeCoach,
    assistantEntry: AssistantEntry,
    onDismiss: () -> Unit,
    onMoreGestures: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    val tone = if (colors.isDark) {
        CoachColors(bubble = colors.card, text = colors.ink, accent = colors.gold)
    } else {
        CoachColors(bubble = colors.ink, text = colors.paper, accent = colors.goldHi)
    }
    val shape = RoundedCornerShape(18.dp)
    val ready = coach == HomeCoach.AssistantReady
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 下缘的小三角：先画，气泡本体盖住它上半截，只留下 6dp 的尖。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 6.dp)
                .size(12.dp)
                .rotate(45f)
                .background(tone.bubble, RoundedCornerShape(2.dp)),
        )
        Column(
            modifier = Modifier
                // 蒙层之上只剩这一块是亮的，字号跟着再抬一档，站着扫一眼就能读完。
                // 宽度收窄、文字居中：撑满屏宽会摊成一条细长的横条，不像一张卡。
                .widthIn(min = 236.dp, max = 272.dp)
                .shadow(14.dp, shape, ambientColor = colors.ink, spotColor = colors.ink)
                .clip(shape)
                .background(tone.bubble)
                .then(
                    if (ready) {
                        Modifier.border(1.dp, tone.accent.copy(alpha = 0.4f), shape)
                    } else {
                        Modifier
                    },
                )
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InlineIcon(iconKey = "ai", contentDescription = null, size = 16.dp, tint = tone.accent)
                    Spacer(Modifier.width(6.dp))
                    CoachTitle(text = "小莉已经就绪", color = tone.text)
                }
            } else {
                // 「长按 ＋ 不动」读起来像一条操作说明；对着 ＋ 说「长按」两个字就够了。
                CoachTitle(text = "长按", color = tone.text, fontSize = 20.sp)
            }
            Spacer(Modifier.height(5.dp))
            if (ready) {
                CoachBody(text = "长按 ＋ 右滑，直接问她", color = tone.text.copy(alpha = 0.78f))
            } else {
                CoachBody(text = "上滑语音 · 左滑截图 · 右滑小莉", color = tone.text.copy(alpha = 0.78f))
                if (assistantEntry != AssistantEntry.Chat) {
                    Spacer(Modifier.height(4.dp))
                    CoachBody(
                        // 落点跟着三态走，别让右键把人送到配置页、卡上却写着「去设置」。
                        text = if (assistantEntry == AssistantEntry.Settings) {
                            "小莉还没开启，右滑先去设置"
                        } else {
                            "小莉还没配好，右滑去配置"
                        },
                        color = tone.text.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 入口压在最底下一行，跟着正文一起居中，不和标题抢位置。
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onMoreGestures)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("更多手势", fontSize = 12.sp, lineHeight = 16.sp, color = tone.accent)
                    InlineIcon(iconKey = "chevron", contentDescription = null, size = 10.dp, tint = tone.accent)
                }
            }
        }
        // ✕ 浮在卡片右上角，不占标题行的一列；30dp 是手指够得着的热区，图标本身只有 13dp。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp)
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(
                iconKey = "close",
                contentDescription = "关闭引导",
                size = 13.dp,
                tint = tone.text.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun CoachTitle(text: String, color: Color, fontSize: TextUnit = 15.sp) {
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = fontSize * 1.3f,
        fontWeight = FontWeight.SemiBold,
        fontFamily = TallyType.screenTitle.fontFamily,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
private fun CoachBody(text: String, color: Color, fontSize: TextUnit = 13.sp) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        fontSize = fontSize,
        lineHeight = 18.sp,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/**
 * G-01b：气泡里点「更多手势」才有这一屏，不占首屏。
 * 前三行是说明，最后一行是唯一能点的——小莉关机时直接把人送到该去的那一页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeMoreGesturesSheet(
    assistantEntry: AssistantEntry,
    onOpenAssistant: (AssistantEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text("更多手势", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(10.dp))
            TallyCard(contentPadding = 9.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TallyListRow(
                        title = "长按 ＋ 不动",
                        subtitle = "上滑语音 · 左滑截图 · 右滑小莉",
                        iconKey = "plus",
                        iconTone = IconTone.BRAND,
                        iconSize = 30,
                    )
                    Hairline(modifier = Modifier.padding(vertical = 1.dp))
                    TallyListRow(
                        title = "长按一笔账单",
                        subtitle = "批量选：改分类、标报销、不计入或删除",
                        iconKey = "receipt",
                        iconSize = 30,
                    )
                    Hairline(modifier = Modifier.padding(vertical = 1.dp))
                    TallyListRow(
                        title = "点账本名 · 左上角菜单",
                        subtitle = "切换账本；导入、导出、备份都在菜单里",
                        iconKey = "menu",
                        iconSize = 30,
                    )
                    Hairline(modifier = Modifier.padding(vertical = 1.dp))
                    AssistantRow(entry = assistantEntry, onOpenAssistant = onOpenAssistant, onDismiss = onDismiss)
                }
            }
            Spacer(Modifier.height(14.dp))
            TallyButton(
                label = "知道了",
                onClick = onDismiss,
                variant = ButtonVariant.BRAND,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "以后想重看：设置 → 使用指南",
                style = TallyType.helper,
                color = colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AssistantRow(
    entry: AssistantEntry,
    onOpenAssistant: (AssistantEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    val title: String
    val subtitle: String
    val action: String?
    when (entry) {
        AssistantEntry.Settings -> {
            title = "小莉默认关闭"
            subtitle = "打开并配置后，右滑才进对话"
            action = "去设置"
        }
        AssistantEntry.Configure -> {
            title = "小莉已开启，还没配置"
            subtitle = "填好服务商、模型与 Key 就能用"
            action = "去配置"
        }
        AssistantEntry.Chat -> {
            title = "小莉已经就绪"
            subtitle = "长按 ＋ 右滑，直接进对话"
            action = null
        }
    }
    TallyListRow(
        title = title,
        subtitle = subtitle,
        iconKey = "ai",
        iconTone = IconTone.GLOW,
        iconSize = 30,
        onClick = if (action != null) {
            {
                onDismiss()
                onOpenAssistant(entry)
            }
        } else {
            null
        },
        trailing = if (action != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(action, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.brand)
                    TallyRowChevron()
                }
            }
        } else {
            null
        },
    )
}
