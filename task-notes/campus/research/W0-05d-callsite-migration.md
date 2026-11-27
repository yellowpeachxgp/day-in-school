# W0-05-d CampusRepository 调用方迁移路径与 facade 收口评估

> 基线：c8 合入后的 `origin/main`（4c12c041）。本批分支只迁移第一个内聚调用方簇 `feature/grades`，不全仓改写。
> 统计口径：精确词法引用 `CampusRepository`（排除 `CampusRepositoryPrefKeys` / `CampusRepositoryEnvironment` 等同前缀符号）；它是迁移工作量信号，不等同于唯一运行期调用次数。

## 1. c8 后全仓调用点盘点（迁移前）

| 模块 | 生产文件数 | 精确引用行 | 类型/注入引用 | 直接使用的 distinct facade 成员 | 备注 |
|---|---:|---:|---:|---:|---|
| app | 2 | 5 | 3 | 22 | MainActivity 组合根 + Onboarding 同步/偏好宽入口 |
| core/model | 1 | 1 | 0 | 0 | 注释/契约引用，非注入热点 |
| core/reminder | 2 | 4 | 2 | 17 | Reminder/Focus 单例；含会话守卫、擦除 hook、DAO escape hatch |
| feature/grades | 3 | 7 | 4 | 13 | Route/ViewModel 已清晰，最内聚 |
| feature/importer | 1 | 2 | 1 | 15 | 教务同步、导入 diff、Term/Timetable 宽入口 |
| feature/map | 2 | 4 | 2 | 11 | 以只读 Flow 和少量偏好/楼宇写作为主 |
| feature/settings | 37 | 89 | 51 | 125 | 最大簇；应再按 leaf ViewModel 领域分批 |
| feature/timetable | 1 | 12 | 1 | 24 | 依赖 7 个 facade nested RestoreCommand 类型 |
| feature/today | 1 | 20 | 3 | 38 | 最宽 UI 簇；依赖同一组 nested RestoreCommand 类型 |

迁移前全仓精确引用：93 个 Kotlin 文件、428 行；其中 `core/database` 生产 78 行、测试 203 行。生产层仓库外热点合计 144 行。
本批迁移后：90 个文件、421 行；`feature/grades` 生产代码精确引用降为 **0**。`core/database` 测试仍是最大兼容面，需最后迁移或随 facade 退役删除。

## 2. 任务卡与 R05 终态约束

- W0-05 验收原文：`CampusRepository.kt < 800 行`，新文件均 `< 1500 行`；同时要求原类保留为 `@Deprecated` 委托外观，**公开 API 不变**。
- R05 终态不是“把 god object 改名”：`CampusServicesRepository` 只是薄协调 Repository，图书馆、云身份、Utilities、Parcel、内容、偏好、提醒配置、插件注册必须保持内部业务分片；`ProfileDataWiper` 与 `SyncOrchestrator` 分别承载擦除和同步编排。
- facade 只保留公开兼容委托、session guard 与兼容嵌套类型；调用方最终直接注入领域 Repository。facade 退役是兼容策略决策，不能在本批静默删除。

## 3. 本批已完成的首个内聚簇

`feature/grades` 被选定原因：生产侧只有 7 行精确 facade 引用；已有 Route/ViewModel 边界；不依赖 `RestoreCommand` 等 facade nested 类型；依赖可整齐映射到 `GradesRepository`、`ExamRepository`、`TermRepository`、`TimetableRepository`、`CampusServicesRepository`。

已实施的机械变化：

1. `CampusRepository` 作为当前组合根，只读暴露已组装好的上述 5 个 Repository 实例；不新增业务逻辑。
2. `GradesScreen` / `GradesRoute` / `GradesViewModel` 改为直接接收 5 个拆分 Repository；`profileId` 与 `sessionGeneration` 作为会话标量显式传入。
3. `MainActivity` 只在组合根处从兼容 facade 取出实例并注入；功能模块不再 import 或类型引用 facade。

行为不变性依据：Flow 与写操作逐一对应原 facade 委托目标；`ToggleGpaPrivacyShield` 仍写入 `GradesRepository.setGpaPrivacyShield`；ViewModel 的 `profileId+sessionGeneration` key 语义保持不变。

## 4. 后续调用方迁移路径（建议批次）

