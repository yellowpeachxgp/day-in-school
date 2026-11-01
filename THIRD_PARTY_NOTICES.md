# 第三方开源组件与外部参考声明 (Third-Party Notices)

本项目「日在校园 (Day in School / HAUT Campus Assistant)」采用 [Apache License, Version 2.0](LICENSE) 许可证开源。

---

## 1. HAUTPocket 归属与改编声明

- **上游仓库**：[rainthink-vanilla/HAUTPocket](https://github.com/rainthink-vanilla/HAUTPocket)
- **锁定提交**：`aa83d6f7200ebde4a816e91191f19d9990563362`（对应版本 `v1.0.1`）
- **开源许可**：MIT License（Copyright (c) 2026 HAUTPocket contributors，完整原文保存在 [`licenses/HAUTPocket-MIT.txt`](licenses/HAUTPocket-MIT.txt)）

本项目在 Apache-2.0 许可下，对 HAUTPocket（MIT 许可）的部分代码、前端脚本与协议适配逻辑进行了移植、重构或参考，以融入本项目的 Room + DataStore + Jetpack Compose 多模块架构。具体涉及范围如下：

1. **宿舍水电查询、历史采样与手工抄表**：
   - 参考并改编 `WanxiaoClient.kt`、`DailyElectricity.kt`、`AccountVault.kt`、`Models.kt`（表计与读数计算）及 `UsageLineChart.kt`，用于实现完美校园「智能水电」只读查询（`customercode=43`）、Android Keystore 账号加密、WorkManager 周期电量采样及手工抄表净耗量计算。
2. **图书馆馆藏检索**：
   - 参考并改编 `CatalogActivity.kt`、`CatalogScreen.kt`、`CatalogSnapshot.kt`、`LibraryPageDesign.kt` 及 `assets/catalog-snapshot.js`、`assets/catalog-design.js`、`assets/catalog-design.css`，用于通过学校官方馆藏移动站点（`libspace.haut.edu.cn`）提取公开书目与馆藏状态并在原生界面展示。
3. **图书馆座位预约与学校网页共享会话**：
   - 参考并改编 `OfficialWebActivity.kt`、`OfficialWebSession.kt`、`OfficialServices.kt` 及 `assets/library-design.js`、`assets/library-design.css`，用于内嵌官方选座 H5（`wslib.haut.edu.cn`）展示增强、学校统一身份认证（`mapp.haut.edu.cn`）登录回跳与私有 WebView Cookie/DOM Storage 会话持久化。
4. **专注计时与完成提醒**：
   - 参考并改编 `FocusCompletion.kt`、`FocusReminders.kt`、`FocusDelivery.kt`，用于实现专注倒计时、基于 `expectedEnd` 的幂等完成入账、系统 `AlarmManager` 提醒与 10 秒后台送达测试诊断。
5. **课表文件互通与教务网页显示修复**：
   - 参考并改编 `TimetableFile.kt`、`TimetableExport.kt`、`ScheduleParser.kt`、`ScheduleImport.kt`、`CourseBlocks.kt` 及 `assets/teaching-layout.js`，用于兼容 `univ-timetable/v1`（`.md` / `.json`）课表导入导出、"第N周"周次解析以及正方 N9 课表页（`/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html`）WebView 0 高度白屏修复。

### 明确不复制的美术资源、标识与签名材料声明

上游 HAUTPocket 的 `THIRD_PARTY_NOTICES.md` 已说明其应用图标原图（`artwork/launcher.jpg`）包含第三方插画与河南工业大学校徽，且 MIT 代码许可不授予第三方美术作品或校徽的使用权。因此，**本项目严格不复制、不引入、不分发以下任何内容**：

- 上游仓库中的任何应用图标、启动图与插画资源（`artwork/launcher.jpg`、`app/src/main/res/drawable-nodpi/ic_launcher_artwork.png`、`app/src/main/res/mipmap-anydpi/ic_launcher.xml` 及 `tools/generate-launcher-icons.ps1`）；
- 任何河南工业大学校徽、视觉标识或第三方插画；
- 任何馆藏图书封面图片文件（运行期仅在用户检索时按官方页面返回结果实时加载公开 HTTPS 封面链接，不在源码树或安装包内打包任何图书封面）；
- 上游仓库中的发行证书与签名轮换材料（`signing/lineage.bin`、`signing/release-certificate.pem` 及相关签名脚本）。

详细的源文件级映射与复用方式见 [`docs/HAUTPOCKET_MIGRATION_PROVENANCE.md`](docs/HAUTPOCKET_MIGRATION_PROVENANCE.md)。

---

## 2. HAUTPocket 上游致谢与传递声明

根据 HAUTPocket 仓库的 `THIRD_PARTY_NOTICES.md`，其涉及的上游项目与声明如下：

### 2.1 ZZUPocket
- **来源**：<https://github.com/3beuml/ZZUPocket>（参考提交 `5d2b2e6e2e0871922f424436a1785fade4cdae4d`）
- **版权与许可**：Copyright (c) 2026 3beuml，MIT License。
- **说明**：HAUTPocket 参考了 ZZUPocket 将课表、宿舍用电与校园服务集成为离线优先校园助手的产品思路及部分构建配置；河南工业大学相关业务代码为独立实现，不含郑州大学凭据、接口或房间目录。

### 2.2 Univ Timetable（大学课表互通契约）
- **来源**：<https://github.com/rainthink-vanilla/univ-timetable>
- **版权与许可**：Copyright (c) 2026 rainthink-vanilla，MIT License。
- **说明**：提供 `univ-timetable/v1` 课表文件格式约定（JSON 与 Markdown 课程明细表）。Kotlin 端解析与导出器基于该格式约定独立实现。

```text
MIT License

Copyright (c) 2026 3beuml
Copyright (c) 2026 rainthink-vanilla
Copyright (c) 2026 HAUTPocket contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. 核心开源依赖与许可证

| 组件 | 许可证 | 版权归属 |
| --- | --- | --- |
| Kotlin Standard Library & Coroutines | Apache-2.0 | JetBrains s.r.o. |
| Android Jetpack (Compose, Room, DataStore, Navigation, Glance, WorkManager, Lifecycle, Webkit) | Apache-2.0 | Google LLC / The Android Open Source Project |
| Material Components / Material 3 | Apache-2.0 | Google LLC |
| Coil (Image Loading, Optional/Library Cover) | Apache-2.0 | Coil Contributors |
| Jsoup (HTML/DOM Parser, Optional) | MIT | Jonathan Hedley |
| JUnit 4 | EPL-1.0 | JUnit Team |
| Gradle Wrapper | Apache-2.0 | Gradle Inc. |

---

## 4. 外部公开协议与资料参考说明（非代码复制声明）

1. **教务系统接口与字段事实（`yusixian/AISchedule_myparser`、`openschoolcn/zfn_api`）**：
   - 仅参考河南工业大学正方教务系统（`jwglxt.haut.edu.cn`）公开的课表查询路径（`/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151`、`/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`）与 JSON 字段名称（`kbList/kcmc/xqj/zcd/jc/jcs/cdmc/xm/xqmc`）。由于相关参考仓库未声明开源许可证或采用不同技术栈，本项目**未复制**其任何解析器源码。
2. **完美校园智能水电接口事实（`DragonYH/astrbot_plugin_wanxiao_electricity`、`JustaSage/ZCST_FEE_Bot`、`zuwei522/perfect-campus_electricity-alert`）**：
   - 仅参考公开记录的完美校园水电接口地址（`https://xqh5.17wanxiao.com/smartWaterAndElectricityService/SWAEServlet`）、方法名（`getbindroom`、`h5_getstuindexpage`）、请求/响应字段（`existflag`、`roomlist`、`roomverify`、`modlist`、`devicename/odd/todayuse/sumbuy/status`）以及河南工业大学历史租户代码 `customercode=43`（当前有效性需真实账户实测验证）。本项目**未复制**上述任何 GPL/AGPL 或未授权项目的源代码。
3. **河南工业大学校方公开通知与网页入口**：
   - 包括信息管理中心关于云上工大与统一身份认证的公开说明（`https://xxb.haut.edu.cn/`）、图书馆关于空间预约入口调整的通知（`https://lib.haut.edu.cn/info/1162/12015.htm`）、移动馆藏检索站点（`https://libspace.haut.edu.cn/`）及选座 H5 站点（`https://wslib.haut.edu.cn/`）。所有网页交互均在应用内受域名白名单限制的官方 WebView 会话中由用户本人完成认证，不内置或硬编码任何会话令牌。
4. **河南工业大学国际教育学院公开文件（`https://sie.haut.edu.cn/info/1189/7545.htm`）**：
   - 仅用于区分国际学生 5.0 绩点办法与普通本科生绩点规则，不将其作为普通本科默认 GPA 公式。
5. **河南工业大学莲花街校区地图参考（`https://www.haut.edu.cn/info/1120/1030.htm`）**：
   - 仅用于人工核对校内主要建筑名称与地理方位，未抓取或分发学校官方地图图片。
