# 阶段联调验收 stage-7：train/cis-007（Android W0-07a 架构护栏 + Cloud W0-08 路由拆分）

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：r-stage7acc（未参与 W0-07a / W0-08 开发）
> 交叉复核子代理：pending
> 对象：Android train/cis-007 tip `cb1d5da34f393939822e1b7741b0d0df4709da6b`；Cloud train/cis-007 tip `9ce1ef56d4ee2a78f2b9efe58fba06a8ea5e6516`
> 能力编号：C25（W0-07a 架构护栏切片）、C28（W0-08 路由/契约对齐切片）
> 总结论：**W0-07a 与 W0-08 两个声明切片均为 pass；stage-7 联调整体为 blocked，不得视为全部通过**。阻断不是本趟列车回归：两仓 contracts 漂移仍归 INF-02，12 个 moments extension 仍只是显式 runtime extension、尚未契约化。

## 1. 元信息、exact tree 与执行边界

| 字段 | 值 |
|---|---|
| Android 验收对象 | train/cis-007 merge `cb1d5da34f393939822e1b7741b0d0df4709da6b`；tree `46e66e14b0a5fe490d48414ad47aa5f4ca13b304` |
| Android 对应 main 合并点 | PR#61 merge `b754cc41ac522c788602ae35c588ced683d6d03b`；tree 同为 `46e66e14b0a5fe490d48414ad47aa5f4ca13b304` |
| Android exact tree 核验 | `git diff --quiet cb1d5da34f393939822e1b7741b0d0df4709da6b b754cc41ac522c788602ae35c588ced683d6d03b` exit 0；train tree 与 main merge tree 完全一致 |
| Cloud 验收对象 | train/cis-007 merge `9ce1ef56d4ee2a78f2b9efe58fba06a8ea5e6516`；tree `3c63057367b42bbe938b78a1c54d972f4dd54375` |
| Cloud 对应 main 合并点 | PR#8 merge `dc990d614e5f3a771ebe122c250362bf67104dd9`；tree 同为 `3c63057367b42bbe938b78a1c54d972f4dd54375` |
| Cloud exact tree 核验 | `git diff --quiet 9ce1ef56d4ee2a78f2b9efe58fba06a8ea5e6516 dc990d614e5f3a771ebe122c250362bf67104dd9` exit 0；train tree 与 main merge tree 完全一致 |
| 验收分支 | `acc/cis-stage-7-train007` |
| 本报告仓库改动 | 仅 `task-notes/campus/acceptance/stage-7.md` 与 `task-notes/campus/acceptance/evidence/stage-7.jsonl` |
| 证据索引 | `task-notes/campus/acceptance/evidence/stage-7.jsonl` |
| 验证边界 | 开发机联调验收；不标 `DEVICE_TESTED` / `SCHOOL_VERIFIED`；不部署、不发版、不触碰真实学校账号、不改业务代码 |

时点说明：本报告验收的是 train/cis-007 与其对应 main merge 的 exact tree，不是最新 main 的持续健康证明。报告创建时 Android origin/main 已前进到 `4fc32078279221e3e338a3e63cb1a66ce656be64`、Cloud origin/main 已前进到 `cddd2e51e553e6fcf933be90edafde532ecc5e1f`；后续 W0-07b/c、W0-05a、W1-02a、W6 hotfix 等不属于本报告结论范围。验收 PR 基于当前 main 只是为了减少文档冲突。

## 2. 开发机门禁汇总

