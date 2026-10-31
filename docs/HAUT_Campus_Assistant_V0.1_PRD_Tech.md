# 河南工业大学校园助手 — V0.1 产品与技术开发规格

> 状态：开发前规格 / 2026-10-31  
> 项目代号：HAUT Campus Assistant（正式名称未定）  
> 平台：Android 原生；第一所学校仅河南工业大学，首期以莲花街校区为主要地图试点  
> 交付对象：产品、设计、Android 开发与 Codex  
> **产品北极星：打开手机就知道下一节课何时、何地；打开 App 就能看懂整个学期的课程与学业表现。**

## 0. 执行原则（不可退让）

1. **课表是第一入口。** 今日课程、完整周课表、课程详情、手动调整和离线可用都是 V0.1 的基本能力。
2. **时间轴是核心交互。** App 内有按真实时间比例绘制的日时间轴、当前时间指示线、课程状态变化以及下一节课卡片。
3. **桌面小组件是一等产品。** 同一套课程状态引擎供 App 与 Widgets 使用；小组件不得被降级成只有文字的列表。
4. **个人 GPA 是独立一级页面。** 区分教务原始成绩、教务返回课程绩点与 App 计算值；不假定学校所有学生使用统一的 4.0/5.0 制度。
5. **本地优先，隐私优先。** 无后端依赖可工作；登录使用学校官方页面和本人会话；不收集或上报明文账号密码；不绕过验证码、访问控制或学校授权。
6. **单校做精。** 第一版不做高校市场、社会化社区、在线聊天、多高校动态适配平台。
7. **先可用，再锦上添花。** 校园地图功能可以循序渐进，但课表时间轴、小组件、GPA 绝不能挤出 MVP。

## 1. V0.1 信息架构

| 底部导航 | 首屏内容 | 核心动作 |
| --- | --- | --- |
| 今天 | 今天日期/第几教学周、下一节课、日时间轴、今日课程、快捷导航 | 查看课程、导航至教学楼 |
| 课表 | 周课表、周次切换、课程颜色与详情、补课/单双周 | 导入、编辑、分享/导出 |
| 学业 | GPA 总览、学期图表、成绩列表、学分进度 | 同步成绩、筛选学期 |
| 校园 | 莲花街校区地图、建筑/教室搜索、地点收藏 | 定位、查看建筑、步行路线 |

设置、课表导入、学期管理和小组件个性化配置从头像/设置入口进入，不占用底栏。

## 2. 课表与日时间轴（P0）

### 2.1 周课表

- 显示周一至周日，纵轴以实际节次/时间呈现，支持一周及按天查看。
- 当前日期高亮，当前节次高亮；同一课程跨节次纵向合并。
- 有课周次精确存储为周编号集合 `weeks: Set<Int>`，不能仅依赖“单/双周”字符串。
- 支持课程的上午、下午、晚间、跨时段、冲突重叠、隔周与临时调课。
- 每个课程展示课程名、教室、教师（空间不足时优先显示名称 + 地点）。
- 用户可调整课程色彩、节次、教室、课程名称和时间；修改不能被下次导入静默覆盖。
- 支持课程点击详情、长按快捷编辑、当前周/指定周切换、回到本周、放大/缩小时间轴。
- 教务导入结果必须先预览并给出“新增/修改/冲突/保留用户修改”的差异明细，再确认提交。

### 2.2 日时间轴（最高 UI 优先级）

建议布局（示意，不是最终视觉稿）：

```text
10月31日  周六                         第 9 教学周

下一节 · 数据结构                       10:10–11:50
教学楼 A · 204                       距开始  24 分钟

08:00 │  ··· 已结束课程（弱化）
09:30 │
09:46 ├── ● 现在（随时间平滑移动）
10:10 │  ┌ 数据结构     未开始
      │  │ 教室 A204    10:10 — 11:50
11:50 │  └
14:00 │  ┌ 遥感原理     未开始
      │  │ 教室 B306    14:00 — 15:40
15:40 │  └
```

