# 日在校园 (Day in School)

面向河南工业大学（莲花街校区）的 Android 课表、学业、校园地图与校园生活工具。

项目基于 **Android 12–16 (MinSdk 31 / TargetSdk 36)** 构建，采用 **Kotlin 2.1 + Jetpack Compose + Material 3 + Room + WorkManager + AppWidget (`RemoteViews` + `Chronometer`)** 多模块架构。

---

## 1. 主要功能

- **今天（首页 · 当天课表 + 可定制模块化 Bento 卡片布局）**：
  - 顶部日期栏支持跨周/跨月左右无极连续滑动选择任意日期，并显示每日课程圆点与月份角标。
  - 上半部分聚焦「当天课表卡片」，在课程行内直接高亮「正在上课 / 下一节课」并提供行内一键「去教室」导航按钮与课间换楼提示，支持顶栏一键切换学期。
  - 下半部分采用**可定制模块化 Bento 卡片引擎**：支持用户通过「定制卡片」面板自由调整「近期考试与学业」「自习专注」「宿舍水电」「座位预约」「馆藏检索」「空教室查询」「社团与频道」七大板块的排列顺序、显示/隐藏状态以及「通栏 / 双列」尺寸规格，并支持一键恢复默认布局。
- **课表（多形态课表 + 原始总课表）**：
  - 支持「周网格」「周清单」「原始课表」三种形态一键切换：
    - **周网格**：周一至周日 1–10 节网格视图，支持周次切换、回本周、手动添加课程与颜色修改。
    - **周清单**：按星期展开当周每日课程时间、地点、任课教师与一键教室导航。
    - **原始课表**：展示全学期 7×5 大节原始排课总表与完整原始规则明细（含周次范围、单双周、学分及数据来源）。
- **学业（GPA + 学习与图书馆安排）**：
  - 集成 2×2 **学习与图书馆安排** Bento 矩阵（图书馆选座、馆藏书目检索、自习专注计时、空闲自习教室反查）。
  - 累计/学年/学期平均学分绩点（`BigDecimal` 加权计算）、重修去重、学期趋势折线图与新修学分绩点测算。
  - 考试安排列表（含期初补缓考、期中考试、期末考试），支持一键跳转考场地图，以及成绩与学号隐藏（开启 `FLAG_SECURE` 防截屏）与 CSV 导出。
- **校园（4×4 地图预览模块 + 校园生活中心 + 全屏实时导航）**：
  - 默认以「校园生活与地图中心」呈现：顶部配备 **`4×4` 交互式校园地图小预览模块**（支持实时蓝点、快捷地标一键寻路与展开全屏），中部集成宿舍水电、空教室速查、学生社团与校园频道，底部提供校区建筑分类目录。
  - 点击预览卡或任意建筑「去这里」即可展开全屏矢量地图：实时监听 `FUSED` / `GPS` / `NETWORK` 定位源与方位传感器，内置校内步行路网（A* 寻路），动态计算步行距离、耗时与转向提示，支持调起外部地图导航。
- **我的（精简个人核心数据 + 右上角二级设置菜单）**：
  - **「我的」主页**：仅展示个人必要数据（数字学生证名片、本周课时/考试场次/已获学分/平均绩点 4 维看板、已修学分分类构成、本学期校历进度条、个人宿舍水电与专注时长摘要）。
  - **右上角「设置」二级菜单**：收拢**自定义背景图片主题与 HSV 自由调色盘**（支持上传自定义背景图片并自动提取最多 5 款代表性配色应用到按钮、菜单与卡片背景模板，支持实时调节背景模糊度 `0~30dp`、背景透明度 `10%~100%` 与卡片菜单毛玻璃通透度 `50%~98%`，以及跟随系统/浅色/深色外观、8 款精选主题配色、`360°` 色相/饱和度/明度滑块与 `#RRGGBB` 色号输入）、首页模块化卡片布局管理、**网络代理与云服务加速**（预置 4 条 GitHub 镜像加速线路、自定义反代前缀、HTTP/SOCKS5 代理及 Clash/v2rayNG/Sing-box 快捷端口填入、连通性测速、云服务 API 网关自定义）、上课与考试提醒、学期与法定调休管理、作息时间表微调、桌面小组件配置、隐私安全、GitHub Release 在线检查更新与全量数据清除。
- **教务同步与 `univ-timetable/v1` 课表互通**：
  - 支持河南工业大学统一身份认证（`jwglxt.haut.edu.cn` / `mapp.haut.edu.cn`）直连同步课表、考试安排与成绩。
  - 成绩与考试按服务端分页完整读取；部分响应不会覆盖上次完整缓存。教务密码使用 Android Keystore AES-GCM 保存，旧版 Base64 凭据会在校验后迁移并删除。
  - 支持启动时自动更新课表与考试、WebView 网页登录导入、CSV 文本导入及手动修改冲突保留。
  - 支持 `univ-timetable/v1` 课表文件（`.json` / `.md`）双向导入与脱敏导出，自动拆分不连续节次并通过 `ImportDiffEngine` 预览合并。
