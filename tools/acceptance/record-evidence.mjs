#!/usr/bin/env node
// 追加验收证据 JSONL。只记录已经执行过的命令；本脚本不执行构建、测试或 ssh。
// 写入前会把可识别的 campus 绝对路径规范化为 <campus-root>/...，并拒绝未脱敏秘密形状。

import { appendFile, mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { parseArgs } from 'node:util';

const usage = [
  '用法：',
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
  '本脚本只记录证据，不执行 --command。至少提供一个 --android-sha 或 --cloud-sha。',
  '如果把 cis-gate 输出的真实 campus 绝对路径传给 --log-path，脚本会规范化为 <campus-root>/... 后写入；可用 --campus-root 或 CIS_CAMPUS_ROOT 帮助识别，真实值不会落盘。'
].join(String.fromCharCode(10)) + String.fromCharCode(10);

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
    'campus-root': { type: 'string' },
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
function trimTrailingSlashes(value) {
  let result = value.trim();
  while (result.length > 1 && result.endsWith('/')) result = result.slice(0, -1);
  return result;
}
function validSuffix(value) {
  return typeof value === 'string' && value.length > 0 && !value.split('/').includes('..') && !value.includes(String.fromCharCode(0));
}
function extractCampusSuffix(value) {
  if (!value.startsWith('/')) return null;
  const parts = value.split('/');
  for (let index = 1; index < parts.length - 1; index += 1) {
    if (parts[index] === 'campus') {
      const suffix = parts.slice(index + 1).join('/');
      return validSuffix(suffix) ? suffix : null;
    }
  }
  return null;
}
function configuredCampusRoot(explicitRoot) {
  return trimTrailingSlashes(explicitRoot || process.env.CIS_CAMPUS_ROOT || '');
}
function normalizeCampusPath(input, explicitRoot) {
  const value = (input || '').trim();
  if (!value) return null;
  if (value.startsWith('<campus-root>/')) {
    const suffix = value.slice('<campus-root>/'.length);
    return validSuffix(suffix) ? value : null;
  }
  if (value.startsWith('campus://')) {
    const suffix = value.slice('campus://'.length);
    return validSuffix(suffix) ? '<campus-root>/' + suffix : null;
  }
  const root = configuredCampusRoot(explicitRoot);
  if (root && root !== '/' && value.startsWith(root + '/')) {
    const suffix = value.slice(root.length + 1);
    return validSuffix(suffix) ? '<campus-root>/' + suffix : null;
  }
  const suffix = extractCampusSuffix(value);
  if (suffix) return '<campus-root>/' + suffix;
  return null;
}
function normalizeCommand(input, explicitRoot) {
  let output = input;
  const root = configuredCampusRoot(explicitRoot);
  if (root && root !== '/') output = output.split(root).join('<campus-root>');
  output = output.replace(new RegExp('/(?:[^/]+/)*campus(?=/)', 'g'), '<campus-root>');
  return output;
}
function normalizeFreeText(input, explicitRoot) {
  if (typeof input !== 'string') return input;
  return normalizeCommand(input, explicitRoot);
}
function normalizeArtifactPath(input, explicitRoot) {
  const value = (input || '').trim();
  if (!value) return null;
  const campusPath = normalizeCampusPath(value, explicitRoot);
  if (campusPath) return campusPath;
  if (!value.startsWith('/') && validSuffix(value) && !value.includes(':')) return value;
  return null;
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
  fail('--executed-on 必须是 ' + executedOnValues.join(' / '));
}

let matrix;
try {
  matrix = JSON.parse(await readFile(values.matrix, 'utf8'));
} catch (error) {
  fail('无法读取能力矩阵（路径参数 matrix）；请先运行矩阵校验');
}

const matrixItem = matrix?.items?.find((entry) => entry.id === values['item-id']);
if (!matrixItem) fail('能力矩阵中不存在条目 ' + values['item-id']);
if (matrix && Array.isArray(matrix.evidenceTypes) && !matrix.evidenceTypes.includes(values['evidence-type'])) {
  fail('未登记的 evidence-type: ' + values['evidence-type']);
}
const levels = matrix?.verificationRules?.verificationLevels ?? [];
if (!levels.includes(values['verification-level'])) {
  fail('verification-level 未使用 VerificationLevel 枚举: ' + values['verification-level']);
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
  fail(values['item-id'] + ' 在矩阵中未登记 DEVICE_TESTED 待用户子项');
}

const normalizedCommand = typeof values.command === 'string' ? normalizeCommand(values.command, values['campus-root']) : values.command;
const normalizedLogPath = normalizeCampusPath(values['log-path'], values['campus-root']);
if (!normalizedLogPath) {
  fail('--log-path 无法脱敏为 campus 公开路径；请使用 <campus-root>/logs/... 或 campus://logs/...，或用 --campus-root / CIS_CAMPUS_ROOT 提供临时识别值');
} else if (!normalizedLogPath.startsWith('<campus-root>/logs/')) {
  fail('--log-path 规范化后必须位于 <campus-root>/logs/ 下；报告或截图请使用 --artifact-path');
}

const rawArtifactPaths = values['artifact-path'] ?? [];
if (!Array.isArray(rawArtifactPaths)) fail('--artifact-path 解析异常');
const artifactPaths = [];
for (const artifact of rawArtifactPaths) {
  const normalized = normalizeArtifactPath(artifact, values['campus-root']);
  if (!normalized) {
    fail('某个 --artifact-path 无法脱敏；只允许仓库相对路径、campus 公开路径或可识别的 campus 绝对路径');
  } else {
    artifactPaths.push(normalized);
  }
}

const normalizedNotes = normalizeFreeText(values.notes, values['campus-root']);
const normalizedTestName = normalizeFreeText(values['test-name'], values['campus-root']);

const secretScanValues = [
  ['command', normalizedCommand],
  ['log-path', normalizedLogPath],
  ['executor', values.executor],
  ['notes', normalizedNotes],
  ['test-name', normalizedTestName],
  ...artifactPaths.map((artifact, index) => ['artifact-path[' + index + ']', artifact])
];
const forbiddenRegexes = [
  { name: 'IPv4 字面量', pattern: new RegExp('(?:^|[^0-9.])(?:[0-9]{1,3}[.]){3}[0-9]{1,3}(?:[^0-9.]|$)') },
  { name: 'user@host 形式的地址或账号', pattern: new RegExp('[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+') },
  { name: 'GitHub Token 形状', pattern: new RegExp('(ghp_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})') },
  { name: 'OpenAI 风格 Key 形状', pattern: new RegExp('sk-[A-Za-z0-9_-]{20,}') },
  { name: '云 AccessKey 形状', pattern: new RegExp('AKIA[0-9A-Z]{16}') },
  { name: '未脱敏 srv 路径', pattern: new RegExp('/srv/') },
  { name: '未脱敏 Volumes 路径', pattern: new RegExp('/Volumes/') }
];
const forbiddenLiterals = [
  { name: '私钥头', text: 'PRIVATE KEY-----' },
  { name: '开发机账号字面量', text: ['xpeach', '-dev'].join('') },
  { name: '开发机 campus 路径字面量', text: ['/srv/', 'xpeach', '-dev'].join('') },
  { name: '本机绝对路径字面量', text: ['/Volumes/', 'SN ', '770'].join('') }
];
for (const [field, value] of secretScanValues) {
  if (typeof value !== 'string') continue;
  for (const { name, pattern } of forbiddenRegexes) {
    if (pattern.test(value)) fail(field + ' 含疑似敏感信息（' + name + '）；请使用 $CIS_DEVBOX 与 <campus-root>');
  }
  for (const { name, text } of forbiddenLiterals) {
    if (text && value.includes(text)) fail(field + ' 含禁用字面量（' + name + '）；请先脱敏');
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
  evidenceType: values['evidence-type'],
  verificationLevel: values['verification-level'],
  acceptanceOutcome: exitCode !== 0 ? 'fail' : (values['device-tested-pending-user'] ? 'device-pending-user' : 'pass'),
  command: normalizedCommand,
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
    logPath: normalizedLogPath,
    testName: normalizedTestName ?? null,
    artifactPaths
  },
  deviceTestedPendingUser: values['device-tested-pending-user'],
  notes: normalizedNotes ?? null
};

await mkdir(path.dirname(outPath), { recursive: true });
await appendFile(outPath, JSON.stringify(record) + '\n', 'utf8');
console.log(JSON.stringify({ wrote: values.out, recordId: record.recordId, itemId: record.itemId, exitCode: record.exitCode }, null, 2));
