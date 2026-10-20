-- m21：清理 2026-10-20 21:05 轮（部署前旧代码）的夜间提示类误报
--
-- 背景：m19/m20 执行后、服务重启前，21:05 轮仍按旧逻辑把这批夜间现象写成 fail：
--   GRAYSCALE 137、TOO_DARK,GRAYSCALE 8、TOO_DARK,GRAYSCALE,LOW_CONTRAST 3、
--   GRAYSCALE,NOISE 3、COLOR_CAST 1、TOO_DARK,LOW_CONTRAST 1。
--   该轮 fail 216，清理后应为 70（61 离线 + 1 取流 + 噪声/低对比度等真实故障）。
--
-- 口径同 m18（夜间）：纯提示类改 ok 保留类型；混合剔除提示项、保留真实故障。
-- 仅作用于这一轮，不触碰其他数据。

-- M21-0 备份（152 行）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020d AS
SELECT id, round_time, channel_code, result, fault_types
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE round_time = TIMESTAMP '2026-10-20 21:05:00'
  AND result = 'fail'
  AND (fault_types LIKE '%GRAYSCALE%' OR fault_types LIKE '%COLOR_CAST%' OR fault_types LIKE '%TOO_DARK%');

-- M21-1 纯提示类改 ok，保留类型（146 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET result = 'ok'
WHERE round_time = TIMESTAMP '2026-10-20 21:05:00'
  AND result = 'fail'
  AND fault_types IN ('GRAYSCALE', 'COLOR_CAST', 'TOO_DARK', 'TOO_DARK,GRAYSCALE');

-- M21-2 混合故障剔除提示项（6 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'NOISE'
WHERE round_time = TIMESTAMP '2026-10-20 21:05:00'
  AND result = 'fail' AND fault_types IN ('GRAYSCALE,NOISE', 'COLOR_CAST,NOISE');

UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'LOW_CONTRAST'
WHERE round_time = TIMESTAMP '2026-10-20 21:05:00'
  AND result = 'fail'
  AND fault_types IN ('GRAYSCALE,LOW_CONTRAST', 'COLOR_CAST,LOW_CONTRAST',
                      'TOO_DARK,LOW_CONTRAST', 'TOO_DARK,GRAYSCALE,LOW_CONTRAST');

-- M21-3 核验
-- ① 该轮：fail 期望 70，ok 期望 150（提示 146 + 正常 4）
SELECT round_time,
       count(*) AS total,
       count(*) FILTER (WHERE result = 'fail') AS fail_cnt,
       count(*) FILTER (WHERE result = 'ok' AND COALESCE(fault_types, '') = '') AS normal_cnt,
       count(*) FILTER (WHERE result = 'ok' AND COALESCE(fault_types, '') <> '') AS notice_cnt
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE round_time = TIMESTAMP '2026-10-20 21:05:00'
GROUP BY round_time;

-- ② 全库残留：黑白/偏色 fail 期望 0，夜间过暗 fail 期望 0
SELECT count(*) FILTER (WHERE result = 'fail'
                        AND (fault_types LIKE '%GRAYSCALE%' OR fault_types LIKE '%COLOR_CAST%')) AS grayscale_fail,
       count(*) FILTER (WHERE result = 'fail'
                        AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')
                        AND fault_types LIKE '%TOO_DARK%') AS night_dark_fail
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol;
