package io.github.guohangbk.tally.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.ui.design.BillBadge
import io.github.guohangbk.tally.ui.design.Marker
import io.github.guohangbk.tally.ui.design.MarkerTone
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * 使用指南：按「想干的事」排的文字说明 + 操作路径，不画图、不放按钮。
 * 「统计口径」原来挂在统计页右上角，现在收进这一页——每个数字怎么算出来的，
 * 与「这个功能从哪儿进」是同一个问题的两面，查一次就够。
 */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "使用指南", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "塔莉是一本只存在这台手机里的账本：没有账号，不联网也能全用。",
                style = TallyType.secondary.copy(lineHeight = 21.sp),
                color = colors.ink2,
            )
            // 标记图例挪在口径前面：那一节正是在解释这几枚标记怎么参与计算。
            val caliber = GUIDE_SECTIONS.last()
            GUIDE_SECTIONS.dropLast(1).forEachIndexed { index, section ->
                GuideBlock(number = index + 1, section = section)
            }
            MarkLegend(number = GUIDE_SECTIONS.size)
            GuideBlock(number = GUIDE_SECTIONS.size + 1, section = caliber)
            Spacer(Modifier.height(32.dp))
        }
    }
}

private data class GuideItem(val term: String, val text: String)

private data class GuideSection(val title: String, val items: List<GuideItem>)

/** 节标题带编号：一眼能看出这一页被切成几段、现在读到第几段。 */
@Composable
private fun GuideHeader(number: Int, title: String) {
    val colors = tallyColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = number.toString().padStart(2, '0'),
            style = TallyType.eyebrow,
            color = colors.gold,
        )
        Spacer(Modifier.width(10.dp))
        Text(text = title, style = TallyType.sectionTitle, color = colors.ink)
    }
    Spacer(Modifier.height(10.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.line))
}

