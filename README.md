# 河南工业大学校园助手 (HAUT Campus Assistant)

> **产品北极星：打开手机就知道下一节课何时、何地；打开 App 就能看懂整个学期的课程与学业表现。**

本项目按照《[河南工业大学校园助手 — V0.1 产品与技术开发规格](docs/HAUT_Campus_Assistant_V0.1_PRD_Tech.md)》构建，全面面向 **最新 Android 平台（CompileSdk 36 / TargetSdk 36 · Android 16，MinSdk 31 · Android 12）**，采用原生 **Kotlin 2.1 + Jetpack Compose + Material 3 + Room + AppWidget (`RemoteViews` + `Chronometer`)** 多模块架构实现。

---

## 1. 最新 Android (Android 12–16 / API 31–36) 原生特性支持

- **TargetSdk 36 (Android 16) 全屏沉浸式 Edge-to-Edge**：通过 `enableEdgeToEdge()` 原生适配系统状态栏与手势导航栏留白。
- **Android 14–16 预测式返回手势 (`enableOnBackInvokedCallback="true"`)**：配合 Compose `BackHandler` 实现页面与底部栏平滑手势回退。
- **Material You (Monet) 动态壁纸取色与河工大暖橙主题双模式**：可在「设置」页随时切换 Android 12–16 系统级动态壁纸色彩或河工大定制暖橙理性主题。
- **Android 12–16 原生一键固定桌面小组件 (`AppWidgetManager.requestPinAppWidget`)**：无需返回桌面长按寻找，在「设置」页即可一键将 `2×2 下一节课`、`4×2 今日课程`、`4×4 时间轴`、`4×4 周课表` 固定到手机主屏。
- **零后台轮询省电桌面小组件**：使用系统级 `Chronometer` (`SystemClock.elapsedRealtime()`) + 课程起止时刻非精确 `AlarmManager` 窗口触发，无常驻后台 Service。
- **全自动课间识别引擎**：
  - **连堂课内小课间**：自动识别如 `1-2节` (`08:30–10:05`) 内部的 `09:15–09:20`（5 分钟小课间）、`9-10节` 内部的 `20:15–20:25`（10 分钟小课间）。
  - **跨课程大课间与换楼提醒**：自动识别如 `2-3节` 之间 `10:05–10:25`（20 分钟大课间）、`4-5节` 之间 `12:00–14:30`（午间休息），并自动检测前后两节课是否需要跨教学楼换教室（如 `莲4号教学楼321 → 文科组团楼131`）。

---

## 2. 模块化工程结构 (18 Modules)

| 模块路径 | 类型 | 职责说明 |
| --- | --- | --- |
| `:core:model` | Pure Kotlin JVM | 领域模型（`Term`、`PeriodTime`、`Course`、`CourseScheduleRule`、`OccurrenceOverride`、`InternalPeriodBreak`、`ScheduleBreakInterval`、`GradeAttempt`、`Building` 等） |
| `:core:domain` | Pure Kotlin JVM | 纯业务算法内核：教学周映射、比例时间轴计算 `y = (t - timelineStart) * pxPerMinute`、课内小课间与跨课大课间自动识别、冲突分栏、导入差异引擎、GPA `BigDecimal` 精确加权引擎、教室楼宇别名解析 |
| `:core:database` | Android Library (Room) | 13 张 Room 数据表、DAO、DataStore 偏好设置、`CampusRepository` 及莲花街校区真实作息与离线种子数据 |
| `:core:designsystem` | Android Library (Compose) | 极简理性中性配色 + 暖橙/桃色强调色 + Android 12–16 Material You 动态色主题 |
| `:core:academic` | Android Library | 河南工业大学统一身份认证 CAS (`jwglxt.haut.edu.cn/sso/jhlogin` ↔ `mapp.haut.edu.cn/cas/login`) 实网直连抓取、WebView 授权抓取、独立 `kbList`/`items` JSON 与 CSV 解析器 |
| `:core:location` | Android Library | 莲花街校区建筑坐标与系统/外部地图步行导航拉起 |
| `:feature:today` | Android Library (Compose) | **今天**：下一节课核心卡片、跨课大课间与换楼提醒、按真实时刻比例绘制的日时间轴、前台生命周期感知的实时时间线（`● 现在`）、单日停课覆盖 |
| `:feature:timetable` | Android Library (Compose) | **课表**：周一至周日完整网格、跨节次纵向合并、小课间/大课间标注、冲突课程并列提示、周次切换、回本周、手动加课与课程编辑保护 |
| `:feature:grades` | Android Library (Compose) | **学业**：累计/学期/学年 GPA 看板（严格区分官方/教务课程绩点加权/不足以计算）、学期趋势折线图、重修去重解释、防截屏隐私保护与 CSV 导出 |
| `:feature:map` | Android Library (Compose) | **校园**：莲花街校区建筑相对方位图、教室字符串解析、模糊匹配确认弹窗与外部步行路线导航 |
| `:feature:importer` | Android Library (Compose) | **导入**：统一身份认证实网直连同步、教务 WebView 登录、同步差异预览（新增/更新/保留用户修改冲突）、CSV 本地导入与导入审计 |
| `:feature:settings` | Android Library (Compose) | **设置**：学期切换、完整 1–10 节作息时间表自定义、一键固定桌面小组件、Material You 动态色切换、个人数据与会话一键彻底清除 |
| `:widget:common` | Android Library | App 与四款桌面小组件共用的 `ScheduleSnapshotUseCase` 与非精确 `AlarmManager` 课时边界刷新调度器 |
| `:widget:nextclass` | Android Library | **2×2 下一节课 Mini**：基于 `SystemClock.elapsedRealtime()` 的系统级 `Chronometer` 倒计时与窄屏自适应布局 |
| `:widget:today` | Android Library | **4×2 今日课程 Today**：今日日期、教学周、当前/下节课高亮与随后两节课 |
| `:widget:timeline` | Android Library | **4×4 日程时间轴 Timeline**：左侧时间刻度、竖向时间轴与今日课程状态进度 |
| `:widget:week` | Android Library | **4×4 周课表 Week**：周一至周日简化周视图与当天强调色 |
| `:app` | Android Application | 主入口、Edge-to-Edge、预测式返回手势、底部四栏导航、小组件广播注册与 `FLAG_SECURE` 隐私控制 |

---

## 3. 版本号管理与 CI/CD 工作流

项目通过根目录 [`version.properties`](version.properties) 集中管理语义化版本与 Android SDK 版本：

```bash
# 查看当前解析出的版本号与 VersionCode
./gradlew printVersion

# 自动递增修订号 / 次版本号 / 主版本号（自动重算 VERSION_BUILD）
./gradlew bumpPatch
./gradlew bumpMinor
./gradlew bumpMajor

# 运行单元测试并构建已签名的 Release APK 与 Debug APK
./gradlew :core:domain:test :core:academic:testDebugUnitTest :app:assembleRelease :app:assembleDebug
```

### GitHub Actions 工作流
- **`.github/workflows/android-ci.yml`**：每次推送或 PR 到 `main` 分支时自动运行单元测试、编译 Debug & Release APK 并上传构建产物。
- **`.github/workflows/android-release.yml`**：当推送 `v*.*.*` 标签（如 `v0.1.0`）或手动触发时，自动提取标签版本号、运行全量测试、使用签名密钥打包 V1/V2/V3/V4 签名 Release APK，并自动发布到 GitHub Releases。
