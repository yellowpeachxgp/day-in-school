# dis-backup/v1 备份格式与恢复语义

> 实现卡：W1-04；能力：C07/C27。本文只描述本地加密备份；云端 E2EE 增量同步由 W4-11/W4-12 在此数据模型上演进。

## 1. 容器分层

导出的文件不是明文 ZIP。实现先构造一个确定性 ZIP，再把**整个 ZIP**作为 plaintext 用口令派生密钥加密：

```
plaintext ZIP ── AES-GCM encrypt ─> DISBKUP1 容器
├─ manifest.json
├─ data.json
└─ blobs/<sha256>
```

外层二进制容器：

```
"DISBKUP1" (8 bytes)
uint32be headerLength
canonicalJson(header)
ciphertext || GCM tag
```

header 只包含算法参数，不包含用户数据：`format=dis-backup-envelope/v1`、`formatVersion`、`kdf=PBKDF2WithHmacSHA256`、`iterations`、`cipher=AES/GCM/NoPadding`、salt、nonce、`keyCheck`、payloadFormat、plaintextSha256。默认导出迭代次数为 120000。header canonical bytes 同时作为 AES-GCM AAD；keyCheck 只用于把「口令错误」与「口令正确但密文/容器损坏」分开，不保存口令或派生密钥本身。

## 2. manifest.json

manifest 是 CanonicalJson，且 v1 拒绝任何未定义字段。字段：

- `format`: 固定 `dis-backup/v1`
- `formatVersion`: 当前为 `1`
- `createdAt`: ISO-8601 UTC 时间
- `appVersion`: 导出 App 版本
- `schemaVersion`: 导出时 Room schema 版本
- `profileFingerprint`: `SHA-256(schoolId/accountId/profileId/campusId 的长度前缀编码)`；manifest 不直接暴露学号或 Profile UUID
- `entryCount`: 数据行数 + 去重后的 blob 二进制条目数
- `payloadSha256`: `data.json` 原文 SHA-256
- `tables[]`: 表名、行数、该表 CanonicalJson SHA-256

格式版本或 schema 版本高于当前实现时恢复 fail-closed，报「版本过高」。v1 不接受靠忽略未知字段继续恢复。

## 3. data.json 与 blob

`data.json` 是 CanonicalJson：scope、tables、blobEntries。每个表显式列出 columns；行值使用带类型标签的 object（`null/integer/real/text/blob`），避免 JSON number 精度或 SQLite 动态类型丢失。blob 字节不内联进 JSON，而作为 `blobs/<sha256>` ZIP 条目保存；data 中只保存摘要、大小和路径引用。恢复前逐项重新计算 blob SHA-256，未引用或缺失的 blob 都视为损坏。

## 4. 可移植数据与明确排除

v1 备份的可移植分类：学业数据、校园参考数据、提醒偏好、水电与专注历史、图书馆预约计划、包裹记录、个人日程、blob 原件。实现目录见 `BackupTableCatalog.kt`。

以下内容**明确不进入备份**：

1. 凭据、Cookie、Token、AI Key、私钥、授权 session；schema 键名防线对 `password/credential/cookie/authorization/token/secret/apikey/aikey/privatekey/sessionid` 等标记 fail-closed。
2. `cloud_identity_binding`、`parcel_source_connections`：它们是跨设备授权/登录 linkage。备份不携带 Cookie/Token，因此不能恢复出“看似已授权”的假状态；用户在新设备重新登录/授权。
3. `widget_instance_configs`：Android `appWidgetId` 是设备局部序号，跨设备恢复会错绑或覆盖其它 widget。
4. `reminder_tasks`：调度运行时态，由课程/计划等 source-of-truth 重新 reconcile。
5. `installed_extension_plugins`：插件包、签名和授权文件在库外，恢复登记行会制造悬挂信任状态。
6. `entity_journal`、`domain_outbox`、`search_docs`、`search_fts`：审计、投递和索引由恢复事务重新生成。业务行是可移植事实；恢复不能伪装成旧写入，必须留下自己的 `Actor.RESTORE` 审计。
7. `occurrence_overrides.mutationToken`：这是并发行版本令牌而非用户内容，schema 层省略；恢复时每行重新生成，不复用旧令牌。

## 5. 身份与跨设备语义

备份绑定的是逻辑 Profile（school/account/profile/campus）指纹，不绑定设备名、Android 安装 ID、widget ID 或开发机/设备路径。同一逻辑 Profile 在另一台设备上具备相同身份时，备份语义保持一致；实现**不做隐式 ID 重写**。指纹不匹配时明确报「身份不匹配」并拒绝恢复。未来跨设备增量同步如需把本地随机 Profile 映射到同一逻辑身份，必须在 W4-12 的冲突/映射 UI 中显式完成，不能在 v1 恢复路径静默改写。

## 6. 预览与恢复原子性

`inspect` 解密并完整校验后才生成 `RestorePreview`：版本、身份、条目数、逐表摘要、blob 完整性、列集合、行类型、scope、CanonicalJson 规范形。预览列出新增、未变化、冲突以及 REPLACE 将移除的行数。

- `MERGE`：不删除缺失行；每个内容冲突必须由用户选择 `USE_BACKUP` 或 `KEEP_EXISTING`，缺少选择时恢复前失败。
- `REPLACE`：只覆盖备份内所选分类对应表；不清除设备本地授权、插件登记、widget 配置等非备份表。
- 预览后本地相关表若发生变化，restore 在事务内报 stale，要求重新预览。
- 所有删除/upsert/search rebuild/journal/outbox 写在同一个 Room 事务中。任何校验、约束或注入失败都整体回滚，不发生部分写入。
- 成功的恢复由 Vault journal 以 `Actor.RESTORE` 记录可追踪实体变更，outbox 只携带引用/changeId，不复制大快照或敏感正文；搜索索引在同事务重建，避免 FTS 幽灵结果。

## 7. 错误分类

实现对用户可见地区分：文件损坏、口令错误、备份格式/schema 版本过高、Profile 身份不匹配、MERGE 冲突未选择、预览后本地数据已变化。错误消息不得包含口令、派生密钥、Cookie、Token 或 AI Key。
