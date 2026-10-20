-- m17：太湖毕岭站点摘除 #5#（site 改挂后的类型收尾）
--
-- 背景：site 于 2026-10-20 将 16 台视频设备按点位改挂到工程站。其中
--       黄花庙_1（1000242$1$0$0）由「太湖毕岭」改挂到「黄花庙直灌涵」后，
--       太湖毕岭名下只剩「太湖毕岭墒情探测#」（type=#7#），已无视频设备，
--       但站点类型仍是 #7#|#5#，违反「含 #5# 的站必有视频设备」不变量。
--       按 m13 口径摘除 #5#，恢复为 #7#。
--
-- 前置：16 台设备改挂已完成（site 操作）。
-- 影响：仅 1 行站点类型，不动设备、不动其他站。
-- 执行：整文件按 M17-0 → M17-1 → M17-2 顺序执行。

-- M17-0 备份（1 行）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_station_type5_bak_20261020b AS
SELECT id, zzkaec, epjutj, updated_at, updated_by
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE zzkaec = '太湖毕岭';

-- M17-1 摘除 #5#（守卫：名下确无视频设备才改）
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET epjutj = regexp_replace(s.epjutj, '[|]?#5#', ''),
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE s.zzkaec = '太湖毕岭'
  AND s.epjutj LIKE '%#5#%'
  AND NOT EXISTS (
      SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
      WHERE d.site = s.id AND d.type LIKE '%#5#%'
  );

-- M17-2 核验
-- ① 目标站类型应为 #7#，设备不变（1 台墒情）
SELECT s.zzkaec, s.epjutj, s.iofhpi,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
        WHERE d.site = s.id) AS devices,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
        WHERE d.site = s.id AND d.type LIKE '%#5#%') AS videos
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.zzkaec = '太湖毕岭';

-- ② 全库双向不变量，期望两项均为 0
SELECT 'type5_no_video' AS check_item, count(*) AS cnt
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.epjutj LIKE '%#5#%'
  AND NOT EXISTS (
      SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
      WHERE d.site = s.id AND d.type LIKE '%#5#%'
  )
UNION ALL
SELECT 'video_no_type5', count(*)
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE COALESCE(s.epjutj, '') NOT LIKE '%#5#%'
  AND EXISTS (
      SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
      WHERE d.site = s.id AND d.type LIKE '%#5#%'
  );

-- ③ 设备总量与挂站，期望 413 / 0 / 0
SELECT count(*) AS dev_total,
       count(*) FILTER (WHERE site IS NULL OR site = '') AS dev_no_site,
       count(*) FILTER (WHERE site IS NOT NULL AND site <> ''
                        AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
                                        WHERE s.id = "qixiao-apaas".t_auto_hltgq_water_device.site)) AS dev_orphan
FROM "qixiao-apaas".t_auto_hltgq_water_device;
