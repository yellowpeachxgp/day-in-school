# core:knowledge — 课程知识空间（W3-04）

## 范围

- 课程资料（课件、PDF、照片、笔记、作业）按课程与教学周归档。
- 复用 W2-04 TextExtractPort 做 PDF 嵌入文本与图片 OCR；OCR/PDF 结果初始保持 UNCONFIRMED，用户确认前不得当作事实答案。
- 复用 W1-03 blob_store、search_docs/search_fts、personal_events 与 entity_journal；不新建 Room 表。
- 作业截止写成 TimelineKind.TASK_DUE / TimelineTimeSemantics.DEADLINE，由统一 Timeline 投影读取。
- 支持课程包 zip 导出（manifest、原始资料、抽取 JSON）。

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
