# acceptance 工具骨架

本目录提供槽 10 的轻量验收工具骨架。它们只记录和校验证据索引；**不替代** `cis-gate`，不在本机执行 Android/Cloud 构建或测试，也不接入 CI。

## 文件

| 文件 | 用途 |
|---|---|
| `validate-acceptance-matrix.mjs` | 静态校验能力矩阵的 ID 覆盖、枚举引用、Device-tested 规则和统一敏感形状。 |
| `record-evidence.mjs` | 把命令型或非命令型验收证据追加为 JSONL，写入前统一脱敏。 |
| `sensitive-utils.mjs` | 统一脱敏与敏感形状检测；只使用通用形状和运行时环境值，不写死真实主机、账号或路径。 |
| `capability-matrix.schema.json` | 能力矩阵 JSON Schema。 |
| `evidence-record.schema.json` | 证据 JSON Schema；用统一 `publicString` 禁止 IP、user@host、私钥、常见 Token 和未脱敏绝对路径形状。 |

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

再记录命令、退出码、SHA 和日志路径：

```bash
node tools/acceptance/record-evidence.mjs \
  --out task-notes/campus/acceptance/evidence/stage-<序号>.jsonl \
  --item-id R0-1 \
  --evidence-type devbox-command-log \
  --verification-level MOCK_TESTED \
  --command 'ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main android-full' \
  --exit-code 0 \
  --android-sha <40位Android SHA> \
  --log-path '<cis-gate输出的日志路径或<campus-root>/logs/...>' \
  --executor acc/cis-stage-<序号>
```

`devbox-command-log` 必须有 `--command`、`--exit-code`、脱敏 `--log-path`，并且 `--executed-on devbox`。提供 `--command` 时必须同时提供 `--exit-code`；未显式给 `--outcome` 时按退出码推导 pass/fail。

## 记录非命令型证据

非命令型证据不再强制 `command` / `exitCode` / `logPath`；必须显式提供 `--outcome`，并满足类型要求：

| evidenceType | executedOn | 最小证据要求 |
|---|---|---|
| `github-ci-run` | `github-ci` | artifact、testName、notes 至少一项；artifact 可是无查询参数的 github.com 证据 URL |
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
3. 只有 `--executed-on user-device` 的手工证据才能标 `DEVICE_TESTED`；只有 `--executed-on school-account` 的真实学校账号证据才能标 `SCHOOL_VERIFIED`；本骨架拒绝 `DISTRIBUTION_READY`。
4. `--log-path` 可接收真实 campus 绝对路径、`<campus-root>/logs/...` 或 `campus://logs/...`，落盘统一为 `<campus-root>/logs/...`。可用 `--campus-root` 或 `CIS_CAMPUS_ROOT` 帮助识别，真实值不落盘。
5. artifact 可以是仓库相对路径、脱敏 campus 路径或无查询参数的 github.com URL。签名 URL、查询 token、本机绝对路径和未脱敏开发机路径会被拒绝。

## 脱敏与自检

- 敏感检测只使用通用形状（IPv4、user@host、私钥头、常见 Token、未脱敏绝对路径形状）和运行时环境值（`CIS_DEVBOX`、`CIS_CAMPUS_ROOT`、`--campus-root`），仓库不写死任何真实主机、账号或路径。
- 终端摘要只打印仓库相对 `--out`、recordId、itemId 和 outcome，不打印本机绝对路径。
- 自检建议：命令型证据、GitHub CI、手工真机各追加一条临时 JSONL 并断言路径已脱敏；再验证类型不匹配和运行时敏感值会拒绝写入。具体示例见本 PR 验证记录。

## 本卡边界

这是骨架而非完整验收系统：不实现重型测试、不解析 `cis-gate` 日志、不修改 GitHub Actions、不碰 `TASKBOARD.md`。后续阶段验收卡可以在此基础上增加报告生成器和证据聚合器。
