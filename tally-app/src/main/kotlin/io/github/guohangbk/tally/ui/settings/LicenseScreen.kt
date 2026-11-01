package io.github.guohangbk.tally.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * 许可与依赖（设置 → 关于）。
 *
 * 标题不叫「开源许可」：BUSL 1.1 不是 OSI 定义的开源许可证，标准说法是 Source‑Available
 * （源码可用许可 / 商业源码许可）。把话说准，用户才知道自己到底能拿它做什么。
 */
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "许可与依赖",
            onBack = onBack,
            trailing = { TallyTag(text = "BUSL-1.1", tone = TagTone.OUTLINE) },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Section("本项目") {
                Line("许可证", "Business Source License 1.1（BUSL-1.1）")
                Line("术语", "Source‑Available —— 源码可用许可 / 商业源码许可。它不是 OSI 定义的开源许可证。")
                Line("你可以", "自由阅读、构建、修改并装到自己的设备上自用；这些权利不需要向我另要授权。")
                Line("不可以", "把它当成商品或服务提供给第三方——托管、订阅、预装销售、改名再分发都算。")
                Line("期满后", "每个版本在 Change Date（最早 2030-11-01，或该版本首次公开分发满四年）自动转为 GPL-2.0-or-later，届时就是真正的开源软件。")
                Line("全文", "随包 LICENSE 文件；改协议或要商业授权，请在仓库提 issue。")
            }
            Section("第三方依赖") {
                DEPENDENCIES.forEach { (name, license, usage) -> DepRow(name, license, usage) }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "CSV 与 XLSX 的读写、关键词规则、金额解析、海报绘制都是本仓库自己实现的，没有引入表格库与 AI 框架。",
                    fontSize = 10.5.sp,
                    lineHeight = 16.sp,
                    color = colors.ink3,
                )
            }
            Section("图标与字体") {
                Line("分类图标", "136 枚 + 界面图标全部是本仓库手写的矢量路径，不来自任何第三方图标集。")
                Line("字体", "用系统字体族（衬线与默认各一支），不随包分发字体文件。")
                Line("OCR 模型", "ML Kit 中文文字识别由 Google 按 Apache-2.0 分发，模型本身遵循其自带条款；识别只在设备内跑，图片不出设备。")
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "塔莉不收集、不上传任何数据：账单、图片、备份与偏好都留在这台手机里。",
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                color = colors.ink2,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            text = title,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
        )
        Spacer(Modifier.height(7.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        content()
    }
}

@Composable
private fun Line(term: String, text: String) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(term, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text(text, fontSize = 11.sp, lineHeight = 17.sp, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun DepRow(name: String, license: String, usage: String) {
    val colors = tallyColors
    Row(modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Text(
            text = name,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = colors.ink,
            modifier = Modifier.width(124.dp),
        )
        Text(
            text = license,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.brand,
            modifier = Modifier.width(74.dp),
        )
        Text(text = usage, fontSize = 10.5.sp, lineHeight = 15.sp, color = colors.ink3, modifier = Modifier.weight(1f))
    }
}

/** 名称 / 许可证 / 在这个 App 里干什么。版本以 build.gradle.kts 为准，不在这儿重复一遍号。 */
private val DEPENDENCIES = listOf(
    Triple("Jetpack Compose + Material 3", "Apache-2.0", "全部界面与图表"),
    Triple("AndroidX Core / Activity", "Apache-2.0", "系统栏、生命周期、图片选择器"),
    Triple("AndroidX Lifecycle", "Apache-2.0", "页面状态随生命周期存取"),
    Triple("AndroidX Navigation", "Apache-2.0", "页面路由与深链"),
    Triple("Room", "Apache-2.0", "本地数据库与手写迁移"),
    Triple("DataStore", "Apache-2.0", "偏好设置"),
    Triple("WorkManager", "Apache-2.0", "周期与分期补执行、每日清理"),
    Triple("OkHttp", "Apache-2.0", "小莉与 WebDAV 的网络层"),
    Triple("kotlinx-serialization", "Apache-2.0", "协议与备份包的 JSON"),
    Triple("ML Kit 中文文字识别", "Apache-2.0", "截图记账的端侧 OCR"),
    Triple("Kotlin / KSP / Gradle", "Apache-2.0 等", "构建工具链，不随包分发"),
)
