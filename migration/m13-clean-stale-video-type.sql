-- ============================================================
-- m13 清理"含视频类型但已无视频设备"的站点类型残留
-- ============================================================
-- 起因（2026-10-20 核查）：m10 删除 25 台离线视频设备时，只删了设备/告警/工单，
-- 没有回收站点 epjutj 上的 #5#，导致 8 个站点仍标着视频类型但站下已无任何视频设备。
--   集岭电站进水闸  #1#|#3#|#4#|#5#
--   王家桥节制闸    #1#|#4#|#5#
--   长喉槽          #2#|#5#
--   南山寺节制闸    #3#|#4#|#5#
--   太怀干渠进水闸  #3#|#4#|#5#
--   中沟支渠进水闸  #3#|#5#
--   南山寺闸        #6#|#5#   （另有 devicecode=1000328$1$0$0 历史残留，本脚本不动）
--   虎形渡槽        #6#|#5#   （另有 devicecode=1000328$1$0$0 历史残留，本脚本不动）
--
-- 判定口径：站点 epjutj 含 #5#，且设备表中没有任何 type 含 #5# 的设备挂在它下面。
--   口径按"直接挂靠"计（与页面/接口按 site 查询一致）；8 站均无子站，子树口径同为 0。
--
-- 执行顺序：必须在 m12 之后执行。
--   m12 把麻塘湖 34 台设备从片区站迁到 34 个点位站后，片区站"麻塘湖灌区"也会
--   落入本口径（#7#|#5# → #7#），本脚本会自动一并处理，无需单独指定。
--
-- 幂等：重复执行第二遍 UPDATE 0 行。
-- 不做什么：不删站点、不清 devicecode、不改其他类型码、不动正在使用的视频站。
-- 纯 #5# 且无设备的僵尸视频站不在本脚本范围（当前 0 条），确需清理请人工确认后单独处理。
-- 本库注意：字符串拼接一律 concat()（|| 实测返回 false）；否定判定一律 COALESCE 包裹。
-- ============================================================


-- ------------------------------------------------------------
-- M13-0 前置核对（只读）：待处理清单 + 保护性检查
-- 期望：待处理 8 行（m12 已执行则 9 行，多一行"麻塘湖灌区"）；
--       protected 列应全部 >= 1（说明摘完还有其他类型码，不会摘成空串）
-- ------------------------------------------------------------
SELECT id, zzkaec, epjutj,
       trim(both '|' from regexp_replace(epjutj, '[|]?#5#', '', 'g')) AS cleaned,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
        WHERE d.site = s.id AND d.type LIKE '%#5#%') AS video_dev,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d
        WHERE d.site = s.id AND COALESCE(d.type,'') NOT LIKE '%#5#%') AS other_dev
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE COALESCE(s.epjutj,'') LIKE '%#5#%'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                  WHERE d.site = s.id AND d.type LIKE '%#5#%')
ORDER BY s.epjutj, s.zzkaec;
-- @@
-- M13-0b 保护性统计：口径内总行数 / 摘除后会变空的行数（期望 empty_after = 0）
SELECT count(*) AS todo,
       count(*) FILTER (WHERE trim(both '|' from regexp_replace(s.epjutj, '[|]?#5#', '', 'g')) = '') AS empty_after
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE COALESCE(s.epjutj,'') LIKE '%#5#%'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                  WHERE d.site = s.id AND d.type LIKE '%#5#%');
-- @@
-- ------------------------------------------------------------
-- M13-1 摘除 #5#（写操作）
-- 期望：UPDATE 8（m12 后 9）
-- 守卫：无任何视频设备 + 摘除后非空 + 摘除后确有变化
-- ------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
SET epjutj = trim(both '|' from regexp_replace(epjutj, '[|]?#5#', '', 'g')),
    updated_at = LOCALTIMESTAMP,
    updated_by = 'SYSTEM'
WHERE COALESCE(epjutj,'') LIKE '%#5#%'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                  WHERE d.site = t_auto_hltgq_5nw74_vnqqef.id AND d.type LIKE '%#5#%')
  AND trim(both '|' from regexp_replace(epjutj, '[|]?#5#', '', 'g')) <> ''
  AND trim(both '|' from regexp_replace(epjutj, '[|]?#5#', '', 'g')) <> epjutj;
-- @@
-- ------------------------------------------------------------
-- M13-2 执行后核对（只读）
-- ① 残留口径内的站：期望 0
-- ------------------------------------------------------------
SELECT count(*) AS remaining
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE COALESCE(s.epjutj,'') LIKE '%#5#%'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                  WHERE d.site = s.id AND d.type LIKE '%#5#%');
-- @@
-- ② 反向不变量：视频设备所指站点必须含 #5#：期望 0
SELECT count(*) AS bad_refs
FROM "qixiao-apaas".t_auto_hltgq_water_device d
JOIN "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s ON s.id = d.site
WHERE d.type LIKE '%#5#%'
  AND COALESCE(s.epjutj,'') NOT LIKE '%#5#%';
-- @@
-- ③ 被处理站的最终类型（8/9 行，确认其他类型码保留）
--    麻塘湖灌区：m13 在 m12 之后执行时已变为 #7#；若 m12 未执行则仍为 #7#|#5#，属正常（它还有 34 台设备）。
SELECT id, zzkaec, epjutj, updated_at
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id IN ('VzoiGl9W3XT7WheoEXV','LCmIfy4WB6lQ3OROQRo','Gz2UD0Vs097fkSG3nrP',
             'dByBR1Xdze8shnRGAw9','zxgbgrx0lUXS44Jklo8','24duMpDea9bPirywDlw',
             'Br8tp1OaBjYUZhN0TuO','bH0iPDKIbUTeah9F8yK','OOH2pADzfwQioCb5qAF')
ORDER BY zzkaec;
