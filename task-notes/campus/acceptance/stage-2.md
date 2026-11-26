# 阶段联调验收 stage-2：INF-01 治理基线

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：s10b-accfinish（未参与 INF-01 开发）
> 交叉复核子代理：pending
> 对象：Android PR#57 merge `04d4424b164ddf0044bea45d491c526849816355`；Cloud PR#7 merge `5480f0d907bd799e83ab4de7e15f2a1b666e8eec`
> 能力编号：C25（治理与工程基线）、C28（契约一致性相关边界）
> 总结论：**INF-01 自身验收点为 pass；stage-2 联调门禁矩阵为 blocked，不得视为全部通过**。阻断仍是 INF-02 契约漂移，不是 INF-01 回归；secrets 本次已真实通过。

## 1. 元信息与执行边界

| 字段 | 值 |
|---|---|
| Android main SHA | `04d4424b164ddf0044bea45d491c526849816355` |
| Cloud main SHA | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` |
| 验收分支 | `acc/cis-stage-2-inf01` |
| 本报告唯一仓库改动 | `task-notes/campus/acceptance/stage-2.md` |
| 开发机准备 | 验收前将两仓 `<devbox>` bare `main` 以 fast-forward 方式同步到上述 origin/main SHA；未改写历史 |
| 验证边界 | 开发机联调验收；不标 `DEVICE_TESTED` / `SCHOOL_VERIFIED`；不部署、不发版 |

## 2. 新 main 门禁汇总

| 门禁 | 结果 | exit | SHA | 耗时 | 日志 / 说明 |
|---|---|---:|---|---:|---|
| Android `android-full` | pass | 0 | `04d4424b164ddf0044bea45d491c526849816355` | 66s | `<campus-root>/logs/day-in-school/main/04d4424b164ddf0044bea45d491c526849816355-android-full.log` |
| Cloud `cloud` | pass | 0 | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` | 22s | `<campus-root>/logs/cloud-in-school/main/5480f0d907bd799e83ab4de7e15f2a1b666e8eec-cloud.log` |
| Android `contracts` | **fail（INF-02 缺口）** | 1 | `04d4424b164ddf0044bea45d491c526849816355` | 3s | `<campus-root>/logs/day-in-school/main/04d4424b164ddf0044bea45d491c526849816355-contracts.log` |
| Android `secrets` | pass | 0 | `04d4424b164ddf0044bea45d491c526849816355` | 3s | `<campus-root>/logs/day-in-school/main/04d4424b164ddf0044bea45d491c526849816355-secrets.log` |
| Cloud `secrets` | pass | 0 | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` | 1s | `<campus-root>/logs/cloud-in-school/main/5480f0d907bd799e83ab4de7e15f2a1b666e8eec-secrets.log` |

门禁细节：

- `android-full`：`BUILD SUCCESSFUL in 1m 3s`，1475 actionable tasks（795 executed、680 from cache）；Gradle scope peak 132,734,976 B。
- `cloud`：204/204 tests pass；contract-ci pass（97 个 OpenAPI operations 100% inventory parity、5 个 JSON Schema）。
- `contracts`：Android 本地 contract-ci 自身通过（67 个 operations 100% parity），但两仓 `contracts/endpoints/endpoint-inventory.yaml` 与 `contracts/openapi/cloud-v1.yaml` 不一致；这是 D15/INF-02 已登记的预存漂移，**不算 INF-01 回归，也不能算 pass**。
- Android `secrets`：`SECRET_SCAN_PASS files=546 tracked=559 findings=0 allowlistEntries=59 allowlistUsed=59 warnings=0`。
- Cloud `secrets`：`SECRET_SCAN_PASS files=132 tracked=133 findings=0 allowlistEntries=113 allowlistUsed=113 warnings=0`。

## 3. C25 / C28 验收点逐项核验

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| 权威 roadmap 入库 | pass | `docs/roadmap/MASTER_PLAN.md` 与 `docs/roadmap/AGENT_TASK_CARDS.md` 在 Android merge main 存在 |
| 两仓 AGENTS 协作守则 | pass | Android `AGENTS.md` 存在并覆盖 D1–D16、§12、门禁与热点文件；Cloud `AGENTS.md` 在 merge SHA 存在 |
| 任务板结构 | pass（快照待更新） | `task-notes/campus/TASKBOARD.md` 存在且有 10 行槽位与全卡表；但内容是 INF-01 合并前快照，见风险表 |
| 决策记录 | pass | `DECISIONS.md` 含 D1–D16 与 AUTO-001..AUTO-012；包括 AUTO-006/007/008/011/012 等关键边界 |
| DEVBOX 与 baseline | pass | `DEVBOX.md` 使用 `<devbox>` / `<campus-root>`；`BASELINE.md` 记录两仓 bootstrap origin/main SHA、fsck/ref 检查和 INF-00 基线日志路径 |
| secret scanner 文件与两仓一致性 | pass | `tools/secret-scan/run`、`scan.mjs`、`selftest.mjs` 在两仓 merge SHA 哈希一致；allowlist 分仓维护 |
| secrets 正门禁 | pass | 两仓均 exit 0 且 findings=0、warnings=0；allowlist entries/used 分别为 59/59 与 113/113 |
| scanner 运行时敏感字面量边界 | pass | README 与实现要求真实值只能由运行时环境/参数传入，仓库不内置真实 devbox 账号/地址/campus 根路径；命中只输出指纹 |
| secret-scan selftest | pass | 验收代理在两仓 merged-main build checkout 独立执行 `node tools/secret-scan/selftest.mjs`，均 exit 0：`SECRET_SCAN_SELFTEST_PASS positiveFindings=26 negativeFindings=0` |
| 契约一致性边界（C28） | blocked | 本 INF-01 不改 contracts；两仓漂移已由 D15/INF-02 立项，当前 contracts gate exit 1 |
| 保密与不泄漏 | pass | scanner 正门禁 findings=0；本报告引用路径全部使用 `<campus-root>` 或 `<devbox>` |

## 4. 失败、缺口与风险分诊

| 项 | 状态 | 是否 INF-01 回归 | 说明 / 后续 |
|---|---|---:|---|
| 两仓 contracts 漂移 | fail | 否 | INF-02 契约收敛未合入；需契约线修复后重跑 |
| 任务板时间快照 | 风险 | 否 | 合入的 `TASKBOARD.md` 是 PR 创建后/合并前状态，仍显示 INF-01 等待审阅；需后续任务板 PR 回填合并状态，不影响本卡结构性交付 |
| AUTO-* 决策 | 风险 | 否 | AUTO-001..012 均标待用户复核；stage 验收不替代用户决策确认 |
| allowlist 拓扑例外 | 风险 | 否 | topology-pending-user-review 类例外仍需用户复核；本次 scanner 结果证明当前未产生未豁免 findings |
| selftest 不自动嵌入 cis-gate | 风险 | 否 | secrets gate 执行 scan；selftest 已由本验收代理独立执行通过，可作为单独证据 |

## 5. 交叉复核所需重点

1. 复核上表 5 个新 main gate log 是否存在且 SHA 与本报告一致。
2. 复核 Android/Cloud secrets 输出中的 files/tracked/findings/allowlist 数字。
3. 复核两仓 `tools/secret-scan/run`、`scan.mjs`、`selftest.mjs` 哈希一致性，以及 selftest 输出 `positiveFindings=26 negativeFindings=0`。
4. 复核 contracts exit 1 是否仍仅限于 `endpoint-inventory.yaml` 与 `openapi/cloud-v1.yaml` 的 INF-02 预存漂移。

## 6. 总结论

- **C25 / INF-01 实现验收：pass**。治理文件、任务板、决策记录、DEVBOX/BASELINE、scanner、allowlist、selftest 与两仓 secrets 正门禁均满足本阶段要求。
- **C28 / contracts 联调：blocked**。漂移归属 INF-02；不得记为 INF-01 回归，也不得宣称 stage-2 门禁全绿。
- 本报告未验证真机、真实学校账号、生产部署或发布链路；这些均不属于 INF-01 本阶段范围。