| 门禁 | 命令（脱敏模板） | SHA | 退出码 | 耗时 | 日志 / 结论 |
|---|---|---|---:|---:|---|
| Android `android-full` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-007 android-full` | `cb1d5da34f393939822e1b7741b0d0df4709da6b` | 0 | 23s | `<campus-root>/logs/day-in-school/train/cis-007/cb1d5da34f393939822e1b7741b0d0df4709da6b-android-full.log`；pass |
| Cloud `cloud` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate cloud-in-school train/cis-007 cloud` | `9ce1ef56d4ee2a78f2b9efe58fba06a8ea5e6516` | 0 | 14s | `<campus-root>/logs/cloud-in-school/train/cis-007/9ce1ef56d4ee2a78f2b9efe58fba06a8ea5e6516-cloud.log`；pass |
| Android `secrets` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-007 secrets` | `cb1d5da34f393939822e1b7741b0d0df4709da6b` | 0 | 5s | `<campus-root>/logs/day-in-school/train/cis-007/cb1d5da34f393939822e1b7741b0d0df4709da6b-secrets.log`；pass |
| Cloud `secrets` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate cloud-in-school train/cis-007 secrets` | `9ce1ef56d4ee2a78f2b9efe58fba06a8ea5e6516` | 0 | 1s | `<campus-root>/logs/cloud-in-school/train/cis-007/9ce1ef56d4ee2a78f2b9efe58fba06a8ea5e6516-secrets.log`；pass |
| Android `contracts` | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-007 contracts` | `cb1d5da34f393939822e1b7741b0d0df4709da6b` | 1 | 0s | `<campus-root>/logs/day-in-school/train/cis-007/cb1d5da34f393939822e1b7741b0d0df4709da6b-contracts.log`；**fail（INF-02 缺口）** |

门禁细节：

- `android-full`：第一遍 `BUILD SUCCESSFUL in 11s`，1499 actionable tasks（806 executed、693 from cache）；pure-JVM second pass `BUILD SUCCESSFUL in 3s`，34 actionable tasks。内存峰值低于 10,737,418,240 B 上限。
- `android-full` 末尾架构护栏：`dep-check: 检查模块 26 个，依赖边 85 条，违规 0 条，警告 0 条` / `dep-check: PASSED`；`file-line-gate: 检查文件 407 个，FAILED 0，WARNING 0，INFO 0` / `file-line-gate: PASSED`。
- `cloud`：205/205 tests pass、0 fail、0 skipped；`contract:check` 输出 `[CONTRACT-CI PASS] Verified SYSTEM_CONTRACT.md, 97 OpenAPI 3.1.2 operations (100% inventory parity), and 5 JSON Schema 2020-12 files.`
- Cloud 测试日志中两个关键 parity 子测试均通过：`Contract Parity: core runtime, endpoint inventory, and OpenAPI operationIds are 100% bidirectional` 与 `Contract Parity: moments extension runtime inventory is exact and explicitly pending INF-02 (AUTO-012)`。
- Android `secrets`：`SECRET_SCAN_PASS files=605 tracked=618 findings=0 allowlistEntries=60 allowlistUsed=60 warnings=0`。
- Cloud `secrets`：`SECRET_SCAN_PASS files=147 tracked=148 findings=0 allowlistEntries=113 allowlistUsed=113 warnings=0`。
- `contracts`：Android 本地 contract-ci 自身通过（67 个 operations 100% parity），但两仓 `contracts/endpoints/endpoint-inventory.yaml` 与 `contracts/openapi/cloud-v1.yaml` 不一致，gate exit 1。该 gate 当时比较的是 devbox Cloud main `5480f0d907bd799e83ab4de7e15f2a1b666e8eec`；已额外核验 W0-08 从 `5480f0d` 到 train exact SHA 对 `contracts/` 的 diff 为空，因此该结果同样代表 Cloud train/main exact tree。

## 3. W0-07a / C25 架构护栏切片验收

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| 改动边界 | pass | Android train diff 仅含 `.github/workflows/android-ci.yml` 与 `tools/dep-check/*`、`tools/file-line-gate/*`；无业务代码改动 |
| dep-check 正向门禁 | pass | exact train 上 26 模块、85 依赖边、0 违规、0 警告；`exceptions.txt` 当前为空 |
| file-line-gate 正向门禁 | pass | exact train 上 407 文件、FAILED/WARNING/INFO 均为 0；`baseline.txt` 17 个登记行数经逐一 `wc -l` 复核无 mismatch |
| CI 接入 | pass | `architecture-guardrails` job 独立运行 `tools/dep-check/run` 与 `tools/file-line-gate/run`，并使用 `fetch-depth: 0` 满足棘轮 merge-base 需求；脚本 `run` 文件均为 100755 |
| dep-check 负向证据 | pass（护栏能变红） | 复核既有 devbox 负例：`a3514c65…` feature→feature 依赖违规 exit 1；`cd954ff3…` 逃避解析的跨行/尾逗号依赖与 ui/ 包 Repository/ViewModel import 违规 exit 1 |
| file-line-gate 负向证据 | pass（护栏能变红） | 复核既有 devbox 负例：`d751ac43…` 未登记 2605 行 YAML exit 1；`01394ba2…` 基线数值上升/新增白名单 exit 1；`7551c362…` 删除单条基线 exit 1；`a92fd14c…` 清空基线 exit 1；`eee912d0…` 重复基线 exit 1 |
| 已知边界披露 | pass | README 明示星号 import、全限定名内联引用、Gradle accessor 写法靠审阅把关；未把这些边界冒充为已机检 |
| C25 完整能力 | blocked（后续卡） | W0-07a 只是 dep-check/file-line-gate 切片；巨型文件拆分、Roborazzi 与后续工程能力不能因本切片通过而宣称完整 C25 完成 |

负例日志索引（均为既有 devbox 证据复核，不冒充本验收新跑）：

- `<campus-root>/logs/day-in-school/test/cis-w0-07-negative/a3514c65d2ddf6adc6e19d325c833dd51b999710-android.log`：`feature:today -> feature:timetable` 违规，gate exit 1。
- `<campus-root>/logs/day-in-school/test/cis-w0-07-negative/d751ac43816df86c8da29224e5f2577fc8046491-android.log`：未登记 `negative-fixture.yaml` 2605 行，gate exit 1。
- `<campus-root>/logs/day-in-school/test/cis-w0-07-neg-parsing/cd954ff3319168a94f4737f0030b4ce43466172b-android.log`：依赖解析逃避与 ui/ import 共 4 条违规，gate exit 1。
- `<campus-root>/logs/day-in-school/test/cis-w0-07-neg-ratchet/01394ba2c52ff41b3b2fea2dffbaa6be7f17bb19-android.log`：基线上升与新增白名单，gate exit 1。
- `<campus-root>/logs/day-in-school/test/cis-w0-07-neg-deletion/7551c362022ed7f3b1189b21adecac100ccf4a72-android.log`、`<campus-root>/logs/day-in-school/test/cis-w0-07-neg-delete-all/a92fd14cbf40aa6008941f665da8fc7a083d7086-android.log`、`<campus-root>/logs/day-in-school/test/cis-w0-07-neg-dupe/eee912d0f29087c4f0985b631b2c9dc2232d7aaf-android.log`：删除保护、清空保护、重复条目保护均 exit 1。

复跑边界：exact train 与 main merge 是两个同父同 tree 的平行 merge commit；用“当前最新 origin/main”回灌评估历史 train 的 baseline 棘轮没有阶段意义。现场 positive gate 使用的 devbox main 参照仍处本阶段基线（关键条目与 merge tree 相同），而当前 origin/main 已含后续 W0-07c/W0-05 基线收紧；本报告只声明 exact train/main merge tree 的阶段验收。

## 4. W0-08 / C28 路由拆分切片验收

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| `app.mjs` 行数 | pass | pre-split 5874 行 → exact train 427 行，满足 <600 行要求；427 行版本保留依赖装配、production fail-closed invariant、认证 helper、同步快照 helper 与 ctx 组合根 |
| 路由表结构 | pass | `router.mjs` 编译时要求每项恰好为 `{ method, path, operationId, handler, auth }`，并拒绝重复 method+path/operationId；`routes/index.mjs` 将 system/auth/me/school/reservations/notifications/parcels/plugins/admin/sync/campus-content 组成 core，将 moments 单独组成 extension |
| 行为不变源审计 | pass | 独立静态抽取 pre-split `register/registerExtension` 与 post-split `route` 调用：old_count=109、new_count=109、missing/extra 为空、method+path+auth metadata mismatch=0；107/109 normalized handler source 完全一致；仅 `getReadyz` / `getOpenApiSpec` 因 `isDraining()` 与 `ctx.getCoreRouteCount()` 组合根接线做等价适配 |
| 97 core parity | pass | contract-parity 测试断言并实际通过：inventory=97、OpenAPI=97、runtime core=97、all runtime=109；inventory/OpenAPI/runtime method+path+operationId+auth 100% 双向一致 |
| 12 moments extension 边界 | pass（边界显式） / blocked（契约化） | runtime extension exact 12 条，`MOMENTS_EXTENSION_CONTRACT_VERSION=moments-extension/v0-pending-inf02`；测试证明它们不与 core operationId 重叠、不静默进入 OpenAPI 或 endpoint-inventory。契约化仍未完成，见第 5 节 |
| contracts/ 改动边界 | pass | W0-08 diff 不包含 `contracts/`；从 pre-W0-08 main `5480f0d` 到 train exact SHA 的 `contracts/` diff 为空 |
| reservation fixture 最小扩大 | pass | 按 AUTO-013 仅把 fixture meta 从整文件 hash 改为 canonical route entry + normalized handler source 语义指纹；两个 fixture 的响应 body/status 内容无 diff，只有 `handler_sha256` 值变化 |
| secrets allowlist 改动 | pass | W0-08 只移动 5 条 synthetic-fixture 例外的精确行号；rule、SHA-256 fingerprint、path glob 均未放宽 |
| Cloud 全量测试 | pass | 205/205 tests pass、contract-ci pass；见第 2 节 |
| C28 完整能力 | blocked | 运行中 core 路由与 OpenAPI 已对齐只是 W0-08 切片；两仓 contracts 一致、moments extension 契约化、新端点完整契约要求仍不能宣称完成 |

12 个 moments extension 的 exact 边界为：

1. `GET /api/v1/moments/snapshot` / `getMomentsSnapshot` / public
2. `POST /api/v1/moments/posts` / `createMomentsPost` / public
3. `POST /api/v1/moments/posts/{postId}/comments` / `addMomentsPostComment` / public
4. `POST /api/v1/moments/posts/{postId}/contact-requests` / `createMomentsContactRequest` / bearer
5. `GET /api/v1/moments/posts/{postId}/contact-requests` / `listMomentsContactRequests` / bearer
6. `POST /api/v1/moments/posts/{postId}/contact-requests/{requestId}/action` / `handleMomentsContactRequestAction` / bearer
7. `POST /api/v1/moments/posts/{postId}/action` / `mutateMomentsPost` / public
8. `POST /api/v1/moments/books` / `createMomentsBookListing` / public
9. `POST /api/v1/moments/books/{bookId}/action` / `mutateMomentsBookListing` / public
10. `GET /api/v1/moments/isbn-resolve` / `resolveMomentsIsbn` / public
11. `POST /api/v1/moments/anti-lost-tags` / `upsertAntiLostTag` / public
12. `POST /api/v1/moments/anti-lost-tags/{tagCode}/report-found` / `reportAntiLostTagFound` / public

以上 12 条在 Cloud exact tree 的 `contracts/openapi/cloud-v1.yaml` 与 `contracts/endpoints/endpoint-inventory.yaml` 中均为 0 次 moments 命中；Android exact tree 的 contracts 同样不含 moments。这是显式的 AUTO-012 边界，不是已契约化证明。

## 5. secrets 与 contracts 联调结论

| 项 | 结论 | 证据与说明 |
|---|---|---|
| Android secrets | pass | exit 0，findings=0、warnings=0、allowlist 60/60 全部使用 |
| Cloud secrets | pass | exit 0，findings=0、warnings=0、allowlist 113/113 全部使用 |
| scanner 一致性 | pass | 两仓 `tools/secret-scan/run`、`scan.mjs`、`selftest.mjs` cmp_rc=0 且 sha256 相同；两仓 devbox selftest 均为 `SECRET_SCAN_SELFTEST_PASS positiveFindings=26 negativeFindings=0` |
| 两仓 contracts 一致性 | fail / blocked | Android exact contracts 为 67/67 OpenAPI+inventory operations；Cloud exact contracts 为 97/97。两仓各自可自洽，但 `endpoint-inventory.yaml` 与 `openapi/cloud-v1.yaml` 互相漂移，contracts gate exit 1 |
| INF-02 状态 | blocked | 两仓 `origin/feat/cis-inf-02-contract-convergence` 均未合入当前 origin/main（`merge-base --is-ancestor` rc=1）；复核时 tips 分别为 Android `e69e337ca9939004d834d94d8a7f5cac59221961`、Cloud `4f5ab756df94bca63b3d4ed0da6487d605ad88e8` |
| moments 契约化 | blocked | 12 个 runtime extension 尚未进入 OpenAPI/endpoint-inventory；必须由 INF-02/契约线关闭，不得由 W0-08 越权冒充完成 |

## 6. 托管 CI 补充证据（不替代 devbox gate）

- Android PR#61：GitHub Actions 的 `全模块单元测试、Lint 与 APK 构建`、`架构护栏（依赖方向与文件行数）`、`核对 Cloud 实际 handler 报价与错误契约` 均 success（包括 run `45879008761` / `45879005155`）。
- Cloud PR#8：`Campus Cloud CI & Contract Gate` 的 `verify` success（run `45877756108`）。

这些只是合并记录补充；本报告的验收依据仍以 exact train tree 上收集的 devbox gate、源审计和证据索引为准。

## 7. 失败、缺口与风险分诊

| 项 | 状态 | 是否 train/cis-007 回归 | 说明 / 后续 |
|---|---|---:|---|
| 两仓 contracts 漂移 | fail / blocked | 否 | INF-02 未合入；Android 67 与 Cloud 97 的契约面不一致，必须契约线收敛后重跑 |
| moments extension 契约化 | blocked | 否 | W0-08 已把 12 条显式隔离为 extension inventory，但未契约化；不得算作 C28 完整通过 |
| C25 完整能力 | blocked | 否 | W0-07a 仅护栏切片；巨型文件拆分、Roborazzi 与后续工程收口不在本切片 |
| file-line-gate 历史复跑时点 | 风险 | 否 | exact train 与 main merge 是同 tree 平行 merge；不要用后续 origin/main 的收紧 baseline 回溯判定历史 train |
| dep-check 词法边界 | 风险 | 否 | 星号 import、全限定名内联引用、Gradle accessor 写法仍靠审阅；README 已明示 |
| AUTO-012/013/015 用户复核 | 风险 | 否 | 相关自主决策仍是“待用户复核”；stage 验收不替代用户确认 |
| DEVICE_TESTED / SCHOOL_VERIFIED | not-run | 否 | 本切片无真机或真实学校账号证据；均未宣称 |

## 8. 交叉复核所需重点

1. 复核两仓 train exact SHA、main merge SHA 与 tree SHA：Android tree `46e66e14b0a5fe490d48414ad47aa5f4ca13b304`；Cloud tree `3c63057367b42bbe938b78a1c54d972f4dd54375`；两组 `git diff --quiet` 均应为 exit 0。
2. 复核第 2 节 5 个 gate log 是否存在、SHA/exit/duration 是否与本报告一致，尤其是 `dep-check` 26/85/0 与 `file-line-gate` 407/0/0/0。
3. 复核 Cloud 测试日志中 205/205、两个 contract-parity 子测试与 contract-ci 97 operations 输出。
4. 复核 12 个 moments extension exact 列表：它们必须在 runtime extension inventory 中 exact 存在，同时不得出现在两仓 OpenAPI/endpoint-inventory。
5. 复核负例日志确实显示 dep-check/file-line-gate 各自独立变红；注意这些是既有负例证据复核，不是本验收重新制造的业务改动。
6. 复核 evidence JSONL 中 9 条记录的 outcome、SHA、脱敏命令模板与 `<campus-root>` 日志路径。

## 9. 总结论

- **C25 / W0-07a 切片：pass**。dep-check 与 file-line-gate 已接入 devbox android-full 与托管 CI；正向 exact train gate 通过，既有负例证明两类护栏能独立变红。
- **C28 / W0-08 切片：pass**。`app.mjs` 427 行满足 <600，路由表拆分保持 109 个 operation 的 metadata/handler 等价边界，97 core 三方 parity 与 12 moments extension exact 自检均通过。
- **stage-7 联调整体：blocked**。两仓 contracts 漂移仍归 INF-02，moments extension 仍未契约化；不得把 android-full/cloud/secrets 全绿或两个切片 pass 扩张为 C25/C28 完整能力通过。
- 本报告未验证真机、真实学校账号、生产部署或发布链路；这些均不属于 train/cis-007 本阶段范围。
