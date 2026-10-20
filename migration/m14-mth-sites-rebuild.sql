-- =====================================================================
-- m14 麻塘湖站点重建与视频设备归位（2026-10-20）
--
-- 背景：m12 误把青谷台账「灌区视频监控点」（R201-R235）的点位名称当成站点，
--       建了 34 个纯 #5# 视频点站（如「泄洪闸前院」）；真正的站点在
--       台账 R171-R199（有站码、量测水方式），一个都没建。
--
-- 本脚本：
--   1. 备份 34 个 MthVideo 点位站 + 34 台设备；
--   2. 按台账 R171-R199 建 19 个站点（麻塘湖水库已存在，保持不动）；
--      站码写 iofhpi（站点编号），devicecode 是视频通道编号，不写；
--      站点类型按量测水方式：流量 #3#、水位 #1#、雨量 #2#、水质 #8#，
--      挂了视频设备的追加 #5#；
--      站点坐标取匹配视频设备的经纬度；
--   3. 20 台设备挂到对应站点（含 4 组名称近似、2 台支渠中段设备）；
--   4. 其余 14 台挂麻塘湖灌区片区站；
--   5. 告警/工单的 site 冗余字段按设备新归属回填（共 16+16 条）；
--   6. 删除 34 个 MthVideo 点位站；
--   7. 核验。
--
-- 说明：望江分干渠进水闸、怀宁分干渠进水闸在台账各有两个站码
--       （水质 8000000002/8000000003，水位 8000000017/8000000016），
--       iofhpi 为单值字段，本脚本存水质主码；水位码保留在台账/本文档，
--       如业务要求双码入库，需另行确认承载字段。
--
-- 注：本脚本 2026-10-20 已在生产执行，但当时流量/雨量/水质类型码写错
--     （#2#/#3# 互反、水质误用 #4#），生产数据由 m15-mth-site-type-fix.sql
--     修正；本文件中的类型值已同步改正，供新环境重跑使用。
-- =====================================================================

-- ---------------------------------------------------------------------
-- M14-0 备份（只建一次；重跑不覆盖首轮快照）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_mth_video_sites_bak_20261020 AS
SELECT * FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id LIKE 'MthVideo%';

CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_mth_video_devices_bak_20261020 AS
SELECT * FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE id LIKE 'MthVideo%')
  AND type LIKE '%#5#%';

-- 备份行数核对：期望 34 / 34
SELECT 'sites_bak' AS item, count(*) AS cnt
FROM "qixiao-apaas".t_auto_hltgq_mth_video_sites_bak_20261020
UNION ALL
SELECT 'devices_bak', count(*)
FROM "qixiao-apaas".t_auto_hltgq_mth_video_devices_bak_20261020;

-- ---------------------------------------------------------------------
-- M14-1 建 19 个站点（上级=麻塘湖灌区 OOH2pADzfwQioCb5qAF）
--       id 规则：MthSite + 13 位序号（20 位，与 MthVideo 同长）
--       iofhpi = 台账站码；bviiio_x/y = 匹配视频设备坐标
--       望江分干渠（雨量）无对应视频点，坐标暂借望江分干渠进水闸设备坐标
-- ---------------------------------------------------------------------
WITH v(id, name, type, code, x, y) AS (
VALUES
    ('MthSite0000000000001', '望江分干渠进水闸', '#1#|#8#|#5#', '8000000002', 116.660346, 30.392488),
    ('MthSite0000000000002', '怀宁分干渠进水闸', '#1#|#8#|#5#', '8000000003', 116.664589, 30.393721),
    ('MthSite0000000000003', '东方红进水闸',     '#3#|#5#',       '8000000004', 116.627681, 30.407585),
    ('MthSite0000000000004', '朱山咀进水闸',     '#3#|#5#',       '8000000005', 116.631488, 30.406190),
    ('MthSite0000000000005', '五圩支渠进水闸',   '#3#|#5#',       '8000000006', 116.637702, 30.402738),
    ('MthSite0000000000006', '长青支渠',         '#3#|#5#',       '8000000007', 116.645979, 30.400076),
    ('MthSite0000000000007', '万全支渠',         '#3#|#5#',       '8000000008', 116.648347, 30.399082),
    ('MthSite0000000000008', '庆洲支渠',         '#3#|#5#',       '8000000009', 116.651563, 30.398810),
    ('MthSite0000000000009', '易潭支渠',         '#3#|#5#',       '8000000010', 116.685305, 30.403997),
    ('MthSite0000000000010', '彭西进水闸',       '#3#|#5#',       '8000000011', 116.662227, 30.391338),
    ('MthSite0000000000011', '同兴支渠',         '#3#|#5#',       '8000000012', 116.681727, 30.390635),
    ('MthSite0000000000012', '高排沟进水闸',     '#3#|#5#',       '8000000013', 116.677556, 30.367594),
    ('MthSite0000000000013', '新光支渠',         '#1#|#5#',       '8000000014', 116.639498, 30.402826),
    ('MthSite0000000000014', '中心支渠',         '#1#|#5#',       '8000000015', 116.645795, 30.401270),
    ('MthSite0000000000015', '周上墩支渠',       '#1#|#5#',       '8000000018', 116.679428, 30.392426),
    ('MthSite0000000000016', '九朋支渠',         '#1#|#5#',       '8000000019', 116.683352, 30.395207),
    ('MthSite0000000000017', '张屋干渠',         '#1#|#5#',       '8000000020', 116.669278, 30.372162),
    ('MthSite0000000000018', '望江分干渠',       '#2#',           '8000000021', 116.660346, 30.392488),
    ('MthSite0000000000019', '老鼠沟',           '#2#|#5#',       '8000000022', 116.709162, 30.368424)
)
INSERT INTO "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
    (id, corp_code, created_at, created_by, updated_at, updated_by,
     zzkaec, mivbcz, zebpsu, epjutj, ahieto, iofhpi, bviiio_x, bviiio_y)