/** 一项一段：条目名在上、说明在下，段间留白大于行距，扫读时不会连成一片。 */
@Composable
private fun GuideBlock(number: Int, section: GuideSection) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 34.dp)) {
        GuideHeader(number = number, title = section.title)
        section.items.forEach { item ->
            Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    text = item.term,
                    style = TallyType.listTitle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
                    color = colors.ink,
                )
                Text(
                    text = item.text,
                    style = TallyType.secondary.copy(lineHeight = 21.sp),
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 口径那几张标记：图形与账单行、统计页、详情弹层完全同一套。 */
@Composable
private fun MarkLegend(number: Int) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 34.dp)) {
        GuideHeader(number = number, title = "账目上的标记")
        MARKS.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { Marker(BillBadge(it.first, it.second, it.third)) }
            }
        }
        Text(
            text = "同一笔可以同时带好几枚标记，它们互不覆盖；报销与退款只有支出能带。",
            style = TallyType.secondary.copy(lineHeight = 21.sp),
            color = colors.ink2,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private val MARKS = listOf(
    Triple("receipt", "未报销", MarkerTone.OUTLINE),
    Triple("receipt", "已报销", MarkerTone.BRAND),
    Triple("refund", "退款", MarkerTone.BRAND),
    Triple("eyeoff", "不计入收支", MarkerTone.SLATE),
    Triple("repeat", "周期", MarkerTone.GOLD),
    Triple("slice", "分期", MarkerTone.GOLD),
    Triple("import", "导入", MarkerTone.EXPENSE),
)

private val GUIDE_SECTIONS = listOf(
    GuideSection(
        "记一笔",
        listOf(
            GuideItem(
                "首页底部那枚 ＋",
                "短按＝进记账页：上方切支出 / 收入，中间选分类，下面的 4×4 键盘输金额（支持 38+12 这种算式）。" +
                    "备注、账单时间、一张凭证图、所属账本、要不要计入收支，都在同一页里改。",
            ),
            GuideItem(
                "打开就记（秒开模式）",
                "设置 → 记账偏好 → 秒开模式：开启后打开应用直接落在记账页——冷启动与「停在首页时点桌面图标」都算，" +
                    "停在别的页不打断；从记账页返回或保存一笔后回首页。",
            ),
            GuideItem(
                "记完之后那句话",
                "这笔如果是靠关键词或截图认出来的，保存后底部气泡会问「以后『打车』都记到 交通 · 打车？」——点一下才写进规则，不点就只是一句话。",
            ),
            GuideItem("改一笔", "点流水里任意一行＝底部弹层看详情。要改金额、分类或时间就按「编辑」跳记账页，弹层里不做就地编辑。"),
        ),
    ),
    GuideSection(
        "语音与截图",
        listOf(
            GuideItem("长按 ＋", "松开弹出一个扇形：上＝语音记账，左＝截图记账，右＝问小莉（没开小莉时不出右臂）。"),
            GuideItem(
                "语音",
                "点一下开始说，再说一下结束并发送；说满 20 秒会先提示再收尾。「二十九块八毛五」这类说法在手机本地解析成 29.85，" +
                    "再带进记账页等你确认——这条链路不发网络请求。",
            ),
            GuideItem(
                "截图",
                "从相册选一张，本机 OCR + 规则抽取，出草稿卡逐笔勾选，一次最多 9 张。图片不出设备，点「记入」才写库。",
            ),
            GuideItem(
                "小莉（可选）",
                "设置 → 小莉（AI）→ 小莉配置：可以存多份配置，右上角 ＋ 新增；填服务商、模型与 Key，测通了才保存。" +
                    "对话里可以发图，一次最多 3 张。小莉只负责把草稿摆好，落库这一步永远留给人。",
            ),
        ),
    ),
    GuideSection(
        "看账、批量与删除找回",
        listOf(
            GuideItem(
                "首页流水",
                "按月分组。顶栏那行字就是当前账本，点它可以切换，也能选「全部账本」一起看；统计跟着首页这个选择走，搜索页自己有一组筛选项。",
            ),
            GuideItem(
                "批量",
                "长按任意一行进入选择态，底部出现两排动作：改分类 / 报销 / 不计入 / 删除，改日期 / 改备注 / 移至账本。" +
                    "「移至账本」只搬钱的归属，分类不动；周期与分期自动生成的那几期会被整批跳过并说明原因。",
            ),
            GuideItem(
                "删除与找回",
                "删除是软删。找回只在回收站：设置 → 数据 → 回收站。按删除日分组，长按组头能把那一天整组选中；" +
                    "保留期在页右上角设置里改（7 天 / 30 天 / 90 天 / 永不清理），到期才物理删除并回收图片。",
            ),
        ),
    ),
    GuideSection(
        "搜索",
        listOf(
            GuideItem(
                "入口",
                "首页右上角的放大镜。默认只摊开关键词与收支类型，其余条件收在「更多筛选」里；" +
                    "收起时已生效的条件原样写在开关下面，点任意一条就地展开。",
            ),
            GuideItem(
                "能按什么筛",
                "时间范围（全部 / 本月 / 近 30 天）、账本、来源（手动 / 导入）、排序（最新 / 最早 / 金额大 / 金额小）、金额区间（元，两侧可以只填一头）。",
            ),
            GuideItem(
                "数字与列表为什么不一样",
                "合计算的是全部命中，列表一次最多先列 1000 笔，超出会在末行写清还剩多少条。按金额排序时是排行榜，不再按天分组。",
            ),
        ),
    ),
    GuideSection(
        "统计与长图",
        listOf(
            GuideItem(
                "入口与范围",
                "首页右上角的图表图标。统计用的账本＝首页当前所选：首页切「全部账本」这里就是全部账本，统计页自己不换账本。",
            ),
            GuideItem(
                "翻周期",
                "顶栏的 ‹ › 或在页面上左右滑；维度在 月 / 年 / 自定义区间 之间切。区间不超过 62 天按日分桶，更久按月，也可手动按周。",
            ),
            GuideItem(
                "段序",
                "收支总览 → 预算段（设了才出现）→ 每日统计（趋势与星期分布）→ 分类报表 → 日报表；年维度时最后一段是月报表。" +
                    "分类点一级展开二级小计、点二级进末级清单；日报表点某天出当日清单，可以就地补一笔。",
            ),
            GuideItem(
                "AI 小结",
                "统计页右下角那颗「分析本月 / 分析本年 / 分析区间」：点一次算一次，没点之前这一屏没有任何 AI 痕迹；" +
                    "没开小莉或模型没配好时，这颗按钮不出现。",
            ),
            GuideItem(
                "生成长图",
                "统计页右上角的分享图标，把当前这一段画成一张竖长图：概览、分类排行（最多 8 条）、预算、趋势；" +
                    "这一段算过 AI 小结的话，还会带上一段结论。打开「脱敏」后所有数字换成占位符，比例条与排序保留。" +
                    "两条出口：直接分享，或存到 Download/Tally。账本概览页的「生成结算长图」画的是同一套版式。",
            ),
        ),
    ),
    GuideSection(
        "预算 · 报销 · 周期 · 分期",
        listOf(
            GuideItem(
                "预算",
                "从屏幕左边缘向右滑打开抽屉 → 预算管理。一个账本在同一周期段里只有一条：总额与分类预算各算各的，" +
                    "点 ＋ 撞上已存在的段会直接进那条编辑。可设提醒阈值（默认 80%）与每月自动续期，往期去「预算历史」看。" +
                    "首页黑框卡片下面那条进度点开会弹「预算速览」。",
            ),
            GuideItem(
                "报销",
                "抽屉 → 报销管理。它只改状态（未报销 / 已报销），不改金额——这里没有账户，标了报销不等于钱到账。" +
                    "已报销的支出不再计入支出合计，未报销的照常计入；统计页支出视图还能临时打开「剔除未报销」，把那几笔先摘出去。",
            ),
            GuideItem(
                "周期记账",
                "抽屉 → 周期记账。设好频率与执行日，到期自动生成账单。错过的期数不会静默补：页顶出现一条金色卡" +
                    "「检测到 N 笔待补账单」，按「全部补记」才入账，账单日期仍是应执行日。",
            ),
            GuideItem(
                "分期管理",
                "抽屉 → 分期管理。把一笔大额拆成多期，末期自动吃分币尾差，合计恒等于总额。卡片只放名称、进度与期数；" +
                    "本期应还、剩余本金、下期本金都在「明细」弹层顶部三格里。房贷可以改利率，金额、期数与首期入账日锁住不能回头改。",
            ),
        ),
    ),
    GuideSection(
        "账本与分类",
        listOf(
            GuideItem(
                "账本",
                "抽屉 → 我的账本。新建只填名称、挑图标。卡片四个动作：概览 / 编辑 / 结算 / 删除。" +
                    "「编辑」里能改名字与图标，也能勾「设为默认账本」——默认账本名字旁有标签，全机只有一本，它不能结算。" +
                    "「概览」是一本账的结算页：支出合计、分类占比、最近几笔，加四个快速问答（收入和结余 / 最大一笔 / 每月平均 / 某分类花了多少），" +
                    "页底可以生成结算长图。结算过的账本收在下半段，能恢复记账；删除时可以把账单整批搬到另一本。账本只管钱归属哪件事，分类全机共用。",
            ),
            GuideItem(
                "分类",
                "抽屉 → 分类管理。全机共用一套两级树：一级是卡片，二级在卡片下面密集平铺，不做缩进树。长按一级卡片拖动排序。" +
                    "有账单的分类删除时要先选一个去处；归档区在页底，归档后只有 0 笔的才能彻底删除。「查看统计数据」会带着这个分类进搜索。",
            ),
            GuideItem("关键词规则", "设置 → 数据 → 关键词规则。内置词只读，你自己的规则优先；规则只在你确认沉淀时才新增，不会偷偷学你。"),
        ),
    ),
    GuideSection(
        "导入 · 导出 · 备份",
        listOf(
            GuideItem(
                "导入",
                "设置 → 数据 → 导入账单。支持自己的模板、钱迹、支付宝账单；预览把「可导入」与「重复」分成两段，重复行默认跳过，" +
                    "要重复导入得手动点「仍然导入」。判重键会写清是外部 ID 还是组合指纹。",
            ),
            GuideItem("导出", "设置 → 数据 → 导出账单。13 列 CSV / XLSX，导出去的文件可以原样导回来，标记与凭证图引用都在列里。"),
            GuideItem(
                "备份",
                "设置 → 数据 → 备份与同步。.tally 是本机加密包（口令至少 4 位，可含字母）；WebDAV 只做手动快照，不做云同步。" +
                    "AI 配置、对话记录与聊天图片不进备份包。",
            ),
        ),
    ),
    GuideSection(
        "提醒 · 桌面",
        listOf(
            GuideItem(
                "提醒与通知",
                "设置 → 提醒 → 提醒与通知。四类各自可关：当日未记账、周期任务到期、分期到期、执行失败。" +
                    "预算阈值不在这里，它挂在每条预算自己的编辑页里。提醒只承诺「今天之内」不承诺准点；进程被系统杀掉时，回到 App 会补算一次。",
            ),
            GuideItem(
                "桌面组件",
                "长按桌面 → 小部件，有八枚：近七日、收支速览（2×2 / 4×2 四格）、快速记账（2×2 / 4×2 八格）、预算环、消费节奏、记账连续记录。" +
                    "组件全部只读，点哪儿就进 App 对应的页；取不到数时显示「—」并标「未更新」，不会拿 ¥0.00 冒充。",
            ),
            GuideItem("长按图标", "长按 App 图标只有两条：记一笔、查看统计。"),
        ),
    ),
    GuideSection(
        "统计口径",
        listOf(
            GuideItem("无标记", "正常计入。支出进支出、收入进收入，笔数一起算。"),
            GuideItem("未报销 / 已报销", "已报销的支出不再计入支出合计；未报销的照常计入，统计页支出视图打开「剔除未报销」时那几笔才暂时不算。"),
            GuideItem(
                "退款",
                "关联了原单的退款按负数冲抵原单所在分类的支出；没关联原单的退款不动金额，只出现在列表与笔数里。" +
                    "退款累计不超过原单，退满后不能再挂。",
            ),
            GuideItem("不计入收支", "整笔从支出与收入里剔除，单独汇成「已排除 N 笔」；它仍然出现在列表里，标记降饱和。"),
            GuideItem("周期 / 分期", "只算已经生成的那几笔真实账单，未来期次不进任何数字。"),
            GuideItem("导入", "与手打的账完全同口径，来源只决定能不能按来源搜到它。"),
            GuideItem("区间与上期", "自定义区间含起止两天；用来对比的「上期」是紧挨着这段的等长前一段。"),
            GuideItem("月份边界", "每月起始日可设 1–28 号；首页、统计月维度、预算段、日报表共用这一条边界。"),
            GuideItem("星期分布", "画的是「该星期几平均每天花多少」，分母是它在这一段里出现过的天数，不是固定 7。"),
            GuideItem("删除的账", "进了回收站的账单不参与任何统计，直到被恢复。"),
            GuideItem("分类被删", "账单落到「未分类」，金额口径不变，笔数照算。"),
            GuideItem("对不上数时", "先看这笔带了哪枚标记，再回来读它在上面这一段里是计入、冲抵还是剔除。页面上每个数字都由这套规则推导，界面自己不做加减。"),
        ),
    ),
)
