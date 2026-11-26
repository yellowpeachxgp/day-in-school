# 日在校园 · 世界级开源校园软件总体规划（Master Plan v1）

> 日期：2026-11-26（Asia/Shanghai）
> 版本：**v1.1**（同日晚，按用户决策更新：不与学校合作、论坛只留接口、AI 用户自带 Key、swe 先行 neon 后续、全程在开发机构建、10 槽位满载。**§0.5 与正文冲突时以 §0.5 为准**；协作规范见 §12–§16）
> 适用：`day-in-school`（Android）+ `cloud-in-school`（Campus Cloud）
> 地位：路线图与设计指导文件。仍服从根目录 [SYSTEM_CONTRACT.md](../../SYSTEM_CONTRACT.md)。本文与契约冲突时，以契约为准。
> 配套：[AGENT_TASK_CARDS.md](AGENT_TASK_CARDS.md)（swe-2.1 / neon 的可执行任务卡）

---

## 0. 一页结论

1. **38 个 issue 中，大约三分之一已经在代码里完成，只是 issue 没关**：#5 #6 #10 #13 #14 #16 #17 #18 #19。第 0 波先清账，附提交号和真机证据逐个关闭。
2. **真正的 bug 只剩 3 个**：#24 进度条不动（根因已定位，见 §3.2）、#12 旧学期考试（需要真机复核，并在界面显式提示）、#22 自定义节次时间和实践课多日排课。
3. **14 个大型提案里，11 个可以做，而且应该做**，但不能各自造轮子。它们共同依赖 5 个还没有的「内核」：
   **统一时间流 Timeline、校园实体图 Campus Graph、个人数据仓库 Vault（历史 + 搜索 + 备份）、自动化引擎 Automation、群组与社区云 Community Cloud**。先建内核，功能是内核上的应用。
4. **明确拒绝或推迟**：#46 哈啰代扫码收手续费（拒绝，平台协议和资金中介风险）、#20 广告变现（核心 App 拒绝）、#44 数字孪生（推迟，先做轻量「一日回放」）、#45 论坛与 #9 私信**本轮只预留接口，不实现**（§0.5 D2）。
5. **最大的工程风险是巨型文件**，不是功能缺失：`CampusRepository.kt` 8885 行、`SettingsScreen.kt` 4519 行、云端 `app.mjs` 5819 行。两个代理同时往里写会持续冲突。**第 0 波必须先拆分**，再开放新功能开发。
6. **「世界第一」的定义**：不是功能最多，而是同时做到下面三件事，至今还没有开源校园软件同时做到：
   - **本地优先、诚实可追溯**：每个结论都能回溯来源、可信等级和新鲜度。这一点已经写进现有代码的 DNA。
   - **一校一包、多校共建**：学校能力用签名数据包和声明式插件交付，不改宿主。
   - **可编排**：课表、成绩、水电、通知、地图等所有模块都是自动化引擎的事件源和动作目标。

### 0.5 v1.1 用户决策（与正文冲突时以本节为准）

| 编号 | 决策 | 落地要求 |
|---|---|---|
| D1 | **不与学校合作** | 学校数据只来自公开资料和用户本人账号的只读访问。#38 #41 的数据包由维护者从公开页面整理，每条带 `sources` 和 `verifiedAt`，代理产出的数据一律标 `UNVERIFIED`，等人工核对。选课提交、自动写入等需要校方授权的能力继续默认关闭，**不追求官方授权**。§11 第 1 条作废 |
| D2 | **论坛（#45）和私信（#9）只留接口，不做** | 云端：`boards / posts / replies / reports / moderation / media` 端点全部写进 OpenAPI，标注 `x-dis-status: reserved`，实现统一返回 `501` 和错误码 `FEATURE_NOT_ENABLED`；**不建表、不写业务逻辑**。Android：`:core:community` 中定义 `CommunityForumPort`，只提供 `DisabledForumPort` 实现，开关 `community.forum` 默认关闭，没有任何 UI 入口。插件槽 `community.boards` 只登记为保留。G6 保留为将来开启的前置条件 |
| D3 | **AI 功能用户自带 Key（BYOK）** | 新增 `AiProviderPort`（OpenAI 兼容的 chat 与 vision 接口，见 §6.8），用户填写 baseUrl、model、key。Key 用 Keystore AES-GCM 加密，按 Profile 隔离，不进备份、日志和诊断信息；云端不代理、不存储。默认关闭；每次发送前可预览发送内容；只允许 HTTPS。现有 Parcel AI Key 的存储方式能复用就复用 |
| D4 | **两阶段执行：先 swe-2.1，再 neon** | 阶段 S：swe-2.1 完成全部 `[S]` 卡和可用的骨架 UI（M3 默认组件，状态矩阵完整，不做视觉打磨），并通过阶段 S 终验（§14）。阶段 N：之后才由 neon 执行全部 `[N]` 卡（§15）。两个阶段不重叠 |
| D5 | **全程在开发机上构建和测试** | 编译、测试、Lint、截图、模拟器、容器全部在开发机上进行。本机 Mac（16GB）只保留代理会话、只含源码的 worktree，以及 `ssh / git / gh` 这类轻量命令（§12.2） |
| D6 | **与企业项目共用开发机，互不干扰** | 校园项目的资源统一用 `cis-` 前缀，目录集中在 `/srv/xpeach-dev/campus/`。不得触碰 `xpeach-stg-*`、`ent-*`、`swe2-*` 容器，企业项目的 runner、`/srv/xpeach-dev/repos/xpeach-*`、`/srv/xpeach-dev/worktrees/` 和 `/srv/xpeach-ci`。严禁任何全局 `docker ... prune` |
| D7 | **全程 10 个子代理并发** | 槽 1–8 通用，槽 9 集成列车，槽 10 阶段联调验收（§12.4） |
| D8 | **合并方式** | `main` 是唯一集成分支。只用 merge commit；禁止 squash、rebase、force push、改写历史；按合并列车合入（§12.5） |
| D9 | **交叉审阅** | 每个 PR 至少 1 个没参与它的审阅者；Room 迁移、擦除链路、凭据与 Key 存储、契约、自动化动作风险等级、备份格式类 PR 需要 2 个独立审阅者 |
| D10 | **推理强度分级** | 简单任务用 `high`，其余一律 `xhigh`；封闭清单见 §12.7 |
| D11 | **CI 继续用 GitHub 托管 runner** | 两个仓库都是**公开仓库**，托管 runner 免费。**禁止给公开仓库注册开发机自托管 runner**：任何人提交的 fork PR 都可能在开发机上执行代码。开发机现场门禁是第一道关，托管 CI 作为合并记录 |
| D12 | **发布边界** | 不推 `v*` 标签（会触发 `android-release.yml` 正式发版）、不建 GitHub Release、不部署云端生产机、不碰正式签名密钥 |
| D13 | **公开的 GitHub 操作要用户确认** | issue 的回复、关闭、加标签，先把草稿写进 `task-notes/campus/issue-replies.md`，用户确认后再由主代理执行。PR 正常创建，不在此列 |
| D14 | **编号统一分配** | Room 版本号（从 v11 起）、云端 SQL 迁移编号（从 `0005` 起）由主代理分配（初始分配见 §13），带迁移的 PR 严格按编号顺序合入 |
| D15 | **契约的唯一权威是 `cloud-in-school/contracts/`** | Android 仓库的 `contracts/` 是同步副本。目前两份已经漂移（`openapi/cloud-v1.yaml`、`endpoints/endpoint-inventory.yaml` 不同），由 INF-02 收敛，并在两仓 CI 中加一致性检查。契约同一时间只由契约线（CT 系列）修改 |
| D16 | **开发机地址和账号不进公开仓库** | 仓库里的 `DEVBOX.md` 和脚本一律用 `<devbox>` 或环境变量 `CIS_DEVBOX`；真实地址只出现在本机启动提示词里。任何密码、Token、Key 都不写进仓库、PR、Issue、任务板、日志和子代理汇总 |

---

## 1. 现状审视（事实基线）

### 1.1 规模与技术栈

| 项 | Android `day-in-school` | Cloud `cloud-in-school` |
|---|---|---|
| 版本 | 0.20.22（versionCode 2022） | 1.0.0 |
| 语言 / 运行时 | Kotlin 2.1，Compose M3，minSdk 31 / target 36 | Node ≥ 22，原生 ESM `.mjs`，PostgreSQL + Redis |
| 规模 | 24 个 Gradle 模块，main 源码约 95k 行 | 6 个 apps + 11 个 packages，约 23k 行 |
| 存储 | Room v10（每个 Profile 独立库）+ GlobalProfileRegistry v1 + DataStore | 4 个 SQL migration |
| 契约 | 与 Cloud 共用 `contracts/`（OpenAPI 3.1 共 97 个 operationId、AsyncAPI、错误目录、权限目录、5 个 JSON Schema） | 同左，含 contract-parity 测试 |
| DI / 导航 | 无 DI 框架；手写 Tab 枚举加二级页路由 | 手写 `pathname` 分支路由 |

### 1.2 已有的护城河（必须保护，不得回退）

- **诚实边界体系**：`VerificationLevel`、`CapabilityState`、`DataProvenance`、`SnapshotCompleteness`、`Staleness`，以及 `OFFICIAL / DERIVED / ESTIMATED`。这是区别于所有「课表 App」的核心资产，新模块必须复用，不得另造。
- **多身份隔离**：`SchoolAccountScope`、`profileId + generation`、每个 Profile 独立数据库，Worker 和小组件都绑定世代。
- **九协议教务网关**：`:core:academic-protocols`（MOCK_TESTED）。这是多校扩张的发动机。
- **签名包体系**：`:core:school-packages`（Ed25519、原子安装 / 回滚、权限域），加上 `dis-extension/v2` Schema 和声明式 UI 槽位（`feature-ui-v1`）。
- **已完成但未产品化的领域层**：`:core:academic-plan`（培养方案树、学分分组）和 `:core:selection`（选课草稿、半开区间冲突）。#29 和 #30 的地基已经有了。
- **云端**：契约优先、三阶段调度（T−30 / T−5 / T）、资金 Saga、审计事件、`/api/v1/sync/snapshot`、`/api/v1/me/data-export|data-delete`。

### 1.3 技术债（按阻塞程度排序）

| # | 问题 | 影响 | 处理波次 |
|---|---|---|---|
| D1 | `CampusRepository.kt` 8885 行，承载所有领域 | 任何新功能都要改它，两个代理必然冲突；测试粒度粗 | W0 |
| D2 | `SettingsScreen.kt` 4519 行、`CampusMapScreen` 3834 行、`TodayScreen` 3760 行、`HautTheme` 2992 行 | neon 无法只改 UI 层；Compose 重组性能难控 | W0 |
| D3 | 云端 `apps/api/src/app.mjs` 5819 行，每个 package 只有一个 `index.mjs` | 新的云领域（社区、账本）无处安放 | W0 |
| D4 | 无统一 `TimelineEvent`（GAP 08 已记录） | 自动化、空闲匹配、复习排程、通知收件箱都缺公共时间模型 | W1 |
| D5 | 无全文搜索、无实体变更历史 | 阻塞 #42 #34 #31 去重 | W1 |
| D6 | 无 `androidTest` 和 Compose UI / 截图测试 | neon 的 UI 改动没有回归网 | W0 |
| D7 | 本地 `main`（`a3af60e`）落后于 `origin/main` | 分支基线混乱 | W0（`git fetch` 后以 origin/main 为准） |
| D8 | `StudentProfile.schoolId` 仍是学号语义（GAP 04） | 新模块误用会导致跨校数据串号 | W1，新代码一律用 `SchoolAccountScope` |

### 1.4 Issue 现状核对（基于源码和提交记录）

| Issue | 结论 | 证据 / 说明 | 动作 |
|---|---|---|---|
| #5 删 2 号楼、2/3 餐厅 | 已修复 | `872875a`、`e885171` | 附证据关闭 |
| #6 2.5D 地图无法缩小 | 已修复 | `872875a`（缩放 12.6..18.8，支持双指和 ± 按钮） | 真机录屏后关闭 |
| #10 嵩山路电子围栏漂移 | 已修复 | `872875a`、`e885171` | 请报告人现场复核 |
| #13 嵩山路空教室 | 已修复（参考数据） | `872875a`；数据为「公共名录 + 个人无课反查」，不是实时占用 | 关闭，并在说明中写清边界 |
| #14 工大蓝 #053968 | 已修复 | `872875a`，README 已写明 | 关闭 |
| #16 计算机服务社场地 | 已修复 | `872875a`，README 已写明 F01 W111 | 关闭 |
| #17 校医院药品 | 已修复 | 202 项离线目录，带致谢和医保说明 | 关闭（OCR 增量更新转入 W5 数据包流程） |
| #18 二次元图标 | 已修复 | 可选 `activity-alias` 图标，默认关闭 | 关闭 |
| #19 QQ 群 / 频道 | 已支持 | 应用内可配置交流群 | 关闭（运营动作另行处理） |
| #7 停课后无法恢复 | 大概率已修复 | README：「当日已停课」区域可一键恢复 | 真机复核后关闭 |
| #8 #15 课程管理、实践课手动排课 | 部分完成 | README 已有手动加课、仅改本次、实践课绑定排课 | 并入 #22，W0 补齐 |
| #12 考试显示上学期 | 逻辑已修复，需复核 | `CampusRepository` 有学期自动推进逻辑（约 1160 行）；用户手动锁定旧学期时仍会停留 | W0：加「正在查看 2025-2026-2」常驻提示，并补回归测试 |
| #22 课表设置更灵活 | 未完成 | 已有 `period_times` 表，但没有自定义节次时间编辑器，实践课只能排单日 | W0 |
| #24 通知栏进度条不动 | **确认 bug** | 见 §3.2 | W0 |

