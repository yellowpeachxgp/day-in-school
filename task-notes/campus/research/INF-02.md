# INF-02 契约漂移差异分析（C28）

> 日期：2026-11-27（Asia/Shanghai）  
> 初始漂移基线：cloud-in-school origin/main 5480f0d907bd799e83ab4de7e15f2a1b666e8eec；day-in-school origin/main 04d4424b164ddf0044bea45d491c526849816355
> 一审后合并复核基线：cloud-in-school origin/main dc990d614e5f3a771ebe122c250362bf67104dd9（W0-08 路由拆分已合入）；W0-08 合入本身未变契约树，契约审阅修复后的新指纹见 §3
> 范围：两仓 contracts/**、contract-ci、CI 契约检查；不修改 apps/api/src，不修改业务逻辑，不把 moments extension 契约化。

## 1. 结论

1. cloud-in-school/contracts/ 是更新且应与运行路由对齐的一侧，继续作为唯一权威；day-in-school/contracts/ 是滞后的同步副本。
2. 漂移只出现在 2 个文件：contracts/openapi/cloud-v1.yaml 与 contracts/endpoints/endpoint-inventory.yaml；其余 10 个 contracts 文件逐字节相同。
3. 云端权威副本声明 97 个 core operations；Android 漂移副本声明 67 个。云端多 30 个，Android 没有独有 endpoint，operationId 没有冲突。
4. 在初始漂移基线 5480f0d 静态抽取 97 个 register core 路由后，method+path+operationId+auth 与云端 OpenAPI 和 endpoint inventory 均为 100% 双向一致。
5. 一审后 cloud main dc990d6 已合入 W0-08；按新 main 复核 routes/*.mjs 运行表，仍为 97 个 core 路由与同一云端契约 100% 双向一致，另有 12 个 moments extension。本次收敛不与已合入的路由拆分冲突。
6. 12 个 /api/v1/moments/** extension 在新 main 中已有 exact runtime inventory 并由 contract-parity 精确固定，但仍不在 OpenAPI/endpoint inventory；这是 AUTO-012 已记录的显式缺口。本卡按要求只记录缺口和建立机制，未把 moments 契约化、未改业务。

## 1.1 对独立盘点结论的逐项确认与纠偏

主代理转交的独立盘点要求交叉校验。复核结果如下：

1. 文件一致性数量需要纠偏：按两个 base SHA 用 git show 重新计算 SHA-256，12 个文件中 10 个逐字节一致、2 个不同；不是 9 个一致。10 个一致文件见 §3 表。
2. 确认漂移文件只有 endpoint-inventory.yaml 与 openapi/cloud-v1.yaml；确认云端多 30 个操作、Android 0 个独有，且两文件的新增集合相同。
3. 确认共享操作中具有实质语义漂移的是 refundAdminOrder（单人直接退款 → 双人审批退款请求）和 oidc/callback（303 重复回调恢复与 401 内容协商）；reservation-quote 的 200/summary 主要是措辞详略差异，采用云版。
4. 独立盘点未逐项列出的附带差异也已确认：reservation-quote 在 inventory 中缺 owner，云端补为 reservations；ReservationPlan 相关 schema 的 repeat_days、remaining_days、end_local_date 字段只存在于云版。它们同样采用云版。
5. 确认 moments 在两仓契约中均 0 命中；12 个 extension 只存在于运行时，属 AUTO-012 预存缺口，不在本卡契约化。

## 2. 方法与证据边界

- 用 SHA-256 对两仓 contracts/ 全树逐文件比对；用 YAML 解析比较 OpenAPI paths、operationId、endpoint inventory；初始基线用正则静态提取 app.mjs 的 register/registerExtension，W0-08 合入后复核 routes/*.mjs 组合后的运行表。
- 本机只进行静态分析、轻量实例只读核对和只读脚本检查；未运行 npm、node --test、Gradle、Docker 或业务测试。动态门禁以 devbox cis-gate 结果为准。
- W0-08 在一审后已合入 cloud main dc990d6；本卡最终结论以该新 main 复核为准，初始基线仅用于解释漂移来源。

## 3. 文件级漂移

| contracts 文件 | 漂移前状态 | 收敛后权威 SHA-256 |
|---|---|---|
| asyncapi/events-v1.yaml | 相同 | bc22a6655335466526cae47ee9270d9b16b0a33b99e54e3c60d0b26bf5eabe9f |
| compatibility/host-api-matrix.yaml | 相同 | d7aee18bc74de0fe343edfb1b2d0e3c1f61069075fdd7d9974990ca6a29a034c |
| endpoints/endpoint-inventory.yaml | 不同 | d440f21dd0aea7ecdc4c058ad22097a268a8e10fa1fe2f0d8c647ab796ea48f1 |
| errors/error-catalog.yaml | 相同 | 56fa86bacebec8d9e454fa32ee7bc12fefabeefd1b27bc577c0174257b85147c |
| examples/cloud-api-examples.http | 相同 | e6548be218974f57ea29449d445ec06cc4adad2369a1db9768e7e32eabf07fca |
| openapi/cloud-v1.yaml | 不同 | 1ad2050b9b4850e696c57b0633e71735ee3cea704d7d6b3354b81dca19166301 |
| permissions/permission-catalog.yaml | 相同 | c59ad4aab72a18dbc1027f520479b95c8ce2e536e48b02a40a4f81de9876653f |
| schemas/booking-rule-v1.schema.json | 相同 | 5819990676391f4c73bb7470cb28e0596fb1e209d733fd2e04eb273f2c071dec |
| schemas/feature-ui-v1.schema.json | 相同 | 0002904414cad9d289f46ad0fc2b66696f3fba6d8981ebb02dbff1c9839a125e |
| schemas/parcel-canonical-v1.schema.json | 相同 | af2f3d6e44067b23b2fa3cecab83863c1afab660639d498646bb8fe9c6514e47 |
| schemas/plugin-extension-v2.schema.json | 相同 | 08e1c7ac3bc5adde5de0922fc24c19cdc4fc576b99fff4bbbf2124dd4fe88bfb5 |
| schemas/reservation-quote-v1.schema.json | 相同 | 12d053f2044a32a975abaf6889e19e86c5ab0bb535cb086ac10fee7c88ff72f9 |

权威树指纹（契约审阅修复后）：CONTRACTS_TREE_SHA256=43f31a536ab609b191052be1d06221993b8f7616fa7908e3eb0c4dda461b5028。

## 4. 哪一侧更新

云端更新，理由如下：

1. 云端 OpenAPI 最近一次契约演进是 602fd8f（2026-11-17，OIDC 回调重复返回恢复）；Android 漂移前最后一次本侧契约修改是 b771d79（2026-11-15，预约绝对日期与耐久提交响应）。
2. Android 在 b771d79 增加的耐久性 503/200 表述，云端已经包含相同语义；云端随后又增加 OIDC 303/401 行为、双人退款、campus content、live school binding、sync snapshot 等运行端点。
3. 云端 97 项是 Android 67 项的超集；Android 没有 cloud 缺失的 method+path。也就是说采用云端副本不会丢失任何 Android 独有路由声明。
4. 云端契约与 cloud 当前 core 运行路由静态一致；Android 旧副本缺失 30 个已运行 core endpoint，不能再作为客户端或测试的事实来源。

## 5. 云端相对 Android 新增的 30 个 core endpoint

按领域归并如下（method+path 的完整集合均在 endpoint-inventory.yaml）：

- 身份与同步（7）：GET/POST /api/v1/sync/snapshot；GET /api/v1/auth/web/start；GET /api/v1/auth/web/logout；POST /api/v1/auth/web/exchange；GET /api/v1/auth/mobile/status；POST /api/v1/auth/oidc/native-login。
- 预约执行（2）：POST /api/v1/reservation-jobs/{jobId}/execute-now；POST /api/v1/reservation-jobs/{jobId}/cancel-seat。
- 学校绑定与实时图书馆（3）：POST /api/v1/school-bindings/bind-live；GET /api/v1/schools/haut/library/live-regions；GET /api/v1/schools/haut/library/live-seats。
- 校园内容（9）：GET /api/v1/campus-content；GET/PUT /api/v1/admin/campus-content；POST/DELETE channels；POST/DELETE clubs；POST/DELETE competitions 六个管理端点。
- 资金、退款与安全（9）：POST /api/v1/me/orders/{orderId}/settle；POST /api/v1/admin/orders/{orderId}/quick-refund；POST /api/v1/admin/orders/{orderId}/refund-requests；GET /api/v1/admin/refund-requests；POST approve；POST execute；POST refund-reconcile；GET /api/v1/admin/credential-integrity；GET /api/v1/admin/finance-worker/metrics。

## 6. 既有 endpoint 的语义差异

1. GET /api/v1/auth/oidc/callback：云端新增 303（重复返回失效回调时沿有效 Cookie 会话回工作台），并把 401 明确为 JSON 标准错误或浏览器中文重登入口。该差异来自云端 2026-11-17 的运行修复，云端为正确侧。
2. POST /api/v1/admin/orders/{orderId}/refund：Android 旧文案仍描述单管理员幂等退款；云端改为创建双人审批退款请求，且云端另有 refund-requests/approve/execute/reconcile 运行端点。云端为正确侧。
3. GET /api/v1/reservation-quote：两侧都引用同一 reservation-quote-v1.schema.json；云端 200 描述明确 amount、price_version、expires_at、quote_hash，inventory 补 owner: reservations。Android 旧副本缺 owner 且表述更旧。
4. OpenAPI components 中，云端 CreateReservationPlanRequest 增加 repeat_days、remaining_days、end_local_date；UpdateReservationPlanRequest 增加 repeat_days、remaining_days；ReservationPlanEnvelope.data 增加三者。运行代码已使用这些字段（计划更新与 sync snapshot），所以云端为正确侧。

## 7. 与 cloud 运行路由的一致性

### 7.1 初始 origin/main 5480f0d

- 静态提取 apps/api/src/app.mjs：register core 97 个，registerExtension 12 个。
- 97 个 core 路由与云端 OpenAPI 97 项、endpoint inventory 97 项在 method+path+operationId+auth 上完全一致；无 runtime-only core，无 contract-only core，无 operationId mismatch。
- 该初始基线用于解释漂移来源；最终结论不依赖旧 main。

### 7.2 W0-08 合入后的 origin/main dc990d6

- 轻量实例只读复核合并后的运行表：getRegisteredCoreRoutes 97 个、getRegisteredExtensionRoutes 12 个、getRegisteredRoutes 合计 109 个。
- 97 个 core 路由与云端 OpenAPI 97 项、endpoint inventory 97 项在 method+path+operationId+auth 上完全一致；missingCore=0、contractOnly=0、mismatches=0。
- 12 个 moments extension 与新 main 的 exact runtime inventory 一致，且 extInContracts=0；即它们被精确记录但未进入 OpenAPI/endpoint inventory。
- 新 main 的 contract-parity 测试把 97 core 与 12 extension 分开，显式断言 moments 不在 OpenAPI/inventory，并标记 moments-extension/v0-pending-inf02。
- 因用户对本卡的约束，INF-02 不消灭该缺口；后续必须由契约线单独决定是否及如何契约化 moments，并同步调整该显式测试边界。

## 8. moments extension 显式缺口（仅记录，不契约化）

dc990d6 后，12 个运行时 extension 已由 main 的 exact runtime inventory 精确固定；该 inventory 是运行时边界记录，不等于 OpenAPI/endpoint inventory 契约化。当前 12 个运行时 extension 是：

1. GET /api/v1/moments/snapshot — getMomentsSnapshot — public
2. POST /api/v1/moments/posts — createMomentsPost — public
3. POST /api/v1/moments/posts/{postId}/comments — addMomentsPostComment — public
4. POST /api/v1/moments/posts/{postId}/contact-requests — createMomentsContactRequest — bearer
5. GET /api/v1/moments/posts/{postId}/contact-requests — listMomentsContactRequests — bearer
6. POST /api/v1/moments/posts/{postId}/contact-requests/{requestId}/action — handleMomentsContactRequestAction — bearer
7. POST /api/v1/moments/posts/{postId}/action — mutateMomentsPost — public
8. POST /api/v1/moments/books — createMomentsBookListing — public
9. POST /api/v1/moments/books/{bookId}/action — mutateMomentsBookListing — public
10. GET /api/v1/moments/isbn-resolve — resolveMomentsIsbn — public
11. POST /api/v1/moments/anti-lost-tags — upsertAntiLostTag — public
12. POST /api/v1/moments/anti-lost-tags/{tagCode}/report-found — reportAntiLostTagFound — public

处理建议：另开契约线卡（或用户确认后的 CT 卡）决定是否将 moments 提升为正式 OpenAPI core；在那之前不得以 INF-02 名义擅自补写这些端点。

## 9. 本次建立的机制

1. 新增 tools/contract-ci/sync-contracts.mjs：
   - sync：默认先执行 git fetch origin main 刷新 remote-tracking ref，再读取云端 git origin/main，把 contracts/ 单向镜像到 Android，含多余文件删除；显式 --cloud-ref 或 --use-cloud-worktree 时由调用者管理来源。
   - sync 写入采用事务化替换：先校验 cloud-root 为 cloud-in-school 仓库 checkout 或完整导出，完整 staging 云端树并备份受影响文件；每个文件用临时文件加 rename 替换，异常时按逆序回滚，避免半同步状态。
   - check：递归比较两棵 contracts/ 树的文件清单与每个文件 SHA-256；缺失、多余、哈希不同均失败。
   - fingerprint：输出确定性文件哈希清单与 CONTRACTS_TREE_SHA256。
2. Android CI 检出 cloud-in-school main 到独立目录，执行 sync-contracts.mjs check；因此 Android 副本相对云端 main 漂移时托管 CI 变红。
3. 云端 CI 在 contract:check 后输出权威 contracts/ 哈希清单。跨仓强制检查采用云端权威到 Android 副本的方向，避免权威侧等待副本侧而造成成对 PR 合并顺序死锁。
4. 本卡用该工具完成同步：12 个文件，新增 0、更新 2、删除 0、未变 10，最终逐字节一致。

## 9.1 一审后事务化修正

独立审阅指出逐文件直接 write/unlink 可能在中途失败时留下半同步状态。修正后实现为：云端来源先完整 staging 并比对 staging 指纹；受影响既有文件先复制到事务 backup；正式替换逐文件使用临时文件加 rename；任一异常按已执行操作逆序回滚新增、覆盖和删除。rollback 清理空目录时保留同步前已存在目录，避免把用于注入失败的预存目录误删。

本地人为失败负例结果：

1. 写入负例：第一文件已更新后，第二文件目标路径被预存目录阻塞；sync 退出码 1，第一文件恢复旧内容，阻塞目录保留，未同步多余文件未提前删除，事务目录已清理。
2. 删除负例：第一多余文件已删除后，第二多余文件因目录权限注入失败；sync 退出码 1，已更新文件恢复旧内容，两个多余文件均恢复/保留，事务目录已清理。
3. 防护负例：cloud-root 与 android-root 经 symlink 指向同一目录时 realpath 防护拒绝；package.json name 非 cloud-in-school 时身份校验拒绝且 Android 目标内容不变。

动态 contracts/cloud/android/secrets 门禁结果仍以最终 PR 正文和 <campus-root>/logs/ 为准。

## 9.2 W0-08 合入后的复核

一审后按主代理要求把 cloud origin/main dc990d6 合并入云端 INF-02 分支，并重新核对新基线：

1. 运行路由与契约：core=97、inventory=97、OpenAPI=97、extension=12、all=109；missingCore=0、contractOnly=0、mismatches=0、extInContracts=0。
2. AUTO-012 边界：12 个 moments extension 的 method+path+operationId+auth 与 main 的 exact runtime inventory 一致，但在 contracts/** 中仍为 0 命中；本卡仍未把 moments 契约化。
3. secrets allowlist：合并后只读扫描 SECRET_SCAN_PASS，files=149、tracked=150、findings=0、allowlistEntries=113、allowlistUsed=113、warnings=0；W0-08 更新的行号在当前合并树上仍准确。
4. W0-08 合入本身未改变契约树；随后的契约审阅修复把权威树更新为 43f31a536ab609b191052be1d06221993b8f7616fa7908e3eb0c4dda461b5028。

## 9.3 契约审阅四项阻断修复

1. path template：quickRefundAdminOrder/orderId、executeReservationJobNow/jobId、cancelConfirmedReservationSeat/jobId、deleteAdminCampusChannel/channelId、deleteAdminCampusClub/clubId、deleteAdminCampusCompetition/competitionId 均已补 in: path + required: true 声明；validate-contracts.mjs 新增严格 path template 正反向校验。用修复前 OpenAPI 做负例，校验器 exit 1 并点名 quickRefundAdminOrder；修复后 PASS。
2. refundAdminOrder：契约改为运行事实 202 + RefundRequestEnvelope，不再宣称 200 Order refunded/OrderEnvelope；新增 RefundRequest、RefundRequestEnvelope、RefundRequestListEnvelope、RefundExecutionEnvelope 等 schema，并把 quick-refund/refund-requests/approve/execute/list 对齐到同一运行 envelope。AdminRefundRequest 移除无运行语义的 second_approver_ref，amount_micropeach 必需、reason 可选；examples 增加双人审批退款三步示例。
3. fetch  freshness：sync 默认改为显式 git fetch origin +refs/heads/main:refs/remotes/origin/main。受控负例中云端 clone 的 remote.origin.fetch 被刻意指向 not-main 且远端 main 已推进，sync 仍取到 v2 而不是 stale v1。
4. alias/file-type 防护：sync 在计划/执行前检测云端或 Android 内仅大小写/Unicode 规范化不同的 alias、跨仓 case-only rename、file↔directory 冲突并 fail-closed；sparse checkout 明确拒绝。受控负例证明 Foo/foo case-only rename 在写入前失败且旧文件不变，file↔dir 与 parent file↔dir 均写前失败。
5. 事务回归：删除中点失败仍逆序回滚；用只读子目录注入 rename 失败的写入中点负例也已恢复全部旧内容，无半同步。

## 10. 残留风险与待决

1. AUTO-012 的 moments extension 即使在 dc990d6 后已有 exact runtime inventory，仍是实际运行时未契约化面；这不是 INF-02 已解决项，必须保留为后续契约线阻断/待决事项。
2. 云端权威 OpenAPI 的部分新增 endpoint 只列了最小响应，请求体、完整错误码、幂等说明和示例仍不满足 CT 线的完整 Contract-First 深度；本卡只做收敛，不擅自补语义，后续 CT 卡应逐项补强。
3. INF-00 的 devbox cis-gate contracts 目前用另一仓 main 做双向 diff。云端侧在 Android main 尚未合入同步副本前可能因顺序性漂移失败；这属于成对 PR 的顺序风险，是否需要 pair-ref/方向性门禁应由主代理另行决策，本卡未越权修改 tools/devbox。
4. Android CI 以云端 main 为准；若云端 main 在 Android PR 生命周期内继续前进，Android CI 会因新漂移变红。这是 D15 的预期行为，契约线应重新同步后再合并。
