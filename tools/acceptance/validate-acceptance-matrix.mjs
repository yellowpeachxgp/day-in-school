#!/usr/bin/env node
// 静态校验 cis-acceptance-matrix/v1。无第三方依赖；只读矩阵，不执行构建或测试。

import { readFile } from 'node:fs/promises';

const matrixPath = process.argv[2] ?? 'task-notes/campus/acceptance/capability-matrix.json';
const errors = [];

function fail(message) {
  errors.push(message);
}

function isNonEmptyString(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function expectArrayOfStrings(value, allowed, label) {
  if (!Array.isArray(value)) {
    fail(`${label} 必须是数组`);
    return;
  }
  for (const entry of value) {
    if (!isNonEmptyString(entry)) fail(`${label} 含空或非字符串项`);
    if (allowed && !allowed.includes(entry)) fail(`${label} 含未登记值: ${entry}`);
  }
}

const expectedIds = [
  ...Array.from({ length: 7 }, (_, index) => `R0-${index + 1}`),
  ...Array.from({ length: 28 }, (_, index) => `C${String(index + 1).padStart(2, '0')}`)
];

let raw;
let matrix;
try {
  raw = await readFile(matrixPath, 'utf8');
  matrix = JSON.parse(raw);
} catch (error) {
  console.error(`无法读取或解析 ${matrixPath}: ${error.message}`);
  process.exit(2);
}

if (matrix.schemaVersion !== 'cis-acceptance-matrix/v1') {
  fail(`schemaVersion 必须是 cis-acceptance-matrix/v1，实际为 ${matrix.schemaVersion}`);
}
if (!isNonEmptyString(matrix.matrixId)) fail('matrixId 缺失');
if (!/^\d{4}-\d{2}-\d{2}$/.test(matrix.updatedAt ?? '')) fail('updatedAt 必须是 YYYY-MM-DD');

const evidenceTypes = matrix.evidenceTypes;
const privacyFlags = matrix.privacyFlags;
const migrationTypes = matrix.migrationTypes;
expectArrayOfStrings(evidenceTypes, null, 'evidenceTypes');
expectArrayOfStrings(privacyFlags, null, 'privacyFlags');
expectArrayOfStrings(migrationTypes, null, 'migrationTypes');

const levels = matrix.verificationRules?.verificationLevels;
expectArrayOfStrings(levels, [
  'SOURCE_CONFIRMED',
  'MOCK_TESTED',
  'DEVICE_TESTED',
  'SCHOOL_VERIFIED',
  'DISTRIBUTION_READY'
], 'verificationRules.verificationLevels');

if (!Array.isArray(matrix.items)) {
  fail('items 必须是数组');
} else {
  const seen = new Set();
  for (const item of matrix.items) {
    if (!isNonEmptyString(item.id)) {
      fail('存在缺少 id 的条目');
      continue;
    }
    if (seen.has(item.id)) fail(`重复条目: ${item.id}`);
    seen.add(item.id);

    const expectedKind = item.id.startsWith('R0-') ? 'full-regression' : 'capability';
    if (item.kind !== expectedKind) fail(`${item.id}: kind 必须是 ${expectedKind}`);
    if (!isNonEmptyString(item.title)) fail(`${item.id}: title 缺失`);
    if (!isNonEmptyString(item.acceptanceSummary)) fail(`${item.id}: acceptanceSummary 缺失`);
    expectArrayOfStrings(item.requiredEvidenceTypes, evidenceTypes, `${item.id}.requiredEvidenceTypes`);
    if ((item.requiredEvidenceTypes ?? []).length === 0) fail(`${item.id}: requiredEvidenceTypes 不能为空`);

    if (typeof item.privacy?.sensitive !== 'boolean') fail(`${item.id}: privacy.sensitive 必须是布尔值`);
    expectArrayOfStrings(item.privacy?.flags, privacyFlags, `${item.id}.privacy.flags`);
    if ((item.privacy?.flags ?? []).length > 0 && item.privacy?.sensitive !== true) {
      fail(`${item.id}: 有隐私标记时 sensitive 必须为 true`);
    }
    if ((item.privacy?.flags ?? []).length === 0 && item.privacy?.sensitive === true) {
      fail(`${item.id}: sensitive 为 true 时必须至少一个隐私标记`);
    }

    if (typeof item.migration?.involved !== 'boolean') fail(`${item.id}: migration.involved 必须是布尔值`);
    expectArrayOfStrings(item.migration?.types, migrationTypes, `${item.id}.migration.types`);
    if ((item.migration?.types ?? []).length > 0 && item.migration?.involved !== true) {
      fail(`${item.id}: 有迁移类型时 involved 必须为 true`);
    }
    if ((item.migration?.types ?? []).length === 0 && item.migration?.involved === true) {
      fail(`${item.id}: involved 为 true 时必须至少一个迁移类型`);
    }

    if (typeof item.deviceTested?.pendingUser !== 'boolean') {
      fail(`${item.id}: deviceTested.pendingUser 必须是布尔值`);
    }
    const label = item.deviceTested?.label ?? null;
    if (item.deviceTested?.pendingUser === true && label !== 'DEVICE_TESTED 待用户') {
      fail(`${item.id}: pendingUser=true 时 label 必须是 DEVICE_TESTED 待用户`);
    }
    if (item.deviceTested?.pendingUser === false && label !== null) {
      fail(`${item.id}: pendingUser=false 时 label 必须为 null`);
    }
    if (!isNonEmptyString(item.deviceTested?.rule)) fail(`${item.id}: deviceTested.rule 缺失`);
    expectArrayOfStrings(item.deviceTested?.pendingEvidenceTypes, evidenceTypes, `${item.id}.deviceTested.pendingEvidenceTypes`);
    if (item.deviceTested?.pendingUser === true && (item.deviceTested?.pendingEvidenceTypes ?? []).length === 0) {
      fail(`${item.id}: pendingUser=true 时必须列出待用户证据类型`);
    }

    if (!levels?.includes(item.maximumAutomaticVerificationLevel)) {
      fail(`${item.id}: maximumAutomaticVerificationLevel 未使用 VerificationLevel 枚举`);
    }
    if (['DEVICE_TESTED', 'SCHOOL_VERIFIED', 'DISTRIBUTION_READY'].includes(item.maximumAutomaticVerificationLevel)) {
      fail(`${item.id}: 自动证据上限不得为 DEVICE_TESTED / SCHOOL_VERIFIED / DISTRIBUTION_READY`);
    }
  }

  for (const id of expectedIds) {
    if (!seen.has(id)) fail(`缺少必需条目: ${id}`);
  }
  for (const id of seen) {
    if (!expectedIds.includes(id)) fail(`未知或越界条目: ${id}`);
  }
}

const forbiddenPatterns = [
  { name: 'IPv4 字面量', pattern: /\b(?:\d{1,3}\.){3}\d{1,3}\b/ },
  { name: '私钥头', pattern: /-----BEGIN [A-Z ]*PRIVATE KEY-----/ },
  { name: 'GitHub Token 形状', pattern: /\b(?:ghp_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/ },
  { name: 'OpenAI 风格 Key 形状', pattern: /\bsk-[A-Za-z0-9_-]{20,}\b/ }
];
for (const { name, pattern } of forbiddenPatterns) {
  if (pattern.test(raw)) fail(`矩阵含疑似敏感信息: ${name}`);
}

const forbiddenLiterals = [
  { name: '开发机 campus 路径字面量', text: ['/srv/', 'xpeach', '-dev'].join('') },
  { name: '本机绝对路径字面量', text: ['/Volumes/', 'SN ', '770'].join('') },
  { name: '开发机账号字面量', text: ['xpeach', '-dev'].join('') }
];
for (const { name, text } of forbiddenLiterals) {
  if (raw.includes(text)) fail(`矩阵含禁用字面量: ${name}`);
}

if (errors.length > 0) {
  console.error('能力矩阵校验失败：');
  for (const error of errors) console.error(`- ${error}`);
  process.exit(1);
}

console.log(`能力矩阵校验通过: ${matrixPath}（${expectedIds.length} 项）`);
