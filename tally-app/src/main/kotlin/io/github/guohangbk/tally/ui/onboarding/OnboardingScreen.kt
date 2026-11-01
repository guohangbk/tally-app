package io.github.guohangbk.tally.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyErrorBanner
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * P01 启动与首次引导。
 *
 * 首屏只给三条路：导入历史账单 / 导入 Tally 备份 / 直接从零开始；默认账本与默认分类树在进入本页时就准备好，
 * 引导完成后首页一定有账本可用。小莉（AI）在本期没有配置入口，因此这里不出现任何人格化文案或金晕装饰。
 */
@Composable
fun OnboardingScreen(
    onImport: () -> Unit,
    onImportBackup: () -> Unit,
    onStart: () -> Unit,
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { OnboardingViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(42.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(iconKey = "jasmine", label = "塔莉", size = 68.dp, corner = 22.dp, tone = IconTone.BRAND)
            Spacer(Modifier.height(12.dp))
            Text("Tally", style = TallyType.pageTitle, color = colors.ink)
            Text("塔莉", style = TallyType.eyebrow, color = colors.brand, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "你的私人记账助手",
                style = TallyType.sectionTitle,
                color = colors.ink2,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "记一笔、看统计、整理分类，都在本机完成。",
                style = TallyType.helper,
                color = colors.ink3,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))
        PrivacyCard()

        Spacer(Modifier.height(22.dp))
        TallyButton(
            label = "导入历史账单",
            onClick = onImport,
            variant = ButtonVariant.BRAND,
            height = 46,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TallyButton(
            label = "导入 Tally 备份",
            onClick = onImportBackup,
            variant = ButtonVariant.GHOST,
            height = 46,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TallyButton(
            label = if (state.canStart) "直接从零开始" else "正在准备默认账本…",
            onClick = { viewModel.startFromScratch(onReady = onStart) },
            variant = ButtonVariant.GHOST,
            height = 46,
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        state.error?.let { detail ->
            TallyErrorBanner(
                title = if (state.starting) "引导没有完成" else "默认账本没有准备好",
                detail = detail,
                actionLabel = "重新准备",
                onAction = viewModel::prepareWorkspace,
            )
            Spacer(Modifier.height(12.dp))
        }
        LedgerCard(state)

        Spacer(Modifier.height(14.dp))
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun PrivacyCard() {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth()) {
        TallySectionLabel("数据存在哪里")
        TallyCard(corner = 16, contentPadding = 12.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {
                PRIVACY_LINES.forEachIndexed { index, line ->
                    Text(
                        text = line,
                        style = TallyType.body,
                        color = colors.ink2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (index != PRIVACY_LINES.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun LedgerCard(state: OnboardingUiState) {
    val colors = tallyColors
    val name = state.ledgerName
    Column(modifier = Modifier.fillMaxWidth()) {
        TallySectionLabel("准备工作")
        TallyCard(corner = 16, contentPadding = 9.dp) {
            when {
                name == null && state.error != null -> Text(
                    text = "默认账本待重建，恢复后这里会显示账本名称。",
                    style = TallyType.helper,
                    color = colors.ink3,
                    modifier = Modifier.padding(vertical = 6.dp),
                )

                name == null -> Text(
                    text = "正在创建默认账本与系统分类…",
                    style = TallyType.helper,
                    color = colors.ink3,
                    modifier = Modifier.padding(vertical = 6.dp),
                )

                else -> TallyListRow(
                    title = name,
                    subtitle = "已自动设为默认账本 · 内置两级分类已就位",
                    iconKey = "ledger",
                    iconSize = 30,
                    trailing = { TallyTag(text = "默认", tone = TagTone.BRAND) },
                )
            }
        }
    }
}

private val PRIVACY_LINES = listOf(
    "账单、分类与图片都写在这台手机的本地数据库里，不上传任何服务器。",
    "没有账号体系，也不需要联网；离线时记账、统计完全可用。",
    "备份与导出都在本机完成，导出的文件由你自己保管。",
)
