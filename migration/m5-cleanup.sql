-- ============================================================
-- 站点-设备架构改造 · 退役数据清理（m5）
-- ============================================================
-- 性质：写操作（DELETE）。不属迁移窗口，属迁移完成后的独立清理动作，
--       依据 2026-09-24 业务方决策：退役旧行由"标离线保留"变更为"物理删除"。
--       原因：旧行会展示在 site 档案页 / 大屏 / 外部应用等本侧无法过滤的页面上。
--
-- 影响面核查（2026-09-24 只读实测：20 张活跃业务表 + 3 张外部应用表全扫）：
--   175 退役旧行（epjutj = '#5#'）被引用的全集：
--     - water_work_order           8 条（测试工单，第 4 节名单）
--     - knc3g_bpzjoh              19 条（外部应用隐患记录，9 月测试期数据，第 2 节）
--     - water_inspection_record    2 条（巡查记录，第 3 节）
--     - water_device / water_alert / 其余活跃表 = 0
--   其他清理项（与旧行无引用关系，但同属测试/悬空数据）：
--     - 悬空告警 5 + 异常告警 1（第 5 节）、异常引用工单 12（第 6 节，含 1 条待核）
--     - 僵尸设备 3 台（第 8 节，业务方裁决后启用）
--
-- 执行前提（全部满足才执行）：
--   1. 业务方确认本清理的范围与口径（第 2/4/5/6 节涉及外部应用数据与测试数据）；
--   2. knc3g 侧确认第 2 节 —— 若不清理其 19 条引用行，旧行删除后
--      其隐患记录页面将显示"站点缺失"；
--   3. 第 0 节备份已导出并归档 —— 备份在手时，本清理任何步骤均可人工恢复
--      （按导出文件逐字段 INSERT，id 保持原值）；
--   4. 观察期观察通过；回退窗口由决策方关闭 —— 本清理执行后，
--      m4-rollback.sql 对该批旧行不再适用（恢复改由备份文件人工执行）。
--
-- 执行方式：标准 SQL 客户端单条顺序执行，各节先跑校验、后执行删除、再跑核对；
--           行数不符即停。全部语句幂等，可重跑（重跑命中 0 行）。
-- 执行顺序：2 → 3 → 4 → 5 → 6 → 7 →（8 可选）→ 9 核对
-- 本文件未经执行验证（只读通道不具写权限），实际行数以执行时点实测为准。
-- ============================================================


-- ============================================================
-- 第 0 节 备份导出（只读；执行当日重导一次并与 2026-09-24 首导比对）
-- 首导留档：hltgq-dbcheck/retired-rows-list.out.txt（175 行全字段）
-- ============================================================

-- 0.1 旧行全字段（恢复主依据）
SELECT id, zzkaec AS site_name, zebpsu, devicecode, ahieto AS target_site,
       COALESCE(iofhpi, '') AS iofhpi,
       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
       to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
       COALESCE(created_by, '') AS created_by, COALESCE(updated_by, '') AS updated_by
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#'
ORDER BY id;

-- 0.2 全部待删对象导出（恢复用，逐个归档）
SELECT * FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh
WHERE site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE epjutj = '#5#');

SELECT * FROM "qixiao-apaas".t_auto_hltgq_water_inspection_record
WHERE id IN ('7kkVJ7zRRAxn6TGMQqi', 'Cvuc24Jqvxpxjo7eVMX');

SELECT * FROM "qixiao-apaas".t_auto_hltgq_water_work_order
WHERE id IN ('RCrbBJtuBBw9RFhdNG8', 'V3ojbJ1Zk6VrFzmCRWo', 'DM7tO3VPTpolqfGRqZk', '4ltUSjH23K0HVEz8DG3',
             '3wdgE0zoZj4hIlMBvAg', 'BFRhKpXaOxprzEc8ATw', 'eD1U1Ws6IscXik2QViU', 'M9NL6kxh6dPpV1Ikir4',
             'TYlouExfT8tMrM71Ajr', 'WjFVgmMAXfuoC5iqS41', 'dPFeu1Jp8F2fRcYi99J', 'Tf8q3qS3UnpxhCjaFNu',
             'czDerhwlmy2y0oMrqNt', 'VT1ZxAMVNzB0K1ck4Zi', 'V9k4gkVNGgEu5xtAFjx', 'Zg2fyP3FrfNLgmztvO4z',
             'IOmd0eMKpuimRgJYqTx', 'pPG34Ap6EfLXnR3NEyCJ', 'Ya5EyKsPAaakA1kPQiiX');

