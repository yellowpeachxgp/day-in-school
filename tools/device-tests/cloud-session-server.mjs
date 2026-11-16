import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
if (!process.argv[2]) throw new Error('请指定隔离 Cloud 源码目录');
const { createCampusCloudApp } = await import(pathToFileURL(path.resolve(process.argv[2], 'apps/api/src/app.mjs')));
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'android-cloud-chain-'));
const forbidden = () => { throw new Error('隔离验收禁止学校写入或真实资金'); };
const app = createCampusCloudApp({ requireAdminAccess: false,
  financeBridgeOptions: { realDebitsEnabled: false, allowSandboxFallback: true },
  storeOptions: { snapshotPath: path.join(dir, 'state.json'), requireDurableSnapshot: true },
  schoolProviderOptions: { mockBackend: { handleCreateReservation: forbidden, handleVerifyReservation: forbidden, handleCancelReservation: forbidden } }
});
// 不启动扫描器、调度器或资金 worker；所有数据和资金均为沙箱。
const user = app.store.findOrCreateCloudUser({ issuer: 'https://test.invalid', subject: 'android-isolated', displayName: '隔离安卓用户' });
app.financeBridge.setSandboxBalance(user.id, 50000);
const session = app.identity.issueDeviceSession(user, '隔离安卓测试', null, 1, Date.now() - 5 * 3600000, 'MOBILE_APP');
let refreshCalls = 0;
const server = http.createServer(async (req, res) => {
  res.setHeader('content-type', 'application/json');
  if (req.url === '/fixture' && req.method === 'GET') { res.end(JSON.stringify({ ...session, issuer: user.issuer, subject: user.subject })); return; }
  if (req.url === '/evidence' && req.method === 'GET') {
    res.end(JSON.stringify({ refresh_calls: refreshCalls, plans: app.store.reservationPlans.size, jobs: app.store.reservationJobs.size,
      target_date: [...app.store.reservationPlans.values()][0]?.target_local_date, orders: app.store.reservationOrders.size, finance_operations: app.store.financeOperations.size })); return;
  }
  if (req.url === '/api/v1/auth/refresh') refreshCalls++;
  let raw = ''; for await (const bytes of req) raw += bytes;
  try {
    const r = await app.inject({ method: req.method, url: req.url, headers: req.headers, body: raw ? JSON.parse(raw) : undefined });
    res.writeHead(r.status); res.end(JSON.stringify(r.body));
  } catch { res.writeHead(500); res.end('{"ok":false,"message":"隔离测试失败"}'); }
});
server.listen(18190, '127.0.0.1', () => process.stdout.write('隔离 Cloud 已就绪，仅本机 18190，无学校或资金 worker\n'));
process.on('SIGTERM', () => { server.close(); clearTimeout(app.store._snapshotFlushTimer); fs.rmSync(dir, { recursive: true, force: true }); process.exit(0); });
