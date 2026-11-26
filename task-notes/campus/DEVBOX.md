# DEVBOX（开发机基线与资源预算）

> 保密边界：本文件只写 `<devbox>` 或 `$CIS_DEVBOX`，不得写真实开发机地址、账号、密码、Token、Key。

## 当前状态

- 主机：`<devbox>`（仅通过本机环境变量 `$CIS_DEVBOX` 访问）。
- 2026-11-26 复核：`cis-gate` 已安装且可执行；两仓裸库已存在；INF-00 的 main 基线门禁结果见 `BASELINE.md`。
- 开发机不存放 GitHub 凭据；两个公开仓库只读拉取。

## 目录约定

| 用途 | 路径 |
|---|---|
| 校园项目根目录 | `<campus-root>/` |
| 裸库 | `<campus-root>/repos/<仓库>.git` |
| 构建 worktree | `<campus-root>/build/<仓库>/<分支>` |
| 缓存 | `<campus-root>/cache/gradle`、`<campus-root>/cache/npm` |
| Android SDK | `<campus-root>/android-sdk/` |
| 脚本 | `<campus-root>/bin/` |
| 锁 | `<campus-root>/locks/` |
| 日志 | `<campus-root>/logs/` |
| 产物 | `<campus-root>/artifacts/` |

## 工具版本（2026-11-26 只读检查）

| 工具 | 版本 | 备注 |
|---|---|---|
| OS | Debian GNU/Linux 13 (trixie)，Linux 6.12.116+deb13-amd64 | 只读记录 |
| git | 2.47.3 | 只读记录 |
| Node | campus-local v22.23.5；system v24.22.3 | `cis-gate` 优先使用 campus-local Node 22，满足 cloud `>=22`；system Node 仅作环境事实记录 |
| Java | OpenJDK 21.0.13.1 | 只读记录 |
| Docker | 29.10.0 | 校园容器只用 `cis-` 前缀 |
| Android cmdline-tools | 22.0 | `cmdline-tools/latest/source.properties` |
| Android platform | android-36 | 已安装 |
| Android build-tools | 35.0.0、36.0.0 | 已安装 |

## 脚本状态

| 脚本 | 状态 | 备注 |
|---|---|---|
| `cis-gate` | 已安装 | 门禁日志写入校园 logs 目录；只回传退出码、SHA、耗时、日志路径和末尾摘要 |
| `cis-gradle` | 已安装 | 4 个 flock 构建槽、内存守护、PAUSE 检查；并发验收仍属 INF-00 剩余项 |
| `cis-containers` | 已安装 | 只起停 `cis-*` 容器，绑定 loopback 随机端口 |
| `cis-clean` | 已安装 | 只清理校园目录 |
| `cis-worktree` | 已安装 | 本机侧脚本；地址只从 `$CIS_DEVBOX` 读取 |

## 资源预算

- 校园项目内存合计不超过 40GB；重型构建同时最多 4 个。
- 构建前 `MemAvailable` 低于 24GB 时等待；存在校园 `PAUSE` 文件时不启动新构建。
- 单构建优先限制为 10GB；Gradle JVM 参数按 MASTER_PLAN §12.3。
- 模拟器同时最多 1 个、内存 4GB；阶段 S 以 Roborazzi JVM 截图为主。
- 云端测试容器统一 `cis-<仓库>-<分支短名>-<用途>`，结束含失败都按名字精确清理。
- 校园目录超过 150GB 时，由一个子代理清理过期构建 worktree 和日志。

## 基线门禁

| 仓库/分支 | 门禁 | 结果 | 日志 |
|---|---|---|---|
| day-in-school `main` | `android` | 通过：退出码 0，321 秒，MinMemAvailableKB 73076276，host-wide peak used 58792440 KB | `<campus-root>/logs/day-in-school/main/05eeff879bad40bf22dcee1d4bc4073151a5f8ad-android.log` |
| cloud-in-school `main` | `cloud` | 通过：退出码 0，14 秒，MinMemAvailableKB 85227192，host-wide peak used 46641524 KB | `<campus-root>/logs/cloud-in-school/main/ee8687da02b36cf703c37a4891c3190dd43233cc-cloud.log` |
| 构建槽并发与 PAUSE | INF-00 专项验收 | 待 INF-00 交回 | 待补 |
