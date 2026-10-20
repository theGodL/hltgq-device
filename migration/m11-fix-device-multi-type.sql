-- ============================================================
-- m11 修正设备表多类型标签（2026-10-20）
-- ============================================================
-- 背景：设备表 type 与站点表 epjutj 共用 "#n#" 标签体系。站点按多类型设计
--       （m2 与同步轮都会追加 #5#），这两条设备行的标签组合与所属站点一致，
--       属建行时连带了站点要素，不是本服务写入（本服务只用单个 #5#）。
-- 影响：双庙湖渡槽流量计被 type LIKE '%#5#%' 统计成视频设备。
--       它 code 为空，不参与 ICC 同步，不会重复建设备、不产生视频告警。
-- 预期：视频设备 200 → 199；首轮同步补建 21 条后为 220（= ICC 通道数）。
-- ============================================================

-- 1. 执行前确认：应为 2 行（流量计 #3#|#5#、水位计 #1#|#2#）
SELECT id, code, name, type, status
FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE type LIKE '%|%'
ORDER BY name;

-- 2. 流量计去掉视频标签，保留流量计 #3#（期望 UPDATE 1）
UPDATE "qixiao-apaas".t_auto_hltgq_water_device
SET type = '#3#',
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE id = 'U3ReNoiudbIo8a3NIU91'
  AND type = '#3#|#5#'
  AND (code IS NULL OR code = '');

-- 3. 执行后确认：multi_type_left=1（只剩水位计），video_multi_left=0
SELECT count(*) AS multi_type_left,
       count(*) FILTER (WHERE type LIKE '%#5#%') AS video_multi_left
FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE type LIKE '%|%';

-- ============================================================
-- 另 1 条多标签：花凉亭坝上水位计# type=#1#|#2#（水位 + 雨量），不含 #5#，
-- 不属视频侧；其站点下也没有第二台雨量计。默认不动。
-- 若业务确认该设备只测水位，再单独执行：
-- UPDATE "qixiao-apaas".t_auto_hltgq_water_device SET type = '#1#', updated_at = now(), updated_by = 'SYSTEM'
-- WHERE id = 'sY8rwpxtydfwxAnsFhBa' AND type = '#1#|#2#';
-- ============================================================
