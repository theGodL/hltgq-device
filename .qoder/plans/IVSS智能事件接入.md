# IVSS 智能事件接入计划

## 背景
大华已配置好 IVSS 智能分析事件，通过 ICC 事件订阅接口推送（文档：open-icc enterprisebase/5.0.17 wiki/evo-event/subscribe.html）。本服务已具备完整的视频告警闭环（VideoAlertService/WorkOrderService/站点设备 code 匹配），只需新增事件接收与映射层。

## 核心设计决策
1. **接入方式**：订阅回调为主（实时秒级）+ alarm-record/page 轮询兜底（挂入现有 30 分钟轮巡，补偿网络抖动丢失的事件，按 alarmCode 幂等）。
2. **订阅范围**：types 留空（订阅全部 alarm）+ eventType=2（只要报警）+ orgs 配置化（默认订阅灌区根组织，未来 ICC 新增视频设备自动纳入，无需重新订阅）；处理侧按 nodeCode 匹配站点表（devicecode + epjutj LIKE '%#5#%'）过滤，非视频子系统事件直接丢弃。
3. **事件映射**：不需要提前拿到 alarmType 码表——官方文档明确类型码随现场平台动态生成；直接用事件体 alarmTypeName（大华给的中文名）生成告警 content，另提供配置化映射表（alarmType → 自定义名称/级别）供后续按需覆盖。
4. **去重与恢复**：复用现有 content 三字段去重（同通道同类型事件重复推送天然幂等）+ closeByContent 恢复关警/关工单（alarmStat=0 → 关闭）；不新增数据库列。
5. **防抖**：智能事件自带 alarmStat 发生/恢复配对，不做防抖，收到即落库。
6. **级别映射**：alarmGrade 1~4 → level #1#~#4#（1:1，配置化可调，现场核对大华 grade 语义后调整）。
7. **命名防撞**：智能事件告警 content 用「{站点名}-视频智能事件 {事件名}！」前缀，与现有图像故障「{站点名}-视频 {故障名}！」隔离，避免与「视频遮挡」等同名故障互串去重。

## 变更内容

### 1. 配置（application.properties + DahuaConfig.java）
- `icc.sdk.event.enabled`（默认 false，联调通过后打开）
- `icc.sdk.event.callback-url`（ICC 服务器可达的回调地址，如 http://内网IP:8080/api/dahua/event/receive）
- `icc.sdk.event.orgs`（订阅组织码，逗号分隔；留空=订阅全部，靠处理侧过滤）
- `icc.sdk.event.type-mapping`（可选：alarmType=名称,级别 覆盖映射）
- DahuaConfig 新增对应字段与 getter/setter

### 2. 新增 DahuaEventSubscribeService（service 包）
- @PostConstruct 启动时调用 `POST /evo-apigw/evo-event/1.0.0/subscribe/mqinfo`（复用 DahuaAuthService.getOauthConfig() + SDK HttpUtils.executeJson，与现有 DahuaPtzService 同模式）
- 订阅体：monitor=callback-url、monitorType=url、magic=IP_端口（从 callback-url 解析）、subsystem.name/magic 同值、events=[{category=alarm, eventType=2, subscribeAll=1}]
- 失败重试：每小时整点重试（复用现有 @Scheduled 轮巡节拍），订阅接口幂等（同 name/magic 覆盖）
- 可选：查询订阅列表 `GET /evo-apigw/evo-event/1.0.0/subscribe/subscribe-list?monitorType=url&category=alarm` 验证订阅状态，日志输出

### 3. 新增 DahuaEventCallbackController（controller 包）
- `POST /api/dahua/event/receive`：接收事件 JSON（无鉴权或 URL 固定参数，文档要求），快速返回 {code:"0",message:"成功"}
- 幂等保护：同一 uuid 已处理过直接跳过（内存近期 uuid 集合，防平台重推）
- 异步处理：接收后丢给线程池处理，不阻塞回调响应

### 4. 新增 VideoEventService（videoalert 包）
- 解析事件：category=alarm && method=alarm.msg；提取 nodeCode/channelName/alarmType/alarmTypeName/alarmGrade/alarmStat/alarmCode
- 站点解析：nodeCode → 站点表 devicecode + epjutj LIKE '%#5#%'（与 DeviceTableService type 限定同思路，防同 devicecode 的闸门/水质站点误命中）；未匹配 → 丢弃并 debug 日志
- 设备解析：复用 DeviceTableService.lookupOrCreateDevice（code 匹配）
- alarmStat=1：VideoAlertService 新增公开方法 insertEventAlert(siteId, deviceId, content, level)（提取现有 insertAlert 私有逻辑的通用入口，故障检测与智能事件共用）→ 自动联动工单
- alarmStat=0：复用 VideoAlertService.closeByContent + WorkOrderService.closeByContent
- 级别：alarmGrade → level 映射（配置化覆盖）

### 5. 轮询兜底（VideoAlertScheduler）
- 每轮追加：`POST /evo-apigw/evo-event/1.2.0/alarm-record/page`（时间窗=距上次成功拉取，含 handleStartDateString 等）拉取遗漏事件，与回调共用 VideoEventService 处理逻辑（alarmCode 幂等去重）

### 6. 测试（Mockito 纯单测，风格对齐现有 49+ 用例）
- DahuaEventServiceTest：事件解析、alarmStat=1 落告警+工单、alarmStat=0 关警+关工单、非视频 nodeCode 丢弃、重复 uuid 幂等、级别映射
- DahuaEventSubscribeServiceTest：订阅请求体字段正确性（monitor/magic/category/eventType）、失败重试
- 全量回归跑绿

### 7. 文档（告警方案.md）
- 新增「IVSS 智能事件接入」章节：订阅/回调/轮询兜底架构、事件映射规则、级别映射、与自研 17 类故障检测的关系（并行互补：智能事件=行为识别，自研=图像质量/链路）

## 前置条件（需用户/大华侧确认，联调前）
1. ICC 服务器能访问回调地址（文档强调必须 telnet 通；需网络放行到本服务端口）
2. 平台版本：订阅 V5.0.6+、订阅列表查询 V5.0.11+（客户 5.0.17 满足）
3. 订阅组织范围：确认灌区根组织 code（或先留空订阅全部，靠处理侧过滤）
4. alarmGrade 语义核对（1 一般~4 特别严重 的对应关系）

## 验证方式
- 部署后看订阅日志（订阅成功 + 收到事件日志）
- 大华侧触发一次测试事件 → 数据库 t_auto_hltgq_water_alert 出现「{站点}-视频智能事件 {事件名}！」、工单联动生成；事件恢复 → 告警/工单自动关闭
- 非视频子系统事件不落库