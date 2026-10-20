-- m20：恢复夜间巡检历史的「提示」类型（2026-10-20）
--
-- 背景：m18 执行的是早期版本（改判 ok 时把 fault_types 置空），导致夜间 120,410 行
--       「黑白/过暗/偏色」历史数据在页面上显示为「正常」而不是「提示」。
--       m18 的备份表保留了原始 fault_types，可按 id 精确回填。
--
-- 恢复口径：当前 result=ok、fault_types 为空、备份中原始故障全部属于豁免类
--   （GRAYSCALE / TOO_DARK / COLOR_CAST 单值，或 TOO_DARK,GRAYSCALE 组合）。
--   混合后仍有真实故障的行（如 GRAYSCALE,NOISE → NOISE）当前 fault_types 非空，不匹配。
-- 幂等：重复执行时已回填的行 fault_types 非空，不再匹配。
-- 影响行数（2026-10-20 21:10 实测）：120,410 行。

-- M20-0 备份当前待回填行（120,410 行）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020c AS
SELECT id, round_time, channel_code, result, fault_types
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE result = 'ok' AND COALESCE(fault_types, '') = ''
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00');

-- M20-1 从 m18 备份回填提示类型
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol p
SET fault_types = b.fault_types
FROM "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020 b
WHERE p.id = b.id
  AND p.result = 'ok'
  AND COALESCE(p.fault_types, '') = ''
  AND b.result = 'fail'
  AND b.fault_types IN ('GRAYSCALE', 'TOO_DARK', 'COLOR_CAST', 'TOO_DARK,GRAYSCALE');

-- M20-2 核验
-- ① 夜间 ok 带类型（提示）期望 120410；白天保持 25629；总行数 342130
SELECT CASE WHEN (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')
            THEN 'night' ELSE 'day' END AS period,
       count(*) FILTER (WHERE result = 'ok' AND COALESCE(fault_types, '') = '') AS ok_empty,
       count(*) FILTER (WHERE result = 'ok' AND COALESCE(fault_types, '') <> '') AS ok_typed,
       count(*) FILTER (WHERE result = 'fail') AS fail_cnt
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
GROUP BY period ORDER BY period;

-- ② 夜间提示类型分布
SELECT fault_types, count(*) AS cnt
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE result = 'ok' AND COALESCE(fault_types, '') <> ''
  AND (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')
GROUP BY fault_types ORDER BY cnt DESC;
