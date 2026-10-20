-- m20：移除黑白（GRAYSCALE）历史提示（2026-10-20）
--
-- 背景：业务确认黑白属设备正常输出（黑白机/夜间红外），不需要提示、不需要报警。
-- 影响：巡检表当前含 GRAYSCALE 的全部行 140,113 条（均为 result=ok）：
--   ① 纯黑白 137,960 行 → fault_types 置空；
--   ② 过暗+黑白 2,153 行 → 仅保留 TOO_DARK（过暗提示保留）。
-- 不删除行、不改 result/round_time，仅从 fault_types 移除 GRAYSCALE。
--
-- 注意：执行后旧代码的新巡检仍会产生黑白提示（每轮约 140 条），
--       需部署 ignore-faults=GRAYSCALE 的版本；部署后再重复执行 M20-1/M20-2 清理期间增量。
--
-- 实测基线（2026-10-20 23:10）：巡检表 343,230 行；GRAYSCALE 提示 140,113 行；
--   TOO_DARK 提示 247 行；备份表 bak/bak_b/bak_c/bak_d 已存在，本脚本使用新表 bak_e。
--   23:35 巡检后行数会略增（每轮约 140 条），以下数量以执行时备份行数为准。

-- M20-0 备份本次受影响行（基线约 140,113 行，以执行时点为准）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020e AS
SELECT id, round_time, channel_code, result, fault_types
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE fault_types LIKE '%GRAYSCALE%';

-- M20-1 纯黑白提示 → 清空类型（期望 137,960 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = NULL
WHERE fault_types = 'GRAYSCALE';

-- M20-2 过暗+黑白 → 仅保留过暗（期望 2,153 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'TOO_DARK'
WHERE fault_types = 'TOO_DARK,GRAYSCALE';

-- M20-3 核验
-- 期望：bak = M20-1 + M20-2 影响行数（基线约 140,113），gray_left=0，
--       too_dark = 原 247 + M20-2 行数（基线约 2,400），total 不变（清理不改行数）
SELECT (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020e) AS bak,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
        WHERE fault_types LIKE '%GRAYSCALE%') AS gray_left,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
        WHERE fault_types = 'TOO_DARK') AS too_dark,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol) AS total;

-- M20-4 清理后残留检查（若已过下一轮巡检，可能少量新增，属旧代码预期）
SELECT round_time, result, fault_types, count(*) AS cnt
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE fault_types LIKE '%GRAYSCALE%'
GROUP BY round_time, result, fault_types
ORDER BY round_time DESC LIMIT 5;