1. **D1 feature/map**：只读 Timetable/Exam/CampusServices Flow 为主，无 nested RestoreCommand 类型；适合下一批。
2. **D2 settings leaf 簇**：按 ViewModel 而不是整个 settings 一口改完：network/update/theme/data → emptyclassroom/focus → dorm → community/moments → parcel → library/cloud。先重构 `ProfileSessionScopedViewModel`，让会话守卫不再要求完整 facade。
3. **D3 importer + onboarding 同步簇**：依赖 `SyncOrchestrator`；需先补齐 `importAuditsFlow` 等 R05 映射缺口，避免 importer 仍读 facade DAO 委托。
4. **D4 timetable + today 恢复命令簇**：必须先处理 nested 类型兼容。当前 `TimetableRepository` 自己的签名仍引用 `CampusRepository.RestoreCommand` / `OccurrenceCommandResult` / `OccurrenceRestoreResult`；直接迁移 UI 只会把循环依赖显式化。
5. **D5 reminder/focus/app 组合根**：会话守卫、wipe hook、DAO escape hatch 需要 `AccountSessionGuard` / `ProfileDataWiper` / 明确的 session repository basket；最后改 `ActiveSession` 形状。
6. **D6 测试与退役**：迁移 characterization tests 到领域 Repository；只有全部生产与测试调用方离开后，才能讨论删除 facade。若仍需外部兼容，则永久保留薄 deprecated facade，但不作为新代码入口。

## 5. facade `<800` 可行性边界

c8 后 facade 为 1768 行。结构事实：164 个 public fun、41 个 public val/var（本批为迁移暴露 5 个组合根只读实例后当前为 46）、11 个 nested 类型声明、68 行 import；约 45 个方法带默认参数。初始组合/会话/runtimePort 区域约 302 行，图书馆预约 runtime 装配另约 39 行；只删除空白与注释也仍有约 1314 行，说明“删掉注释空行”本身不能满足目标。

结论：**`<800` 在保持公开 API 兼容下理论上可达，但不是调用方迁移的自然结果，也不是无成本格式化目标。**

可行组合路径：

- 把 runtimePort/组合装配迁到 internal `CampusRepositoryRuntimeAssembly` 一类文件，facade 只持有装配结果；这能保持行为并减少约三百行级负担。
- 对纯机械 delegate 做紧凑格式：单行 Flow delegate、短方法一行表达式、只有多参数/默认参数方法保留多行签名。
- 对 nested 类型保留兼容声明或明确的兼容 typealias/迁移层；不能为降行数直接搬走而破坏 `CampusRepository.RestoreCommand` 等源码/API 形状。
- 若考虑 Kotlin interface delegation，必须先单独验证默认参数、`@Deprecated` 签名、Java/Kotlin 调用二进制形状；约 45 个默认参数方法使这不是无风险捷径。

不可行/不建议路径：

- 只改调用方：facade 仍必须为 164 个公开方法与 32 个 Flow 保留委托，行数不会自动降到 800 以下。
- 只做 b) 压缩格式：能在字面上接近目标，但会牺牲可读性；若不同步迁出 runtime 装配，压完后仍大概率超过 800。
- 删除 nested 类型或改签名：违反“公开 API 不变”，不是收口而是破坏兼容。

因此建议把 `<800` 拆成独立 facade 缩编批：先迁 runtime 装配与 nested 类型兼容层，再做机械压缩并跑全部 characterization tests。本批不混入该大改。

## 6. 当前已识别阻碍

- `TimetableRepository` 的恢复命令签名仍反向引用 facade nested 类型。
- `CloudIdentityRepository` / `SyncOrchestrator` / `LibraryBookingPlanRepository` 仍引用 `CampusRepository.CloudIdentitySessionSnapshot` 或 `BookingSubmissionIdentity`。
- `importAuditsFlow` 仍在 facade 直接委托 DAO，未完全落到 R05 指定的 `SyncOrchestrator` 边界。
- `CampusServicesRepository` 目前尚未完全承担 Library/Cloud/Parcel 的薄协调终态；调用方迁移应按 R05 分片边界推进，避免把这些内部实现类型扩散成新的公开依赖。

## 7. 验证记录

- 2026-11-28 quick：`ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w0-05d-callsite-migration quick :core:database :feature:grades :app`，退出码 0，SHA `1baaaef2a782ce86469bf60b55304f745678dd2f`，日志 `<campus-root>/logs/day-in-school/feat/cis-w0-05d-callsite-migration/1baaaef2a782ce86469bf60b55304f745678dd2f-quick.log`。
- android / secrets：最终门禁结果记录于 PR 正文；本文件不追逐门禁后追加提交造成的 SHA 漂移。
