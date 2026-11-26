# file-line-gate — 文件行数门禁（W0-07a）

## 用法

```bash
tools/file-line-gate/run
```

退出码：`0` 通过（可含 WARNING/INFO）；`1` 存在 FAILED；`64` 环境错误（含无法验证棘轮）。CI 与 devbox `cis-gate android / android-full` 自动调用。

## 规则

| 对象 | 阈值 | 结果 |
|---|---|---|
| 未登记基线的文件 | > 1500 行 | WARNING |
| 未登记基线的文件 | > 2500 行 | FAILED |
| 已登记基线的既有大文件 | 行数增加（> 基线值） | FAILED（只许降不许升） |
| 已登记基线的既有大文件 | 行数减少 | INFO：提示收紧 baseline.txt |
| 基线条目对应文件已不存在 | — | WARNING：提醒移除条目，逐步清零 |

## 棘轮保护（防同 PR 抬基线）

baseline.txt 自身是被保护对象，否则攻击者可以在同一个 PR 里先把基线数值抬高再让文件增长：

| 检查 | 结果 |
|---|---|
| 重复条目、畸形条目（非 `<路径><TAB><行数>`） | FAILED |
| 相对 merge-base 参照版本**新增白名单条目** | FAILED |
| 相对 merge-base 参照版本**条目数值上升** | FAILED |
| 条目数值下降、移除条目 | 允许（收紧与清零方向） |

参照解析顺序：`FILE_LINE_GATE_BASE_REF` 环境变量 → `refs/remotes/origin/main` → `refs/heads/main` 等同义写法。
参照版本取 `git merge-base <ref> HEAD` 处的 baseline.txt；merge-base 无该文件时回退到参照分支版本；两者都无
（首次引入本基线的 bootstrap 阶段）则跳过棘轮并输出 INFO，由审阅把关。**找不到 git 环境、main 参照或
merge-base 时门禁 fail-closed（退出 64）**——托管 CI 的 guardrails 作业因此必须 `fetch-depth: 0`。

设计推论：白名单只减不增。W0-06 式的巨型 Screen 拆分产物若仍 >1500 行会 WARNING、>2500 行直接 FAILED，
形成「拆分必须到位」的持续压力； pure 搬移大文件而不能拆小是行不通的。

## 覆盖范围

- 扫描 `git ls-files` 中扩展名为 `kt kts java xml mjs cjs js ts sql sh py yaml yml` 的文件。
- 不扫描 `.md`（文档长文属正常形态）、`.json`（多为 Room schema 等生成物或数据文件）、二进制资源。

## baseline.txt 格式

```
<路径><TAB><登记行数>
```

文件缩小后应把登记行数收紧到当前值；文件被拆分或删除后应移除条目。目标是基线条目逐步清零
（W0-05 拆 `CampusRepository`、W0-06 拆巨型 Screen 都是清零路径的一部分）。

## 已知边界

- 门禁脚本自身完整性靠热点文件独占与交叉审阅保证（任何门禁都无法机械防止有人改写门禁本身）。
- 参照版本不含 baseline 的 bootstrap 窗口期内，棘轮静默跳过（有 INFO 输出），本仓库该窗口随本文件首次合入 main 关闭。