---

## 2. 设计北极星：「世界第一」意味着什么

### 2.1 八条设计原则

| # | 原则 | 落地要求 |
|---|---|---|
| P1 | **本地优先** | 核心功能离线可用；云端只承载必须多人协作的数据（群组、社区、众包状态），不托管教务密码 |
| P2 | **诚实可追溯** | 每个展示给用户的结论都带 `DataProvenance`（来源、时间、完整度、可信等级）；缺数据时显示「未知」，不猜 |
| P3 | **用户确认优先于自动写入** | OCR、AI、规则推断出的结果一律先「预览 → 修正 → 确认」，再写入；对校方系统的写操作永不自动执行，除非有学校授权 |
| P4 | **一校一包** | 学校差异（节次、楼宇、地图、办事流程、设施、教务协议）用签名数据包表达，不 fork 代码 |
| P5 | **万物皆事件** | 所有模块都向统一时间流和事件总线发布变化，自动化、提醒、搜索、小组件只订阅、不耦合 |
| P6 | **可扩展但不可越权** | 插件仍然是声明式（不做远程 Dex 或任意脚本，遵守契约 I.6）；扩展点通过 Port 和权限 Broker |
| P7 | **社区不失控** | 任何 UGC 先过合规门禁：实名锚点、审核、举报、审计、删除脱钩 |
| P8 | **人人可用** | 200% 字号、TalkBack、深色、减少动画；启动和滑动帧率有基准；电量有预算 |

### 2.2 值得借鉴的成熟设计模式

| 来源模式 | 借鉴点 | 用在哪里 |
|---|---|---|
| 智能家居平台的「集成 → 实体 → 自动化 → 蓝图」 | 适配器产出标准实体；规则引擎跨实体编排；规则模板可分享 | 学校包 → Campus Graph 实体 → Automation 引擎 → 规则模板库（#36 #43） |
| 本地优先笔记软件 | 数据在用户手里，文件可携带，插件生态 | Vault、课程知识空间（#34 #42） |
| 众包地图 | 来源标注、编辑历史、审核、时效 | 设施雷达、状态上报、地图纠错（#39 #35 #10） |
| 出行决策类 App | 回答「现在出发来不来得及」，而不是「在哪」 | 课间通勤、跨楼提醒（#30 #36） |
| 报税软件的「问答式流程」 | 用问题分支生成个人清单 | 办事导航、奖助申请（#38 #41） |

### 2.3 北极星指标（建议埋本地统计，用户可关闭，不上传原始数据）

- **首个课表可用时间**（安装 → 看到本周课表）：p50 < 60 秒，含手工建课路径。
- **周活跃留存**：开学第 4 周 WAU / 第 1 周 WAU ≥ 70%。
- **可信覆盖率**：首页展示的数据中，带新鲜来源（< 24h）的比例 ≥ 95%。
- **校园覆盖**：`SCHOOL_VERIFIED` 的学校数，以及每校已验证的能力数。
- **贡献者**：每月合并的外部数据包或插件 PR 数。
- **质量**：崩溃率 < 0.2%；冷启动 p90 < 1.2 秒（需要 Macrobenchmark 支撑）。

---

## 3. 许愿池评估

### 3.1 评估总表

评分口径：**价值**（覆盖人群 × 频次）、**难度**（S/M/L/XL）、**合规风险**（低 / 中 / 高）。

| Issue | 主题 | 判定 | 价值 | 难度 | 合规 | 依赖内核 | 波次 |
|---|---|---|---|---|---|---|---|
| #24 | 进度条不动 | 修 | 高 | S | 低 | — | W0 |
| #12 | 旧学期考试 | 复核 + 提示 | 高 | S | 低 | — | W0 |
| #22 / #8 / #15 | 课表灵活设置 | 做 | 高 | M | 低 | — | W0 |
| #42 | 个人数据仓库 | **做（内核）** | 高 | L | 低 | Vault | W1 |
| #36 | 自动化引擎 | **做（内核）** | 高 | L | 低 | Timeline、事件总线 | W2 |
| #31 | 通知收件箱 | 做 | 很高 | L | 中（AI 需单独同意） | Timeline、Vault | W2 |
| #29 | 毕业进度审计 | 做（地基已有） | 高 | M | 低 | academic-plan | W3 |
| #30 | 智能选课沙盘 | 做（地基已有） | 高（选课季） | L | 低（不自动提交） | selection、location | W3 |
| #34 | 课程知识空间 | 做 v1（归档 + 搜索 + 复习排程），AI 后置 | 中高 | L | 中（AI） | Vault、Timeline | W3 |
| #33 | 共同空闲与组队 | 做 | 高 | L | 中（共享忙闲） | Community Cloud、Timeline | W4 |
| #37 | 宿舍协作空间 | 做（**只记账，不收付款**） | 中高 | L | 中（不碰资金） | Community Cloud | W4 |
| #39 (+#32) | 设施雷达与状态上报 | 做 | 中高 | M | 中（UGC） | Campus Graph、Community Cloud | W4 |
| #45 | 论坛 | **只留接口**（D2）：契约、501 桩、默认关闭的 Port | 中 | S | 低（不上线） | Community Cloud | W4-06 |
| #9 | 失物招领聊天 | **只留接口**，与论坛同样处理（D2） | 中 | S | 低（不上线） | 同上 | W4-06 |
| #38 | 办事流程导航 | 做（数据包 + 问答流程） | 中高 | M | 低 | Campus Graph、数据包 | W5 |
| #41 | 考证、竞赛、奖助中心 | 做（目录 + 追踪；资格判断只做「可计算部分」） | 中高 | M | 低 | 数据包、Timeline | W5 |
| #43 | 插件与适配 SDK | 做（在 v2 上做 v2.1，不推倒） | 战略 | L | 中 | 全部 | W6（贯穿） |
| #35 | 室内导航 / AR | 做室内路网试点（1 栋楼）；AR 推迟 | 中 | L（AR 为 XL） | 低 | Campus Graph | W6 |
| #44 | 数字孪生 | **推迟**；先做「一日回放」（2.5D 动画 + 通勤统计） | 低中 | XL | 低 | location、Timeline | W6 |
| #11 | 微信公众号 | 运营事项，不入代码 | — | — | — | — | 运营 |
| #20 | 小程序 + 广告 | 核心 App 拒绝广告；小程序见已有可行性文档 | — | — | 中 | — | 拒绝 / 运营 |
| #46 | 哈啰代扫码收手续费 | **拒绝** | — | — | **高** | — | 关闭 |
| #1 #3 #40 #32 | 已关闭 | #32 的内容并入 #39 | — | — | — | — | — |

### 3.2 #24 根因（已定位）

`core/reminder/.../ReminderNotificationFactory.kt` 第 459–465 行用 `builder.setProgress(total, elapsed, false)` 写入的是**发布那一刻的静态值**。Android 通知的进度条不会自己前进，只有重新 `notify()` 才会更新。而 `ReminderCoordinator.syncLiveCourseNotification` 只在边界闹钟（startAt / endAt）和同步时重新发布，所以课中进度条会一直停在发布时刻。倒计时文字用的是 `setUsesChronometer`，由系统驱动，所以「时间在走、进度条不动」，和截图一致。

**修复方案**（按推荐顺序）：
1. **低频节拍**：实况窗口内用 `AlarmManager.setWindow` 或 inexact 闹钟，每 5 分钟（可配置为 1、5、10 分钟）触发一次重发布；必须带 `setOnlyAlertOnce(true)`（已有）。窗口结束后取消节拍。进程被杀后，由下一次边界闹钟恢复。
2. **API 36 增强**：Android 16 上用 `Notification.ProgressStyle`，以课间和下课为分段点，配合节拍更新。
3. **诚实降级**：节拍不可用时（省电模式、精确闹钟权限被拒），**隐藏进度条**，只保留系统驱动的 Chronometer。宁可不显示，也不显示错误进度，符合 P2。
4. **测试**：抽出纯函数 `LiveProgressModel.compute(now, start, end) -> ProgressSnapshot` 做 JVM 单测；Robolectric 校验节拍调度和取消。

### 3.3 拒绝和推迟的理由（可以直接贴到 issue 回复）

- **#46**：帮别人用自己的月卡扫码，大概率违反共享单车平台的用户协议（账户和权益不得转借）；「抢单 + 抽手续费」等于做资金撮合中介，需要支付和经营资质，还会让 App 承担纠纷责任。与契约 I.8（「不收 🍑 / 资金真实使用边界」）的精神冲突。**关闭，不做。**
- **#20**：广告 SDK 会引入第三方数据采集，和本地优先、隐私承诺冲突，破坏开源项目的信任。**核心 App 不接广告**。可持续性建议走开源赞助、学校或社团合作、云端增值服务（已有 🍑 体系）。
- **#44**：三维建模、人流仿真的维护成本远超收益，且缺少真实数据校准。先用已有的 2.5D 底图加 Timeline 做「一日回放 + 通勤对比」，能覆盖 #44 首期验收中 80% 的用户价值。
- **#45 / #9**（v1.1：本轮只留接口，见 §0.5 D2）：价值成立，但在境内公开运营 UGC 社区，至少需要核实 ICP 备案、《互联网论坛社区服务管理规定》要求的后台实名，以及跟帖评论管理、安全评估等义务（**需要法务或指导老师确认，本文不构成法律意见**）。issue 里提出的「后台实名、前台匿名 + 内容拦截 + 脱钩删除」方向正确，采纳为设计基线。

---

## 4. 目标架构

### 4.1 分层总览

```
┌──────────────────────────────────────────────────────────────────────┐
│  Feature 层（Compose，neon 主导 UI）                                    │
│  today · timetable · grades · map · inbox · automation · groups ·       │
│  dorm · knowledge · guide · facilities · community · search · vault     │
├──────────────────────────────────────────────────────────────────────┤
│  引擎层（纯 Kotlin JVM，swe-2.1 主导）                                  │
│  AutomationEngine · NoticeExtraction · DegreeAuditor · TimetableSolver  │
│  ReviewPlanner · SplitCalculator · RoutePlanner(室外+室内) · FlowRunner  │
├──────────────────────────────────────────────────────────────────────┤
│  五大内核                                                               │
│  ① Timeline（统一时间流 + 忙闲）   ② Campus Graph（地点/楼/室/设施/流程）│
│  ③ Vault（实体变更日志 + FTS 搜索 + 备份恢复 + 同步）                    │
│  ④ Event Bus（领域事件，进程内）   ⑤ Trust（Provenance / 验证等级，已有）│
├──────────────────────────────────────────────────────────────────────┤
│  适配层：学校包 · 九协议网关 · 校园服务 Provider · 数据包 · 声明式插件     │
├──────────────────────────────────────────────────────────────────────┤
│  平台层：Room（每 Profile）· DataStore · WorkManager/AlarmManager ·      │
│  Keystore Vault · Cloud SDK（OpenAPI 生成客户端）                        │
└──────────────────────────────────────────────────────────────────────┘
                     │ HTTPS（OpenAPI 3.1）/ 事件（AsyncAPI）
┌──────────────────────────────────────────────────────────────────────┐
│ Campus Cloud：identity · community(groups/invites) · ugc(posts/reports) │
│ · moderation · media · ledger · place-status · content-catalog ·        │
│ sync(E2EE) · notifications · reservations(已有) · finance(已有)           │
└──────────────────────────────────────────────────────────────────────┘
```

**依赖方向铁律**：`feature → engine → kernel → model`。`adapter` 只依赖 `model` 和 `kernel` 的 Port。引擎和内核尽量保持纯 JVM，可在 CI 秒级测试。新增 `tools/dep-check` 在 CI 中禁止反向依赖。

### 4.2 内核 ①：Timeline（统一时间流）

**为什么是第一优先级**：#36 自动化、#31 收件箱、#33 空闲匹配、#34 复习排程、#41 截止追踪、#38 预约提醒、小组件、ICS 导出，全部需要「一个人的所有时间块」。

- 课程、考试、实践课、个人日程、待办截止、群组会议、复习块、专注、座位预约、办事步骤、申请节点，统一投影为 `TimelineEvent`。
- **投影，不迁移**：各领域保留自己的表；`TimelineSource` 负责把领域数据投影成事件。避免一次性大迁移。
- 新增一张 `personal_events` 表，承载用户日程、待办、群组会议和复习块，它们没有现成的家。

### 4.3 内核 ②：Campus Graph（校园实体图）

把现在散落在 `:core:location`（楼宇、路网）、`CampusStudyRoomCatalog`、`CampusHospitalPharmacyCatalog`、`CampusDiningRecommender`、社团场地中的数据，统一成可寻址实体：

