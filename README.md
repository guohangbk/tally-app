# Tally 塔莉

> 小莉帮你管好每一笔收支。

**一本只存在手机里的账本**：原生安卓的离线记账 App，数据只存在手机本地，没有账号、没有服务端。

官网：<https://tally.guohang.asia> · 源码与发行包：<https://github.com/guohangbk/tally-app>

<p align="center">
  <img src="site/assets/shot-home.jpg" width="200" alt="首页：本月支出与收入、预算进度条，下面是按天分组的账单">
  <img src="site/assets/shot-entry.jpg" width="200" alt="记账页：4×4 金额键盘，算式 18.00 + 12.00 = 30.00">
  <img src="site/assets/shot-stats.jpg" width="200" alt="统计页：年视图的收支总览、每月柱状、星期分布与分类环">
  <img src="site/assets/shot-chat.jpg" width="200" alt="小莉：说一句「火锅 168，打车 26」，回来一张两笔待确认的账单卡">
</p>

## 下载与安装

**APK 在 Releases 页**：<https://github.com/guohangbk/tally-app/releases>。

下载前先确认手机对得上：

| 项 | 要求 |
|---|---|
| 架构 | 安装包**只含 `arm64-v8a`**：2018 年后的主流机型都是这个架构 |
| 系统 | **Android 9（API 28）及以上** |
| 装不上 | 32 位老机器、x86 / x86_64 模拟器——包里没有对应的 ABI |
| 怎么装 | 没有上架任何应用商店，下载 APK 后自己装；从浏览器或文件管理器装的第一次，要在系统里允许「安装未知应用」 |

