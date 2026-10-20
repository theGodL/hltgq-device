-- =====================================================================
-- m15 麻塘湖新建站点类型码修正（2026-10-20）
--
-- 背景：m14 建 19 个站点时类型码映射写错，导致：
--   10 个雷达流量站写成 #2#|#5#（雨量/视频），应为 #3#|#5#（流量/视频）；
--    2 个雨量站写成 #3#、#3#|#5#（流量），应为 #2#、#2#|#5#（雨量）；
--    2 个水质站写成 #1#|#4#|#5#（水位/闸门/视频），应为 #1#|#8#|#5#（水位/水质/视频）。
--
-- 类型码（库内数据实证）：
--   #1# 水位   周家河水位计、花凉亭坝下水位计
--   #2# 雨量   16 台「xx雨量计」
--   #3# 流量   100 台「xx流量计」
--   #4# 闸门   60 台「xx节制闸 1#/2#/3#」
--   #5# 视频   220 台摄像设备
--   #6# 水库/水闸/渡槽/大坝（discharge_type 有值）
--   #7# 墒情 / 片区组织站
--   #8# 水质   南干进水闸水质站（#8# + 水质监测仪 #8#）
--
-- 不动的站：7 个水位站（#1#|#5#）、麻塘湖水库（#6#）。
-- 幂等：每条 UPDATE 带原值守卫，重跑 0 行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- M15-0 备份 19 个 MthSite 站当前类型（只建一次）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_mth_sites_type_bak_20261020 AS
SELECT id, zzkaec, epjutj
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id LIKE 'MthSite%';

SELECT 'type_bak' AS item, count(*) AS cnt
FROM "qixiao-apaas".t_auto_hltgq_mth_sites_type_bak_20261020;

-- ---------------------------------------------------------------------
-- M15-1 雷达流量站 10 个：#2#|#5# → #3#|#5#（期望 10 行）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
SET epjutj = '#3#|#5#',
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE id IN ('MthSite0000000000003','MthSite0000000000004','MthSite0000000000005',
             'MthSite0000000000006','MthSite0000000000007','MthSite0000000000008',
             'MthSite0000000000009','MthSite0000000000010','MthSite0000000000011',
             'MthSite0000000000012')
  AND epjutj = '#2#|#5#';

-- ---------------------------------------------------------------------
-- M15-2 雨量站 2 个（期望 1 + 1 行）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
SET epjutj = '#2#',
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE id = 'MthSite0000000000018'   -- 望江分干渠（雨量）
  AND epjutj = '#3#';

UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
SET epjutj = '#2#|#5#',
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE id = 'MthSite0000000000019'   -- 老鼠沟（雨量 + 视频）
  AND epjutj = '#3#|#5#';

-- ---------------------------------------------------------------------
-- M15-3 水质站 2 个：#1#|#4#|#5# → #1#|#8#|#5#（期望 2 行）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
SET epjutj = '#1#|#8#|#5#',
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE id IN ('MthSite0000000000001','MthSite0000000000002')  -- 望江/怀宁分干渠进水闸
  AND epjutj = '#1#|#4#|#5#';

-- ---------------------------------------------------------------------
-- M15-4 核验
-- ---------------------------------------------------------------------

-- A 19 个站点类型清单
SELECT id, zzkaec, iofhpi, epjutj
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id LIKE 'MthSite%'
ORDER BY id;

-- B 计数（期望：流量 10、雨量 2、水质 2、闸门 0、水位 7）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
   WHERE id LIKE 'MthSite%' AND epjutj LIKE '%#3#%') AS flow_sites,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
   WHERE id LIKE 'MthSite%' AND epjutj LIKE '%#2#%') AS rain_sites,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
   WHERE id LIKE 'MthSite%' AND epjutj LIKE '%#8#%') AS quality_sites,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
   WHERE id LIKE 'MthSite%' AND epjutj LIKE '%#4#%') AS gate_sites,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
   WHERE id LIKE 'MthSite%' AND epjutj LIKE '%#1#%' AND id NOT IN
     ('MthSite0000000000001','MthSite0000000000002')) AS water_level_sites;

-- C 反向不变量（改动不动设备，期望 0/0）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
   WHERE COALESCE(s.epjutj,'') LIKE '%#5#%'
     AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                     WHERE d.site = s.id AND d.type LIKE '%#5#%')) AS video_site_no_device,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
   WHERE d.type LIKE '%#5#%'
     AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
                     WHERE s.id = d.site AND COALESCE(s.epjutj,'') LIKE '%#5#%')) AS video_device_no_site;
