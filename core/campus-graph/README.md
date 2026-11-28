# `:core:campus-graph`

W1-07 校园实体图内核（纯 JVM）。本模块把楼宇、教室、设施、入口、室内节点、服务与办事流程统一到稳定图 ID：

`<schoolId>/<campusId>/<type>/<slug>`

## 迁移映射评估

| 现有事实源 | 迁入实体 | 说明 |
|---|---|---|
| `core/database` 的莲花街 30 栋 `Building` 与 76 条别名 | `BuildingNode` + `EntranceNode` + 楼内别名 | Android Room 模块不能被纯 JVM 内核依赖，因此本卡只迁移为内核快照；旧种子仍保留，消费方不变 |
| `ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS` 8 栋楼与别名 | `BuildingNode` + `EntranceNode` | 坐标继续使用原有“院区近似定位”来源说明，不拔高验证等级 |
| `CampusStudyRoomCatalog` 自习室名录 | `RoomNode` + 按房间号推导的 `FloorNode` | 空闲语义仍只是个人课表反推参考，不表示校方实时占用 |
| `CampusDiningRecommender` 餐厅与菜品 | 餐厅建筑对应的 `FacilityNode(DINING)` + `ServiceCatalogItem` | 已停业餐厅不进入活跃图；菜品价格与评价快照保留在目录项属性 |
| `CampusHospitalPharmacyCatalog` | `ServiceNode(PHARMACY)` + 药品 `ServiceCatalogItem` | 保留原图转录、待核实和文号差异状态，不生成“医保后价格” |
| `PresetCampusCommunityCatalog` 社团场地 | `FacilityNode(CLUB_VENUE)`（必要时关联 `RoomNode`） | 计算机服务社 F01 W111 按现有纠错后的目录迁移，未核实精确坐标时不编造坐标 |
| 既有楼名入口字符串 | `EntranceNode` | 没有单独坐标的入口继承楼宇坐标并在属性中标记 `coordinatePrecision=building-center` |
| 官方 GIS 室内图与路由 | `FloorNode` / `IndoorNode` 模型 | 运行时才拉取的官方 GIS 数据不在本卡复制；后续 W6-03 接入 |
| 办事流程 | `ProcedureNode` 模型 | W5-06 才产生首批公开来源流程，本卡不编造流程数据 |

## 来源优先级

同一稳定 ID 按 `DATA_PACKAGE > USER_OVERRIDE > CROWD_REPORT` 合并。迁移快照使用 `DATA_PACKAGE` 级别表示它等价于随宿主分发的基线数据包；这不等于校方已核验。所有实体继续携带 `DataProvenance`，来源说明与核验日期保持原样。众包上报必须带 `reviewBy`，过期后不再进入合并快照。

合并粒度是**整实体替换**，不是字段级 merge：高优先级同 ID 实体完整覆盖低优先级实体，缺省字段不会从低优先级回填。当前模型也没有 tombstone/删除标记语义；某层缺少实体只表示该层无覆盖，不能用来表达删除或隐藏低优先级实体。快照同时提供实体检索、保守教室解析与服务/设施目录项查询，用于承接药品、菜品和自习室 legacy 查询。目录项自由文本查询只命中 legacy 兼容字段集：药品为名称/规格/剂型/厂家/批准文号，菜品为名称/档口位置/标签/示例评价；价格、贡献者、核验状态、评分计数等证据或元数据不参与命中。

## 边界

- 本卡不改既有目录消费方、不改 Room、不改契约、不改 UI。
- `implementation(project(":core:domain"))` 仅用于过渡迁移适配器直接读取现有目录对象，避免复制大型药品/餐厅目录造成第二事实源；公共内核模型不依赖 domain。
- `dis-datapack/v1` 外部签名、exact-byte 解析与 Schema 校验在 `:core:school-packages`；本模块的 `DisDatapackCampusGraphDecoder`（独立分发入口 `decodeVerified`）只把已验证包投影为图实体。当前可投影支持 `procedure-guide`（`placeRef` 为 service/campus，或显式 defaultCampusRef）与 `facility-catalog`（`placeRef` 为 campus/building）；`indoor-graph` 的 edge/level、`opportunity-catalog`、`period-preset`、`pharmacy-catalog` 与语义不明的 `dependsOn` 一律 fail-closed，待契约/图模型补齐无损映射后开放。
