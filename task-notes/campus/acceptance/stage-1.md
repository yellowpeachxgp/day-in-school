# 阶段联调验收 stage-1：INF-00 基础设施

> 验收日期：2026-11-27（Asia/Shanghai）
> 验收子代理：s10b-accfinish（未参与 INF-00 开发）
> 交叉复核子代理：pending
> 对象：Android PR#53 merge `f18915f00b501c547f9920fde2fd8fbf779c1fdd`；Cloud PR#6 merge `d4fb93c14344078eb8fb43465b0aa507134bf10f`
> 能力编号：C25（架构与工程中的开发机基础设施部分）
> 总结论：**INF-00 自身验收点为 pass；stage-1 联调门禁矩阵为 blocked，不得视为全部通过**。阻断不是 INF-00 回归，而是 INF-02 契约收敛和 INF-01 secrets scanner 尚未合入。

## 1. 元信息与执行边界

| 字段 | 值 |
|---|---|
| Android main SHA | `f18915f00b501c547f9920fde2fd8fbf779c1fdd` |
| Cloud main SHA | `d4fb93c14344078eb8fb43465b0aa507134bf10f` |
| 验收分支 | `acc/cis-stage-1-inf00` |
| 本报告唯一仓库改动 | `task-notes/campus/acceptance/stage-1.md` |
| 开发机准备 | 验收前将两仓 `<devbox>` bare `main` 以 fast-forward 方式同步到上述 origin/main SHA；未改写历史 |
| 验证边界 | 开发机联调验收；不标 `DEVICE_TESTED` / `SCHOOL_VERIFIED`；不部署、不发版 |

## 2. 新 main 门禁汇总

| 门禁 | 结果 | exit | SHA | 耗时 | 日志 / 说明 |
|---|---|---:|---|---:|---|
| Android `android-full` | pass | 0 | `f18915f00b501c547f9920fde2fd8fbf779c1fdd` | 13s | `<campus-root>/logs/day-in-school/main/f18915f00b501c547f9920fde2fd8fbf779c1fdd-android-full.log` |
| Cloud `cloud` | pass | 0 | `d4fb93c14344078eb8fb43465b0aa507134bf10f` | 11s | `<campus-root>/logs/cloud-in-school/main/d4fb93c14344078eb8fb43465b0aa507134bf10f-cloud.log` |
| Android `contracts` | **fail（INF-02 缺口）** | 1 | `f18915f00b501c547f9920fde2fd8fbf779c1fdd` | 2s | `<campus-root>/logs/day-in-school/main/f18915f00b501c547f9920fde2fd8fbf779c1fdd-contracts.log` |
| Android `secrets` | **blocked（INF-01 缺口）** | 3 | `f18915f00b501c547f9920fde2fd8fbf779c1fdd` | 0s | `<campus-root>/logs/day-in-school/main/f18915f00b501c547f9920fde2fd8fbf779c1fdd-secrets.log` |
| Cloud `secrets` | **blocked（INF-01 缺口）** | 3 | `d4fb93c14344078eb8fb43465b0aa507134bf10f` | 1s | `<campus-root>/logs/cloud-in-school/main/d4fb93c14344078eb8fb43465b0aa507134bf10f-secrets.log` |
| Android 故意失败 `quick :definitely-not-a-module` | pass（负向语义正确） | 1 | `f18915f00b501c547f9920fde2fd8fbf779c1fdd` | 3s | `<campus-root>/logs/day-in-school/main/f18915f00b501c547f9920fde2fd8fbf779c1fdd-quick.log` |

门禁细节：

- `android-full`：`BUILD SUCCESSFUL in 12s`，1475 actionable tasks（796 executed、679 from cache）；Gradle systemd scope peak 150,196,224 B，上限 10,737,418,240 B。
- `cloud`：204/204 tests pass；contract-ci pass（97 个 OpenAPI 3.1.2 operations 100% inventory parity、5 个 JSON Schema）；日志含 `CIS_CLOUD_NPM_CI=skipped-no-lockfile-no-dependencies`。
- `contracts`：Android 本地 contract-ci 自身通过（67 个 operations 100% parity），但两仓 `contracts/endpoints/endpoint-inventory.yaml` 与 `contracts/openapi/cloud-v1.yaml` 不一致；这是 INF-02 未收敛的已知缺口，**不计入 pass**。
- `secrets`：两仓均明确输出 `secrets scanner is missing: INF-01 has not merged tools/secret-scan yet`；这是扫描器缺失，不是命中，也不是通过。
- 故意失败：不存在的 Gradle module 使 `BUILD FAILED`，`cis-gate` 如实返回 exit 1，未遮蔽失败。

## 3. C25 / INF-00 验收点逐项核验

