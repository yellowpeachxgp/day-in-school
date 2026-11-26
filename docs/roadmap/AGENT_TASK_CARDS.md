# 代理任务卡 v1.1（阶段 S：swe-2.1 → 阶段 N：neon）

> 配套 [MASTER_PLAN.md](MASTER_PLAN.md)。依赖、编号分配和能力编号以 MASTER_PLAN §13 为准，协作规范以 §12 为准。
> 卡号格式：`INF-xx` 基础设施、`Rxx` 调研、`CT-xx` 契约线、`W<波次>-<序号>` 功能。`[S]` 属于阶段 S（swe-2.1），`[N]` 属于阶段 N（neon）。
> **「改动范围」就是文件锁**：同一时间只能由一个子代理持有。需要越界时先停下来回报。

## 通用提示词前缀（主代理派卡时附在每张卡前面）

```
你是 day-in-school（Android，Kotlin 2.1 / Compose M3 / Room）或 cloud-in-school（Node 22 ESM）的子代理。
必读：根目录 SYSTEM_CONTRACT.md、AGENTS.md、docs/roadmap/MASTER_PLAN.md（§0.5 决策优先）。

工作方式（D5，违反即视为任务失败）：
- 在本机 /Volumes/SN 770/Developer/campus-worktrees/<仓库>/<分支> 建只含源码的 worktree，用读写工具编辑。
- 本机禁止：./gradlew、Android Studio、语言服务器、模拟器、npm install/test、node --test、Docker。
- 构建和测试只在开发机上做：git push devbox <分支>，然后
  ssh "$CIS_DEVBOX" /srv/xpeach-dev/campus/bin/cis-gate <仓库> <分支> <quick|android|android-full|cloud|contracts|secrets> [参数]
- 禁止用 ssh 内联 heredoc 写文件；禁止触碰开发机上非 cis- 前缀的容器、企业项目目录和 runner；禁止全局 docker prune。
- 每完成一个可验证的小步就提交并推送；门禁通过后再 git push origin 并开 PR。

铁律：
- 只修改本卡「改动范围」内的文件。
- 复用 DataProvenance / VerificationLevel / CapabilityState / SchoolAccountScope，不另造等价类型。
- 新表注册到擦除链路并附「擦除后为 0」的测试；Room 或 SQL 迁移只用主代理分配的编号，并附迁移测试。
- 云端端点先改契约（只有契约线能改 contracts/），再实现。
- 用户可见功能必须带骨架 UI（M3 默认组件，不做视觉打磨）和覆盖完整状态矩阵的 PreviewFixtures。
- 没在真机或真实学校账号上验证的，一律不标 DEVICE_TESTED / SCHOOL_VERIFIED。
- 不推 v* 标签，不建 Release，不部署生产；不在仓库、PR、日志里写任何地址、密码、Token、Key。
- 只用 merge 合并；禁止 squash、rebase、force push。
- 交回汇总不超过 30 行：任务 / SHA / 改动 / 验证 / 未完成与风险 / 待决。
```

---

## 基础设施

### INF-00 [S] 开发机就绪（`xhigh`）
- **目标**：按 MASTER_PLAN §12.2–§12.3 建好校园项目在开发机上的全部基础设施，**不碰企业项目的任何东西**。
- **任务**：
  1. 在开发机上以 `xpeach-dev` 身份创建 `/srv/xpeach-dev/campus/{repos,build,cache/gradle,cache/npm,android-sdk,bin,locks,logs,artifacts}`。
  2. 安装 Android 命令行工具到 `/srv/xpeach-dev/campus/android-sdk`（从 Google 官方地址下载并校验 SHA-256），安装与 `version.properties` 和 AGP 匹配的 `platforms;android-36`、对应 build-tools、`platform-tools`。接受 SDK 许可证（与现有 GitHub CI 的做法相同，记入 DECISIONS 待用户复核）。模拟器和系统镜像只在 `xpeach-dev` 已加入 `kvm` 组时安装。
  3. 建两个裸库 `repos/day-in-school.git`、`repos/cloud-in-school.git`，从 GitHub 只读克隆（公开仓库，不需要凭据）。
  4. 在两个仓库的 `tools/devbox/` 编写脚本：
     - `cis-gradle`：4 个 `flock` 构建槽、`MemAvailable < 24GB` 时等待、`PAUSE` 文件检查、优先 `systemd-run --user --scope -p MemoryMax=10G`；设置 `GRADLE_USER_HOME`、`ANDROID_HOME`。
     - `cis-gate <仓库> <分支> <门禁> [参数]`：在 `build/<仓库>/<分支>` 中从裸库检出确切 SHA，写 `local.properties`，运行门禁，日志写到 `logs/<仓库>/<分支>/<sha>-<门禁>.log`，只打印退出码、SHA、耗时、日志路径和末尾约 60 行。
     - `cis-containers`：按 `cis-*` 前缀起停 PostgreSQL、Redis，只绑定 `127.0.0.1` 随机端口，精确清理。
     - `cis-clean`：清理超过 7 天未用的构建 worktree 和日志；只动 `/srv/xpeach-dev/campus/`。
     - 本机侧的 `cis-worktree`：建源码 worktree 并配置 `devbox` 远端（地址从环境变量 `CIS_DEVBOX` 读取，脚本里不写死）。
  5. 在 `cache/gradle/gradle.properties` 写入 §12.3 的 Gradle 参数。
  6. 在开发机上对当前 `main` 跑一次 `android` 和 `cloud`，作为基线；记录耗时、峰值内存。