`Campus → Building → Floor → Room / Facility / Entrance / IndoorNode`，再加上 `Service`（窗口、部门）和 `Procedure`（办事流程）。

- 每个实体有稳定 ID：`<schoolId>/<campusId>/<type>/<slug>`，带来源和最近核验时间。
- 数据来源分三级：**数据包（官方或维护者签名） > 用户本地覆盖 > 众包上报（云端，带时效）**。
- #35 室内路网、#38 办理地点、#39 设施、#13 空教室、#5 #16 数据纠错，都变成「改数据包」，不再「改代码」。

### 4.4 内核 ③：Vault（个人数据仓库）

三个子能力：
1. **ChangeJournal**：所有领域写入经过 `EntityWriter`，记录 before / after（CanonicalJson，已有）、actor（SYNC / USER / AUTOMATION / PLUGIN / RESTORE）和来源。直接满足 #42「变更历史」，也是 #31 去重、#36 审计、恢复误删的基础。
2. **Search**：Room FTS（`@Fts4`，中文分词首选 ICU tokenizer，不可用时回退为写入时做二元切分）。各领域通过 `SearchIndexContributor` 注册。
3. **Backup / Restore / Sync**：`dis-backup/v1` 格式（见 §6.5），默认**不包含凭据**，用口令派生密钥加密。恢复时先预览差异，再选择合并或替换。跨设备同步复用云端 `/api/v1/sync/snapshot`，演进为 E2EE 增量同步，云端只看到密文。

### 4.5 内核 ④：Event Bus（进程内领域事件）

- `DomainEvent` 是密封层级：`CourseChanged`、`ExamPublished`、`GradeReleased`、`UtilityBalanceSampled`、`GeofenceTransition`、`NoticeConfirmed`、`GroupEventConfirmed` 等。
- 写入流程：**先落库，再发布**。事件同时写入 `domain_outbox` 表，保证进程被杀后自动化仍能补处理，实现至少一次投递；消费者按 `eventId` 做幂等。
- Android 后台限制下，不常驻监听；由 WorkManager 唤醒消费 outbox。

### 4.6 内核 ⑤：Trust（已有，强制复用）

所有新模块对外暴露的数据结构都必须携带 `DataProvenance`，并明确 `OFFICIAL / DERIVED / ESTIMATED / USER_REPORTED / PREDICTED / SIMULATED`。**新增后三种**，服务于 #39 #32（上报）、#39 进阶（预测）和 #44（模拟），UI 用统一徽章展示（neon 负责视觉规范）。

---

## 5. 模块规划

### 5.1 Android 模块变更

**拆分现有巨型模块（W0，行为不变）**

| 现状 | 拆分后 |
|---|---|
| `CampusRepository`（8885 行） | `TimetableRepository`、`TermRepository`、`GradesRepository`、`ExamRepository`、`CampusServicesRepository`、`MomentsRepository`、`ProfileDataWiper`、`SyncOrchestrator`。原类保留为**委托外观**（`@Deprecated`），逐步迁移调用方 |
| `SettingsScreen.kt`（4519 行） | 按二级页拆成独立文件，加 `settings/ui` 与 `settings/state` 两个包 |
| `TodayScreen`、`CampusMapScreen`、`TimetableScreen` | 拆成 `XxxRoute`（拿 ViewModel） + `XxxScreen`（纯 UiState → UI） + 组件文件 |
| `HautTheme.kt`（2992 行） | `tokens/`（颜色、字号、形状、间距、动效）、`theme/`、`components/` |

**新增模块**

| 模块 | 类型 | 职责 | 依赖 | 服务的 issue |
|---|---|---|---|---|
| `:core:timeline` | JVM | `TimelineEvent`、`TimelineSource` SPI、忙闲计算、ICS 导出 | model | #36 #31 #33 #34 #41 |
| `:core:events` | JVM | `DomainEvent`、`EventBus`、outbox 协议 | model | 全部 |
| `:core:vault` | Android | ChangeJournal、EntityWriter、FTS、备份恢复、同步客户端 | database、events | #42 #34 #31 |
| `:core:campus-graph` | JVM + 数据包加载 | 地点、楼、室、设施、入口、室内节点、流程实体与查询 | model、school-packages | #35 #38 #39 #13 |
| `:core:automation` | JVM 核心 + Android 调度 | 规则模型、Trigger / Condition / Action SPI、运行记录、去重、试运行 | timeline、events | #36 |
| `:core:inbox` | JVM 解析 + Android 摄取 | 分享摄取、文本抽取 Port、相对日期解析、提议动作、指纹去重 | timeline、vault | #31 |
| `:core:textextract` | Android | 端侧 OCR Port（首选端侧中文识别模型，用 bundled 版本，不依赖 GMS）、PDF 文本抽取 | — | #31 #34 #17 |
| `:core:solver` | JVM | 约束求解（选课、复习排程、共同空闲） | timeline | #30 #34 #33 |
| `:core:knowledge` | Android | 课程资料库、资料与课程或教学周关联、复习任务 | vault、timeline、textextract | #34 |
| `:core:community` | Android | 云端群组、邀请、成员、UGC 客户端（由 OpenAPI 生成） | campusservices（云客户端） | #33 #37 #39 #45 |
| `:core:ledger` | JVM | 金额（Long，单位为分）、分摊算法、结算状态机 | model | #37 |
| `:core:guide` | JVM | 问答式流程运行器（FlowRunner）、个人清单、资格判断 | campus-graph、timeline | #38 #41 |
| `:core:ui-runtime` | Android Compose | `feature-ui-v1` 声明式节点渲染器，抽自现有 `XPeachSdkComponents` | designsystem | #43 |
| `:feature:inbox` `:feature:automation` `:feature:groups` `:feature:dorm` `:feature:knowledge` `:feature:guide` `:feature:facilities` `:feature:community` `:feature:search` `:feature:vault` | Compose | 各自的 Route、Screen、ViewModel | 对应 core | — |

**DI 建议**：不引入 Hilt（24+ 模块迁移成本高、构建变慢）。采用**手写构造注入 + 每个 feature 一个 `XxxDependencies` 接口**，由 `:app` 的 `AppGraph` 实现。好处是 neon 写的 UI 在 Preview 中可以直接注入假实现。

### 5.2 Cloud 模块变更

**拆分（W0）**：`apps/api/src/app.mjs` 拆成 `routes/<domain>.mjs`，统一注册到 `router.mjs` 的路由表（`method + path → handler + operationId`）。路由表与 OpenAPI 做 100% 双向比对（扩展现有 contract-parity 测试）。各 `packages/*/src/index.mjs` 按子领域拆文件，对外 API 不变。

**新增 packages**

| package | 职责 | 关键表 |
|---|---|---|
| `packages/community` | 群组（类型：study / dorm / club / project）、邀请码 / 二维码、成员、角色、退出与撤回 | `groups`、`group_members`、`group_invites` |
| `packages/availability` | 忙闲块共享（只存时间块，不存标题）、交集计算、投票、确认 | `busy_blocks`（带 TTL）、`meeting_polls`、`poll_votes` |
| `packages/ledger` | 只追加的账本、分摊、结算确认（**不涉及真实支付**） | `ledger_entries`、`ledger_splits`、`settlements` |
| `packages/place-status` | 设施或地点状态上报、按类型过期、分歧聚合 | `place_reports`、`place_status_view` |
| `packages/ugc`（**v1.1：本轮只有契约与 501 桩**） | 帖子、回复、引用、板块、全文搜索、举报 | `boards`、`posts`、`replies`、`reports` |
| `packages/moderation`（**v1.1：本轮只有契约与 501 桩**） | 关键词与 PII 拦截（学号、手机号、身份证、宿舍号）、审核队列、处置审计（复用 audit-events） | `moderation_queue`、`moderation_actions` |
| `packages/media`（**v1.1：本轮只有契约与 501 桩**） | 图片上传：服务端剥离 EXIF、转 WebP、随机 ID、私有桶、签名 URL | `media_objects` |
| `packages/content-catalog` | 数据包发布：办事流程、机会目录、设施目录、地图包；签名、版本、下架 | `content_packs`、`content_pack_versions` |
| `packages/sync` | 演进现有 `/sync/snapshot` 为 E2EE 增量块存储 | `sync_blobs`、`sync_cursors` |

**云端身份锚点**：所有社区实体挂在 `(cloud_user_id, school_id)` 下。公开显示名为每校独立生成的化名（例如「莲花街·松鼠 0427」），映射表单独存放，只有审核流程能反查。`/api/v1/me/data-delete` 必须覆盖社区数据，按 #45 的「30 天可撤销 + 到期脱钩」执行。

---

## 6. 接口指导

> 以下是**契约草案**。落地顺序：先 Schema / OpenAPI，再实现，最后补测试，符合契约 I.3。Kotlin 签名是指导性的，命名可调整，但语义（作用域、来源、幂等、确认）不得省略。

### 6.1 Timeline

```kotlin
// :core:timeline —— 纯 JVM
@JvmInline value class TimelineEventId(val value: String) // "<source>:<stableKey>"，跨同步稳定

enum class TimelineKind {
    COURSE, EXAM, PRACTICE, PERSONAL, TASK_DUE, GROUP_MEETING, REVIEW_BLOCK,
    FOCUS, RESERVATION, PROCEDURE_STEP, OPPORTUNITY_MILESTONE, DORM_DUTY
}
enum class EventStatus { SCHEDULED, CANCELLED_TODAY, RESCHEDULED, TENTATIVE, DONE }

data class TimelineEvent(
    val id: TimelineEventId,
    val scope: SchoolAccountScope,
    val kind: TimelineKind,
    val title: String,
    val start: Instant,
    val end: Instant?,              // null = 截止时间点
    val zone: ZoneId,               // 校区时区，禁止使用设备默认时区
    val place: PlaceRef?,           // 指向 Campus Graph
    val source: SourceRef,          // 回溯到领域实体
    val provenance: DataProvenance,
    val status: EventStatus,
    val busy: BusyLevel,            // BUSY / FREE / TENTATIVE，决定是否参与忙闲
    val revision: Long,
    val links: List<EntityRef> = emptyList()
)

interface TimelineSource {
    val id: String
    val kinds: Set<TimelineKind>
    fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<TimelineEvent>>
}

interface TimelineService {
    fun observe(scope: SchoolAccountScope, window: TimeWindow, filter: TimelineFilter = TimelineFilter.ALL): Flow<List<TimelineEvent>>
    suspend fun busyBlocks(scope: SchoolAccountScope, window: TimeWindow, granularity: Duration = 30.minutes): List<BusyBlock>
    suspend fun freeSlots(scope: SchoolAccountScope, window: TimeWindow, minLength: Duration, constraints: SlotConstraints): List<FreeSlot>
}

interface PersonalEventStore {   // personal_events 表
    suspend fun upsert(event: PersonalEventDraft, actor: Actor): TimelineEventId
    suspend fun delete(id: TimelineEventId, actor: Actor)
}

interface IcsExporter { fun export(events: List<TimelineEvent>, calendarName: String): String } // UID = TimelineEventId，保证幂等
```

**验收要点**：跨日、跨周、调休（已有 `calendar_overrides`）、校区时区、「今日停课」都要正确投影；同一课程多次同步后 `TimelineEventId` 保持不变；ICS 重复导入不产生重复事件。

### 6.2 Events 与 Vault

```kotlin
// :core:events
sealed interface DomainEvent {
    val eventId: String; val scope: SchoolAccountScope; val occurredAt: Instant
    data class CourseChanged(...,  val courseRef: EntityRef, val change: ChangeKind, val diff: FieldDiff) : DomainEvent
    data class ExamPublished(..., val examRef: EntityRef) : DomainEvent
    data class GradeReleased(..., val attemptRef: EntityRef) : DomainEvent
    data class UtilityBalanceSampled(..., val meterRef: EntityRef, val balanceCents: Long, val estimatedDaysLeft: Double?, val provenance: DataProvenance) : DomainEvent
    data class GeofenceTransition(..., val campusId: CampusId, val enter: Boolean, val accuracyMeters: Float) : DomainEvent
    data class NoticeConfirmed(..., val inboxItemId: String, val createdRefs: List<EntityRef>) : DomainEvent
    data class GroupEventConfirmed(..., val groupId: String, val eventRef: EntityRef) : DomainEvent
    data class DataStale(..., val capability: CapabilityKind, val since: Instant) : DomainEvent
}
interface EventBus {
    suspend fun publish(event: DomainEvent)        // 与业务写入同一事务写 outbox
    fun subscribe(consumerId: String, filter: (DomainEvent) -> Boolean): Flow<DomainEvent>
    suspend fun ack(consumerId: String, eventId: String)
}

// :core:vault
enum class Actor { SYNC, USER, AUTOMATION, PLUGIN, RESTORE, INBOX }
data class EntityChange(
    val ref: EntityRef, val op: ChangeOp, val before: CanonicalJson?, val after: CanonicalJson?,
    val actor: Actor, val actorDetail: String?, val at: Instant, val source: SourceRef?
)
interface EntityWriter { suspend fun <T> write(ref: EntityRef, actor: Actor, block: suspend () -> T): T } // 自动记录 journal + 发布事件
interface ChangeJournal {
    fun history(ref: EntityRef): Flow<List<EntityChange>>
    fun recent(scope: SchoolAccountScope, types: Set<EntityType>, limit: Int): Flow<List<EntityChange>>
    suspend fun revert(changeId: Long, actor: Actor = Actor.USER): RevertResult
}

data class SearchDocument(val ref: EntityRef, val type: EntityType, val title: String, val body: String,
                          val termId: String?, val at: Instant?, val facets: Map<String, String>)
interface SearchIndexContributor {
    val type: EntityType
    suspend fun fullRebuild(scope: SchoolAccountScope): Sequence<SearchDocument>
    fun incremental(scope: SchoolAccountScope): Flow<SearchDocumentChange>
}
interface CampusSearch { suspend fun query(scope: SchoolAccountScope, q: SearchQuery): SearchPage } // 结果带 type 与跳转 deeplink

interface BackupService {
    suspend fun export(scope: SchoolAccountScope, selection: BackupSelection, passphrase: CharArray, out: OutputStream): BackupManifest
    suspend fun inspect(input: InputStream, passphrase: CharArray): RestorePreview   // 版本、身份、条目数、冲突
    suspend fun restore(preview: RestorePreview, mode: RestoreMode /* MERGE / REPLACE */, resolutions: Map<EntityRef, Resolution>): RestoreReport
}
```