- 时间位置以真实时刻映射：`y = (t - timelineStart) * pxPerMinute`；不用“等高卡片”冒充时间轴。
- 当前时间指示线每分钟实时移动；App **在前台可见**时更新，进入后台停止无意义的每分钟重绘。
- 当前课程状态：`UPCOMING → IN_PROGRESS → FINISHED`；课前显示还有多久，课中显示已上课时长和剩余时间，结束后弱化。
- 一天无课：显示不空旷的“今天没有课程”与下一次上课日期；跨日时自动切换。
- 放假、调休、节假日特殊课表：由学期日历/用户覆盖规则决定，不仅看周几。
- 给用户完整时间节次表的编辑权；教务教室时间不详时，不能硬编码全国通用上下课时间。

### 2.3 时间模型及边界

- 时区默认 `Asia/Shanghai`，使用 `java.time`。
- 学期包含 `startDate`（第一教学周开始日期）、`weekStartDay`、`weekCount`、节次时间表以及特殊日历覆盖。
- 从**实际日期**映射 `teachingWeek`，学校的特殊教学周或补课通过 `CalendarOverride` 单独维护。
- 课程排课模板 `CourseScheduleRule` 与某一天的实际课程 `ClassOccurrence` 分开；用于调课、停课、补课且不污染原始规则。
- 课程的“具体起止时间”从节次时间表派生；可按天/课程覆写；不同校区/教学楼将来可有不同配置。
- 对本地时钟修改、时区变化、系统重启、跨学期、空白课表、多人共用设备、无网络做测试。

## 3. Android 桌面小组件（V0.1 必交付）

### 3.1 组件系列和适配尺寸

> 2×2 / 4×2 / 4×4 等是 Launcher 网格的目标布局类别；实际尺寸来自系统提供的 dp 区间。必须响应式适配，不能按像素等比缩放整张设计图。

| 组件 | 推荐网格 | 主要呈现 | 交互 |
| --- | --- | --- | --- |
| 下一节课 Mini | 2×2 | 下一课名称、起止时间、地点、系统倒计时 | 点击打开课程；地点进入地图 |
| 今日课程 Today | 4×2 | 日期、教学周、当前课/下节课、随后两节课程 | 点击课程进入详情 |
| 日程时间轴 Timeline | 4×4 | 左侧时间刻度、时间线、按时间排列课程、当前状态 | 点击卡片进入具体课程 |
| 周课表 Week | 4×4 或更大 | 周一到周日简化周视图与当天强调色 | 点击日期跳到 App 周课表 |

- 首次配置可选：所在学期、主题、强调色、紧凑/舒展、显示教师、显示教室、隐私模式。
- 同一种组件允许创建多个实例，每个实例的配置独立保存。
- 无课、未导入、登录失效、数据更新中、课程冲突等均有**精心设计**的空/错误状态。
- 小组件选择器必须有**真实、准确的预览**；Android 15+ 尽可能支持生成式预览，同时有早期系统的 fallback。
- Android 12+ 动态颜色，兼容深色、浅色；避免锁死设备颜色。
- 自适应布局：横向压缩优先隐藏教师、其次隐藏结束时间；不得截断课程名到不可识别。

### 3.2 视觉规范

- 设计风格：**极简、理性、精确、有空间感**；不采用复杂渐变、过度玻璃拟态或大面积装饰图案。
- 提供中性系统配色 + 可选暖橙/桃色强调；深浅主题均具备足够对比度。
- 卡片内边距推荐 12–16dp；圆角遵循 Android 系统/OEM 提供的 widget 容器曲率；统一 4/8dp 间距节奏。
- 文本层级：课程名 > 开始时间/倒计时 > 地点 > 教师与学期信息。
- 当前课程强调色，未来课程次级色，已完成课程弱化；**不要仅用颜色区分状态**，还要用文字/图形。
- 触摸目标不少于 48×48dp；不在 2×2 卡片塞入密集的小按钮。
- 不要用单张位图承载全部文字与内容；优先原生文字/布局，确保字重、缩放、无障碍与清晰度。
- 没有课程时显示短文案与下一次课的日期；不留一整块纯白。

