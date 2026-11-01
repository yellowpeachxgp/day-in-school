# HAUTPocket 迁移溯源与合规审计台账 (Migration Provenance Ledger)

本文档记录「日在校园 (Day in School)」从上游开源项目 **HAUTPocket（口袋工大）** 迁移、改编或参考各功能模块时的源文件级溯源台账、哈希校验值、许可证兼容性及真实环境验证状态。

---

## 1. 审计基准信息

| 项目 | 内容 |
| --- | --- |
| **上游仓库** | `https://github.com/rainthink-vanilla/HAUTPocket` |
| **上游锁定 Commit** | `aa83d6f7200ebde4a816e91191f19d9990563362`（Tag: `v1.0.1`） |
| **上游许可证** | MIT License（原文存档于 [`licenses/HAUTPocket-MIT.txt`](../licenses/HAUTPocket-MIT.txt)） |
| **目标仓库** | `day-in-school`（Apache License 2.0，见 [`LICENSE`](../LICENSE) 与 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)） |
| **许可证兼容性结论** | 上游 MIT 许可证允许在保留版权与许可声明的前提下复制、修改并再分发于 Apache-2.0 项目中。上游明确声明不含第三方权利的图标插画/校徽（`artwork/launcher.jpg`）及发行签名证书（`signing/*`）已全部排除。 |

### 复用模式（Reuse Mode）定义

- **`Direct Copy (Asset)`**：直接复用只读前端注入脚本、样式表或合成测试样本，保持字节一致或仅做必要的包路径适配。
- **`Adapted / Ported`**：移植核心业务代码、领域模型、算法或界面组件，并重构包名（`cn.edu.haut.campus.*`）、存储层（由单体 `PocketStore` JSON 迁移至 Room / DataStore）与模块依赖。
- **`Referenced Only`**：仅吸收接口路径、边界条件、正则表达式或会话管理策略，在目标仓库既有实现上增量扩展。
- **`Excluded / Discarded`**：明确丢弃，不进入目标仓库源码树或构建产物。

---

## 2. 源文件 → 目标文件映射台账

> 注：`Source SHA` 列同时给出 Git Blob SHA-1（前 10 位）与文件 SHA-256（前 16 位），均在锁定提交 `aa83d6f7200ebde4a816e91191f19d9990563362` 下计算得出。

### 2.1 水电模块（完美校园智能水电查询、周期采样与手工抄表）

