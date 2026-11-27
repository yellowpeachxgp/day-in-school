# 阶段联调验收 stage-8：train/cis-008（W0-11a 三阶段预约窗口纯语义切片）

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：r-stage7acc（未参与 W0-11a 开发；沿验收链继续执行 stage-8）
> 交叉复核子代理：pending
> 对象：Android train/cis-008 tip `2a4a2c7c8481678326392b900ae0201e59869a6a`
> 能力编号：C26（零回归中的 W0-11a 三阶段预约窗口纯语义切片）
> 总结论：**W0-11a 纯语义切片为 pass；stage-8 不得扩张为 C26 完整能力通过**。C26 整体仍需后续 receiver / PendingIntent / repository strict seam 等接缝收口，并保留 DEVICE_TESTED 待用户项。

## 1. 元信息、exact tree 与执行边界

| 字段 | 值 |
|---|---|
| Android 验收对象 | train/cis-008 merge `2a4a2c7c8481678326392b900ae0201e59869a6a`；tree `3bfc3bd712defd1ed1fe96a192115c90b8540485` |
| Android 对应 main 合并点 | PR#70 merge `d3432d86b106d6216629a583958e10285e93e95e`；tree 同为 `3bfc3bd712defd1ed1fe96a192115c90b8540485` |
| exact tree 核验 | `git diff --quiet 2a4a2c7c8481678326392b900ae0201e59869a6a d3432d86b106d6216629a583958e10285e93e95e` exit 0；train tree 与 main merge tree 完全一致 |
| Cloud main SHA | null（本趟只改 Android；未把云端门禁计入本 stage） |
| 验收分支 | `acc/cis-stage-8-train008` |
| 本报告仓库改动 | 仅 `task-notes/campus/acceptance/stage-8.md` 与 `task-notes/campus/acceptance/evidence/stage-8.jsonl` |
| 证据索引 | `task-notes/campus/acceptance/evidence/stage-8.jsonl` |
| 验证边界 | 开发机联调验收；不标 `DEVICE_TESTED` / `SCHOOL_VERIFIED`；不部署、不发版、不触碰真实学校账号、不改业务代码 |

时点说明：本报告验收的是 train/cis-008 与其对应 main merge 的 exact tree，不是最新 main 的持续健康证明。报告创建时 Android origin/main 已前进到 `b5af93b5faea260382097aea0c2266037006289d`；后续 W0-05b、W0-07b/c、W6 hotfix 等不属于本报告结论范围。验收 PR 基于当前 main 只是为了减少文档冲突。

## 2. 开发机门禁汇总