### 3.3 更新架构（重点：不能夸大实时能力）

- 纯内容型小组件：可采用 Jetpack Glance；倒计时型优先评估 `RemoteViews + Chronometer`，用系统控件推进数字，而非每分钟后台更新整个小组件。
- `RemoteViews.setChronometer` + `setChronometerCountDown`：使用基于 `SystemClock.elapsedRealtime()` 的时间基准；在 API 24+ 实现系统倒计时控件。跨课时边界需重设下一个倒计时目标。
- 事件驱动更新：导入课表后、手动修改课程后、切换学期后、系统跨日或时间/时区变化、相关系统广播、用户主动刷新时更新。
- 在课程开始/结束节点可尝试 `AlarmManager` 定向刷新；**优先非精确闹钟**并接受 Doze/OEM 延迟；精确提醒属于需另行论证权限和商店政策的独立功能，未授予权限时要回退。
- `AppWidgetProviderInfo.updatePeriodMillis` 常规更新最短 30 分钟；`WorkManager` 最短周期任务为 15 分钟，不可承诺秒级准确的背景状态切换。
- 如果被系统延迟，应基于当前系统时间重新推导状态，避免把过时的“正在上课”永远挂在桌面。
- 小组件响应式与设备适配：Pixel Launcher、MIUI/HyperOS、ColorOS、OriginOS、HarmonyOS 兼容模式等，按实际设备能力测试；不要依赖厂商私有 API。
- 测试：横竖尺寸调整、字体放大到 1.3/1.5 倍、深色/动态色切换、低电量与省电模式、重启、离线、权限拒绝。

## 4. GPA 个人学业页面（P0）

### 4.1 顶部总览

```text
我的学业                                 2025—2026 第 1 学期

累计 GPA             本学期 GPA        已修学分
  3.62                  3.78            112.5
（示例数据，非学校计算结果）

[ 学期 GPA 趋势折线图：按学期可筛选 ]
[ 课程成绩分布：优秀 / 良好 / 及格 / 其他 ]
[ 成绩明细：课程名称 | 学分 | 原始成绩 | 课程绩点 ]
```

- 累计 GPA、当前学期 GPA、学年 GPA；显示“来自教务/本地计算/估算”来源标签。
- GPA 折线图按学期展示，并可切换成平均分、获得学分走势；避免假装已有多学期数据。
- 成绩列表可筛选学年学期、必修/选修、已通过/未通过；课程详情含学分、成绩、绩点、修读方式、原始来源。
- 支持低学分绩点课程排查、绩点变化解释（如缺少成绩、重修或补考被纳入）。
- 数据导出 CSV（数据最小化）、可选本地备份；不需要上传学生成绩到服务端。

### 4.2 GPA 计算规范

优先级：

1. **学校教务系统提供的总体/学期 GPA**：若可获取，作为“官方数据”，原样展示，并保留抓取时间与字段定义。
2. **课程级绩点来自教务，学分来自教务**：按 `Σ(课程学分×该课绩点) / Σ纳入计算课程学分` 得到“本地加权 GPA”，明确标为计算结果。
3. **只有百分制/五级制原始成绩**：暂不套用网上找到的通用公式；先取得河南工业大学**普通本科生适用的有效规定**，再实现对应的可测试规则。若无该规则，显示“不足以可靠计算”，而不是猜数。

特别注意：能检索到河南工业大学公开的一套 5.0 绩点换算办法，但它是**国际学生管理办法**，不能直接应用到所有普通本科学生。见官方来源：https://sie.haut.edu.cn/info/1189/7545.htm 。

其他规则：