想自己编也行：仓库里带着一把测试签名钥匙，克隆下来不用配任何东西就能出包，命令见[本地构建](#本地构建)。

```bash
git clone https://github.com/guohangbk/tally-app.git
```

## 这是什么

**原生 Android 记账应用**：Kotlin + Jetpack Compose 写界面，Room 存本地库，不引跨端框架、不引 AI SDK、不引图表库——图表、金额键盘、海报、桌面组件都是自己画的。数据全部存在手机本地，没有账号、没有服务端。

一眼看全：多账本与「全部账本」合并统计、预算提醒与自动续期、周期记账与分期（含房贷 LPR）到期自动入账、语音与截图记账、导入导出与 `.tally` 加密备份换机、桌面八枚只读小组件与两条长按快捷方式。可选的 AI 小莉负责统计分析与对话式记账——不配置它，前面这些照样能用。

### 记账方式

| 方式 | 怎么用 |
|---|---|
| 手打 | 4×4 金额键盘，支持 `38+12` 这类算式；两级分类、时刻、凭证图、备注 |
| 语音 | 长按 ＋ 上滑（松开即识别），说「二十九块八毛五」出 29.85；识别交给系统语音引擎、端侧优先，金额与分类在手机本地按规则解析 |
| 截图 | 长按 ＋ 左滑，从相册选支付截图，端侧 OCR + 规则抽金额与商户，一次最多 9 张 |
| 周期 | 固定周期**到期自动入账**（房租、订阅、工资），错过的期数一次性「全部补记」；一次要补超过 100 期才停下来等确认 |
| 分期 | 一笔大额拆成多期，末期自动吃分币尾差；支持房贷（LPR±BP、等额本息 / 等额本金），到期同样自动入账 |
| 小莉（AI） | 可选增强：长按 ＋ 右滑，对话里一句话或几张图变成待确认的记账卡；不配置也不影响上面五条 |

### 账本与数据

- **多账本**：日常、装修、买车、结婚……各记各的，也能选「全部账本」一起看；账本只管钱归属哪件事，分类字典全机共用一套。
- **预算**：按月或按年设总额与分类额，每月预算可自动续期，往期有「预算历史」。
- **找回**：删除是软删，回收站按删除日分组，保留期自己设（7 / 30 / 90 天 / 永不清理）。
- **看账**：任意起止区间的统计（趋势、星期分布、分类二级小计、日报表下钻）、带金额区间与排序的搜索、七动作批量修改（改分类、报销、不计入、删除、改日期、改备注、移至账本）。
- **传出去**：月度 / 年度 / 区间分享长图（可一键脱敏）、桌面八枚只读小组件——近七日、收支速览（小 / 四格）、快速记账（小 / 八格）、预算环、消费节奏、记账连续记录，点击一律落回 App；长按图标另有「记一笔」「查看统计」两条快捷方式。
- **换机与备份**：`.tally` 加密备份包（ZIP + AES-256）导出后在任何设备上恢复；CSV/XLSX 导出可原样导回；支付宝 / 钱迹账单可批量导入，重复行在预览阶段就说破。
- **排障**：设置 → 数据 → 导出诊断包——把版本、机型与 ABI、库结构与各表行数、日志尾巴写成一份 txt 落到公共下载目录，并直接顶起系统分享；**不含**账单金额、备注、分类名、账本名、图片与 API Key，行数只说明有多少条、不含任何一条的内容。

### 设置里几个常被问到的入口

| 想干什么 | 在哪 |
|---|---|
| 导入 / 导出账单 | 设置 → 数据 → 导入账单 / 导出账单 |
| 换机、备份、WebDAV | 设置 → 数据 → 备份与同步 |
| 找回删掉的账 | 设置 → 数据 → 回收站 |
| 导诊断包给作者排障 | 设置 → 数据 → 导出诊断包 |
| 看怎么用 | 设置 → 关于 → 使用指南 |
| 打开项目主页 | 设置 → 关于 → 项目主页（用浏览器打开 <https://github.com/guohangbk/tally-app>） |
| 看许可与第三方依赖 | 设置 → 关于 → 许可与依赖 |

### 不做的事

账号体系、云同步、多端合并、账户与资产、转账、多币种、iOS、应用商店上架。系统已经提供的能力（应用锁、通知中心）不在 App 里做第二套。

## 文档

都在 [`docs/release/`](docs/release/) 下（对外**只有这一份，不带版本号**；历史版本用公开仓的 tag 取回）：

| 内容                               | 文档指引                                                                             |
|------------------------------------|--------------------------------------------------------------------------------------|
| 需求与验收标准                     | [`需求规格说明书.md`](docs/release/需求规格说明书.md)                         |
| 架构、模块、技术选型、构建基线     | [`概要设计说明书.md`](docs/release/概要设计说明书.md)                         |
| 逐功能 / 逐模块的处理逻辑与边界    | [`详细设计说明书.md`](docs/release/详细设计说明书.md)                         |
| 表结构、索引、统计口径与版本策略   | [`数据库设计文档.md`](docs/release/数据库设计文档.md)                         |
| 设计语言、组件与交互判据           | [`界面与交互规范.md`](docs/release/界面与交互规范.md)                         |
| 界面长什么样（自包含，可离线打开） | [`界面设计图.html`](docs/release/界面设计图.html)（29 节 / 137 屏）           |
| 为什么这么设计、否决过什么         | [`设计决策.md`](docs/release/设计决策.md)                                     |
| 交给使用者看的说明书               | [`用户操作手册.md`](docs/release/用户操作手册.md) + App 内「设置 → 使用指南」 |


## 本地构建

环境：**JDK 17+**（Gradle 的守护进程要 JDK 21，本机没有时 Gradle 会自己下载）+ **Android SDK `android-37.0`（API 37）**，Android Studio 或纯命令行都行。签名文件仓库里已经带了，克隆下来直接构建，不用先配任何东西；`local.properties` 不进 git——Android Studio 会自己写 `sdk.dir`，纯命令行则用 `ANDROID_HOME` 指定 SDK。

### 1. 签名：一把测试钥匙，随仓库走

Debug 与 Release 都走同一把签名钥匙，仓库里带的是 `tally-app/keystore/tally-unified.jks`——用 Android 调试那套公开常量生成（store/key 口令都是 `android`，别名 `androiddebugkey`）。它不是秘密，所以任何一次克隆都能直接出包；也正因为两者同签名，自己编的 debug 与 release 包可以互相覆盖安装。

> 这把钥匙只为了让大家装得上、能自己编。真要正式发版，请在仓库根的 `local.properties` 里用你自己的钥匙覆盖它，写四项（`tally.keystore` 是**相对 `tally-app/` 模块目录**的路径）：
>
> ```properties
> # local.properties —— 只存在于本机，不进 git
> tally.keystore=keystore/tally-unified.jks
> tally.storePassword=<你的 keystore 口令>
> tally.keyAlias=<你的别名>
> tally.keyPassword=<你的别名口令>
> ```
>
> 想换成自己那把 `~/.android/debug.keystore` 也一样，构建脚本不关心它是哪把。四项缺任何一项，Gradle 会直接报错并告诉你缺哪个键，不会静默走出一个没签名的包。

### 2. 构建

```powershell
.\gradlew.bat :tally-app:assembleDebug      # Windows
```

```bash
./gradlew :tally-app:assembleDebug          # macOS / Linux
```

产物：`tally-app/build/outputs/apk/debug/tally-app-debug.apk`，装到 `arm64-v8a` 的真机或模拟器即可。首次构建会下载 Gradle 发行包（走腾讯镜像）与依赖（`google()` / `mavenCentral()`），之后走本地缓存。

构建基线（AGP 9.4.1 / Gradle 9.6.0 / Kotlin 2.3.21 / KSP 2.3.12 / Compose BOM 2026.09.00 / Room 2.8.5 / compileSdk 37 + minor 0 / minSdk 28 / 仅 `arm64-v8a`）**互相咬死，不要单独升降其中任何一个**，原因见《概要设计说明书》§9.2。

## 验证方式

本项目不写自动化测试。改代码之后只有两条路：

1. 完整构建必须绿：`.\gradlew.bat :tally-app:build`（macOS / Linux 用 `./gradlew :tally-app:build`）。它包含编译、lint、debug 与 release 打包四步；
2. 真机 / 模拟器走查（交互验证一律用 Pixel 9 模拟器，AVD `Tally_API37`）。

单独出包与看报告：

- release 包：`.\gradlew.bat :tally-app:assembleRelease` → `tally-app/build/outputs/apk/release/tally-app-release.apk`（用仓库自带的测试钥匙签名，可覆盖安装）；
- lint 报告：`tally-app/build/reports/lint-results-debug.html`。

## 仓库结构

```text
tally/
├── tally-app/              # Android 客户端（:tally-app，唯一应用模块）
├── docs/                   # 对外只发布 docs/release/ 这一份当前口径
├── site/                   # 官网（单页 HTML + 截图资源，无构建）
├── gradle/                 # Gradle wrapper 与守护进程 JVM 配置（发行包 9.6.0，走腾讯镜像）
└── LICENSE                 # BUSL-1.1
```

## 许可

**BUSL-1.1**（Business Source License 1.1）——属于 **Source-Available（源码可用许可 / 商业源码许可）**，不是 OSI 定义的开源许可。可读、可改、可自行构建、可个人自用；不得作为商业产品或服务提供给第三方。每个版本在 Change Date（最早 2030-11-01，或该版本首次公开分发满四年）自动转为 GPL-2.0-or-later。详见 [LICENSE](LICENSE) 与 App 内「设置 → 许可与依赖」。
