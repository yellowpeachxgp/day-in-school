# 阶段 S 任务板（TASKBOARD）

> 本文件是阶段 S 进度的唯一来源。每次派发、交回、阻塞、PR 创建、合并都必须立即更新，并经 PR 合入。
> 当前快照：2026-11-28 11:10（Asia/Shanghai）由主代理批量刷新；22:40 之后已合并 CT-04/05/06、W4-02/03/04、W6-01a/01b/02、W0-04、W1-02b、W1-07、W0-05-c6/c7/c8/d首片、W0-10 收尾、lint 修复、sanitize 竞态修复等。

## 10 槽位状态

| 槽位 | 子代理 | 当前任务 | 分支或 PR | 开始时间 | 状态 | 阻塞项 |
|---|---|---|---|---|---|---|
| 1 | m-w501 | W5-01 dis-datapack/v1 Schema/加载器/签名 | feat/cis-w5-01-datapack | 2026-11-28 | 进行中 | — |
| 2 | m-w103fix2 | W1-03 vault 迁移审阅三项修复 | feat/cis-w1-03-vault / PR#133 | 2026-11-28 | 进行中 | — |
| 3 | m-ct07 | CT-07 E2EE 同步块契约 | feat/cis-ct-07-e2ee-contract | 2026-11-28 | 进行中 | — |
| 4 | m-w006ttfix5 | PR#104 核对候选持久化修复 | feat/cis-w0-06d / PR#104 | 2026-11-28 | 进行中 | — |
| 5 | m-w405fix2 | W4-05 缓存竞态/毒化修复 | feat/cis-w4-05 / PR#137 | 2026-11-28 | 进行中 | — |
| 6 | m-w103rev4 | W1-03 迁移维度复审 | PR#133 | 2026-11-28 | 进行中 | — |
| 7 | — | 机动预留 | — | — | 空闲 | — |
| 8 | — | 机动预留 | — | — | 空闲 | — |
| 9 | — | 集成列车 | — | — | 空闲 | — |
| 10 | — | 阶段联调验收 | — | — | 空闲 | — |

## 阶段 S 卡片总表