- **改动范围**：两仓 `tools/devbox/**`；开发机 `/srv/xpeach-dev/campus/**`。
- **验收**：
  - [ ] 基线门禁在开发机上通过，耗时和峰值内存已记录
  - [ ] 同时提交 6 个构建请求时只有 4 个在跑，其余排队
  - [ ] 创建 `PAUSE` 文件后新构建等待，删除后继续
  - [ ] `docker ps` 中只出现 `cis-*` 前缀的校园容器，企业容器数量不变
  - [ ] 两仓脚本里不含任何地址、账号和凭据（`secrets` 扫描通过）

### INF-01 [S] 治理与基线（`xhigh`，原 W0-01 并入）
- **任务**：
  1. 把 `docs/roadmap/MASTER_PLAN.md`、`AGENT_TASK_CARDS.md` 入库（Android 仓库）。
  2. 两仓新增 `AGENTS.md`：概括 §0.5 决策、§12 工作方式、门禁清单、热点文件、禁止事项，并链接计划。
  3. 新建 `task-notes/campus/`：`TASKBOARD.md`（顶部 10 行槽位 + 全部卡片行）、`DECISIONS.md`（D1–D16）、`DEVBOX.md`（只写 `<devbox>`，记录目录、工具版本、脚本、资源预算）、`issue-replies.md`（空模板）、`research/`、`acceptance/`、`ui-change-requests/`。
  4. 实现 `secrets` 门禁：扫描开发机地址、私网和公网 IP 字面量（白名单除外）、Token 格式、私钥头、`.env`。
  5. `git fetch --prune`；记录两仓 `origin/main` 的 SHA、版本号，以及 INF-00 的基线结果。损坏的 `refs/codex/turn-diffs/...` 只列出，**不删除**。
- **改动范围**：两仓 `AGENTS.md`、Android 仓库 `docs/roadmap/**`、`task-notes/campus/**`、两仓 `tools/secret-scan/**`。

### INF-02 [S] 契约收敛（`xhigh`，D15）
- **背景**：两仓的 `contracts/openapi/cloud-v1.yaml` 和 `contracts/endpoints/endpoint-inventory.yaml` 已经不同。
- **任务**：逐项列出差异，判断哪一侧更新、是否与云端运行中的路由一致；以 `cloud-in-school/contracts/` 为权威统一；新增 `tools/contract-ci/sync-contracts.mjs`（把云端仓库的契约复制到 Android 仓库）和一致性检查（Android 仓库 CI 下载云端 `main` 的契约并比对哈希）。
- **改动范围**：两仓 `contracts/**`、`tools/contract-ci/**`、两仓 CI 中契约检查的步骤。
- **验收**：两份契约逐字节一致；故意改动一侧时 CI 变红；差异分析写入 `task-notes/campus/research/INF-02.md`。

---

## 调研（只读，产出写入 `task-notes/campus/research/<编号>.md`，`xhigh`）

