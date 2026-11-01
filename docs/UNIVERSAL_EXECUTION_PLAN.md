# Universal 总蓝图执行计划与状态台账

更新日期：2026-11-02（Asia/Shanghai）。本文件是长期执行的**唯一状态源**：任何“已完成”结论都必须能在这里找到对应的源码、测试与证据位置；没有证据的能力一律留在 UNVERIFIED。

## 0. 合并与交付策略

- 集成分支：codex/universal-campus。main 在全部目标完成并通过门禁前**保持不变**（当前仍为 fc2adb1，v0.11.3）。
- 未完成的切片可以有代码、有测试，但不得在 README/Release 中描述为“已支持”。
- 只有七项目标全部满足第 6 节门禁后，才向维护者申请合并 main。

## 1. 事实基线

| 项目 | 值 |
|---|---|
| 基线 commit | 38c5ccd（安全凭据 / Profile 隔离 / 分页 / 迁移） |
| 本次新增 | 1653dd2（九协议合同内核 + 四个纯 Kotlin 模块骨架） |
| 版本 | 0.11.3 / build 1103（尚未 bump） |
| 模块数 | 24（原 20 + 4） |
| 单测 | 定向 99 项（基线）+ 新增模块测试 |
| 工具链 | JDK 21 / Kotlin 2.1.10 / AGP 8.8.2 / Gradle 8.14 / Room 2.6.1 |
| 设备证据 | 无（adb devices 为空），任何 DEVICE_TESTED 结论都还不成立 |

## 2. 新增架构分层（纯 JVM，可单测，不依赖 Android）

| 模块 | 职责 | 关键文件 |
|---|---|---|
| :core:model | 通用契约 + 严格 JSON 值模型与规范化摘要 | core/model/.../json/CanonicalJson.kt |
| :core:academic-protocols | 九协议 Gateway 契约、会话、URL 策略、Cookie 容器、完整分页、夹具回放、九项合同 harness | core/academic-protocols/.../protocol/*.kt |
| :core:school-packages | dis-campus-plugin/v1 清单、Ed25519 验签、不可变包安装/回滚、权限域、离线目录 | 建设中 |
| :core:selection | 选课轮次、教学班、冲突、容量、草稿、受控提交状态机、回读校验 | 建设中 |
| :core:academic-plan | 成绩尝试、成绩分项、培养方案、毕业进度、官方/估算隔离 | 建设中 |

分层规则：协议层不依赖 Android、不认识 UI；领域层只依赖 :core:model；Android 模块通过适配器接入，不允许反向依赖。

## 3. 九协议合同 harness（已可用）

GatewayContractSuite 强制每个协议提供九个场景夹具并验证九项不变量：

1. 认证成功且确实发出请求；
2. 学期列表字段完整、来源 ID 唯一、重复读取稳定；
3. 课表非空、绑定学期、课程身份唯一、节次区间合法；
4. 成绩完整分页且来源 ID 唯一（防 identity drift）；
5. 考试来源 ID 唯一；
6. 登录过期 → SESSION_EXPIRED 或 REAUTHENTICATE；
7. 越权跳转 → UNTRUSTED_REDIRECT（不得把凭据带到未声明来源）；
8. 部分响应 → 不得以 COMPLETE 快照发布；
9. 合法空课表 → authoritative 空快照，不得当作失败。

参考实现与自证：core/academic-protocols/src/test/kotlin/.../FakeProtocolContractTest.kt。

## 4. 七项目标状态

| # | 目标 | 状态 | 现有证据 | 还缺什么 |
|---|---|---|---|---|
| 1 | 九类教务协议 | 进行中 | 合同内核 + 参考实现 + 自证测试 | 九个适配器实现与夹具、逐协议失败矩阵 |
| 2 | 学校配置与贡献生态 | 进行中 | 模块骨架 | 清单/验签/安装回滚/权限域/目录 |
| 3 | 选课业务系统 | 进行中 | 模块骨架 | 领域模型、冲突、状态机、回读 |
| 4 | 学业画像 | 进行中 | 模块骨架 | 尝试、政策、培养方案、进度 |
| 5 | 校园服务解耦 | 未开始 | 工大实现仍在 :core:campusservices | Provider 抽象与 HAUT 实现迁移 |
| 6 | 系统级体验 | 未开始 | 现有五栏、组件、提醒 | 首启流程、多账号组件、自习/静音、无障碍 |
| 7 | 全量测试与交付 | 未开始 | CI 改造、unsigned Release | 合同矩阵、真机、SBOM、签名发布 |

## 5. 证据规则（不得放宽）

- SOURCE_CONFIRMED：源码可读，不代表任何学校可用。
- MOCK_TESTED：夹具合同测试通过，只证明解析与失败路径。
- DEVICE_TESTED / SCHOOL_VERIFIED：必须附设备型号/ROM/日期或授权账号回读记录，当前**全部为空**。
- 夹具一律标注 synthetic；禁止在 README、Release Notes、UI 中把合成夹具写成学校实测。

## 6. 合并 main 的门禁（全部满足才可申请）

1. 九协议合同测试全绿，且每协议至少覆盖认证/学期/课表/成绩/考试/过期/越权/部分/空九类；
2. 学校包：伪签名、篡改、路径穿越、权限扩大、回滚五类攻击测试通过；
3. 选课：幂等、超时未知态不重发、回读不一致有测试；
4. 学业：官方与估算严格分离、重修不重复计学分；
5. 旧库 v4→v5 迁移与 adoption journal 在真机升级路径复现；
6. 工大既有功能回归（GIS/图书馆/水电/时间轴/GPA/专注/Snooze/四组件）；
7. 账号隔离：Room/Flow/Widget/Alarm/Session/包授权逐项验证；
8. 无无障碍与性能阻塞项，构建签名与 SHA/commit 对应；
9. 许可证与第三方清单更新，未引入 GPL 代码。

## 7. 并行工作流约定

- 每个工作流一个 task/* 分支 + 独立 git worktree，从集成分支基线切出；合并前必须通过自己的模块测试。
- 工作流之间不得修改彼此目录；公共契约（protocol/*.kt、json/*.kt）只能由集成负责人变更。
- 一次合并只带一个工作流的成果，合并后立即跑全模块测试，避免“多流混绿”。
