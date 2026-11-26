# DEVBOX（开发机基线与资源预算）

> 保密边界：本文件只写 `<devbox>` 或 `$CIS_DEVBOX`，不得写真实开发机地址、账号、密码、Token、Key。

## 当前状态

- 主机：`<devbox>`（仅通过本机环境变量 `$CIS_DEVBOX` 访问）。
- 2026-11-26 检查：`cis-gate` 尚未安装/可执行，INF-00 仍在 bootstrap；本文件的现场门禁结果均为「待补」。
- 开发机不存放 GitHub 凭据；两个公开仓库只读拉取。

## 目录约定

| 用途 | 路径 |
|---|---|
| 校园项目根目录 | `/srv/xpeach-dev/campus/` |
| 裸库 | `/srv/xpeach-dev/campus/repos/<仓库>.git` |
| 构建 worktree | `/srv/xpeach-dev/campus/build/<仓库>/<分支>` |
| 缓存 | `/srv/xpeach-dev/campus/cache/gradle`、`/srv/xpeach-dev/campus/cache/npm` |
| Android SDK | `/srv/xpeach-dev/campus/android-sdk/` |
| 脚本 | `/srv/xpeach-dev/campus/bin/` |
| 锁 | `/srv/xpeach-dev/campus/locks/` |
| 日志 | `/srv/xpeach-dev/campus/logs/` |
| 产物 | `/srv/xpeach-dev/campus/artifacts/` |

## 工具版本（2026-11-26 只读检查）

| 工具 | 版本 | 备注 |
|---|---|---|
| OS | Debian GNU/Linux 13 (trixie)，Linux 6.12.116+deb13-amd64 | 只读记录 |
| git | 2.47.3 | 只读记录 |
| Node | v24.22.3 | 满足 cloud `>=22`，但 INF-00 需确认项目目标 Node 22 的可用性 |
| Java | OpenJDK 21.0.13.1 | 只读记录 |
| Docker | 29.10.0 | 校园容器只用 `cis-` 前缀 |
| Android SDK | 待补 | INF-00 安装后记录 platform/build-tools 精确版本和校验值 |

## 脚本状态

| 脚本 | 状态 | 备注 |
|---|---|---|
| `cis-gate` | 待补 | INF-00 安装；门禁日志写入校园 logs 目录 |
| `cis-gradle` | 待补 | 4 个 flock 构建槽、内存守护、PAUSE 检查 |
| `cis-containers` | 待补 | 只起停 `cis-*` 容器，绑定 loopback 随机端口 |
| `cis-clean` | 待补 | 只清理校园目录 |
| `cis-worktree` | 待补 | 本机侧脚本；地址只从 `$CIS_DEVBOX` 读取 |

## 资源预算

- 校园项目内存合计不超过 40GB；重型构建同时最多 4 个。
- 构建前 `MemAvailable` 低于 24GB 时等待；存在校园 `PAUSE` 文件时不启动新构建。
- 单构建优先限制为 10GB；Gradle JVM 参数按 MASTER_PLAN §12.3。
- 模拟器同时最多 1 个、内存 4GB；阶段 S 以 Roborazzi JVM 截图为主。
- 云端测试容器统一 `cis-<仓库>-<分支短名>-<用途>`，结束含失败都按名字精确清理。
- 校园目录超过 150GB 时，由一个子代理清理过期构建 worktree 和日志。

## 基线门禁

| 仓库 | 门禁 | 结果 | 日志 |
|---|---|---|---|
| day-in-school | `android` | 待补（INF-00 未完成） | 待补 |
| cloud-in-school | `cloud` | 待补（INF-00 未完成） | 待补 |
| 两仓 | `secrets` | 待补（INF-01 自举后需现场重跑） | 待补 |
