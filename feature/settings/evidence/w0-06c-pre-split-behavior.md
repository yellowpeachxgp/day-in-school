# W0-06c 拆分前行为证据（静态基线）

> 目的：在纯搬移前固化 feature/settings 的文件、公开入口、关键接缝与危险入口状态，供审查者核对“行为不变”。本文不包含开发机地址、账号或校园根路径。

- 基线 SHA：`338e5c32fe6b545238f60898640833b420dc809e`（worktree 创建点即最新 `origin/main`）
- 基线门禁：`ssh <devbox> <campus-root>/bin/cis-gate day-in-school feat/cis-w0-06c-settings-split quick :feature:settings` 已在拆分前执行，退出码 0，`BUILD SUCCESSFUL`，SHA 与上行一致。
- 证据等级：静态源码哈希与 JVM 门禁输出；不是 DEVICE_TESTED / SCHOOL_VERIFIED。

## 1. 文件行数与 SHA-256

| 文件 | 行数 | SHA-256 |
|---|---:|---|
| `CampusHubScreens.kt` | 3066 | `48f6b3a75b408622a6adb2bfe28f0301c3cf773701558ea739d51d7c1883a8d3` |
| `CampusMomentsScreen.kt` | 2876 | `3728e3a7e1e5573fffb9c759303a706318034ed1ae584171992d495740f3fcdb` |
| `DormElectricityScreen.kt` | 1029 | `5c81ff3711990977a2454aab0d20640ebb7d5c7bcca392908f90a95958425392` |
| `EmptyClassroomSlotController.kt` | 111 | `d2588afc923b36b97781174a10e0910a41f42608b00ef6de82a7e4bb89e9f777` |
| `LibraryBookingDateState.kt` | 177 | `9e0982a1c77ea421ae9b4100fa917684e306c1d130c471088a855628e3c05b2b` |
| `LibraryBookingSubmitStateMachine.kt` | 416 | `8c3de3f8046b8711f4de8f0354ad7473a6e93b2782a1bcc2b17417ff1146dc08` |
| `LibraryCatalogScreen.kt` | 983 | `42d03f114d73607791c779c2b170a657bc4f07477c0f429feaedba3d635499da` |
| `LibrarySeatBookingScreen.kt` | 3484 | `bfa8ba3c1bf1ec6203c52c8ee3761ea616b6dbaf4de285b80dfeaa3880cbda0a` |
| `MomentsContactComposerStateMachine.kt` | 387 | `01d613d1b86a51076a33a1c8f2258b77158f4e4b8108c7ec0d4bfe9f57675386` |
| `ParcelHubScreen.kt` | 1462 | `c1a869343b8837eddabf6e27e3e55df71fc9ec76984d16eebbaaaba55ac2ad0e` |
| `SchoolCenterScreen.kt` | 804 | `e3c7b70c15700bd58af96203b806ae1c5d7d5d53a48e7aa29b05b653b7c1da42` |
| `SettingsScreen.kt` | 4520 | `dbe0936e71f71b00662796e2bae60d77d2a61a8752c1d0ea836d18433761a708` |
| `StudyFocusScreen.kt` | 474 | `2db04e350b61efd59ba53b9b13748f4e3a97c632b84eef7ba0bcaf9a6476e1ad` |
| `XPeachCloudAccountScreen.kt` | 1077 | `ed86f29c19df06cfa32f4de8687920e8040ab2d07b2c3b74dd8d3fc3b6c603d5` |

## 2. 公开入口签名（调用方不得变化）

