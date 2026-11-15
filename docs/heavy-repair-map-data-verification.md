# 地图 / 校园目录 / 数据核验修复台账（heavy-repair 批次）

本文档汇总 GitHub issue 修复计划中「地图 / 校园目录 / 数据核验」范围（#5、#10、#13、#14、#16、#17、#18/A5）的
代码级验证证据、自动化测试矩阵与遗留待办（设备 / GPS / 产品决策）。

> 范围声明：本批次仅覆盖**代码可验证**部分。不做真实 GPS / 设备验证、不伪造核验证据、不关闭 issue。
> 凡需要产品/运营决策或真机证据的条目均标注为 pending。

## 1. 逐 issue 验证矩阵

| Issue | 代码/测试已验证 | 仍待办（设备 / 数据 / 产品决策） | 相关测试 |
| --- | --- | --- | --- |
| #14 工大蓝主题 | PresetThemePalette.HAUT_BLUE 种子精确为 0xFF053968；浅色 primary / onPrimaryContainer 精确为 #053968（Robolectric + buildSeedColorScheme）；DataStore 层：HAUT_BLUE + 旧种子 0xFF0066CC、无种子、无 palette 三种旧状态均解析为 #053968；CUSTOM 主题（含相同 0xFF0066CC 或其他用户色）绝不迁移；DataStore 文件级重建后主题保持（模拟 recreate）；getActiveThemeAccentArgb 与 palette 联动正确 | 真机视觉走查（色板渲染）；SettingsScreen 提示文案需 UI 走查 | LiquidGlassThemeSdkTest.hautBluePaletteSeedIsExactSchoolBadgeBlue053968、HautBlueThemeSchemeTest（3）、HautBlueThemePersistenceTest（4） |
| #13 自习室名录 | 嵩山路 17 条候选教室全唯一；SONGSHANLU_STUDY_ROOMS_MAP 每个楼宇键均存在于 ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS 名录；同名房号（嵩山1教101 vs 嵩山2教101）跨楼宇/跨校区不合并；空课表输入 hasScheduleBasis=false（"未观测到冲突" 不冒充 "全部空闲"）；新增 CampusStudyRoomCatalogProvenance 诚实标注 PERSONAL_REFERENCE_NOT_REALTIME、verifiedAt=null（非官方名录） | 名录本身未获校方官方自习室名录核验（pending：数据来源确认）；嵩山路楼宇坐标为地块近似（已有注释说明） | CampusStudyRoomCatalogIntegrityTest（4） |
| #10 电子围栏 | 嵩山路多边形内/外/边界/顶点行为逐点断言（射线法半开语义：南/西边缘与西南顶点判内，北/东边缘判外）；WGS84→GCJ02→WGS84 往返误差实测 ≤2m（断言 <50m 总界 + <25m 回归界）；双校区 GPS 自动识别 + 课表教室文本兜底；accuracyMeters>90m 显式 isLowAccuracyGps 标记而非静默采信；用户手绘电子区划（含圆形回退）优先于内置围栏；手动选择优先级最高 | 真机 GPS 实地走查；嵩山路围栏边界与校方测绘的复核（产品/校方数据） | CampusGeofenceRecognizerTest（5） |
| #5 已停业餐厅 / 2号楼 | 分类见 §2。修复的漏洞：getDirectScheduleDataSnapshot（小组件/提醒数据源）此前未过滤 b_canteen2/b_canteen3，已加 CampusDiningRecommender.CLOSED_CANTEEN_BUILDING_IDS 单一事实源过滤（与 buildingsFlow 一致）。已验证：DB 残留停业餐厅行不出现在 buildingsFlow/buildingAliasesFlow/快照；聚合榜单剔除停业餐厅历史评价；自定义非目录食堂不受影响；用户历史评价 codec 原样保留；"第二餐厅/第三餐厅" 在解析器中无活跃命中 | 嵩山路 2号教学楼（b_ss_t2）数据保留原地（issue 范围未确认，pending 产品决策）；真机 UI 走查确认无入口可达 | ClosedCanteenActivePathTest（4）、CampusDataVerificationRepositoryTest.closedCanteensNeverLeakThroughBuildingsAliasesOrScheduleSnapshot、closedCanteenUserReviewHistoryStaysIntactInPrefs、UserIssuesAndCampusDataRegressionTest（既有） |
| #16 社团场地 | 预置目录 club_partner_computer_service 结构化四字段（莲花街校区 / 学生宿舍F01 / W111 / 完整场地串）正确；云端旧值（8号教学楼 / 大学生活动中心 / campus_name 嵩山路 等错位组合）在解码层四字段同步纠正；离线缓存 JSON 经 preferencesFlow 读取同样被纠正（同一 parseCampusContentJsonString 解码路径）；DataStore 重建（模拟重启）后结果一致；云端下发新场地不被规范化器永久改写；其他社团含相同字样不受影响；幂等（已纠正值二进不出） | 规范化关键词为 contains("8号") 或 contains("活动中心")：若云端未来合法下发含 "活动中心" 的新场地会被误纠——已记录为 ops 注意事项（该社团 ID 限定，风险收敛）；F01/W111 精确坐标仍属宿舍片区近似（seed 注释已标注 verified=false） | CampusClubVenueNormalizationTest（6）、CampusDataVerificationRepositoryTest.cachedLegacyClubVenueNormalizesOnReadAndAfterRestart、refreshedNewerVenueIsNotPermanentlyOverwrittenByNormalizer、CampusServicesUnitTest（既有 #16 用例） |
| #17 药品目录 | 202 条条目 ID 全唯一且 med_pN_M 形态稳定；全部 disclosedPriceYuan 可解析为非负数；批准文号不符合标准国药准字格式的条目全部带 PENDING_OFFICIAL_VERIFICATION + 非空差异说明，无条目标记 VERIFIED；已知差异条目原文逐字保留（如 med_p1_9 的 H10960167）；搜索覆盖中文 / 含空格名称 / 大小写混合拉丁文号 / 规格 / 厂家 / 文号 / 剂型字段；类别交集与无结果行为明确 | 不声称逐行 OCR 核验——贡献者实拍原图不可得，目录保持"原图转录·待核实"标注；价格/医保以校医院窗口实时结算为准（文案已声明） | CampusHospitalPharmacyCatalogTest（6）、UserIssuesAndCampusDataRegressionTest（既有 202 条断言） |
| #18/A5 启动别名 | WidgetRefreshCoordinator.createMainActivityLaunchIntent 生成的 Intent 组件为 cn.edu.haut.campus.MainActivity 本体（targetActivity 为空 = 真 activity 非 alias）；两个 activity-alias 确为 alias 且指向本体；静态守卫：扫描全仓 src/main Kotlin 源码，除 LauncherIconAliasReconciler.kt（职责内）外无任何文件引用别名组件名或 getLaunchIntentForPackage；全部已知 widget/通知启动生产者（ScheduleSnapshotUseCase / ReminderNotificationFactory / FocusAlarmSubsystem / LibrarySeatAlarmSubsystem / DormElectricityNotificationSubsystem）显式引用 MainActivity 本体；5 个小组件 Provider 统一走 createMainActivityLaunchIntent | 真机切换桌面图标后点击 widget/通知的端到端验证（设备测试） | MainActivityLaunchIntentGuardTest（2）、LauncherIconAliasReconcilerTest（既有） |