| 卡 | 内容 | 仓库 | 依赖 | 编号分配 | 能力 | 状态 | 子代理 | 分支 | PR | 结果 SHA | 合并 SHA | 阻断项 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| INF-00 | 开发机就绪：Android SDK、`cis-gate` / `cis-gradle` 脚本、裸库、缓存、内存守护、构建槽 | 两仓 `tools/devbox/` | — | — | C25 | 已合并 | s1b-inf00fix | feat/cis-inf-00-devbox-clean | Android PR#53；cloud PR#6 | Android 4dccda9；cloud 8493b2f | Android f18915f；cloud d4fb93c | stage-1 联调验收 PR#59 已合并（`d68ddca`） |
| INF-01 | 治理：计划入库、两仓 `AGENTS.md`、任务板 / 决策记录 / DEVBOX、敏感信息扫描、基线记录（原 W0-01） | 两仓 | — | — | C25 | 已合并 | s2b-inf01fin | feat/cis-inf-01-governance-clean2 | Android PR#57；cloud PR#7 | Android 50201c8c；cloud d38c59e | Android 04d4424；cloud 5480f0d | — |
| INF-02 | 契约收敛：统一两份 `contracts/`、同步脚本、两仓 CI 一致性检查（D15）；扩充插件契约事实源基础设施（I43 §4 阶段0） | 两仓 | INF-01 | — | C28 C22 | 已合并 | s-inf02 | feat/cis-inf-02-contract-convergence | Android PR#68；cloud PR#9 | — | Android 7503ce0；cloud 0ba12fc | — |
| R01 | FTS4 ICU 中文分词在 API 31–36 的可用性；不可用时的二元切分方案 | — | INF-00 | — | — | 已合并 | s8d-w008prep | docs/cis-r01-fts-research | PR#60 | — | b3f15cf | — |
| R02 | 端侧 OCR 选型：不依赖 GMS、许可证、包体、中文准确率 | — | — | — | — | 已合并 | s6-r02 | docs/cis-r02-ocr-research | PR#54 | — | 02e5c48 | — |
| R03 | 天气数据源：免 Key 或 BYOK、许可证、配额、署名要求 | — | — | — | — | 已合并 | s7-r03 | docs/cis-r03-weather-research | PR#52 | — | 8c5d554 | — |
| R04 | 求解器方案与基准（50 个候选教学班 < 2 秒） | — | — | — | — | 已合并 | s5-r04 | docs/cis-r04-solver-research | PR#55 | — | 5a810e7 | — |
| R05 | 巨型文件拆分映射：`CampusRepository` 方法 → 8 个 Repository；各 Screen 的拆分清单 | — | — | — | — | 已合并 | s3-r05 | docs/cis-r05-repo-map | PR#49 | — | 9e90a49 | — |
| R06 | Room v10 现状、迁移链和擦除链路清单（`ProfileDataWiper` 注册点） | — | — | — | — | 已合并 | s4-r06 | docs/cis-r06-room-wipe | PR#50 | — | a44017b | — |
| W0-02 | #24 实况进度条 | Android | INF-00 | — | C01 | 已合并 | s6b-w002finish | feat/cis-w0-02-live-progress | PR#65 | — | 0e83b78 | — |
| W0-03 | #12 学期上下文与提示条 | Android | W0-05 | — | C02 | 未开始 | — | — | — | — | — | 依赖 W0-05 收口 |
| W0-04 | #22 / #8 / #15 节次与作息方案、实践课多日排课 | Android | W0-05 | Room v11 | C03 | 已合并 | m-w004 等 | feat/cis-w0-04-schedule-schemes | PR#130 | — | eed7eeac | — |
| W0-05 | 拆分 `CampusRepository`；AUTO-019 分批 | Android | INF-00、R05 | — | C25 | 进行中：a/b/c1-c8 与 d 首切片已合并（PR#69/#82/#93/#100/#106/#112/#117/#119/#121/#126/#127）；现 1768 行 facade，<800 压缩批未开始 | — | — | — | — | — | AUTO-034：结构拆分完成后续卡已解锁 |
| W0-06a/b/c/d | 拆分 Grades/Map/Settings/Today·Timetable 为 Route·State·UI | Android | W0-07、R05 | — | C25 | a/b/c 已合并（PR#89/#94/#92）；d PR#104 终审修复中（核对候选持久化） | m-w006ttfix5 | feat/cis-w0-06d | PR#104 | — | — | — |
| W0-07 | 测试与架构护栏：Roborazzi、依赖方向检查、文件行数门禁 | Android | INF-00 | — | C25 | 已合并：a b754cc4（PR#61）；b 338e5c3（PR#81）；c ba548cb+b1b81b2（PR#76/#79，cloud b3126b8） | s1-w007 等 | feat/cis-w0-07-guardrails 等 | PR#61/#76/#79/#81 | — | 见左 | — |
| W0-08 | 云端 `app.mjs` 拆成路由表，与 OpenAPI 双向比对 | 云端 | INF-00 | — | C28 | 已合并 | s2-w008 | feat/cis-w0-08-router-split | cloud PR#8 | — | cloud dc990d6 | — |
| W0-09 | 已修复 issue 的回复草稿（D13，`high`） | — | — | — | — | 已合并 | s8-w009 | docs/cis-w0-09-issue-replies | PR#47 | — | 062d17d | D13：仅草稿，待用户确认 |
| W0-10 | 已修复 issue 的回归测试补齐（#5 #6 #7 #10 #13 #14 #16 #17 #19） | Android | W0-07 | — | C26 | 已合并：切片1-4（PR#88/#97/#101/#107） | s-w010 等 | test/cis-w0-10-* | — | — | 见各 PR | — |
| W0-11 | 预约窗口语义（#31 抢票接缝）：a 三阶段纯语义模型已合并 d3432d8（PR#70）；剩余接线未开始 | Android | — | — | C26 | 进行中 | s-w011a | feat/cis-w0-11a-booking-windows | PR#70 | — | d3432d8（a） | — |
| W1-00 | 统一实体引用类型（shared kernel refs 微卡） | Android | INF-00 | — | C04 C05 | 已合并 | s2c-w102a | feat/cis-w1-00-kernel-refs | PR#62 | — | e46f02e | — |
| W1-01 | `:core:events` 与 outbox 协议 | Android | INF-00 | — | C05 | 已合并：a e27732c（PR#64）；b（PR#85） | m-w101bfix 等 | feat/cis-w1-01b-domain-events | PR#64/#85 | — | 942fd3bc（b） | — |
| W1-02a | `:core:timeline` 核心（纯 JVM，含 ICS 导出） | Android | INF-00 | — | C04 | 已合并 | s-w102a | feat/cis-w1-02a-timeline | PR#71 | — | 18925c0 | — |
| W1-02b | Timeline 首批 Source：课程、考试、实践课、个人日程 | Android | W1-02a、W0-04 | — | C04 | 已合并 | m-w102b | feat/cis-w1-02b-timeline-sources | PR#136 | — | e8cacae6 | — |
| W1-03 | `:core:vault`：journal、FTS、outbox 落库、`personal_events`、`blob_store` | Android | W1-01、W0-05、R01、R06 | Room v12 | C05 C06 C27 | 修复中：架构/迁移审阅阻断修复（PR#133 head 45aa8db8）；骨架 UI 归 W1-03b（AUTO-035） | m-w103fix2 | feat/cis-w1-03-vault | PR#133 | — | — | — |
| W1-04 | 备份与恢复 `dis-backup/v1` | Android | W1-03 | — | C07 C27 | 未开始 | — | — | — | — | — | — |
| W1-05 | Today、Timetable、小组件改为读取 Timeline（截图不变） | Android | W1-02b、W0-06a | — | C04 C26 | 未开始 | — | — | — | — | — | — |
| W1-07 | `:core:campus-graph`：统一楼宇、教室、设施、入口、流程实体，迁入现有目录数据 | Android | W0-05 | — | C17 C20 C23 | 已合并 | m-w107b 等 | feat/cis-w1-07-campus-graph | PR#135 | — | 56fd280f | — |
| W2-01 | `:core:automation` 引擎核心（规则、SPI、去重、试运行、运行记录模型） | Android | W1-01、W1-02a | — | C08 | 已合并 | m-w201 等 | feat/cis-w2-01-automation-core | PR#105 | — | b39940eb | Devin 2 项 lease/确认语义留 W2-02 硬验收 |
| W2-02 | 内置触发器、条件与动作，Android 调度与 outbox 消费 | Android | W2-01、W1-03 | Room v13 | C08 | 未开始 | — | — | — | — | — | — |
| W2-03 | 4 个规则模板与 `WeatherProvider` SPI | Android | W2-02、R03 | — | C08 | 未开始 | — | — | — | — | — | — |
| W2-04 | `:core:textextract`：端侧 OCR 与 PDF 文本抽取 | Android | R02、INF-00 | — | C09 | 已合并 | s7-w204 等 | feat/cis-w2-04-textextract | PR#78 | — | cddfb54 | — |
| W2-05 | `:core:inbox`：分享入口、相对日期、规则解析器、指纹去重、改课预览与应用 | Android | W1-02b、W1-03、W2-04 | Room v14 | C09 | 未开始 | — | — | — | — | — | — |
| W2-06 | `:core:ai` BYOK 通道（§6.8） | Android | W1-04 | — | C10 C27 | 未开始 | — | — | — | — | — | — |
| W2-07 | 收件箱的 AI 解析器（可选路径，需预览和单次同意） | Android | W2-05、W2-06 | — | C09 C10 | 未开始 | — | — | — | — | — | — |
| W3-01 | 培养方案导入、`DegreeAuditor`、人工认定、依据追踪 | Android | W0-05 | Room v15 | C11 | 未开始 | — | — | — | — | — | — |
| W3-02 | `:core:solver` 与 `TravelTimeMatrix`、无解解释 | Android | R04、W1-02a | — | C12 | 已合并 | w302-solver 等 | feat/cis-w3-02-solver | PR#87 | — | 240eb54 | — |
| W3-03 | 选课沙盘：求解器 ↔ 选课草稿 ↔ 培养方案 | Android | W3-01、W3-02 | — | C12 | 未开始 | — | — | — | — | — | — |
| W3-04 | `:core:knowledge`：资料归档、关联课程与教学周、全文搜索、作业截止 | Android | W1-03、W2-04、W1-02b | Room v16 | C13 | 未开始 | — | — | — | — | — | — |
| W3-05 | 复习排程（求解器复用） | Android | W3-02、W3-04 | — | C13 | 未开始 | — | — | — | — | — | — |
| W3-06 | 课程资料问答（BYOK，答案附出处） | Android | W3-04、W2-06 | — | C13 C10 | 未开始 | — | — | — | — | — | — |
| CT-01 | 群组、邀请、成员 | 两仓 contracts | INF-02 | — | C14 C28 | 已合并 | ct01-contract 等 | feat/cis-ct-01-group-contract | cloud PR#12；Android PR#95 | — | cloud 9ba3a2f；Android 43d58a6 | 11 端点已随 W4-01 翻 active |
| CT-02 | 忙闲块与投票 | 两仓 contracts | CT-01 | — | C15 C28 | 已合并 | m-ct02 等 | feat/cis-ct-02-availability-contract | cloud PR#14；Android PR#99 | — | cloud a0d9cd4；Android 2b951004 | 7 端点 planned，实现属 W4-02 |
| CT-03 | 账本、值日、报修 | 两仓 contracts | CT-01 | — | C16 C28 | 已合并 | m-ct03b 等 | feat/cis-ct-03-dorm-contract | cloud PR#16；Android PR#108 | — | cloud 6808fba；Android 95e6650f | 27 端点 planned，实现属 W4-03 |
| CT-04 | 地点状态 | 两仓 contracts | INF-02 | — | C17 C28 | 已合并 | m-ct04b 等 | feat/cis-ct-04-place-status-contract | cloud PR#20；Android PR#115 | — | cloud 53990f9；Android a7a6eaa1 | 9 端点已随 W4-04 翻 active |
| CT-05 | 论坛与私信保留端点（501） | 两仓 contracts | INF-02 | — | C18 C28 | 已合并 | m-ct05 等 | feat/cis-ct-05-ugc-reserved-contract | cloud PR#22；Android PR#120 | — | cloud fa320ac；Android 55293ada | 12 端点 reserved |
| CT-06 | 内容包 | 两仓 contracts | INF-02 | — | C19 C28 | 已合并 | m-ct06c 等 | feat/cis-ct-06-content-pack-contract | cloud PR#27；Android PR#134 | — | cloud 7102e2d；Android 1d1eadf1 | 10 端点 planned，实现属 W5-02 |
| CT-07 | E2EE 同步块 | 两仓 contracts | INF-02 | — | C07 C28 | 进行中 | m-ct07 | feat/cis-ct-07-e2ee-contract | 待创建 | — | — | — |
| W4-01 | `packages/community` | 云端 | CT-01、W0-08 | SQL 0005 | C14 | 已合并 | m-w401 等 | feat/cis-w4-01-community | cloud PR#15；Android PR#116 | — | cloud 8ce6ae1；Android 3cf03876 | — |
| W4-02 | `packages/availability` | 云端 | CT-02、W4-01 | SQL 0006 | C15 | 已合并 | m-w402c 等 | feat/cis-w4-02-availability | cloud PR#25；Android PR#129 | — | cloud 89dea7b；Android c1a51169 | — |
| W4-03 | `packages/ledger`（只记账） | 云端 | CT-03、W4-01 | SQL 0007 | C16 | 已合并 | m-w403c 等 | feat/cis-w4-03-ledger | cloud PR#26；Android PR#132 | — | cloud 4d8da4d；Android 4937fcbf | — |
| W4-04 | `packages/place-status` | 云端 | CT-04、W0-08 | SQL 0008 | C17 | 已合并 | m-w404b 等 | feat/cis-w4-04-place-status | cloud PR#23；Android PR#123 | — | cloud e7a9477；Android 51a389e5 | — |
| W4-05 | Android `:core:community` 客户端、本地缓存、注册擦除链路 | Android | CT-04、W0-05 | Room v17（暂缓 AUTO-037） | C14 C27 | 修复中：缓存竞态/毒化（PR#137） | m-w405fix2 | feat/cis-w4-05-community-client | PR#137 | — | — | — |
| W4-06 | 论坛与私信接口预留：云端 501、Android `DisabledForumPort` | 两仓 | CT-05、W0-08、W4-05 | — | C18 | 未开始 | — | — | — | — | — | — |
| W4-07 | 组队找时间（#33） | Android | W4-05、W1-02b | — | C15 | 未开始 | — | — | — | — | — | — |
| W4-08 | 宿舍协作（#37） | Android | W4-05、W4-09 | — | C16 | 未开始 | — | — | — | — | — | — |
| W4-09 | `:core:ledger` 纯 JVM 分摊算法（与云端语义一致） | Android | INF-00 | — | C16 | 已合并 | s3-w409 | feat/cis-w4-09-ledger | PR#63 | — | 8aadb4d | — |
| W4-10 | 设施雷达与状态上报（#39） | Android | W4-05、W1-07 | — | C17 | 未开始 | — | — | — | — | — | — |
| W4-11 | `packages/sync` E2EE 块存储 | 云端 | CT-07、W0-08 | SQL 0010 | C07 | 未开始 | — | — | — | — | — | — |
| W4-12 | Android 跨设备同步客户端 | Android | W1-04、W4-11 | — | C07 | 未开始 | — | — | — | — | — | — |
| W5-01 | `dis-datapack/v1` Schema、加载器、签名 | 两仓 | W1-07、INF-02 | Room v18 | C19 | 进行中 | m-w501 | feat/cis-w5-01-datapack | 待创建 | — | — | — |
| W5-02 | `packages/content-catalog` | 云端 | CT-06、W5-01 | SQL 0009 | C19 | 未开始 | — | — | — | — | — | — |
| W5-03 | `:core:guide` FlowRunner | Android | W5-01、W1-02b | — | C20 C21 | 未开始 | — | — | — | — | — | — |
| W5-04 | 办事流程导航（#38） | Android | W5-03 | — | C20 | 未开始 | — | — | — | — | — | — |
| W5-05 | 考证、竞赛与奖助中心（#41） | Android | W5-03、W3-01 | — | C21 | 未开始 | — | — | — | — | — | — |
| W5-06 | 首批数据包草稿：10 个高频事项、机会目录、设施目录（只用公开来源，一律 `UNVERIFIED`，D1） | 数据 | W5-01 | — | C19 | 未开始 | — | — | — | — | — | — |
| W5-07 | 贡献流水线：Schema 校验、链接检查、差异渲染 | 两仓 | W5-01 | — | C19 | 未开始 | — | — | — | — | — | — |
| W6-HOTFIX | 插件安全热修：S1 升级权限确认、S2 回滚收权、S3 Android 手填入口隐藏或禁用、S5 公钥放行使参考插件恢复加载（I43） | 两仓 | INF-01 | 无迁移 | C22 | 已合并 | s-w6hotfix | feat/cis-w6-hotfix-plugin-safety | Android PR#77；cloud PR#10 | — | Android 4fc3207；cloud cddd2e5 | — |
| W6-00 | 插件规范收敛：统一运行时 snake_case `dis-extension/v2`、6 个槽位、host API 矩阵、共享夹具、示例可加载与诚实文档 | 两仓 | INF-02 | — | C22 C28 | 已合并 | m-w600b 等 | feat/cis-w6-00-plugin-spec | cloud PR#13；Android PR#98 | — | cloud ada9806；Android 63cc901f | AC-01/02/03 契约面微切片待契约线空闲（AUTO-022） |
| W6-01a | 插件安全底座 | 两仓 | W6-00、W0-08、W0-06c | SQL 0011；Room v19 预留 | C22 | 已合并 | m-w601a 等 | feat/cis-w6-01a-plugin-security | cloud PR#19；Android PR#114 | — | cloud 925c0df；Android 490e85de | 双独立审阅通过 |
| W6-01b | 插件开发者工具：keygen / pack / simulate / doctor、三种 init 模板与从零到上架指南 | 两仓 | W6-00 | — | C22 | 已合并 | m-w601b 等 | feat/cis-w6-01b-plugin-tools | cloud PR#18；Android PR#111 | — | cloud e8d6e5f；Android ae91fff6 | — |
| W6-02 | `:core:ui-runtime` 声明式渲染器、6 个槽位接入与插件管理页（阶段2） | Android | W6-01a | — | C22 | 已合并 | m-w602 等 | feat/cis-w6-02-ui-runtime | PR#131 | — | b2160486 | — |
| W6-03 | 室内路网数据包与室外到室内衔接（试点 1 栋楼，#35） | Android | W1-07、W5-01 | — | C23 | 未开始 | — | — | — | — | — | — |
| W6-04 | 一日回放与两套课表通勤对比（#44 轻量） | Android | W1-02b、W3-02 | — | C24 | 未开始 | — | — | — | — | — | — |
| W6-05 | 官方「计算机服务社维修预约」与非官方示例插件全流程、零修改宿主与失败安全验收（阶段2） | 两仓 | W6-01a、W6-02 | — | C22 | 未开始 | — | — | — | — | — | AC-34…AC-36 |

## 更新规则

1. 「依赖」中的卡合入 `main` 前，后续卡不得开工；例外必须写入 `DECISIONS.md`。
2. PR 创建后填入 PR 链接；开发机门禁通过后填结果 SHA；主代理合并后填合并 SHA。
3. 损坏、阻塞或返工必须写明具体条件；等待构建槽、CI 或依赖不算空闲。
4. 本表只覆盖阶段 S；阶段 N 在阶段 S 终验签收后再建表。
