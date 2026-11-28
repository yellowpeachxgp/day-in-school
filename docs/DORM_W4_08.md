# W4-08 宿舍协作（#37 / C16）

## 范围

本卡只实现宿舍群组的账本、分摊、结算、值日轮班/换班/请假/完成确认、报修协调，以及本机水电余额联动提醒。账本只记录债务关系，**不收付款、不转账、不声明缴费成功**；`CONFIRMED` 只表示债务方与债权方都确认了一次账本记录。

## 架构边界

- `:feature:dorm` 是用户功能边界；`DormCollaborationPort` 是唯一云端协作入口，UI/ViewModel 不直接构造 HTTP 请求。
- `CommunityDormCollaborationAdapter` 复用 `:core:community` 契约 client，并把 envelope JSON 映射为宿舍领域模型。账本响应必须通过总额守恒、排序、正负号、largest-remainder 与更正链校验；失败一律 `CONTRACT` fail-closed。
- 分摊计算只使用 `:core:ledger` 的 `ExpenseSplitter`：整数分、largest-remainder、负金额先按绝对值分摊再逐项取负；禁止使用浮点金额。
- 云身份装配遵循 W4-07 同项裁决：`CampusRepository` 只读 val 暴露 `CloudIdentityRepository`，feature 侧只注入 `snapshot()` 与 `resolveAccessToken(snapshot)` 两方法窄 port；令牌不进入 UI 状态、日志、备份或持久缓存。
- 协作明细不做本地持久化。只有 W4-05 既有的群组元数据文件缓存可离线降级；成员、账本、值日、报修离线显式失败，不伪装为新数据。
- 水电联动只读取当前 Profile 已授权保存的在线/手工水电数据，按已有采样估计余额可用天数；不触发缴费、支付、自动化或网络刷新。

## 状态与 UI

骨架 UI 覆盖 Loading / Empty / Content / Partial / Stale / Retryable Error / Terminal Error / Needs Permission / Needs Login / Offline / Unsupported 共 11 态，fixtures 与 Roborazzi golden 均使用合成 UUID、合成宿舍和合成账目。

## 验证

开发机命令（真实路径只在日志中体现，公开记录写 `<campus-root>`）：

    ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-08-dorm quick :feature:dorm :core:database
    ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-08-dorm android
    ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w4-08-dorm secrets

截图基线录制：

    ssh "$CIS_DEVBOX" "cd <campus-root>/build/day-in-school/feat/cis-w4-08-dorm && <campus-root>/bin/cis-gradle :feature:dorm:recordRoborazziDebug"

## 已知骨架边界

- 换班目标选择是骨架式“下一个可用成员”单候选，不是完整成员选择器；服务端仍是权限与状态机权威。
- 列表当前读取首 100 条；存在 `next_cursor` 时 UI 明确进入 PARTIAL，不冒充完整历史。
- 报修照片、采购清单、缴费凭证识别、周期性账单和月度统计不在本卡范围。