SELECT * FROM "qixiao-apaas".t_auto_hltgq_water_alert
WHERE id IN ('RM2MorNsdCXwYlWDPGo', 'jCH1mfgKdDi5EFeDXdK', 'E9snpLblYUoyEjtT1cv', 'evtnjQBk6vgUchljP6Wr',
             'wIvYUUxbf5YCH5jWAqEg', 'ONhlddR5c8jYMou6hJtP');


-- ============================================================
-- 第 1 节 前置校验（只读；逐条与期望对照，不符即停）
-- ============================================================

-- 1.1 旧行数（期望 175）
SELECT count(*) AS retired_rows FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#';

-- 1.2 旧行引用全景（期望：8 / 19 / 2 / 0 / 0）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
   WHERE w.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE epjutj = '#5#')) AS wo_refs,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh b
   WHERE b.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE epjutj = '#5#')) AS bpzjoh_refs,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_inspection_record r
   WHERE r.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE epjutj = '#5#')) AS insp_refs,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
   WHERE d.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE epjutj = '#5#')) AS dev_refs,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert a
   WHERE a.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE epjutj = '#5#')) AS alert_refs;

-- 1.3 待删测试数据在册（期望：工单 19 / 告警 6 / 巡检 2）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order w WHERE w.id IN (
     'RCrbBJtuBBw9RFhdNG8','V3ojbJ1Zk6VrFzmCRWo','DM7tO3VPTpolqfGRqZk','4ltUSjH23K0HVEz8DG3',
     '3wdgE0zoZj4hIlMBvAg','BFRhKpXaOxprzEc8ATw','eD1U1Ws6IscXik2QViU','M9NL6kxh6dPpV1Ikir4',
     'TYlouExfT8tMrM71Ajr','WjFVgmMAXfuoC5iqS41','dPFeu1Jp8F2fRcYi99J','Tf8q3qS3UnpxhCjaFNu',
     'czDerhwlmy2y0oMrqNt','VT1ZxAMVNzB0K1ck4Zi','V9k4gkVNGgEu5xtAFjx','Zg2fyP3FrfNLgmztvO4z',
     'IOmd0eMKpuimRgJYqTx','pPG34Ap6EfLXnR3NEyCJ','Ya5EyKsPAaakA1kPQiiX')) AS wo_in_list,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert a WHERE a.id IN (
     'RM2MorNsdCXwYlWDPGo','jCH1mfgKdDi5EFeDXdK','E9snpLblYUoyEjtT1cv','evtnjQBk6vgUchljP6Wr',
     'wIvYUUxbf5YCH5jWAqEg','ONhlddR5c8jYMou6hJtP')) AS alert_in_list,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_inspection_record r WHERE r.id IN (
     '7kkVJ7zRRAxn6TGMQqi','Cvuc24Jqvxpxjo7eVMX')) AS insp_in_list;

-- 1.4 总量基线（期望：368 / 357 / 234 / 219）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef) AS site_total,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device) AS device_total,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert) AS alert_total,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order) AS order_total;


-- ============================================================
-- 第 2 节 清理 knc3g_bpzjoh 引用旧行的 19 条（外部应用数据，须经其侧确认）
-- ============================================================

DELETE FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh
WHERE site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE epjutj = '#5#');
-- 【期望】DELETE 19

-- 可选（knc3g 侧如需一并清理其整表测试数据——全表 38 行均为 2026-09-01 ~ 09-14 测试期数据，
--       含挂正式站 13 行、悬空站 5 行、空 1 行；不影响本侧，仅供其决策）：
-- DELETE FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh
-- WHERE created_at >= TIMESTAMP '2026-09-01 00:00:00' AND created_at < TIMESTAMP '2026-09-15 00:00:00';
-- 【期望】DELETE 38


-- ============================================================
-- 第 3 节 清理 water_inspection_record 巡查记录 2 条（全表即 2 条，均为测试期数据）
-- ============================================================

DELETE FROM "qixiao-apaas".t_auto_hltgq_water_inspection_record
WHERE id IN ('7kkVJ7zRRAxn6TGMQqi', 'Cvuc24Jqvxpxjo7eVMX');
-- 【期望】DELETE 2


-- ============================================================
-- 第 4 节 清理 8 张测试工单（迁移既定名单，引用旧行）
-- ============================================================

DELETE FROM "qixiao-apaas".t_auto_hltgq_water_work_order
WHERE id IN ('RCrbBJtuBBw9RFhdNG8', 'V3ojbJ1Zk6VrFzmCRWo', 'DM7tO3VPTpolqfGRqZk', '4ltUSjH23K0HVEz8DG3',
             '3wdgE0zoZj4hIlMBvAg', 'BFRhKpXaOxprzEc8ATw', 'eD1U1Ws6IscXik2QViU', 'M9NL6kxh6dPpV1Ikir4');
