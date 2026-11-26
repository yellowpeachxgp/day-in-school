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

## INF-00 基线门禁

| 项 | 状态 | 说明 |
|---|---|---|
| `cis-gate` 可用性 | 待补 | 2026-11-26 检查时 `<devbox>` 上尚未安装/可执行 |
| day-in-school `android` 基线 | 待补 | INF-00 完成后记录耗时、峰值内存、日志路径 |
| cloud-in-school `cloud` 基线 | 待补 | INF-00 完成后记录耗时、峰值内存、日志路径 |
| 构建槽并发与 PAUSE 验证 | 待补 | INF-00 验收项 |

## bootstrap 限制

INF-01 的静态自检不能替代开发机现场门禁。AUTO-001 已记录：bootstrap 期间 INF-00/INF-01 与文档卡无法等待 cis-gate，可用静态自检先开 PR，后续仍须补现场门禁；自主决策，待用户复核。
