# dep-check — 依赖方向与 ui/ 包 import 门禁（W0-07a）

## 用法

```bash
tools/dep-check/run
```

退出码：`0` 通过；`1` 存在违规；`64` 环境错误。CI 与 devbox `cis-gate android / android-full` 自动调用。
运行环境需要 `python3`（词法扫描后端；devbox、macOS、GitHub 托管 runner 均已验证具备），缺失时 fail-closed。

## 规则

| # | 规则 |
|---|---|
| 1 | `core` 模块不得依赖 `feature` / `widget` / `app` |
| 2 | `feature` 模块不得依赖 `feature` / `app` |
| 3 | `widget` 模块不得依赖 `feature` / `app` |
| 4 | `:core:model` 禁止任何项目依赖 |
| 5 | feature 模块内路径含 `/ui/` 的 .kt 文件不得 `import` 任何 `Repository` / `ViewModel`（含 `as` 别名与嵌套类形式，如 `import a.b.FooRepository`、`import a.b.FooViewModel.Factory`） |
| 6 | 不属于 core/feature/widget/app 族的新模块视为违规，必须先在本工具中登记分类 |

模块族按 Gradle 路径首段划分（`:core:*` → core 等）。依赖边从各模块 `build.gradle.kts` 提取，`api`/`implementation`/`testImplementation` 等配置不作区分——方向规则对所有配置一致生效。

## 解析健壮性（kotlin_scan.py）

依赖与 import 提取由词法级扫描器完成，不用裸正则扫原文：

- 容忍 `project( ":x" )` 空白写法、跨行 `project(...)`、`project(path = ":x")`；
- 行注释 `//`、可嵌套块注释 `/* */`、字符串与字符字面量中的 `project()` / `import` **不会**产生误报或漏报；
- import 行尾注释、`as` 别名不影响判定。

已知边界：星号导入（`import a.b.*`）无法做符号级判定，靠审阅把关；`projects.core.model` 这类
Gradle 访问器写法未使用也未覆盖；全限定名内联引用（不写 import）靠审阅把关。

## 例外登记

确实无法消除的违规登记在 [exceptions.txt](exceptions.txt)，格式：

```
:from:module -> :to:module  # 原因（必填）
```

登记必须与现存依赖边逐条精确对应；依赖消除后条目会产生 WARNING，应及时清理，逐步清零。
例外的新增属于审阅事项——审阅者必须逐条确认例外必要性，门禁不机械阻止登记（与行数基线不同，
依赖例外本质上是过渡性判断），但例外清单变化在 PR diff 中高度可见。

## 模块目录映射

使用 Gradle 默认约定（`:core:model` → `core/model`）。
