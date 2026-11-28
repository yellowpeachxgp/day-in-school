# `:core:guide`

W5-03 办事导航与机会中心内核（纯 JVM，C20/C21）。本模块只解释 `dis-datapack/v1` 中已经声明的固定字段；不 eval、不反射、不执行脚本、不加载远程代码，与插件/数据包共用同一类 fail-closed 安全边界。

## 输入信任边界

`GuidePackageLoader.loadVerified` 只接受 W5-01 `DisDatapackLoader.verify` 产生的 `VerifiedDataPack`，且重新检查 canonical artifact、签名 payload 与 signer 指纹摘要链。模块没有 unsigned、trust-null 或跳过签名入口。当前可执行 kind 只有：

- `procedure-guide`
- `opportunity-catalog`

包级和 item 级 `sources / verifiedAt / reviewBy / verificationLevel` 会合并成 `GuideItemMetadata`；超过 `reviewBy` 只标记 `REVIEW_DUE`，不伪造来源失效或校方确认。

## FlowRunner 声明式分支语义

v1 schema 没有表达式语言，因此本模块固定使用下列确定性子集，不扩展隐式语义：

1. `steps[]` 是有序前向控制流。
2. 一个 question 的触发点是它所有非空 `nextStepId` 中**最小的 step 下标**；没有分支目标的问题在流程开始前采集。
3. 回答后，`nextStepId == null` 或指向当前下标表示顺序继续；指向更靠后的 step 表示前向跳转。被跳过的 steps/questions 不进入本次个人路径。
4. 遇到未回答的问题立即停止路径求值；`PersonalPath` 只展示已解析前缀，绝不猜测默认分支。已经被确定在当前路径上的问题允许先回答，不要求用户先完成前序步骤，以便提前生成完整个人路径和材料清单。
5. 分支目标必须在 `steps[]` 内，步骤/问题 ID 不得重复或含空白控制字符；非法结构在加载时拒绝。

该子集能表达「条件可选步骤/跳过一段流程」；它不能表达任意 DAG 或带表达式的互斥多分支。若后续契约补充显式分支结束/条件字段，应由契约线先改 schema，再扩展本解释器。

进度只保存答案、已完成步骤与用户显式计划时间。当前位置和个人路径由这些数据确定性重建；artifact/version 变化时恢复显式返回 `DefinitionChanged`，不把旧答案套进新流程。

## 持久化与恢复

`GuideProgressStore` 是纯 JVM Port，附带 `InMemoryGuideProgressStore` 供测试和 Preview；Android/Room 持久化由后续 feature 层实现。`GuideProgressCodec` 提供 `dis-guide-progress/v1` 白名单 JSON codec：

- 未知字段、未来 schema、非法枚举、非法 Instant、非规范材料下标、损坏 SHA-256 全部拒绝；
- procedure 与 opportunity 都按 `(scope, packId, itemId)` 隔离；
- opportunity 材料用同一 artifact 内的下标记录完成时间，包变化后整份进度拒绝恢复。

## 资格判断三态

`OpportunityEligibilityEvaluator` 输出：

- `ELIGIBLE`：所有要求的自动证据均为 `SATISFIED`；
- `NEEDS_VERIFICATION`：至少一项缺少可计算证据，且没有明确不满足；
- `NOT_ELIGIBLE`：至少一项明确不满足。

`COMPUTED` 事实只能挂到 `computable=true` 的要求。`USER_ATTESTATION` 是个人线索，不能单独把结果自动提升为符合；用户确需修正时必须使用带原因和时间的 `ManualEligibilityOverride`，基础判断仍保留供审计。信息缺失永远不会自动判为符合。

## Timeline 集成

`GuideTimelineSource` 是标准 `TimelineSource`（source id `guide`）：

- `PROCEDURE_STEP`：只投影用户显式 schedule 的个人路径步骤。能确定正时长时投影 `TIMED + TENTATIVE`；否则投影 `UNCERTAIN + FREE`，绝不伪造 deadline 或占用窗口。
- `OPPORTUNITY_MILESTONE`：只投影已追踪机会的 `deadlineAt`，使用 `DEADLINE + FREE` 点事件；未追踪机会不会污染个人时间流。
- 进度 artifact 与当前包不一致、跨 scope、条目缺失或 deadline 非法时 fail-closed。

本模块不调度通知、不写 Room、不接入 UI；W5-04/W5-05 在此内核上实现骨架 UI 与提醒装配。
