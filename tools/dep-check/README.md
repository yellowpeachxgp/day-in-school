# dep-check — 依赖方向与 ui/ 包 import 门禁（W0-07）

## 用法

```bash
tools/dep-check/run
```

退出码：`0` 通过；`1` 存在违规；`64` 环境错误。CI 与 devbox `cis-gate android / android-full` 自动调用。

## 规则

| # | 规则 |
|---|---|
| 1 | `core` 模块不得依赖 `feature` / `widget` / `app` |
| 2 | `feature` 模块不得依赖 `feature` / `app` |
| 3 | `widget` 模块不得依赖 `feature` / `app` |
| 4 | `:core:model` 禁止任何项目依赖 |
| 5 | feature 模块内路径含 `/ui/` 的 .kt 文件不得 `import` 任何 `Repository` / `ViewModel`（ui/ 只放无状态 Compose UI，状态与装配在 `state/`、`Route` 中） |
| 6 | 不属于 core/feature/widget/app 族的新模块视为违规，必须先在本工具中登记分类 |

模块族按 Gradle 路径首段划分（`:core:*` → core 等）。依赖边从各模块 `build.gradle.kts` 中的
`project(":…")` / `project(path = ":…")` 引用提取，`api`/`implementation`/`testImplementation` 等配置不作区分——方向规则对所有配置一致生效。

## 例外登记

确实无法消除的违规登记在 [exceptions.txt](exceptions.txt)，格式：

```
:from:module -> :to:module  # 原因（必填）
```

登记必须与现存依赖边逐条精确对应；依赖消除后条目会产生 WARNING，应及时清理，逐步清零。

## 已知边界

- ui/ 检查只覆盖 `import` 行（任务卡的明确要求）；用全限定名直接引用而不 import 的写法不在检查范围内，依赖审阅把关。
- 模块与目录的映射使用 Gradle 默认约定（`:core:model` → `core/model`）。
