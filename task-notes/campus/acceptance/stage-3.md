# 阶段联调验收 stage-3：train/cis-003

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：s10b-accfinish（未参与本趟列车开发）
> 交叉复核子代理：pending
> 对象：train/cis-003 合入后的 Android main `b754cc41ac522c788602ae35c588ced683d6d03b`；Cloud main 仍为 `5480f0d907bd799e83ab4de7e15f2a1b666e8eec`
> 重点能力：C16、C25、C26；范围还包括 R01–R06 调研产出与 W0-09
> 总结论：**train/cis-003 范围内的调研、W0-09 草稿与 W4-09 纯 JVM 内核验收为 pass；stage-3 联调整体为 blocked**。阻断仍是 INF-02 契约漂移；C16/C25/C26 完整能力尚未到可宣称通过的阶段。

## 1. 元信息与执行边界

| 字段 | 值 |
|---|---|
| Android main SHA | `b754cc41ac522c788602ae35c588ced683d6d03b` |
| Cloud main SHA | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` |
| 验收分支 | `acc/cis-stage-3` |
| 本报告唯一仓库改动 | `task-notes/campus/acceptance/stage-3.md` |
| 开发机准备 | 验收前将 Android `<devbox>` bare `main` 以 fast-forward 方式同步到上述 SHA；未改写历史 |
| 验证边界 | 开发机联调验收；不标 `DEVICE_TESTED` / `SCHOOL_VERIFIED`；不部署、不发版 |

列车相关合并：W0-09 PR#47、R05 PR#49、R06 PR#50、R03 PR#52、R02 PR#54、R04 PR#55、R01 PR#60、W4-09 PR#63。验收时 main 还继续合入了 stage/INF/W0-02/W1-00/W1-01a/W0-07 等后续 PR；本报告按 train/cis-003 重点逐项核验，同时以当前 main 做整体门禁。

## 2. 开发机门禁汇总

| 门禁 | 结果 | exit | SHA | 耗时 | 日志 / 说明 |
|---|---|---:|---|---:|---|
| Android `android-full` | pass | 0 | `b754cc41ac522c788602ae35c588ced683d6d03b` | 25s | `<campus-root>/logs/day-in-school/main/b754cc41ac522c788602ae35c588ced683d6d03b-android-full.log` |
| Android `secrets` | pass | 0 | `b754cc41ac522c788602ae35c588ced683d6d03b` | 6s | `<campus-root>/logs/day-in-school/main/b754cc41ac522c788602ae35c588ced683d6d03b-secrets.log` |
| Cloud `cloud` | pass | 0 | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` | 12s | `<campus-root>/logs/cloud-in-school/main/5480f0d907bd799e83ab4de7e15f2a1b666e8eec-cloud.log` |
| Cloud `secrets` | pass | 0 | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` | 1s | `<campus-root>/logs/cloud-in-school/main/5480f0d907bd799e83ab4de7e15f2a1b666e8eec-secrets.log` |
| Android `contracts` | **fail（INF-02 缺口）** | 1 | `b754cc41ac522c788602ae35c588ced683d6d03b` | 1s | `<campus-root>/logs/day-in-school/main/b754cc41ac522c788602ae35c588ced683d6d03b-contracts.log` |
| Android `quick :core:ledger` | pass | 0 | `b754cc41ac522c788602ae35c588ced683d6d03b` | 5s | `<campus-root>/logs/day-in-school/main/b754cc41ac522c788602ae35c588ced683d6d03b-quick.log` |

门禁细节：

- `android-full`：`BUILD SUCCESSFUL in 17s`，1496 actionable tasks（808 executed、688 from cache）；dep-check PASSED（26 模块、85 边、0 违规）；file-line-gate PASSED（407 文件、FAILED 0、WARNING 0、INFO 0）。
- Android `secrets`：`SECRET_SCAN_PASS files=605 tracked=618 findings=0 allowlistEntries=60 allowlistUsed=60 warnings=0`。
- `cloud`：204/204 tests pass；contract-ci pass（97 个 OpenAPI operations 100% inventory parity、5 个 JSON Schema）。
- Cloud `secrets`：`SECRET_SCAN_PASS files=132 tracked=133 findings=0 allowlistEntries=113 allowlistUsed=113 warnings=0`。
- `contracts`：Android 本地 contract-ci 自身通过（67 个 operations 100% parity），但两仓 `endpoint-inventory.yaml` 与 `openapi/cloud-v1.yaml` 仍漂移；归因 INF-02，不能算 pass。
- `quick :core:ledger`：`BUILD SUCCESSFUL in 4s`，`:core:ledger:test` 通过。

## 3. R01–R06 调研产出验收

| 调研 | 结论 | 证据与验收意见 |
|---|---|---|
| R01 FTS 中文分词 | pass（调研交付） | `task-notes/campus/research/R01.md` 存在；推荐写入时 Unicode 规范化 + 中文重叠二元切分 + FTS4 unicode61 + external-content，明确 external FTS 空间估算、W1-03 顺序与测试清单；未把 host/probe 证据冒充真机证据 |
| R02 端侧 OCR | pass（调研交付） | `R02.md` 推荐 PP-OCRv6_tiny ONNX + onnxruntime-android，同时明确排除未验证 16 KB 对齐的 QuickBird OpenCV、分析 ABI/包体风险；结果保持 `UNVERIFIED`，符合保守隐私边界 |
| R03 天气数据源 | pass（调研交付） | `R03.md` 推荐 MET Norway 为默认免 Key，同时写清 3 小时缓存限制、中国准确率未知、AI agent 条款需人类接受；Open-Meteo 非商业属性未决，BYOK 候选边界明确 |
| R04 选课求解 | pass（调研交付） | `R04.md` 覆盖 CSP、传播、分支定界、QuickXplain、50/100/200 合成基准与 50 候选 <2s 测量设计；明确稀疏多解风险和 honest timeout，不宣称性能已验证 |
| R05 拆分映射 | pass（调研交付） | `R05.md` 给出 CampusRepository 164/164 方法映射、公开属性/Flow/internal seam、巨型 Screen 拆分清单和高风险不变量；可供 W0-05/W0-06 使用 |
| R06 Room 擦除 | pass（调研交付） | `R06.md` 给出 v10 表清单、迁移链、擦除顺序、未清表风险、推荐清零项和新表接入标准；静态证据等级诚实 |

共同检查：6 份调研都在 `task-notes/campus/research/` 中；未发现把 `DEVICE_TESTED` 或 `SCHOOL_VERIFIED` 冒充已完成的措辞；调研输出可作为后续任务卡输入，但不等于对应功能已实现。

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
| 单测覆盖 | pass | 测试覆盖金额溢出、分摊守恒/排序/边界、共享夹具、outsider、重复确认幂等、取消/确认终态；`quick :core:ledger` 在 merge main 上 exit 0 |
| C16 完整能力 | **blocked（后续卡）** | 当前只有纯 JVM 记账内核；尚无群组/云端账本、历史记录 UI、SQL 0007、Android 缓存、擦除链路注册或跨端集成证据。不得宣称 C16 完整通过 |

## 6. C25 / C26 观察

| 项 | 结论 | 证据与说明 |
|---|---|---|
| C25 架构与工程护栏 | pass（护栏部分） | dep-check PASSED、file-line-gate PASSED；W0-07 已在 main 生效。巨型文件拆分本身仍是后续 W0-05/W0-06 工作，不能因门禁存在就宣称 C25 全部完成 |
| C26 自动回归 | pass（自动部分） | 当前 `android-full` 全量回归 exit 0，代表既有 Android 自动回归未破坏；W0-10 回归测试补齐与 DEVICE_TESTED 待用户项仍不属本次通过范围 |
| R0-6 secrets | pass（当前 tree 扫描） | Android 与 Cloud secrets 均 exit 0、findings=0；能力矩阵要求的“全部历史”终验语义仍需终验阶段单独确认 |

## 7. 失败与风险分诊

| 项 | 状态 | 是否 train/cis-003 回归 | 说明 / 后续 |
|---|---|---:|---|
| 两仓 contracts 漂移 | fail | 否 | INF-02 契约收敛未合入；contracts gate exit 1，不能算 pass |
| C16 完整能力 | blocked | 否 | W4-09 只完成纯 JVM 内核；群组账本、云端 SQL/API、Android 集成和擦除证据仍需后续卡 |
| C25 拆分完成度 | blocked | 否 | 护栏已生效，但巨型文件拆分未完成；file-line-gate 的压力应继续驱动后续卡 |
| C26 真机/专项回归 | device-pending-user | 否 | 真机通知、小组件、TalkBack、200% 字号和厂商 ROM 后台限制不计入自动通过 |
| 调研不确定性 | 风险 | 否 | R01 真机/system-image 复测、R02 校园 OCR 评测集、R03 条款人工接受与 Open-Meteo 商业属性、R04 性能实测、R06 擦除缺口修复均未关闭 |

## 8. 交叉复核所需重点

1. 复核 6 个新 main gate log 是否存在且 SHA 与本报告一致。
2. 复核 `quick :core:ledger` 与共享夹具是否确实在当前 main 通过。
3. 复核 R01–R06 是否均只是调研输入，未把任何后续能力标为已实现。
4. 复核 contracts exit 1 是否仍仅为 INF-02 预存漂移，而非 train/cis-003 新引入回归。

## 9. 总结论

- **train/cis-003 范围验收：pass**。R01–R06 调研产出、W0-09 草稿边界、W4-09 纯 JVM ledger 内核均满足本趟列车交付目标。
- **stage-3 联调整体：blocked**。contracts 仍由 INF-02 阻断；C16/C25/C26 完整能力均不得因本趟列车而宣称完成。
- 本报告未验证真机、真实学校账号、生产部署或发布链路；这些均不属于本阶段范围。