- `CampusHubScreens.kt:117` `fun EmptyClassroomScreen( repository: CampusRepository, onNavigateToMapWithRoom: (String) -> Unit, onBack: () -> Unit, nowProvider: () -> ZonedDateTime = { ZonedDateTime.now(HAUT_ZONE_ID) } ) {`
- `CampusHubScreens.kt:540` `fun CampusCommunityScreen( repository: CampusRepository, initialTab: Int = 0, onOpenMoments: () -> Unit = {}, onBack: () -> Unit ) {`
- `CampusMomentsScreen.kt:102` `fun CampusMomentsScreen( repository: CampusRepository, initialTab: Int = 0, onBack: () -> Unit ) {`
- `DormElectricityScreen.kt:88` `fun DormElectricityScreen( repository: CampusRepository, onBack: () -> Unit ) {`
- `LibraryCatalogScreen.kt:115` `fun LibraryCatalogScreen( onBack: () -> Unit ) {`
- `LibrarySeatBookingScreen.kt:156` `fun LibrarySeatBookingScreen( onBack: () -> Unit, repository: CampusRepository? = null, initialViewMode: Int = 1, onNavigateToXpeachCloud: (() -> Unit)? = null ) {`
- `ParcelHubScreen.kt:92` `fun ParcelHubScreen( repository: CampusRepository, onBack: () -> Unit ) {`
- `SchoolCenterScreen.kt:90` `fun SchoolCenterScreen( onBack: () -> Unit, directory: SchoolDirectory = remember { SchoolDirectory.builtIn() }, onOpenOfflineImporter: (() -> Unit)? = null, onSelectSchool: ((SchoolProfile) -> Unit)? = null, sessions: AccountSessionCoordinator? = null, activeSession: ActiveSession? = null, onSwitchProfile: ((String) -> Unit)? = null ) {`
- `SettingsScreen.kt:156` `fun SettingsScreen( repository: CampusRepository, onBack: (() -> Unit)? = null, onNavigateToImporter: () -> Unit = {}, onNavigateToMapWithRoom: (String) -> Unit = {}, onNavigateToElectricity: () -> Unit = {}, onNavigateToLibraryCatalog: () -> Unit = {}, onNavigateToLibrarySeats: () -> Unit = {}, onNavigateToCloudSeatBooking: () -> Unit = onNavigateToLibrarySeats, onNavigateToFocus: () -> Unit = {}, onNavigateToEmptyClassrooms: () -> Unit = {}, onNavigateToCommunity: () -> Unit = {}, onNavigateToCompetitions: () -> Unit = onNavigateToCommunity, onNavigateToFeedbackChannels: () -> Unit = onNavigateToCommunity, onNavigateToMoments: () -> Unit = onNavigateToCommunity, onNavigateToSchoolCenter: () -> Unit = {}, onNavigateToParcelHub: () -> Unit = {}, onNavigateToXpeachCloud: () -> Unit = {}, activeSchool: SchoolProfile = SchoolDirectory.hautProfile(), activeProfileLabel: String = "默认账号", resetTrigger: Int = 0 ) {`
- `StudyFocusScreen.kt:74` `fun StudyFocusScreen( repository: CampusRepository, onBack: () -> Unit ) {`
- `XPeachCloudAccountScreen.kt:77` `fun XPeachCloudAccountScreen( repository: CampusRepository, onBack: () -> Unit, onNavigateToCloudSeatBooking: () -> Unit = {} ) {`

## 3. 关键接缝快照

- W0-02 DataStore key `LIVE_PROGRESS_REFRESH_MINUTES` 存在：`true`
- W0-02 偏好字段 `liveProgressRefreshMinutes` 在 Repository 读取/写回出现：`2 处`
- 拆分前根设置页尚未渲染“实况进度刷新频率”行：`false`（本卡只保留配置缝，不夹带新行为）
- 基线 main 的手填扩展状态字段存在：`true`；W6-HOTFIX 要求的最终状态是该入口隐藏/删除，拆分后不得复活手填安装路径。
- 手填扩展直接构造实体存在：`true`；拆分后应为 false。

## 4. 验收对照

- 拆分后同名公开 Composable 仍由原包路径暴露（薄 Route/兼容壳），MainActivity 等调用方不变。
- `feature/settings/**/ui/**` 不得 import Repository 或 ViewModel。
- 原有 4 个状态机测试只随包搬移，不断言文本变更。
