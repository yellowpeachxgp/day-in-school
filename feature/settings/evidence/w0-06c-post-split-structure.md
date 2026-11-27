# W0-06c 拆分后结构证据

> 本文件记录纯搬移后的结构、行数、夹具覆盖与危险入口状态。开发机门禁命令统一写作 `<devbox>` / `<campus-root>`，不写真实地址或账号。

## 1. 关键文件行数与短哈希

| 文件 | 行数 | SHA-256 前 16 位 |
|---|---:|---|
| `SettingsScreen.kt` | 423 | `10f26cd6b92d71c3` |
| `CampusHubScreens.kt` | 39 | `b0f5909b30daf792` |
| `CampusMomentsScreen.kt` | 15 | `50e957fee21878f8` |
| `LibrarySeatBookingScreen.kt` | 21 | `54956b2a32f2b241` |
| `moments/ui/CampusMomentsScreen.kt` | 1159 | `f2c33ae597ca8927` |
| `moments/ui/MomentsCards.kt` | 594 | `f9261eb9efc70d4d` |
| `moments/ui/MomentsDetailAndDialogs.kt` | 1321 | `1cc73a6294fde26d` |
| `library/seat/ui/LibrarySeatBookingScreen.kt` | 1706 | `1466c2b28c837922` |
| `library/seat/ui/AutomatedLibrarySeatBookingWorkbench.kt` | 1931 | `40e8ec6b57982a58` |
| `root/home/ui/SettingsHomeSection.kt` | 930 | `a459de70a90515a4` |
| `root/academic/ui/SettingsAcademicSection.kt` | 1266 | `a01a0ee5a2b1dac4` |

## 2. 结构覆盖

- Route 文件数：16
- ViewModel/分区状态 holder 文件数：16
- PreviewFixtures 文件数：16
- 四个既有状态机及测试均位于 `emptyclassroom/state`、`library/state`、`moments/state` 对应树。
- 根 `SettingsScreen.kt` 现在只做顶栏、共享反馈、同步记录和 Home/Academic/Theme/Data/Network/Update 分区装配；没有总 ViewModel。

## 3. 依赖与危险入口静态检查

- `feature/settings/**/ui/** import Repository/ViewModel` 命中：`无`
- `showInstallPluginDialog`：`false`
- `添加扩展` 文案：`false`
- `pluginNameInput/pluginIdInput`：`false`
- `InstalledExtensionPluginEntity(` 直接构造：`false`
- 结论：W6-HOTFIX 要求的隐藏手填扩展入口未复活；既有扩展启停/移除仍保留。

## 4. 门禁记录

- 拆分前 `quick :feature:settings`：exit 0（基线 SHA）。
- 当前最新 `quick :feature:settings`：exit 0（Roborazzi 测试与隐藏入口守卫已纳入）。
- `android` 与 `secrets` 最终结果以 PR 汇总为准。