- 带“通过/不通过”、五级制、免修、免考、缓考、补考、重修、课程替换的记录必须保留原始字段；是否计入分母由适用学校规则确定。
- 若存在一门课程多次修读，不能简单累加；按学校规则对 attempt 进行纳入/排除，保留可核查的计算明细。
- 无课程级有效绩点时不能把 `score/25` 等公式默认为官方换算。
- 课程学分为 0、未录入、无效结果的处理必须有规则与显式测试。
- UI 绩点最多两位小数，但计算过程使用精确十进制（如 `BigDecimal`）并只在展示时取舍入；按学校实际规则覆盖。
- 独立设置隐私开关，隐藏 GPA 和成绩数据的截图预览/最近任务缩略图（依 Android 支持情况处理）。

## 5. 河南工大教务接入（P0）

- 首期只配置河南工业大学教务登录与课程、成绩查询。
- 官方登录入口：`https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html`（从已有社区仓库获取，实际运行前核实仍有效）。
- 用户在 WebView **官方登录页面**输入账号与验证码；不得绕过验证码，不在 App 后端上传登录信息。
- 复用已登录的授权会话解析教务提供的课表/成绩/考试数据；适配器必须可替换，不把原始 DOM 解析硬写进 UI 层。
- 用户应能手动重新认证、清除会话、完全删除本地个人数据。
- 依次支持：官方课表导入 → 手动课表 → 本地 CSV/ICS 文件导入 → OCR 截图纠错导入（P1）。
- 导入前预览、导入差异、原始数据字段对照；错误时不影响上次成功缓存。
- 首次验证使用开发者**本人授权**的测试账号与模拟数据，尊重学校服务条款与访问频率限制。
- 参考仓库：https://github.com/yusixian/AISchedule_myparser ；该仓库未确认存在明确许可证时不得直接复制后闭源/开源发布。
- 可研究通用新正方数据字段设计，但不得依赖其 API 永不变动：https://github.com/openschoolcn/zfn_api 。

## 6. 校园地图（V0.1 基础功能）

- 初期仅莲花街校区：教学楼、图书馆、食堂、校门、宿舍区、快递点等 POI。
- 建筑数据库含 `buildingId/name/aliases/geoPosition/campus/category/entrances/verifiedAt`，并维护教务“教室原始字符串”到建筑的别名映射。
- 点击课表地点，先得到 `buildingId`，再调起地图定位与路线；模糊匹配需给用户确认，不可装作已经识别成功。
- 首版允许外部地图步行导航跳转；校内道路网及完整步行路径规划可作为后续增强。
- GPS 室外定位是楼级参考，不承诺单凭 GPS 找到楼内具体教室；楼层室内地图需要单独获取建筑平面数据。
- 地图底图和 POI 数据必须核查来源和可用许可；官方校园图是位置核对参考，不等于可自由重绘/批量分发的数据授权。
- 官方校园地图：https://www.haut.edu.cn/info/1120/1030.htm 。

## 7. 建议 Android 技术栈

- 语言/框架：Kotlin、Jetpack Compose、Material 3、AndroidX Navigation。
- 状态：ViewModel + Kotlin Flow / StateFlow；UI 纯展示，业务计算与数据库无耦合。
- DI：Hilt（若规模过小也可先用构造注入；不得跨模块 Service Locator 随意获取对象）。
- 本地数据：Room、DataStore；敏感会话材料受 Android Keystore 管理，遵循 Cookie 管理与最小化存储原则。
- 教务：WebView 授权登录 + 抽象 `AcademicProvider`；只解析用户本人授权会话内的数据。
- Widgets：Glance（一般展示）与必要时的 `RemoteViews/Chronometer`（倒计时）；统一共享 `ScheduleSnapshotUseCase`。
- 任务调度：WorkManager、必要的 AlarmManager 非精确事件调度；正确处理权限与休眠。
- OCR（P1）：ML Kit 或 PaddleOCR；用户本地预览校对后才入库。
- 地图：高德地图 SDK 或 MapLibre + 合规瓦片/底图；通过 `MapProvider` 抽象替换，注意 SDK 许可和网络条件。
- 测试：JUnit、Turbine、Room migration tests、Compose UI tests、截图回归测试、Widget 真机验收。
- CI：GitHub Actions（lint、单测、debug APK、依赖安全与许可证检查）；正式发布使用独立签名管理。
- 默认编译目标/最低 SDK 以实际目标设备分布与最新 AGP 可用性确定；第一版建议优先 Android 10+，是否下探到 Android 8 在开发前复核。

