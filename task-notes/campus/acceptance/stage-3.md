# 阶段联调验收 stage-3：train/cis-003

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：s10b-accfinish（未参与本趟列车开发）；2026-11-27 证据链修正：m-stagefix
> 交叉复核子代理：pending
> 对象：train/cis-003 精确 SHA `296c9cb9cf14befd27a94e9486d5f670c13e2e79`；其 tree `af671e05994bc373e412542855c007e7208b9da9` 与 PR#63 合入 main 的 merge commit `8aadb4d991b93d35b0add22b7dbeb9851ea48e89` 完全一致
> Cloud main（验收时点）：`5480f0d907bd799e83ab4de7e15f2a1b666e8eec`
> 重点能力：C16、C25、C26；范围还包括 R01–R06 调研产出与 W0-09
> 总结论：**train/cis-003 范围内的调研、W0-09 草稿与 W4-09 纯 JVM 内核验收为 pass；stage-3 联调整体为 blocked**。阻断仍是 INF-02 契约漂移；C16/C25/C26 完整能力不能因本趟列车宣称完成。

## 1. 元信息与执行边界

| 字段 | 值 |
|---|---|
| train exact SHA | `296c9cb9cf14befd27a94e9486d5f670c13e2e79` |
| 对应 main merge SHA | `8aadb4d991b93d35b0add22b7dbeb9851ea48e89` |
| 两 commit tree 一致性 | 均为 `af671e05994bc373e412542855c007e7208b9da9`，`git diff --quiet` 为空 |
| Cloud main SHA（验收时点） | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` |
| 验收分支 | `acc/cis-stage-3` |
| 证据索引 | `task-notes/campus/acceptance/evidence/stage-3.jsonl` |
| 本次仓库改动 | 仅验收报告与证据索引；不改业务代码 |
| 验证边界 | 开发机联调验收；不标 DEVICE_TESTED / SCHOOL_VERIFIED；不部署、不发版 |

**证据链更正说明**：本报告早期版本误把后续 main `b754cc41ac522c788602ae35c588ced683d6d03b` 当作 train/cis-003 验收对象，并引用了与该 SHA 对应的 quick/android-full/secrets 数字。2026-11-27 已改按 exact train `296c9cb9cf14befd27a94e9486d5f670c13e2e79` 重跑；后续 main 数字不再作为 stage-3 证据，只可作为另行观察。特别地，exact train 尚未包含 W0-07 guardrails，因此 exact tree 日志中**不要求也不出现** dep-check/file-line-gate；不能拿后续 main 的护栏结果回填本 stage。

列车相关合并：W0-09 PR#47、R05 PR#49、R06 PR#50、R03 PR#52、R02 PR#54、R04 PR#55、R01 PR#60、stage-1 PR#59、W4-09 PR#63。后续合入 main 的 W0-07/W1-00/W1-01a/W0-02 等不属于本 stage 的声明能力。

## 2. exact train 开发机门禁汇总

| 门禁 | 命令（脱敏模板） | SHA | 退出码 | 耗时 | 开发机日志路径 | 结论 |
|---|---|---|---:|---:|---|---|
| Android `android-full` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-003 android-full` | `296c9cb9cf14befd27a94e9486d5f670c13e2e79` | 0 | 21s | `<campus-root>/logs/day-in-school/train/cis-003/296c9cb9cf14befd27a94e9486d5f670c13e2e79-android-full.log` | pass |
| Android `secrets` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-003 secrets` | `296c9cb9cf14befd27a94e9486d5f670c13e2e79` | 0 | 4s | `<campus-root>/logs/day-in-school/train/cis-003/296c9cb9cf14befd27a94e9486d5f670c13e2e79-secrets.log` | pass |
| Cloud `cloud` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate cloud-in-school main cloud` | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` | 0 | 16s | `<campus-root>/logs/cloud-in-school/main/5480f0d907bd799e83ab4de7e15f2a1b666e8eec-cloud.log` | pass |
| Cloud `secrets` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate cloud-in-school main secrets` | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` | 0 | 1s | `<campus-root>/logs/cloud-in-school/main/5480f0d907bd799e83ab4de7e15f2a1b666e8eec-secrets.log` | pass |
| Android `contracts` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-003 contracts` | `296c9cb9cf14befd27a94e9486d5f670c13e2e79` | 1 | 0s | `<campus-root>/logs/day-in-school/train/cis-003/296c9cb9cf14befd27a94e9486d5f670c13e2e79-contracts.log` | fail（INF-02 缺口） |
| Android `quick :core:ledger` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-003 quick :core:ledger` | `296c9cb9cf14befd27a94e9486d5f670c13e2e79` | 0 | 5s | `<campus-root>/logs/day-in-school/train/cis-003/296c9cb9cf14befd27a94e9486d5f670c13e2e79-quick.log` | pass |

门禁细节：

- `android-full`：主构建 `BUILD SUCCESSFUL in 15s`，1486 actionable tasks（804 executed、682 from cache）；pure-JVM second pass `BUILD SUCCESSFUL in 4s`，30 actionable tasks（7 executed、23 up-to-date）；Gradle scope peak 106,631,168 B，低于 10,737,418,240 B 上限。exact train 早于 W0-07a，日志不含 dep-check/file-line-gate。
- Android `secrets`：`SECRET_SCAN_PASS files=566 tracked=579 findings=0 allowlistEntries=59 allowlistUsed=59 warnings=0`。
- `cloud`：204/204 tests pass，fail=0；contract-ci pass（97 个 OpenAPI operations 100% inventory parity、5 个 JSON Schema）。
- Cloud `secrets`：`SECRET_SCAN_PASS files=132 tracked=133 findings=0 allowlistEntries=113 allowlistUsed=113 warnings=0`。
- `contracts`：Android 本地 contract-ci 自身通过（67 个 operations 100% parity）。2026-11-27 重跑时 gate 对照的是最新 Cloud main `0ba12fc835f6945e8484d5a8366092ec3d923ef9`，日志显示 `endpoint-inventory.yaml`、`openapi/cloud-v1.yaml` 与 `examples/cloud-api-examples.http` differ；为避免把后续 Cloud 变化倒灌到历史 stage，另做只读静态比对：exact train 与验收时点 Cloud main `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` 仅 `endpoint-inventory.yaml` 与 `openapi/cloud-v1.yaml` differ。无论按哪一对照，均归 INF-02 预存缺口，不能算 pass。
- `quick :core:ledger`：`CIS_QUICK_MODULE=:core:ledger kind=pure-jvm test_task=:core:ledger:test`，`BUILD SUCCESSFUL in 4s`，5 actionable tasks（2 executed、3 from cache）；quick 后 XML 汇总 35/35 tests pass，failures=0、errors=0、skipped=0（3 个 suite：ExpenseSplitterTest、MoneyTest、SettlementStateMachineTest）。

## 2.1 最新 main 补充观察（不替代 exact train 证据）

按本次整理要求，在最新 Android main `8a978f7be998b082c881c97937b34ee51df2d178` 上重跑当前门禁：

| 门禁 | 退出码 | 耗时 | 关键数字 | 日志 |
|---|---:|---:|---|---|
| `android` | 0 | 28s | 主构建 `BUILD SUCCESSFUL in 14s`，836 actionable tasks（441 executed、395 from cache）；pure-JVM second pass `BUILD SUCCESSFUL in 4s`，38 actionable tasks；dep-check 27 modules / 86 edges / 0 violations；file-line-gate 439 files，FAILED=0、WARNING=0、INFO=1 | `<campus-root>/logs/day-in-school/main/8a978f7be998b082c881c97937b34ee51df2d178-android.log` |
| `secrets` | 0 | 4s | `SECRET_SCAN_PASS files=658 tracked=672 findings=0 allowlistEntries=60 allowlistUsed=60 warnings=0` | `<campus-root>/logs/day-in-school/main/8a978f7be998b082c881c97937b34ee51df2d178-secrets.log` |

该结果只说明最新 main 当前仍可构建且无 secrets 命中；**不得**倒填 train/cis-003 的 exact 验收证据。

## 3. R01–R06 调研产出验收

| 调研 | 结论 | 证据与验收意见 |
|---|---|---|
| R01 FTS 中文分词 | pass（调研交付） | `task-notes/campus/research/R01.md` 存在；推荐写入时 Unicode 规范化 + 中文重叠二元切分 + FTS4 unicode61 + external-content，明确 external FTS 空间估算、W1-03 顺序与测试清单；未把 host/probe 证据冒充真机证据 |
| R02 端侧 OCR | pass（调研交付） | `R02.md` 推荐 PP-OCRv6_tiny ONNX + onnxruntime-android，同时明确排除未验证 16 KB 对齐的 QuickBird OpenCV、分析 ABI/包体风险；结果保持 UNVERIFIED，符合保守隐私边界 |
| R03 天气数据源 | pass（调研交付） | `R03.md` 推荐 MET Norway 为默认免 Key，同时写清 3 小时缓存限制、中国准确率未知、AI agent 条款需人类接受；Open-Meteo 非商业属性未决，BYOK 候选边界明确 |
| R04 选课求解 | pass（调研交付） | `R04.md` 覆盖 CSP、传播、分支定界、QuickXplain、50/100/200 合成基准与 50 候选 <2s 测量设计；明确稀疏多解风险和 honest timeout，不宣称性能已验证 |
| R05 拆分映射 | pass（调研交付） | `R05.md` 给出 CampusRepository 164/164 方法映射、公开属性/Flow/internal seam、巨型 Screen 拆分清单和高风险不变量；可供 W0-05/W0-06 使用 |
| R06 Room 擦除 | pass（调研交付） | `R06.md` 给出 v10 表清单、迁移链、擦除顺序、未清表风险、推荐清零项和新表接入标准；静态证据等级诚实 |

共同检查：6 份调研都在 `task-notes/campus/research/` 中；未发现把 DEVICE_TESTED 或 SCHOOL_VERIFIED 冒充已完成的措辞；调研输出可作为后续任务卡输入，但不等于对应功能已实现。

## 4. W0-09 issue 回复草稿验收

| 项 | 结论 | 证据与说明 |
|---|---|---|
| W0-09 / D13 草稿边界 | pass | `task-notes/campus/issue-replies.md` 存在，明确“仅草稿”，用户确认前不得在 GitHub 回复、关闭或加标签 |
| 已修复清账文本 | pass | #5、#13、#14、#16、#17、#18、#19 均有版本/提交证据；#13 写明非实时占用，#17 写明历史公示价格、非实时库存 |
| 需复核项 | pass | #6、#7、#10 按 needs-device/reporter verify 处理，不直接关闭，符合 DEVICE_TESTED 待用户原则 |
| 拒绝/提案边界 | pass | #46/#20 明确 wontfix 理由；#45/#9 明确阶段 S 只预留接口、501、默认关闭，不宣称已实现 |
| 公开操作状态 | pass | 本次验收未执行任何 GitHub issue 回复、关闭或标签操作；D13 边界保持 |

## 5. W4-09 / C16 验收

| 项 | 结论 | 证据与说明 |
|---|---|---|
| core:ledger 纯 JVM 内核 | pass | `core/ledger/README.md` 明确只记账、不支付；Long 分单位、溢出拒绝、ASCII opaque id、四种分摊规则、最大余数法、确定性 tie-break、负金额守恒、两方确认结算状态机 |
| 跨端共享夹具 | pass | `split-fixtures-v1.csv` 覆盖 equal tie、one-fen tie、ratio、selected、stay days、zero、negative、minimum Long、huge weight；README 要求云端必须复用同一夹具逐分一致 |
| 单测覆盖 | pass | exact train `quick :core:ledger` exit 0；XML 汇总 35/35 pass，failures/errors/skipped 均为 0；覆盖金额溢出、分摊守恒/排序/边界、共享夹具、outsider、重复确认幂等、取消/确认终态 |
| C16 完整能力 | **blocked（后续卡）** | 当前只有纯 JVM 记账内核；尚无群组/云端账本、历史记录 UI、SQL 0007、Android 缓存、擦除链路注册或跨端集成证据。不得宣称 C16 完整通过 |

## 6. C25 / C26 观察

| 项 | 结论 | 证据与说明 |
|---|---|---|
| C25 架构与工程护栏 | not-run（exact train） | exact train 早于 W0-07a，`android-full` 日志中没有 dep-check/file-line-gate；后续 latest main 的 dep-check/file-line-gate pass 只是补充观察，不能回填本 stage。巨型文件拆分本身也仍是后续 W0-05/W0-06 工作 |
| C26 自动回归 | pass（exact train 自动部分） | exact train `android-full` exit 0，代表该 tree 的既有 Android 自动回归未破坏；W0-10 回归测试补齐与 DEVICE_TESTED 待用户项仍不属本次通过范围 |
| R0-6 secrets | pass（exact tree 扫描） | exact Android train 与验收时点 Cloud main secrets 均 exit 0、findings=0；能力矩阵要求的“全部历史”终验语义仍需终验阶段单独确认 |

## 7. 失败与风险分诊

| 项 | 状态 | 是否 train/cis-003 回归 | 说明 / 后续 |
|---|---|---:|---|
| 两仓 contracts 漂移 | fail | 否 | INF-02 契约收敛未合入 exact train；contracts gate exit 1，不能算 pass |
| C16 完整能力 | blocked | 否 | W4-09 只完成纯 JVM 内核；群组账本、云端 SQL/API、Android 集成和擦除证据仍需后续卡 |
| C25 拆分/护栏完成度 | blocked | 否 | exact train 不含 W0-07 护栏；后续 main 护栏 pass 不追溯回填，巨型文件拆分也未完成 |
| C26 真机/专项回归 | device-pending-user | 否 | 真机通知、小组件、TalkBack、200% 字号和厂商 ROM 后台限制不计入自动通过 |
| 调研不确定性 | 风险 | 否 | R01 真机/system-image 复测、R02 校园 OCR 评测集、R03 条款人工接受与 Open-Meteo 商业属性、R04 性能实测、R06 擦除缺口修复均未关闭 |

## 8. 交叉复核所需重点

1. 复核 exact train `296c9cb9cf14befd27a94e9486d5f670c13e2e79` 与 main merge `8aadb4d991b93d35b0add22b7dbeb9851ea48e89` tree 相同。
2. 复核本报告第 2 节 6 条命令证据是否均存在且 SHA/exit/duration/logPath 与 `evidence/stage-3.jsonl` 一致。
3. 复核 `quick :core:ledger` 确实是 `:core:ledger:test`，并核对 35/35、5 actionable tasks、`BUILD SUCCESSFUL in 4s`。
4. 复核 exact train 日志中没有 dep-check/file-line-gate，报告未把后续 main 护栏结果冒充 train 证据。
5. 复核 contracts exit 1 仍仅为 INF-02 预存漂移，而非 train/cis-003 新引入回归。

## 9. 总结论

- **train/cis-003 范围验收：pass**。R01–R06 调研产出、W0-09 草稿边界、W4-09 纯 JVM ledger 内核均满足本趟列车交付目标。
- **stage-3 联调整体：blocked**。contracts 仍由 INF-02 阻断；C16/C25/C26 完整能力均不得因本趟列车而宣称完成。
- 本报告未验证真机、真实学校账号、生产部署或发布链路；这些均不属于本阶段范围。