| 卡 | 必须回答的问题 | 产出形式 |
|---|---|---|
| R01 | Room FTS4 的 `tokenize=icu` 在 API 31–36 上能否可靠使用？有模拟器就实测，没有就用 Robolectric 加文献，并标明证据等级。不可用时，写入时二元切分的方案、索引大小估算和查询改写规则 | 结论 + 推荐方案 + 测试用例清单 |
| R02 | 端侧中文 OCR 候选（至少 3 个）：是否依赖 GMS、许可证、APK 增量、离线可用性、中文印刷体和截图的准确率 | 对比表 + 推荐 + 许可证原文链接 |
| R03 | 天气数据源候选：是否需要 Key、许可证与署名、配额、中国城市覆盖；默认实现推荐一个免 Key 来源，同时保留 BYOK 选项 | 对比表 + 推荐 |
| R04 | 选课求解：回溯 + 约束传播 + 分支定界，与 QuickXplain 的结合；给出 50 / 100 / 200 个候选教学班的合成基准设计 | 算法说明 + 基准方案 |
| R05 | `CampusRepository` 每个公开方法 → 目标 Repository 的映射表；Today / Timetable / Grades / Map / Settings 每个文件的拆分清单（哪些是状态、哪些是 UI、哪些是副作用） | 两张映射表 |
| R06 | Room v10 的全部表、迁移链、擦除链路注册点；新表应该在哪里注册 | 清单 + 「新表接入擦除链路」的标准写法 |

---

## W0 · 清账与地基

### W0-02 [S] #24 实况通知进度条不动
- **根因**：`core/reminder/.../ReminderNotificationFactory.kt:459-465` 的 `setProgress` 只在发布那一刻写入静态值，而 `ReminderCoordinator.syncLiveCourseNotification` 只在上下课边界重新发布。
- **任务**：
  1. 纯函数 `LiveProgressModel.compute(now, startAt, endAt, leadWindow): ProgressSnapshot?`。
  2. `LiveCourseTicker`：实况窗口内用 inexact 闹钟或 `setWindow`，每 N 分钟重新发布一次（默认 5 分钟，可选 1 / 5 / 10），复用已有的 `setOnlyAlertOnce(true)`；窗口结束、用户关闭实况、切换身份时取消；绑定 `profileId + generation`。
  3. 节拍无法调度时**不调用 `setProgress`**，只保留 Chronometer 倒计时。
  4. API 36 上可选用 `Notification.ProgressStyle`（加版本判断）。
  5. 设置项「实况进度刷新频率」：DataStore key 加骨架设置行。
- **改动范围**：`core/reminder/**`；设置项的骨架 UI 放在 `feature/settings` 中 W0-06c 指定的位置（W0-06c 合入前只加 DataStore key）。
- **验收**：JVM 单测覆盖课前、课中、跨越下课、超出时间范围、零时长；Robolectric 断言窗口开始时调度节拍、结束或切换身份时取消；进程被杀后由下一次边界闹钟恢复。

### W0-03 [S] #12 学期上下文显式化
- **任务**：对外暴露 `TermContext(viewingTermId, naturalTermId, isLockedByUser)`；`viewingTermId != naturalTermId` 时 UiState 带 `termMismatchBanner`；骨架提示条「正在查看 2025-2026-2 · 切换到本学期」。回归测试：老用户停在 2025-2026-2 且没有手动锁定，升级后自动推进到 2026-2027-1；手动锁定时保持不变并显示提示条；本学期教务没有发布考试时显示「当前学期暂无考试安排」，不显示旧学期考试。
- **改动范围**：W0-05 拆出的 `TermRepository`、`ExamRepository`；`feature/grades` 的 state 与 ui 骨架。

### W0-04 [S] #22 / #8 / #15 节次、作息方案与实践课多日排课（Room v11）
- **任务**：
  1. `PeriodTimeEditor`：读写 `period_times`，增删节次、调整起止时间；校验单调递增、互不重叠。
  2. 作息方案：同一学期可以有多套节次时间，按日期区间生效。
  3. 实践课排课从单日扩展为 `List<OccurrenceRule>`（周次集合 × 星期 × 节次或自定义时间段 × 地点）。
  4. 节次时间变化后重排提醒、刷新小组件。
  5. 骨架 UI：节次编辑页、作息方案切换、实践课「周次 × 星期」多选。
- **改动范围**：`core/model`、`core/domain`、W0-05 拆出的 `TimetableRepository`、`HautDatabase`（v11）、`feature/timetable`。
- **验收**：校验规则单测；v10→v11 真实旧库迁移测试；同步教务后用户修改不被覆盖；提醒按新时间重排。