## 8. 领域模型草案（Room Tables）

```text
StudentProfile(id, schoolId, displayName?, major?, enrollmentYear?)
Term(id, academicYear, termNumber, startDate, weekCount, timeTableId, current)
PeriodTime(id, timeTableId, periodNo, startLocalTime, endLocalTime)
Course(id, externalId?, name, teacher?, color, credit?, source, lastSyncAt?)
CourseScheduleRule(id, courseId, termId, weekday, startPeriod, endPeriod, weeksJson, rawRoom, buildingId?)
OccurrenceOverride(id, courseRuleId?, date, startAt?, endAt?, room?, canceled, reason?)
GradeAttempt(id, termId, courseKey, name, credits, rawGrade, gradePoint?, attemptType, source, syncedAt)
GpaPolicy(id, source, appliesTo, policyVersion, includeRulesJson, scoreToPointRulesJson?)
Building(id, campusId, name, latitude, longitude, category, verifiedAt)
BuildingAlias(id, buildingId, rawNameOrPattern, confidence, verified)
WidgetInstanceConfig(appWidgetId, widgetType, termId?, theme, compact, privacyMode)
ImportAudit(id, termId, importSource, importedAt, rowCount, conflictCount, outcome)
```

以上仅为概念草案，数据库主键、迁移方案、重复成绩归并、课程编辑版本和本地数据保护要由实现阶段补足。

## 9. 项目目录建议

```text
app/
core/model/
core/database/
core/designsystem/
core/domain/              # 时间/周次/课程状态/GPA 纯业务算法
core/academic/            # 河南工大教务适配接口与实现
core/location/            # POI、楼宇别名解析、地图接口
feature/today/
feature/timetable/
feature/grades/
feature/map/
feature/settings/
feature/importer/
widget/common/
widget/nextclass/
widget/today/
widget/timeline/
widget/week/
benchmark/                # 可后置
```

## 10. 开发顺序（不要按功能菜单逐页堆代码）

**Milestone A — 可验证的纯逻辑内核**
1. 项目骨架、主题、导航、Room schema、测试样例。
2. 教学周与课时映射、课程冲突处理、时间轴状态机单元测试。
3. 使用内置模拟数据完成今天页和周课表，确保离线可运行。

**Milestone B — 桌面小组件第一批（与课表并行）**
4. 下一节课 2×2 和今日课表 4×2；实现小组件配置、预览、无课状态。
5. 验证 Chronometer 倒计时、前后台刷新、重启、动态色和重尺寸。
6. 逐步实现 4×4 时间轴与周视图，不允许因平台限制牺牲可读性。

**Milestone C — 工大教务与 GPA**
7. 官方登录、本人授权课表导入、预览差异、缓存离线与手动编辑。
8. 成绩导入、课程级绩点处理、GPA 看板与学期趋势。
9. 统一 GPA 口径与来源标签，逐项验证学分计入和重修规则。

**Milestone D — 校园联动**
10. 校区 POI 数据库、建筑搜索和位置展示。
11. 课程教室别名关联 → 导航按钮 → 地图位置。
12. 回归、性能、无障碍、权限说明、数据清除、APK 发布。

## 11. 验收标准（Definition of Done）

### A. 课表与时间轴
- 任意一个测试课程组合均能正确表示周次、单双周、课时、教师、教室、冲突与跨日；特别测试周日、学期开始前/结束后及调休。
- 在 App 前台，当前时间线与课程状态及时更新，用户无需手动刷新页面。
- 无网络时当前学期和已缓存成绩可用；教务登录失效不删除已有离线数据。
- 手动编辑在同步冲突时不会被无提示覆盖。

