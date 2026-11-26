# 校园阶段验收与终验证据

本目录服务 MASTER_PLAN §12.8（阶段联调验收）和 §14（阶段 S 终验）。

> Bootstrap 说明：当前 `origin/main` 尚未合入 INF-01，`AGENTS.md`、`docs/roadmap/` 与 `task-notes/campus/` 尚不存在。本目录由槽 10 骨架卡直接创建；INF-01 合入后，本文档中的计划链接应改指仓库内 `docs/roadmap/MASTER_PLAN.md`。

## 文件布局

| 路径 | 用途 |
|---|---|
| `capability-matrix.json` | 机读能力矩阵，覆盖 R0-1..R0-7 与 C01..C28。 |
| `stage-report-template.md` | 每趟列车合入后的 `stage-<序号>.md` 报告模板。 |
| `final-report-template.md` | 终验 `<android_sha>-<cloud_sha>/ACCEPTANCE.md` 报告模板。 |
| `evidence/`（按需创建） | 由 `tools/acceptance/record-evidence.mjs` 追加的 JSONL 证据索引。原始完整日志留在开发机，不写入公开仓库。 |

## 机读矩阵约定

`capability-matrix.json` 中每个条目固定包含：

- `id`：`R0-1`..`R0-7` 或 `C01`..`C28`。
- `title` / `acceptanceSummary`：能力标题和验收要点。
- `requiredEvidenceTypes`：自动验收必须收集的证据类型。
- `privacy.flags`：隐私风险标记；只是验收索引，不替代实现中的 `DataProvenance`。
- `migration.types`：迁移范围标记（`room`、`cloud-sql`、`backup-format`、`datapack-format`、`plugin-package-format`）。
- `deviceTested`：是否存在只能由用户真机证明的子项。
- `maximumAutomaticVerificationLevel`：自动证据最高只能支撑 `MOCK_TESTED`。

矩阵复用领域类型名称：`VerificationLevel`、`CapabilityState`、`DataProvenance`、`SchoolAccountScope`。验收结论（pass/fail 等）只是执行结果，不新造、不替代这些领域类型。

## DEVICE_TESTED 待用户规则

1. 开发机、模拟器、JVM、Robolectric、Roborazzi 或云端测试证据都不得标为 `DEVICE_TESTED`。
2. 只能在设备上证明的内容（例如真实通知外观、小组件实际行为、TalkBack、200% 字号实际渲染、厂商 ROM 后台限制、相机扫码体验）单独写为 `DEVICE_TESTED 待用户`。
3. `DEVICE_TESTED 待用户` 不计入阶段 S 自动验收通过，也不允许写成已通过。
4. 未在真实学校账号验证的内容不得标 `SCHOOL_VERIFIED`；D1 下代理整理的数据在来源说明中默认标记为“UNVERIFIED”（不是新增 `VerificationLevel` 枚举）。

## 公开路径脱敏规则

1. 仓库、报告、证据索引和日志摘录一律使用 `<campus-root>`、`campus://`、`<devbox>` 或 `$CIS_DEVBOX`。
2. 禁止写入真实开发机账号、地址、IP、密码、Token、Key，也禁止写入本机绝对路径。
3. 公开证据路径推荐使用 `<campus-root>/logs/<仓库>/<分支>/<sha>-<门禁>.log`；也可以使用等价的 `campus://logs/...`。
4. `record-evidence.mjs` 会把可识别的开发机 campus 绝对路径规范化成 `<campus-root>/...` 后再写入；真实路径只允许作为命令行输入或 `--campus-root` / `CIS_CAMPUS_ROOT` 的临时值，不会落盘。
5. 命令型证据记录命令、退出码和日志路径；`github-ci-run`、`manual-device-note`、`screenshot-or-recording`、`source-audit-note` 等非命令型证据可改用 artifact、testName、notes 和显式 outcome。证据类型必须与矩阵中该条目的 required/pending 类型一致。

## 阶段联调验收流程（§12.8）

每趟列车合入 `main` 后：

1. 记录 Android 与云端 `main` SHA。单仓库验收时另一仓库 SHA 可记为 `null`，终验必须使用成对 SHA。
2. 在开发机运行现场门禁（不要在本机跑构建或测试）：

   ```bash
   ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main android-full
   ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate cloud-in-school main cloud
   ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main contracts
   ```

   实际是否需要三条全部运行，以该趟列车改动仓库和 PR 声明为准；§12.8 的默认要求是 `android-full`、`cloud`、`contracts`。示例中的 `<campus-root>` 是公开占位符，执行时由操作者在本机环境或 devbox 配置中解析。
3. 从 `cis-gate` 输出记录：命令、退出码、确切 SHA、耗时、开发机日志路径。写入仓库前必须按上一节脱敏。
4. 用 `tools/acceptance/record-evidence.mjs` 把每条命令证据追加到 `evidence/stage-<序号>.jsonl`；GitHub CI、截图、人工核验或来源审计等非命令证据按类型记录 artifact/testName/notes，不伪造 command、exitCode 或 logPath。
5. 按列车 PR 声明的能力编号逐项核对，复制 `stage-report-template.md` 为 `stage-<序号>.md` 并填写。
6. 失败项立即回报主代理建修复卡；不得修改断言迁就结果，不得把 `skipped`、`flaky`、重试后通过或超时写成通过。
7. 报告由另一个未参与开发的审阅子代理交叉复核后，某一波次能力才算完成。

## 终验流程（§14）

1. 冻结两仓合并，确定最终 Android SHA 与云端 SHA。
2. 创建 `task-notes/campus/acceptance/<android_sha>-<cloud_sha>/`。
3. 复制 `final-report-template.md` 为该目录下 `ACCEPTANCE.md`。
4. 由未参与开发的终验子代理执行 R0-1..R0-7 与 C01..C28，逐项记录测试名、命令、退出码、脱敏日志路径或截图路径。
5. 第二个独立终验子代理复核全部证据，并重跑至少 20% 的项、全部隐私类项（C07、C10、C27）与全部迁移类项（R0-2、R0-3）。
6. 任一项失败时，修复并以新的成对 SHA 从 R0 整轮重跑。

## 保密与恢复

- 仓库、报告、证据索引和日志摘录一律写 `<devbox>` 或 `$CIS_DEVBOX`，路径写 `<campus-root>/...` 或 `campus://...`。
- 不得粘贴含秘密的日志内容；引用日志摘录前先按相同规则脱敏。
- 会话中断后，先读本 README、机读矩阵、最近 stage 报告和任务板，再从最近一条 JSONL 证据继续。
