import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const cloudRoot = path.resolve(process.argv[2] || '../cloud-in-school');
const source = JSON.parse(fs.readFileSync(path.join(root, 'tools/contract-ci/cloud-fixture-source.json'), 'utf8'));
const head = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: cloudRoot, encoding: 'utf8' });
if (head.status !== 0 || head.stdout.trim() !== source.commit) throw new Error('Cloud checkout 与固定的契约源提交不一致');
for (const name of ['reservation-quote-envelope.json', 'reservation-error-envelopes.json']) {
  const cloud = fs.readFileSync(path.join(cloudRoot, 'tests/fixtures', name));
  const day = fs.readFileSync(path.join(root, 'core/campusservices/src/test/resources', name));
  if (!cloud.equals(day)) throw new Error(`跨仓库 fixture 不一致：${name}`);
}
const schema = 'contracts/schemas/reservation-quote-v1.schema.json';
if (!fs.readFileSync(path.join(root, schema)).equals(fs.readFileSync(path.join(cloudRoot, schema)))) {
  throw new Error('跨仓库报价 JSON Schema 不一致');
}
const test = spawnSync(process.execPath, ['--test', 'tests/quote-contract-shape.test.mjs'], { cwd: cloudRoot, stdio: 'inherit' });
if (test.status !== 0) throw new Error('Cloud 实际 handler 与 fixture 的契约检查失败');
console.log(`Day 已核对 Cloud ${source.commit} 的实际 handler 响应及两份共享 fixture。`);
