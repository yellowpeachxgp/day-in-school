# acceptance 工具骨架

本目录提供槽 10 的轻量验收工具骨架。它们只记录和校验证据索引；**不替代** `cis-gate`，不在本机执行 Android/Cloud 构建或测试，也不接入 CI。

## 文件

| 文件 | 用途 |
|---|---|
| `validate-acceptance-matrix.mjs` | 静态校验 `task-notes/campus/acceptance/capability-matrix.json` 的 ID 覆盖、枚举引用和 Device-tested 规则。 |
| `record-evidence.mjs` | 把一次已执行的开发机门禁或测试记录为 JSONL 证据，包含命令、退出码、SHA、日志路径和验证等级。 |
| `capability-matrix.schema.json` | 能力矩阵的 JSON Schema（供后续工具或 CI 使用，本卡不改 CI）。 |
| `evidence-record.schema.json` | 单条证据记录的 JSON Schema（供后续工具或 CI 使用，本卡不改 CI）。 |

## 先静态校验矩阵

在仓库根目录运行（Node 22，无第三方依赖）：

```bash
node tools/acceptance/validate-acceptance-matrix.mjs task-notes/campus/acceptance/capability-matrix.json
```

期望退出码为 0。该命令只做静态一致性检查；真正的构建、测试和门禁必须在开发机通过 `cis-gate` 执行。

## 记录一次开发机门禁证据

先按 §12.2 在开发机执行门禁，例如：

```bash
ssh "$CIS_DEVBOX" /srv/xpeach-dev/campus/bin/cis-gate day-in-school main android-full
```

`cis-gate` 返回退出码、确切 SHA 和日志路径后，在仓库根目录追加证据索引：

```bash
node tools/acceptance/record-evidence.mjs \
  --out task-notes/campus/acceptance/evidence/stage-<序号>.jsonl \
  --item-id R0-1 \
  --evidence-type devbox-command-log \
  --verification-level MOCK_TESTED \
  --command 'ssh "$CIS_DEVBOX" /srv/xpeach-dev/campus/bin/cis-gate day-in-school main android-full' \
  --exit-code 0 \
  --android-sha <40位Android SHA> \
  --cloud-sha <40位Cloud SHA，单仓库门禁可省略> \
  --log-path /srv/xpeach-dev/campus/logs/day-in-school/main/<sha>-android-full.log \
  --executor acc/cis-stage-<序号> \
  --notes '按列车 PR 声明能力执行'
```

脚本行为：

- 追加一行 JSON 到 `--out`；不会执行 `--command` 中的命令。
- 必填：`--out`、`--item-id`、`--evidence-type`、`--verification-level`、`--command`、`--exit-code`、`--log-path`、`--executor`；`--android-sha` 与 `--cloud-sha` 至少提供一个。
- 可选：另一个仓库 SHA、`--executed-on`、`--started-at`、`--finished-at`、`--duration-ms`、`--test-name`、`--artifact-path`（可重复）、`--device-tested-pending-user`、`--notes`。
- 发现 IPv4 字面量、`user@host` 形式的开发机账号、私钥头或常见 Token 形状时拒绝写入。请改用 `$CIS_DEVBOX` 或 `<devbox>`。
- `--device-tested-pending-user` 只表示“待用户真机验证”，不计入通过，也不得把 `--verification-level` 写成 `DEVICE_TESTED`。

## 开发机路径与保密

- 可以在证据中记录开发机内日志路径，例如 `/srv/xpeach-dev/campus/logs/...`。
- 命令示例必须使用 `$CIS_DEVBOX`；不要把真实开发机地址、账号、密码、Token、Key 写入仓库、报告或证据索引。
- 禁止用 ssh 内联 heredoc 在开发机写文件；所有工具文件都在本机 worktree 编辑后经 git 传输。

## 本卡边界

这是骨架而非完整验收系统：不实现重型测试、不解析 `cis-gate` 日志、不修改 GitHub Actions、不碰 `TASKBOARD.md`。后续阶段验收卡可以在此基础上增加报告生成器和证据聚合器。
