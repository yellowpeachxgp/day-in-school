# 阶段 S 任务板（TASKBOARD）

> 本文件是阶段 S 进度的唯一来源。每次派发、交回、阻塞、PR 创建、合并都必须立即更新，并经 PR 合入。
> 当前快照：2026-11-27 02:05（Asia/Shanghai）按 GitHub PR 元数据更新；AUTO-015 明确 scanner 未合入前 exit3 仅适用于 INF-00，INF-01 起 secrets 必须真实通过。

## 10 槽位状态

| 槽位 | 子代理 | 当前任务 | 分支或 PR | 开始时间 | 状态 | 阻塞项 |
|---|---|---|---|---|---|---|
| 1 | s1-w007 | W0-07a dep-check 与 file-line-gate | feat/cis-w0-07-guardrails / PR#61 | 2026-11-27 | PR 已创建 | 待列车/审阅 |
| 2 | s2b-inf01fin | INF-01 治理与基线 | feat/cis-inf-01-governance-clean2 / Android PR#57、cloud PR#7 | 2026-11-26 | 已合并 | Android 合并 `04d4424`；cloud 合并 `5480f0d` |
| 3 | s2-w008 | W0-08 云端路由拆分 | feat/cis-w0-08-router-split / cloud PR#8 | 2026-11-27 | PR 已创建 | 待列车/审阅 |
| 4 | s6-w002 | W0-02 实况进度条 | feat/cis-w0-02-live-progress / PR#65 | 2026-11-27 | PR 已创建 | 待列车/审阅 |
| 5 | s8d-w008prep | R01 FTS 中文分词调研 | docs/cis-r01-fts-research / PR#60 | 2026-11-27 | 已合并 | 合并 `b3f15cf` |
| 6 | s10b-accfinish | W1-01 core events（W1-01a） | feat/cis-w1-01a-events-core / PR#64 | 2026-11-27 | PR 已创建 | 待列车/审阅；完整 W1-01 未收口 |
| 7 | s2c-w102a | W1-00 shared kernel refs | feat/cis-w1-00-kernel-refs / PR#62 | 2026-11-27 | PR 已创建 | 待列车/审阅 |
| 8 | s9b-train | 审阅与集成列车 | PR#61/#62/#64/#65、cloud PR#8 | 2026-11-27 | 进行中 | 待列车/审阅 |
| 9 | s3-w409 | W4-09 core ledger | feat/cis-w4-09-ledger / PR#63 | 2026-11-27 | 已合并 | 合并 `8aadb4d` |
| 10 | s10b-accfinish | stage-2 INF-01 联调验收 | 待列车/审阅 | 2026-11-27 | 开工中 | INF-01 已合并；验收报告待完成 |

## 阶段 S 卡片总表