### W0-05 [S] 拆分 CampusRepository（行为不变）
- **任务**：按 R05 的映射拆成 `TimetableRepository`、`TermRepository`、`GradesRepository`、`ExamRepository`、`CampusServicesRepository`、`MomentsRepository`、`ProfileDataWiper`、`SyncOrchestrator`。原类保留为 `@Deprecated` 的委托外观，**公开 API 不变，调用方本卡不改**（调用方迁移在各功能卡里做，避免与 W0-06 冲突）。
- **方法**：先给公开方法补特征测试（对输入输出拍快照），再分批搬代码；每批一个提交，每个提交都能编译、测试全绿。
- **改动范围**：`core/database/src/**`。
- **验收**：拆分前后特征测试一致；`CampusRepository.kt` < 800 行；新文件都 < 1500 行。

### W0-06a / W0-06b / W0-06c [S] 拆分巨型 Screen
- **任务**：按 MASTER_PLAN §10.2 拆成 `Route`、`ViewModel`、`state/`、`ui/`、`preview/`，补完整状态矩阵的 `PreviewFixtures` 和 Roborazzi 截图。a = Today、Timetable、Grades；b = Map；c = Settings（按二级页拆分）。**行为不变**。
- **改动范围**：a = `feature/today/src`、`feature/timetable/src`、`feature/grades/src`；b = `feature/map/src`；c = `feature/settings/src`。不改任何 `build.gradle.kts`。
- **验收**：现有测试全绿；`ui/` 下的文件不引用 Repository 或 ViewModel（依赖方向检查）；截图基线在开发机生成；审阅者确认是纯搬移。

### W0-07 [S] 测试与架构护栏
- **任务**：接入 Roborazzi；`tools/dep-check`（禁止 `core → feature` 等反向依赖，检查 `ui/` 包的 import）；文件行数门禁（新文件超过 1500 行警告，超过 2500 行失败，老文件进白名单逐步清零）；把三项接入 `cis-gate android` 和托管 CI。
- **改动范围**：所有 `build.gradle.kts`、`gradle/**`、`tools/dep-check/**`、`.github/workflows/android-ci.yml`。
- **验收**：故意引入违规时门禁变红；Roborazzi 截图在开发机录制与比对成功。

### W0-08 [S] 云端路由表拆分
- **任务**：`apps/api/src/app.mjs` 拆成 `routes/<领域>.mjs` 和 `router.mjs`，路由表结构为 `{ method, path, operationId, handler, auth }`；扩展 `contract-parity.test.mjs`，与 OpenAPI 的 operationId 做 100% 双向比对。**行为不变**。
- **改动范围**：`cloud-in-school/apps/api/src/**`、`tests/contract-parity.test.mjs`。
- **验收**：`cloud` 门禁全绿；`app.mjs` < 600 行。

### W0-09 [S] issue 回复草稿（`high`，D13）
- 给 #5 #6 #7 #10 #13 #14 #16 #17 #18 #19 写回复草稿：修复提交、版本号、能力边界（例如 #13 是参考数据，不是实时占用），请报告人复测。#46、#20 按 MASTER_PLAN §3.3 的理由写关闭草稿；#11 标为运营事项；#45 #9 说明「本轮只留接口」。标签方案：`bug`、`data`、`enhancement`、`proposal`、`ops`、`wontfix`、`needs-device-verify`、`wave:W0`…`wave:W6`。
- **只写到 `task-notes/campus/issue-replies.md`，不在 GitHub 上执行任何操作。**

### W0-10 [S] 已修复 issue 的回归测试
- 为 #5（2 号楼与 2/3 餐厅表述）、#6（缩放范围与手势）、#7（停课后可恢复）、#10（嵩山路围栏）、#13（嵩山路自习数据）、#14（`#053968`）、#16（W111）、#17（药品目录条数与免责声明）补回归测试，让这些「已修复」的说法有测试支撑。
- **改动范围**：各模块 `src/test/**`。不改生产代码；发现没有真正修好的，回报主代理另开修复卡。

---

## W1 · 个人内核

### W1-01 [S] `:core:events`
- 按 §6.2 定义 `DomainEvent`、`EventBus` 和 outbox 协议：纯 JVM 接口、内存实现、测试。
- **验收**：至少一次投递；按 `eventId` 幂等 ack；消费者重启后从未 ack 处继续。