| 门禁 | 命令（脱敏模板） | SHA | 退出码 | 耗时 | 日志 / 结论 |
|---|---|---|---:|---:|---|
| Android `android-full` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-008 android-full` | `2a4a2c7c8481678326392b900ae0201e59869a6a` | 0 | 47s | `<campus-root>/logs/day-in-school/train/cis-008/2a4a2c7c8481678326392b900ae0201e59869a6a-android-full.log`；pass |
| Android `secrets` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-008 secrets` | `2a4a2c7c8481678326392b900ae0201e59869a6a` | 0 | 3s | `<campus-root>/logs/day-in-school/train/cis-008/2a4a2c7c8481678326392b900ae0201e59869a6a-secrets.log`；pass |
| focused quick | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-008 quick :core:campusservices :core:database` | `2a4a2c7c8481678326392b900ae0201e59869a6a` | 0 | 9s | `<campus-root>/logs/day-in-school/train/cis-008/2a4a2c7c8481678326392b900ae0201e59869a6a-quick.log`；pass |
| cloud / contracts | 未执行 | null | — | — | 本趟仅 Android 纯 JVM 语义切片，不改云端或 contracts；不冒充 pass/fail |

门禁细节：

- `android-full`：第一遍 `BUILD SUCCESSFUL in 34s`，1499 actionable tasks（806 executed、693 from cache）；pure-JVM second pass `BUILD SUCCESSFUL in 4s`，34 actionable tasks。内存峰值低于 10,737,418,240 B 上限。
- `android-full` 末尾架构护栏：`dep-check: 检查模块 26 个，依赖边 85 条，违规 0 条，警告 0 条` / `dep-check: PASSED`；`file-line-gate: 检查文件 409 个，FAILED 0，WARNING 0，INFO 0` / `file-line-gate: PASSED`。
- `secrets`：`SECRET_SCAN_PASS files=607 tracked=620 findings=0 allowlistEntries=60 allowlistUsed=60 warnings=0`。
- focused quick：`BUILD SUCCESSFUL in 7s`，75 actionable tasks；`core:campusservices` XML 汇总 51/51 tests pass、0 failures/errors/skipped；`core:database` XML 汇总 175/175 tests pass、0 failures/errors/skipped。

## 3. W0-11a 纯语义切片逐项验收

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| 改动边界 | pass | diff 仅 3 个文件：`DualEngineLibraryReservation.kt`、新增 `LibraryBookingTripleSchedulePolicy.kt`、新增对应测试；无 app/feature/Room schema/migration/receiver/PendingIntent/repository strict seam 改动 |
| 07:00–21:30 目标时刻窗口 | pass | `WINDOW_START=07:00:00`、`WINDOW_END_EXCLUSIVE=21:30:00`；测试 `plannerAccepts0700And212959ButRejects065959And2130` 证明 07:00:00 与 21:29:59 接受、06:59:59 与 21:30:00 拒绝 |
| PRECHECK/FINAL_CHECK/FIRE 阶段边界 | pass | `derivedStageWindowsHandOffAndFireExpiryIsInclusive` 证明 PRECHECK 为 `[T-30m,T-5m)`、FINAL_CHECK 为 `[T-5m,T)`，相邻边界毫秒唯一归属；FIRE 从 T 开始 |
| `expiresAt` 推导 | pass | `expiresAt=min(fireAt+30min, 当日21:30)`；`expiryIsClampedAtSchoolWindowEndAndCutoffIsInclusive` 证明晚边界目标时刻被 21:30 截断，且沿用 planner 现有 `now > expiresAt` 才过期的语义：FIRE close 为含端点，21:30:00.000 本身是最后可执行毫秒，下一毫秒关闭 |
| 跨时区 / UTC 跨日 | pass | `zoneAwareInstantsCrossUtcDatesWithoutUsingSystemDefaultZone` 证明 Asia/Shanghai 2026-11-03 07:00 对应 UTC `2026-11-02T23:00:00Z`、expiresAt `2026-11-02T23:30:00Z`；America/New_York 2026-11-03 21:00 对应 UTC `2026-11-04T02:00:00Z`、expiresAt `2026-11-04T02:30:00Z`，不使用系统默认时区 |
| CLOUD_HOSTED 不本地执行 | pass（纯语义） | `cloudHostedIsNeverDueForLocalExecutionEvenInsideFireWindow` 证明 CLOUD_HOSTED 在 PRECHECK/FINAL_CHECK/FIRE 窗口内均返回 `CLOUD_HOSTED_NOT_LOCAL`；LOCAL_DEVICE 与 MANUAL_REMINDER 在 FIRE 窗口内仍为 DUE |
| 直接构造越界事实 fail-closed | pass | `gateRejectsDirectlyConstructedFactsOutsideSchoolWindow` 证明即使绕过 planner 直接构造 06:59:59 或 21:30:00 facts，本地门禁也返回 `TARGET_OUTSIDE_SCHOOL_WINDOW` |
| planner 公式等价接线 | pass | `DualEngineReservationPlanner` 复用同一个 `expiresAtEpochMillis` 推导；diff 复核显示正常持久化 epoch 范围内仍为先前 `min(fireAt+clampedLateness, windowEnd)` / 迟到重建分支 `maxOf(clampedLatenessMs,300000)` 公式，helper 增加饱和加法防溢出 |
| 专项测试结果 | pass | `LibraryBookingTripleSchedulePolicyTest` 6/6 pass、0 skipped/failures/errors；6 个 testcase 与上表逐项对应 |
| 现有 CLOUD_HOSTED runtime 边界补充 | pass（上下文，不计入接缝完成） | 同一 focused quick 中 `CloudIdentityPrivacyRegressionTest` 17/17 pass，包含 `scheduled cloud fire only syncs server state and never dispatches execute-now` 与 `cloud fire for foreign-owned plan does not even sync`；这证明当前 runtime 已有防护，但不表示新 policy 已接入全部接缝 |

## 4. 关键边界：纯语义不等于端到端接缝完成

源审计确认：

- `LibraryBookingTripleSchedulePolicy.evaluateLocalStage` 当前只在政策自身与其测试中出现；尚未被 receiver、alarm/PendingIntent、`CampusRepository` 或 strict seam 调用。
- 生产接线仅包含 `DualEngineReservationPlanner` 对 `expiresAtEpochMillis` 的复用，计算公式在正常持久化 epoch 范围保持等价。
- 因此本 stage 只能宣告 **W0-11a 纯语义切片 pass**：窗口、阶段、过期、时区与 CLOUD_HOSTED 本地门禁语义被固定并有测试；不能宣告端到端闹钟链路、崩溃恢复、身份世代/计划代次 strict seam 或完整零回归能力已经完成。

## 5. C26 边界、DEVICE_TESTED 与风险分诊

| 项 | 状态 | 是否 train/cis-008 回归 | 说明 / 后续 |
|---|---|---:|---|
| W0-11a 纯语义切片 | pass | 否 | 本报告声明范围仅限纯 helper、过期推导与 planner 边界测试 |
| 后续接缝 | blocked | 否 | receiver、PendingIntent、repository strict seam、profile/session/plan generation 校验与迟到 alarm 原子门禁仍需后续批次 |
| C26 完整能力 | blocked / device-pending-user | 否 | R0-7、W0-10、Roborazzi/专项回归与真机通知、小组件、厂商 ROM 后台行为未由本切片关闭；DEVICE_TESTED 待用户不计入通过 |
| cloud / contracts | not-run | 否 | 本趟不改云端或 contracts，不冒充 pass 或 fail |
| 21:30 含端点语义 | 风险（已明示） | 否 | 目标时刻 T 严格排除 21:30:00；但 FIRE 沿用 `now > expiresAt` 才过期，21:30:00.000 可作为最后可执行毫秒。报告已明示，避免把目标窗口和执行截止混为一谈 |
| DEVICE_TESTED / SCHOOL_VERIFIED | not-run | 否 | 无真机、真实学校账号、生产部署或发布链路证据 |

## 6. 托管 CI 补充证据（不替代 devbox gate）

- Android PR#70 的 `全模块单元测试、Lint 与 APK 构建` 与 `核对 Cloud 实际 handler 报价与错误契约` 均 success（run `45878318268` / `45878077513`）。
- 这些只是合并记录补充；本报告验收依据仍以 exact train tree 上收集的 devbox gate、XML 测试摘要与源审计为准。

## 7. 交叉复核所需重点

1. 复核 train exact SHA `2a4a2c7c8481678326392b900ae0201e59869a6a`、main merge `d3432d86b106d6216629a583958e10285e93e95e` 与 tree `3bfc3bd712defd1ed1fe96a192115c90b8540485`；`git diff --quiet` 应为 exit 0。
2. 复核 3 个 gate log 是否存在且 SHA/exit/duration 与本报告一致：android-full、secrets、combined quick。
3. 复核 focused XML 汇总：core:campusservices 51/51、core:database 175/175；`LibraryBookingTripleSchedulePolicyTest` 6/6、`CloudIdentityPrivacyRegressionTest` 17/17。
4. 复核 6 个 policy testcase 是否确实覆盖 07:00/21:29:59、06:59:59/21:30、PRECHECK/FINAL/FIRE 边界、expiresAt clamp、跨时区 UTC 跨日与 CLOUD_HOSTED_NOT_LOCAL。
5. 复核报告没有把 `evaluateLocalStage` 未接入接缝的事实掩盖，也没有把 C26 完整能力或 DEVICE_TESTED 写成 pass。
6. 复核 evidence JSONL 中 4 条记录的 outcome、SHA、脱敏命令模板与 `<campus-root>` 日志路径。

## 8. 总结论

- **C26 / W0-11a 纯语义切片：pass**。07:00–21:30 目标窗口、T−30/T−5/T 三阶段半开边界、FIRE 含端点 expiresAt、跨时区 UTC 跨日和 CLOUD_HOSTED_NOT_LOCAL 均有 exact train 上的自动证据。
- **C26 完整能力：blocked / device-pending-user**。后续接缝、完整回归收口与真机 DEVICE_TESTED 不在本切片内，不得因本报告宣称完成。
- 本报告未验证真机、真实学校账号、生产部署或发布链路；这些均不属于 train/cis-008 本阶段范围。
