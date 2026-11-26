# acceptance 工具骨架

本目录提供槽 10 的轻量验收工具骨架。它们只记录和校验证据索引；**不替代** `cis-gate`，不在本机执行 Android/Cloud 构建或测试，也不接入 CI。

## 文件

| 文件 | 用途 |
|---|---|
| `validate-acceptance-matrix.mjs` | 静态校验能力矩阵的 ID 覆盖、枚举引用、Device-tested 规则和统一敏感形状。 |
| `record-evidence.mjs` | 把命令型或非命令型验收证据追加为 JSONL；只接受已脱敏输入，不改写命令或真实路径。 |
| `sensitive-utils.mjs` | 统一敏感形状与运行时环境值检测；不写死真实主机、账号或路径。 |
| `selftest.mjs` + `selftest-cases.json` | schema/recorder 一致性自测与负向回归样例。 |
| `capability-matrix.schema.json` | 能力矩阵 JSON Schema。 |
| `evidence-record.schema.json` | 证据 JSON Schema；统一 `publicString`、`publicLogPath`、`publicArtifactPath` 脱敏约束。 |

## 静态校验矩阵

```bash
node tools/acceptance/validate-acceptance-matrix.mjs task-notes/campus/acceptance/capability-matrix.json
```

期望退出码为 0。该命令只做静态一致性检查；真正的构建、测试和门禁必须在开发机通过 `cis-gate` 执行。

## 记录命令型证据

先在开发机执行门禁，例如：

```bash
ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main android-full
```

再记录**已经脱敏**的命令模板、退出码、SHA 和日志路径：

```bash
node tools/acceptance/record-evidence.mjs \
  --out task-notes/campus/acceptance/evidence/stage-<序号>.jsonl \
  --item-id R0-1 \
  --evidence-type devbox-command-log \
  --verification-level MOCK_TESTED \
  --command 'ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main android-full' \
  --exit-code 0 \
  --android-sha <40位Android SHA> \
  --log-path '<campus-root>/logs/day-in-school/main/<sha>-android-full.log' \
  --executor acc/cis-stage-<序号>
```

`devbox-command-log` 必须有 `--command`、`--exit-code`、`--log-path`，并且 `--executed-on devbox`。提供 `--command` 时必须同时提供 `--exit-code`；未显式给 `--outcome` 时按退出码推导 pass/fail。

**重要保守约束**：脚本不做命令路径 normalization，不接收真实绝对路径后“帮忙脱敏”。调用方必须传入可重放的脱敏模板；否则拒绝写入。

## 记录非命令型证据

非命令型证据不强制 `command` / `exitCode` / `logPath`；必须显式提供 `--outcome`，并满足类型要求：

| evidenceType | executedOn | 最小证据要求 |
|---|---|---|
| `github-ci-run` | `github-ci` | artifact、testName、notes 至少一项 |
| `manual-device-note` | `user-device` | artifact、testName、notes 至少一项 |
| `screenshot-or-recording` | `devbox/github-ci/user-device/local-static` | 至少一个 artifact |
| `source-audit-note` | `devbox/local-static` | testName 或 notes |

示例（GitHub CI）：

```bash
node tools/acceptance/record-evidence.mjs \
  --out task-notes/campus/acceptance/evidence/final-1.jsonl \
  --item-id R0-5 \
  --evidence-type github-ci-run \
  --verification-level MOCK_TESTED \
  --executed-on github-ci \
  --outcome pass \
  --android-sha <40位SHA> \
  --artifact-path https://github.com/yellowpeachxgp/day-in-school/actions/runs/<run-id> \
  --executor acc/cis-final-1
```

## 一致性规则

1. `evidenceType` 必须在矩阵该条目的 `requiredEvidenceTypes` 或 `deviceTested.pendingEvidenceTypes` 中登记；额外类型会被拒绝。
2. `DEVICE_TESTED 待用户` 标记只能附加在该条目的自动 required 证据上；此时 outcome 自动为 `device-pending-user`，不得写成 pass。
3. 只有 `--executed-on user-device` 的手工证据才能标 `DEVICE_TESTED`。
4. 阶段 S 证据索引 fail-closed：一律拒绝记录或宣称 `SCHOOL_VERIFIED`；`executedOn` 不提供 school-account。若未来开启真实学校验证，必须先由用户和主代理批准专项类型。
5. `--log-path` 只接受 `<campus-root>/logs/...` 或 `campus://logs/...`，并按原样写入以保持可重放；真实绝对路径会被拒绝。
6. `--artifact-path` 只接受 `campus://...` 或无凭据、无 query 的 HTTPS URL。签名 URL、查询 token、本机绝对路径、仓库相对路径和未脱敏开发机路径都会被拒绝。

## 脱敏与自检

- 敏感检测只使用通用形状（IPv4、user@host、私钥头、常见 Token、Unix/macOS home/绝对路径形状）和运行时环境值（`CIS_DEVBOX`、`CIS_CAMPUS_ROOT`），仓库不写死任何真实主机、账号或路径。
- 终端摘要只打印仓库相对 `--out`、recordId、itemId 和 outcome，不打印本机绝对路径。
- 运行完整自测：

```bash
node tools/acceptance/selftest.mjs
```

自测覆盖 schema/recorder 枚举一致性、命令型/GitHub CI/手工真机/来源审计/截图正向样例，以及类型不匹配、home 路径、绝对命令、绝对日志路径、`SCHOOL_VERIFIED`、带 query artifact、运行时敏感值等负向回归样例。

## 本卡边界

这是骨架而非完整验收系统：不实现重型测试、不解析 `cis-gate` 日志、不修改 GitHub Actions、不碰 `TASKBOARD.md`。后续阶段验收卡可以在此基础上增加报告生成器和证据聚合器。