- **上课与考试提醒（含「稍后提醒」Snooze）**：
  - 支持课前提醒（提前 5–60 分钟可选）、考前 1 天提醒与考前 1 小时提醒，支持单门课程独立设置提前时间或关闭提醒。
  - 通知栏提供「5 分钟后」「10 分钟后」（考试为「15 分钟后」「30 分钟后」）及「去教室 / 去考场」操作按钮。
  - 基于持久化任务表与 `generation` 代数校验，在关机重启、修改系统时间、调休补课或重导课表后自动更新闹钟计划。
- **校园服务（我的 → 校园服务 2×2 矩阵）**：
  - **宿舍水电**：支持已绑定宿舍在线水电账单查询（Keystore `AES/GCM/NoPadding` 加密存储账号、POST 表单请求体传输、每日低频采样与 7/30 日趋势图）及离线手动水电表记录。
  - **馆藏检索**：对接学校图书馆 OPAC（`opac.haut.edu.cn`），提供移动端版式适配、书目馆藏状态快照提取、条码/索书号明细查看与纯文本复制分享。
  - **座位预约**：对接学校图书馆官方选座系统（`seat.lib.haut.edu.cn`），支持学校统一认证与 WebVPN 会话复用、快速回首页、刷新与清空会话（仅提供官方页面浏览与手动选座，不含自动抢座或代签）。
  - **自习专注**：独立番茄钟/自习倒计时（15 / 25 / 45 / 60 / 90 分钟及自定义时长），具备课前 20 分钟非强制冲突提示、独立闹钟通道（`dayinschool_focus_timer_v1`）、15 分钟恢复静默窗口与今日/累计专注统计。
- **个人中心与校园工具**：
  - 空闲教室速查（按教学楼与节次反查无课教室）、校历调休/补课设置、作息时间微调、校园服务实验开关。
  - 校区社团名录（支持分类搜索、复制群号与唤起手机 QQ 加群）与 QQ 频道快捷入口（支持自定义频道链接）。
  - 检查 GitHub Release 新版本并直接下载安装 APK。
- **桌面小组件（X-Peach Bento 视觉重构 · 教室全量完整展示）**：
  - 提供 `2×2 下一节课`、`4×2 今日课程`、`4×4 日程时间轴`、`4×4 周课表` 四款桌面小组件，采用 X-Peach Bento 胶囊层次与深浅色自适应高对比度配色，并自动同步用户自定义主题强调色。
  - 内置 `WidgetRoomFormatter` 智能剥离冗余校区前缀并为每款小组件配备独立的双行/右对齐教室信息栏，确保教学楼与教室门牌号完整显示、绝不被课程名或时间截断。

### 通用校园基础（实验）

- “我的 → 学校与能力”提供离线学校目录、中文/拼音/英文搜索、校区与逐能力核验状态。当前目录只收录已有实现的河南工业大学，其他学校使用手工课表或文件导入。
- 可创建彼此隔离的离线身份；每个 Profile 使用独立业务数据库和 DataStore。切换身份时会撤销旧网页会话，并取消或重绑提醒、专注、水电 Worker 与桌面组件。
- 九类教务家族目前只是稳定协议类型契约，尚未据此宣称完成九套适配。没有学校授权账号和真机证据时，能力保持 `UNVERIFIED`，不外推为“全国高校已支持”。

---

## 2. 模块结构 (20 Modules)