| Source file | Source SHA (Blob / SHA-256) | Target module/file | Reuse mode | License | Verification status |
| --- | --- | --- | --- | --- | --- |
| `app/src/main/java/cn/hautpocket/app/data/WanxiaoClient.kt` | `4c68316702` / `86421c7bc41b9c5d` | `:core:academic`（或 `:core:database` 水电客户端封装）<br>`WanxiaoClient.kt` / `UtilityServiceClient.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 协议解析与两层 JSON 响应通过 5 项单测；使用 `customercode=43` 只读查询 `getbindroom` / `h5_getstuindexpage`，**真实账号与双校区租户归属待实测验收** |
| `app/src/main/java/cn/hautpocket/app/data/DailyElectricity.kt` | `91da83d483` / `56e6053302e3d39a` | `:core:database` / `:core:reminder`（或水电后台采样模块）<br>`DailyElectricity.kt` / `ElectricitySamplingWorker.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 采样窗口（日间 60m / 23点后 15m）、跨日丢弃、kWh 单位规范化及同名设备去重通过单测；真机 WorkManager 长期后台表现受系统省电策略限制 |
| `app/src/main/java/cn/hautpocket/app/data/AccountVault.kt` | `9cd1eb1b60` / `0aa862e44d02fefa` | `:core:database`<br>`AccountVault.kt` / `CampusRepository.kt` | `Adapted / Ported` | MIT → Apache-2.0 | Android Keystore `AES/GCM/NoPadding` 本机加密保存水电账号；临时 `roomverify` 仅留存内存，不落盘 |
| `app/src/main/java/cn/hautpocket/app/domain/Models.kt`（`Campus` / `MeterKind` / `Meter` / `Reading` / `OnlineDevice` / `OnlineRoom` / `consumption` / `removeReading`） | `66a0c0007b` / `831b882ad3f022a4` | `:core:model` & `:core:domain`<br>`UtilityModels.kt` / `UtilityCalculator.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 手工读数区间净耗量计算、充值未知不连线、删除中间读数自动使后一段充值量失效均已通过 JVM 单元测试 |
| `app/src/main/java/cn/hautpocket/app/ui/UsageLineChart.kt` | `db81405e3f` / `48f6c05c89f27893` | `:feature:settings`（或独立校园服务/宿舍页）<br>`UsageLineChart.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 在线与手工用电折线图 Canvas 组件（含纵轴刻度、48dp 点击热区、断档不连线与详情面板），UI 编译与 Lint 通过 |
| `app/src/main/java/cn/hautpocket/app/ui/WeekAndUsage.kt`（宿舍与用量面板部分） | `07104d30c7` / `b982334c7eacb58a` | `:feature:settings`（或独立校园服务/宿舍页）<br>`DormUtilityScreen.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 房间校区人工确认（莲花街/嵩山路）、在线表计卡片、采样状态与手工抄表管理界面，已通过编译与单测 |
| `app/src/test/java/cn/hautpocket/app/domain/WanxiaoProtocolTest.kt` | `5da27ddc9f` / `23e3252f9c1b3abd` | `:core:academic` / `:core:domain` `src/test`<br>`WanxiaoProtocolTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 覆盖单房间/多房间、表计类型过滤、服务端原样单位保留与异常响应拦截，JVM 单测通过 |
| `app/src/test/java/cn/hautpocket/app/domain/DailySamplingTest.kt` | `c76d3fba44` / `e1ec00fbd85a4506` | `:core:domain` `src/test`<br>`DailySamplingTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 覆盖北京时间跨午夜判断、日间/夜间采样间隔、kWh 单位提取与非法金额排除，JVM 单测通过 |

### 2.2 馆藏模块（移动馆藏检索、DOM 快照提取与封面加载）

| Source file | Source SHA (Blob / SHA-256) | Target module/file | Reuse mode | License | Verification status |
| --- | --- | --- | --- | --- | --- |
| `app/src/main/java/cn/hautpocket/app/domain/CatalogSnapshot.kt` | `b6d89cdca3` / `a9c2b0d199445a76` | `:core:model` / `:core:domain`<br>`CatalogSnapshot.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 书目列表、馆藏详情及公开 HTTPS 封面域名白名单（`libspace.haut.edu.cn`、`img.hwlsp.cn`、`*.ddimg.cn`）校验通过单测与真机验证 |
| `app/src/main/java/cn/hautpocket/app/CatalogActivity.kt` | `b3e5c7d675` / `f25ce0d936661ff6` | `:feature:settings`（或图书馆功能模块 / `:app`）<br>`CatalogActivity.kt` / `LibraryCatalogHost.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 复用官方 `libspace.haut.edu.cn` 会话，通过 `AlphaCommonHeader.onSearch` 触发检索并支持切换「完整检索」完成学校认证，已通过 v0.8.0/v1.0.0 真机搜索与馆藏详情验收 |
| `app/src/main/java/cn/hautpocket/app/ui/CatalogScreen.kt` | `d67cd42169` / `fe4ca5e8491351de` | `:feature:settings`（或图书馆功能模块）<br>`CatalogScreen.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 原生 Compose 书目卡片、馆藏条码/索书号/可借状态列表、封面大图预览与「暂无封面」占位说明，已通过真机验收 |
| `app/src/main/java/cn/hautpocket/app/LibraryPageDesign.kt` | `2d064f08a9` / `4ef942a8bde19a29` | `:feature:settings`（或图书馆功能模块）<br>`LibraryPageDesign.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 将应用明暗主题配色与字号比例序列化为 CSS 变量注入馆藏/选座 WebView，支持一键切回原版网页 |
| `app/src/main/assets/catalog-snapshot.js` | `92082a33c5` / `413f92acf9125d63` | `app/src/main/assets/catalog-snapshot.js` | `Direct Copy (Asset)` | MIT → Apache-2.0 | 仅在已核实的 `libspace.haut.edu.cn` 检索路由提取公开 DOM 书目与馆藏节点，不读取 Cookie/令牌；语法检查与真机验证通过 |
| `app/src/main/assets/catalog-design.js` | `e5b2323b23` / `1fecc90ca9f20a66` | `app/src/main/assets/catalog-design.js` | `Direct Copy (Asset)` | MIT → Apache-2.0 | 移动馆藏网页（`/mspace/index`、`/mspace/search`、`/mspace/searchList`、`/mspace/searchDetailLocal/*`）展示增强脚本，已通过真机预览验证 |
| `app/src/main/assets/catalog-design.css` | `f00f3c69cd` / `a90c9eee144e89a6` | `app/src/main/assets/catalog-design.css` | `Direct Copy (Asset)` | MIT → Apache-2.0 | 移动馆藏网页展示层样式表，隔离首页排行榜与搜索结果样式，已通过真机验证 |
| `app/src/test/java/cn/hautpocket/app/domain/CatalogSnapshotTest.kt` | `e38797c362` / `82bc67a6225def10` | `:core:domain` `src/test`<br>`CatalogSnapshotTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 覆盖书目解析、馆藏状态保留、封面 URL 域名与查询参数过滤、非法页面拒绝，JVM 单测通过 |

### 2.3 选座与学校网页会话模块（图书馆空间预约 H5 与共享 WebView 会话）

| Source file | Source SHA (Blob / SHA-256) | Target module/file | Reuse mode | License | Verification status |
| --- | --- | --- | --- | --- | --- |
| `app/src/main/java/cn/hautpocket/app/domain/OfficialServices.kt` | `006a5f7557` / `e274cefd0bc5222e` | `:core:domain` / `:core:academic`<br>`OfficialServices.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 官方 HTTPS 域名白名单（`*.haut.edu.cn`、`*.17wanxiao.com`）、子框架 `about:blank` 放行、旧 HTTP 教务 GET 升级 HTTPS、选座 H5 与 CAS 登录路径判定，单测与真机验证通过 |
| `app/src/main/java/cn/hautpocket/app/OfficialWebSession.kt` | `020b1d8227` / `3b03919e9cfc108c` | `:core:academic` / `:feature:importer`<br>`OfficialWebSession.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 教务、查书与选座共享应用私有 WebView Cookie 与 DOM Storage，后台串行队列执行 `CookieManager.flush()`，支持用户主动一键清除，已在 v1.0.1 真机验证免重登有效 |
| `app/src/main/java/cn/hautpocket/app/OfficialWebActivity.kt` | `c554bfa04e` / `b380c657a1fbfbae` | `:feature:importer` / `:feature:settings`<br>`OfficialWebActivity.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 承载教务网页课表导入、图书馆选座（`wslib.haut.edu.cn`）展示增强开关及学校统一认证返回后的一次 hash 导航回跳（`SelectRoom`）；**完整提交预约未代用户执行** |
| `app/src/main/assets/library-design.js` | `4a55b61395` / `e6eafa57972ca926` | `app/src/main/assets/library-design.js` | `Direct Copy (Asset)` | MIT → Apache-2.0 | 仅作用于 `SelectRoom` / `SelectSeat` 路由，提供区域与座位名称搜索、只看可选过滤及图例状态映射，保留官方 DOM 节点与点击事件，真机验证通过 |
| `app/src/main/assets/library-design.css` | `29b28d14f0` / `e9cedae65bc0943d` | `app/src/main/assets/library-design.css` | `Direct Copy (Asset)` | MIT → Apache-2.0 | 选座页面响应式网格与底部工具栏样式，支持一键切回原版移除全部自定义样式，真机验证通过 |
| `app/src/test/java/cn/hautpocket/app/domain/LibraryNavigationTest.kt` | `229669a4aa` / `e80af088c2e58941` | `:core:domain` `src/test`<br>`LibraryNavigationTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 覆盖图书馆选座 H5、馆藏路由与 CAS 登录回跳白名单及伪造域名/HTTP/非标端口拦截，JVM 单测通过 |
| `app/src/test/java/cn/hautpocket/app/domain/OfficialNavigationTest.kt` | `35e52dd749` / `e771764c001c5229` | `:core:domain` `src/test`<br>`OfficialNavigationTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 覆盖内部空白 iframe 放行、旧 HTTP 教务 GET 链接升级 HTTPS（拒绝 POST 重放）及诊断来源脱敏，JVM 单测通过 |

### 2.4 专注模块（番茄钟倒计时、幂等完成与后台闹钟通知）

| Source file | Source SHA (Blob / SHA-256) | Target module/file | Reuse mode | License | Verification status |
| --- | --- | --- | --- | --- | --- |
| `app/src/main/java/cn/hautpocket/app/domain/FocusCompletion.kt` | `ae52a45940` / `aca52317fa89b375` | `:core:domain`<br>`FocusCompletion.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 校验 `expectedEnd` 与当前会话结束时刻，确保前台倒计时与后台闹钟广播并发触发时仅记录一次完成会话，单测通过 |
| `app/src/main/java/cn/hautpocket/app/data/FocusReminders.kt` | `e589095fa8` / `160f955514f71f01` | `:core:reminder`<br>`FocusReminders.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 基于 `AlarmManager`（`FLAG_RECEIVER_FOREGROUND`）调度精确/非精确提醒、重启与更新后恢复（过期超 15 分钟仅入账不补弹通知）及 10 秒测试通知，已在小米/澎湃 OS 真机验证后台送达 |
| `app/src/main/java/cn/hautpocket/app/data/FocusDelivery.kt` | `368d521a5a` / `a941324e12f79853` | `:core:reminder`<br>`FocusDelivery.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 记录测试提醒的计划时刻、广播接收时刻、前后台状态与系统提交通知时刻，用于排查国产 ROM 后台拦截，已通过真机验证 |
| `app/src/main/res/drawable/ic_stat_focus.xml` | `852aead561` / `4837c0802eca338a` | `:core:reminder` / `:app`<br>`res/drawable/ic_stat_focus.xml` | `Direct Copy (Asset)` | MIT → Apache-2.0 | 纯矢量通知栏小图标（不含任何校徽或第三方插画），符合通知栏单色规范 |
| `app/src/test/java/cn/hautpocket/app/domain/FocusCompletionTest.kt` | `dcbd36c480` / `afbc34c596e97067` | `:core:domain` `src/test`<br>`FocusCompletionTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 覆盖前台与闹钟重复触发幂等、提前取消后旧闹钟不误入账、新一轮会话隔离，JVM 单测通过 |

### 2.5 课表互通与正方网页显示修复模块（`univ-timetable/v1` 与 N9 白屏修复）

| Source file | Source SHA (Blob / SHA-256) | Target module/file | Reuse mode | License | Verification status |
| --- | --- | --- | --- | --- | --- |
| `app/src/main/java/cn/hautpocket/app/domain/TimetableFile.kt` | `50067b75a7` / `bbf07f66e5ec7847` | `:core:academic` / `:core:domain`<br>`TimetableFile.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 兼容 `univ-timetable/v1` JSON、Markdown 内嵌 JSON 代码块及 Markdown 课程明细表/作息表解析，已通过真实导出样本互通测试 |
| `app/src/main/java/cn/hautpocket/app/domain/TimetableExport.kt` | `df344c55a3` / `73ac1f74c0f3395d` | `:core:academic` / `:core:domain`<br>`TimetableExport.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 将本地课表导出为符合 `univ-timetable/v1` 契约的 JSON（不连续节次自动拆分为连续安排，不含水电账号或会话数据），已通过双向互通校验 |
| `app/src/main/java/cn/hautpocket/app/domain/ScheduleParser.kt` | `fce5dcfc75` / `993b1d8122d19c97` | `:core:academic` / `:core:domain`<br>`ScheduleParser.kt` / `HautAcademicProvider.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 支持正方 `kbList`、`univ-timetable/v1` 与简化 `courses` 格式，兼容「第19周」等带「第」前缀的单周/分段/单双周解析，已在 v1.0.1 经真实账号验证消除解析漏项 |
| `app/src/main/java/cn/hautpocket/app/domain/ScheduleImport.kt` | `da0a5aa155` / `4288db06ad496e2d` | `:core:domain` / `:feature:importer`<br>`ScheduleImport.kt` / `ImportDiffEngine.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 支持整体替换（`REPLACE`）、更新合并（`MERGE`）与追加（`APPEND`）三种导入模式及可选同步校历/作息，单测通过 |
| `app/src/main/java/cn/hautpocket/app/domain/CourseBlocks.kt` | `8aec7c4688` / `fd9fe3c356075d2c` | `:core:domain`<br>`CourseBlocks.kt` / `ScheduleEngine.kt` | `Referenced Only` | MIT → Apache-2.0 | 同日、同名、同教师、同教室、同校区的相邻连续节次显示层合并逻辑（不篡改底层原始导入记录，中间空节不合并），单测通过 |
| `app/src/main/assets/teaching-layout.js` | `1d7de99b53` / `8ebbb5d8c32bc7ed` | `app/src/main/assets/teaching-layout.js` | `Direct Copy (Asset)` | MIT → Apache-2.0 | 针对正方 N9 课表查询页 `/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html` 在 WebView 下 `html/body` 百分比高度计算为 0 导致整页白屏的问题，恢复 `height:auto` 与纵向滚动；已在 v1.0.1 真机验证生效 |
| `app/src/main/java/cn/hautpocket/app/ui/ScheduleImportDialog.kt` | `1f3cb2f8b8` / `aa15c0b1ca6d862d` | `:feature:importer`<br>`ImporterScreen.kt` / `ScheduleImportDialog.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 导入预览弹窗（展示有效课程数、跳过条数、警告提示、导入模式切换及是否应用文件中的校历/作息） |
| `app/src/test/java/cn/hautpocket/app/domain/TimetableCompatibilityTest.kt` | `1c6b87bdcb` / `e18d55a701be88d8` | `:core:domain` / `:core:academic` `src/test`<br>`TimetableCompatibilityTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 验证 `univ-timetable-export.md` 读取、Markdown 表格解析、替换/合并/追加模式及反向 JSON 导出，JVM 单测通过 |
| `app/src/test/java/cn/hautpocket/app/domain/ScheduleParserTest.kt` | `555b68e81c` / `846a79764478b836` | `:core:domain` / `:core:academic` `src/test`<br>`ScheduleParserTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 覆盖单双周、分段周次、「第N周」、不连续节次与空/坏课表拒绝，JVM 单测通过 |
| `app/src/test/java/cn/hautpocket/app/domain/CourseBlocksTest.kt` | `d86c9205dd` / `3e55003cb168fab9` | `:core:domain` `src/test`<br>`CourseBlocksTest.kt` | `Adapted / Ported` | MIT → Apache-2.0 | 覆盖相邻节次合并、跨节不合并、不同教师/教室/校区不合并，JVM 单测通过 |
| `app/src/test/resources/univ-timetable-export.md` | `e7cdd8fe62` / `b9d6afe2a53eab9f` | `:core:domain` / `:core:academic` `src/test/resources`<br>`univ-timetable-export.md` | `Direct Copy (Asset)` | MIT → Apache-2.0 | 合成课程与作息的 `univ-timetable/v1` 标准测试样本（不含任何真实学生信息或凭据） |

### 2.6 通用外观辅助与明確丢弃项（Discarded / Excluded Items）

| Source file | Source SHA (Blob / SHA-256) | Target module/file | Reuse mode | License | Verification status / 处置原因 |
| --- | --- | --- | --- | --- | --- |
| `artwork/launcher.jpg` | `00f5153aed` / `fed50384775bd65c` | **无（严禁引入）** | `Excluded / Discarded` | 第三方插画/校徽（非 MIT 授权范围） | **已丢弃**：含用户提供的插画、水印与学校校徽，上游声明 MIT 许可证不授予该美术资源权利，本项目保留自绘矢量图标 `ic_launcher_vector.xml` |
| `app/src/main/res/drawable-nodpi/ic_launcher_artwork.png` | `aa0e6f7315` / `104d19e6081609cf` | **无（严禁引入）** | `Excluded / Discarded` | 第三方插画/校徽（非 MIT 授权范围） | **已丢弃**：由 `artwork/launcher.jpg` 缩放生成的应用图标位图，不复制 |
| `app/src/main/res/mipmap-anydpi/ic_launcher.xml` | `26af9de6fe` / `2f9f31dcc1a23db9` | **无（严禁引入）** | `Excluded / Discarded` | MIT（但引用上述非授权位图） | **已丢弃**：引用 `ic_launcher_artwork`，本项目使用既有自适应图标配置 |
| `tools/generate-launcher-icons.ps1` | `4dd37bb876` / `c3bd55a020d1b2d0` | **无（严禁引入）** | `Excluded / Discarded` | MIT | **已丢弃**：用于裁剪 `artwork/launcher.jpg` 的 Windows PowerShell 脚本，不需要 |
| `signing/lineage.bin` & `signing/release-certificate.pem` | `3e8c478ad6` / `73911568f5` | **无（严禁引入）** | `Excluded / Discarded` | 上游维护者签名材料 | **已丢弃**：上游仓库 APK 签名证书与轮换证明，与本项目签名体系无关 |
| `tools/build.ps1`, `tools/package.ps1`, `tools/setup-android.ps1`, `tools/setup-release-signing.ps1`, `tools/sign-release.ps1`, `tools/github-release.py`, `tools/audit-source.py`, `tools/timetable-interop.cjs` | 见 Git 树列表 | **无** | `Excluded / Discarded` | MIT | **已丢弃**：上游针对 Windows 便携工具链与手工发布的辅助脚本；本项目使用标准 Gradle Wrapper 与 GitHub Actions 工作流 |
| `app/src/main/java/cn/hautpocket/app/data/PocketStore.kt` | `9e5e043a8f` / `c7bddc66bf628e95` | `:core:database`（由 Room + DataStore 替代） | `Referenced Only` | MIT → Apache-2.0 | **不照搬单体 `SharedPreferences` JSON 存储**：仅参考其字段默认值与并发更新锁语义，持久化统一收敛至 `:core:database` |
| `app/src/main/java/cn/hautpocket/app/domain/TextColors.kt`, `ui/ColorPickerDialog.kt`, `ui/TextColorDialog.kt` | `353e6b018a` / `333eb73bf1` / `0e23925e9b` | `:core:designsystem` / `:feature:settings`（按需参考） | `Referenced Only` | MIT → Apache-2.0 | 参考 WCAG 2.2 相对亮度对比度计算公式（`TextColors.contrast`）与馆藏/选座 WebView 文字配色映射；应用主界面继续采用 `:core:designsystem` 的 `HautTheme` |
| `app/src/main/java/cn/hautpocket/app/MainActivity.kt`, `ui/PocketApp.kt`, `ui/Theme.kt`, `ui/DesignComponents.kt` | `dba7abc5ea` / `757bf84334` / `f94da13cb7` / `2e172e8266` | `:app` & 各 `:feature:*` 模块 | `Referenced Only` | MIT → Apache-2.0 | **不替换本项目既有 19 模块导航与 UI 架构**：仅将水电、馆藏、选座、专注及课表互通能力集成至本项目对应 Feature 页面 |

---

## 3. 真实环境验证边界与安全合规准则（摘自上游验证记录）

为确保功能描述客观真实，迁移后的模块严格遵循上游 `docs/INTEGRATIONS.md`、`docs/WEB-SESSIONS.md` 与 `docs/VERIFICATION.md` 确立的验证边界与安全准则：

1. **教务网页登录与课表导入（已由用户真机验证）**：
   - 官方正方 N9 课表查询页（`/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html`）的 `teaching-layout.js` 白屏修复、「第N周」周次解析、子框架 `about:blank` 放行、旧 HTTP 教务 GET 链接自动升级 HTTPS 以及 WebView 会话持久化，已在上游 `v1.0.1` 经真实学生账号本机验证通过。
   - 原生代码不读取用户在学校网页输入的密码，不导出或备份 Cookie/令牌。
2. **图书馆馆藏检索与座位预约（查书已真机验证，选座仅展示增强与登录回跳）**：
   - 馆藏检索基于 `libspace.haut.edu.cn` 官方移动页面提取公开 DOM 快照，已在真机验证搜索与馆藏状态展示；封面仅加载白名单域名下的无参数公开 HTTPS 图片，不携带 WebView 凭据，且不在安装包内打包任何图书封面。
   - 座位预约内嵌 `https://wslib.haut.edu.cn/h5/index.html#/seatPage/SelectRoom/SelectRoom`（不含任何捕获的 token 参数），`library-design.js` 仅做前端布局增强与本地筛选，不调用非官方选座 API、不自动抢座、不代用户提交预约；认证返回后的自动/手动回跳已在单测与真机登录流程中核实，但完整预约提交仍由用户在官方页面内自行完成。
3. **完美校园 · 智能水电（协议与采样已单测，待真实账号端到端核验）**：
   - 接口地址 `https://xqh5.17wanxiao.com/smartWaterAndElectricityService/SWAEServlet` 与河南工业大学历史代码 `customercode=43` 源自公开社区记录；仅实现只读查询（`getbindroom`、`h5_getstuindexpage`），不枚举他人学号、不猜房间号、不实现绑定/解绑/充值写操作。
   - 服务端返回的余额（`odd`）、累计购入（`sumbuy`）保持原文展示，不擅自推断货币或物理单位；今日电量（`todayuse`）经用户确认按 `kWh` 规范化并记录趋势。**当前租户代码有效性及莲花街/嵩山路双校区表计规则仍需真实绑定账号实测确认。**
4. **专注提醒（已在真机验证后台闹钟送达）**：
   - 采用系统 `AlarmManager` 显式广播与 `expectedEnd` 幂等校验，取消或提前结束立即撤销闹钟；在小米/澎湃 OS 等系统上需用户开启通知权限及后台自启动/无限制省电策略以确保桌面横幅送达，应用提供 10 秒测试提醒与投递时间诊断，不伪造常驻前台服务。