### W1-02a [S] `:core:timeline` 核心
- 纯 JVM：`TimelineEvent`、`TimelineSource`、`TimelineService`（含 `busyBlocks`、`freeSlots`）、`IcsExporter`（UID = `TimelineEventId`）。
- **验收**：跨日、跨周、校区时区、忙闲合并的单测；ICS 往返测试。

### W1-02b [S] Timeline 首批 Source
- 课程（含今日停课、仅改本次、调休 `calendar_overrides`）、考试、实践课（W0-04 的 OccurrenceRule）、个人日程（`personal_events`，表由 W1-03 建；本卡先用接口，W1-03 合入后接通）。
- **验收**：§6.1 的验收要点逐条有测试；多次同步后 ID 不变。

### W1-03 [S] `:core:vault`（Room v12）
- 新表：`personal_events`、`entity_journal`、`domain_outbox`、`search_fts` + `search_docs`、`blob_store`。`EntityWriter` 包住课程、成绩、考试的写入，记录 journal 并发布事件。中文分词按 R01 结论实现。骨架 UI：全局搜索页、实体变更历史抽屉。
- **验收**：v11→v12 真实旧库迁移测试；擦除后所有新表为 0；搜索「高数」「离散」「W111」都能命中；撤销一次变更后数据和 journal 都正确。

### W1-04 [S] 备份与恢复（`dis-backup/v1`）
- ZIP：`manifest.json`（格式版本、appVersion、schemaVersion、Profile 指纹、条目计数、SHA-256）+ CanonicalJson 数据；口令派生 AES-GCM 整体加密。**Schema 层面不含任何凭据、Cookie、AI Key 字段**，测试断言。恢复前预览；MERGE / REPLACE。骨架 UI：导出向导、恢复向导。
- **验收**：备份 → 清空 → 恢复零差异；损坏、口令错误、版本过高、身份不匹配都明确报错且不发生部分写入。

### W1-05 [S] Today、Timetable、小组件改为读取 Timeline
- 替换现有时间计算，**不改 UI**；顺带把这些调用方从 `CampusRepository` 外观迁移到新 Repository。
- **验收**：截图基线不变；四款小组件快照测试不变。

### W1-07 [S] `:core:campus-graph`
- 统一实体 `Campus → Building → Floor → Room / Facility / Entrance / IndoorNode`、`Service`、`Procedure`；稳定 ID `<schoolId>/<campusId>/<type>/<slug>`；把 `:core:location` 的楼宇、`CampusStudyRoomCatalog`、`CampusHospitalPharmacyCatalog`、餐厅数据、社团场地迁成图数据；三级来源（数据包 > 用户本地覆盖 > 众包）。
- **验收**：迁移前后查询结果一致（特征测试）；W0-10 的数据回归测试全绿。

---

## W2 · 自动化、收件箱与 AI

### W2-01 [S] `:core:automation` 引擎核心
- §6.3 的规则模型、Trigger / Condition / Action SPI（带 JSON Schema）、`ActionRisk`、去重键、试运行、运行记录模型。纯 JVM。
- **验收**：`certainty == UNKNOWN` 视为不通过并记录原因；`EXTERNAL_WRITE_FORBIDDEN` 类动作无法注册；同一触发实例只执行一次。

### W2-02 [S] 内置触发器、条件、动作与调度（Room v13）
- 触发：定时、课前 N 分钟、水电阈值、粗定位地理围栏、领域事件；条件：今天有课、在校、专注时段、通勤时长、数据新鲜度；动作：本地通知、创建待办或日程、刷新首页卡片和小组件。Android 调度：inexact 闹钟 / WorkManager 批量唤醒，消费 outbox。表：`automation_rules`、`automation_runs`、`automation_dedupe`。骨架 UI：规则列表、表单式编辑器、运行记录。
- **验收**：进程被杀后补处理；重复事件不重复执行；擦除后为 0。

### W2-03 [S] 规则模板与天气 SPI
- 模板：早八带伞、跨校区通勤提醒、水电低余额周五提醒、临时停课推荐自习。`WeatherProvider` SPI 加 R03 推荐的默认实现（带署名），数据过期时条件判定为 UNKNOWN。
- **验收**：每个模板都有试运行测试；天气不可用时不误触发。