-- 【期望】DELETE 8


-- ============================================================
-- 第 5 节 清理悬空告警 5 条 + 异常告警 1 条（均为 2026-07 ~ 09 测试数据）
-- ============================================================

DELETE FROM "qixiao-apaas".t_auto_hltgq_water_alert
WHERE id IN ('RM2MorNsdCXwYlWDPGo', 'jCH1mfgKdDi5EFeDXdK', 'E9snpLblYUoyEjtT1cv',
             'evtnjQBk6vgUchljP6Wr', 'wIvYUUxbf5YCH5jWAqEg');
-- 【期望】DELETE 5（悬空 site 集）

-- 异常告警 1 条（site 正常、device 悬空、内容为测试失联告警）
DELETE FROM "qixiao-apaas".t_auto_hltgq_water_alert
WHERE id = 'ONhlddR5c8jYMou6hJtP';
-- 【期望】DELETE 1


-- ============================================================
-- 第 6 节 清理其他异常引用工单 11 条（悬空 site / 悬空 device / 悬空 alert，均为测试数据）
-- 注：花凉亭工单 4MFIgps01yMSPSdYQRhb（SYSTEM 创建、疑似真实业务、仅 alert 引用悬空）
--     单独列出，待业务方核实后再决定修复或删除，本脚本默认不处理。
-- ============================================================

DELETE FROM "qixiao-apaas".t_auto_hltgq_water_work_order
WHERE id IN ('TYlouExfT8tMrM71Ajr', 'WjFVgmMAXfuoC5iqS41', 'dPFeu1Jp8F2fRcYi99J', 'Tf8q3qS3UnpxhCjaFNu',
             'czDerhwlmy2y0oMrqNt', 'VT1ZxAMVNzB0K1ck4Zi', 'V9k4gkVNGgEu5xtAFjx', 'Zg2fyP3FrfNLgmztvO4z',
             'IOmd0eMKpuimRgJYqTx', 'pPG34Ap6EfLXnR3NEyCJ', 'Ya5EyKsPAaakA1kPQiiX');
-- 【期望】DELETE 11

-- 花凉亭工单（核实为测试后如决定删除，取消注释）：
-- DELETE FROM "qixiao-apaas".t_auto_hltgq_water_work_order WHERE id = '4MFIgps01yMSPSdYQRhb';
-- 【期望】DELETE 1


-- ============================================================
-- 第 7 节 删除 175 退役旧行（主任务）
-- 守卫：存在任一引用（设备/告警/工单/巡查/隐患记录）即不删该行；
--       若 knc3g 侧决定保留其引用行，先去掉 bpzjoh 守卫再执行（并知会其页面影响）。
-- ============================================================

DELETE FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.epjutj = '#5#'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d WHERE d.site = s.id)
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_alert a WHERE a.site = s.id)
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_work_order w WHERE w.site = s.id)
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_inspection_record r WHERE r.site = s.id)
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh b WHERE b.site = s.id);
-- 【期望】DELETE 175（前序各节完成后）；如有剩余未删行，按守卫项逐项查因


-- ============================================================
-- 第 8 节 （可选）僵尸设备 3 台 —— 业务方裁决"剔除"后启用
-- 现状：已离线（status = '#2#'）、无任何告警/工单引用、挂于正式站下
-- ============================================================

-- DELETE FROM "qixiao-apaas".t_auto_hltgq_water_device
-- WHERE id IN ('rxJLt8TrxHQ9iDZfLPkn', 'sXe1hdcrWDcSdnlQwstJ', 'hSFgr7nVIIOM0VsyS8EG');
-- 【期望】DELETE 3（code：1000276$1$0$0、1000324$1$0$0、1000304$1$0$0）


-- ============================================================
-- 第 9 节 执行后核对（只读；逐条与期望对照）
-- ============================================================

-- 9.1 旧行清零（期望 0）
SELECT count(*) AS retired_left FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#';

-- 9.2 引用与悬空复查（全部期望 0）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
   WHERE NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = d.site)) AS dev_dangling,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert a
   WHERE NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = a.site)) AS alert_dangling,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
   WHERE NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = w.site)) AS order_dangling,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh b
   WHERE NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = b.site)) AS bpzjoh_dangling;

-- 9.3 执行后总量（预期：site 193 / device 357（或 354）/ alert 228 / order 200（或 199））
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef) AS site_total,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device) AS device_total,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert) AS alert_total,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order) AS order_total;

-- 9.4 口径知会（site / mq 侧联合回归预期修订）：
--   site 档案页与 S5 回归：站点 368 → 193；退役行动态集合 = 0；
--   mq 侧 stationTotal / videoSiteTotal 按"有设备站"动态判定，不受本清理影响。
