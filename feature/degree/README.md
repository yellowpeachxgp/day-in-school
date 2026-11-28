# feature:degree — 毕业进度看板骨架 UI（W3-01）

## 范围

- 毕业进度看板：总进度、要求组已修/缺口、风险提示、未归类课程、审计说明，全程标明「个人规划参考」。
- 「查看依据」：每个要求组可展开 AuditReport.trace 逐条审计轨迹（规则、课程、学分、结论、依据、代表成绩 ID/学期/来源）。
- 人工认定：从未归类课程发起（直接携带代表成绩实体 ID，改名后不失效），归组或排除，依据必填；认定写入 journal 变更历史。已生效认定卡片提供改派与撤销入口，撤销后回到自动匹配。
- 导入：粘贴 univ-training-plan/v1 JSON（以 { 开头）或扁平 CSV 文本；解析失败给出具体行级错误。
- 状态矩阵（8 态 PreviewFixtures）：loading / empty / ready-derived / ready-estimated-unconfirmed / ready-unclassified / import-dialog / override-dialog / error。

## 接线

与 W3-04 一致：主导航接入不在本切片内，避免抢占 MainActivity/AppGraph 热点。集成方注入当前 profile 的 DegreeRepository 工厂、成绩读取通道与 profileId+sessionGeneration 组成的 sessionKey（账号切换即重建 VM 并 close 旧仓库）。