| 模块路径 | 类型 | 说明 |
| --- | --- | --- |
| `:app` | Android Application | 应用入口、底部五栏导航（今日 / 课表 / 学业 / 校园 / 我的）、全屏二级页面路由、水电周期采样 Worker、启动自动同步与版本更新检查 |
| `:core:model` | Pure Kotlin JVM | 课表、成绩、考试、地图、提醒、水电、自习专注、社团与小组件领域模型 |
| `:core:domain` | Pure Kotlin JVM | 教学周计算、比例时间轴、课间识别、冲突检测、导入差异对比、GPA 计算、提醒计划与自习专注状态机 (`FocusSessionEngine`) |
| `:core:campusservices` | Android Library | 校园服务核心层：HTTPS 域名白名单守卫 (`OfficialServiceRoutePolicy`)、WebView 会话管理 (`SchoolWebSessionManager`)、水电加密存储与协议解析 (`WanxiaoProtocolParser`)、采样策略 (`UtilitySamplingPolicy`)、图书馆 OPAC 适配与馆藏快照提取 (`OfficialLibraryWebAdapter`) |
| `:core:database` | Android Library (Room) | Room 数据库（v4，含 `MIGRATION_1_2` / `MIGRATION_2_3` / `MIGRATION_3_4`）、DAO、DataStore 配置、AES-GCM 凭据加密、`CampusRepository` 与多钩子全量擦除 |
| `:core:reminder` | Android Library | 课前/考前提醒子系统 (`ReminderCoordinator`) 与完全隔离的自习专注闹钟子系统 (`FocusCoordinator` / `AndroidFocusAlarmScheduler`) |
| `:core:designsystem` | Android Library (Compose) | Material 3 主题配色（工大蓝默认主题与 Android 12+ 壁纸动态取色）、通用组件及 `CampusMetricChart` 趋势图组件 |
| `:core:academic` | Android Library | 教务 CAS 登录、课表/考试/成绩抓取、CSV 解析及 `univ-timetable/v1` 双向课表适配器 (`TimetableInterchangeAdapter`) |
| `:core:location` | Android Library | 多源实时定位、手机朝向角监听、校内步行路网 A* 规划与外部地图调用 |
| `:feature:today` | Android Library (Compose) | 今日首页：7 日周历条、当天课表流式卡片（行内高亮与一键去教室）、Bento 混合卡片板块模块（通栏考试窄卡 + 双列六大校园服务卡片） |
| `:feature:timetable` | Android Library (Compose) | 课表页：周视图网格、周次切换、学期切换、手动加课与课程编辑 |
| `:feature:grades` | Android Library (Compose) | 学业页：绩点统计、绩点测算、学期趋势、成绩明细与考试安排列表 |
| `:feature:map` | Android Library (Compose) | 地图页：校区交互地图、实时定位跟随、教室搜索、校内步行路线与转向提示 |
| `:feature:settings` | Android Library (Compose) | 我的页与校园二级页：宿舍水电、馆藏检索、座位预约、自习专注、空教室查询、社团与频道、调休设置、提醒设置、小组件固定与在线更新 |
| `:feature:importer` | Android Library (Compose) | 导入页：教务账号同步、网页登录导入、`univ-timetable/v1` (`.md`/`.json`) 导入与导出、CSV 导入与差异预览 |
| `:widget:common` | Android Library | 小组件数据快照加载与定时刷新调度 |
| `:widget:nextclass` | Android Library | `2×2` 下一节课桌面小组件 |
| `:widget:today` | Android Library | `4×2` 今日课程桌面小组件 |
| `:widget:timeline` | Android Library | `4×4` 日程时间轴桌面小组件 |
| `:widget:week` | Android Library | `4×4` 周课表桌面小组件 |

---

## 3. 架构、安全与开源合规文档

- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) & [`licenses/HAUTPocket-MIT.txt`](licenses/HAUTPocket-MIT.txt)：第三方开源代码许可声明（MIT License）。
- [`docs/HAUTPOCKET_MIGRATION_PROVENANCE.md`](docs/HAUTPOCKET_MIGRATION_PROVENANCE.md)：上游模块复用映射、重写范围与资产排除台账。
- [`docs/CAMPUS_SERVICES_ARCHITECTURE.md`](docs/CAMPUS_SERVICES_ARCHITECTURE.md)：`:core:campusservices` 模块架构、Room v3→v4 迁移与闹钟隔离说明。
- [`docs/CAMPUS_SERVICES_SECURITY_REVIEW.md`](docs/CAMPUS_SERVICES_SECURITY_REVIEW.md)：校园服务安全与隐私边界审计（Keystore 加密、HTTPS 白名单、POST 表单传输与全量擦除闭环）。
- [`docs/CAMPUS_SERVICES_REAL_DEVICE_VERIFICATION.md`](docs/CAMPUS_SERVICES_REAL_DEVICE_VERIFICATION.md)：真机回归验证清单（含冒烟与 26 项功能/安全验收用例）。

---

## 4. 构建与测试

项目通过根目录 [`version.properties`](version.properties) 管理版本号与 SDK 版本：

```bash
# 查看当前版本号与 VersionCode
./gradlew printVersion

# 递增修订号 / 次版本号 / 主版本号
./gradlew bumpPatch
./gradlew bumpMinor
./gradlew bumpMajor

# 运行全部单元测试并构建 Debug / Release APK
./gradlew testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

### GitHub Actions
- [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)：推送或 PR 时运行单元测试并构建 Debug / Release APK。
- [`.github/workflows/android-release.yml`](.github/workflows/android-release.yml)：推送 `v*.*.*` 标签或手动触发时运行测试、签名打包 Release APK 并发布到 GitHub Releases。
