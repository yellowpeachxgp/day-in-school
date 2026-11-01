# 校园服务模块安全与隐私审查报告 (Campus Services Security Review)

本文档记录「日在校园」在引入宿舍水电、图书馆馆藏检索、图书馆座位预约、自习专注计时及 `univ-timetable/v1` 课表互通时的安全威胁建模、关键修复项与红线合规自检结果。

---

## 一、核心安全升级与上游缺陷修复对比

| 审计域 | 上游 `HAUTPocket` 原始实现 | 本项目 (`day-in-school`) 安全加固方案 | 风险消除等级 |
|---|---|---|---|
| **1. 水电接口参数传输 (CWE-598)** | `WanxiaoClient.call()` 将包含 `account`（学号）与 `roomverify`（房间凭据）的 `param` 拼接到 `URL("$API?$params")` 查询字符串中 | `WanxiaoFormPostTransport` 强制校验纯 HTTPS 端点不含 `?`，所有参数经 UTF-8 `application/x-www-form-urlencoded` 编码写入 `POST` 请求体；异常信息脱敏擦除 | **高危修复**（杜绝 URL 日志泄露学号与房间凭据） |
| **2. 水电传输未验证门控** | 直接向 `xqh5.17wanxiao.com` 发起在线请求 | 默认处于 `TRANSPORT_UNVERIFIED` 门控状态，界面显式标注「待实机核验」，须经用户知情开启后才允许发起在线请求；未开启时后台采样 Worker 自动跳过网络请求 | **中危加固**（防止未验证接口静默发包） |
| **3. 房间查询凭据 (`roomverify`) 落盘** | 仅内存使用，但账号直接明文回填 UI 输入框 | `roomverify` 严禁写入 Room 或 DataStore；本地仅持久化 `SHA-256(roomverify)` 作为稳定 `roomId`；UI 默认展示脱敏学号（如 `2023****1012`） | **中危加固**（最小化敏感凭据留存） |
| **4. Keystore 密钥失效恢复** | `AccountVault.read()` 遇到密钥失效仅返回 `null`，未清理损坏的 Keystore 条目与残留密文 | `UtilityAccountSecureStore` 捕获 `KeyPermanentlyInvalidatedException` / `AEADBadTagException` 后自动删除损坏别名与密文并重建密钥，返回 `InvalidatedAndReset` | **中危修复**（避免锁屏变更后永久无法保存） |
| **5. WebView 域名校验与子框架安全** | 分别在多处用字符串判断 host | `OfficialServiceRoutePolicy` 统一通过 `java.net.URI` 严格校验 `scheme == "https"`、`userInfo == null`、`port in {-1, 443}` 及精确主机白名单；禁止 `addJavascriptInterface`、禁止文件与 Content 访问、SSL 错误一律 `cancel()` | **高危加固**（防 SSRF、开放重定向与 JS 桥攻击） |
| **6. 馆藏封面加载隐私隔离** | 校验封面域名并在无凭据连接加载 | `OfficialServiceRoutePolicy.sanitizeCoverUrl` 仅放行 `libspace.haut.edu.cn`、`img.hwlsp.cn`、`*.ddimg.cn` 的纯路径 HTTPS 链接，剥离全部 Query/Fragment/UserInfo，且使用零 Cookie 独立连接加载 | **低危加固**（防封面 URL 追踪与 Cookie 泄露） |
| **7. 课表导出隐私脱敏** | 导出课表不包含学号与水电数据 | `TimetableInterchangeAdapter` 仅读取 `Term`、`PeriodTime`、`Course`、`CourseScheduleRule`，Markdown 元信息表「学生」「学号」「教务系统地址」固定输出 `-` | **隐私合规**（课表分享零个人身份泄露） |

---

## 二、安全红线逐项核查表

1. **禁止遍历或代查他人房间**：
   - `WanxiaoProtocolParser` 与 `WanxiaoUtilityClient` 仅提供 `getbindroom`（查询当前学号在完美校园已绑定的本人宿舍）与 `h5_getstuindexpage`（读取上述已绑房间表计），不包含任何楼栋/房间号枚举接口。
2. **禁止抓取或持久化网页密码与选座令牌**：
   - `SchoolWebSessionManager`、`OfficialLibraryWebAdapter` 与 `LibrarySeatBookingScreen` 均不向登录表单注入读取密码的脚本，不拦截或导出 `CookieManager` 中的会话令牌，不实现自动抢座或后台代约。
3. **禁止破坏性数据库迁移**：
   - `HautDatabase` 从 v3 升级至 v4 通过显式 `MIGRATION_3_4` 创建 `utility_daily_samples`、`utility_online_rooms`、`utility_meters`、`utility_readings` 与 `focus_sessions` 五张表及索引，全程未启用 `fallbackToDestructiveMigration()`。
4. **一键抹除个人数据 (`wipeAllPersonalData`) 全覆盖**：
   - 执行「设置 -> 抹除所有个人数据」时，通过 `CampusRepository` 预清理钩子与事务依次完成：
     1. 取消全部课程/考试提醒闹钟与通知（`:core:reminder`）；
     2. 取消全部自习专注闹钟、清空专注通知与送达诊断记录（`FocusCoordinator`）；
     3. 取消 `WorkManager` 周期水电采样任务（`dayinschool.utility.sampling.v1`）；
     4. 销毁 Android Keystore 水电账号密钥与加密 SharedPreferences（`UtilityAccountSecureStore.wipeAll()`）；
     5. 清空 `SchoolWebSessionManager` 管理的全部 WebView Cookie 与 DOM Storage；
     6. 清空 Room 数据库中的课表、成绩、考试、学生档案、水电采样、在线房间缓存、手工水电台账与专注会话记录。
