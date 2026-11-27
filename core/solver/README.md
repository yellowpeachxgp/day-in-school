# :core:solver

纯 JVM 选课/排程约束求解内核，对应 W3-02 与能力 C12 的求解器部分。本模块不实现 UI、不写选课草稿、不调用 `SelectionWritePort`，也绝不触发任何学校系统提交。

## 公开边界

- `TimetableSolver.solve(problem, maxSolutions = 5, budget = 2s)`：同步求解，默认最多 5 套方案。
- `SelectionProblem`：候选教学班、用户级硬约束、非负软偏好、通勤矩阵、学分范围、身份 scope、显式教学周时间网格、固定占用/Timeline 事件。
- `TravelTimeMatrix`：楼宇对通勤接口。生产接入应由路网 A* 预计算并缓存；本模块只依赖接口。`Unknown` 不等同可达，跨校区可由实现返回大分钟数或 `NotWithinBreak`。
- `ScheduleVerifier`：独立重算硬约束和成本，供测试与后续沙盘验收，不用求解器“自证正确”。

## 算法

1. 以「课程 offering」为变量；必选 offering 值域为各教学班，可选 offering 额外含 `SKIP`，因此同一 offering 天然至多一班。
2. 教学周 × 星期 × 节次编译为位集；周次未知按可能冲突保守展开，不伪造确定周次。
3. 硬约束：时间重叠、固定占用、校区过滤、学分上下限、必选、容量过滤、互斥、课间通勤。
4. 传播：赋值后前向检查；学分上下界迭代过滤；singleton 值域归约；变量选择为动态 MRV，平局取 degree，再取学分影响。
5. 优化：所有软成本为非负整数毫分，搜索使用可接纳下界做分支定界；成对/全局成本无法安全估计时在下界中取 0，完整方案由 `SolutionCostEvaluator` 精确重算。
6. `PROVEN_COST_TOP_K` 的诚实含义：按（成本、确定性 DFS 发现顺序）定义同成本次序，不存在被遗漏的更优方案；signature 只作稳定标识，不把并列方案伪造成全序唯一最优。
7. 预算耗尽返回 `BEST_FOUND_BUDGET_EXHAUSTED`，即使没有任何方案也不得误报无解。

## 无解解释

QuickXplain 只在少量用户级约束上运行：必选、学分上下限、容量策略、时间排除、校区限制、互斥和通勤策略。课程两两时间冲突、固定课表、时段事实和变量结构属于背景，不作为“可放松”建议。解释返回前会逐条做极小性验证；oracle 超时时明确为 `EXPLANATION_UNKNOWN`。

`ORACLE_NODE_LIMIT`（当前 100,000）是**单次一致性 oracle 调用**的搜索节点上限，不是整个 `solve` 或全部 oracle 调用的累计上限；每次调用从 0 独立计数。`stats.nodes` 仍累计记录整个求解过程用于诊断，但不会消耗后续 oracle 的额度。所有 oracle 继续共享外层墙钟预算；达到节点上限只把该次一致性结论降级为 `UNKNOWN`，不得伪报一致或不一致。

放松建议只是草稿编辑建议，不会自动修改约束、不会自动写入草稿，更不会提交选课。

## 固定种子基准

测试源码实现 R04 §7/§8 的合成基准：

- 规模：50 / 100 / 200 候选；种子：20260301..20260305。
- 难度族：`medium-planted`、`sparse-many-solutions`、`dense-conflict`、`near-infeasible`、`uncertain-data`、`cross-campus`。
- 每个实例生成确定性 CanonicalJson 与 SHA-256；medium planted 引用方案由独立 verifier 校验。
- quick 矩阵：S50/S100/S200 × medium/dense 的第一个种子，以及 S50 near-infeasible。
- S50 冷启动记录第一次求解完整耗时；稳态使用同一 JVM 3 次预热、5 次测量，报告 p50/p95/max。计时从 `solve` 调用前到方案排序或 QuickXplain 解释完成后结束。
- 机器可读输出写入测试 stdout 与构建报告 `build/reports/solver-benchmark/benchmark-evidence.txt`，行前缀为 `CIS_SOLVER_BENCH` / `CIS_SOLVER_PERF`。

开发机结果只证明工程门禁口径，不标注 DEVICE_TESTED 或 SCHOOL_VERIFIED。
