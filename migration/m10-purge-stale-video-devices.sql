-- ============================================================
-- m10 视频设备残留清理（简版：备份 + 删除）
-- ============================================================
-- 现状（2026-10-20 已核对）：4 张备份/清单表均已建好且行数正确
--   device_bak 25 / purge_ids 25 / alert_bak 100 / order_bak 100
--   设备、告警、工单均未删除。第 1 节已执行过，重跑会自动跳过。
--
-- 执行顺序：工单 → 告警 → 设备（工单引用告警/设备，设备最后删）
-- 执行后期望：工单 100、告警 100、设备 25；视频设备总数 225 → 200
-- 执行后必须重启 hltgq-device（deviceCache 是 code→deviceId 永久缓存）
-- 注：第 1 节若仍报错（库不支持 IF NOT EXISTS），直接跳过——备份表已建好。
-- ============================================================


-- ============================================================
-- 第 1 节 备份（已执行；表已存在则自动跳过）
-- ============================================================

-- 1.1 设备整行备份（25 行）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_water_device_bak_20261020 AS
SELECT *
FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE type LIKE '%#5#%'
  AND code IN (
    '1000271$1$0$0','1000273$1$0$0','1000303$1$0$0','1000304$1$0$0','1000324$1$0$0',
    '1000328$1$0$0','1000328$1$0$1','1000328$1$0$2','1000328$1$0$3','1000328$1$0$4',
    '1000328$1$0$5','1000328$1$0$6','1000328$1$0$7','1000328$1$0$8','1000328$1$0$9',
    '1000328$1$0$10','1000328$1$0$11','1000328$1$0$12','1000328$1$0$13','1000328$1$0$14',
    '1000328$1$0$15','1000330$1$0$0','1000334$1$0$0','1000346$1$0$0','1000347$1$0$0'
  );

-- 1.2 待删设备 id 清单（25 行，后续删除统一引用本表，勿删）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_purge_video_ids_20261020 AS
SELECT id, code, name, site, status
FROM "qixiao-apaas".t_auto_hltgq_water_device_bak_20261020;

-- 1.3 告警整行备份（100 行）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_water_alert_bak_20261020 AS
SELECT a.*
FROM "qixiao-apaas".t_auto_hltgq_water_alert a
JOIN "qixiao-apaas".t_auto_hltgq_purge_video_ids_20261020 t ON a.device = t.id;

-- 1.4 工单整行备份（100 行）
CREATE TABLE IF NOT EXISTS "qixiao-apaas".t_auto_hltgq_water_work_order_bak_20261020 AS
SELECT w.*
FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
JOIN "qixiao-apaas".t_auto_hltgq_purge_video_ids_20261020 t ON w.device = t.id;


-- ============================================================
-- 第 2 节 删除（按顺序执行）
-- ============================================================

-- 2.1 删除工单（期望 DELETE 100）
DELETE FROM "qixiao-apaas".t_auto_hltgq_water_work_order
WHERE device IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_purge_video_ids_20261020);

-- 2.2 删除告警（期望 DELETE 100）
DELETE FROM "qixiao-apaas".t_auto_hltgq_water_alert
WHERE device IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_purge_video_ids_20261020);

-- 2.3 删除设备（期望 DELETE 25；叠加类型/离线守卫，防误删）
DELETE FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE id IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_purge_video_ids_20261020)
  AND type LIKE '%#5#%'
  AND status = '#2#';
