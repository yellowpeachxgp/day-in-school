# W4-12 Android E2EE 跨设备同步客户端设计

> 实现卡：W4-12；能力：C07/C27。上游契约：CT-07 `dis-sync-e2ee/v1`（云端 active）。本文是开发文档与边界说明，不修改两仓 `contracts/`。

## 1. 与 dis-backup/v1 的边界

- **备份是用户显式导出的静态快照**：`dis-backup/v1` 用口令整包加密，先 inspect/preview，再由用户选择 MERGE 或 REPLACE；它解决离线归档、迁移和灾难恢复。
- **同步是获得 E2EE_CLOUD_SYNC 明确同意后的持续复制**：客户端把同一套 dis-backup 行/列/主键/typed-cell 数据模型投影为行级增量对象，使用云端密文块、version-vector CAS 与 device-bound cursor 收敛；它不是备份文件的分片，也不会绕过备份恢复预览。
- 凭据、Cookie、Token、AI Key、授权 session、插件登记、widget 实例、调度运行时态仍按 dis-backup 目录排除，不因为同步而变为可上传数据。
- 本地全量擦除不清空云端用户密文历史；擦除链路会撤销/停用本设备同步登记、删除本地同步状态与密钥材料。云端账户级删除与 E2EE 同意撤回保留期由云端 CT-07 生命周期处理。

## 2. 行级增量对象

- 可移植表仍来自 `BackupTableCatalog`；每张表的列白名单、typed-cell 编码、主键编码与值级秘密扫描保持 dis-backup/v1 语义。同步复制在其上进一步排除 `library_booking_plans` 与 `focus_sessions`：它们可以进入同一设备的灾难恢复备份，但执行状态/generation/计时 lease 是设备局部事实，跨设备复制会重复本地自动化执行；未来必须先设计 device owner/lease 协议。
- 每个逻辑对象是一行：`table + primaryKey`。上传 plaintext 为 CanonicalJson 的 `dis-sync-backup-row/v1` payload，内含表名、行键、行 typed-cells、必要 blob 字节、schemaVersion、syncSpaceId、writerDeviceId、logicalClock 与 rowSha256。
- `personal_events` 与 `blob_store` 的 scope 列在上传前规范化为固定同步值，应用前再改写为接收设备的当前 Profile scope。同步空间由恢复密钥显式选择，避免把设备随机 profile/account UUID 当成跨设备逻辑身份，也避免不同 Profile 静默合并。
- 删除优先使用云端 tombstone；blob_id 被 tombstone 后绝不复用，逻辑行重建必须分配新的随机 blob_id。
- v1 客户端对单行 payload 超过 1,048,556 字节 plaintext 上限的对象 fail-closed 并在运行报告中列出，不以未定义方式拆包或静默跳过。后续切片如需大 blob chunked replication，必须先补专门的引用/垃圾回收设计。

## 3. 密钥与明文边界

- 首次启用生成 32 字节随机 sync master key 与 16 字节随机 sync space id，合并编码为带 6 字节 SHA-256 校验位的一次性恢复密钥；其他设备只能由用户手动粘贴同一恢复密钥加入同一逻辑同步空间，校验位用于防抄写错位。恢复密钥绝不上传、不写入日志、不进入备份。
- 每台设备另生成 Android Keystore AES-GCM 非导出包装密钥，只用于密封本机缓存的 sync master key；设备硬件序列号、IMEI、SSAID、推送 token 不作为 device_id。
- 块加密为 AES-256-GCM；AAD 精确绑定 `dis-sync-e2ee/v1\n<lowercase blob_id>\n<key_id>\n`。plaintext 先写入 4 字节大端原始长度，再零填充到 bucket-16；输出密文含 tag 必须恰为 1,024/4,096/16,384/65,536/262,144/1,048,576 之一。
- 云端请求只包含注册元数据、密文、密文 SHA-256、version vector、cursor 与非秘密 encryption envelope；明文、明文 hash、master key、包装密钥、恢复密钥、口令或 KDF secret 不出设备。

## 4. HTTP transport 与依赖边界