### W2-04 [S] `:core:textextract`
- 按 R02 结论接入端侧 OCR（不依赖 GMS）和 PDF 文本抽取；输出带行坐标，用于原文高亮回溯。
- **验收**：合成截图和 PDF 夹具的抽取测试；APK 增量记录在 PR 中。

### W2-05 [S] `:core:inbox`（Room v14）
- §6.4 全部接口：分享菜单入口、剪贴板和链接导入、`RelativeDateResolver`（明天 / 本周五 / 下周一 / 第 8 周周三）、规则解析器、`NoticeFingerprinter` 去重、改课预览与应用（`Actor.INBOX`）。表：`inbox_items`、`inbox_proposals`。骨架 UI：收件箱列表、原文与抽取结果对照、逐字段修正、确认页。
- **验收**：C09 的全部要点；原件保存在 `blob_store`，擦除后为 0。

### W2-06 [S] `:core:ai` BYOK 通道
- §6.8 全部接口：`AiKeyStore`（Keystore AES-GCM、`noBackupFilesDir`、按 profileId 分区）、`AiProviderPort`（OpenAI 兼容）、发送预览、本地脱敏、单次 `AiConsentToken`。骨架 UI：「我的 → AI 服务」配置页（baseUrl、model、Key、连通性测试）。
- **验收**：Key 不出现在备份、日志、诊断、崩溃报告和 saved state 中（测试断言）；只允许 HTTPS；自动化动作注册表中不存在 AI 动作。

### W2-07 [S] 收件箱 AI 解析器
- 作为 `NoticeParser` 的可选实现：先预览发送内容，用户点「发送」才调用；结果是 `ProposedAction`，全部字段标「模型生成」，仍需确认。
- **验收**：未配置 Key 时入口不可用并给出说明；拒绝发送时不产生网络请求。

---

## W3 · 学业智能

### W3-01 [S] 培养方案与毕业审计（Room v15）
- `TrainingPlanImporter`（`univ-training-plan/v1` JSON 与 CSV，以及已有教务网关的培养方案接口）、`DegreeAuditor`、`AuditOverride`（进 journal）、`AuditReport.trace`。表：`training_plan_imports`、`audit_overrides`。骨架 UI：毕业进度看板、「查看依据」、人工认定。
- **验收**：C11 全部要点；重修、改名、替代课程、跨专业选课的用例；学分不重复计算。

### W3-02 [S] `:core:solver`
- 按 R04：`TimetableSolver`、硬约束与加权偏好、QuickXplain 无解解释、`TravelTimeMatrix`（由路网 A* 预计算并按楼宇对缓存，跨校区标记为课间不可达）。
- **验收**：50 个候选教学班在开发机上 < 2 秒（基准测试）；无解时给出最小冲突集。

### W3-03 [S] 选课沙盘
- 接通培养方案（优先排毕业所需课程）、求解器和 `:core:selection` 草稿；保存多个候选方案并对比早八数量、空档、通勤、每日负担。骨架 UI：约束编辑、方案列表、方案对比。
- **验收**：C12 全部要点；导出的方案只是草稿，不触发任何提交。

### W3-04 [S] `:core:knowledge`（Room v16）
- 课程资料（课件、PDF、照片、笔记、作业）按课程和教学周归档；全文搜索定位到页码或图片；作业截止进入时间流。表：`course_materials`、`review_tasks`。骨架 UI：课程空间页。
- **验收**：C13 的归档、搜索、截止提醒部分；资料可导出。

### W3-05 [S] 复习排程
- 根据考试日期和可用空档，用求解器把复习任务排进时间流；漏做后可重新安排。
- **验收**：不与课程冲突；重新安排不丢任务。

### W3-06 [S] 课程资料问答（BYOK）
- 基于用户导入的资料问答，答案附具体出处（资料与页码）；区分「资料原文」与「模型补充」。
- **验收**：没有出处的句子被标为「模型补充」；未配置 Key 时不可用。

---

## 契约线（串行；两仓 `contracts/` 同步修改；每张卡一个 PR 对）

每张 CT 卡都要：写 OpenAPI 3.1.2 路径（请求体、响应体、HTTP 码、错误码、幂等、授权、版本、示例、兼容性说明）、AsyncAPI 事件、`error-catalog.yaml`、`permission-catalog.yaml`、`endpoint-inventory.yaml`、`cloud-api-examples.http`；跑 `contracts` 门禁。端点清单见 MASTER_PLAN §6.7。

