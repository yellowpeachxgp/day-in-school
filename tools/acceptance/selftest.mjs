#!/usr/bin/env node
// acceptance 工具自测：schema/recorder 一致性与负向回归样例。无第三方依赖；只写临时 JSONL 并清理。

import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { findSensitiveHits, isPublicArtifactPath, isPublicCampusLogPath } from './sensitive-utils.mjs';

const repoRoot = process.cwd();
const recorder = path.join(repoRoot, 'tools/acceptance/record-evidence.mjs');
const outRel = 'task-notes/campus/acceptance/evidence/.tmp-acceptance-selftest.jsonl';
const outAbs = path.resolve(repoRoot, outRel);
const sha = '0123456789abcdef0123456789abcdef01234567';
const errors = [];

function fail(message) {
  errors.push(message);
}
function assert(condition, message) {
  if (!condition) fail(message);
}
function sameArray(a, b) {
  return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((value, index) => value === b[index]);
}
function runRecorder(args, extraEnv = {}) {
  return spawnSync(process.execPath, [recorder, ...args], {
    cwd: repoRoot,
    env: { ...process.env, ...extraEnv },
    encoding: 'utf8'
  });
}
function baseArgs(itemId, evidenceType, verificationLevel) {
  return [
    '--out', outRel,
    '--item-id', itemId,
    '--evidence-type', evidenceType,
    '--verification-level', verificationLevel,
    '--android-sha', sha,
    '--executor', 'acceptance-selftest'
  ];
}
function commandEvidenceArgs(itemId = 'R0-1') {
  return [
    ...baseArgs(itemId, 'devbox-command-log', 'MOCK_TESTED'),
    '--command', 'ssh $CIS_DEVBOX <campus-root>/bin/cis-gate day-in-school main android-full',
    '--exit-code', '0',
    '--log-path', '<campus-root>/logs/day-in-school/main/' + sha + '-android-full.log',
    '--notes', 'up / down / repeat wording is allowed'
  ];
}
async function cleanup() {
  await rm(outAbs, { force: true });
  await rm(path.dirname(outAbs), { recursive: false }).catch(() => {});
}

await cleanup();

const matrix = JSON.parse(await readFile(path.join(repoRoot, 'task-notes/campus/acceptance/capability-matrix.json'), 'utf8'));
const schema = JSON.parse(await readFile(path.join(repoRoot, 'tools/acceptance/evidence-record.schema.json'), 'utf8'));
const manifest = JSON.parse(await readFile(path.join(repoRoot, 'tools/acceptance/selftest-cases.json'), 'utf8'));
assert(sameArray(schema.properties.evidenceType.enum, matrix.evidenceTypes), 'schema evidenceType enum 与矩阵 evidenceTypes 不一致');
assert(sameArray(schema.properties.execution.properties.executedOn.enum, ['devbox', 'github-ci', 'user-device', 'local-static']), 'schema executedOn enum 不符合 fail-closed 设计');
assert(schema.properties.verificationLevel.not?.const === 'SCHOOL_VERIFIED', 'schema 未显式禁止 SCHOOL_VERIFIED');

const positiveCases = [
  {
    name: 'command-devbox',
    args: commandEvidenceArgs(),
    outcome: 'pass'
  },
  {
    name: 'github-ci-run',
    args: [
      ...baseArgs('R0-5', 'github-ci-run', 'MOCK_TESTED'),
      '--executed-on', 'github-ci',
      '--outcome', 'pass',
      '--artifact-path', 'https://github.com/yellowpeachxgp/day-in-school/actions/runs/123456'
    ],
    outcome: 'pass'
  },
  {
    name: 'manual-device-note',
    args: [
      ...baseArgs('C01', 'manual-device-note', 'DEVICE_TESTED'),
      '--executed-on', 'user-device',
      '--outcome', 'pass',
      '--test-name', '用户真机复测记录'
    ],
    outcome: 'pass'
  },
  {
    name: 'source-audit-note',
    args: [
      ...baseArgs('C11', 'source-audit-note', 'SOURCE_CONFIRMED'),
      '--executed-on', 'local-static',
      '--outcome', 'pass',
      '--notes', '人工核对培养方案导入来源与证据链'
    ],
    outcome: 'pass'
  },
  {
    name: 'screenshot-or-recording',
    args: [
      ...baseArgs('C01', 'screenshot-or-recording', 'DEVICE_TESTED'),
      '--executed-on', 'user-device',
      '--outcome', 'pass',
      '--artifact-path', 'campus://artifacts/screenshots/c01.png'
    ],
    outcome: 'pass'
  }
];
assert(sameArray(manifest.positive, positiveCases.map((entry) => entry.name)), 'positive manifest 与实现不一致');

const positiveResults = [];
for (const testCase of positiveCases) {
  const result = runRecorder(testCase.args);
  positiveResults.push({ name: testCase.name, status: result.status, stderr: result.stderr.trim() });
  assert(result.status === 0, 'positive case failed: ' + testCase.name);
}

