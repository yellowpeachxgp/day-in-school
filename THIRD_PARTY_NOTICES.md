# 第三方开源组件与外部参考声明 (Third-Party Notices)

本项目（HAUT Campus Assistant / 河南工业大学校园助手）采用 Apache License 2.0 许可证开源。

## 1. 核心开源依赖与许可证

| 组件 | 许可证 | 版权归属 |
| --- | --- | --- |
| Kotlin Standard Library & Coroutines | Apache-2.0 | JetBrains s.r.o. |
| Android Jetpack (Compose, Room, DataStore, Navigation, Glance, WorkManager, Lifecycle) | Apache-2.0 | Google LLC / The Android Open Source Project |
| Material Components / Material 3 | Apache-2.0 | Google LLC |
| Jsoup (HTML/DOM Parser, Optional) | MIT | Jonathan Hedley |
| JUnit 4 | EPL-1.0 | JUnit Team |

## 2. 外部公开参考说明（非代码复制声明）

1. **`yusixian/AISchedule_myparser`**：仅作为河南工业大学教务系统入口 URL 的公开线索参考，由于该仓库未声明明确开源许可证，本项目**未复制**该仓库任何脚本代码，所有正方教务网页/JSON 解析器与数据适配器均由本项目基于公开 DOM/JSON 字段约定独立编写。
2. **`openschoolcn/zfn_api`**：仅参考新正方教务系统的通用概念字段命名，未直接依赖或内嵌其代码。
3. **河南工业大学国际教育学院公开文件（`https://sie.haut.edu.cn/info/1189/7545.htm`）**：仅用于警示“国际学生 5.0 绩点办法不得默认套用于普通本科生”，本项目不将其作为普通本科默认 GPA 公式。
4. **河南工业大学莲花街校区地图参考（`https://www.haut.edu.cn/info/1120/1030.htm`）**：仅用于人工核对校内主要建筑名称与地理方位，未抓取或分发学校官方地图图片。