生产 HTTPS transport 使用 OkHttp（Apache-2.0，`com.squareup.okhttp3:okhttp:4.12.0`），因为 CT-07 的 blob DELETE 必须携带 `base_vector/writer_counter` JSON body，而 Android 内建 `HttpURLConnection` 在多个受支持版本拒绝 DELETE 写 body。transport 只允许 HTTPS、关闭自动重定向、不写请求/响应日志；除该显式依赖外不引入代理池、Cookie 持久化或第三方密钥。

## 5. CAS、cursor 与冲突语义

- PUT/DELETE 使用服务器当前 version vector 做严格 CAS；writer_counter 只前进一格。网络结果不确定时，客户端先持久化 canonical pending write，后续用相同 Idempotency-Key 与相同字节重试，绝不用同一 counter 发送不同 plaintext。
- cursor 只在页面全部解密、校验并持久应用后保存；cursor 不跨设备复制。SYNC_CURSOR_EXPIRED/INVALID 只丢弃 cursor 并 cursorless resync，不删除本地 dirty 或从未确认的对象。
- version vector 只排序服务端写，不证明 plaintext freshness。payload logicalClock/rowSha256 是客户端回滚检测事实源：远端 logicalClock 倒退、同 clock 不同内容、本地 dirty 与远端并发、同逻辑键双 blob identity、删除遇上本地 dirty，全部进入 pending conflict。
- 冲突永不静默覆盖。UI 同时展示本机版本与远端版本（表、row key、logicalClock、内容摘要/canonical row），用户显式选择保留本机或使用远端；删除冲突提供「保留本机」与「接受远端删除」。
- 无冲突且远端按 logicalClock 前进时，客户端在 Room 事务中应用行 upsert/delete，并以 `Actor.SYNC` / `e2ee-cloud-sync` 写 vault journal；不伪装成用户手动编辑或 RESTORE。`personal_events` 恢复生成列使用 `dis-sync-e2ee/v1` / `E2EE_CLOUD_SYNC` provenance，绝不写成 `dis-backup/v1` / `LOCAL_BACKUP_RESTORE`。每张可同步表都有受控 EntityType 的行级 journal ref；已注册 SnapshotStore 的实体保持原生快照语义，其他表记录精确 before/after typed-cell 快照，blob 内容仍只按摘要引用。

## 6. 云身份、设备与本地擦除

- 本地同步状态显式绑定启用时的 `cloud_user_id`。切换 XPeach Cloud 账号后，旧 cursor/vector/object index 一律冻结 fail-closed；不得把旧账号因果状态用于新账号，也不得以 cursorless resync 删除本地 dirty 数据。用户必须显式停用/擦除旧同步状态后重新加入。
- 停用顺序是先撤回 E2EE consent、再撤销当前设备：revoke 当前设备会失效其绑定的会话族，反向顺序会导致 consent 撤回不可达。UI 如实区分「远端已确认」与「本机已停止但远端未确认」。
- 撤销是永久 tombstone；重新启用总是生成新的随机 installation UUID。设备列表只展示账号安全元数据，不展示硬件序列号、精确设备标识、密钥或 session 绑定。JOIN 只能重复同一 recovery key；不同 key 不得覆盖本机密封 key 或混入旧 objects/conflicts，用户必须先显式执行「清除本机同步状态并重新加入」。
- 全量擦除注册在 ProfileDataWipe before-hook：best-effort 停用远端后无条件删除本机状态、pending write、cursor、冲突缓存、Keystore 包装密钥与密封 master key，并取消 WorkManager。

## 7. 同意与生命周期 UI

- 骨架 UI 放在「备份与恢复」卡片内，但文案明确区分静态备份和持续同步。用户必须勾选理解：云端只存密文但可见 bucket size/时间/设备在线模式；v1 设备、块数、字节与 24h 传输配额；撤回同意后 30 天保留、过期删除和设备撤销生命周期。
- 勾选前不创建 consent、不注册设备、不生成或上传任何同步元数据。未登录 XPeach Cloud 时显示 NEEDS_LOGIN；离线/不支持/错误状态进入同一 SettingsUiMatrix。
- 同步后台唤醒只是用户已同意后的周期性复制补处理，不是通知保活机制；禁用同意或撤销设备后 fail-closed。