| 卡 | 范围 |
|---|---|
| CT-01 | 群组、邀请、成员；事件 `group.member.joined / left` |
| CT-02 | 忙闲块（只存时间块，粒度 ≥ 30 分钟，带 TTL）、交集查询、投票、确认；事件 `group.availability.updated`、`group.event.confirmed`、`group.event.conflict_detected` |
| CT-03 | 账本（整数分，只追加更正）、结算两方确认、值日、报修；事件 `ledger.entry.created / amended`、`settlement.confirmed` |
| CT-04 | 地点状态上报、按类型 TTL、分歧聚合；事件 `place.status.changed` |
| CT-05 | 论坛与私信保留端点：全部标 `x-dis-status: reserved`，响应只有 `501` + `FEATURE_NOT_ENABLED` |
| CT-06 | 内容包的列表、版本、管理端发布与下架；事件 `content-pack.published` |
| CT-07 | E2EE 同步块的上传与增量拉取（云端只见密文） |

---

## W4 · 群组云

| 卡 | 任务 | 关键验收 |
|---|---|---|
| W4-01 [S] | `packages/community`（SQL 0005）：群组、邀请码与二维码载荷、成员、角色；身份锚点 `(cloud_user_id, school_id)`；纳入 `/me/data-export` 与 `/me/data-delete` | 邀请过期与次数上限；跨学校隔离；删号后数据删除 |
| W4-02 [S] | `packages/availability`（SQL 0006）：忙闲块存取（TTL 过期删除）、交集计算（全员或至少 N 人）、投票、确认后发事件 | 退出或撤回后立即不可见；成员调课只触发 `conflict_detected`，不改期 |
| W4-03 [S] | `packages/ledger`（SQL 0007）：只追加账本、分摊（均摊、比例、指定参与人、按入住天数）、结算两方确认 | 与 W4-09 共用同一组金额夹具，结果逐分一致；没有任何支付接口 |
| W4-04 [S] | `packages/place-status`（SQL 0008）：上报、按类型 TTL、分歧聚合、举报 | 过期后状态为未知；相互矛盾的报告同时展示 |
| W4-05 [S] | Android `:core:community`：由 OpenAPI 生成客户端（或手写，并与契约做测试比对）、本地缓存（Room v17）、注册擦除链路；功能开关 | 离线时显示缓存且标为陈旧；擦除后为 0 |
| W4-06 [S] | 论坛与私信接口预留：云端保留端点统一返回 501；Android `CommunityForumPort` + `DisabledForumPort`、开关 `community.forum` 默认关闭、插件槽 `community.boards` 登记为保留 | C18 全部要点；**不建表、没有 UI 入口** |
| W4-07 [S] | #33 组队找时间：建组、只共享忙闲、候选时段、投票、确认后写入时间流；骨架 UI | C15 全部要点 |
| W4-08 [S] | #37 宿舍协作：记账、分摊、结算、值日轮班（交换、请假、完成确认）、报修记录、水电余额联动提醒；骨架 UI | C16 全部要点 |
| W4-09 [S] | `:core:ledger` 纯 JVM：`Money`（Long，单位分）、分摊算法（余数分配规则固定并文档化）、结算状态机 | 金额属性测试（总和守恒） |
| W4-10 [S] | #39 设施雷达：设施目录（来自 Campus Graph 与数据包）、能力筛选、故障上报、计时提醒、收藏；骨架 UI | C17 全部要点 |
| W4-11 [S] | `packages/sync`（SQL 0010）：E2EE 块存储与游标 | 服务端无法解密（测试断言只存密文） |
| W4-12 [S] | Android 跨设备同步：基于 dis-backup 的数据模型做增量块，冲突展示两个版本供用户选择 | 两设备模拟测试；冲突不静默覆盖 |

---

## W5 · 校园知识网络

