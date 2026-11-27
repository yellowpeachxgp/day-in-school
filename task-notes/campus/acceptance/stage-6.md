# 阶段联调验收 stage-6：train/cis-006 / W1-01a events core

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：s6c-w102aprep（未参与 W1-01a 开发）
> 交叉复核子代理：pending
> 对象：Android train/cis-006 tip `18000ef7225a0a6ff4144ae529a445925d317dbc`；Cloud main（同一时点）`5480f0d907bd799e83ab4de7e15f2a1b666e8eec`
> 能力编号：C05（仅 events core 前置协议部分）；联调边界涉及 C25/C28
> 总结论：**W1-01a events core 本趟范围验收为 pass；stage-6 联调整体为 blocked**。blocked 仅因 INF-02 两仓 contracts 预存漂移；**完整 C05 变更历史能力仍为 not-run，不得由本报告宣称通过**。

## 1. 元信息与执行边界

| 字段 | 值 |
|---|---|
| Android 验收对象 | train/cis-006 tip `18000ef7225a0a6ff4144ae529a445925d317dbc` |
| 对应 main 合并点 | PR#64 merge `e27732c87be1822ab2f181a41a3ca8c012839f80` |
| tree 一致性 | train tip tree 与 PR#64 merge tree 均为 `48f52697fd779b2314be072b8d7c251ba881365b`，逐 tree 相同 |
| Cloud 验收对象 | `5480f0d907bd799e83ab4de7e15f2a1b666e8eec`（train/cis-006 合入时点 Cloud main） |
| 验收分支 | `acc/cis-stage-6-train006` |
| 报告基线 | 本 PR 基于后续 main `338e5c32fe6b545238f60898640833b420dc809e` 仅为避免验收文档冲突；后续 W0/W6/治理合入不属于本报告结论范围 |
| 本报告仓库改动 | `task-notes/campus/acceptance/stage-6.md` 与 `task-notes/campus/acceptance/evidence/stage-6.jsonl` |
| 证据索引 | `task-notes/campus/acceptance/evidence/stage-6.jsonl`（5 条） |
| 验证边界 | 开发机联调验收；不标 `DEVICE_TESTED` / `SCHOOL_VERIFIED`；不部署、不发版、不改业务代码 |

时点说明：本报告验收 train/cis-006 合入时点状态，不是最新 main 的持续健康证明。验收后 Android/main 与 Cloud/main 已继续前进；后到的 W0-07b/c、W0-11a、W6、治理更新等均不属本次 W1-01a 验收范围。

## 2. 开发机门禁汇总

| 门禁 | 结果 | exit | SHA | 耗时 | 日志 / 说明 |
|---|---|---:|---|---:|---|
| Android `quick :core:events` | pass | 0 | `18000ef7225a0a6ff4144ae529a445925d317dbc` | 6s | `<campus-root>/logs/day-in-school/train/cis-006/18000ef7225a0a6ff4144ae529a445925d317dbc-quick.log` |
| Android `android-full` | pass | 0 | `18000ef7225a0a6ff4144ae529a445925d317dbc` | 41s | `<campus-root>/logs/day-in-school/train/cis-006/18000ef7225a0a6ff4144ae529a445925d317dbc-android-full.log` |
| Android `secrets` | pass | 0 | `18000ef7225a0a6ff4144ae529a445925d317dbc` | 5s | `<campus-root>/logs/day-in-school/train/cis-006/18000ef7225a0a6ff4144ae529a445925d317dbc-secrets.log` |
| Android `contracts` | **fail（INF-02 缺口）** | 1 | `18000ef7225a0a6ff4144ae529a445925d317dbc` | 0s | `<campus-root>/logs/day-in-school/train/cis-006/18000ef7225a0a6ff4144ae529a445925d317dbc-contracts.log` |

门禁细节：

