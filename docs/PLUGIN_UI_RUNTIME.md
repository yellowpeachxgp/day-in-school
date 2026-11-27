# 插件声明式 UI 运行时（W6-02）

> 范围：Android `:core:ui-runtime`、6 个宿主槽位、feature/settings 插件管理页。本文是开发者说明；不修改 `contracts/` 权威契约。所有示例均为合成 fixture，验证等级保持 `UNVERIFIED`，不代表真机或学校账号验收。

## 1. 安全边界

首期唯一扩展形式是 **声明式卡片 + 声明式命令 + 签名数据包**：

- 渲染器只消费 W6-01a 安装链重复验签后的 `dis-extension/v2` manifest 与产物字节。
- 不加载 Dex、不执行 JavaScript/Kotlin/脚本、不反射插件实现、不把产物当代码。
- 未知或未实现节点一律渲染为 `ErrorState` 占位，不让宿主崩溃。
- 每个槽位要求安装状态为 `ENABLED` 且已授予 `ui.slot.render`；每个命令再按声明的 `required_permission`、宿主 route 白名单和 `allowed_origins` 独立门控。

## 2. `feature-ui-v1` 首批节点

`:core:ui-runtime` 实现 `Text / Metric / KeyValue / Badge / ActionButton / List / EmptyState / ErrorState`。`Field / Form / Chart` 仍属保留节点，本阶段按 `UnsupportedNode` 降级；未知节点同样降级。

显式卡片产物按以下顺序查找（`{component}` 为规范化 component id）：

1. `cards/{component}.json`
2. `cards/{component}.feature-ui.json`
3. `ui/{component}.json`
4. `assets/{component}.json`
5. `assets/card-data.json`（兼容读取；若不是有效 feature-ui 卡片则回退到 manifest 声明，而不是误判失败）

manifest 的 `card_id` 允许点号，feature-ui 的 `component_id` 只允许小写字母、数字、连字符。运行时确定性地把其他字符折叠为 `-`，例如 `hello.card -> hello-card`。没有显式卡片产物时，宿主从**已验签 manifest** 的 `title/subtitle/action_command` 合成最小声明式卡片；这不是手填数据，也不是未验签输入。

## 3. 六个宿主槽位

| 槽位 | 宿主位置 | 数量上限 | 节点上限 | 高度约束 |
|---|---|---:|---:|---:|
| `home.cards` | Today 首页卡片区 | 2 | 8 | 220dp |
| `campus.services` | 学校中心校园服务卡后 | 4 | 6 | 180dp |
| `library.actions` | 图书馆选座操作区顶部 | 3 | 5 | 160dp |
| `parcel.cards` | 快递主页包裹列表前 | 2 | 6 | 180dp |
| `parcel.providers` | 快递来源/手机号绑定区 | 4 | 5 | 160dp |
| `settings.entries` | 设置「云账号与扩展」入口区 | 3 | 4 | 150dp |

每个槽位统一支持「有插件 / 无插件 / 插件停用 / 插件出错 / 缺少权限」占位；无插件状态在生产宿主默认隐藏，在 fixtures 与截图中显式展示。超出数量的贡献不渲染并显示计数说明。

## 4. 声明式命令

只允许三类命令：

- `open_url`：必须是 HTTPS、已授予 `network.request`，且 origin 精确属于 manifest `allowed_origins`。
- `open_host_route`：必须精确属于**当前宿主屏幕显式传入**的白名单；没有真实导航回调的 route（包括当前页空操作）一律禁用，不允许按钮看似可用却无响应。
- `show_data`：只允许安全相对路径，读取同版本已验签 artifact；不做脚本求值。

未知类型、未声明命令、重复命令、空白权限声明、撤权后的命令、越出 origin/route/path 白名单的目标全部 fail-closed。`show_data` 在真正读取前会再次核验安装状态、签名/摘要和当前授权；命令响应不回显请求 payload，也不伪装成插件代码已经执行。

## 5. 插件管理页

管理页嵌入 feature/settings 的「云账号与扩展」扩展页，遵循 Route / State / UI / Preview fixtures 与组合作用域 `ViewModelStoreOwner + session key` 模式。生命周期写操作直接使用 `ExtensionPluginRuntimeRegistry` 提供的 W6-01a 共享安装器实例；**没有新增 `CampusRepository` facade**。

页面展示：发布者、来源格式、版本、重复验签状态、声明/已授权限、数据占用、槽位数量、最近错误；支持逐项撤权、启用/停用、升级权限差异确认、回滚、卸载。旧的手填/未验证遗留扩展不渲染，只保留移除入口。

## 6. 测试与截图证据

- `FeatureUiCardCodecTest`：节点解析、边界拒绝、未知节点降级、component id 规范化。
- `DeclarativeCommandGateTest`：撤权、未知类型、origin/route/path 拒绝。
- `ExtensionUiPackageLoaderTest`：合成签名包安装后加载，停用/撤权/非法模型/缺失卡片产物降级。
- `PluginManagementScreenTest`：发布者与权限可见，逐项撤权、停用、升级确认、回滚、卸载动作 wiring。
- Roborazzi：首批节点浅色/深色/200% 字号，6 槽位 × 4 状态，插件管理页 smoke。golden 必须由 devbox Linux 录制。

常用门禁（日志路径在汇总中写 `<campus-root>/...`）：

    ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w6-02-ui-runtime quick :core:ui-runtime :feature:settings :feature:today
    ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w6-02-ui-runtime android
    ssh "$CIS_DEVBOX" "$CIS_CAMPUS_ROOT/bin/cis-gate" day-in-school feat/cis-w6-02-ui-runtime secrets