## 2. #5 数据分类结果（全仓扫描）

对 "2号楼" "2号教学楼" "第二餐厅" "第三餐厅" "b_canteen2" "b_canteen3" 全仓命中逐条分类：

**(a) 合法的 32号楼 / 嵩山路真实数据（保留）**

- ClassroomBuildingResolver.kt：嵩山路2号教学楼（b_ss_t2）楼名、别名 al_ss_t2_*（含 嵩2号楼）、注释说明「2号不得匹配32号楼」防误配
- CampusStudyRoomCatalog.kt：嵩山路2号教学楼 自习室键 + normalizeSongshanRoomKey 对教务原文「嵩山路校区2号教学楼」的楼宇前缀归一化
- HautMockSeedData.kt：32号楼（土建楼）楼宇与 al_4d/al_4e/al_4e2/al_4f 别名、示例排课 32号楼32106
- CampusMapNavigator.kt：32号楼路网节点/支路；HautOfficialGisClient.kt：GIS 13178 → b_t2 绑定
- CampusDiningRecommender.kt：31/32号楼、近1/2号教学楼 区位文案（嵩山路侧指 b_ss_t1/t2）
- CampusRepository.kt:1193：V18 字典同步中删除 b_canteen2/b_canteen3 的清理逻辑（必须保留）

**(b) 历史记录（保留）**

- README.md / RELEASE_NOTES.md 变更说明；用户本地历史餐厅评价（DataStore，codec 原样往返已测试）

**(c) 测试夹具（保留）**

- ScheduleTimelineAndConflictTest、ReminderSystemDomainTest、UserIssuesAndCampusDataRegressionTest 中 32号楼32204、2号教学楼2106（断言不识别校区）等合成数据

**(d) 活跃路径泄漏面（已封堵并测试）**

- buildingsFlow（map/timetable/settings/today 共用）：b_canteen2/b_canteen3 过滤——已验证
- buildingAliasesFlow：al_cant2_*/al_cant3_* 删除别名不复活——已验证
- aggregateDishRatings：停业餐厅历史评价不上榜；自定义非目录食堂不受影响——已验证
- buildRecommendation/rankCanteens：候选池仅 OPERATING_CANTEENS——已验证
- ClassroomBuildingResolver.resolve：「第二餐厅/第三餐厅/二餐厅/三餐厅」无活跃命中——已验证
- getDirectScheduleDataSnapshot（widget/提醒）：本次修复——补 CLOSED_CANTEEN_BUILDING_IDS 过滤，与 buildingsFlow 对齐——已验证

**(e) 待产品决策**

- 嵩山路2号教学楼（b_ss_t2）及其自习室/别名列保留原地——issue #5 对该校区的处置范围未确认，等待产品确认。

## 3. 遗留风险与边界（不伪造证据）

- #13：自习室名录为「教务课表教室文本 × 楼宇名录」交叉整理，未获校方官方自习室名录核验；verifiedAt=null 与 PERSONAL_REFERENCE_NOT_REALTIME 语义已在 CampusStudyRoomCatalogProvenance 中固化。
- #17：药品目录为贡献者实拍转录，原图不可得；本批次只做结构性/搜索/标注一致性自动化校验，不做逐行 OCR 比对。
- #10：GPS/GCJ02 往返与围栏判定已在纯函数层验证；真机定位精度、嵩山路围栏与校方测绘边界复核为 pending。
- #16：规范化器对单社团 ID 生效；若云端为该社团下发含 8号/活动中心 字样的新场地，会被误纠——需 ops 变更时同步评估。
- #14：SettingsScreen 中「已切换为工大校徽深蓝 (#053968)」提示为硬编码文案，UI 层未做运行时断言（palette subtitle 断言已覆盖数据层）。
- #18：别名切换后桌面点击链路的真机回归为 pending；当前静态+Robolectric 守卫覆盖「目标组件」层面。

## 4. 运行记录

（本批次各模块 gradle 测试的执行命令与通过计数见随附修复报告。）
