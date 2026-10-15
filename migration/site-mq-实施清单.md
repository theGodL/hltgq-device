# site / mq 配套实施清单（迁移与改造联动）

> 依据：《站点-设备数据架构改造评审说明.md》9.2（site 行动）/ 9.3（mq 行动）/ 10.4-10.6（313 口径对齐与复审）。
> **结论先行：site 与 mq 均无新增代码改造**——
> - site 侧：7.4 复核确认"无代码改造"（9.2-⑤），本清单给出**迁移后回归验证 SQL 与核对项**；
> - mq 侧：会签 10.1 全部无否决；10.5 两处加固（失联告警 device 排除视频设备、采集站判定排除视频设备）**已实施并上线（2026-09-23）**，无新增改造；本清单给出**联合回归数字预期与知悉项**。

---

## 一、hltgq-site（需执行 / 知悉）

### 1. 代码改造项

**无。** 改造前后接口结构不变：
- 站点档案列表 / 站点详情 `videos` 继续按 `site` 查设备表（1:1 → 1:N 由数据侧完成，接口天然兼容）；
- 数据统计页「视频数据」行链路不变：site 网关 `/data-statistics/video-collect` → device `/api/dahua/video/patrol-stats`（计数口径连续、无数字变化，见 11.4-3）。

### 2. 迁移后回归验证（7.4 / 9.2-④；下列 SQL 均为只读，可直接执行）

| # | 回归项 | 方法 | 期望 |
|---|---|---|---|
| S1 | 站点档案列表（旧行可见性） | 列表页 + SQL ① | 368 行保留、旧行 175 行仍可见（过渡期不过滤；过滤口径见下节跟进项） |
| S2 | 站点详情 `videos`（多设备/运行状态） | 详情页抽查 + SQL ②③ | 多设备站 27 个（合计 80 台）；双庙湖 2 站各 3 台（灰度抽检） |
| S3 | 数据统计页「视频数据」行 | 页面 + 接口 | expected 基数 = 175（与现状一致） |
| S4 | 数据统计页站点全集 | 页面（数字以 mq 出参为准） | 见「二、3」联合回归数字预期 |
| S5 | 大屏站点与设备统计 | 大屏 + SQL ① | 站点 368、设备 357（视频 175） |

```sql
-- ① 站点总行数 / 旧行保留（迁移不删除任何行）
SELECT count(*) AS site_total FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef;              -- 368
SELECT count(*) AS retired_rows FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#';                                                                     -- 175（旧行保留）

-- ② 多设备站明细（站点详情 videos 兼容性回归：27 站 ≥2 台）
SELECT s.id, s.zzkaec AS site_name, count(*) AS video_devices
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
JOIN "qixiao-apaas".t_auto_hltgq_water_device d ON d.site = s.id AND d.type LIKE '%#5#%'
GROUP BY s.id, s.zzkaec HAVING count(*) >= 2 ORDER BY count(*) DESC;
-- 【期望】27 站 ≥2 台（合计 80 台；11.3-A 1:N 形态）

-- ③ 灰度抽检（双庙湖 2 站，迁移后立即验证）
SELECT s.zzkaec AS site_name, count(*) AS video_devices
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
JOIN "qixiao-apaas".t_auto_hltgq_water_device d ON d.site = s.id AND d.type LIKE '%#5#%'
WHERE s.id IN ('CYXjx0vlVQlWPVAZTEf', 'mqG9dLawRteYWHgjqFG')
GROUP BY s.zzkaec;
-- 【期望】双庙湖管理所 3 台、双庙湖节制闸 3 台

-- ④ 数据统计「视频数据」行基数核对（device 侧口径，与 S3 配套）
SELECT count(*) AS patrol_channels FROM
  (SELECT code FROM "qixiao-apaas".t_auto_hltgq_water_device
   WHERE type LIKE '%#5#%' AND COALESCE(code, '') <> '' GROUP BY code) t;                  -- 175
```

### 3. 后续跟进项（不在本次迁移窗口）

