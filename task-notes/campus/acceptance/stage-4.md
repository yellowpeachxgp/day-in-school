# 阶段联调验收 stage-4：train/cis-004（W1-00 kernel refs + W0-02 实况进度）

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：s8e-inf01review（未参与 W1-00 / W0-02 开发）
> 交叉复核子代理：pending
> 对象：train/cis-004 精确 SHA `40bd3d29ff4c9ede43beffc8b977635531a8ab65`；其 tree 与 PR#65 合入后的 main merge `0e83b780541ca526c450e7ac78e5fbf2ada4c4a6` 完全一致
> 能力编号：C01（W0-02 实况进度）；C04/C05 的 W1-00 shared kernel refs 前置契约切片
> 总结论：**train 声明范围内自动验收 pass；C01 仍有 DEVICE_TESTED 待用户子项，完整 C04/C05 能力仍为 not-run，不得把 kernel refs 微卡误写为完整能力完成**。

## 1. 元信息与执行边界

| 字段 | 值 |
|---|---|
| 验收对象 SHA | `40bd3d29ff4c9ede43beffc8b977635531a8ab65` |
| 对应 main merge SHA | `0e83b780541ca526c450e7ac78e5fbf2ada4c4a6` |
| Android main SHA（验收语义） | `0e83b780541ca526c450e7ac78e5fbf2ada4c4a6` |
| Cloud main SHA | null（本趟只改 Android；未把云端门禁计入本 stage） |
| 本报告创建时的 origin/main | `d3432d86b106d6216629a583958e10285e93e95e`（含后续 PR#70；本报告不回溯宣称其能力） |
| 验收分支 | `acc/cis-stage-4-train004` |
| 本报告仓库改动 | 仅 `task-notes/campus/acceptance/stage-4.md` 与 `task-notes/campus/acceptance/evidence/stage-4.jsonl` |
| 验证边界 | 开发机联调验收；不标 DEVICE_TESTED / SCHOOL_VERIFIED；不部署、不发版、不触碰真实学校账号 |

补充核验：`git diff --quiet origin/train/cis-004 0e83b780541ca526c450e7ac78e5fbf2ada4c4a6` 为空，证明 train exact tree 与对应 main tree 一致；因此 train SHA 上的门禁可代表该趟列车合入后的 main tree。后续合入的 W1-01a、W0-07a、W0-11a 等不属于本 stage 的声明能力。

## 2. 开发机门禁汇总

