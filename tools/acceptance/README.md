# acceptance 工具骨架

本目录提供槽 10 的轻量验收工具骨架。它们只记录和校验证据索引；**不替代** `cis-gate`，不在本机执行 Android/Cloud 构建或测试，也不接入 CI。

## 文件

| 文件 | 用途 |
|---|---|
| `validate-acceptance-matrix.mjs` | 静态校验 `task-notes/campus/acceptance/capability-matrix.json` 的 ID 覆盖、枚举引用、Device-tested 规则和禁用隐私字面量。 |
| `record-evidence.mjs` | 把一次已执行的开发机门禁或测试记录为 JSONL 证据，包含脱敏命令、退出码、SHA、脱敏日志路径和验证等级。 |
| `capability-matrix.schema.json` | 能力矩阵的 JSON Schema（供后续工具或 CI 使用，本卡不改 CI）。 |
| `evidence-record.schema.json` | 单条证据记录的 JSON Schema（供后续工具或 CI 使用，本卡不改 CI）。 |

## 先静态校验矩阵

在仓库根目录运行（Node 22，无第三方依赖）：

```bash
node tools/acceptance/validate-acceptance-matrix.mjs task-notes/campus/acceptance/capability-matrix.json
```

期望退出码为 0。该命令只做静态一致性检查；真正的构建、测试和门禁必须在开发机通过 `cis-gate` 执行。

## 记录一次开发机门禁证据

先在开发机执行门禁。公开文档中的命令形式为：

```bash
ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main android-full
```

`cis-gate` 返回退出码、确切 SHA 和日志路径后，在仓库根目录追加证据索引：

```bash
node tools/acceptance/record-evidence.mjs \
  --out task-notes/campus/acceptance/evidence/stage-<序号>.jsonl \
  --item-id R0-1 \
  --evidence-type devbox-command-log \
  --verification-level MOCK_TESTED \
  --command 'ssh "$CIS_DEVBOX" <campus-root>/bin/cis-gate day-in-school main android-full' \
  --exit-code 0 \
  --android-sha <40位Android SHA> \
  --cloud-sha <40位Cloud SHA，单仓库门禁可省略> \
  --log-path '<cis-gate输出的日志路径或<campus-root>/logs/...>' \
  --executor acc/cis-stage-<序号> \
  --notes '按列车 PR 声明能力执行'
```

脚本行为：

- 追加一行 JSON 到 `--out`；不会执行 `--command` 中的命令。
- 必填：`--out`、`--item-id`、`--evidence-type`、`--verification-level`、`--command`、`--exit-code`、`--log-path`、`--executor`；`--android-sha` 与 `--cloud-sha` 至少提供一个。
- 可选：另一个仓库 SHA、`--campus-root`、`--executed-on`、`--started-at`、`--finished-at`、`--duration-ms`、`--test-name`、`--artifact-path`（可重复）、`--device-tested-pending-user`、`--notes`。
- `--log-path` 可以原样接收 `cis-gate` 输出的真实 campus 绝对路径；脚本只用它在内存中匹配，写入前统一规范化为 `<campus-root>/logs/...`。如果路径不是常见的 `campus` 目录布局，可用 `--campus-root "$CIS_CAMPUS_ROOT"` 帮助识别；真实值不会写入 JSONL 或终端摘要。
- `campus://logs/...` 也会规范化成 `<campus-root>/logs/...`；报告和截图放在 `--artifact-path`，可以是仓库相对路径或脱敏 campus 路径。
- 发现 IPv4 字面量、`user@host` 形式的账号、私钥头、常见 Token、未脱敏 `/srv/` 或 `/Volumes/` 路径时拒绝写入。请改用 `$CIS_DEVBOX` 与 `<campus-root>`。
- `--device-tested-pending-user` 只表示“待用户真机验证”，不计入通过，也不得把 `--verification-level` 写成 `DEVICE_TESTED`；退出码为 0 时记录的 `acceptanceOutcome` 是 `device-pending-user` 而不是 `pass`（退出码非 0 仍为 `fail`）。
- 终端摘要只打印仓库相对 `--out`、recordId、itemId 和退出码，不打印本机绝对路径。

## 开发机路径与保密

- 公开证据路径推荐 `<campus-root>/logs/<仓库>/<分支>/<sha>-<门禁>.log`；也接受输入 `campus://logs/...`，落盘时统一为前一种形式。
- 命令示例必须使用 `$CIS_DEVBOX` 与 `<campus-root>`；不要把真实开发机地址、账号、密码、Token、Key 或本机绝对路径写入仓库、报告或证据索引。
- 禁止用 ssh 内联 heredoc 在开发机写文件；所有工具文件都在本机 worktree 编辑后经 git 传输。

## 本卡边界

这是骨架而非完整验收系统：不实现重型测试、不解析 `cis-gate` 日志、不修改 GitHub Actions、不碰 `TASKBOARD.md`。后续阶段验收卡可以在此基础上增加报告生成器和证据聚合器。