### 6.3 Automation（#36）

```kotlin
data class AutomationRule(
    val id: String, val scope: SchoolAccountScope, val name: String, val enabled: Boolean,
    val trigger: TriggerSpec, val conditions: List<ConditionSpec>, val actions: List<ActionSpec>,
    val dedupe: DedupePolicy = DedupePolicy.OncePerTriggerInstance,
    val quietHours: QuietHours? = null, val templateId: String? = null, val schemaVersion: Int = 1
)

sealed interface TriggerSpec {
    data class AtTime(val local: LocalTime, val days: Set<DayOfWeek>) : TriggerSpec
    data class BeforeEvent(val kinds: Set<TimelineKind>, val offset: Duration) : TriggerSpec
    data class OnEvent(val eventType: String, val filter: JsonObject?) : TriggerSpec      // 订阅 DomainEvent
    data class Threshold(val metric: MetricRef, val op: CompareOp, val value: BigDecimal) : TriggerSpec
    data class Geofence(val campusId: CampusId, val enter: Boolean) : TriggerSpec
    data class Plugin(val pluginId: String, val triggerId: String, val config: JsonObject) : TriggerSpec
}
// ConditionSpec / ActionSpec 同理，按 type + config(JsonObject) 组织，由 SPI 提供 JSON Schema

interface TriggerProvider   { val type: String; val configSchema: JsonSchema; fun arm(rule: AutomationRule, ctx: ArmContext): ArmHandle }
interface ConditionEvaluator{ val type: String; val configSchema: JsonSchema; suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult }
interface ActionExecutor    { val type: String; val configSchema: JsonSchema; val risk: ActionRisk; suspend fun execute(spec: ActionSpec, ctx: RunContext): ActionOutcome }

enum class ActionRisk { LOCAL_SILENT, LOCAL_NOTIFY, NEEDS_USER_CONFIRM, EXTERNAL_WRITE_FORBIDDEN }

data class ConditionResult(val passed: Boolean, val evidence: List<Evidence>, val certainty: Certainty) // 数据陈旧时 certainty = UNKNOWN
data class AutomationRun(val runId: String, val ruleId: String, val triggerInstanceKey: String,
                         val firedAt: Instant, val evaluations: List<ConditionResult>,
                         val outcomes: List<ActionOutcome>, val status: RunStatus, val dryRun: Boolean)

interface AutomationEngine {
    suspend fun dryRun(rule: AutomationRule, at: Instant): AutomationRun     // 试运行：预览将如何执行
    suspend fun onTrigger(ruleId: String, triggerInstanceKey: String)        // 幂等键 = ruleId + triggerInstanceKey
    fun runs(ruleId: String): Flow<List<AutomationRun>>
}
```

**强制规则**：
1. `certainty == UNKNOWN` 的条件**视为不通过**，并在运行记录中说明原因，对应 issue 中「失效数据不能直接触发确定性结论」。
2. `ActionRisk.EXTERNAL_WRITE_FORBIDDEN` 类型禁止在规则中注册。抢座、选课、退款等外部写操作不进入自动化，继续走现有三阶段调度和授权门禁。
3. 调度：时间类触发用 inexact 闹钟或 WorkManager；只有用户显式开启的「强提醒」才使用精确闹钟。批量合并唤醒，遵守电量预算。
4. 首期内置模板：早八带伞（需要天气 Provider SPI，具体供应商待定）、跨校区通勤提醒、水电低余额周五提醒、临时停课推荐自习。

### 6.4 Inbox（#31）

```kotlin
data class InboxItem(val id: String, val scope: SchoolAccountScope, val receivedAt: Instant,
                     val referenceDate: LocalDate?,           // 原消息日期；缺失时 UI 必须让用户补充
                     val originals: List<StoredBlobRef>,      // 图片 / PDF / 文本原件，留存可回溯
                     val origin: InboxOrigin /* SHARE_SHEET / CLIPBOARD / URL / PLUGIN */)

interface TextExtractor { suspend fun extract(blob: StoredBlobRef): ExtractedText } // 带行坐标，用于原文高亮回溯
interface RelativeDateResolver { fun resolve(span: String, reference: LocalDate, term: TermContext): DateResolution } // 明天 / 本周五 / 第 8 周周三
interface NoticeParser { val id: String; suspend fun parse(text: ExtractedText, ctx: ParseContext): List<ProposedAction> } // 规则解析器优先；AI 解析器需要单独同意

sealed interface ProposedAction {
    val confidence: Map<String, Confidence>; val sourceSpans: List<TextSpan>
    data class CreateEvent(val draft: PersonalEventDraft) : ProposedAction
    data class CreateTask(val title: String, val due: Instant?, val checklist: List<String>) : ProposedAction
    data class ModifyCourseOccurrence(val courseRef: EntityRef, val date: LocalDate, val change: OccurrenceChange) : ProposedAction
    data class UpdateExisting(val target: EntityRef, val diff: FieldDiff) : ProposedAction   // 识别为同一通知的更新
}
interface NoticeFingerprinter { fun fingerprint(a: ProposedAction): String; suspend fun findExisting(fp: String): EntityRef? }
interface ProposalApplier { suspend fun preview(p: List<ProposedAction>): ApplyPreview; suspend fun apply(confirmed: ApplyPreview): List<EntityRef> } // Actor.INBOX
```

### 6.5 数据包、学校包与插件 v2.1（#43 #38 #41 #39 #35）

在现有 `dis-extension/v2` 上**增量扩展**，保持 v1 / v2 兼容：

- **`types` 新增**：`data-pack`、`automation-trigger`、`automation-action`、`automation-template`、`inbox-parser`（声明式正则或模板，不执行代码）、`map-pack`、`theme`。
- **`slots` 新增**：`inbox.parsers`、`automation.templates`、`campus.facilities.layer`、`guide.procedures`、`guide.opportunities`、`search.providers`、`today.timeline.decorator`。
- **`permissions.kind` 新增**：`timeline.read`（只读忙闲或事件）、`campus-graph.read`、`notification.schedule`，每项都需用户单独授权。
- **新的数据包格式 `dis-datapack/v1`**（JSON，签名后随学校包分发，或从 `content-catalog` 拉取）：

```jsonc
{
  "format": "dis-datapack/v1",
  "packId": "haut.procedures",            // <schoolId>.<topic>
  "schoolId": "haut",
  "kind": "procedure-guide",             // procedure-guide | opportunity-catalog | facility-catalog | indoor-graph | period-preset | pharmacy-catalog
  "version": "2026.11.1",
  "sources": [{ "url": "https://...", "title": "...", "retrievedAt": "2026-11-20" }],
  "verifiedAt": "2026-11-20", "reviewBy": "2027-05-20",   // 超过 reviewBy 时 UI 显示「待重新核验」
  "maintainers": ["github:xxx"],
  "items": [ /* kind 对应的 item schema */ ]
}
```

- **办事流程 item**（#38）：`steps[]`、`questions[]`（分支条件）、`materials[]`、`placeRef`、`officeHours`、`contacts[]`、`dependsOn`。由 `:core:guide` 的 `FlowRunner` 根据用户回答求出个人路径。
- **贡献流水线**：外部贡献者向 `school-data` 仓库（或本仓库的 `data/` 目录）提 PR，CI 做 schema 校验、链接存活检查和差异渲染，维护者合并后用 `dis-plugin sign` 签名，再发布到 `content-catalog`。#17 药品、#16 场地这类数据纠错从此走 PR，不再走 issue。

### 6.6 学业智能（#29 #30）

```kotlin
// 扩展 :core:academic-plan
interface TrainingPlanImporter { fun import(input: ImportInput /* univ-training-plan/v1 JSON | CSV | 教务网关 */): TrainingPlanImport }
data class AuditOverride(val attemptRef: EntityRef, val mapTo: RequirementGroupId?, val reason: String, val at: Instant) // 人工认定，进 journal
interface DegreeAuditor { fun audit(plan: TrainingPlan, attempts: List<GradeAttempt>, overrides: List<AuditOverride>, now: LocalDate): AuditReport }
data class AuditReport(val groups: List<GroupProgress>, val gaps: List<RequirementGap>,
                       val risks: List<PlanRisk>,            // 仅春季开设、先修未完成、影响毕业时间
                       val trace: List<AuditStep>,           // 每一步：规则、课程、学分、结果，用于「查看依据」
                       val trust: TrustLevel)                // 未导入官方方案时为 ESTIMATED

// :core:solver（被 #30 #33 #34 复用）
data class SelectionProblem(val candidates: List<TeachingClassOption>, val hard: List<HardConstraint>,
                            val soft: List<WeightedPreference>, val travel: TravelTimeMatrix, val creditRange: ClosedRange<BigDecimal>)
sealed interface SolveResult {
    data class Solutions(val ranked: List<ScheduleSolution>) : SolveResult       // 每套方案附带偏好满足明细和取舍说明
    data class Infeasible(val conflictCore: List<ConstraintRef>, val relaxations: List<Relaxation>) : SolveResult
}
interface TimetableSolver { fun solve(p: SelectionProblem, maxSolutions: Int = 5, budget: Duration = 2.seconds): SolveResult }
```

- 求解：回溯 + 约束传播 + 分支定界（软约束加权）。无解时用 **QuickXplain** 求最小冲突集，直接回答「为什么无解」。
- 通勤：`TravelTimeMatrix` 由 `:core:location` 的 A* 路网预计算，按楼宇对缓存；跨校区标记为「不可在课间完成」。
- **不提交**：方案只导出为选课草稿（`:core:selection` 已有），提交仍由用户在学校系统完成。

### 6.7 Cloud OpenAPI 新增端点草案

所有端点都在 `/api/v1` 下，需要 XPEACH SSO 会话，并按 `school_id` 隔离；写操作支持 `Idempotency-Key` 头；错误码进入 `error-catalog.yaml`；权限进入 `permission-catalog.yaml`。

| 领域 | 方法与路径 | 说明 |
|---|---|---|
| 群组 | `POST /groups` · `GET /groups` · `GET /groups/{groupId}` · `PATCH /groups/{groupId}` · `DELETE /groups/{groupId}` | 类型为 study / dorm / club / project |
| 邀请 | `POST /groups/{groupId}/invites` · `POST /group-invites/{code}/accept` · `DELETE /groups/{groupId}/invites/{inviteId}` | 邀请码带过期时间和次数上限 |
| 成员 | `GET /groups/{groupId}/members` · `DELETE /groups/{groupId}/members/{memberId}`（踢出或自己退出） · `PATCH .../members/{memberId}`（角色、共享级别） | 退出即撤回忙闲共享 |
| 忙闲 | `PUT /groups/{groupId}/availability/me`（上传忙闲块，带 TTL） · `DELETE /groups/{groupId}/availability/me` · `POST /groups/{groupId}/availability/query`（时长、范围、最少人数 → 候选时段） | 只存时间块；共享粒度 ≥ 30 分钟 |
| 投票 | `POST /groups/{groupId}/polls` · `POST /polls/{pollId}/votes` · `POST /polls/{pollId}/confirm` | confirm 后发出 `group.event.confirmed` |
| 账本 | `POST /groups/{groupId}/ledger/entries` · `GET .../ledger/entries` · `POST .../ledger/entries/{entryId}/amend`（只追加更正） · `GET .../ledger/balances` · `POST .../settlements` · `POST /settlements/{id}/confirm` | 金额用整数分；两方确认 |
| 值日与报修 | `POST /groups/{groupId}/rotas` · `POST /rotas/{rotaId}/swaps` · `POST /groups/{groupId}/repairs` · `PATCH /repairs/{repairId}` | — |
| 地点状态 | `POST /places/{placeRef}/reports` · `GET /places/status?schoolId&bbox|placeRefs` · `POST /place-reports/{id}/flag` | 按类型 TTL；返回分歧 |
| UGC（**保留，返回 501**） | `GET /boards` · `GET /boards/{boardId}/posts` · `POST /posts` · `GET /posts/{postId}` · `DELETE /posts/{postId}` · `POST /posts/{postId}/replies` · `GET /search/posts?q=` | 发帖前走 moderation 预检 |
| 举报与审核（**保留，返回 501**） | `POST /reports` · `GET /admin/moderation/queue` · `POST /admin/moderation/{itemId}/actions` | 所有处置写入 audit-events |
| 媒体（**保留，返回 501**） | `POST /media/uploads`（返回上传槽位） · `POST /media/uploads/{id}/complete` | 服务端剥离 EXIF，转为 WebP |
| 内容包 | `GET /content-packs?schoolId&kind` · `GET /content-packs/{packId}/versions/{version}` · `POST /admin/content-packs` | 签名校验 |
| 同步 | `PUT /sync/blobs/{blobId}` · `GET /sync/changes?cursor=` | E2EE 密文 |

