# file-line-gate — 文件行数门禁（W0-07a）

## 用法

```bash
tools/file-line-gate/run
```

退出码：`0` 通过（可含 WARNING/INFO）；`1` 存在 FAILED；`64` 环境错误。CI 与 devbox `cis-gate android / android-full` 自动调用。

## 规则

| 对象 | 阈值 | 结果 |
|---|---|---|
| 未登记基线的文件 | > 1500 行 | WARNING |
| 未登记基线的文件 | > 2500 行 | FAILED |
| 已登记基线的既有大文件 | 行数增加（> 基线值） | FAILED（只许降不许升） |
| 已登记基线的既有大文件 | 行数减少 | INFO：提示收紧 baseline.txt |
| 基线条目对应文件已不存在 | — | WARNING：提醒移除条目，逐步清零 |

## 覆盖范围

- 扫描 `git ls-files` 中扩展名为 `kt kts java xml mjs cjs js ts sql sh yaml yml` 的文件。
- 不扫描 `.md`（文档长文属正常形态）、`.json`（多为 Room schema 等生成物或数据文件）、二进制资源。

## baseline.txt 格式

```
<路径><TAB><登记行数>
```

文件缩小后应把登记行数收紧到当前值；文件被拆分或删除后应移除条目。目标是基线条目逐步清零
（W0-05 拆 `CampusRepository`、W0-06 拆巨型 Screen 都是清零路径的一部分）。