SELECT v.id, 'hltgq', LOCALTIMESTAMP, 'SYSTEM', LOCALTIMESTAMP, 'SYSTEM',
       v.name, v.name, '#1#', v.type, 'OOH2pADzfwQioCb5qAF', v.code, v.x, v.y
FROM v
WHERE NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = v.id);

-- ---------------------------------------------------------------------
-- M14-2 20 台设备挂到对应站点
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_water_device
SET site = CASE code
        WHEN '1000384$1$0$0' THEN 'MthSite0000000000001'  -- 望江分干渠进水闸
        WHEN '1000382$1$0$0' THEN 'MthSite0000000000002'  -- 怀宁分干渠进水闸
        WHEN '1000374$1$0$0' THEN 'MthSite0000000000003'  -- 东方红进水闸
        WHEN '1000368$1$0$0' THEN 'MthSite0000000000004'  -- 朱山咀进水渠 → 朱山咀进水闸
        WHEN '1000364$1$0$0' THEN 'MthSite0000000000005'  -- 五圩支渠进水闸
        WHEN '1000376$1$0$0' THEN 'MthSite0000000000006'  -- 常青支渠 → 长青支渠
        WHEN '1000377$1$0$0' THEN 'MthSite0000000000007'  -- 万全支渠
        WHEN '1000381$1$0$0' THEN 'MthSite0000000000008'  -- 庆洲支渠
        WHEN '1000401$1$0$0' THEN 'MthSite0000000000009'  -- 易潭支渠
        WHEN '1000395$1$0$0' THEN 'MthSite0000000000009'  -- 易潭支渠中段
        WHEN '1000383$1$0$0' THEN 'MthSite0000000000010'  -- 彭西进水闸
        WHEN '1000403$1$0$0' THEN 'MthSite0000000000011'  -- 同兴支渠
        WHEN '1000394$1$0$0' THEN 'MthSite0000000000012'  -- 高排进水闸 → 高排沟进水闸
        WHEN '1000380$1$0$0' THEN 'MthSite0000000000013'  -- 新光支渠
        WHEN '1000375$1$0$0' THEN 'MthSite0000000000014'  -- 中心支渠
        WHEN '1000393$1$0$0' THEN 'MthSite0000000000015'  -- 周上墩支渠
        WHEN '1000402$1$0$0' THEN 'MthSite0000000000016'  -- 九朋支渠
        WHEN '1000396$1$0$0' THEN 'MthSite0000000000016'  -- 九朋支渠中尾段
        WHEN '1000385$1$0$0' THEN 'MthSite0000000000017'  -- 张屋 → 张屋干渠
        WHEN '1000397$1$0$0' THEN 'MthSite0000000000019'  -- 老鼠沟
    END,
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE type LIKE '%#5#%'
  AND code IN ('1000384$1$0$0','1000382$1$0$0','1000374$1$0$0','1000368$1$0$0',
               '1000364$1$0$0','1000376$1$0$0','1000377$1$0$0','1000381$1$0$0',
               '1000401$1$0$0','1000395$1$0$0','1000383$1$0$0','1000403$1$0$0',
               '1000394$1$0$0','1000380$1$0$0','1000375$1$0$0','1000393$1$0$0',
               '1000402$1$0$0','1000396$1$0$0','1000385$1$0$0','1000397$1$0$0');

