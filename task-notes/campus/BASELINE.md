# 基线记录（INF-01）

> 记录时间：2026-11-26（Asia/Shanghai）。本文件只记录公开仓库基线和 bootstrap 状态，不记录真实开发机地址、账号或凭据。

## git fetch --prune 后的 origin/main

| 仓库 | origin/main SHA | 最新提交时间 | 最新提交标题 | 版本号 |
|---|---|---|---|---|
| day-in-school | `05eeff879bad40bf22dcee1d4bc4073151a5f8ad` | 2026-11-17 14:10:46 +0800 | 合并 Cloud 实际响应契约同步 | Android `0.20.23` / build `2023`；compile/target SDK 36，min SDK 31 |
| cloud-in-school | `ee8687da02b36cf703c37a4891c3190dd43233cc` | 2026-11-19 03:52:24 +0800 | 修复多历史委托同意链路错配导致预检误判撤销及绑定选择一致性问题 | package `cloud-in-school` `1.0.0`；Node `>=22.0.0` |

## 损坏 refs 检查

- 已执行：`git fetch --prune origin`、`git ls-remote origin "refs/codex/turn-diffs/*"`、`git fsck --no-progress --connectivity-only`（在两个干净种子克隆中）。
- 结果：两仓远端当前未广告 `refs/codex/turn-diffs/*`；种子克隆 connectivity fsck 退出码 0，未发现损坏引用。
- 规则：若后续发现损坏的 `refs/codex/turn-diffs/...`，只列出供主代理处理，**不得删除**。

## INF-00 main 基线门禁（已核验日志摘要）

| 仓库 | SHA | 门禁 | 结果 | 耗时 | 内存摘要 | 日志路径 |
|---|---|---|---|---|---|---|
| day-in-school | `05eeff879bad40bf22dcee1d4bc4073151a5f8ad` | `android` | 退出码 0 | 321 秒 | MinMemAvailableKB 73076276；host-wide peak used 58792440 KB | `<campus-root>/logs/day-in-school/main/05eeff879bad40bf22dcee1d4bc4073151a5f8ad-android.log` |
| cloud-in-school | `ee8687da02b36cf703c37a4891c3190dd43233cc` | `cloud` | 退出码 0 | 14 秒 | MinMemAvailableKB 85227192；host-wide peak used 46641524 KB | `<campus-root>/logs/cloud-in-school/main/ee8687da02b36cf703c37a4891c3190dd43233cc-cloud.log` |

内存说明：`host-wide peak used` 是门禁采样期间全机 `MemTotal - min(MemAvailable)`，不是单个构建进程 RSS。

## INF-00 剩余验收项

| 项 | 状态 | 说明 |
|---|---|---|
| `cis-gate` 可用性 | 已核验 | 2026-11-26 复核时已安装且可执行 |
| 构建槽并发（6 请求只跑 4 个） | 待 INF-00 交回 | INF-01 不空等；由 INF-00 验收 |
| `PAUSE` 文件等待/恢复 | 待 INF-00 交回 | INF-01 不空等；由 INF-00 验收 |
| 校园容器前缀与企业容器隔离 | 待 INF-00 交回 | INF-01 不空等；由 INF-00 验收 |

## bootstrap 限制

AUTO-001 已记录：bootstrap 期间 INF-00/INF-01 与文档卡无法等待 cis-gate，可用静态自检先开 PR，后续仍须补现场门禁；自主决策，待用户复核。INF-01 已在 cis-gate 可用后补跑现场门禁，结果写入对应 PR；本文件只记录 main 基线，不用后续 INF-01 分支 SHA 循环更新。
