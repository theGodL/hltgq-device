-- m19：白天黑白/偏色改判「提示」（2026-10-20）
--
-- 背景：设计院口径——黑白、偏色属设备输出特性（黑白机/白平衡），正常不会有人上站维修，
--       不应算故障、不应进报警。m18 已清理夜间；本脚本清理白天历史数据。
--
-- 口径（仅白天窗 07:30-18:30）：
--   ① 纯 GRAYSCALE / COLOR_CAST → result=ok，保留 fault_types 作为「提示」；
--   ② 混合故障剔除黑白/偏色，保留其余真实故障：
--      GRAYSCALE,NOISE → NOISE；GRAYSCALE,LOW_CONTRAST → LOW_CONTRAST；
--      COLOR_CAST,NOISE → NOISE；COLOR_CAST,LOW_CONTRAST → LOW_CONTRAST；
--      TOO_DARK,GRAYSCALE → TOO_DARK（过暗白天按故障保留）；
--      TOO_DARK,GRAYSCALE,LOW_CONTRAST → TOO_DARK,LOW_CONTRAST；
--   ③ 白天 TOO_DARK 单独保留（可能镜头遮挡/补光故障，有人维修）；
--   ④ 夜间数据已在 m18 清理完毕，本脚本不触碰夜间。
--
-- 影响行数（2026-10-20 21:05 实测）：白天 fail 61,594 行，受影响 27,203 行——
--   25,629 行改判 ok（提示），1,574 行仅剔除黑白/偏色；清理后白天 fail 应为 35,965 行。
--   另有关联告警：黑白未关闭 53、偏色未关闭 16（均已产生待处理工单 69 条），一并关闭。
-- 不删除行：只改判 result/fault_types/status，保留巡检与告警记录。

-- M19-0 备份受影响行（27,203 行）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020b AS
SELECT id, round_time, channel_code, result, fault_types
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE result = 'fail'
  AND (round_time::time < time '18:30:00' AND round_time::time >= time '07:30:00')
  AND (fault_types LIKE '%GRAYSCALE%' OR fault_types LIKE '%COLOR_CAST%');

-- M19-0b 备份待关闭的黑白/偏色告警与工单（69 条）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_water_alert_bak_20261020 AS
SELECT id, site, device, content, status, updated_at
FROM "qixiao-apaas".t_auto_hltgq_water_alert
WHERE (content LIKE '%视频 黑白图像！' OR content LIKE '%视频 图像偏色！')
  AND status IS DISTINCT FROM '#4#';

CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_water_work_order_bak_20261020 AS
SELECT id, alert, site, device, content, status, updated_at
FROM "qixiao-apaas".t_auto_hltgq_water_work_order
WHERE (content LIKE '%视频 黑白图像！' OR content LIKE '%视频 图像偏色！') AND status = '#1#';

-- M19-1 纯黑白/偏色 → ok（保留类型作为提示，25,629 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET result = 'ok'
WHERE result = 'fail'
  AND fault_types IN ('GRAYSCALE', 'COLOR_CAST')
  AND (round_time::time < time '18:30:00' AND round_time::time >= time '07:30:00');

-- M19-2 混合故障剔除黑白/偏色（保留真实故障，1,574 行）
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'NOISE'
WHERE result = 'fail' AND fault_types IN ('GRAYSCALE,NOISE', 'COLOR_CAST,NOISE')
  AND (round_time::time < time '18:30:00' AND round_time::time >= time '07:30:00');

UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'LOW_CONTRAST'
WHERE result = 'fail' AND fault_types IN ('GRAYSCALE,LOW_CONTRAST', 'COLOR_CAST,LOW_CONTRAST')
  AND (round_time::time < time '18:30:00' AND round_time::time >= time '07:30:00');

-- 白天过暗保留：TOO_DARK,GRAYSCALE → TOO_DARK；TOO_DARK,GRAYSCALE,LOW_CONTRAST → TOO_DARK,LOW_CONTRAST
UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'TOO_DARK'
WHERE result = 'fail' AND fault_types = 'TOO_DARK,GRAYSCALE'
  AND (round_time::time < time '18:30:00' AND round_time::time >= time '07:30:00');

UPDATE "qixiao-apaas".t_auto_hltgq_water_video_patrol
SET fault_types = 'TOO_DARK,LOW_CONTRAST'
WHERE result = 'fail' AND fault_types = 'TOO_DARK,GRAYSCALE,LOW_CONTRAST'
  AND (round_time::time < time '18:30:00' AND round_time::time >= time '07:30:00');

-- M19-3 关闭黑白/偏色的存量告警与待处理工单（告警 status #4#，工单 #3#）
UPDATE "qixiao-apaas".t_auto_hltgq_water_alert
SET status = '#4#', updated_at = LOCALTIMESTAMP, updated_by = 'SYSTEM'
WHERE (content LIKE '%视频 黑白图像！' OR content LIKE '%视频 图像偏色！')
  AND status IS DISTINCT FROM '#4#';

UPDATE "qixiao-apaas".t_auto_hltgq_water_work_order
SET status = '#3#', updated_at = LOCALTIMESTAMP, updated_by = 'SYSTEM'
WHERE (content LIKE '%视频 黑白图像！' OR content LIKE '%视频 图像偏色！') AND status = '#1#';

-- M19-4 核验
-- ① 备份行数，期望 巡检27203 / 告警69 / 工单69
SELECT (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_video_patrol_bak_20261020b) AS patrol_bak,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert_bak_20261020) AS alert_bak,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order_bak_20261020) AS work_order_bak;

-- ② 白天：残留黑白/偏色 0，fail 期望 35965，ok 期望 121414；夜间不变
SELECT CASE WHEN (round_time::time >= time '18:30:00' OR round_time::time < time '07:30:00')
            THEN 'night' ELSE 'day' END AS period,
       count(*) FILTER (WHERE result = 'fail') AS fail_cnt,
       count(*) FILTER (WHERE result = 'ok') AS ok_cnt,
       count(*) FILTER (WHERE result = 'fail'
                        AND (fault_types LIKE '%GRAYSCALE%' OR fault_types LIKE '%COLOR_CAST%')) AS notice_left
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
GROUP BY period ORDER BY period;

-- ③ 告警/工单：未关闭黑白偏色应为 0
SELECT (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert
        WHERE (content LIKE '%视频 黑白图像！' OR content LIKE '%视频 图像偏色！')
          AND status IS DISTINCT FROM '#4#') AS alert_open_left,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order
        WHERE (content LIKE '%视频 黑白图像！' OR content LIKE '%视频 图像偏色！')
          AND status = '#1#') AS work_order_open_left;

-- ④ 白天失败类型分布（应只剩真实故障）
SELECT fault_types, count(*) AS cnt
FROM "qixiao-apaas".t_auto_hltgq_water_video_patrol
WHERE result = 'fail'
  AND (round_time::time < time '18:30:00' AND round_time::time >= time '07:30:00')
GROUP BY fault_types ORDER BY cnt DESC;
