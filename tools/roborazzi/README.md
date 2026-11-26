# Roborazzi 截图测试（W0-07b）

本目录记录 Android Roborazzi 接入约定。当前只启用 `:app` 单模块 smoke；业务 UI 不在本卡改动，后续 feature/widget 截图应复用这里的目录与录制策略。

## 技术基线评估

- **Roborazzi 1.79.0**：选用接入时的 Maven Central 稳定版；需要其 `separateOutputDirs`，避免多个变体/测试任务共享输出目录产生竞态，并为后续多模块扩展预留隔离。
- **Robolectric 4.17**：main 已在使用 4.17；该版本支持 SDK 37，覆盖项目 `compileSdk` / smoke 使用的 API 36。接入本卡不额外升级到 4.18.x，避免把运行时升级和截图链路接入混在一起。
- **Native Graphics 与 API 36**：smoke 固定 `@GraphicsMode(NATIVE)`、`@Config(sdk = [36], qualifiers = Pixel5)`，并启用 `robolectric.pixelCopyRenderMode=hardware`。
- **字体一致性**：Roborazzi/Robolectric 不保证跨 macOS、Linux、Windows 逐像素一致。因此基线只允许在 devbox Linux 录制并提交；devbox 与 GitHub Ubuntu runner 只做 verify/compare，不用本机 macOS 录制。若未来 Debian/Ubuntu 字体包差异导致文字 diff，先查看 compare 产物并固定测试字体或 CI 字体包，不得直接放宽阈值。
- **isIncludeAndroidResources**：`:app` 已启用 `unitTests.isIncludeAndroidResources = true`，smoke 复核该配置能与 Native Graphics 一起工作。

## 目录约定

`separateOutputDirs.set(true)` 后，Debug 变体使用独立目录：

| 用途 | 路径 | 是否入库 |
|---|---|---|
| golden/reference 基线 | `app/src/test/roborazzi/debug/` | 是 |
| verify/compare 当前图与 diff | `app/build/outputs/roborazzi-compare/debug/` | 否 |
| Gradle 中间缓存 | `app/build/intermediates/roborazzi/debug/` | 否 |
| HTML/测试结果 | `app/build/reports/roborazzi/`、`app/build/test-results/roborazzi/` | 否 |

## 在 devbox 录制基线

录制必须对应一个已提交、已推送的精确 SHA。使用仓库内助手，不在命令或脚本中写真实开发机地址：

```bash
tools/roborazzi/devbox-record feat/cis-w0-07b-roborazzi :app
git status --short app/src/test/roborazzi/debug
```

脚本行为：推送分支到 devbox 裸库；先通过 `cis-gate quick` 物化并编译该 SHA；再在 devbox 的 `cis-gradle` 资源守卫下运行 `:app:recordRoborazziDebug`；最后只把 `src/test/roborazzi/debug/` 基线同步回本机 worktree。录制是基线作者流程，不替代验证门禁。

## 验证门禁

本机不要运行 Gradle。提交并推送后使用：

```bash
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school <branch> android
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school <branch> android-full
ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school <branch> secrets
```

`cis-gate android/android-full` 检测到模块构建脚本中的 Roborazzi 后会执行根任务 `verifyRoborazziDebug`。托管 CI 也执行同一任务并上传 compare/report/test-result 产物。

## 故意失败验证

验证负样本时应使用一次性分支或一次性提交：改动 smoke 中的颜色/文案，推送 devbox，运行 `android` 门禁，确认 `verifyRoborazziDebug` 非零且 compare 目录出现 `*_actual` / `*_compare` 产物。验证后恢复；不要为了掩盖真实回归而重录基线。

## 扩展到后续模块

1. 只在需要截图的模块应用 `alias(libs.plugins.roborazzi)`。
2. 复制 `:app` 的 `roborazzi` 目录配置，保持 golden 在 `src/test/roborazzi/<variant>/`、compare 在 `build/outputs/roborazzi-compare/<variant>/`。
3. 测试类继续使用 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`、固定 SDK/设备、固定文案与确定性状态。
4. 新基线一律在 devbox 录制、审阅 PNG 后提交；禁止把本机或未知 runner 的截图当作权威基线。
