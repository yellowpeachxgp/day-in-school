# 阶段联调验收 stage-5：train/cis-005 治理、验收矩阵与 stage-2 报告

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：s9d-pr56verify（未参与 train/cis-005 对应开发）
> 交叉复核子代理：pending
> 对象：Android train/cis-005 tip `6addb682875eeefd1c0cbd151ec2fbc5aff8f828`；Cloud main `5480f0d907bd799e83ab4de7e15f2a1b666e8eec`
> 能力编号：C25（治理、工程与验收工具）、C28（契约一致性边界）
> 总结论：**train/cis-005 自身验收点为 pass；stage-5 联调门禁矩阵为 blocked，不得视为全部通过**。阻断仍是 INF-02 契约漂移，不是本趟列车回归；secrets 与验收工具自测本次已真实通过。

## 1. 元信息与执行边界

| 字段 | 值 |
|---|---|
| Android 验收对象 | train/cis-005 tip `6addb682875eeefd1c0cbd151ec2fbc5aff8f828` |
| Android 对应 main 合并点 | PR#66 merge `028c57a3`；已核对 train tip tree `c723663d2db45f22608f19b84b1a8ad571b2a68d` 与该 merge tree 完全一致 |
| Cloud 验收对象 | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec`（train/cis-005 合入时点 Cloud main） |
| 验收分支 | `acc/cis-stage-5-train005` |
| 本报告仓库改动 | `task-notes/campus/acceptance/stage-5.md` 与 `task-notes/campus/acceptance/evidence/stage-5.jsonl` |
| 证据索引 | `task-notes/campus/acceptance/evidence/stage-5.jsonl` |
| 验证边界 | 开发机联调验收；不标 `DEVICE_TESTED` / `SCHOOL_VERIFIED`；不部署、不发版、不改业务代码 |

时点说明：本报告验收的是 train/cis-005 合入后的时点状态，不是最新 main 的持续健康证明。验收后 Android/main 与 Cloud/main 已继续前进；后到的 W0-07/W1-01a/cloud W0-08/W0-11a/W0-05a 不属于本报告结论范围。报告 PR 基于当前 main 只是为了避免文档冲突。

## 2. 开发机门禁汇总

| 门禁 | 结果 | exit | SHA | 耗时 | 日志 / 说明 |
|---|---|---:|---|---:|---|
| Android `android-full` | pass | 0 | `6addb682875eeefd1c0cbd151ec2fbc5aff8f828` | 119s | `<campus-root>/logs/day-in-school/train/cis-005/6addb682875eeefd1c0cbd151ec2fbc5aff8f828-android-full.log` |
| Cloud `cloud` | pass | 0 | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` | 16s | `<campus-root>/logs/cloud-in-school/main/5480f0d907bd799e83ab4de7e15f2a1b666e8eec-cloud.log` |
| Android `contracts` | **fail（INF-02 缺口）** | 1 | `6addb682875eeefd1c0cbd151ec2fbc5aff8f828` | 3s | `<campus-root>/logs/day-in-school/train/cis-005/6addb682875eeefd1c0cbd151ec2fbc5aff8f828-contracts.log` |
| Android `secrets` | pass | 0 | `6addb682875eeefd1c0cbd151ec2fbc5aff8f828` | 3s | `<campus-root>/logs/day-in-school/train/cis-005/6addb682875eeefd1c0cbd151ec2fbc5aff8f828-secrets.log` |
| Cloud `secrets` | pass | 0 | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec` | 1s | `<campus-root>/logs/cloud-in-school/main/5480f0d907bd799e83ab4de7e15f2a1b666e8eec-secrets.log` |

门禁细节：

- `android-full`：主构建 `BUILD SUCCESSFUL in 1m 39s`，1496 actionable tasks（805 executed、691 from cache）；随后 pure-JVM second pass `BUILD SUCCESSFUL in 18s`，30 actionable tasks。第一遍 `CIS_GRADLE_MEMORY_PEAK_BYTES=3533053952`，低于 10,737,418,240 B 上限。
- `cloud`：204/204 tests pass、0 fail；contract-ci pass（97 个 OpenAPI operations 100% inventory parity、5 个 JSON Schema）。
- `contracts`：Android 本地 contract-ci 自身通过（67 个 operations 100% parity），但两仓 `contracts/endpoints/endpoint-inventory.yaml` 与 `contracts/openapi/cloud-v1.yaml` 不一致；这是 D15/INF-02 已登记的预存漂移，**不算本趟列车回归，也不能算 pass**。
- Android `secrets`：`SECRET_SCAN_PASS files=587 tracked=600 findings=0 allowlistEntries=60 allowlistUsed=60 warnings=0`。
- Cloud `secrets`：`SECRET_SCAN_PASS files=132 tracked=133 findings=0 allowlistEntries=113 allowlistUsed=113 warnings=0`。

## 3. train/cis-005 验收点逐项核验

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| 治理更新 AUTO-013/014/015 已入库 | pass | `DECISIONS.md` 含 3 条记录；AUTO-014 明确包含 `ReminderModels.kt` 1 行配置字段，AUTO-015 明确 bootstrap 门禁边界，均保持「待用户复核」而不是冒充用户批准 |
| TASKBOARD 事实修正 | pass | INF-01 结果 SHA 为 `Android 50201c8c / cloud d38c59e`；stage-2 行写 PR#66；W0-02 与列车代理名为修正后的 `s6b-w002finish` / `s9c-train` |
| stage-2 报告收录且结论适度 | pass | `acceptance/stage-2.md` 存在；明确 INF-01 自身 pass、secrets 真实通过、stage-2 整体 blocked 且 contracts 归 INF-02，没有把 contracts 冒充 pass |
| 验收矩阵静态一致性 | pass | 在 train checkout 执行 `validate-acceptance-matrix.mjs`：35 项校验通过，rc0 |
| 验收工具 selftest | pass | `tools/acceptance/selftest.mjs` rc0；正向 command/GitHub CI/device-note/source-audit/screenshot 样例均 status 0，8 个负向样例均按预期拒绝 |
| 验收证据索引 | pass | 本次新增 `evidence/stage-5.jsonl`，由 `record-evidence.mjs` 写入 6 条记录；命令、logPath 均为 `$CIS_DEVBOX` / `<campus-root>` 脱敏形式 |
| secrets scanner 与正门禁 | pass | 两仓 `tools/secret-scan/run`、`scan.mjs`、`selftest.mjs` 哈希一致；两仓 secrets gate 均 exit 0、findings=0、warnings=0；Android allowlist 60/60、Cloud 113/113 全部使用 |
| secret-scan selftest | pass | train checkout 执行结果 `SECRET_SCAN_SELFTEST_PASS positiveFindings=26 negativeFindings=0`，rc0 |
| 契约一致性边界（C28） | blocked | 本趟列车不改 contracts；两仓漂移仍属 INF-02，contracts gate exit 1 |
| 保密与不泄漏 | pass | secrets gate findings=0；本报告和 evidence JSONL 只使用 `<campus-root>`、`<devbox>`、`$CIS_DEVBOX`，不写真实开发机地址、账号或凭据 |

## 4. C25 / C28 结论边界

- **C25 / train/cis-005 交付点：pass**。治理快照修正、AUTO 决策记录、stage-2 报告、验收矩阵、验收工具 selftest、证据索引和两仓 secrets 正门禁均满足本趟列车范围。
- **C25 整体：不宣称完成**。巨型文件拆分、依赖方向/Roborazzi/文件行数护栏与后续 W0/W1 工作仍属于各自列车验收范围。
- **C28 / contracts 联调：blocked**。Android 与 Cloud 各自 contract-ci 可以通过，不代表两仓 contracts 一致；两仓漂移必须由 INF-02 关闭。
- **stage-5 联调整体：blocked**。不能因 android-full、cloud 与 secrets 全绿而宣称阶段门禁全绿。

## 5. 失败、缺口与风险分诊

| 项 | 状态 | 是否 train/cis-005 回归 | 说明 / 后续 |
|---|---|---:|---|
| 两仓 contracts 漂移 | fail | 否 | INF-02 契约收敛未合入；需契约线修复后重跑 |
| AUTO-013/014/015 用户复核 | 风险 | 否 | 三条均为自主决策且状态仍是待用户复核；stage 验收不替代用户确认 |
| allowlist 拓扑例外 | 风险 | 否 | topology-pending-user-review 仍需用户复核；本次 scanner 证明当前没有未豁免 findings |
| TASKBOARD 时点快照 | 风险 | 否 | 任务板是 02:05 快照，后续状态会变化；需要后续治理更新继续回填，不影响本次事实修正本身 |
| evidence JSONL 覆盖范围 | 风险 | 否 | 本次开始记录 6 条核心证据；更早 stage-1/2 尚无 JSONL 索引，不能回溯宣称已有完整机读证据链 |
| 验收对象时点性 | 风险 | 否 | 本报告是 train/cis-005 时点验收；后续 main 变化需由后续 stage 报告覆盖 |

## 6. 交叉复核所需重点

1. 复核 5 个 gate log 是否存在、SHA 是否与本报告一致；Android 侧注意命令对象是 `train/cis-005`，其 tree 与 PR#66 merge `028c57a3` 完全一致。
2. 复核 Android/Cloud secrets 输出中的 files/tracked/findings/allowlist 数字，以及 scanner 三文件跨仓哈希一致性。
3. 复核 acceptance matrix validation、acceptance selftest 与 secret-scan selftest 输出。
4. 复核 contracts exit 1 是否仍仅限于 `endpoint-inventory.yaml` 与 `openapi/cloud-v1.yaml` 的 INF-02 预存漂移。
5. 复核 `evidence/stage-5.jsonl` 中 6 条记录的 outcome、SHA、命令模板与脱敏 logPath。

## 7. 总结论

- **train/cis-005 自身验收：pass**。治理更新、验收矩阵、stage-2 报告和 secrets/selftest 证据链满足本趟列车验收点。
- **stage-5 联调整体：blocked**。contracts 仍归 INF-02，不得记为本趟列车失败，也不得宣称阶段门禁全绿。
- 本报告未验证真机、真实学校账号、生产部署或发布链路；这些均不属于 train/cis-005 本阶段范围。