| 卡 | 任务 | 关键验收 |
|---|---|---|
| W5-01 [S] | `dis-datapack/v1` JSON Schema（放在 `contracts/schemas/`，由契约线合入）、加载器、Ed25519 签名（复用 `:core:school-packages`）、已安装数据包表（Room v18） | 篡改、过期、`reviewBy` 过期都有明确状态 |
| W5-02 [S] | `packages/content-catalog`（SQL 0009）：发布、版本、下架、签名校验 | 未签名的包被拒绝 |
| W5-03 [S] | `:core:guide` FlowRunner：问答分支 → 个人路径 → 材料清单 → 时间流节点；资格判断三态（符合 / 待核实 / 未满足） | 信息缺失时判为「待核实」，不判为符合 |
| W5-04 [S] | #38 办事导航：事项目录、搜索、个人清单、地图跳转、提醒、反馈；骨架 UI | C20 全部要点 |
| W5-05 [S] | #41 机会中心：机会目录、资格检查（可计算部分接成绩与证书）、申请进度、材料库（有效期与版本）、提醒；骨架 UI | C21 全部要点 |
| W5-06 [S] | 首批数据包草稿：10 个高频办事事项、机会目录、设施目录。**只用公开来源**，每条带 `sources` 和 `verifiedAt`，一律 `UNVERIFIED`（D1）；无法从公开来源确认的字段留空，不编造 | Schema 校验通过；每条都有可访问的来源链接 |
| W5-07 [S] | 贡献流水线：PR 中自动做 Schema 校验、链接存活检查、差异渲染；`CONTRIBUTING` 中的数据贡献章节 | 一个外部贡献示例 PR 在 CI 中走通 |

---

## W6 · 平台与空间

| 卡 | 任务 | 关键验收 |
|---|---|---|
| W6-01 [S] | 插件 v2.1：§6.5 的新 types、slots、permissions（含保留的 `community.boards`），保持 v1 / v2 兼容；`dis-plugin validate / simulate / pack / sign` | 旧插件全部可装；权限扩大时要求重新授权 |
| W6-02 [S] | `:core:ui-runtime`：`feature-ui-v1` 声明式节点渲染器（从 `XPeachSdkComponents` 抽出），不执行任意代码 | 每种节点都有 fixtures 和截图 |
| W6-03 [S] | #35 室内路网试点：1 栋楼的教室级路网数据包、跨楼层路线、室外到入口的衔接、手动或扫码定位起点；骨架 UI | C23 全部要点 |
| W6-04 [S] | #44 一日回放：在现有 2.5D 底图上按时间播放全天路线，对比两套课表的步行距离、跨楼次数、通勤时间；骨架 UI | C24 全部要点 |
| W6-05 [S] | 非官方示例插件：只用公开 SDK 完成卡片、命令、自动化动作和数据包的接入；宿主 Git tree 和构建校验 SHA 不变（契约 I.4） | 安装、停用、升级、回滚、卸载全流程；故障插件被隔离 |

---

## 阶段 N 卡片（neon；阶段 S 终验通过并经用户签收后才开始）

> 范围只限 `feature/*/ui/**`、`feature/*/preview/**`（只能补充）、`core/designsystem/**` 和文案资源。需要改状态层时提「UI 契约变更请求」（MASTER_PLAN §15）。每张卡都要交浅色、深色、200% 字号截图和 Compose 语义测试。

| 卡 | 内容 |
|---|---|
| N-01 | 设计 token 与组件库：拆分 `HautTheme.kt` 为 tokens / theme / components；统一 `ProvenanceBadge`（OFFICIAL / DERIVED / ESTIMATED / USER_REPORTED / PREDICTED / SIMULATED / STALE）；统一状态矩阵组件（Loading / Empty / Partial / Stale / Error / NeedsPermission / NeedsLogin / Offline / Unsupported） |
| N-02 | 今日、课表、学业、校园、我的五个主页面的视觉与动效统一 |
| N-03 | #24 设置项、#12 学期提示条、#22 节次编辑器与实践课排课矩阵（拖拽调整、批量平移） |
| N-04 | 全局搜索、变更历史抽屉、备份与恢复向导 |
| N-05 | 自动化：可视化规则编辑器、模板库、试运行结果、运行记录 |
| N-06 | 收件箱：原文与抽取结果对照高亮、逐字段修正、补充日期交互 |
| N-07 | AI 服务配置页与发送预览页 |
| N-08 | 毕业进度看板与「查看依据」、选课沙盘与方案对比、课程空间与复习计划 |
| N-09 | 组队找时间、宿舍空间（账本、值日、报修）、设施雷达地图图层 |
| N-10 | 办事导航、机会中心 |
| N-11 | 室内导航、一日回放 |
| N-12 | 插件管理页与声明式卡片的视觉规范 |
| N-13 | 全站无障碍专项：TalkBack 文案、焦点顺序、触控区域、减少动画模式 |
