# 日在校园 (Day in School)

面向河南工业大学（莲花街校区 / 嵩山路校区）的 Android 课表、学业、校园地图与校园生活工具。

项目基于 **Android 12–16 (MinSdk 31 / TargetSdk 36)** 构建，采用 **Kotlin 2.1 + Jetpack Compose + Material 3 + Room (v7) + WorkManager + AppWidget (`RemoteViews` + `Chronometer`)** 多模块架构。

---

## 合作社团、活动场地与交流反馈

| 合作社团 / 渠道 | 常规活动场所 | 群号 / 入口 |
| :--- | :--- | :--- |
| **炽夜空动漫社** | 大学生活动中心 | QQ 群 `314623763` |
| **硬件技术联盟** | 莲花街校区创客空间 | QQ 群 `789860526` |
| **计算机服务社** | 莲花街校区 `学生宿舍F01 W111` | QQ 群 `1043885481` |
| **App 用户交流与问题反馈** | 独立于校内社团招新群 | 支持应用内配置用户交流 QQ 群/频道及 [GitHub Issues](https://github.com/yellowpeachxgp/day-in-school/issues) |

---

## 1. 主要功能与诚实能力边界

- **今天（首页 · 当天课表 + 单日停课恢复 + 可定制模块化 Bento 卡片布局）**：
  - 顶部日期栏支持跨周/跨月左右无极连续滑动选择任意日期，并显示每日课程圆点与月份角标。
  - 上半部分聚焦「当天课表卡片」，在课程行内直接高亮「正在上课 / 下一节课」并提供行内一键「去教室」导航按钮与课间换楼提示；设为「今日停课」的课程会在卡片底部「当日已停课」区域保留一键**「恢复上课」**入口，重启后仍可随时恢复。
  - 下半部分采用**可定制模块化 Bento 卡片引擎**：支持自由调整「近期考试与学业」「自习专注」「宿舍水电」「座位预约」「馆藏检索」「自习教室参考」「社团与竞赛」「下课就餐推荐」等板块的顺序、显隐与通栏/双列尺寸。
- **课表（周网格 + 周清单 + 全学期总课表 + 实践课排课管理）**：
  - 支持「周网格」「周清单」「总课表」三种形态一键切换：
    - **周网格 & 周清单**：支持周次切换、回本周、手动添加课程、区分**「修改全学期规则」**与**「仅改本次（单日改教室/节次）」**、单节停课及在顶部停课记录条中一键恢复。
    - **时间待定实践课排课**：对教务系统未指定固定节次的实践/集中环节课程（`sjkList`），支持绑定原课程直接设置排课周次、星期、节次与场地，排入周课表后重新同步教务不会静默冲掉用户修改。
    - **总课表**：展示全学期 7×5 大节排课总表与课程管理明细（支持逐条编辑、删除排课时段与添加课程）。
- **学业（GPA + 本学期/历史学期考试严格分离）**：
  - 集成 2×2 **学习与图书馆安排** Bento 矩阵（图书馆选座、馆藏书目检索、自习专注计时、双校区自习教室目录与个人无课参考）。
  - 累计/学年/学期平均学分绩点（`BigDecimal` 加权计算）、重修去重、学期趋势折线图与新修学分绩点测算。
  - **考试安排严格按学期隔离**：默认展示当前激活学期考试安排；若本学期教务尚未发布考试，明确提示「当前学期暂无考试安排」，并将往期考试单独归入「历史学期考试归档」，绝不将上学期考试静默冒充本学期考试。
- **校园（双校区地图 + 2.5D/矢量缩放 + 校政数据校准）**：
  - 支持**莲花街校区**（官方 GIS 矢量轮廓、2.5D 轴测底图、室内 1F–11F 平面图）与**嵩山路校区**（中原区嵩山南路 140 号校准电子围栏与教学/生活楼宇），在预览卡与全屏地图中均支持双指中心点放大/缩小（`13.8..18.8`）及 `+`/`-` 按钮控制；当定位精度较低（`>90m`）时明确提示不确定性并保留手动校区切换。
  - 楼宇与食堂状态据实校准：`32号楼（土建楼）`移除不存在的“2号教学楼”别名；原第二、第三餐厅标记为已停业并退出下课就餐推荐候选池（仅保留历史评价归档兼容）。
  - **自习教室与个人无课参考**：支持莲花街与嵩山路双校区教学楼筛选，并明确标注数据来源于「校区公共自习教室名录 + 个人已导入课表无冲突反查」，非全校教务实时排课占用数据。
- **校医院药品价格公示与医保政策参考（只读目录）**：
  - 离线收录校医院现场公示的 **198 项西药/中成药/生物制品基准单价、规格、剂型、生产厂家与国药准字号**，支持离线搜索与分类筛选。
  - 明确标注「历史公示价格（采集于 2026-06-23），不代表当前实时在售或库存，实际结算以校医院收费窗口为准」，附大学生医保报销政策说明，不虚构全员固定的“医保后单价”。
  - **资料致谢**：感谢 [@Lollipop2233](https://github.com/Lollipop2233) 拍摄并贡献校医院西药与中成药价格公示实拍资料（[#17](https://github.com/yellowpeachxgp/day-in-school/issues/17)）。
- **互助广场（失物招领私密联系申请 + 二手教材 + 防丢码）**：
  - 失物招领卡片与详情页支持**「向发起人发消息 / 提交认领线索」**，区分「仅发起人可见联系方式」与「公开线索留言」，支持快捷认领模板、一键呼起 QQ/短信联系及不良信息举报。
- **图书馆座位预约安全双引擎（本地免费 + 云端可选委托）**：
  - 本地定时预约完全免费且不强制绑定云端 SSO；用户显式选择的时段（如 `08:00–22:00`）在预热、提交与回查全链路原样保留。
  - 预热缓存按账号、计划版本、日期、区域、座位与时段隔离并校验 TTL，失败或空候选立即失效重算；每次外部写入前重新核验计划状态、授权版本与协程取消状态，已发出请求后若撤回委托仍完整保留学校返回的预约单号或 `RESULT_UNKNOWN` 待核对状态。
- **外观、工大深蓝主色 (`#053968`) 与可选桌面图标**：
  - 默认「工大蓝」主色调升级为 `#053968`，支持浅色/深色/Liquid Glass/清新森系/奶油手账与系统动态壁纸取色，不会覆盖用户已选的自定义配色。
  - 在「设置 → 外观、隐私与数据」中提供基于 `<activity-alias>` 的可选二次元时尚桌面图标开关（默认关闭，保留标准品牌主图标）。

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
- [`docs/WECHAT_MINIPROGRAM_FEASIBILITY.md`](docs/WECHAT_MINIPROGRAM_FEASIBILITY.md)：微信小程序与商业化可行性、隐私边界及维护成本评估（[#20](https://github.com/yellowpeachxgp/day-in-school/issues/20)）。

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