**AsyncAPI 新事件**：`group.member.joined|left`、`group.availability.updated`、`group.event.confirmed`、`group.event.conflict_detected`（成员调课导致冲突时**只提示，不自动改期**）、`ledger.entry.created|amended`、`settlement.confirmed`、`place.status.changed`、`ugc.post.moderated`、`content-pack.published`。

### 6.8 AI 通道（BYOK，D3）

```kotlin
// :core:ai —— 接口纯 JVM，Key 存储在 Android 层
data class AiEndpoint(val baseUrl: HttpsUrl, val model: String, val supportsVision: Boolean)
interface AiKeyStore {                       // Keystore AES-GCM，按 profileId 分区，noBackupFilesDir
    suspend fun put(scope: SchoolAccountScope, endpoint: AiEndpoint, key: CharArray)
    suspend fun clear(scope: SchoolAccountScope)
    fun status(scope: SchoolAccountScope): Flow<AiKeyStatus>  // NONE / CONFIGURED / INVALID，永不暴露 Key 本身
}
data class AiRequest(val purpose: AiPurpose /* INBOX_PARSE / KNOWLEDGE_QA / QUIZ */, val messages: List<AiMessage>,
                     val attachments: List<StoredBlobRef>, val maxTokens: Int)
data class AiSendPreview(val endpointHost: String, val model: String, val textChars: Int, val attachmentCount: Int, val redactions: List<Redaction>)
interface AiProviderPort {
    suspend fun preview(scope: SchoolAccountScope, req: AiRequest): AiSendPreview   // 发送前给用户看
    suspend fun send(scope: SchoolAccountScope, req: AiRequest, consent: AiConsentToken): AiResult
}
```

- `AiConsentToken` 只能由用户在预览页点「发送」生成，单次有效；自动化引擎**不能**调用 AI 动作。
- 发送前做本地脱敏：学号、手机号、身份证号、宿舍号，命中时在预览里列出。
- AI 的结果一律是 `ProposedAction` 或 `ESTIMATED` 数据，必须经用户确认（G2），并标记「模型生成」。
- 擦除链路清空 Key；备份格式（dis-backup/v1）的 schema 层面就不包含 Key 字段，并有测试断言。

### 6.9 社区接口预留（D2）

```kotlin
interface CommunityForumPort {
    val availability: Flow<FeatureAvailability>            // 本轮恒为 Disabled(reason = NOT_ENABLED)
    suspend fun listBoards(scope: SchoolAccountScope): CommunityResult<List<Board>>
    suspend fun listPosts(scope: SchoolAccountScope, boardId: String, cursor: String?): CommunityResult<PostPage>
    suspend fun createPost(scope: SchoolAccountScope, draft: PostDraft, idempotencyKey: String): CommunityResult<PostRef>
    suspend fun report(scope: SchoolAccountScope, target: EntityRef, reason: ReportReason): CommunityResult<Unit>
}
object DisabledForumPort : CommunityForumPort { /* 全部返回 CommunityResult.NotEnabled */ }
```

云端保留端点的统一响应：`501`，body 为 `{"code":"FEATURE_NOT_ENABLED","feature":"community.forum"}`。契约测试逐个断言。

---

## 7. 数据与迁移

- Room 从 v10 开始，每个波次最多一次 schema 升级，**每次都必须有**：导出 schema JSON、`MIGRATION_N_N+1`、基于真实旧库的 Robolectric 迁移测试（沿用 v4→v5 的做法）、中断回滚测试。
- **W1（v11）新表**：`personal_events`、`entity_journal`、`domain_outbox`、`search_fts`（FTS4 虚表） + `search_docs`、`blob_store`（收件箱原件）。
- **W2（v12）新表**：`automation_rules`、`automation_runs`、`automation_dedupe`、`inbox_items`、`inbox_proposals`。
- **W3（v13）新表**：`training_plan_imports`、`audit_overrides`、`selection_scenarios`、`course_materials`、`review_tasks`。
- **W4**：以云端为主，本地只缓存群组快照。缓存表 `community_cache_*` 进入 `wipeAllPersonalData()` 擦除链路，但**不缓存 UGC 原文以外的他人隐私**。
- 擦除链路：每个新表都必须注册到 `ProfileDataWiper`，并在测试中断言「擦除后行数为 0」。

---

## 8. 合规与安全门禁（上线前必须逐条打勾）

| 门禁 | 适用 | 要求 |
|---|---|---|
| G1 本地优先 | 全部 | 功能在无云账号时可用，或明确说明为什么需要云 |
| G2 确认写入 | #31 #34 #36 | 推断结果必须经过「预览 → 确认」；运行记录可审计 |
| G3 AI 单独同意 | #31 #34 进阶 | **用户自带 Key（BYOK），云端不代理、不存储**；Key 独立加密、按 Profile 隔离、不进备份和日志；发送内容可预览；默认关闭；显示「模型补充」与「资料原文」的区别 |
| G4 忙闲最小化 | #33 | 只共享时间块；粒度 ≥ 30 分钟；退出即删；TTL 过期即删 |
| G5 资金不落地 | #37 | 只记账不收付款；不接入支付；金额整数分；更正只追加 |
| G6 UGC | #39 #45 #9 | 后台实名锚点（SSO sub + schoolId），前台化名；发布前 PII 拦截；举报通道；审核队列；处置审计；删号 30 天后脱钩；图片剥离 EXIF；**并在法务核实 ICP 备案及论坛社区、跟帖评论、安全评估等义务后才能公开上线** |
| G7 数据包来源 | #38 #41 #17 | 每条数据都有官方来源和 `verifiedAt`；过期显示「待核验」；不虚构「医保后单价」这类全员固定值 |
| G8 插件 | #43 | 声明式；签名；权限 Broker；单插件故障隔离和熔断；零修改宿主（契约 I.4） |
| G9 位置 | #36 #35 | 地理围栏只用粗定位；不上传轨迹；精度不足时声明不确定 |

---

## 9. 迭代波次

> 单位使用相对规模而不是日历周，因为 AI 代理的吞吐和人工复核节奏都不固定。每个波次结束都做一次**阶段联调验收**（§12.8）。阶段 S 内**不发版**（D12），issue 回复按 D13 先写草稿。

| 波次 | 主题 | 交付 | 出口验收 |
|---|---|---|---|
| **W0 清账与地基** | 让两个代理可以并行开工 | 关闭 9 个已完成 issue；修 #24；#12 加提示和测试；#22 节次编辑器和实践课多日排课；拆分 D1–D3；引入 `XxxRoute / XxxScreen / UiState` 规范与 Roborazzi 截图测试；依赖方向检查；同步 origin/main | 全量单测、lint、双 APK 通过；截图基线建立；拆分前后行为快照一致 |
| **W1 个人内核** | Timeline + Vault | `:core:timeline`、`:core:events`、`:core:vault`（journal、FTS、本地备份恢复）；Today、Timetable、小组件改为消费 Timeline；ICS 导出；全局搜索页 | 课表、考试、实践课、停课全部正确投影；备份 → 卸载 → 恢复零差异；搜索中文命中 |
| **W2 自动化与收件箱** | 让 App 主动工作 | `:core:automation` + 4 个模板 + 试运行 + 运行记录；`:core:inbox` + 端侧 OCR + 相对日期 + 去重 + 改课预览 | #36 与 #31 的首期验收条款逐条通过；陈旧数据不触发；重复分享不重复创建 |
| **W3 学业智能** | 选课季杀手功能 | 培养方案导入、审计、依据回溯、人工认定；选课求解器 + 无解解释 + 方案对比；课程资料库、搜索、复习排程 v1 | #29 #30 #34 首期验收通过；求解 50 个候选教学班 < 2 秒 |
| **W4 群组云** | 从个人走向协作 | 云端 community、availability、ledger、place-status；#33 #37 #39 端到端 | 撤回共享立即生效；账目精确到分且可重算；状态按 TTL 过期 |
| **W4 附：社区接口预留** | #45 #9 | 只写契约和 Port；保留端点统一返回 501；Android 侧默认关闭、无入口 | 契约测试断言 501 与错误码；无 UI 入口；开关默认关闭 |
| **W5 校园知识网络** | 数据驱动 | `dis-datapack/v1`、content-catalog、贡献流水线；#38 #41 首批 10 个高频事项；设施目录 | 至少 1 个带分支的流程；过期数据提示；外部贡献者 PR 走完全流程 |
| **W6 平台与空间** | 生态与多校 | 插件 v2.1 + 开发者工具（validate / simulate / pack / sign）；室内路网试点 1 栋楼；一日回放；第二所学校（需要真实授权账号，**移出阶段 S**，列为用户待办 §16） | 非官方示例插件零修改宿主安装；室内跨楼层路线；其余各项通过；第二所学校不在阶段 S 验收范围 |

**并行策略**：W1 之后，W2、W3、W4 的云端部分可以三线并行（分属不同模块，冲突面小）；W5 的数据工作可以随时由社区并行推进。

---

## 10. 双代理工作流：swe-2.1（代码与框架）→ neon（前端交互）

### 10.1 职责边界

| | swe-2.1 | neon |
|---|---|---|
| 负责 | 契约（OpenAPI / AsyncAPI / Schema）、领域模型、引擎、Room 与迁移、Repository、ViewModel、`UiState` / `UiAction` / `UiEffect`、骨架 Screen、云端实现、全部非 UI 测试 | `feature/*/ui/**` 与 `core/designsystem/**`：视觉、布局、动效、交互细节、无障碍、截图测试、文案润色 |
| 禁止 | 在骨架 UI 中做视觉打磨（浪费，且会和 neon 冲突） | 修改 ViewModel 签名、UiState 字段、Repository、契约；需要改时提「UI 契约变更请求」 |
| 交付物 | 可运行的功能 + `XxxPreviewFixtures`（覆盖全部状态） | 截图基线 + 交互说明 + 无障碍检查记录 |

### 10.2 Feature 包结构（W0 定为规范）

```
feature/<name>/src/main/kotlin/.../feature/<name>/
├── <Name>Route.kt            // swe：拿 ViewModel，收集状态，处理 Effect
├── <Name>ViewModel.kt        // swe
├── state/
│   ├── <Name>UiState.kt      // swe：@Immutable，只含展示所需字段
│   ├── <Name>UiAction.kt     // swe：用户意图（密封）
│   └── <Name>UiEffect.kt     // swe：一次性事件（导航、Toast）
├── ui/                       // neon 的地盘
│   ├── <Name>Screen.kt       // (UiState, (UiAction)->Unit) -> Unit，无副作用
│   └── components/...
└── preview/
    └── <Name>PreviewFixtures.kt  // swe 提供，neon 可补充
```

**统一状态矩阵**（每个 Screen 必须在 fixtures 里覆盖，对应 GAP 14）：`Loading`、`Empty`、`Content`、`Partial`（部分数据）、`Stale`（过期但可用）、`Error(可重试 / 不可重试)`、`NeedsPermission`、`NeedsLogin`、`Offline`、`Unsupported`（本校无此能力）。

### 10.3 分支与合并

v1.1 起以 §12.5 为准：`main` 是唯一集成分支，一张卡一个分支，审阅和验证都通过后由集成列车合入；只用 merge commit。

### 10.4 Definition of Done（每张卡）

- [ ] 开发机现场门禁通过（§12.3），汇总里写命令和结果
- [ ] 契约先行（如涉及云端或 Schema），`contract:check` 通过
- [ ] 纯 JVM 逻辑单测覆盖正常、边界和失败路径；涉及 Room 的有迁移测试
- [ ] 新数据带 `DataProvenance`；新表注册到擦除链路
- [ ] fixtures 覆盖状态矩阵；截图测试通过（neon 阶段）
- [ ] 无硬编码学校逻辑（工大专属能力走 Provider 或数据包）
- [ ] `docs/` 有对应的开发者说明；`UNIVERSAL_EXECUTION_PLAN.md` 台账更新
- [ ] 验证等级如实标注，未上真机的不写 `DEVICE_TESTED`

---

## 11. 风险与待决问题（v1.1：第 1、3、4、6 条已由用户回答，见 §0.5）

