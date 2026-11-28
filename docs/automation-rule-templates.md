# 自动化规则模板与天气 Provider（W2-03）

> 能力编号：C08。本文是开发者说明，不表示真实学校或真实天气准确率已经验证；当前证据等级仅限 JVM/Robolectric 夹具测试。

## 1. 首期内置模板

| 模板 | 触发器 | 关键条件 | 动作 | 诚实边界 |
|---|---|---|---|---|
| 早八带伞 | 工作日 07:20（模板默认值，可在编辑器调整） | 今天有课 + 未来 180 分钟降水证据 | 本地通知、刷新首页 | 天气条款未确认、无校区粗坐标、网络/解析失败、缓存过期或降水字段缺失时 UNKNOWN，不触发 |
| 跨校区通勤提醒 | 课程/实践课前 60 分钟 | 通勤估计在 1..120 分钟 | 本地通知、刷新首页 | 当前没有可信跨校区路线证据时 UNKNOWN；模板默认关闭 |
| 水电低余额周五提醒 | 周五 17:30 | 星期五 + 最新电表余额 < 20 度且读数不超过 7 天 | 本地通知、刷新首页 | 只读本地采样，不触发充值或任何外部写操作；水表可在编辑器切换 |
| 临时停课推荐自习 | course.changed 且 change=UPDATED | CourseChanged.diff 的结构化字段与精确枚举明确表达取消语义 | 本地通知、创建本地自习日程 | 普通调课、复课/否定值、混合或未知枚举、缺少事件上下文时不触发 |

四个模板都由 BuiltinAutomationTemplates 生成，全部 enabled=false、templateId 固定。UI「使用模板」只载入草稿；保存和启用都是用户显式动作。

## 2. WeatherProvider SPI

- SPI 位于 core/automation/WeatherProviderSpi.kt：WeatherProvider、WeatherForecastRequest、WeatherSnapshot、WeatherProviderResult 与 WeatherLocationPort。
- 默认实现为 R03 推荐的 MetNorwayWeatherProvider（Locationforecast 2.0 compact，免 Key），输出强制携带 attributionLines、license/terms URL、fetchedAt、cacheExpiresAt 与 DataProvenance。
- MET Norway 请求必须满足：用户在该规则中显式确认条款；只使用校区级参考坐标；坐标截断到 4 位小数；HTTPS、禁重定向、响应大小受限；同一坐标相同请求至少间隔 3 小时。
- BYOK 只由 SPI 的 USER_KEY_REQUIRED 能力形态预留。任何未来天气 Key 都必须走独立加密存储，绝不能进入自动化 config、evidence、日志、备份或云端。
- Open-Meteo 的商业属性仍未确认，本卡不把它作为默认源；QWeather/OpenWeather BYOK 实现也不在本卡范围内。

## 3. 自动化安全边界

天气是不可信外部输入，只能作为只读条件证据：

1. WeatherProviderResult.Unavailable、快照过期、位置缺失、降水字段缺失一律映射为 Certainty.UNKNOWN。
2. 引擎既有规则仍是 UNKNOWN == 不通过，运行记录必须写明原因；试运行/运行记录展示 provider、获取时间、有效/缓存时间与署名。
3. 天气 Provider 不注册、也不能注册 EXTERNAL_WRITE_FORBIDDEN 动作；自动化动作仍仅限本地通知、本地日程/待办、本地刷新。
4. 文件缓存按 Profile 散列隔离，文件名和内容索引不写账号、校区或坐标明文；损坏缓存 fail-closed，不能绕过频率约束。

## 4. Android 接线与 UI

- AutomationRuntime 注入 MetNorwayWeatherProvider、RepositoryWeatherLocationPort 与 RoomUtilityBalanceReadPort。
- 校区坐标优先使用用户自定义校区中心；否则使用既有校区级粗围栏质心。没有证据时返回 null，不猜测位置。
- feature/automation 按 §10.2 拆为 Route、ViewModel、state/、ui/、preview/。规则列表提供模板入口，编辑器提供天气条款确认、降水窗口/概率、水电余额和停课证据开关。
- PreviewFixtures 覆盖 11 态：Loading、Empty、Content、Partial、Stale、Error(retryable/non-retryable)、NeedsPermission、NeedsLogin、Offline、Unsupported。

## 5. 已知限制与待决

1. MET Norway 条款包含「AI agents」相关条款；有权维护者的人类阅读/接受记录仍是待决事项。实现侧已把每规则条款确认默认关闭。
2. 跨校区通勤估计端口当前证据不足，模板不会因为猜测而触发；需要后续路网/通勤矩阵能力。
3. 临时停课模板依赖 CourseChanged 生产者提供明确取消字段差异；当前测试使用合成事件，不宣称真实教务停课链路已验证。
4. 中国本地天气准确率未验证，MET Norway 对北欧以外数据更新和精细度较低；不得在 UI 或汇总中宣称准确率。
