# W4-07 组队找时间（#33 / C15）开发说明

## 范围

- 群组列表与创建（学习/项目/社团；宿舍群也可被契约客户端列出）。
- 显式共享本人未来 7 天、30 分钟粒度忙碌时间块；可随时撤回。
- 群组忙闲交集查询、候选时段选择、投票创建、单选投票与确认。
- 确认后把云端 confirmed event 以 GROUP_MEETING 写入本人 Timeline。
- 骨架 UI 使用 M3 默认组件，TeamTimePreviewFixtures 覆盖阶段 S 11 态矩阵。

## 隐私硬约束

- 上传体只有 start_at/end_at/ttl_seconds；不包含课程标题、地点、原因、备注或事件 ID。
- 云端交集响应只展示成员 AVAILABLE/BUSY/UNKNOWN 状态与聚合计数；UI/ViewModel 没有他人原始 busy block 模型。
- 未共享、撤回或 TTL 过期的成员是 UNKNOWN，绝不能当作空闲。
- 成员信封不离线缓存；只有 W4-05 已限定的群组元数据快照可离线兜底。
- access token 只在 feature 装配注入的单次请求闭包内使用，不进日志、备份、诊断或 UI 状态。

## 组合与生命周期

- CampusRepository 只按 PR#127 过渡模式只读暴露已装配 CloudIdentityRepository；不新增 facade 业务委托。
- feature/teamtime 的 TeamTimeCloudIdentityPort 是 feature 侧窄 port；TeamTimeRuntime 绑定当前 ActiveSession 的 profile scope。
- TeamTimeRoute 使用 profileId + sessionGeneration 派生组合作用域 ViewModelStoreOwner；离开组合或切号即清理 ViewModel 和在途协程。
- 本机课表对照通过 Timeline busy blocks 完成；确认写回使用 PersonalEventStore，云端成功但本机写入失败时 UI 明确进入 PARTIAL，绝不改写事实。

## 已知骨架边界

- 契约 v1 没有“列出群组投票”端点，因此骨架只能操作当前组合会话内创建或已持有的投票；跨设备/重进后的投票发现需要后续契约与产品卡补齐。
- 成员调课或服务端 group.event.conflict_detected 在本卡不做自动改期；UI 文案明确只提示核对。
- 未做真机或真实学校账号验证，不能标 DEVICE_TESTED / SCHOOL_VERIFIED。

## 测试入口

- quick :feature:teamtime：JSON 契约解析、11 态 fixtures、组合 owner、ViewModel 显式动作。
- android：全模块单测、Lint、Debug 构建、依赖方向与文件行数门禁。
- secrets：仓库敏感信息扫描。