| 卡 | 内容 | 仓库 | 依赖 | 编号分配 | 能力 | 状态 | 子代理 | 分支 | PR | 结果 SHA | 合并 SHA | 阻断项 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| INF-00 | 开发机就绪：Android SDK、`cis-gate` / `cis-gradle` 脚本、裸库、缓存、内存守护、构建槽 | 两仓 `tools/devbox/` | — | — | C25 | 已合并 | s1b-inf00fix | feat/cis-inf-00-devbox-clean | Android PR#53；cloud PR#6 | Android 4dccda9；cloud 8493b2f | Android f18915f；cloud d4fb93c | stage-1 联调验收 PR#59 已合并（`d68ddca`） |
| INF-01 | 治理：计划入库、两仓 `AGENTS.md`、任务板 / 决策记录 / DEVBOX、敏感信息扫描、基线记录（原 W0-01） | 两仓 | — | — | C25 | 已合并 | s2b-inf01fin | feat/cis-inf-01-governance-clean2 | Android PR#57；cloud PR#7 | Android d8e455a；cloud d38c59e | Android 04d4424；cloud 5480f0d | — |
| INF-02 | 契约收敛：分析两份 `contracts/` 的差异并统一、同步脚本、两仓 CI 一致性检查（D15） | 两仓 | INF-01 | — | C28 | 未开始 | — | — | — | — | — | — |
| R01 | FTS4 ICU 中文分词在 API 31–36 的可用性；不可用时的二元切分方案 | — | INF-00 | — | — | 已合并 | s8d-w008prep | docs/cis-r01-fts-research | PR#60 | — | b3f15cf | — |
| R02 | 端侧 OCR 选型：不依赖 GMS、许可证、包体、中文准确率 | — | — | — | — | 已合并 | s6-r02 | docs/cis-r02-ocr-research | PR#54 | — | 02e5c48 | — |
| R03 | 天气数据源：免 Key 或 BYOK、许可证、配额、署名要求 | — | — | — | — | 已合并 | s7-r03 | docs/cis-r03-weather-research | PR#52 | — | 8c5d554 | — |
| R04 | 求解器方案与基准（50 个候选教学班 < 2 秒） | — | — | — | — | 已合并 | s5-r04 | docs/cis-r04-solver-research | PR#55 | — | 5a810e7 | — |
| R05 | 巨型文件拆分映射：`CampusRepository` 方法 → 8 个 Repository；各 Screen 的拆分清单 | — | — | — | — | 已合并 | s3-r05 | docs/cis-r05-repo-map | PR#49 | — | 9e90a49 | — |
| R06 | Room v10 现状、迁移链和擦除链路清单（`ProfileDataWiper` 注册点） | — | — | — | — | 已合并 | s4-r06 | docs/cis-r06-room-wipe | PR#50 | — | a44017b | — |
| W0-02 | #24 实况进度条 | Android | INF-00 | — | C01 | PR 已创建 | s6-w002 | feat/cis-w0-02-live-progress | PR#65 | — | — | 待列车/审阅 |
| W0-03 | #12 学期上下文与提示条 | Android | W0-05 | — | C02 | 未开始 | — | — | — | — | — | — |
| W0-04 | #22 / #8 / #15 节次与作息方案、实践课多日排课 | Android | W0-05 | Room v11 | C03 | 未开始 | — | — | — | — | — | — |
| W0-05 | 拆分 `CampusRepository`（行为不变，调用方不改） | Android | INF-00、R05 | — | C25 | 未开始 | — | — | — | — | — | — |
| W0-06a/b/c | 拆分 Today·Timetable·Grades / Map / Settings 为 Route·State·UI | Android | W0-07、R05 | — | C25 | 未开始 | — | — | — | — | — | — |
| W0-07 | 测试与架构护栏：Roborazzi、依赖方向检查、文件行数门禁 | Android | INF-00 | — | C25 | W0-07a PR 已创建 | s1-w007 | feat/cis-w0-07-guardrails | PR#61 | — | — | 待列车/审阅；W0-07b/c 未收口 |
| W0-08 | 云端 `app.mjs` 拆成路由表，与 OpenAPI 双向比对 | 云端 | INF-00 | — | C28 | PR 已创建 | s2-w008 | feat/cis-w0-08-router-split | cloud PR#8 | — | — | 待列车/审阅 |
| W0-09 | 已修复 issue 的回复草稿（D13，`high`） | — | — | — | — | 已合并 | s8-w009 | docs/cis-w0-09-issue-replies | PR#47 | — | 062d17d | D13：仅草稿，待用户确认 |
| W0-10 | 已修复 issue 的回归测试补齐（#5 #6 #7 #10 #13 #14 #16 #17） | Android | W0-07 | — | C26 | 未开始 | — | — | — | — | — | — |
| W1-00 | 统一实体引用类型（shared kernel refs 微卡） | Android | INF-00 | — | C04 C05 | PR 已创建 | s2c-w102a | feat/cis-w1-00-kernel-refs | PR#62 | — | — | 待列车/审阅 |
| W1-01 | `:core:events` 与 outbox 协议 | Android | INF-00 | — | C05 | W1-01a PR 已创建 | s10b-accfinish | feat/cis-w1-01a-events-core | PR#64 | — | — | 待列车/审阅；完整 W1-01 未收口 |
| W1-02a | `:core:timeline` 核心（纯 JVM，含 ICS 导出） | Android | INF-00 | — | C04 | 未开始 | — | — | — | — | — | — |
| W1-02b | Timeline 首批 Source：课程、考试、实践课、个人日程 | Android | W1-02a、W0-04 | — | C04 | 未开始 | — | — | — | — | — | — |
| W1-03 | `:core:vault`：journal、FTS、outbox 落库、`personal_events`、`blob_store` | Android | W1-01、W0-05、R01、R06 | Room v12 | C05 C06 C27 | 未开始 | — | — | — | — | — | — |
| W1-04 | 备份与恢复 `dis-backup/v1` | Android | W1-03 | — | C07 C27 | 未开始 | — | — | — | — | — | — |
| W1-05 | Today、Timetable、小组件改为读取 Timeline（截图不变） | Android | W1-02b、W0-06a | — | C04 C26 | 未开始 | — | — | — | — | — | — |
| W1-07 | `:core:campus-graph`：统一楼宇、教室、设施、入口、流程实体，迁入现有目录数据 | Android | W0-05 | — | C17 C20 C23 | 未开始 | — | — | — | — | — | — |
| W2-01 | `:core:automation` 引擎核心（规则、SPI、去重、试运行、运行记录模型） | Android | W1-01、W1-02a | — | C08 | 未开始 | — | — | — | — | — | — |
| W2-02 | 内置触发器、条件与动作，Android 调度与 outbox 消费 | Android | W2-01、W1-03 | Room v13 | C08 | 未开始 | — | — | — | — | — | — |
| W2-03 | 4 个规则模板与 `WeatherProvider` SPI | Android | W2-02、R03 | — | C08 | 未开始 | — | — | — | — | — | — |
| W2-04 | `:core:textextract`：端侧 OCR 与 PDF 文本抽取 | Android | R02、INF-00 | — | C09 | 未开始 | — | — | — | — | — | — |
| W2-05 | `:core:inbox`：分享入口、相对日期、规则解析器、指纹去重、改课预览与应用 | Android | W1-02b、W1-03、W2-04 | Room v14 | C09 | 未开始 | — | — | — | — | — | — |
| W2-06 | `:core:ai` BYOK 通道（§6.8） | Android | W1-04 | — | C10 C27 | 未开始 | — | — | — | — | — | — |
| W2-07 | 收件箱的 AI 解析器（可选路径，需预览和单次同意） | Android | W2-05、W2-06 | — | C09 C10 | 未开始 | — | — | — | — | — | — |
| W3-01 | 培养方案导入、`DegreeAuditor`、人工认定、依据追踪 | Android | W0-05 | Room v15 | C11 | 未开始 | — | — | — | — | — | — |
| W3-02 | `:core:solver` 与 `TravelTimeMatrix`、无解解释 | Android | R04、W1-02a | — | C12 | 未开始 | — | — | — | — | — | — |
| W3-03 | 选课沙盘：求解器 ↔ 选课草稿 ↔ 培养方案 | Android | W3-01、W3-02 | — | C12 | 未开始 | — | — | — | — | — | — |
| W3-04 | `:core:knowledge`：资料归档、关联课程与教学周、全文搜索、作业截止 | Android | W1-03、W2-04、W1-02b | Room v16 | C13 | 未开始 | — | — | — | — | — | — |
| W3-05 | 复习排程（求解器复用） | Android | W3-02、W3-04 | — | C13 | 未开始 | — | — | — | — | — | — |
| W3-06 | 课程资料问答（BYOK，答案附出处） | Android | W3-04、W2-06 | — | C13 C10 | 未开始 | — | — | — | — | — | — |
| CT-01 | 群组、邀请、成员 | 两仓 contracts | INF-02 | — | C14 C28 | 未开始 | — | — | — | — | — | — |
| CT-02 | 忙闲块与投票 | 两仓 contracts | CT-01 | — | C15 C28 | 未开始 | — | — | — | — | — | — |
| CT-03 | 账本、值日、报修 | 两仓 contracts | CT-01 | — | C16 C28 | 未开始 | — | — | — | — | — | — |
| CT-04 | 地点状态 | 两仓 contracts | INF-02 | — | C17 C28 | 未开始 | — | — | — | — | — | — |
| CT-05 | 论坛与私信保留端点（501） | 两仓 contracts | INF-02 | — | C18 C28 | 未开始 | — | — | — | — | — | — |
| CT-06 | 内容包 | 两仓 contracts | INF-02 | — | C19 C28 | 未开始 | — | — | — | — | — | — |
| CT-07 | E2EE 同步块 | 两仓 contracts | INF-02 | — | C07 C28 | 未开始 | — | — | — | — | — | — |
| W4-01 | `packages/community` | 云端 | CT-01、W0-08 | SQL 0005 | C14 | 未开始 | — | — | — | — | — | — |
| W4-02 | `packages/availability` | 云端 | CT-02、W4-01 | SQL 0006 | C15 | 未开始 | — | — | — | — | — | — |
| W4-03 | `packages/ledger`（只记账） | 云端 | CT-03、W4-01 | SQL 0007 | C16 | 未开始 | — | — | — | — | — | — |
| W4-04 | `packages/place-status` | 云端 | CT-04、W0-08 | SQL 0008 | C17 | 未开始 | — | — | — | — | — | — |
| W4-05 | Android `:core:community` 客户端、本地缓存、注册擦除链路 | Android | CT-04、W0-05 | Room v17 | C14 C27 | 未开始 | — | — | — | — | — | — |
| W4-06 | 论坛与私信接口预留：云端 501、Android `DisabledForumPort` | 两仓 | CT-05、W0-08、W4-05 | — | C18 | 未开始 | — | — | — | — | — | — |
| W4-07 | 组队找时间（#33） | Android | W4-05、W1-02b | — | C15 | 未开始 | — | — | — | — | — | — |
| W4-08 | 宿舍协作（#37） | Android | W4-05、W4-09 | — | C16 | 未开始 | — | — | — | — | — | — |
| W4-09 | `:core:ledger` 纯 JVM 分摊算法（与云端语义一致） | Android | INF-00 | — | C16 | 已合并 | s3-w409 | feat/cis-w4-09-ledger | PR#63 | — | 8aadb4d | — |
| W4-10 | 设施雷达与状态上报（#39） | Android | W4-05、W1-07 | — | C17 | 未开始 | — | — | — | — | — | — |
| W4-11 | `packages/sync` E2EE 块存储 | 云端 | CT-07、W0-08 | SQL 0010 | C07 | 未开始 | — | — | — | — | — | — |
| W4-12 | Android 跨设备同步客户端 | Android | W1-04、W4-11 | — | C07 | 未开始 | — | — | — | — | — | — |
| W5-01 | `dis-datapack/v1` Schema、加载器、签名 | 两仓 | W1-07、INF-02 | Room v18 | C19 | 未开始 | — | — | — | — | — | — |
| W5-02 | `packages/content-catalog` | 云端 | CT-06、W5-01 | SQL 0009 | C19 | 未开始 | — | — | — | — | — | — |
| W5-03 | `:core:guide` FlowRunner | Android | W5-01、W1-02b | — | C20 C21 | 未开始 | — | — | — | — | — | — |
| W5-04 | 办事流程导航（#38） | Android | W5-03 | — | C20 | 未开始 | — | — | — | — | — | — |
| W5-05 | 考证、竞赛与奖助中心（#41） | Android | W5-03、W3-01 | — | C21 | 未开始 | — | — | — | — | — | — |
| W5-06 | 首批数据包草稿：10 个高频事项、机会目录、设施目录（只用公开来源，一律 `UNVERIFIED`，D1） | 数据 | W5-01 | — | C19 | 未开始 | — | — | — | — | — | — |
| W5-07 | 贡献流水线：Schema 校验、链接检查、差异渲染 | 两仓 | W5-01 | — | C19 | 未开始 | — | — | — | — | — | — |
| W6-01 | 插件 v2.1 与 `dis-plugin validate / simulate / pack / sign` | 两仓 | INF-02、W2-02、W5-01 | — | C22 | 未开始 | — | — | — | — | — | — |
| W6-02 | `:core:ui-runtime` 声明式渲染器 | Android | W0-06c、W6-01 | — | C22 | 未开始 | — | — | — | — | — | — |
| W6-03 | 室内路网数据包与室外到室内衔接（试点 1 栋楼，#35） | Android | W1-07、W5-01 | — | C23 | 未开始 | — | — | — | — | — | — |
| W6-04 | 一日回放与两套课表通勤对比（#44 轻量） | Android | W1-02b、W3-02 | — | C24 | 未开始 | — | — | — | — | — | — |
| W6-05 | 非官方示例插件零修改宿主的安装验收（契约 I.4） | 两仓 | W6-01、W6-02 | — | C22 | 未开始 | — | — | — | — | — | — |

## 更新规则

1. 「依赖」中的卡合入 `main` 前，后续卡不得开工；例外必须写入 `DECISIONS.md`。
2. PR 创建后填入 PR 链接；开发机门禁通过后填结果 SHA；主代理合并后填合并 SHA。
3. 损坏、阻塞或返工必须写明具体条件；等待构建槽、CI 或依赖不算空闲。
4. 本表只覆盖阶段 S；阶段 N 在阶段 S 终验签收后再建表。