1. **学校合作**：是否尝试与信息化办公室或学生组织正式合作？这直接决定 #38 #41 的数据权威性、#45 的合规路径，以及教务、校园卡写接口能否获得授权。
2. **云端预算与备案**：W4 之后云端从「预约工具」变成「社区平台」，需要确认服务器规格、对象存储、域名 ICP 备案主体。
3. **论坛范围**：#45 是做「全校公开论坛」，还是先做「群组内讨论」（风险低得多，复用 W4 的群组能力）？**建议先做后者。**
4. **AI 供应商**：#31 #34 的 AI 增强走用户自带 Key（已有 Parcel AI Key 机制），还是由云端代理？前者隐私更好、零成本，建议作为默认。
5. **天气数据源**：#36 的「带伞」模板需要选定天气 API（注意许可和配额）。
6. **代理能力**：swe-2.1 和 neon 能否在本机执行 `./gradlew` 和截图测试？不能的话，每张卡的验收需要由你或 CI 执行。
7. **多校扩张优先级**：W6 的第二所学校选哪所？建议选一所使用 `ZF_MODERN` 正方新版、并且有可靠测试账号的学校，以便最快达到 `SCHOOL_VERIFIED`。

---

## 12. 协作模式：主代理与子代理（v1.1，参照企业计划第 18 章）

> 主代理只负责编排和决策，不写代码、不跑测试、不读完整 diff、不逐行审阅。开发、测试、审阅、组车、验收全部交给子代理，子代理只交回结构化汇总。目的是让主代理的上下文始终只装着进度、决策和风险。

### 12.1 角色与职责

| 角色 | 做什么 | 不做什么 | 分支 |
|---|---|---|---|
| 主代理 | 写任务卡、派发子代理；维护任务板和决策记录；读汇总、`gh pr view/checks` 元数据和审阅结论；分配 Room 与迁移编号；决定合并或返工；按列车顺序执行合并；准备 issue 回复草稿交给用户 | 写业务代码、跑测试、打开完整 diff、逐行审阅 | `main` 上的 `task-notes/campus/`（经 PR） |
| 开发子代理 | 按任务卡实现一张卡（含骨架 UI 和 fixtures），开发机现场门禁通过后推送并开 PR | 改卡片范围以外的文件；合并 PR | `feat/cis-<卡号>-<简称>`、`chore/cis-<卡号>-<简称>` |
| 测试子代理 | 对指定 PR 独立重跑门禁；补缺失的回归用例，单独提 PR | 改业务逻辑 | `test/cis-<卡号>` |
| 审阅子代理 | 按领域审阅：数据与迁移、隐私与凭据、契约、Android 架构、云端。逐条对照本计划小节和能力编号，输出阻断与非阻断清单 | 改代码；审阅自己参与过的 PR | 只读 |
| 调研子代理 | 只读调研，产出写入 `task-notes/campus/research/<编号>.md`，作为后续任务卡的附件 | 改业务代码 | `docs/cis-<编号>` |
| 集成列车子代理（槽 9） | 把审阅和验证都通过、互不改同一文件的 PR 按依赖顺序 merge 到列车分支，在开发机上跑全量门禁，推送后触发一次托管 CI，报告主代理合入顺序；失败时定位到具体 PR 并退回 | 改业务逻辑；自己合入 `main` | `train/cis-<序号>`（只做 merge） |
| 阶段联调验收子代理（槽 10） | 每趟列车合入后，在开发机上按能力编号逐项验证已落地的能力（§12.8），报告写入 `task-notes/campus/acceptance/stage-<序号>.md`；平时维护机读能力矩阵和验收脚本 | 参与被验证功能的开发；改业务逻辑 | `acc/cis-stage-<序号>` |
| 终验子代理 | 执行阶段 S 终验（§14） | 参与过被验收功能的开发；只重跑失败项就下结论 | `acc/cis-final-<轮次>` |

### 12.2 本机与开发机的分工（D5）

本机 Mac 只有 16GB 内存，只承载**主代理 + 10 个子代理**的会话，以及 `ssh`、`git`、`gh` 这类轻量命令。

| 本机允许 | 本机禁止 |
|---|---|
| 代理会话；只含源码的 worktree（用本地读写工具编辑）；`git commit/push`；`ssh` 到开发机执行门禁；`gh` 建 PR、查 CI、合并；阅读计划与任务板 | `./gradlew`（任何任务）、Android Studio、Kotlin 或 Java 语言服务器、模拟器、`npm install`、`npm test`、`node --test`、Docker、全仓索引 |

**目录约定**

| 位置 | 路径 |
|---|---|
| 本机源码 worktree | `/Volumes/SN 770/Developer/campus-worktrees/<仓库>/<分支>`（每个子代理独占一个，不共用目录和分支） |
| 开发机根目录 | `/srv/xpeach-dev/campus/`，下设 `repos/<仓库>.git`（裸库）、`build/<仓库>/<分支>`（构建 worktree）、`cache/{gradle,npm}`、`android-sdk/`、`bin/`、`locks/`、`logs/`、`artifacts/` |
| 开发机脚本源码 | 仓库内 `tools/devbox/`（公开，不含地址和凭据），由 INF-00 安装到 `/srv/xpeach-dev/campus/bin/` |

**一次开发循环**

1. 本机：`git worktree add` 建源码 worktree（从最新 `origin/main` 拉出），用读写工具编辑。
2. 本机：提交，然后 `git push devbox <分支>`（远端 `devbox` 指向开发机裸库，只走 SSH 公钥）。
3. 本机：`ssh "$CIS_DEVBOX" /srv/xpeach-dev/campus/bin/cis-gate <仓库> <分支> <门禁> [参数]`。脚本在开发机构建 worktree 中检出该分支的确切 SHA 并运行门禁，完整日志写到 `logs/`，**只把退出码、SHA 和末尾约 60 行摘要打印回本机**。
4. 门禁通过后：`git push origin <分支>`，`gh pr create`。开发机上**不放任何 GitHub 凭据**，两个仓库都是公开的，开发机从 GitHub 只读拉取即可。
5. **禁止用 ssh 内联 heredoc 写文件**（企业项目实测造成大量转义错误和子代理崩溃）；所有文件都在本机 worktree 里编辑后经 git 传输。

### 12.3 开发机资源预算与门禁

开发机为 32 核、125GB 内存，与企业项目共用（2026-11-26 实测已用约 43GB，可用约 82GB；磁盘可用约 590GB）。**校园项目的预算：内存合计不超过 40GB，重型构建同时最多 4 个。**

| 项 | 规则 |
|---|---|
| 重型构建槽 | `cis-gradle` 用 `flock` 实现 4 个构建槽（`locks/gradle.1..4`），拿不到就排队。排队不算空闲，子代理先做下一步编辑 |
| 内存守护 | 开工前读 `/proc/meminfo` 的 `MemAvailable`，低于 24GB 就等待；存在 `/srv/xpeach-dev/campus/PAUSE` 文件时不启动新构建（企业项目独占验收时由用户或主代理创建） |
| 单构建上限 | 优先用 `systemd-run --user --scope -p MemoryMax=10G`；不可用时只靠 JVM 参数限制，并记录到 `DEVBOX.md` |
| Gradle 参数 | 校园专用 `GRADLE_USER_HOME=/srv/xpeach-dev/campus/cache/gradle`，其中 `gradle.properties` 设 `org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m`、`kotlin.daemon.jvmargs=-Xmx2g`、`org.gradle.workers.max=6`、`org.gradle.daemon.idletimeout=600000`，开启 caching 和 parallel。用户目录配置会覆盖仓库里的 `-Xmx4096m` |
| 模拟器 | 同时最多 1 个，内存 4GB；前提是 `xpeach-dev` 已加入 `kvm` 组（用户待办，§16）。阶段 S 的界面验证以 Roborazzi（JVM 截图，不需要模拟器）为主 |
| 容器 | 云端测试需要的 PostgreSQL、Redis 统一命名 `cis-<仓库>-<分支短名>-<用途>`，只绑定 `127.0.0.1` 随机端口；结束时（含失败）按名字精确清理 |
| 磁盘 | 校园目录超过 150GB 时，由一个子代理清理过期的构建 worktree 和日志 |

**门禁清单（`cis-gate` 的第三个参数）**

| 门禁 | 内容 | 何时必须跑 |
|---|---|---|
| `quick` | 指定模块的 `testDebugUnitTest`（参数传模块名） | 开发中随时 |
| `android` | `testDebugUnitTest :app:lintDebug :app:assembleDebug`，加上 `verifyRoborazziDebug`、依赖方向检查、文件行数门禁（这三项在 W0-07 合入后生效） | 每个 Android PR 开 PR 前 |
| `android-full` | `android` 再加 `:app:lintRelease :app:assembleRelease` 和全部 Room 迁移链测试 | 列车、阶段验收、终验 |
| `cloud` | `npm ci && npm test && npm run contract:check`（需要数据库的测试起 `cis-*` 容器） | 每个云端 PR 开 PR 前 |
| `contracts` | 两仓 `contracts/` 一致性检查加上两仓的 contract-ci | 任何改动 `contracts/` 的 PR |
| `secrets` | 敏感信息扫描：开发机地址、私网 IP、Token 格式、私钥头、`.env` 文件 | 每个 PR（INF-01 实现） |

### 12.4 十槽位满载调度（D7）

槽 1–8 通用，槽 9 集成列车，槽 10 阶段联调验收。下表是参考排布，执行时以动态补位为准；每一格都满足 §13 的依赖。

| 波次 | 槽 1 | 槽 2 | 槽 3 | 槽 4 | 槽 5 | 槽 6 | 槽 7 | 槽 8 | 槽 9 | 槽 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| A | INF-00 | INF-01 | R05 | R06 | R01 | R02 | R03 | W0-09 | 审阅（列车空闲时） | 机读能力矩阵与验收脚本骨架 |
| B | W0-05 | W0-07 | W0-08 | W0-02 | INF-02 | W1-01 | W1-02a | R04 | 列车 / 审阅 | 基础设施联调 |
| C | W0-05 续 | W0-06a | W0-06b | W0-10 | CT-01 | W4-09 | W2-04 | W2-01 | 列车 | 阶段验收 |
| D | W0-03 | W0-04 | W0-06c | W1-07 | CT-02 | W4-01 | W3-02 | W2-06 | 列车 | 阶段验收（W0） |
| E | W1-02b | W1-03 | W1-05 | CT-03 | W4-02 | W4-04 | W3-01 | W5-01 | 列车 | 阶段验收 |
| F | W1-04 | W2-02 | W2-05 | CT-04…07 | W4-03 | W4-05 | W3-04 | W5-03 | 列车 | 阶段验收（W1） |
| G | W2-03 | W2-07 | W3-03 | W3-05 | W4-06 | W4-07 | W4-08 | W4-10 | 列车 | 阶段验收（W2、W3） |
| H | W4-11 | W4-12 | W5-02 | W5-04 | W5-05 | W5-06 | W5-07 | W6-01 | 列车 | 阶段验收（W4、W5） |
| I | W6-02 | W6-03 | W6-04 | W6-05 | W3-06 | 缺陷修复 | 缺陷修复 | 缺陷修复 | 列车 | 阶段验收（W6） |
| J | 阶段 S 终验 | 证据复核 | 失败分诊 | 用户待办与晨间报告准备 | — | — | — | — | 暂停 | 协助终验 |

**调度规则**

1. **槽位恒为 10**：任何子代理交回或被阻塞时，主代理在同一轮内补位，不留空位，不超过 10 个。
2. **补位优先级**：关键路径上已就绪的开发（INF-00 → W0-05 → W0-06 → W1-02b → W1-03 一线最优先）→ 其他已就绪的开发 → 测试与回归补强 → 审阅 → 调研 → 文档。「已就绪」指 §13 中的依赖都已合入 `main`。
3. **审阅常驻**：列车空闲时槽 9 兼做审阅；待审 PR 超过 2 个时，临时把一个调研或文档槽改成审阅。
4. **冲突优先于满载**：热点文件（§12.11）同一时间只给一个子代理，不为凑满而制造冲突，这时用调研、测试补强或审阅补位。
5. **等待不算空闲**：等构建槽、等 CI、等依赖时，子代理继续编辑下一步或写测试，不释放槽位。
6. **返工找原主**：优先派回原子代理；原子代理已释放时，新子代理从远端分支和任务卡继续。
7. **终验期间**：终验子代理独占开发机的构建槽，其余槽位只做不需要开发机重型构建、也不合并代码的工作。
8. **槽位看板**：`TASKBOARD.md` 顶部维护 10 行槽位状态（槽号、子代理、任务、分支或 PR、开始时间、状态、阻塞项），每次变化立即更新。

### 12.5 分支、提交与合并（D8）