-- ---------------------------------------------------------------------
-- M14-3 其余 14 台统一挂麻塘湖灌区片区站
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_water_device
SET site = 'OOH2pADzfwQioCb5qAF',
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE type LIKE '%#5#%'
  AND code IN ('1000362$1$0$0','1000363$1$0$0','1000367$1$0$0','1000378$1$0$0',
               '1000379$1$0$0','1000386$1$0$0','1000387$1$0$0','1000388$1$0$0',
               '1000389$1$0$0','1000390$1$0$0','1000391$1$0$0','1000398$1$0$0',
               '1000399$1$0$0','1000400$1$0$0');

-- ---------------------------------------------------------------------
-- M14-4 片区站补 #5#（挂了 14 台视频设备，必须含视频类型）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
SET epjutj = concat(epjutj, '|#5#'),
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE id = 'OOH2pADzfwQioCb5qAF'
  AND COALESCE(epjutj, '') NOT LIKE '%#5#%';

-- ---------------------------------------------------------------------
-- M14-5 告警/工单 site 冗余字段按设备新归属回填（16 + 16 条）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_water_alert a
SET site = (SELECT d.site FROM "qixiao-apaas".t_auto_hltgq_water_device d WHERE d.id = a.device)
WHERE a.site LIKE 'MthVideo%'
  AND EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d WHERE d.id = a.device);

UPDATE "qixiao-apaas".t_auto_hltgq_water_work_order w
SET site = (SELECT d.site FROM "qixiao-apaas".t_auto_hltgq_water_device d WHERE d.id = w.device)
WHERE w.site LIKE 'MthVideo%'
  AND EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d WHERE d.id = w.device);

-- ---------------------------------------------------------------------
-- M14-6 删除 34 个 MthVideo 点位站（守卫：无设备/告警/工单引用）
-- ---------------------------------------------------------------------
DELETE FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id LIKE 'MthVideo%'
  AND id NOT IN (SELECT site FROM "qixiao-apaas".t_auto_hltgq_water_device WHERE site IS NOT NULL)
  AND id NOT IN (SELECT site FROM "qixiao-apaas".t_auto_hltgq_water_alert WHERE site IS NOT NULL)
  AND id NOT IN (SELECT site FROM "qixiao-apaas".t_auto_hltgq_water_work_order WHERE site IS NOT NULL);

-- ---------------------------------------------------------------------
-- M14-7 核验
-- ---------------------------------------------------------------------

-- A 19 个新站点：类型/站码/坐标/设备数（期望 19 行，坐标全非空）
SELECT s.id, s.zzkaec, s.epjutj, s.iofhpi, s.bviiio_x, s.bviiio_y,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
        WHERE d.site = s.id AND d.type LIKE '%#5#%') AS videos
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.id LIKE 'MthSite%'
ORDER BY s.id;

-- B MthVideo 残留（期望 0）
SELECT count(*) AS mthvideo_left
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id LIKE 'MthVideo%';

-- C 麻塘湖片区站：类型与设备数（期望 #7#|#5#，14 台）
SELECT s.id, s.zzkaec, s.epjutj,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
        WHERE d.site = s.id AND d.type LIKE '%#5#%') AS videos
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.id = 'OOH2pADzfwQioCb5qAF';

-- D 双向不变量（期望 0/0）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
   WHERE COALESCE(s.epjutj,'') LIKE '%#5#%'
     AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                     WHERE d.site = s.id AND d.type LIKE '%#5#%')) AS video_site_no_device,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
   WHERE d.type LIKE '%#5#%'
     AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
                     WHERE s.id = d.site AND COALESCE(s.epjutj,'') LIKE '%#5#%')) AS video_device_no_site;

-- E 告警/工单 site 悬空（期望 0/0）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert a
   WHERE a.site IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = a.site)) AS alert_dangling,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
   WHERE w.site IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = w.site)) AS order_dangling;

-- F 总量（期望 视频设备 220、站点 224、含 #5# 站 139）
SELECT
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device WHERE type LIKE '%#5#%') AS video_devices,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef) AS sites,
  (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE COALESCE(epjutj,'') LIKE '%#5#%') AS video_sites;

-- G 片区站 14 台设备清单（抽核）
SELECT code, name
FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE site = 'OOH2pADzfwQioCb5qAF' AND type LIKE '%#5#%'
ORDER BY code;
