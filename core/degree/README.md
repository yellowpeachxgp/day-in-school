# core:degree — 培养方案导入与毕业审计持久化（W3-01）

## 范围

- univ-training-plan/v1 JSON、扁平 CSV 与教务网关中性视图的培养方案导入（导入器在纯 JVM :core:academic-plan）。
- DegreeAuditor 毕业审计：重修去重、课程改名、替代课程、人工认定优先、学分不重复计算、缺口/风险/依据轨迹。
- 人工认定（AuditOverride）按 MASTER_PLAN §6.6 进 journal：依据、操作者、时间可在变更历史展开并撤销。
- 审计结论一律标明「个人规划参考，不代表学校毕业审核结论」；方案来源未知或未确认时信任级别为 ESTIMATED。

## Room v15 如实标注（AUTO-042）

任务卡为 training_plan_imports、audit_overrides 预留 Room v15，但 v14（W2-05）未按编号顺序合入。按 AUTO-042 裁决（同 AUTO-037/041 先例），本实现采用过渡持久化、不消耗 v14/v15：

1. 导入记录（含 univ-training-plan/v1 canonical 方案本体）与人工认定都是版本化 canonical JSON。完整逻辑文档原件单独存 blob_store（不受 Vault 64 KiB journal 快照限制），versioned-degree-document/v2 信封只存 documentRef、摘要与显式单调 writeSequence；journal 快照只存引用/摘要。备份恢复不依赖 rowid/blobId 顺序。激活指针是非实体小型状态，不进 journal；
2. 逻辑实体类型为 TRAINING_PLAN_IMPORT / AUDIT_OVERRIDE（EntityType 纯枚举追加，非迁移）；全部实体写经 Vault EntityWriter，与 journal/outbox 同事务；
3. 待 v14 合入且 v15 窗口可用时，应按这些格式做显式迁移，不得重写既有 blob 内容身份。

这不是 DataStore 草案，也不是内存 mock：数据真实落在 W1-03 Vault 表内，并受既有 ProfileDataWiper 对 entity_journal/domain_outbox/blob_store 的全量擦除覆盖（VaultWipeTest 已验证擦除后为 0）。由于未新增表，本卡无需也无法单独注册新表擦除项。

## 组合入口

DegreeRuntimeFactory.create(database, scope) 返回 DegreeRepository。应用层只需要注入当前 profile 的 HautDatabase 与精确 SchoolAccountScope。repository 实现 AutoCloseable；若门面早于账号库销毁，集成方必须 close 以释放 W1-03 outbox 的 Room invalidation observer。主导航接入不在本切片内（同 W3-04），避免抢占 MainActivity/AppGraph 热点。

## 关键语义

- 导入按内容摘要去重：同一文件重复导入不产生第二条记录；首次导入自动激活，重复导入不改变激活选择。
- 文件导入来源初始 UNKNOWN；用户在界面确认适用后升级 USER_CONFIRMED（确认行为是来源升级的唯一通道），审计信任级别由 ESTIMATED 升 DERIVED；取消确认不回退来源。
- 人工认定按逻辑课程维度生效并绑定创建时的 plan/import/version：同一课程的补考/重修及声明改名后的旧码/新码共享同一归属结论；同一课程最新认定优先，RETRACT 撤销后回到自动匹配；切换方案不串用历史认定。
- 要求组按层次约束审计：子组成就计入父组汇总，root 总要求不会因叶子为 0 而丢失；混合父组的直接课程参与匹配，一门逻辑课程仍只入账一次。
- 网关视图导入不捏造分组要求学分：页面给出的总要求作为 root 汇总约束统计缺口；未给出的字段保持 0/空缺并记 warnings，由人工核对补录。