1. **一张卡一个分支**，从最新 `origin/main` 拉出；依赖合入 `main` 前不开工，不做分支叠分支。卡片过大时拆成 `-a`、`-b`，各自走完 PR。
2. **提交节奏**：每完成一个可验证的小步就提交并推送到开发机裸库；开 PR 后的每次推送都要同时推到 GitHub 并回读远端 SHA。会话中断或换人时，进度都在远端分支上。
3. **提交信息**用中文，正文包含：卡号 / 能力编号 / 改动 / 原因 / 实际验证 / 风险与恢复。一个提交只做一件事；格式化、生成文件与逻辑改动不混在同一个提交里。
4. **PR**：门禁全部通过后开 PR，正文写卡号、计划小节、能力编号、提交列表和开发机上实际运行的门禁与结果。PR 前对比 `git config user.name` 与历史作者，不是核心作者时声明 AI 生成或辅助。
5. **合并方式**：只用 `gh pr merge --merge`。禁止 squash、rebase 合并、force push、改写已发布历史，也不在最后用一个汇总提交代替开发过程。同步 `main` 用 merge。
6. **合并列车**：审阅与验证都通过、互不改同一文件的 PR 进入同一列车；有依赖的按依赖顺序排列。槽 9 从最新 `main` 拉出 `train/cis-<序号>`，按顺序 `git merge --no-ff` 每个分支（不改代码），在开发机跑 `android-full` / `cloud` / `contracts`，推送后跑一次托管 CI。全部通过后主代理按同样顺序逐个 `gh pr merge --merge`。列车失败就把出问题的 PR 移出退回，其余重新组车。
7. **合并之后**：主代理回读 `main` 的 SHA，在任务板记录「卡 → PR → 合并 SHA」，并确认上一次记录的合并 SHA 仍是 `main` 的祖先。远端分支保留不删。
8. **`main` 的健康**：每次合并后 `main` 必须可构建、测试全绿。合并后 CI 失败，先暂停新列车，派子代理修复；必要时用 revert PR 回退最后一趟列车（不改写历史）。
9. **两个仓库成对改动**（契约、云端与客户端联动）：两侧 PR 互相引用，进同一趟列车，按「云端契约 → 云端实现 → Android 客户端」顺序合入。

### 12.6 合并门槛（缺一项不合并）

1. 开发机现场门禁通过，托管 CI 全绿（以列车分支上的一次全量为准）。
2. **交叉审阅**无阻断（D9）：至少 1 个没参与该 PR 的审阅者；Room 迁移、擦除链路、凭据与 Key 存储、契约、自动化动作风险等级、备份格式类 PR 需要 2 个独立审阅者。阻断项必须在同一 PR 内关闭，不得留到「后续 PR」。
3. 测试子代理独立重跑门禁通过；带 Room 迁移的 PR 有真实旧库迁移测试结果，带 SQL 迁移的 PR 有 up / down / 重复执行结果。
4. 新数据带 `DataProvenance`；新表已注册到擦除链路，并有「擦除后为 0」的断言。
5. 用户可见功能有骨架 UI 和完整状态矩阵的 fixtures（D4），截图基线已在开发机生成。
6. PR 正文列出覆盖的能力编号（§14.2）；列车合入后这些编号要在阶段联调验收中被实际验证。

### 12.7 推理强度分级（D10）

| 推理强度 | 适用的任务（封闭清单，不在清单内的一律 `xhigh`） |
|---|---|
| `high` | 无冲突地把最新 `main` 合入分支；集成列车只做 merge 与触发 CI；重跑已有测试并收集结果；更新任务板、决策记录与汇总；按已确定规则改 CI 配置的数值；文档措辞与链接修复；为已审阅通过的 PR 填写正文；issue 回复草稿（W0-09） |
| `xhigh` | 一切写业务代码、改 Room 或 SQL 迁移、改擦除链路、改凭据与 Key 存储、改契约、写或改测试用例的任务；所有审阅、独立验证、阶段验收和终验；有冲突的合并；故障排查与根因分析；调研；主代理自己 |

如果子代理派发不支持逐个指定推理强度，就全部用 `xhigh`。

### 12.8 阶段联调验收（槽 10）

- 每趟列车合入后，槽 10 在开发机上对新的 `main` 跑 `android-full`、`cloud`、`contracts`，并按本趟列车 PR 声明的能力编号逐项执行 §14.2 中的验收项，报告写入 `task-notes/campus/acceptance/stage-<序号>.md`。
- 报告再由另一个审阅子代理交叉复核；失败项立即建任务卡派修复，修复走正常 PR。
- 某一波次的全部能力在阶段验收中通过之后，才算该波次完成。

### 12.9 任务卡与汇总格式

主代理发给开发子代理的任务卡固定包含：卡号；目标与验收标准（引用本计划小节和 [AGENT_TASK_CARDS.md](AGENT_TASK_CARDS.md)）；**归属文件清单**（只能改这些，越界先回报）；必须遵守的决策编号；分配到的 Room 或迁移编号；必须运行的门禁；推理强度；禁止事项。

子代理交回的汇总固定为以下字段，**总长度不超过 30 行**，主代理只读这份汇总：

| 字段 | 内容 |
|---|---|
| 任务 | 卡号、PR 链接 |
| SHA | 基线 SHA → 结果 SHA（远端已回读） |
| 改动 | 按模块列出，不贴代码 |
| 验证 | 开发机上实际运行的门禁命令和结果（含日志路径） |
| 未完成与风险 | 没覆盖的用例、已知缺口、仍然 `UNVERIFIED` 的项 |
| 待决 | 需要主代理或用户拍板的问题，每条给出推荐方案 |

### 12.10 任务板、决策记录与自我恢复

- `task-notes/campus/TASKBOARD.md` 是进度的唯一来源：顶部 10 行槽位，下面每张卡一行（状态、子代理、分支、PR、结果 SHA、合并 SHA、阻断项）。每次派发、交回、合并、阻塞都立即更新并经 PR 合入。
- `task-notes/campus/DECISIONS.md` 记录每个决策的编号、结论、日期和依据；本计划的 D1–D16 是初始内容。主代理的自主决策按「计划推荐默认 → 与已决事项一致 → 对隐私和数据更保守」的顺序选择，标注「自主决策，待用户复核」，然后继续。
- 上下文被压缩或会话重启后，先读 `TASKBOARD.md`、`DECISIONS.md` 和本计划，按任务板继续；子代理失联就新派一个从远端分支继续；开发机异常就派子代理修复并记录到 `DEVBOX.md`。

### 12.11 热点文件归属

同一时间只派给一个子代理：

| 仓库 | 文件 | 规则 |
|---|---|---|
| Android | `core/database/**/CampusRepository.kt` | W0-05 独占，直到合入 |
| Android | `core/database/**/HautDatabase.kt`、`core/database/schemas/**` | 按主代理分配的 Room 版本号串行 |
| Android | `settings.gradle.kts` | 新模块注册是唯一的「只追加」例外：冲突只可能是相邻行，列车子代理保留两边即可，不改其他内容 |
| Android | 所有 `build.gradle.kts`、`gradle/**` | W0-07 合入前由 W0-07 独占；之后每张卡只能改自己模块的构建文件 |
| Android | `app/**/MainActivity.kt`、`AppGraph` | 新功能入口和依赖装配，按列车顺序串行 |
| Android | `feature/settings/**` | W0-06c 合入前独占 |
| 两仓 | `contracts/**` | 只由契约线（INF-02、CT 系列）修改 |
| 云端 | `apps/api/src/app.mjs`、`router.mjs` | W0-08 合入前独占；之后新增路由只追加到对应 `routes/<领域>.mjs` |
| 云端 | `packages/db/migrations/**` | 按主代理分配的迁移编号串行 |
| 两仓 | `.github/workflows/**` | 只由 INF 系列和 W0-07 修改 |

---

## 13. 任务总表与依赖（阶段 S）

> 「依赖」中的卡必须已经合入 `main`。卡片细节见 [AGENT_TASK_CARDS.md](AGENT_TASK_CARDS.md)。推理强度未注明的一律 `xhigh`。

| 卡 | 内容 | 仓库 | 依赖 | 编号分配 | 能力 |
|---|---|---|---|---|---|
| **基础设施** | | | | | |
| INF-00 | 开发机就绪：Android SDK、`cis-gate` / `cis-gradle` 脚本、裸库、缓存、内存守护、构建槽 | 两仓 `tools/devbox/` | — | — | C25 |
| INF-01 | 治理：计划入库、两仓 `AGENTS.md`、任务板 / 决策记录 / DEVBOX、敏感信息扫描、基线记录（原 W0-01） | 两仓 | — | — | C25 |
| INF-02 | 契约收敛：分析两份 `contracts/` 的差异并统一、同步脚本、两仓 CI 一致性检查（D15） | 两仓 | INF-01 | — | C28 |
| **调研（只读）** | | | | | |
| R01 | FTS4 ICU 中文分词在 API 31–36 的可用性；不可用时的二元切分方案 | — | INF-00 | — | — |
| R02 | 端侧 OCR 选型：不依赖 GMS、许可证、包体、中文准确率 | — | — | — | — |
| R03 | 天气数据源：免 Key 或 BYOK、许可证、配额、署名要求 | — | — | — | — |
| R04 | 求解器方案与基准（50 个候选教学班 < 2 秒） | — | — | — | — |
| R05 | 巨型文件拆分映射：`CampusRepository` 方法 → 8 个 Repository；各 Screen 的拆分清单 | — | — | — | — |
| R06 | Room v10 现状、迁移链和擦除链路清单（`ProfileDataWiper` 注册点） | — | — | — | — |
| **W0 清账与地基** | | | | | |
| W0-02 | #24 实况进度条 | Android | INF-00 | — | C01 |
| W0-03 | #12 学期上下文与提示条 | Android | W0-05 | — | C02 |
| W0-04 | #22 / #8 / #15 节次与作息方案、实践课多日排课 | Android | W0-05 | Room v11 | C03 |
| W0-05 | 拆分 `CampusRepository`（行为不变，调用方不改） | Android | INF-00、R05 | — | C25 |
| W0-06a/b/c | 拆分 Today·Timetable·Grades / Map / Settings 为 Route·State·UI | Android | W0-07、R05 | — | C25 |
| W0-07 | 测试与架构护栏：Roborazzi、依赖方向检查、文件行数门禁 | Android | INF-00 | — | C25 |
| W0-08 | 云端 `app.mjs` 拆成路由表，与 OpenAPI 双向比对 | 云端 | INF-00 | — | C28 |
| W0-09 | 已修复 issue 的回复草稿（D13，`high`） | — | — | — | — |
| W0-10 | 已修复 issue 的回归测试补齐（#5 #6 #7 #10 #13 #14 #16 #17） | Android | W0-07 | — | C26 |
| **W1 个人内核** | | | | | |
| W1-01 | `:core:events` 与 outbox 协议 | Android | INF-00 | — | C05 |
| W1-02a | `:core:timeline` 核心（纯 JVM，含 ICS 导出） | Android | INF-00 | — | C04 |
| W1-02b | Timeline 首批 Source：课程、考试、实践课、个人日程 | Android | W1-02a、W0-04 | — | C04 |
| W1-03 | `:core:vault`：journal、FTS、outbox 落库、`personal_events`、`blob_store` | Android | W1-01、W0-05、R01、R06 | Room v12 | C05 C06 C27 |
| W1-04 | 备份与恢复 `dis-backup/v1` | Android | W1-03 | — | C07 C27 |
| W1-05 | Today、Timetable、小组件改为读取 Timeline（截图不变） | Android | W1-02b、W0-06a | — | C04 C26 |
| W1-07 | `:core:campus-graph`：统一楼宇、教室、设施、入口、流程实体，迁入现有目录数据 | Android | W0-05 | — | C17 C20 C23 |
| **W2 自动化、收件箱与 AI** | | | | | |
| W2-01 | `:core:automation` 引擎核心（规则、SPI、去重、试运行、运行记录模型） | Android | W1-01、W1-02a | — | C08 |
| W2-02 | 内置触发器、条件与动作，Android 调度与 outbox 消费 | Android | W2-01、W1-03 | Room v13 | C08 |
| W2-03 | 4 个规则模板与 `WeatherProvider` SPI | Android | W2-02、R03 | — | C08 |
| W2-04 | `:core:textextract`：端侧 OCR 与 PDF 文本抽取 | Android | R02、INF-00 | — | C09 |
| W2-05 | `:core:inbox`：分享入口、相对日期、规则解析器、指纹去重、改课预览与应用 | Android | W1-02b、W1-03、W2-04 | Room v14 | C09 |
| W2-06 | `:core:ai` BYOK 通道（§6.8） | Android | W1-04 | — | C10 C27 |
| W2-07 | 收件箱的 AI 解析器（可选路径，需预览和单次同意） | Android | W2-05、W2-06 | — | C09 C10 |
| **W3 学业智能** | | | | | |
| W3-01 | 培养方案导入、`DegreeAuditor`、人工认定、依据追踪 | Android | W0-05 | Room v15 | C11 |
| W3-02 | `:core:solver` 与 `TravelTimeMatrix`、无解解释 | Android | R04、W1-02a | — | C12 |
| W3-03 | 选课沙盘：求解器 ↔ 选课草稿 ↔ 培养方案 | Android | W3-01、W3-02 | — | C12 |
| W3-04 | `:core:knowledge`：资料归档、关联课程与教学周、全文搜索、作业截止 | Android | W1-03、W2-04、W1-02b | Room v16 | C13 |
| W3-05 | 复习排程（求解器复用） | Android | W3-02、W3-04 | — | C13 |
| W3-06 | 课程资料问答（BYOK，答案附出处） | Android | W3-04、W2-06 | — | C13 C10 |
| **契约线（串行，同一时间一个子代理）** | | | | | |
| CT-01 | 群组、邀请、成员 | 两仓 contracts | INF-02 | — | C14 C28 |
| CT-02 | 忙闲块与投票 | 两仓 contracts | CT-01 | — | C15 C28 |
| CT-03 | 账本、值日、报修 | 两仓 contracts | CT-01 | — | C16 C28 |
| CT-04 | 地点状态 | 两仓 contracts | INF-02 | — | C17 C28 |
| CT-05 | 论坛与私信保留端点（501） | 两仓 contracts | INF-02 | — | C18 C28 |
| CT-06 | 内容包 | 两仓 contracts | INF-02 | — | C19 C28 |
| CT-07 | E2EE 同步块 | 两仓 contracts | INF-02 | — | C07 C28 |
| **W4 群组云** | | | | | |
| W4-01 | `packages/community` | 云端 | CT-01、W0-08 | SQL 0005 | C14 |
| W4-02 | `packages/availability` | 云端 | CT-02、W4-01 | SQL 0006 | C15 |
| W4-03 | `packages/ledger`（只记账） | 云端 | CT-03、W4-01 | SQL 0007 | C16 |
| W4-04 | `packages/place-status` | 云端 | CT-04、W0-08 | SQL 0008 | C17 |
| W4-05 | Android `:core:community` 客户端、本地缓存、注册擦除链路 | Android | CT-04、W0-05 | Room v17 | C14 C27 |
| W4-06 | 论坛与私信接口预留：云端 501、Android `DisabledForumPort` | 两仓 | CT-05、W0-08、W4-05 | — | C18 |
| W4-07 | 组队找时间（#33） | Android | W4-05、W1-02b | — | C15 |
| W4-08 | 宿舍协作（#37） | Android | W4-05、W4-09 | — | C16 |
| W4-09 | `:core:ledger` 纯 JVM 分摊算法（与云端语义一致） | Android | INF-00 | — | C16 |
| W4-10 | 设施雷达与状态上报（#39） | Android | W4-05、W1-07 | — | C17 |
| W4-11 | `packages/sync` E2EE 块存储 | 云端 | CT-07、W0-08 | SQL 0010 | C07 |
| W4-12 | Android 跨设备同步客户端 | Android | W1-04、W4-11 | — | C07 |
| **W5 校园知识网络** | | | | | |
| W5-01 | `dis-datapack/v1` Schema、加载器、签名 | 两仓 | W1-07、INF-02 | Room v18 | C19 |
| W5-02 | `packages/content-catalog` | 云端 | CT-06、W5-01 | SQL 0009 | C19 |
| W5-03 | `:core:guide` FlowRunner | Android | W5-01、W1-02b | — | C20 C21 |
| W5-04 | 办事流程导航（#38） | Android | W5-03 | — | C20 |
| W5-05 | 考证、竞赛与奖助中心（#41） | Android | W5-03、W3-01 | — | C21 |
| W5-06 | 首批数据包草稿：10 个高频事项、机会目录、设施目录（只用公开来源，一律 `UNVERIFIED`，D1） | 数据 | W5-01 | — | C19 |
| W5-07 | 贡献流水线：Schema 校验、链接检查、差异渲染 | 两仓 | W5-01 | — | C19 |
| **W6 平台与空间** | | | | | |
| W6-01 | 插件 v2.1 与 `dis-plugin validate / simulate / pack / sign` | 两仓 | INF-02、W2-02、W5-01 | — | C22 |
| W6-02 | `:core:ui-runtime` 声明式渲染器 | Android | W0-06c、W6-01 | — | C22 |
| W6-03 | 室内路网数据包与室外到室内衔接（试点 1 栋楼，#35） | Android | W1-07、W5-01 | — | C23 |
| W6-04 | 一日回放与两套课表通勤对比（#44 轻量） | Android | W1-02b、W3-02 | — | C24 |
| W6-05 | 非官方示例插件零修改宿主的安装验收（契约 I.4） | 两仓 | W6-01、W6-02 | — | C22 |

