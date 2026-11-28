# :core:community（W4-05）

群组云 Android 客户端基础模块，覆盖群组/邀请/成员、忙闲与投票、账本/结算、值日/报修、地点状态/举报的 active v1 OpenAPI operation。

## 边界

- 手写客户端：`CommunityOperation` 逐项声明 54 个 operation 的 method、path、请求 schema、成功 envelope 与幂等要求；`CommunityContractParityTest` 直接读取仓库内同步副本 `contracts/openapi/cloud-v1.yaml` 做双向比对。
- 响应保持 `CommunityEnvelope` + 契约 JSON。通用层不把 `operationId` 冒充记录 ID，也不伪造未生成的领域类型；后续 feature 卡在自己的边界做类型映射。
- 传输默认只允许 HTTPS（本机 loopback 测试可用 HTTP），写操作按契约强制 `Idempotency-Key`，401 显式映射为 `AuthenticationRequired`。
- `CommunityFeatureGate` 是必须注入的功能开关 Port；每个领域独立返回 enabled/disabled，禁用时不触网、不写缓存。

## 持久化评估（Room v17 替代决策）

任务卡初始分配 Room v17，但 D14 要求 Room 版本严格按序。当前 v11 刚合入、v12 仍在途、v13-v16 属于其他卡；若本卡抢占 v17 会形成迁移顺序和合并冲突风险。因此本卡**不新增 Room schema、不占用 v17**，先采用 `FileCommunityCacheStore`：

- 以 `filesDir/community-cache/account_<sha256(profileId)>` 做 Profile 隔离，文件名不包含学号或账号明文；
- 原子写入、单条/总字节/条数上限、损坏或异常放大文件 fail-closed 删除；
- `CommunityClientFactory.createFileBacked` 是唯一受支持的装配形状；
- 擦除链路经 `CommunityCacheWipeRegistration` 注册到 `ProfileDataWiper`，`CommunityCacheWipeTest` 断言当前 Profile 擦除后为 0 且不跨 Profile 误删。

## 离线降级与隐私缓存策略

MASTER_PLAN §7 规定 W4「以云端为主，本地只缓存群组快照」。因此：

- 只有 `listGroups`、`getGroup`、`listGroupMembers` 允许离线缓存；
- 离线或 408/429/5xx 时命中缓存会返回 `Staleness.STALE`、`cachedAtEpochMillis` 与明确 fallback reason，绝不伪装为云端新数据；
- 忙闲、账本、值日/报修、地点状态等协作/众包读结果不长期保留；离线时显式 `NETWORK_UNAVAILABLE` 失败，避免绕过 TTL、撤回共享或成员移除语义；
- 写请求发出前即保守清空快照，覆盖网络失败但服务端可能已提交的 `STORAGE_COMMIT_UNKNOWN` 类竞态；写成功后再次清理。

## 验证入口

本机不得直接跑 Gradle。开发机现场门禁：

```bash
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-05-community-client quick :core:community :core:database
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-05-community-client android
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-05-community-client secrets
```

论坛/私信不属于本模块实现范围；W4-06 只提供 reserved/disabled Port，且默认无 UI 入口。