| 验收点 | 结论 | 证据与说明 |
|---|---|---|
| Android SDK、platform/build-tools、campus-local Node、裸库、缓存可用 | pass | 新 main `android-full` 与 `cloud` 均 exit 0；复核 Node v22.23.5、Android API 36 platform present；Gradle cache 参数为 Xmx3g、Kotlin daemon Xmx2g、workers=6、parallel/caching |
| `cis-gate` 解析分支到确切 SHA、检出到 build、写完整日志并打印摘要 | pass | 本次 6 个门禁调用均在输出中给出精确 merge SHA、exit、duration、log path 和末尾日志 |
| 门禁失败不被 summary/tail 或输出管道遮蔽 | pass | 故意失败 `quick` 在 merge main 上 exit 1；日志明确记录失败任务与 `CIS_GATE_EXIT_CODE=1` |
| 4 个 Gradle flock 构建槽上限 | pass | 独立复验：手动持有 4 个 flock fd 后，第 5 个 `cis-gradle --version` timeout 124 且输出 `all four Gradle build slots are busy; queueing`；释放后 exit 0 并获得 slot。脚本哈希已确认与 merge main 相同 |
| `PAUSE` 阻塞/恢复 | pass | 独立复验使用 `CIS_PAUSE_FILE` 隔离路径以避免干扰其他代理：文件存在时 waiting 且无 slot；删除后 4s 恢复并运行成功。源码默认路径为 `<campus-root>/PAUSE` |
| `MemAvailable < 24GiB` 等待逻辑 | pass | 独立复验将阈值临时设为不可能满足的大值，进程 timeout 124，输出当前 MemAvailable 低于阈值且未获得 slot；源码默认阈值为 24GiB |
| systemd user scope 与内存上限 | pass | 当前日志显示 `runner=systemd-user-scope-MemoryMax10G`、`CIS_GRADLE_MEMORY_MAX_BYTES=10737418240`；peak 远低于上限 |
| Gradle/Android 环境隔离在 campus cache 内 | pass | 当前 quick 日志显示 `GRADLE_USER_HOME=<campus-root>/cache/gradle`、`ANDROID_HOME=<campus-root>/android-sdk` |
| Cloud npm 安装策略（AUTO-006 候选） | pass | 当前 cloud 日志含 `CIS_CLOUD_NPM_CI=skipped-no-lockfile-no-dependencies`；随后 `npm test` 与 `contract:check` 均通过 |
| `cis-*` 容器只绑 loopback、随机端口、非 restart、精确命名 | pass | 独立复验 postgres/redis 两个容器：输出 Host 均为 `127.0.0.1`，inspect 显示 restart=no、PortBindings HostIp=`127.0.0.1` |
| 容器凭据不打印且 auth 文件受控 | pass | 两个 auth 文件 mode 均为 600；密码未出现在 start 输出 |
| 容器精确清理且不触碰非校园容器 | pass | stop 后目标容器与 auth 文件均删除；同前缀 `-decoy` 容器保留，证明不是 glob 删除；测试前后既有非 `cis-` 容器 missing count=0 |
| root/canonical root 防误删 | pass | `CIS_CAMPUS_ROOT=/` exit 2（non-root absolute path）；`/tmp` exit 2（canonical path must contain campus）；远端 uid 非 root，canonical root 含 campus |
| `cis-clean` 只在 campus root 内清理 | pass | `cis-clean --dry-run` exit 0，worktrees/logs removed 均为 0；源码先用 canonical root 校验并限制 build/logs 子路径 |
| 脚本真实脱敏、不写死地址/账号/凭据 | pass | merge main 两仓 8 个脚本逐对哈希一致，`<devbox>` installed 8 个脚本与 merge main 一致；真实 `<devbox-user>`/`<devbox>`、campus 根及父路径命中均为 0；私钥头、GitHub Token、OpenAI Key 命中 0 |
| secrets 缺失时不伪造通过 | pass（诚实语义） / gate blocked | 两仓 secrets 均 exit 3，并明确缺失原因；语义正确，但不能算 secrets 门禁通过 |

## 4. 失败与缺口分诊

| 项 | 状态 | 是否 INF-00 阻断 | 说明 / 后续 |
|---|---|---:|---|
| 两仓 contracts 漂移 | fail | 否 | INF-02 契约收敛未合入；`endpoint-inventory.yaml` 与 `openapi/cloud-v1.yaml` 不一致。不得算 pass，需 INF-02/CT 线修复后重跑 |
| secrets scanner 缺失 | blocked | 否 | INF-01 未合入 `tools/secret-scan`；scanner 可用后必须在两仓重跑 |
| Android 托管 CI 上游依赖波动 | 风险 | 否 | INF-00 PR 正文记录 Maven Central 429 曾影响重复 workflow；本验收以开发机 `android-full` 为准 |
| systemd scope 内存归因 | 风险 | 否 | peak 是主机级采样且 Gradle daemon 复用可能混合归因；已在 INF-00 PR 与本报告中保留说明 |
| 逐文件 atomic mv | 风险 | 否 | 可防止半写入但不是整目录事务；INF-00 PR 已声明，后续可改代际安装 |

## 5. 交叉复核所需重点

1. 复核上表中的 6 个新 main gate log 是否存在且 SHA 与本报告一致。
2. 复核 `contracts` exit 1 与两仓 `secrets` exit 3 是否确属 INF-02/INF-01 缺口，而不是 INF-00 脚本错误。
3. 复核 8 个 installed script 与两仓 merge main 的哈希一致性及敏感字面量扫描。
4. 如需重跑资源守卫，优先使用隔离 `CIS_PAUSE_FILE`，不要在多代理并发时直接创建全局 `<campus-root>/PAUSE`。

## 6. 总结论

- **C25 / INF-00 实现验收：pass**。开发机基础设施、门禁失败语义、资源守卫、容器边界、root/canonical root 防误删、脚本脱敏和两仓基础构建门禁均满足 INF-00 验收点。
- **stage-1 联调整体：blocked**。contracts 与 secrets 不能算 pass；阻断归属 INF-02/INF-01 后续卡，不得记为 INF-00 失败，也不得宣称阶段门禁全绿。
- 本报告未验证真机、真实学校账号、生产部署或发布链路；这些均不属于 INF-00 本阶段范围。