| 门禁 | 命令（脱敏模板） | SHA | 退出码 | 耗时 | 开发机日志路径 | 结论 |
|---|---|---|---:|---:|---|---|
| focused quick | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-004 quick :core:reminder :core:model` | `40bd3d29ff4c9ede43beffc8b977635531a8ab65` | 0 | 15s | `<campus-root>/logs/day-in-school/train/cis-004/40bd3d29ff4c9ede43beffc8b977635531a8ab65-quick.log` | pass |
| android-full | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-004 android-full` | `40bd3d29ff4c9ede43beffc8b977635531a8ab65` | 0 | 22s | `<campus-root>/logs/day-in-school/train/cis-004/40bd3d29ff4c9ede43beffc8b977635531a8ab65-android-full.log` | pass |
| secrets | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school train/cis-004 secrets` | `40bd3d29ff4c9ede43beffc8b977635531a8ab65` | 0 | 6s | `<campus-root>/logs/day-in-school/train/cis-004/40bd3d29ff4c9ede43beffc8b977635531a8ab65-secrets.log` | pass |
| cloud / contracts | 未执行 | null | — | — | 本趟仅 Android；按主代理本 stage 聚焦 android-full/secrets，不把 contracts 记为 pass 或 fail | not-run |

门禁细节：

- `android-full`：`BUILD SUCCESSFUL in 21s`，1492 actionable tasks（804 executed、688 from cache）；Gradle scope peak 144,969,728 B，低于 10,737,418,240 B 上限。该 train tree 早于 W0-07a，因此日志中不要求出现 dep-check/file-line-gate。
- `secrets`：`SECRET_SCAN_PASS files=574 tracked=587 findings=0 allowlistEntries=59 allowlistUsed=59 warnings=0`。
- focused quick：`BUILD SUCCESSFUL in 13s`，87 actionable tasks；`:core:reminder:testDebugUnitTest` 与 `:core:model:testDebugUnitTest` 均 exit 0。
- 托管 CI 补充证据：对应 merge SHA `0e83b780541ca526c450e7ac78e5fbf2ada4c4a6` 的 GitHub run `45875584954` 中，全模块单元测试/Lint/APK 构建与 Cloud 契约核对均 success。

## 3. C01 实况进度逐项验收

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| 课中进度随时间前进 | pass | `LiveProgressModelTest` 9/9 pass：课中毫秒比例、课前/课中端点、跨下课闭合、零/负时长、零/负 lead；`tick fire republishes notification with advanced in class progress` 证明 tick 后进度前进 |
| 无法调度节拍时不显示进度条 | pass | `LiveCourseTickerTest` 4/4 pass 含 scheduling failure propagation；`tick scheduling failure keeps chronometer notification without progress` 证明失败时只保留 Chronometer，不写 progress |
| 切换身份时取消节拍 | pass | `coordinator deactivation for identity switch cancels live alarms` 通过；闹钟绑定 profileId + session generation，旧身份不复活 |
| 跨下课 / 无候选 / 用户关闭 / revision 或频率变更取消或替换节拍 | pass | Robolectric 集成测试覆盖 crossing class end、user dismiss、event revision change、frequency change；10/10 pass |
| 进程重启后的恢复 | pass | `process restart boundary fire rereads state and restores progress chain` 通过；边界触发后重读状态而非信任旧 extras |
| 模块级测试汇总 | pass | `LiveCourseCoordinatorRobolectricTest` 10/10、`LiveCourseTickerRobolectricTest` 1/1、`LiveCourseTickerTest` 4/4、`LiveProgressModelTest` 9/9、既有 `ReminderSubsystemUnitTest` 3/3，全部 failures=0/errors=0/skipped=0 |
| android-full 与 secrets | pass | 见第 2 节；train exact tree 上两项均 exit 0 |

C01 自动验收结论：**pass（MOCK_TESTED 层级）**。这只证明时间计算、调度、取消、恢复与诚实降级的自动可验证部分。

## 4. W1-00 kernel refs 契约逐项验收

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| 统一类型面 | pass | 源审计确认公开类型为 `EntityType`、`EntityRef`、`PlaceRef`、`SourceRef`、`FieldChangeKind`、`FieldChange`、`FieldDiff`、`ChangeKind`、`Actor`；未新增 `DataProvenance` / `VerificationLevel` / `CapabilityState` / `SchoolAccountScope` 等价类型 |
| scope 与归因必须显式 | pass | `KernelRefsTest` 30/30 pass；覆盖实体引用显式学校/账号作用域、来源不得跨学校/跨账号归因、空白 provider/sourceSystem/sourceRecordId 拒绝 |
| PlaceRef 学校/校区级四段稳定 ID | pass | 测试覆盖四段稳定图 ID、学校/校区前缀矛盾、控制字符与 256/257 字符边界 |
| FieldDiff CanonicalJson 语义 | pass | 测试覆盖规范等值数字不得伪造变更、真实差异、ADDED/MODIFIED 字段值约束、不存在与 JSON null 区分、重复路径拒绝 |
| RFC 6901 字段路径 | pass | 测试覆盖必须以 `/` 开始、根 `/` 空键合法、未转义波浪号拒绝 |
| quick 正向门禁 | pass | focused quick 在 train exact tree 上 exit 0；`KernelRefsTest` failures=0/errors=0/skipped=0 |

kernel refs 微卡契约结论：**pass（仅限 W1-00 shared refs 切片）**。完整 C04「统一时间流」仍需 Timeline/Source 投影、稳定 ID 与 ICS 重复导入验收；完整 C05「变更历史」仍需 events/vault/outbox、差异查看与撤销链路验收。两者在本 stage 均为 **not-run**，不得把本微卡结论扩张为完整 C04/C05 pass。

## 5. DEVICE_TESTED 待用户（不计入通过）

| 能力 | 待用户验证内容 | 当前自动证据 | 用户所需设备 / 系统版本 | 备注 |
|---|---|---|---|---|
| C01 | 真实通知中进度条外观、厂商 ROM 对非精确链式节拍的实际延迟/合并行为、用户手动关闭实况通知后的真实表现 | Robolectric/JVM 调度与进度语义全部 pass | 用户真机；建议覆盖 API 31–36 中至少一个常用系统版本 | 不得由开发机或模拟器证据标 DEVICE_TESTED |

## 6. 失败、缺口与风险分诊

| 能力 / 门禁 | 状态 | 是否阻断 train | 说明 / 后续 |
|---|---|---:|---|
| C01 自动验收 | pass | 否 | 仅真机外观与 OEM 后台行为保留 DEVICE_TESTED 待用户 |
| W1-00 kernel refs 契约 | pass | 否 | 仅代表共享引用类型前置契约，不代表完整 C04/C05 |
| 完整 C04/C05 | not-run | 否 | 依赖 W1-01/W1-02/W1-03 等后续卡；本 stage 不伪造成 pass |
| cloud / contracts | not-run | 否 | 本趟仅 Android；INF-02/契约线另行收敛，不在此冒充通过 |
| 后续 main 变化 | 风险 | 否 | 本报告创建时 origin/main 已含后续 PR#70；stage 结论只约束 train exact tree 与对应 merge tree，不回溯评价后续列车 |
| secrets allowlist 拓扑例外 | 风险 | 否 | 本次 findings=0；topology-pending-user-review 仍按 INF-01 决策待用户复核 |

## 7. 证据索引与复核重点

证据索引：`task-notes/campus/acceptance/evidence/stage-4.jsonl`。索引只记录脱敏命令模板、退出码、SHA、日志路径和测试摘要；原始完整日志保留在 `<campus-root>`。

交叉复核建议重点：

1. 复核 `train/cis-004` SHA 与上述三条 devbox 日志路径、退出码、耗时是否一致。
2. 复核 `git diff --quiet origin/train/cis-004 0e83b780541ca526c450e7ac78e5fbf2ada4c4a6` 的 tree 等价事实。
3. 复核 quick XML 汇总：C01 相关 27/27、KernelRefsTest 30/30 均 failures=0/errors=0/skipped=0。
4. 复核报告没有把完整 C04/C05、DEVICE_TESTED、SCHOOL_VERIFIED、cloud/contracts 冒充为 pass。

## 8. 总结论

- **train/cis-004 声明范围内自动验收：pass**。C01 的自动可验证部分、W1-00 kernel refs 契约切片、android-full 与 secrets 均通过。
- **C01 真机外观与厂商后台行为：device-pending-user**；不计入通过。
- **完整 C04/C05：not-run**；需后续 Timeline、events/vault/outbox、差异查看与撤销链路完成后验收。
- 本报告未验证真机、真实学校账号、生产部署或发布链路。
