# 河南工业大学校园助手 (HAUT Campus Assistant) — V0.1

> **产品北极星：打开手机就知道下一节课何时、何地；打开 App 就能看懂整个学期的课程与学业表现。**

本项目按照《[河南工业大学校园助手 — V0.1 产品与技术开发规格](docs/HAUT_Campus_Assistant_V0.1_PRD_Tech.md)》构建，采用原生 Kotlin + Jetpack Compose + Material 3 + Room + AppWidget (`RemoteViews` + `Chronometer`) 多模块架构实现。

---

## 1. 模块化工程结构

| 模块路径 | 类型 | 职责说明 |
| --- | --- | --- |
| `:core:model` | Pure Kotlin JVM | 领域模型（`Term`、`PeriodTime`、`Course`、`CourseScheduleRule`、`OccurrenceOverride`、`GradeAttempt`、`Building`、`WidgetInstanceConfig` 等） |
| `:core:domain` | Pure Kotlin JVM | 纯业务算法内核：教学周映射、单双周/自定义周集合解析、比例时间轴计算 `y = (t - timelineStart) * pxPerMinute`、冲突分栏、导入差异引擎、GPA `BigDecimal` 精确加权引擎、教室楼宇别名解析 |
| `:core:database` | Android Library (Room) | 13 张 Room 数据表、DAO、DataStore 偏好设置、`CampusRepository` 及莲花街校区离线种子数据 |
| `:core:designsystem` | Android Library (Compose) | 极简理性中性配色 + 暖橙/桃色强调色 + Android 12+ 动态色主题 |
| `:core:academic` | Android Library | 河南工业大学正方教务官方 WebView 授权会话接入、独立 `kbList`/`items` JSON 与 CSV 解析器 |
| `:core:location` | Android Library | 莲花街校区建筑坐标与系统/外部地图步行导航拉起 |
| `:feature:today` | Android Library (Compose) | **今天**：下一节课核心卡片、按真实时刻比例绘制的日时间轴、前台生命周期感知的实时时间线（`● 现在`）、单日停课覆盖 |
| `:feature:timetable` | Android Library (Compose) | **课表**：周一至周日完整网格、跨节次纵向合并、冲突课程并列提示、周次切换、回本周、手动加课与课程编辑保护 |
| `:feature:grades` | Android Library (Compose) | **学业**：累计/学期/学年 GPA 看板（严格区分官方/教务课程绩点加权/不足以计算）、学期趋势折线图、重修去重解释、防截屏隐私保护与 CSV 导出 |
| `:feature:map` | Android Library (Compose) | **校园**：莲花街校区建筑相对方位图、教室字符串解析、模糊匹配确认弹窗与外部步行路线导航 |
| `:feature:importer` | Android Library (Compose) | **导入**：教务官方 WebView 登录、同步差异预览（新增/更新/保留用户修改冲突）、CSV 本地导入与导入审计 |
| `:feature:settings` | Android Library (Compose) | **设置**：学期切换、完整 1-10 节作息时间表自定义、桌面小组件配置与手动刷新、个人数据与会话一键彻底清除 |
| `:widget:common` | Android Library | App 与四款桌面小组件共用的 `ScheduleSnapshotUseCase` 与非精确 `AlarmManager` 课时边界刷新调度器 |
| `:widget:nextclass` | Android Library | **2×2 下一节课 Mini**：基于 `SystemClock.elapsedRealtime()` 的系统级 `Chronometer` 倒计时与窄屏自适应布局 |
| `:widget:today` | Android Library | **4×2 今日课程 Today**：今日日期、教学周、当前/下节课高亮与随后两节课 |
| `:widget:timeline` | Android Library | **4×4 日程时间轴 Timeline**：左侧时间刻度、竖向时间轴与今日课程状态进度 |
| `:widget:week` | Android Library | **4×4 周课表 Week**：周一至周日简化周视图与当天强调色 |
| `:app` | Android Application | 主入口、底部四栏导航、小组件广播注册与 `FLAG_SECURE` 隐私控制 |

---

## 2. 构建与测试指令

```bash
# 1. 运行纯业务逻辑与教务解析器单元测试
./gradlew test

# 2. 构建可安装 Debug APK
./gradlew :app:assembleDebug
# 产物路径：app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. 待真机/实网核验事项清单 (Pending Verification Checklist)

1. **河南工大教务实网回归**：`https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html` 需在校内网/VPN 及本人授权测试账号下核对 2026 学年最新 DOM 与 `kbList`/`cjcx` 字段是否微调。
2. **普通本科 GPA 换算细则核实**：当前严格使用教务返回的课程级绩点 (`jd`) 进行加权计算；若部分课程无 `jd`，系统按规格标记为「不足以可靠计算」，绝不套用国际学生 5.0 规定。待获取教务处最新普通本科生文件后可注入 `GpaPolicy`。
3. **莲花街校区建筑入口与坐标精校**：内置 POI 坐标已按莲花街校区主要建筑布局建模，后续可结合实地踩点进一步细化各教学楼东/西/南/北门经纬度。
4. **多品牌真机 Launcher 小组件验收**：需在 Pixel Launcher、HyperOS (MIUI)、ColorOS、OriginOS 真机上验证 2×2 / 4×2 / 4×4 尺寸拉伸与省电策略下的 `AlarmManager` 窗口刷新表现。