1. **旧行数据侧过滤（8.2-3，待 site / 业务方定案）**：退役行动态条件为
   `epjutj = '#5#' AND NOT EXISTS(设备表 type 含 #5# 且 site = 该行)`（迁移前预检 0 命中、迁移后 175 命中）；
   档案页过渡期可先按 `dry-run/dryrun-sites.csv` 名单比对；定案后由 site/产品决定列表过滤与展示策略；
2. **口径知会（9.2-⑤）**：`stationTotal`、`videoSite*` 迁移后构成变化（见二、3），`video-collect` 无变化——
   请 site 知会产品 / 前端；到报 / 缺测口径调整由 mq 统一实现（9.3-3），site / device 不另起口径；
3. **B3 / B4 裁决联动**：旧行处置（标离线保留 + 动态条件标识，device 建议）与 `#5#` 摘除规则依赖业务方拍板；
   裁决后涉及的档案页可见性 / 类型展示调整服从产品 / 前端结论（7.3-d / 7.5）。

---

## 二、hltgq-mq（需执行 / 知悉）

### 1. 代码改造项

**无新增。** 会签 10.1 全部无否决（M1/M2 兼容、M3/D7 同意、D2 无异议）；
10.5 两处 1 行级加固已上线（2026-09-23）：①失联告警 `device_id` 排除视频设备；②采集站判定 `inCollect` 排除视频设备。
迁移范围与 mq 数据零交叉（告警 / 工单 content 模式实测 0 命中，10.6-②）。

### 2. 迁移前知悉

- 迁移窗口（11.5.3）：**停 device 旧同步服务 → 快照 → 灰度 6 台 → 全量 → 启动 device 新代码**；
  窗口内 mq 侧只读、无影响；建议与阶段 0 dry-run 核对同批协调（不得晚于阶段 1，10.6 建议）；
- 灰度 6 台 = 双庙湖管理所 3 台 + 双庙湖节制闸 3 台（名单见 `migration/README.md`）。

### 3. 联合回归数字预期（迁移 + device 新代码部署后；10.6-④ / 10.4-③）

| 指标 | 迁移前（09-23 基线） | 迁移后预期 | 构成说明 |
|---|---|---|---|
| `stationTotal` | 314 = 139 + 175 | **151 + 兜底站** | 175 旧行掉出"有设备站"；12 站新入（按"无采集站"记） |
| `videoSiteTotal/Online/Offline` | 175（= 全部视频行） | **12 + 兜底站** | "仅视频设备业务站"（122 目标站 − 110 已挂非视频设备） |
| `noReportSites/missedSites` | 稳定 | 稳定（前提：10.5 加固已上线 ✓） | — |
| 采集站 | 139 | **139 不变** | 110 个已挂非视频设备的目标站本就在集合内，增挂视频设备不改变其采集判定 |

- "12 站" = 10 个"有站码、无非视频设备"站 + 2 个 `#6#` 站（虎型渡槽、南山寺闸），名单见 11.4-6 / 10.6-②；
- "兜底站" = device 侧匹配失败时的 S4 兜底站（无采集要素、按"无采集站"口径自动纳入，mq 无需特殊逻辑）；
  联合回归时如出现兜底站，请与 device 侧 `migration/` 日志"待挂靠"清单交集核对。

### 4. 待办与知悉项

1. **版本锚定（待贴、不阻塞）**：迁移前以容器哈希闭环"已上线"结论——
   `docker exec hltgq-mq sha256sum /app/app.jar`，期望 `2D233B…BF0F743`（11.6-⑤版本锚定提醒）；
2. **联合回归（一次性）**：迁移 + device 新代码部署后，三方同批核对数据统计页与上表数字（7.3-e 闭合项）；
3. **口径一致性**：改造联动引起的到报 / 缺测口径调整由 mq 统一实现（9.3-3）；「视频数据」行归口维持现状
   （site 网关 → device `/patrol-stats`，mq 无链路）。