if (errors.length === 0) {
  const lines = (await readFile(outAbs, 'utf8')).trim().split(String.fromCharCode(10));
  assert(lines.length === positiveCases.length, 'positive JSONL 行数不正确');
  const records = lines.map((line) => JSON.parse(line));
  const requiredTopLevel = [...schema.required].sort();
  for (const [index, record] of records.entries()) {
    const testCase = positiveCases[index];
    assert(record.itemId === testCase.args[testCase.args.indexOf('--item-id') + 1], 'positive itemId mismatch: ' + testCase.name);
    assert(record.acceptanceOutcome === testCase.outcome, 'positive outcome mismatch: ' + testCase.name);
    assert(sameArray(Object.keys(record).sort(), requiredTopLevel), 'record top-level keys mismatch: ' + testCase.name);
    assert(schema.properties.evidenceType.enum.includes(record.evidenceType), 'record evidenceType absent from schema');
    assert(schema.properties.execution.properties.executedOn.enum.includes(record.execution.executedOn), 'record executedOn absent from schema');
    assert(record.command === null || typeof record.command === 'string', 'command nullability mismatch');
    assert(record.exitCode === null || Number.isInteger(record.exitCode), 'exitCode nullability mismatch');
    assert(record.execution.logPath === null || isPublicCampusLogPath(record.execution.logPath), 'logPath shape mismatch');
    for (const artifact of record.execution.artifactPaths) {
      assert(isPublicArtifactPath(artifact), 'artifact shape mismatch');
    }
    const scanValues = [record.command, record.execution.logPath, record.execution.executor, record.execution.testName, record.notes, ...record.execution.artifactPaths];
    for (const value of scanValues) {
      assert(findSensitiveHits(value).length === 0, 'record still contains sensitive shape');
    }
  }
}

const slash = String.fromCharCode(47);
const fakeUnixHome = slash + 'home' + slash + 'selftester' + slash + 'evidence.png';
const fakeMacHome = slash + 'Users' + slash + 'selftester' + slash + 'evidence.png';
const fakeTmp = slash + 'tmp';
const fakeVarLog = slash + 'var' + slash + 'log' + slash + 'example.log';
const negativeCases = [
  {
    name: 'type-mismatch',
    args: [
      ...baseArgs('C01', 'github-ci-run', 'MOCK_TESTED'),
      '--executed-on', 'github-ci',
      '--outcome', 'pass'
    ],
    expect: '未在该条目'
  },
  {
    name: 'unix-home-path-note',
    args: [...commandEvidenceArgs(), '--notes', 'bad path ' + fakeUnixHome],
    expect: 'home 或绝对路径'
  },
  {
    name: 'mac-users-path-note',
    args: [...commandEvidenceArgs(), '--notes', 'bad path ' + fakeMacHome],
    expect: 'home 或绝对路径'
  },
  {
    name: 'absolute-command',
    args: [
      ...baseArgs('R0-1', 'devbox-command-log', 'MOCK_TESTED'),
      '--command', 'cd ' + fakeTmp + ' && echo no',
      '--exit-code', '0',
      '--log-path', '<campus-root>/logs/example.log'
    ],
    expect: '绝对路径'
  },
  {
    name: 'absolute-log-path',
    args: [
      ...baseArgs('R0-1', 'devbox-command-log', 'MOCK_TESTED'),
      '--command', 'ssh $CIS_DEVBOX <campus-root>/bin/cis-gate day-in-school main android-full',
      '--exit-code', '0',
      '--log-path', fakeVarLog
    ],
    expect: '不再改写真实绝对路径'
  },
  {
    name: 'school-verified-fail-closed',
    args: [
      ...baseArgs('R0-1', 'devbox-command-log', 'SCHOOL_VERIFIED'),
      '--command', 'ssh $CIS_DEVBOX <campus-root>/bin/cis-gate day-in-school main android-full',
      '--exit-code', '0',
      '--log-path', '<campus-root>/logs/example.log'
    ],
    expect: '不得记录或宣称 SCHOOL_VERIFIED'
  },
  {
    name: 'artifact-query',
    args: [
      ...baseArgs('C01', 'screenshot-or-recording', 'DEVICE_TESTED'),
      '--executed-on', 'user-device',
      '--outcome', 'pass',
      '--artifact-path', 'https://example.com/evidence.png?token=redacted'
    ],
    expect: '无凭据、无 query'
  },
  {
    name: 'runtime-sensitive',
    args: [...commandEvidenceArgs(), '--notes', '包含 selftester'],
    env: { CIS_DEVBOX: 'selftester@example.invalid' },
    expect: '运行时环境敏感值'
  }
];
assert(sameArray(manifest.negative, negativeCases.map((entry) => entry.name)), 'negative manifest 与实现不一致');
await cleanup();
const negativeResults = [];
for (const testCase of negativeCases) {
  const result = runRecorder(testCase.args, testCase.env ?? {});
  const output = (result.stderr || '') + (result.stdout || '');
  negativeResults.push({ name: testCase.name, status: result.status, matched: output.includes(testCase.expect) });
  assert(result.status !== 0, 'negative case unexpectedly passed: ' + testCase.name);
  assert(output.includes(testCase.expect), 'negative case missing expected error: ' + testCase.name);
}
await cleanup();

if (errors.length > 0) {
  console.error('acceptance selftest failed:');
  for (const error of errors) console.error('- ' + error);
  process.exit(1);
}

console.log(JSON.stringify({
  schemaConsistency: 'ok',
  positive: positiveResults.map(({ name, status }) => ({ name, status })),
  negative: negativeResults.map(({ name, status, matched }) => ({ name, status, matched }))
}, null, 2));