- `quick :core:events`：`BUILD SUCCESSFUL in 5s`，4 actionable tasks；Gradle scope peak 96,550,912 B。pure-JVM 任务实际选择为 `:core:events:test`。
- `android-full`：主构建 `BUILD SUCCESSFUL in 35s`，1499 actionable tasks（806 executed、693 from cache），包含 `:app:assembleRelease` 与 `:app:lintRelease`；随后 pure-JVM second pass `BUILD SUCCESSFUL in 3s`，34 actionable tasks，其中明确执行 `:core:events:test`。第一遍 `CIS_GRADLE_MEMORY_PEAK_BYTES=3,327,787,008`，低于 10,737,418,240 B 上限。
- Android `secrets`：`SECRET_SCAN_PASS files=598 tracked=611 findings=0 allowlistEntries=60 allowlistUsed=60 warnings=0`。
- `contracts`：Android 本地 contract-ci 自身通过（67 个 OpenAPI operations 100% parity、5 个 JSON Schema），但两仓 `contracts/endpoints/endpoint-inventory.yaml` 与 `contracts/openapi/cloud-v1.yaml` 仍漂移；归因 INF-02，不能算 pass。
- 本趟没有 Cloud 代码变更；Cloud SHA 仅用于 contracts 对照和时点记录。未把既有 Cloud 门禁结果转写为本趟新增证据。

## 3. W1-01a events core 逐项验收

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| 纯 JVM 模块边界 | pass | `core/events/build.gradle.kts` 使用 kotlin-jvm，仅依赖 coroutines core 与 JUnit/coroutines test；不依赖 Android、Room 或 core/model 以外新类型；`settings.gradle.kts` 只追加注册 |
| 幂等 publish | pass | `publishIsIdempotentByEventIdAndRejectsConflictingPayload`：同一 eventId 完全相同 envelope 重发后 unackedCount 仍为 1；payload 不同的同 ID 抛 `EventIdConflictException` |
| ack 幂等 | pass | `duplicateAndConcurrentAckAreIdempotent`：32 个并发 ack 只有 1 个返回 true、31 个 false，重复 ack 后 unackedCount 保持 0；未知 eventId 会失败而不是静默成功 |
| backlog 至少一次投递 | pass | `unackedBacklogIsDeliveredAtLeastOnceUntilAck`：同一 group 未 ack 时两次冷订阅都收到同一事件；ack 后 100ms 内无再投递 |
| backlog → live 顺序与 group 隔离 | pass | `consumerGroupsReceiveBacklogThenLiveIndependently`：group-a 先收 backlog 再收 live；group-b 作为独立组仍能收到完整 backlog |
| 重建续投 | pass | `rebuildingBusWithSameOutboxRedeliversUnackedEvents`：用同一 outbox 重建 bus 后，未 ack 事件仍再次投递；这只证明同实例内存续投，不证明进程持久化 |
| 订阅过滤 | pass | `subscriptionFilterAppliesToBacklogAndLiveEvents` 覆盖 filter 只接受目标 eventType；过滤不删除事件本身 |
| CanonicalJson 对象/字符串 | pass | `CanonicalJsonTest` 覆盖对象键排序、空白移除、引号/反斜线/Unicode surrogate/control 转义、重复键拒绝 |
| CanonicalJson 数字规范化 | pass | 测试覆盖 `1e1→10`、`1.20→1.2`、`1e-3→0.001`、`1e-7→0.0000001`、负零归一为 `0`、超长 plain decimal 科学计数 `1E+130`、非法前导零与尾随内容拒绝 |
| envelope wire format | pass | `EventEnvelopeCodecTest` 验证确定性文本、metadata 键排序、payload canonical 化和 decode round-trip；额外顶层字段被拒绝 |
| outbox/生产集成 | **not-run** | 本趟仅有内存协议与内存实现；没有 Room v12、业务同事务 outbox、DomainEvent sealed 类型、生产 EntityWriter、进程重启持久化或消费者竞争分配 |

