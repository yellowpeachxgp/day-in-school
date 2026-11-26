# 决策记录（DECISIONS）

> 每条记录包含编号、结论、日期和依据。D1–D16 是 MASTER_PLAN §0.5 的初始用户决策，日期均为 2026-11-26，权威表述以 `docs/roadmap/MASTER_PLAN.md` §0.5 为准。

## D1–D16 初始用户决策

| 编号 | 决策 | 落地要求 |
|---|---|---|
| D1 | **不与学校合作** | 学校数据只来自公开资料和用户本人账号的只读访问。#38 #41 的数据包由维护者从公开页面整理，每条带 `sources` 和 `verifiedAt`，代理产出的数据一律标 `UNVERIFIED`，等人工核对。选课提交、自动写入等需要校方授权的能力继续默认关闭，**不追求官方授权**。§11 第 1 条作废 |
| D2 | **论坛（#45）和私信（#9）只留接口，不做** | 云端：`boards / posts / replies / reports / moderation / media` 端点全部写进 OpenAPI，标注 `x-dis-status: reserved`，实现统一返回 `501` 和错误码 `FEATURE_NOT_ENABLED`；**不建表、不写业务逻辑**。Android：`:core:community` 中定义 `CommunityForumPort`，只提供 `DisabledForumPort` 实现，开关 `community.forum` 默认关闭，没有任何 UI 入口。插件槽 `community.boards` 只登记为保留。G6 保留为将来开启的前置条件 |
| D3 | **AI 功能用户自带 Key（BYOK）** | 新增 `AiProviderPort`（OpenAI 兼容的 chat 与 vision 接口，见 §6.8），用户填写 baseUrl、model、key。Key 用 Keystore AES-GCM 加密，按 Profile 隔离，不进备份、日志和诊断信息；云端不代理、不存储。默认关闭；每次发送前可预览发送内容；只允许 HTTPS。现有 Parcel AI Key 的存储方式能复用就复用 |
| D4 | **两阶段执行：先 swe-2.1，再 neon** | 阶段 S：swe-2.1 完成全部 `[S]` 卡和可用的骨架 UI（M3 默认组件，状态矩阵完整，不做视觉打磨），并通过阶段 S 终验（§14）。阶段 N：之后才由 neon 执行全部 `[N]` 卡（§15）。两个阶段不重叠 |
| D5 | **全程在开发机上构建和测试** | 编译、测试、Lint、截图、模拟器、容器全部在开发机上进行。本机 Mac（16GB）只保留代理会话、只含源码的 worktree，以及 `ssh / git / gh` 这类轻量命令（§12.2） |
| D6 | **与企业项目共用开发机，互不干扰** | 校园项目的资源统一用 `cis-` 前缀，目录集中在 `<campus-root>/`。不得触碰 `xpeach-stg-*`、`ent-*`、`swe2-*` 容器，企业项目的 runner、企业项目 repos/worktrees（路径去标识化）和企业 CI 目录（路径去标识化）。严禁任何全局 `docker ... prune` |
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

## 自主决策

