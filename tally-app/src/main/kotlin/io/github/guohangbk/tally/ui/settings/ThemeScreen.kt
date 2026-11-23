package io.github.guohangbk.tally.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.preferences.ThemeMode
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.TallySkin
import io.github.guohangbk.tally.domain.model.TallySkins
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.design.tallyColorsFor
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * 设置 → 主题。
 *
 * 两个维度分开管：顶上的开关决定读浅色档还是深色档，下面的色卡决定读哪一张色表。
 * 这一页**本身就用当前皮肤渲染**——点藤紫整页立刻变藤紫，色卡不是别人家的图。
 *
 * 色卡只画两个色：主体是这套的主操作色（就是「存入 N 笔」那颗按钮的颜色），
 * 底部一条是这套的纸面。只给主操作色分不开纸墨和素白——两套同一支墨绿，差别在纸上。
 */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "theme-settings") { SettingsViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val colors = tallyColors
    val followSystem = settings.themeMode == ThemeMode.SYSTEM
    val skins = TallySkins.ordered

    TallyToastEffects(
        notice = state.notice,
        error = state.error,
        errorTitle = "这项设置没有保存",
        onNoticeShown = viewModel::dismissNotice,
        onErrorShown = viewModel::dismissError,
    )

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "主题", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsSection(title = "自动") {
                SettingsSwitchRow(
                    checked = followSystem,
                    // 开＝交回系统；关＝锁在**当前正在显示**的那一档，
                    // 不然拨下去界面会跳到另一档，看着像开关坏了。
                    onCheckedChange = { on ->
                        viewModel.setThemeMode(
                            if (on) ThemeMode.SYSTEM else if (colors.isDark) ThemeMode.DARK else ThemeMode.LIGHT,
                        )
                    },
                    label = "跟随系统夜间模式",
                    description = "白天深色自动换档，色卡不受影响",
                )
                if (!followSystem) {
                    SettingsDivider()
                    TallySegmented(
                        options = listOf(ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色"),
                        selected = settings.themeMode,
                        onSelect = viewModel::setThemeMode,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SettingsSection(title = "主题色") {
                skins.forEachIndexed { index, skin ->
                    SkinRow(
                        skin = skin,
                        dark = colors.isDark,
                        selected = skin == settings.themeSkin,
                        onClick = { viewModel.setThemeSkin(skin) },
                    )
                    if (index != skins.lastIndex) SettingsDivider()
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}

/**
 * 一行一套皮肤：色卡 + 名称 + 一句气质描述 + 右侧圆圈。
 *
 * 选中态只有圆圈填色这一件事——不描边、不换底色、不加勾，
 * 否则点一套就把整行的几何撑动，列表滚动时会跟着跳。
 */
@Composable
private fun SkinRow(
    skin: TallySkin,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    // 预览的是「那一套」而不是当前这套：色卡要提前让人看见换上之后长什么样。
    val tone = tallyColorsFor(skin, dark)
    val swatch = tone.brand
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(13.dp))
                .border(1.dp, colors.line, RoundedCornerShape(13.dp)),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(swatch))
            // 底部这条是纸面：素白与纸墨的主操作色同为墨绿，只有这一条能把两者分开。
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .fillMaxWidth()
                    .background(tone.paper),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skin.label,
                style = TallyType.listTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = skin.art,
                style = TallyType.helper,
                color = colors.ink3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) swatch else Color.Transparent)
                .border(1.6.dp, if (selected) swatch else colors.ink3, CircleShape),
        )
    }
}
