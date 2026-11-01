# 通用校园改造：源码与验证基线

核查日期：2026-11-02（Asia/Shanghai）。本文件记录真实执行结果和待验边界，不表示全国高校通用版已经完成。

## 源码事实

| 项目 | 核查结果 |
|---|---|
| 当前起点 | `fc2adb1`（`main` / `origin/main` / `v0.11.3`） |
| 应用版本 | `0.11.3`，`VERSION_BUILD=1103` |
| 蓝图引用的旧起点 | `b223a7d09dba9579f367d360c9cc5d698ea9c7d6`，`v0.11.1`；现仓库另含网络代理和背景图片功能 |
| 集成分支 | `codex/universal-campus` |
| 模块 | 20 个；2 个纯 Kotlin 模块，18 个 Android 模块 |
| Gradle / JDK | Wrapper `8.14`，JDK `21`；本机使用 Homebrew `21.0.13.0.3` |
| Kotlin / KSP / AGP | `2.1.10` / `2.1.10-1.0.31` / `8.8.2` |
| Android | `minSdk=31`、`compileSdk=36`、`targetSdk=36` |
| Room | `2.6.1`；改造前数据库 v4，21 张实体表，原配置不导出 schema |
| 上游研究范围 | `znjhahaha/zhengfang-apk` 固定 SHA `6fb290f63d4b930f063a9342ec4f0b3485499054`；GitHub API `/commits/main` 与 `/releases/latest` 返回稳定版 `v1.0.131` |

Gradle 8.14 可在 JDK 21 上运行，现有 JVM 21 编译目标实际通过。AGP 8.8.2 对 API 36 的官方验证范围不足，仓库已有 `android.suppressUnsupportedCompileSdk=36`；本次保留工具链以控制迁移变量，不把抑制提示等同兼容性保证。后续升级 AGP 应单独执行编译、Lint、打包与设备回归。

## 已执行的起点验证

在任何实体表改动之前执行：

```bash
./gradlew :core:domain:test testDebugUnitTest :app:assembleDebug
./gradlew :core:domain:test testDebugUnitTest :app:lintDebug :app:lintRelease --rerun-tasks
```

- 首轮 Debug APK 构建成功。
- 强制重跑的 11 个测试类共 **54 项测试，0 失败、0 错误、0 跳过**。
- 应用 Debug 与 Release Lint 均 **0 错误**。Debug 55 警告、1 信息；Release 38 警告、1 信息。多数为依赖升级建议，另含 `DefaultLocale`、`UnusedAttribute`、`DataExtractionRules` 和 `AutoboxingStateCreation`。
- `--rerun-tasks` 质量门禁实际执行 1286 个任务，耗时 9 分 4 秒。
- 当前 `adb devices -l` 没有连接设备，无法标注 `DEVICE_TESTED` 或 `SCHOOL_VERIFIED`。
- 旧 `ReminderDatabaseMigrationTest` 只校验实体映射和版本号，没有执行 SQLite 迁移；其通过不构成无损升级证据。

本机日志位于 `build/reports/universal-baseline-build.log` 与 `build/reports/universal-baseline-quality.log`，不纳入源码。运行时间以机器记录为准，文件中的验证日期以会话时区为准。

## 集成分支阶段验证

在源码冻结后，`codex/universal-campus` 工作区按顺序执行了以下门禁：

```bash
git diff --check
./gradlew --no-daemon :core:model:test :core:domain:test \
  :core:academic:testDebugUnitTest :core:database:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:lintRelease
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease
```

- 定向单测共 **89 项**，0 失败、0 错误、0 跳过：model 3、domain 33、academic 33、database 20。
- `core:reminder`、`feature:settings`、`feature:timetable`、四款 widget 与应用最终编译通过。
- Debug Lint 为 **0 error / 54 warning**；Release Lint 为 **0 error / 37 warning**。
- Debug 与 Release 构建成功。由于本机没有正式签名材料，Release 按设计只生成未签名 APK，不回退调试证书。
- Debug APK（65,471,428 bytes）SHA-256：`8d742f1eaee5676dd6f85e10297e072a602fa5bdbc0ae83fc7d5a8577b8a49ba`。
- 未签名 Release APK（47,036,568 bytes）SHA-256：`807a43ceeac9a6dbe1878b61b8cdd8522a0ba7427eddf86ecb834d3b01dcc8a6`。

这些结果证明本工作区的源码、合成迁移和本地 APK 构建通过；不证明正式签名发行、真机升级或学校授权账号实测通过。

## 本次质量与发行修复

- Android CI 覆盖 `:core:model:test`、`:core:domain:test` 和全部 `testDebugUnitTest`，补上以前遗漏的数据库、校园服务、提醒测试；增加全模块 `lintDebug` / `lintRelease`，失败时仍保留报告。
- 从 Gradle `printVersion` 读取统一版本，尊重 `VERSION_BUILD`；手动发行必须检出指定标签并校验标签与版本一致。
- 移除源码中的默认签名口令与 Release 调试签名回退。不提供签名配置时只生成 `app-release-unsigned.apk`；不再因为磁盘恰好存在某个 `.jks` 文件便自动使用它。
- 发行工作流要求完整签名配置，执行 `apksigner verify`，比较 APK 与提供的密钥证书 SHA-256，拒绝 Android Debug 证书，保存签名报告、APK SHA-256、精确源码 SHA、版本与许可证包。
- Gradle 分发包校验值来自官方 `gradle-8.14-all.zip.sha256`，固定在 Wrapper 配置中。
- 在更改数据库结构前由 KSP 导出 v4 schema；v5 仅增加待排课程表，不是账号隔离迁移。v4/v5 schema 均纳入版本控制。

正式签名通过环境变量或 Gradle 属性提供：`SIGNING_STORE_FILE`（可选，默认 `keystore/haut-release.jks`）、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。任何一个显式配置存在时，必须完整有效，否则构建停止。不得在工单、提交、日志中粘贴这些值。此前已经暴露在历史源码中的口令不会被本次删除自动撤销；维护者应在不破坏既有 APK 升级签名连续性的前提下轮换相关保护口令。

## 可重复执行的集成门禁

本机已经安装的工具链通过以下环境变量选择，无需改写系统全局设置：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME='/Volumes/SN 770/dev-caches/android-sdk'
./gradlew --no-daemon :core:model:test :core:domain:test testDebugUnitTest
./gradlew --no-daemon lintDebug lintRelease
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease
```

测试与构建顺序执行，避免多个 Gradle 进程同时写同一工作目录的 Kotlin 增量缓存。需要排除测试缓存时使用 `--rerun-tasks`；不在其他代理写代码期间清理其输出目录。

## 尚未通过的项目级验收

- Robolectric SQLite 迁移只验证 Android JVM 模拟环境下的合成数据，不代替真实用户 v0.11.1/v0.11.3 升级、真机 Keystore、系统备份/恢复和异常关机测试。
- 没有执行学校真实账号登录、课表/成绩/考试官方回读比对、国产 ROM 提醒、四款桌面组件、TalkBack/200% 字体或性能电量验收。
- 未提供九类教务协议合同测试、插件沙箱/签名目录、SBOM、依赖漏洞审计和全量资产许可证审查。本轮已加入独立的学校/Profile 数据库与 DataStore 分区，但仍需设备级账号切换、通知、组件和失败恢复验收。
- 本地 unsigned Release 构建通过不代表正式签名、发行流水线或升级安装已验证。只有 CI/发行运行和设备证据实际存在后才能提高验证等级。
- 这次开发分支的推送不等于蓝图全量交付，不应合并成宣称全国高校已适配的正式版本。
