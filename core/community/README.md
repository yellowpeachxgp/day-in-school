# :core:community（W4-05）

群组云 Android 客户端基础模块，覆盖群组/邀请/成员、忙闲与投票、账本/结算、值日/报修、地点状态/举报的 active v1 OpenAPI operation。

## 边界

- 手写客户端：`CommunityOperation` 逐项声明 54 个 operation 的 method、path、请求 schema、成功 envelope 与幂等要求；`CommunityContractParityTest` 直接读取仓库内同步副本 `contracts/openapi/cloud-v1.yaml` 做**结构 parity**（operation/method/path/schema 名称/envelope/幂等），不夸大为字段级 schema exact parity。
- 响应保持 `CommunityEnvelope` + 契约 JSON。通用层不把 `operationId` 冒充记录 ID，也不伪造未生成的领域类型；后续 feature 卡在自己的边界做类型映射。
- 传输默认只允许 HTTPS（本机 loopback 测试可用 HTTP），写操作按契约强制 `Idempotency-Key`，401 显式映射为 `AuthenticationRequired`。`execute` 仅为模块内部入口，外部只能使用类型化 API，不能注入任意 raw body 绕过校验。
- `CommunityFeatureGate` 是必须注入的功能开关 Port；每个领域独立返回 enabled/disabled，禁用时不触网、不写缓存。

## 持久化评估（Room v17 替代决策）

任务卡初始分配 Room v17，但 D14 要求 Room 版本严格按序。当前 v11 刚合入、v12 仍在途、v13-v16 属于其他卡；若本卡抢占 v17 会形成迁移顺序和合并冲突风险。因此本卡**不新增 Room schema、不占用 v17**，先采用 `FileCommunityCacheStore`：

- 以 `filesDir/community-cache/account_<sha256(profileId)>` 做 Profile 隔离，文件名不包含学号或账号明文；缓存键另含当前 `cloudUserId`、`identityGeneration` 与规范化 cloud base URL 的规范键材料（整体哈希落盘），切云号、同号重登、解绑重绑或换云地址后旧键天然不可读；
- 原子写入、单条/总字节/条数上限、损坏或异常放大文件 fail-closed 删除；
- 进程内按 root 共享锁与 wipe generation：擦除前已在途的旧实例写入不得重新种回，擦除后新实例可正常使用；擦除失败会传播并阻断该 root 后续读写，重试成功后才解除；
- `CommunityClientFactory.createFileBacked` 是唯一受支持的装配形状，且必须注入当前云身份/代际提供者；`CommunityClient` 在 cacheStore 与身份提供者缺失组合下直接构造失败；
- 云身份登录、切号、同号重登与解绑经 `CloudIdentityRuntimePort` 钩子在身份变更生效前清空本 Profile community 缓存，失败会阻断身份变更；
- 擦除链路经 `CommunityCacheWipeRegistration` 注册到 `ProfileDataWiper`，`CommunityCacheWipeTest` 断言当前 Profile 擦除后为 0、不跨 Profile 误删，且文件删除失败会传播而不是伪装成成功。

## 离线降级与隐私缓存策略

MASTER_PLAN §7 规定 W4「以云端为主，本地只缓存群组快照」；AUTO-038 进一步从严为只缓存群组元数据。因此：

- 只有 `listGroups`、`getGroup` 允许离线缓存；`listGroupMembers` 因成员信封含 `cloud_user_id/display_name/sharing_level` 一律不落地，离线显式失败；
- 离线或 408/429/5xx 时命中缓存会返回 `Staleness.STALE`、`cachedAtEpochMillis` 与明确 fallback reason，绝不伪装为云端新数据；
- 忙闲、账本、值日/报修、地点状态等协作/众包读结果不长期保留；离线时显式 `NETWORK_UNAVAILABLE` 失败，避免绕过 TTL、撤回共享或成员移除语义；
- 写请求发出前即保守清空快照，覆盖网络失败但服务端可能已提交的 `STORAGE_COMMIT_UNKNOWN` 类竞态；写成功后再次清理；普通纯 GET/查询成功不会误清缓存；
- 任一 community operation 返回 403/404 权威失权/删除信号时，群组列表与详情因内容重叠而保守清空整个小型群组元数据缓存分区；清理失败返回 `CACHE_INVALIDATION_FAILED`，不让失权数据后续复活。

## AUTO-030 成员共享级别边界

`UpdateGroupMemberRequest` 类型层面只有 `role`，不存在 `sharingLevel` 属性或序列化字段。客户端无法证明任意 `memberId` 就是调用者本人，因此不提供经成员管理端点代改 `sharing_level` 的任何 API 路径；共享/撤销只走 `/availability/me` 自服务端点及 `ReplaceGroupAvailabilityRequest` 独立类型，由服务端按调用者身份派生共享级别。

## 擦除依赖方向评估

当前 `:core:database` 仍通过 `CommunityCacheWipeRegistration` 直接调用 `:core:community` 文件缓存实现。Port/注册式依赖反转需要应用 composition root 或 W4 feature 装配卡提供可靠注册点；在注册缺失会导致漏擦的前提下，本卡保留显式耦合作为 fail-closed 保守实现，并记录为后续架构债，不伪造已完成反转。

## 验证入口

本机不得直接跑 Gradle。开发机现场门禁：

```bash
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-05-community-client quick :core:community :core:database
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-05-community-client android
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-05-community-client secrets
```

论坛/私信不属于本模块实现范围；W4-06 只提供 reserved/disabled Port，且默认无 UI 入口。
