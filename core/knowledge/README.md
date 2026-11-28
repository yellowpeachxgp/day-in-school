# core:knowledge — 课程知识空间与复习排程（W3-04/W3-05）

## 范围

- 课程资料（课件、PDF、照片、笔记、作业）按课程与教学周归档。
- 复用 W2-04 TextExtractPort 做 PDF 嵌入文本与图片 OCR；OCR/PDF 结果初始保持 UNCONFIRMED，用户确认前不得当作事实答案。
- 复用 W1-03 blob_store、search_docs/search_fts、personal_events 与 entity_journal；不新建 Room 表。
- 作业截止写成 TimelineKind.TASK_DUE / TimelineTimeSemantics.DEADLINE，由统一 Timeline 投影读取。
- 支持课程包 zip 导出（manifest、原始资料、抽取 JSON）。
- W3-05：按课程、考试/任务截止与掌握度，把复习任务以间隔重复方式排入 Timeline 空闲块；漏做块保留为 MISSED 历史并重排。

## Room v16 如实标注

任务卡为 course_materials、review_tasks 预留 Room v16，但 v13（W2-02）、v14（W2-05）、v15（W3-01）尚未按编号顺序合入。为了不抢占迁移编号、不制造跨卡迁移冲突，本实现采用过渡持久化：

1. metadata 是小型 canonical JSON，经 versioned-knowledge-document/v1 信封写入既有 blob_store；
2. 原始文件与抽取 payload 是不可变 blob；原始字节在 blob_store 使用 application/octet-stream 作为内容身份，导入方声明的 MIME 只保存在 metadata，避免同字节跨课程/标签冲突；大 blob 读取经 SQLite substr 分块，绕开 CursorWindow 单行上限。metadata 只保存 blobId/SHA-256/统计，实体 journal 不塞大正文；
3. course_materials 逻辑实体类型为 COURSE_MATERIAL，页级搜索投影为 COURSE_MATERIAL_PAGE；review_tasks 逻辑实体使用既有 REVIEW_ITEM；
4. 待 v13–v15 合入且 v16 窗口可使用时，应按这些格式做显式迁移，不得重写既有 blob 内容身份。

这不是 DataStore 草案，也不是内存 mock：数据真实落在 W1-03 Vault 表内，并受既有 ProfileDataWiper 对 personal_events/entity_journal/domain_outbox/search_docs/search_fts/blob_store 的全量擦除覆盖。由于未新增表，本卡无需也无法单独注册新表擦除项。

## 组合入口

KnowledgeRuntimeFactory.create(database, scope, textExtractor) 返回 KnowledgeRepository。应用层只需要注入当前 profile 的 HautDatabase、精确 SchoolAccountScope 与 W2-04 抽取端口。repository 实现 AutoCloseable；若门面早于账号库销毁，集成方必须 close 以释放 W1-03 outbox 的 Room invalidation observer。主导航与文件选择器接入不在本切片内，避免抢占 MainActivity/AppGraph 热点。

## 搜索定位

抽取正文按页切成不超过 search document 上限的 chunk 写入 FTS。搜索命中后重新读取行级 payload，返回零基 pageIndex、lineIndex 与 PDF point/图片像素 bounds；UI 展示页码时才转换为人类可读的一基页码。笔记使用 TEXT_LINE 坐标，不伪造图片或 PDF 坐标。

## W3-05 复习排程

`TimelineReviewScheduler` 是编排入口：调用方显式传入 scope、排程窗口、校区时区/`SlotConstraints`、统一 `TimelineService` 与 `KnowledgeRepository`。排程窗口最多 62 天； tentative 事件在复习排程中一律保守按占用处理。

1. **间隔重复**：`dis-spaced-repetition/v1` 按有效掌握度选择阶梯：低掌握度 0/1/3/7 天，中等 0/2/6 或 0/3/8 天，高掌握度 0/7 天；每个漏做块使有效掌握度额外降低 10 点。`estimatedMinutes` 视为本轮总投入，按实际轮次均分并向上取整到 30 分钟。
2. **求解器复用**：每个任务的完整多次复习序列先合成候选方案，再编码为 W3-02 `TimetableSolver` 的 offering 候选。求解器统一处理跨任务互斥与固定 Timeline 占用，结果再经 `ScheduleVerifier` 独立校验；校验失败 fail-closed，不写新排程。
3. **冲突语义**：课程、考试、个人日程和既有复习块都通过 Timeline busy interval 参与精确空闲计算；候选再投影到 30 分钟教学网格做独立硬约束验证。考试 deadline 通过 COURSE link 或考试标题与课程名精确匹配识别；任务 `dueAt` 与未来考试取最早者。
4. **Timeline 集成**：`ReviewTask.schedule` 仍是 blob_store 中的权威状态；`upsertReviewTask` 把 PLANNED/DONE/MISSED 块投影为 `personal_events` 的 `REVIEW_BLOCK`，与任务写入共享 Vault writeGroup。撤销任务写入时派生事件按既有因果撤销一起恢复/删除。
5. **重排与幂等**：重跑只替换未来 PLANNED 块；DONE/MISSED 历史块保留。过期未完成块先落库为 MISSED 并增加 lapseCount，再生成替代块，因此「重新安排不丢任务」。相同输入下重跑复用原 TimelineEventId，不制造重复 personal_events。

主导航、课程空间 UI 触发器与完成打卡交互不在本切片内；本切片交付的是可组合核心与 Timeline 投影。
