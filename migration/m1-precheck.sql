-- =====================================================================
-- m1-precheck.sql · 迁移前校验（只读，全部 SELECT）
-- 依据：评审说明 11.5.1（幂等守卫）/ 11.5.2（映射基线）/ 11.6-④（动态条件）
-- 使用：迁移窗口内、停旧服务并导出四张映射 CSV 之后、执行 m2 之前逐条运行；
--       每条与注释内【期望】比对，不符即停、查因。
-- 说明：本库实测坑——否定式判定一律 COALESCE(列,'') 包裹（NULL 行会被
--       NOT LIKE / <> '' 整组误排除）；字符串拼接一律 concat()（'||' 返回 false）。
-- =====================================================================

-- 1) 存量总盘（基线快照，与评审说明 11.1 对账）
SELECT count(*) AS site_total FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef;
-- 【期望】368（迁移不删除任何行，此值迁移前后恒等）

SELECT count(*) AS video_site_lines FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#';
-- 【期望】175（纯 #5# 视频行 = 旧行；注意必须用等值 '#5#'，勿用 LIKE——业务站迁移后含复合 #5#）

SELECT count(DISTINCT v.ahieto) AS target_sites FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
WHERE v.epjutj = '#5#' AND COALESCE(v.ahieto,'') <> '';
-- 【期望】122（迁移目标站 = 旧行 ahieto 去重）

SELECT count(*) AS video_devices FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE type LIKE '%#5#%';
-- 【期望】175（视频设备总数，迁移前后恒等）

-- 2) 守卫预检：目标站尚不含 #5#（动作 1 的守卫命中数 = 目标站全量）
SELECT count(*) AS target_without_5
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.id IN (SELECT DISTINCT v.ahieto FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
               WHERE v.epjutj = '#5#' AND COALESCE(v.ahieto,'') <> '')
  AND COALESCE(s.epjutj,'') NOT LIKE '%#5#%';
-- 【期望】122（= 目标站数；若小于 122 说明目标站已含 #5#，动作 1 会幂等跳过，无需处理，但需记录）

SELECT count(*) AS target_with_5
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.id IN (SELECT DISTINCT v.ahieto FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
               WHERE v.epjutj = '#5#' AND COALESCE(v.ahieto,'') <> '')
  AND COALESCE(s.epjutj,'') LIKE '%#5#%';
-- 【期望】0（迁移前目标站均不含 #5#；>0 表示疑似重复迁移，停止并核查）

-- 3) 旧行 ahieto 覆盖率（无空值/悬挂 = 全部可迁移）
SELECT count(*) AS video_lines_without_ahieto FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#' AND COALESCE(ahieto,'') = '';
-- 【期望】0（11.3-A 已实测 175 行 ahieto 100% 挂靠、无悬挂、无指向视频行）

-- 4) 待换指设备数（动作 2 命中数）
SELECT count(*) AS devices_to_move
FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.type LIKE '%#5#%'
  AND EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = d.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto,'') <> '');
-- 【期望】175（灰度执行前；灰度完成后全量再跑此值 = 169）

-- 5) 待换指告警数（动作 3 命中数）
SELECT count(*) AS alerts_to_move
FROM "qixiao-apaas".t_auto_hltgq_water_alert a
WHERE EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = a.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto,'') <> '');
-- 【期望】执行时点值（09-24 10:17 快照 = 95；随巡检持续增长，以本次执行前的值为基线）
-- 口径：site ∈ 旧视频行集合的全量告警（实测全部为视频巡检类，与 mq 三类零交叉）

-- 6) 待换指工单数（动作 4 命中数，排除 8 张测试工单）
SELECT count(*) AS orders_to_move
FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
WHERE EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = w.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto,'') <> '')
  AND w.id NOT IN ('RCrbBJtuBBw9RFhdNG8','V3ojbJ1Zk6VrFzmCRWo','DM7tO3VPTpolqfGRqZk','4ltUSjH23K0HVEz8DG3',
                   '3wdgE0zoZj4hIlMBvAg','BFRhKpXaOxprzEc8ATw','eD1U1Ws6IscXik2QViU','M9NL6kxh6dPpV1Ikir4');
-- 【期望】执行时点值 − 8（09-24 10:17 快照 = 95；8 张测试不换指、留旧行待业务清理）

SELECT w.id, w.code, w.status, w.title
FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
WHERE EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = w.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto,'') <> '')
  AND w.id IN ('RCrbBJtuBBw9RFhdNG8','V3ojbJ1Zk6VrFzmCRWo','DM7tO3VPTpolqfGRqZk','4ltUSjH23K0HVEz8DG3',
               '3wdgE0zoZj4hIlMBvAg','BFRhKpXaOxprzEc8ATw','eD1U1Ws6IscXik2QViU','M9NL6kxh6dPpV1Ikir4');
-- 【期望】恰 8 行（测试/垃圾数据清单，交业务方/平台清理；执行前复核清单构成未变）

-- 7) 退役行动态条件预检（迁移前应为 0，8.2-3 / 11.6-④ 基线）
SELECT count(*) AS retired_lines_before
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.epjutj = '#5#' AND NOT EXISTS
  (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
   WHERE d.site = s.id AND d.type LIKE '%#5#%');
-- 【期望】0（175 旧行全部有设备指向，不误伤活跃行）

-- 8) 快照与映射 CSV 就绪核对（人工）：dry-run/dryrun-{sites,devices,alerts,orders}.csv
--    重导方法见 dry-run/dry-run说明.md「工具复现」；导出的 sites CSV 行数应为 175、
--    devices 175、alerts/orders 为执行时点全量。回退基线以本次导出的 CSV 为准。

-- =====================================================================
-- 全部通过后 → 执行 m2-migrate.sql 灰度节（6 台）
-- =====================================================================
