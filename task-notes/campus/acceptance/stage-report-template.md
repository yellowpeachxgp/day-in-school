# 阶段联调验收 stage-<序号>

> 复制本模板为 `stage-<序号>.md`。结论只有 `pass` / `fail` / `not-run` / `blocked` / `device-pending-user`；`not-run`、`blocked` 和 `device-pending-user` 都不是通过。

## 元信息

| 字段 | 值 |
|---|---|
| 验收日期 | <YYYY-MM-DD> |
| 验收子代理 | <agent-name> |
| 交叉复核子代理 | <agent-name 或 pending> |
| 列车 / PR | <train/cis-序号或 PR 列表> |
| Android main SHA | <40 位 SHA 或 null> |
| Cloud main SHA | <40 位 SHA 或 null> |
| 证据索引 | `evidence/stage-<序号>.jsonl` |

## 开发机门禁汇总

| 门禁 | 命令（秘密已用 $CIS_DEVBOX 表示） | SHA | 退出码 | 开发机日志路径 | 结论 |
|---|---|---|---:|---|---|
| android-full | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main android-full` | `<sha>` |  | `<campus-root>/logs/...` |  |
| cloud | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate cloud-in-school main cloud` | `<sha>` |  | `<campus-root>/logs/...` |  |
| contracts | `ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main contracts` | `<sha>` |  | `<campus-root>/logs/...` |  |

## 本趟列车声明能力逐项验收

| 能力 | 标题 | 结论 | 测试 / 命令 | 证据索引记录 | 日志或截图路径 | 备注 |
|---|---|---|---|---|---|---|
| Cxx |  |  |  |  |  |  |

## DEVICE_TESTED 待用户（不计入通过）

| 能力 | 待用户验证内容 | 当前自动证据 | 用户所需设备 / 系统版本 | 备注 |
|---|---|---|---|---|
| Cxx |  |  |  |  |

## 失败与分诊

| 能力 / 门禁 | 失败现象 | 首要证据 | 建议修复卡 | 是否阻断列车 |
|---|---|---|---|---|
|  |  |  |  |  |

## 交叉复核

- 复核人：
- 复核范围：
- 重跑项：
- 复核结论：

## 总结论

- [ ] 本趟列车声明能力全部通过（不含 `DEVICE_TESTED 待用户`）
- [ ] 存在失败项，已回报主代理建修复卡

备注：