**编号分配规则**：上表是初始分配。某卡最终不需要迁移时，后续号顺延，不留空号；带迁移的 PR 严格按号合入。

**移出阶段 S 的项**：第二所学校达到 `SCHOOL_VERIFIED`（需要真实授权账号）、真机验收（`DEVICE_TESTED`）、正式发版，都列为用户待办（§16）。

---

## 14. 阶段 S 终验

### 14.1 全量回归（R0）

在最终成对 SHA（Android `main` + 云端 `main`）上一次性执行：

| 编号 | 内容 |
|---|---|
| R0-1 | Android `clean` 后跑 `android-full`（全部单测、双 Lint、Debug 与 Release APK、Roborazzi 截图比对、依赖方向、文件行数门禁） |
| R0-2 | Room 迁移链：从 v4 起逐版本升级到最新版本，用保真合成的旧库比对全表摘要；含中断回滚和重复打开 |
| R0-3 | 云端 `cloud`：全部 `node --test`、`contract:check`、路由表与 OpenAPI 双向 100% 比对、0005 之后每个 SQL 迁移的 up / down / 重复执行 |
| R0-4 | 两仓 `contracts/` 完全一致；保留端点全部返回 501 |
| R0-5 | 两仓最终 SHA 的托管 CI 全绿 |
| R0-6 | `secrets` 扫描两仓全部历史提交（不只是最新一次） |
| R0-7 | 既有功能零回归：工大课表 / 成绩 / 考试、四款小组件快照、签名学校包、九协议合同测试、三阶段预约调度测试 |

### 14.2 能力矩阵

| 编号 | 能力 | 验收要点（摘自对应 issue 的「首期验收」与本计划） |
|---|---|---|
| C01 | #24 实况进度 | 课中进度随时间前进；无法调度节拍时不显示进度条；切换身份时取消节拍 |
| C02 | #12 学期上下文 | 自然学期自动推进；手动锁定时显示提示条；本学期无考试时不显示旧学期考试 |
| C03 | #22 节次与实践课 | 自定义节次时间与作息方案；实践课多日多节排课；同步教务不覆盖用户修改；提醒按新时间重排 |
| C04 | 统一时间流 | 课程、考试、实践课、停课、调休全部正确投影；同步后 ID 稳定；ICS 重复导入不重复 |
| C05 | 变更历史 | 同步、用户、自动化、恢复四类写入都有记录；可查看前后差异并撤销 |
| C06 | 全局搜索 | 中文命中；按类型、学期、时间筛选；结果可跳转 |
| C07 | 备份、恢复与同步 | 备份 → 清空 → 恢复零差异；损坏、口令错误、版本过高、身份不匹配都明确报错且不部分写入；同步只传密文 |
| C08 | 自动化引擎（#36） | 课表、时间、水电阈值、本地提醒打通；4 个模板；可暂停、可删除；同一事件不重复执行；陈旧数据不触发确定性结论；有试运行和运行记录 |
| C09 | 通知收件箱（#31） | 截图或文本导入、字段抽取、原文回溯、确认后创建日程；识别为更新时修改已有事项，不重复创建；缺少日期依据时要求补充 |
| C10 | BYOK AI | Key 加密且按身份隔离；不进备份、日志、诊断；发送前预览；自动化不能调用；默认关闭 |
| C11 | 毕业进度（#29） | 导入培养方案和成绩后完成学分分类、缺口展示、人工修正和依据回溯；标明「个人规划参考」 |
| C12 | 选课沙盘（#30） | 冲突检测、多方案生成、无解原因说明；不自动提交 |
| C13 | 课程知识空间（#34） | 资料归档、搜索、作业截止提醒、复习计划；问答附出处并区分「资料原文」与「模型补充」 |
| C14 | 群组 | 创建、邀请码或二维码加入、过期与次数上限、退出、角色 |
| C15 | 共同空闲（#33） | 只共享忙闲块；共同空闲计算、投票确认、撤回共享；成员调课时只提示，不静默改期 |
| C16 | 宿舍协作（#37） | 小组记账、分摊、结算确认、历史记录；修改可追溯并重新确认；金额精确到分；不涉及真实支付 |
| C17 | 设施雷达（#39） | 设施目录、地图筛选、故障上报、计时提醒；区分实时、上报与预测；无近期信息时显示未知；按类型过期 |
| C18 | 社区接口预留（#45 #9） | 所有保留端点返回 501 与 `FEATURE_NOT_ENABLED`；Android 无入口、开关默认关闭；没有建表 |
| C19 | 数据包与内容目录 | Schema 校验、签名、版本、下架；过期显示「待核验」；贡献流水线可走通 |
| C20 | 办事导航（#38） | 来源展示、个人清单、地图跳转、提醒；至少 1 个带条件分支的流程；来源失效时明确提示 |
| C21 | 机会中心（#41） | 项目建档、条款来源、材料清单、进度追踪、提醒；信息缺失不视为符合；可手动修正资格判断 |
| C22 | 插件 v2.1 | 新 types、slots、permissions 可用；开发者工具可用；非官方示例插件零修改宿主安装、停用、升级、回滚、卸载；单插件故障隔离 |
| C23 | 室内路网试点（#35） | 1 栋楼的教室级路网、跨楼层路线、手动或扫码定位起点 |
| C24 | 一日回放（#44 轻量） | 播放全天路线；两套课表的步行距离、跨楼次数、通勤时间对比 |
| C25 | 架构与工程 | 巨型文件拆分完成（行数门禁通过）；依赖方向检查通过；开发机脚本可复现 |
| C26 | 零回归 | R0-7 全部通过；W0-10 的回归测试全部通过 |
| C27 | 擦除与隐私 | 每张新表擦除后为 0；凭据、Cookie、AI Key 不进备份和日志；按身份隔离 |
| C28 | 契约一致 | 运行中的路由与 OpenAPI 100% 一致；两仓 `contracts/` 一致；每个新端点都有错误码、幂等、授权、示例 |

### 14.3 执行规则

1. 由**没参与开发的终验子代理**执行 R0 全部和 C01–C28 全部，每一项写明证据：测试名、命令、日志路径或截图路径。
2. **零容错**：结论只有「全部通过」或「不通过」。不允许豁免、标记已知问题、把失败改成跳过、修改断言迁就结果；`skipped`、`flaky`、重试后才通过、超时，一律按失败处理。
3. 任何一项失败：建任务卡、修复、经交叉审阅合入，再用新的成对 SHA **从 R0 整轮重跑**。
4. **第二个独立终验子代理**逐项复核证据，并重跑至少 20% 的项、全部隐私类项（C07、C10、C27）和全部迁移类项（R0-2、R0-3）。两次结论不一致视为不通过。
5. 只能在设备上证明的项（真机通知外观、TalkBack、200% 字号的实际渲染、厂商 ROM 后台限制），在报告里单独列为 `DEVICE_TESTED 待用户`，**不计入通过，也不冒充通过**。
6. 终验期间冻结两仓合并。
7. 报告写到 `task-notes/campus/acceptance/<android_sha>-<cloud_sha>/ACCEPTANCE.md`。

---

## 15. 阶段 N：交给 neon（D4）

**开始条件**：阶段 S 终验全部通过，用户已签收。

**neon 的输入**：每个 Screen 的 `UiState` / `UiAction` / `UiEffect`、骨架 Screen、覆盖完整状态矩阵的 fixtures、开发机上生成的截图基线、[AGENT_TASK_CARDS.md](AGENT_TASK_CARDS.md) 中全部 `[N]` 卡。

**neon 的范围**：只改 `feature/*/ui/**`、`feature/*/preview/**`（只能补充）和 `core/designsystem/**`，以及文案资源。

**UI 契约变更请求**：neon 需要改 `UiState` 字段、ViewModel 或 Repository 时，写一份变更请求到 `task-notes/campus/ui-change-requests/<编号>.md`。主代理派一个专门的「状态层修订」子代理（`xhigh`，2 个独立审阅）处理，neon 不直接改。

**阶段 N 的门禁**：
- 截图基线只在开发机生成和比对；每个基线变更 PR 附前后对比图。
- 每个 Screen 都有浅色、深色、200% 字号三套截图，以及 Compose 语义测试（内容描述、可点击区域、焦点顺序）。
- 不改变任何业务行为：阶段 S 的单测和能力矩阵在阶段 N 结束时整轮重跑，必须全部通过。
- 协作规范（槽位、列车、交叉审阅、推理强度、开发机）与 §12 相同。

**阶段 N 终验**：R0 全部 + C01–C28 全部重跑，加上界面专项：每个 Screen 的状态矩阵截图齐全、无障碍语义测试全部通过、设计 token 无硬编码颜色和尺寸（静态检查）。

---

## 16. 用户待办

| 事项 | 说明 |
|---|---|
| 把 `xpeach-dev` 加入 `kvm` 组 | `usermod -aG kvm xpeach-dev`（需要 root，一次性）。不做也能完成阶段 S，只是没有模拟器 |
| 更换开发机 root 密码并关闭密码登录 | 企业项目的提示词里有明文 root 密码 |
| 确认 issue 回复草稿 | `task-notes/campus/issue-replies.md`（D13） |
| 复核天气数据源和端侧 OCR 的许可证结论 | R02、R03 的调研结论 |
| 核对数据包草稿 | W5-06 产出的数据一律 `UNVERIFIED`，需要人工对照公开来源核对 |
| 真机验收 | Android 12–16 真机的通知、小组件、TalkBack、200% 字号、旧版覆盖安装 |
| 第二所学校 | 选一所 `ZF_MODERN` 学校，并提供真实授权账号 |
| 正式发版 | 打 `v*` 标签、正式签名、部署云端（D12） |
