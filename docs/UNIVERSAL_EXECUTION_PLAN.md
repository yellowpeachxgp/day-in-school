# Universal 总蓝图执行计划与状态台账

更新日期：2026-11-02（Asia/Shanghai）。本文件是长期执行的**唯一状态源**：任何“已完成”结论都必须能在这里找到对应的源码、测试与证据位置；没有真实设备或学校授权回读证据的能力一律留在 `UNVERIFIED`（或最高 `MOCK_TESTED` / `SOURCE_CONFIRMED`）。

## 0. 合并与交付策略

- 集成分支：`codex/universal-campus`。`main` 在全部目标完成并通过门禁及维护者批准前**保持不变**（当前仍为 `fc2adb1`，`v0.11.3`）。
- 未经过对应真实学校授权账号与真机验证的适配器与能力在代码与界面中均显式标注 `UNVERIFIED`，不得在 README/Release 中描述为“已在某真实学校支持”。
- 保留 `v0.11.1`（四款小组件重构、模块化卡片布局、主题调色盘）、`v0.11.2`（网络代理、预设镜像、云服务网关设置）与 `v0.11.3`（自定义背景图主题、自动取色、模糊度与透明度）全部既有工大功能零回归。

## 1. 事实基线与当前集成结果

| 项目 | 值 |
|---|---|
| `main` 基线 commit | `fc2adb1`（`v0.11.3` / `versionCode = 1103`） |
| 集成分支最新进展 | `38c5ccd`（多身份隔离与 Room `v4→v5`）→ `1653dd2`（协议合同内核）→ `60320a5`（`:core:academic-plan`）→ `5cf0dd2`（`:core:selection`）→ `3fe9064`（`:core:school-packages`）→ `bdbf023`（`:core:academic-protocols` 九协议）→ `21bd2ac`（校园服务与地图 Provider 解耦）→ `055e40d`（Feature 与 App 消费者接入） |
| 模块数 | 24（原 20 + `:core:academic-protocols`、`:core:school-packages`、`:core:academic-plan`、`:core:selection`） |
| 自动化测试结果 | **52 个测试套件、393 项测试全部通过（0 失败）** |
| Lint 检查 | `:app:lintDebug` 与 `:app:lintRelease` 均通过（0 错误） |
| 构建产物与 SHA-256 | `app-debug.apk`: `32022867cecefc9aa1a6e908d3e9ed49cdbcb086c31ddd91877cfc4ba1360186`<br>`app-release-unsigned.apk`: `8b427999b8271892ef271d529e3e40332e2ab92a5ebdc2a6c8087b0eb79de20b` |
| 工具链 | JDK 21 / Kotlin 2.1.10 / AGP 8.8.2 / Gradle 8.14 / Room 2.6.1 |
| 设备与外校实测证据 | 本地 `adb devices` 无连接真机，且无非工大授权教务账号，故任何 `DEVICE_TESTED` 与新学校 `SCHOOL_VERIFIED` 均**保持未达成（UNVERIFIED）** |

## 2. 架构分层与测试统计

| 模块 | 职责 | 测试套件 / 用例数 | 验证等级边界 |
|---|---|---|---|
| `:core:model` | 通用学校/校区/账号/能力契约 + 严格 JSON 值模型与规范化 SHA-256 摘要 | 2 套件 / 10 项 | `MOCK_TESTED` |
| `:core:domain` | 排课时间流、GPA 引擎、专注引擎、提醒领域、离线学校目录 `SchoolDirectory` | 6 套件 / 33 项 | `MOCK_TESTED` |
| `:core:academic-protocols` | 九类教务协议只读网关（`ZF_MODERN`、`ZF_LEGACY`、`QZ_MODERN`、`QZ_LEGACY`、`JINZHI`、`CHENGFANG`、`KINGOSOFT`、`EAMS`、`CHAOXING_ACADEMIC`）、90+ 合成夹具、`ProtocolGatewayRegistry`、九项合同套件 `GatewayContractSuite` | 11 套件 / 42 项（含 9×9=81 项合同检查） | `MOCK_TESTED`（合成夹具合同验证，协议实现标 `UNVERIFIED`） |
| `:core:school-packages` | `dis-campus-plugin/v1` 清单编解码、Ed25519 纯算法验签、ZIP 路径安全策略、原子安装与回滚、HTTPS 来源/路径前缀授权域 `PermissionScope`、通用学校目录检索 | 8 套件 / 83 项 | `MOCK_TESTED` |
| `:core:selection` | 选课轮次、教学班、半开区间冲突检测、三态容量、按学校/账号/学期隔离的离线草稿、受控提交状态机与幂等账本、官方回读核对 | 6 套件 / 88 项 | `MOCK_TESTED`（无已验证写接口时在线提交默认禁用） |
| `:core:academic-plan` | 重修/补考去重 `AttemptAggregator`、成绩换算、成绩分项解析、培养方案树、毕业学分分组进度 `DegreeProgressCalculator`、绩点模拟、`OFFICIAL`/`DERIVED`/`ESTIMATED` 严格可信分级 | 7 套件 / 72 项 | `MOCK_TESTED` |
| `:core:academic` | 河南工业大学正方教务解析、CAS 边界、完整分页与稳定复合键 | 4 套件 / 33 项 | `SOURCE_CONFIRMED` / `MOCK_TESTED` |
| `:core:campusservices` | `CampusServiceProviderRegistry`（馆藏 `LibraryCatalogProvider`、选座 `LibrarySeatProvider`、水电 `UtilityServiceProvider`）及工大实现与跨校隔离 | 1 套件 / 8 项 | `SOURCE_CONFIRMED` / `MOCK_TESTED` |
| `:core:location` | `CampusMapProvider` / `CampusMapProviderRegistry` 与工大莲花街 GIS 路网实现 | 1 套件 / 1 项 | `SOURCE_CONFIRMED` / `MOCK_TESTED` |
| `:core:database` | `GlobalProfileRegistry`、`AccountDatabaseFactory`、`AccountSessionCoordinator`、Keystore AES-GCM 凭据库、Room `v4→v5` 迁移与 adoption journal | 5 套件 / 20 项 | `MOCK_TESTED`（Robolectric SQLite 迁移验证） |
| `:core:reminder` | 提醒协调、Snooze 与账号世代绑定 | 1 套件 / 3 项 | `MOCK_TESTED` |