### B. Widgets
- 首发提供 **2×2、4×2、4×4** 三种尺寸级别与四类 widget 预设（各类按实际 Launcher 约束重新排布）。
- 各组件均具备深色/浅色与 Android 12+ 动态色适配；可正确显示空课表、休息日和登录失效。
- 重要文本对比清晰、不会重叠或溢出；触摸目标符合可访问性要求；文字缩放 1.5 倍保持核心信息可读。
- 小组件数据与 App 保持同源；课程调整后主动刷新；后台刷新可延迟时不会对外保证秒级实时。
- 倒计时采用可验证的系统控件或显示明确的静态开始时间，不用伪造实时数字。
- 安装后从系统“添加小组件”界面能够识别不同组件及真实预览。

### C. GPA
- 同一份模拟成绩对比人工计算，误差仅来自显示时的规定舍入；不把 GPA 规模（4.0/5.0）猜为默认。
- 每一项 GPA 明确来自“教务直接提供 / 教务课程绩点本地加权 / 缺少规则无法计算”。
- 重修、补考、零学分、无效/未录入成绩都有回归用例；没有可靠规则时不进入“官方 GPA”统计。

### D. 地图与隐私
- 点击课程地点能准确找到已经核验的教学楼；模糊匹配要让用户确认。
- 用户能够完全清除个人课程、成绩、教务会话与组件缓存；日志无明文教务密码、会话令牌和成绩明细。

## 12. 风险与待核验项

1. 河南工大当前正方教务页面、课程 API、成绩 API 的登录流程和响应结构，必须在用户本人授权下真机/测试账号验证；2025 年的小爱适配脚本不保证 2026 年仍兼容。
2. 普通本科适用的 GPA 换算及补考/重修纳入规则尚未在公开官方资料中完整核实。**不能用国际学生规定替代。**
3. 校园地图与 POI 的授权来源、坐标和楼栋别名需核验；室内导航不属于 V0.1 承诺范围。
4. Android 系统、第三方 Launcher 对 widget 的圆角、尺寸、刷新、电量管理存在差异；需真实设备矩阵验证。
5. 个人信息及教务会话安全需要独立审查；App 上架前应完成适用的隐私和许可证合规检查。

## 13. 参考资料（公开来源）

- 河南工大小爱适配：https://github.com/yusixian/AISchedule_myparser
- 河南工大官方地图：https://www.haut.edu.cn/info/1120/1030.htm
- 新正方 API 结构参考：https://github.com/openschoolcn/zfn_api
- 河南工大国际学生绩点（**仅国际学生范围**）：https://sie.haut.edu.cn/info/1189/7545.htm
- Android Widget 质量：https://developer.android.com/docs/quality-guidelines/widget-quality
- Android Glance：https://developer.android.com/develop/ui/compose/glance
- Android Widget 更新：https://developer.android.com/develop/ui/views/appwidgets/advanced
- Android 计时控件：https://developer.android.com/reference/android/widget/RemoteViews
- Android 响应式 Widget：https://developer.android.com/develop/ui/views/appwidgets/layouts
- Android Widget 预览：https://developer.android.com/develop/ui/views/appwidgets/previews
- Android Alarm 调度：https://developer.android.com/develop/background-work/services/alarms

---

### 交给 Codex 的执行指令

> 阅读本规格后，先建立 Android 工程骨架、Room 与业务层，建立含单双周、时间边界、补课的纯 Kotlin 测试集；使用 Mock 课表做出「今天」真实时间轴、周课表和两款核心小组件（2×2 下一节课、4×2 今日课程）。先拿模拟数据完成完整运行闭环，不要先处理教务验证码、成绩爬取或地图抓取。验证 UI、Widget 数据同源、尺寸与深浅色后，再按 Milestone C 接入用户授权教务，完成 GPA 页面。整个项目使用明确许可证、保留第三方版权信息，禁止提交账号密码或会话。每个 Milestone 提交可运行 APK、测试结果与截图，并列出待验证事项。