| 编号 | 日期 | 结论 | 依据 | 状态 |
|---|---|---|---|---|
| AUTO-001 | 2026-11-26 | bootstrap 期间 INF-00/INF-01 与文档卡无法等待 cis-gate，可用静态自检先开 PR，后续仍须补现场门禁；自主决策，待用户复核 | INF-00 尚未使 cis-gate 可用；INF-01 属于治理与文档基线，若不先行入库会阻塞全部任务卡引用 | 待用户复核 |
| AUTO-002 | 2026-11-26 | INF-01 不再持有 issue-replies.md，W0-09 作为该文件唯一 owner；自主决策，待用户复核 | 主代理冲突裁决：W0-09 已在 PR#47 中新增 substantive task-notes/campus/issue-replies.md，为避免 add/add 冲突并保持文件锁清晰 | 待用户复核 |
| AUTO-003 | 2026-11-26 | 公开仓库不保存真实开发机账号/路径；统一用 `<devbox-user>`/`<campus-root>`/`$CIS_CAMPUS_ROOT`；本公开 roadmap 副本按 D16 去标识化；自主决策，待用户复核 | 主代理高优先级隐私裁决：D16 与正文冲突时以 D16 为准，即使 roadmap 入库副本不能逐字包含真实账号/路径 | 待用户复核 |
| AUTO-004 | 2026-11-26 | 为降低未合并公开分支中开发机账号/路径字面量的可见性，已删除两仓旧 INF-01 远端分支；但删除分支不等于彻底消除公开 diff，两个自动关闭 PR 的 diff 仍可能公开可读；该残留属于低敏感账号/路径元数据，列入用户复核；自主决策，待用户复核 | 主代理隐私补救说明：旧远端分支和开发机裸库 ref 已删除，两个自动关闭 PR 不更新不重开；可见性降低不得宣称完全清除 | 待用户复核 |
| AUTO-005 | 2026-11-26 | INF-00/INF-01 两卡曾因旧分支历史含开发机账号/路径字面量而采用干净分支重建；干净分支压缩为最少逻辑提交属于隐私补救例外，但后续复核发现 INF-01 首个 clean 分支历史仍含拼接编码的 devbox account，不能宣称完全无污染；最终处置见 AUTO-008，公开 diff 残留列入用户复核 | 主代理补充裁决：隐私更保守优先于细化提交原则；不得宣称公开 diff 已完全清除 | 待用户复核 |
| AUTO-006 | 2026-11-26 | cloud 当前无依赖且无 lockfile 时，cis-gate cloud 跳过安装步骤；一旦 package.json 出现 dependencies/devDependencies 而无 lockfile 则失败；自主决策，待用户复核 | cloud-in-school 当前 package.json 无 dependencies/devDependencies 且无 lockfile；npm ci EUSAGE 与项目无依赖现实冲突，需区分无依赖跳过安装与缺 lockfile 失败 | 待用户复核 |
| AUTO-007 | 2026-11-26 | 本机 Mac worktree 路径（/Volumes/...）不是开发机地址/账号，也不是 D16 秘密；roadmap 和 AGENTS 可保留，scanner 不应把它当违规；自主决策，待用户复核 | 主代理澄清裁决：避免过度去标识化破坏计划可执行性 | 待用户复核 |
| AUTO-008 | 2026-11-26 | 因首个 clean 分支历史仍含拼接编码的 devbox account，INF-01 需二次干净重建；隐私补救例外，待用户复核 | 主代理隐私裁决：首个 feat/cis-inf-01-governance-clean 提交不得进入 main 历史；最终从最新 origin/main 建 feat/cis-inf-01-governance-clean2，这不是 force push，也不改写 main | 待用户复核 |
| AUTO-009 | 2026-11-26 | acceptance 工具第一/第二版因含拼接编码的 devbox 敏感字面量，PR#48/#56 均不合并；最终以 acc/cis-stage-0-skeleton-clean2 二次干净重建，隐私补救例外，待用户复核 | 主代理隐私裁决：acceptance 骨架与 INF-01 同样不得把拼接编码的敏感字面量带入 main 历史 | 待用户复核 |
| AUTO-010 | 2026-11-27 | INF-00 属 secrets scanner 自举前置且两仓契约漂移已由 INF-02 专卡处理；train/cis-001 的 secrets exit3 与 contracts exit1 不视为 INF-00 内容失败。INF-00 可基于人工/审阅脱敏证据、android-full/cloud 全绿、两独立审阅与阶段验收先合入；INF-01/INF-02 合入后必须在 main 重跑 secrets/contracts。自主决策，待用户复核 | 主代理自举与依赖裁决：secrets/contracts 工具链尚未合入 main 时不能把固有 bootstrap 缺口记为 INF-00 内容失败；后续 main 重跑作为强制补偿门禁 | 待用户复核 |
| AUTO-011 | 2026-11-27 | W0-07 独占既有 build.gradle.kts/gradle/**；W1/W4 新纯 JVM 模块在并行期只允许新增本模块 build.gradle.kts 并在 settings.gradle.kts 末尾追加注册，不得修改 root 或既有构建文件。此为例行解释而非放松热点规则，冲突由列车处理；自主决策，待用户复核 | 主代理热点文件解释：限制并行期 Gradle 冲突面，同时不改变 W0-07 对既有构建文件的独占；列车负责相邻追加行冲突合并 | 待用户复核 |
| AUTO-012 | 2026-11-27 | W0-08 不改 contracts/；97 个 core endpoint 做 OpenAPI/inventory/runtime 三方 100% 双向，12 个预存 moments extension 只做显式 extension inventory 与 exact 自检，契约化缺口由 INF-02/契约线关闭；自主决策，待用户复核 | 主代理契约边界裁决：W0-08 专注路由拆分与既有 core endpoint 对齐，不越权修改契约权威；extension 缺口必须由契约线关闭 | 待用户复核 |
| AUTO-013 | 2026-11-27 | W0-08 因拆分 `app.mjs` 必然改变整文件 hash，授权最小扩大到 `tools/contract-ci/generate-reservation-fixtures.mjs` 与 `tests/fixtures/reservation-*.json`；优先改语义指纹，不得改 `contracts/`；自主决策，待用户复核 | 主代理行为不变证据裁决：纯搬移后整文件 hash 必然变化，语义指纹比文件 hash 更能证明 handler 等价；范围不得再扩大 | 待用户复核 |
| AUTO-014 | 2026-11-27 | W0-02 可对 `CampusRepository.kt` 做 3 行配置接缝、对 `core/model/src/main/kotlin/cn/edu/haut/campus/core/model/ReminderModels.kt` 做 1 行 `liveProgressRefreshMinutes` 配置字段、对 `core/reminder/build.gradle.kts` 做最小测试依赖改动；不得扩大；自主决策，待用户复核 | 主代理最小接缝裁决：实况进度需要读取刷新频率配置并补测试依赖，三处均为必要最小接缝，但不得演变成 Repository、模型或构建文件宽泛改动 | 待用户复核 |
| AUTO-015 | 2026-11-27 | INF-00/INF-01 bootstrap 列车中，`contracts` 的两仓预存漂移归 INF-02；scanner 未合入前的 exit3 只适用于 INF-00；INF-01 起 secrets 必须真实通过。workflow 暂不覆盖 `train/**`，以原 PR 托管 CI 作合并记录；自主决策，待用户复核 | 主代理 bootstrap 边界裁决：区分工具未合入的 exit3 与真实扫描通过，避免把 INF-02 契约漂移或 train 分支覆盖缺口记为 INF-00/INF-01 内容失败 | 待用户复核 |

## 追加规则

- 主代理自主决策按「计划推荐默认 → 与已决事项一致 → 对隐私和数据更保守」顺序选择，并标注「自主决策，待用户复核」。
- 用户复核推翻某条自主决策时，追加新决策，不改写原记录。
