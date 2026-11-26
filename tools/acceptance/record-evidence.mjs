#!/usr/bin/env node
// 追加验收证据 JSONL。只记录已经执行过或人工确认过的证据；本脚本不执行构建、测试、ssh 或设备操作。
// 写入前统一脱敏；敏感检测只使用通用形状和运行时环境值，不在仓库写死真实主机、账号或路径。

import { appendFile, mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { parseArgs } from 'node:util';
import {
  findSensitiveHits,
  isPublicArtifactPath,
  isPublicCampusLogPath
} from './sensitive-utils.mjs';

const usage = [
  '用法（命令型证据示例）：',
  'node tools/acceptance/record-evidence.mjs',
  '  --out task-notes/campus/acceptance/evidence/stage-<序号>.jsonl',
  '  --item-id R0-1',
  '  --evidence-type devbox-command-log',
  '  --verification-level MOCK_TESTED',
  '  --command ssh $CIS_DEVBOX <campus-root>/bin/cis-gate day-in-school main android-full',
  '  --exit-code 0',
  '  --android-sha <40位SHA>',
  '  --log-path <campus-root>/logs/day-in-school/main/<sha>-android-full.log',
  '  --executor acc/cis-stage-<序号>',
  '',
  '非命令型证据（如 github-ci-run、manual-device-note、screenshot-or-recording、source-audit-note）可不提供 command/exitCode/logPath，但必须显式提供 --outcome，并按类型提供 artifact/testName/notes。',
  '至少提供一个 --android-sha 或 --cloud-sha。命令和路径必须已经是脱敏模板；脚本不再改写真实绝对路径。'
].join(String.fromCharCode(10)) + String.fromCharCode(10);

const { values } = parseArgs({
  options: {
    out: { type: 'string' },
    matrix: { type: 'string', default: 'task-notes/campus/acceptance/capability-matrix.json' },
    'item-id': { type: 'string' },
    'evidence-type': { type: 'string' },
    'verification-level': { type: 'string' },
    outcome: { type: 'string' },
    command: { type: 'string' },
    'exit-code': { type: 'string' },
    'android-sha': { type: 'string' },
    'cloud-sha': { type: 'string' },
    'log-path': { type: 'string' },
    executor: { type: 'string' },
    'executed-on': { type: 'string', default: 'devbox' },
    'started-at': { type: 'string' },
    'finished-at': { type: 'string' },
    'duration-ms': { type: 'string' },
    'test-name': { type: 'string' },
    'artifact-path': { type: 'string', multiple: true },
    'device-tested-pending-user': { type: 'boolean', default: false },
    notes: { type: 'string' },
    help: { type: 'boolean', default: false }
  },
  allowPositionals: false
});

if (values.help) {
  console.log(usage);
  process.exit(0);
}

const errors = [];
function fail(message) {
  errors.push(message);
}
function requireString(name) {
  const value = values[name];
  if (typeof value !== 'string' || value.trim() === '') fail('缺少必填参数 --' + name);
  return value;
}
function validSha(value) {
  return typeof value === 'string' && /^[0-9a-f]{40}$/.test(value);
}
function validIsoInstant(value) {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value)) && value.includes('T');
}
function nonEmpty(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

for (const name of ['out', 'item-id', 'evidence-type', 'verification-level', 'executor']) {
  requireString(name);
}

if (!values['android-sha'] && !values['cloud-sha']) {
  fail('至少提供一个 --android-sha 或 --cloud-sha');
}
if (values['android-sha'] && !validSha(values['android-sha'])) fail('--android-sha 必须是 40 位小写十六进制 SHA');
if (values['cloud-sha'] && !validSha(values['cloud-sha'])) fail('--cloud-sha 必须是 40 位小写十六进制 SHA');

let exitCode = null;
if (values['exit-code'] !== undefined) {
  exitCode = Number(values['exit-code']);
  if (!Number.isInteger(exitCode) || exitCode < 0 || exitCode > 255) fail('--exit-code 必须是 0..255 的整数');
}

let durationMs = null;
if (values['duration-ms'] !== undefined) {
  durationMs = Number(values['duration-ms']);
  if (!Number.isInteger(durationMs) || durationMs < 0) fail('--duration-ms 必须是非负整数');
}
if (values['started-at'] && !validIsoInstant(values['started-at'])) fail('--started-at 必须是 ISO-8601 时间');
if (values['finished-at'] && !validIsoInstant(values['finished-at'])) fail('--finished-at 必须是 ISO-8601 时间');

const executedOnValues = ['devbox', 'github-ci', 'user-device', 'local-static'];
if (!executedOnValues.includes(values['executed-on'])) {
  fail('--executed-on 必须是 ' + executedOnValues.join(' / '));
}

let matrix;
try {
  matrix = JSON.parse(await readFile(values.matrix, 'utf8'));
} catch (error) {
  fail('无法读取能力矩阵；请先运行矩阵校验');
}
if (!matrix || errors.length > 0) {
  console.error('证据记录校验失败，未写入：');
  for (const error of errors) console.error('- ' + error);
  process.exit(1);
}

const matrixItem = matrix.items?.find((entry) => entry.id === values['item-id']);
if (!matrixItem) fail('能力矩阵中不存在条目 ' + values['item-id']);
if (!Array.isArray(matrix.evidenceTypes) || !matrix.evidenceTypes.includes(values['evidence-type'])) {
  fail('未登记的 evidence-type: ' + values['evidence-type']);
}
const levels = matrix.verificationRules?.verificationLevels ?? [];
if (!levels.includes(values['verification-level'])) {
  fail('verification-level 未使用 VerificationLevel 枚举: ' + values['verification-level']);
}

if (values['verification-level'] === 'DEVICE_TESTED' && values['executed-on'] !== 'user-device') {
  fail('只有 --executed-on user-device 的手工真机证据才能标 DEVICE_TESTED');
}
if (values['verification-level'] === 'SCHOOL_VERIFIED') {
  fail('阶段 S 证据索引 fail-closed：不得记录或宣称 SCHOOL_VERIFIED；如需真实学校验证，先由用户和主代理开启专项流程');
}
if (values['verification-level'] === 'DISTRIBUTION_READY') {
  fail('阶段 S 骨架不接受 DISTRIBUTION_READY；发布边界见 D12');
}
if (values['device-tested-pending-user'] && values['verification-level'] === 'DEVICE_TESTED') {
  fail('DEVICE_TESTED 待用户表示尚未验证，不能同时标 DEVICE_TESTED');
}

const commandText = typeof values.command === 'string' && values.command.trim() ? values.command.trim() : null;
let publicLogPath = null;
if (values['log-path'] !== undefined) {
  const candidate = values['log-path'].trim();
  if (!isPublicCampusLogPath(candidate)) {
    fail('--log-path 必须已经是 <campus-root>/logs/... 或 campus://logs/...；脚本不再改写真实绝对路径');
  } else {
    publicLogPath = candidate;
  }
}

const rawArtifactPaths = values['artifact-path'] ?? [];
if (!Array.isArray(rawArtifactPaths)) fail('--artifact-path 解析异常');
const artifactPaths = [];
for (const artifact of rawArtifactPaths) {
  const candidate = artifact.trim();
  if (!isPublicArtifactPath(candidate)) {
    fail('某个 --artifact-path 不被允许；只接受 campus:// 或无凭据、无 query 的 https URL');
  } else {
    artifactPaths.push(candidate);
  }
}
const notesText = values.notes;
const testNameText = values['test-name'];

const evidenceType = values['evidence-type'];
const requiredTypes = matrixItem?.requiredEvidenceTypes ?? [];
const pendingTypes = matrixItem?.deviceTested?.pendingEvidenceTypes ?? [];
const isRequiredEvidence = requiredTypes.includes(evidenceType);
const isPendingEvidence = pendingTypes.includes(evidenceType);
if (matrixItem && !isRequiredEvidence && !isPendingEvidence) {
  fail(itemLabel() + ' 的 ' + evidenceType + ' 未在该条目 requiredEvidenceTypes 或 deviceTested.pendingEvidenceTypes 中登记');
}
function itemLabel() {
  return values['item-id'] || '<unknown-item>';
}

if (values['device-tested-pending-user']) {
  if (matrixItem?.deviceTested?.pendingUser !== true) {
    fail(itemLabel() + ' 在矩阵中未登记 DEVICE_TESTED 待用户子项');
  }
  if (!isRequiredEvidence) {
    fail('DEVICE_TESTED 待用户标记只能附加在该条目的自动 requiredEvidenceTypes 证据上，不能附加到待用户证据类型');
  }
}
if (isPendingEvidence && values['device-tested-pending-user']) {
  fail('待用户证据类型本身不能再标 DEVICE_TESTED 待用户');
}

const outcomes = matrix.acceptanceOutcomes ?? ['pass', 'fail', 'not-run', 'blocked', 'device-pending-user'];
if (values.outcome && !outcomes.includes(values.outcome)) {
  fail('--outcome 未登记: ' + values.outcome);
}
let acceptanceOutcome = values.outcome ?? null;
if (!acceptanceOutcome) {
  if (values['device-tested-pending-user']) {
    acceptanceOutcome = 'device-pending-user';
  } else if (exitCode !== null) {
    acceptanceOutcome = exitCode === 0 ? 'pass' : 'fail';
  }
}
if (!acceptanceOutcome) {
  fail('非命令型证据必须显式提供 --outcome');
}
if (values['device-tested-pending-user'] && acceptanceOutcome === 'pass') {
  fail('DEVICE_TESTED 待用户不计入通过，acceptanceOutcome 不得为 pass');
}

const hasCommand = nonEmpty(commandText);
const hasExitCode = exitCode !== null;
const hasLogPath = nonEmpty(publicLogPath);
const hasArtifacts = artifactPaths.length > 0;
const hasTestName = nonEmpty(testNameText);
const hasNotes = nonEmpty(notesText);
if (hasCommand && !hasExitCode) fail('提供 --command 时必须同时提供 --exit-code');

const specialRules = {
  'devbox-command-log': { executedOn: ['devbox'], command: true, exitCode: true, logPath: true },
  'github-ci-run': { executedOn: ['github-ci'], anyOf: ['artifacts', 'testName', 'notes'] },
  'manual-device-note': { executedOn: ['user-device'], anyOf: ['artifacts', 'testName', 'notes'] },
  'screenshot-or-recording': { executedOn: ['devbox', 'github-ci', 'user-device', 'local-static'], artifacts: true },
  'source-audit-note': { executedOn: ['devbox', 'local-static'], anyOf: ['testName', 'notes'] }
};
const rule = specialRules[evidenceType] ?? {
  executedOn: ['devbox', 'github-ci', 'local-static'],
  anyOf: ['command', 'logPath', 'artifacts', 'testName']
};
if (!rule.executedOn.includes(values['executed-on'])) {
  fail(evidenceType + ' 不允许 executedOn=' + values['executed-on']);
}
if (rule.command && !hasCommand) fail(evidenceType + ' 需要 --command');
if (rule.exitCode && !hasExitCode) fail(evidenceType + ' 需要 --exit-code');
if (rule.logPath && !hasLogPath) fail(evidenceType + ' 需要脱敏 --log-path');
if (rule.artifacts && !hasArtifacts) fail(evidenceType + ' 需要至少一个 --artifact-path');
if (rule.anyOf) {
  const available = {
    command: hasCommand,
    logPath: hasLogPath,
    artifacts: hasArtifacts,
    testName: hasTestName,
    notes: hasNotes
  };
  if (!rule.anyOf.some((key) => available[key])) {
    fail(evidenceType + ' 至少需要 ' + rule.anyOf.join(' / ') + ' 中的一项');
  }
}

const secretScanValues = [
  ['command', commandText],
  ['log-path', publicLogPath],
  ['executor', values.executor],
  ['notes', notesText],
  ['test-name', testNameText],
  ...artifactPaths.map((artifact, index) => ['artifact-path[' + index + ']', artifact])
];
for (const [field, value] of secretScanValues) {
  for (const hit of findSensitiveHits(value)) {
    fail(field + ' 含疑似敏感信息（' + hit + '）；请使用 $CIS_DEVBOX、<campus-root>、campus:// 或无凭据无 query 的 https URL');
  }
}

const repoRoot = process.cwd();
const acceptanceRoot = path.resolve(repoRoot, 'task-notes/campus/acceptance');
if (path.isAbsolute(values.out ?? '')) {
  fail('--out 必须是仓库内相对路径，避免输出本机绝对路径');
}
const outPath = path.resolve(repoRoot, values.out ?? '');
if (!outPath.startsWith(acceptanceRoot + path.sep) || !outPath.endsWith('.jsonl')) {
  fail('--out 必须位于 task-notes/campus/acceptance/ 内并以 .jsonl 结尾');
}

if (errors.length > 0) {
  console.error('证据记录校验失败，未写入：');
  for (const error of errors) console.error('- ' + error);
  process.exit(1);
}

const now = new Date().toISOString();
const record = {
  schemaVersion: 'cis-acceptance-evidence/v1',
  recordId: randomUUID(),
  recordedAt: now,
  itemId: values['item-id'],
  matrixVersion: matrix.schemaVersion,
  evidenceType,
  verificationLevel: values['verification-level'],
  acceptanceOutcome,
  command: commandText,
  exitCode,
  sha: {
    android: values['android-sha'] ?? null,
    cloud: values['cloud-sha'] ?? null
  },
  execution: {
    executedOn: values['executed-on'],
    executor: values.executor,
    startedAt: values['started-at'] ?? null,
    finishedAt: values['finished-at'] ?? null,
    durationMs,
    logPath: publicLogPath,
    testName: testNameText ?? null,
    artifactPaths
  },
  deviceTestedPendingUser: values['device-tested-pending-user'],
  notes: notesText ?? null
};

await mkdir(path.dirname(outPath), { recursive: true });
await appendFile(outPath, JSON.stringify(record) + String.fromCharCode(10), 'utf8');
console.log(JSON.stringify({ wrote: values.out, recordId: record.recordId, itemId: record.itemId, outcome: record.acceptanceOutcome }, null, 2));
