-- m18：清理巡检留痕中的夜间红外误报（2026-10-20）
--
-- 背景：巡检留痕此前未做夜间豁免，18:30-07:30 红外夜视输出灰度画面被判 GRAYSCALE，
--       叠加 TOO_DARK/COLOR_CAST，导致夜间在线设备几乎全部记为 result=fail，页面显示「检出故障」。
--       代码已改为落库前应用同一套夜间豁免（VideoPatrolRecordService#saveRound），本脚本清理历史数据。
--
-- 清理口径（仅夜间窗 18:30-07:30）：
--   ① 纯豁免故障（GRAYSCALE / TOO_DARK / COLOR_CAST）→ result=ok，fault_types 保留 —— 语义为「提示」：
--      规律性红外现象不属报警、不计失败，但保留类型供页面展示「提示：夜视黑白」；
--   ② 混合故障中的豁免项剔除：仍剩真实故障的保留 fail（如 GRAYSCALE,NOISE → NOISE）；
--      剔除后无剩余的记 ok 并保留原 fault_types（如 TOO_DARK,GRAYSCALE → ok + 提示）；
--   ③ 夜间 SIGNAL_LOSS / STREAM_ERROR / NOISE / LOW_CONTRAST 等非豁免类别不动；
--   ④ 白天数据不动（白天黑白/偏色可能是设备真实状态）。
--
-- 不删除行：只改判 result/fault_types，保留巡检覆盖记录，collected/expected 统计不受影响。
-- 影响行数（2026-10-20 20:35 实测）：夜间 fail 160,949 行，受影响 125,561 行，
--   其中 120,410 行改判 ok（提示），5,151 行仅剔除豁免项；清理后夜间 fail 应为 40,539 行。
-- 执行顺序：M18-0 → M18-1 → M18-2 → M18-3。

-- M18-0 备份受影响行（125,561 行）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020 AS
SELECT id, round_time, channel_code, result, fault_types
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE result = 'fail'
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')
  AND (fault_types LIKE '%GRAYSCALE%' OR fault_types LIKE '%TOO_DARK%' OR fault_types LIKE '%COLOR_CAST%');

-- M18-1 纯豁免故障 → ok（保留 fault_types 作为提示，118,302 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET result = 'ok'
WHERE result = 'fail'
  AND fault_types IN ('GRAYSCALE', 'TOO_DARK', 'COLOR_CAST')
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

-- M18-2 混合故障：剔除豁免项（保留真实故障，剔除后为空记 ok）
-- ① 剔除后仍为 fail（5,151 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'NOISE'
WHERE result = 'fail' AND fault_types = 'GRAYSCALE,NOISE'
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'LOW_CONTRAST'
WHERE result = 'fail' AND fault_types IN ('TOO_DARK,LOW_CONTRAST', 'TOO_DARK,GRAYSCALE,LOW_CONTRAST',
                                          'GRAYSCALE,LOW_CONTRAST')
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'NOISE'
WHERE result = 'fail' AND fault_types = 'COLOR_CAST,NOISE'
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'TOO_BRIGHT'
WHERE result = 'fail' AND fault_types = 'TOO_BRIGHT,GRAYSCALE'
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'BLUR'
WHERE result = 'fail' AND fault_types = 'GRAYSCALE,BLUR'
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'TOO_BRIGHT,BLUR,LOW_CONTRAST'
WHERE result = 'fail' AND fault_types = 'TOO_BRIGHT,GRAYSCALE,BLUR,LOW_CONTRAST'
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

-- ② 剔除后无剩余 → ok（保留原 fault_types 作为提示，2,108 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET result = 'ok'
WHERE result = 'fail' AND fault_types = 'TOO_DARK,GRAYSCALE'
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

-- M18-3 核验
-- ① 备份行数，期望 125561
SELECT count(*) AS backup_rows
FROM "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020;

-- ② 夜间剩余豁免项应为 0；夜间 ok 且带类型（提示）期望 120410；夜间 fail 期望 40539
SELECT count(*) AS total_rows,
       count(*) FILTER (WHERE result = 'fail'
                        AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')
                        AND (fault_types LIKE '%GRAYSCALE%' OR fault_types LIKE '%TOO_DARK%'
                             OR fault_types LIKE '%COLOR_CAST%')) AS night_exempt_left,
       count(*) FILTER (WHERE result = 'ok' AND COALESCE(fault_types, '') <> ''
                        AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')) AS night_notice,
       count(*) FILTER (WHERE result = 'fail'
                        AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')) AS night_fail
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol;

-- ③ 夜间失败类型分布（应只剩真实故障）
SELECT fault_types, count(*) AS cnt
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE result = 'fail'
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')
GROUP BY fault_types ORDER BY cnt DESC;
