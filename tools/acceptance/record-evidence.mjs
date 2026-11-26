#!/usr/bin/env node
// 追加验收证据 JSONL。只记录已经执行过的命令；本脚本不执行构建、测试或 ssh。

import { appendFile, mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { parseArgs } from 'node:util';

const usage = `用法：
node tools/acceptance/record-evidence.mjs \
  --out task-notes/campus/acceptance/evidence/stage-<序号>.jsonl \
  --item-id R0-1 \
  --evidence-type devbox-command-log \
  --verification-level MOCK_TESTED \
  --command 'ssh "$CIS_DEVBOX" /srv/xpeach-dev/campus/bin/cis-gate day-in-school main android-full' \
  --exit-code 0 \
  --android-sha <40位SHA> \
  --log-path /srv/xpeach-dev/campus/logs/day-in-school/main/<sha>-android-full.log \
  --executor acc/cis-stage-<序号>

本脚本只记录证据，不执行 --command。至少提供一个 --android-sha 或 --cloud-sha。
`;

const { values } = parseArgs({
  options: {
    out: { type: 'string' },
    matrix: { type: 'string', default: 'task-notes/campus/acceptance/capability-matrix.json' },
    'item-id': { type: 'string' },
    'evidence-type': { type: 'string' },
    'verification-level': { type: 'string' },
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
  if (typeof value !== 'string' || value.trim() === '') fail(`缺少必填参数 --${name}`);
  return value;
}
function validSha(value) {
  return typeof value === 'string' && /^[0-9a-f]{40}$/.test(value);
}
function validIsoInstant(value) {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value)) && /T/.test(value);
}

for (const name of ['out', 'item-id', 'evidence-type', 'verification-level', 'command', 'exit-code', 'log-path', 'executor']) {
  requireString(name);
}

if (!values['android-sha'] && !values['cloud-sha']) {
  fail('至少提供一个 --android-sha 或 --cloud-sha');
}
if (values['android-sha'] && !validSha(values['android-sha'])) fail('--android-sha 必须是 40 位小写十六进制 SHA');
if (values['cloud-sha'] && !validSha(values['cloud-sha'])) fail('--cloud-sha 必须是 40 位小写十六进制 SHA');

const exitCode = Number(values['exit-code']);
if (!Number.isInteger(exitCode) || exitCode < 0 || exitCode > 255) fail('--exit-code 必须是 0..255 的整数');

let durationMs = null;
if (values['duration-ms'] !== undefined) {
  durationMs = Number(values['duration-ms']);
  if (!Number.isInteger(durationMs) || durationMs < 0) fail('--duration-ms 必须是非负整数');
}
if (values['started-at'] && !validIsoInstant(values['started-at'])) fail('--started-at 必须是 ISO-8601 时间');
if (values['finished-at'] && !validIsoInstant(values['finished-at'])) fail('--finished-at 必须是 ISO-8601 时间');

const executedOnValues = ['devbox', 'github-ci', 'user-device', 'school-account', 'local-static'];
if (!executedOnValues.includes(values['executed-on'])) {
  fail(`--executed-on 必须是 ${executedOnValues.join(' / ')}`);
}

let matrix;
try {
  matrix = JSON.parse(await readFile(values.matrix, 'utf8'));
} catch (error) {
  fail(`无法读取能力矩阵 ${values.matrix}: ${error.message}`);
}

const matrixItem = matrix?.items?.find((entry) => entry.id === values['item-id']);
if (!matrixItem) fail(`能力矩阵中不存在条目 ${values['item-id']}`);
if (matrix && Array.isArray(matrix.evidenceTypes) && !matrix.evidenceTypes.includes(values['evidence-type'])) {
  fail(`未登记的 evidence-type: ${values['evidence-type']}`);
}
const levels = matrix?.verificationRules?.verificationLevels ?? [];
if (!levels.includes(values['verification-level'])) {
  fail(`verification-level 未使用 VerificationLevel 枚举: ${values['verification-level']}`);
}

if (values['verification-level'] === 'DEVICE_TESTED' && values['executed-on'] !== 'user-device') {
  fail('只有 --executed-on user-device 的手工真机证据才能标 DEVICE_TESTED');
}
if (values['verification-level'] === 'SCHOOL_VERIFIED' && values['executed-on'] !== 'school-account') {
  fail('只有 --executed-on school-account 的真实学校账号证据才能标 SCHOOL_VERIFIED');
}
if (values['verification-level'] === 'DISTRIBUTION_READY') {
  fail('阶段 S 骨架不接受 DISTRIBUTION_READY；发布边界见 D12');
}
if (values['device-tested-pending-user'] && values['verification-level'] === 'DEVICE_TESTED') {
  fail('DEVICE_TESTED 待用户表示尚未验证，不能同时标 DEVICE_TESTED');
}
if (values['device-tested-pending-user'] && matrixItem && matrixItem.deviceTested?.pendingUser !== true) {
  fail(`${values['item-id']} 在矩阵中未登记 DEVICE_TESTED 待用户子项`);
}

const artifactPaths = values['artifact-path'] ?? [];
if (!Array.isArray(artifactPaths)) fail('--artifact-path 解析异常');

const secretScanValues = [
  ['command', values.command],
  ['log-path', values['log-path']],
  ['executor', values.executor],
  ['notes', values.notes],
  ...artifactPaths.map((artifact, index) => [`artifact-path[${index}]`, artifact])
];
const forbiddenPatterns = [
  { name: 'IPv4 字面量', pattern: /\b(?:\d{1,3}\.){3}\d{1,3}\b/ },
  { name: 'user@host 形式的地址或账号', pattern: /(?<![\w$])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/ },
  { name: '私钥头', pattern: /-----BEGIN [A-Z ]*PRIVATE KEY-----/ },
  { name: 'GitHub Token 形状', pattern: /\b(?:ghp_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/ },
  { name: 'OpenAI 风格 Key 形状', pattern: /\bsk-[A-Za-z0-9_-]{20,}\b/ },
  { name: '云 AccessKey 形状', pattern: /\bAKIA[0-9A-Z]{16}\b/ }
];
for (const [field, value] of secretScanValues) {
  if (typeof value !== 'string') continue;
  for (const { name, pattern } of forbiddenPatterns) {
    if (pattern.test(value)) fail(`${field} 含疑似敏感信息（${name}）；请改用 $CIS_DEVBOX 或 <devbox>`);
  }
}

if (!values['log-path']?.startsWith('/srv/xpeach-dev/campus/')) {
  fail('--log-path 必须是开发机校园目录内的绝对路径，例如 /srv/xpeach-dev/campus/logs/...');
}

const repoRoot = process.cwd();
const acceptanceRoot = path.resolve(repoRoot, 'task-notes/campus/acceptance');
const outPath = path.resolve(repoRoot, values.out ?? '');
if (!outPath.startsWith(acceptanceRoot + path.sep) || !outPath.endsWith('.jsonl')) {
  fail('--out 必须位于 task-notes/campus/acceptance/ 内并以 .jsonl 结尾');
}

if (errors.length > 0) {
  console.error('证据记录校验失败，未写入：');
  for (const error of errors) console.error(`- ${error}`);
  process.exit(1);
}

const now = new Date().toISOString();
const record = {
  schemaVersion: 'cis-acceptance-evidence/v1',
  recordId: randomUUID(),
  recordedAt: now,
  itemId: values['item-id'],
  matrixVersion: matrix.schemaVersion,
  evidenceType: values['evidence-type'],
  verificationLevel: values['verification-level'],
  acceptanceOutcome: exitCode === 0 ? 'pass' : 'fail',
  command: values.command,
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
    logPath: values['log-path'],
    testName: values['test-name'] ?? null,
    artifactPaths
  },
  deviceTestedPendingUser: values['device-tested-pending-user'],
  notes: values.notes ?? null
};

await mkdir(path.dirname(outPath), { recursive: true });
await appendFile(outPath, JSON.stringify(record) + '\n', 'utf8');
console.log(JSON.stringify({ wrote: outPath, recordId: record.recordId, itemId: record.itemId, exitCode: record.exitCode }, null, 2));