## 4. C05 结论边界（强制诚实声明）

- **W1-01a events core：pass**。本趟实现了 CanonicalJson、通用 envelope/codec、内存 outbox、内存 EventBus，以及幂等、backlog/live、ack、重建续投的协议语义。
- **完整 C05：not-run**。C05 在矩阵中的含义是“同步、用户、自动化、恢复四类写入都有记录；可查看前后差异并撤销”。本趟没有业务写入审计、前后差异、撤销、Room v12 journal/outbox 表、Vault UI、跨 Profile 擦除或恢复链路证据。
- 因此，本报告中的 C05 证据只能视为 W1-01a 前置协议的部分输入，**不得累计或宣传为完整 C05 pass**。
- 内存实现不证明进程崩溃后的持久性；“重建续投”仅指同一内存 outbox 实例下重建 bus 对象。

## 5. 失败、缺口与风险分诊

| 项 | 状态 | 是否 W1-01a 回归 | 说明 / 后续 |
|---|---|---:|---|
| 两仓 contracts 漂移 | fail | 否 | INF-02 未合入；contracts gate exit 1，stage-6 联调整体 blocked |
| 完整 C05 | not-run | 否 | 需 W1-01b/W1-03/Vault/Room v12 与业务写入接入后另验 |
| 进程持久化 | not-run | 否 | InMemoryEventOutbox 仅内存；进程死亡后状态不保留 |
| consumer group 竞争消费 | not-run | 否 | 当前语义是 group 维度 backlog/ack，不是同组多消费者负载分配 |
| Android 后续 main 健康 | not-run | 否 | 本报告是 train/cis-006 时点验收；后续 main 由后续 stage 覆盖 |
| 真机/真实学校/生产部署 | not-run | 否 | 不属于 W1-01a；不得标 `DEVICE_TESTED` 或 `SCHOOL_VERIFIED` |

## 6. 证据索引说明

`task-notes/campus/acceptance/evidence/stage-6.jsonl` 共 5 条：

1. C05 / unit-test-report / pass：`quick :core:events`。
2. C05 / devbox-command-log / pass：`android-full`。
3. C25 / devbox-command-log / pass：Android `secrets`。
4. C28 / contract-parity-report / fail：`contracts` 因 INF-02 漂移失败。
5. C05 / unit-test-report / not-run：显式登记完整 C05 变更历史能力未验收，防止把 W1-01a 误读为完整 C05 通过。

## 7. 交叉复核所需重点

1. 复核 4 个 gate log 是否存在、SHA 是否为 `18000ef7225a0a6ff4144ae529a445925d317dbc`，以及 quick/android-full 中 `:core:events:test` 证据。
2. 复核 train tip tree 与 PR#64 merge tree 是否均为 `48f52697fd779b2314be072b8d7c251ba881365b`。
3. 复核 InMemoryEventBusTest 中幂等、backlog/live、重建续投、并发 ack 测试名与本报告一致；复核 CanonicalJson/Codec 测试名与数字规范化预期。
4. 复核 Android secrets 输出 `files=598 tracked=611 findings=0 allowlistEntries=60 allowlistUsed=60 warnings=0`。
5. 复核 contracts exit 1 是否仍仅为 `endpoint-inventory.yaml` 与 `openapi/cloud-v1.yaml` 的 INF-02 预存漂移。
6. 复核 stage-6.jsonl 的 5 条 outcome；特别确认完整 C05 记录为 not-run 而非 pass。

## 8. 总结论

- **train/cis-006 / W1-01a events core：pass**。事件核心协议、内存实现、幂等与投递语义、CanonicalJson 和 envelope wire format 满足本趟验收目标。
- **stage-6 联调整体：blocked**。contracts 仍归 INF-02，不能记为本趟回归，也不能宣称阶段门禁全绿。
- **完整 C05：not-run**。本报告明确不覆盖变更历史、撤销、Room/Vault 持久化和业务写入集成。