## 3. 七项目标完成情况与真实边界

| # | 目标 | 代码与单测状态 | 关键源码与测试位置 | 诚实证据边界（未达成项） |
|---|---|---|---|---|
| 1 | 九类教务协议真实适配 | 已完成源码与合同测试（`bdbf023`） | `core/academic-protocols/src/main/kotlin/cn/edu/haut/campus/core/protocol/**`、`NineProtocolMatrixContractTest.kt`（11 套件 / 42 测试通过） | 所有九协议网关均通过合成夹具九场景合同测试（`MOCK_TESTED`），无外校真实授权账号与线上回读，全部显式标注 `UNVERIFIED` |
| 2 | 学校配置与贡献生态 | 已完成源码与攻击向量测试（`3fe9064`、`055e40d`） | `core/school-packages/src/main/kotlin/cn/edu/haut/campus/core/packages/**`、`feature/settings/SchoolCenterScreen.kt`（8 套件 / 83 测试通过） | 伪签名、篡改、路径穿越、权限扩大、回滚五类攻击单测已通过；尚未接入远端插件仓库在线分发 |
| 3 | 选课业务系统 | 已完成领域层与离线沙盒 UI（`5cf0dd2`、`055e40d`） | `core/selection/src/main/kotlin/cn/edu/haut/campus/core/selection/**`、`feature/grades/GradesScreen.kt`（6 套件 / 88 测试通过） | 离线选课草稿、半开区间冲突检测与受控状态机已接入“学业 → 选课草稿”；因无已核验学校写接口，在线提交门控保持 `ROUND_NOT_WRITABLE` 禁用 |
| 4 | 完整学业画像 | 已完成领域层与培养进度 UI（`60320a5`、`055e40d`） | `core/academic-plan/src/main/kotlin/cn/edu/haut/campus/core/plan/**`、`feature/grades/GradesScreen.kt`（7 套件 / 72 测试通过） | 重修去重、分项透传、毕业进度与 `OFFICIAL`/`DERIVED`/`ESTIMATED` 标签已接入“学业 → 培养进度”；未导入官方培养方案时显式标为 `估算 · UNVERIFIED` |
| 5 | 校园服务全面解耦 | 已完成 Provider 抽象与非工大回退（`21bd2ac`、`055e40d`） | `core/campusservices/.../CampusServiceProviderRegistry.kt`、`core/location/.../CampusMapProvider.kt`、`feature/map/CampusMapScreen.kt` | 工大莲花街地图、馆藏、选座、水电已封装为独立 Provider；非工大身份下自动隐藏工大专属服务并展示能力状态说明 |
| 6 | 系统级体验 | 已完成多身份隔离与首启选校引导（`38c5ccd`、`055e40d`） | `app/src/main/kotlin/cn/edu/haut/campus/MainActivity.kt`、`feature/settings/SchoolCenterScreen.kt`、`widget/**` | 首启欢迎页支持切换学校与离线建档；小组件/提醒/专注绑定 `profileId + generation`；200% 大字体、TalkBack 与锁屏真机表现仍待连接真机实测（`UNVERIFIED`） |
| 7 | 全量测试与交付审计 | 已完成本地全量单测、双 Lint 与双 APK 构建 | 52 套件 393 项单测、`:app:lintDebug`、`:app:lintRelease`、`:app:assembleDebug`、`:app:assembleRelease` | 本地无正式发版签名密钥（产出 `app-release-unsigned.apk`），无物理设备 `adb` 连接，故真机升级与正式签名发布留在维护者环境执行 |

## 4. 合并 `main` 前仍需维护者环境补充的真机/学校门禁

1. **真机升级与 UI 验收（`DEVICE_TESTED` 待补）**：需在连接 Android 12–16 物理设备后，验证旧版 APK 覆盖安装 `v4→v5` 迁移、双账号小组件并排展示、精确闹钟与 TalkBack/200% 字体表现。
2. **学校级实测（`SCHOOL_VERIFIED` 待补）**：除工大既有实现外，其余协议网关仅完成合成夹具合同验证（`MOCK_TESTED`），在取得目标学校授权账号实测前继续保持 `UNVERIFIED`。
3. **正式签名发布**：需在配置正式签名密钥的发布环境中执行签名构建与证书指纹核验。
