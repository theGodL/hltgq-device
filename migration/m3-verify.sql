-- =====================================================================
-- m3-verify.sql · 迁移对账（只读，全部 SELECT）
-- 依据：评审说明 11.5.4（①-⑦）；另补"悬空设备"加固与"部署后观察"节
-- 使用时机：
--   ① 灰度节执行后 → 跑「二、灰度子集节」逐条核对；
--   ② 全量节执行后 → 跑「一、全量节」逐条与【期望】比对；
--   ③ 新代码部署、首个整点轮次后 → 跑「三、部署后观察节」。
-- 本库实测坑：否定式判定一律 COALESCE(列,'') 包裹（NULL 行会被整组误排除）。
-- =====================================================================


-- =====================================================================
-- 一、全量节（迁移完成后逐条核对）
-- =====================================================================

-- ① 视频设备全部挂在含 #5# 的站下（预设 0 例外；NULL 判定已 COALESCE 包裹）
SELECT count(*) AS devices_on_nonvideo_site
FROM "qixiao-apaas".t_auto_hltgq_water_device d
JOIN "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s ON s.id = d.site
WHERE d.type LIKE '%#5#%' AND COALESCE(s.epjutj, '') NOT LIKE '%#5#%';
-- 【期望】0

-- ①+ 悬空加固：视频设备 site 指向不存在的站（迁移异常征兆）
SELECT count(*) AS dangling_video_devices
FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.type LIKE '%#5#%'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = d.site);
-- 【期望】0（>0 说明设备 site 被写坏，立即回退排查）

-- ② 数量不变：设备表 357、视频设备 175
SELECT count(*) AS device_total FROM "qixiao-apaas".t_auto_hltgq_water_device;
-- 【期望】357
SELECT count(*) AS video_device_total FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE type LIKE '%#5#%';
-- 【期望】175

-- ③ 站点表含 #5# 行 = 297（175 旧行 + 122 目标站）；尾部追加行 = 122（对齐 mq 10.3 约定）
SELECT count(*) AS site_with_5 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj LIKE '%#5#%';
-- 【期望】297（迁移前 175）
SELECT count(*) AS site_tail_appended FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj LIKE '%|#5#';
-- 【期望】122

-- ④ 退役行动态集合 = 175（8.2-3 基线：epjutj='#5#' 且无 #5# 设备指向）
SELECT count(*) AS retired_lines FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.epjutj = '#5#' AND NOT EXISTS
  (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
   WHERE d.site = s.id AND d.type LIKE '%#5#%');
-- 【期望】175（迁移前 0；若业务方选僵尸"剔除"则 172）

-- ④+ 退役行 zebpsu 全 #2#（B3 定案后口径）
SELECT count(*) AS retired_not_offline FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.epjutj = '#5#' AND s.zebpsu IS DISTINCT FROM '#2#'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                  WHERE d.site = s.id AND d.type LIKE '%#5#%');
-- 【期望】0

-- ⑤ 告警 / 工单不再指向退役旧行（退役行 = ④ 动态集合）
SELECT count(*) AS alerts_on_retired FROM "qixiao-apaas".t_auto_hltgq_water_alert a
WHERE a.site IN (SELECT s.id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
                 WHERE s.epjutj = '#5#' AND NOT EXISTS
                   (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                    WHERE d.site = s.id AND d.type LIKE '%#5#%'));
-- 【期望】0
SELECT count(*) AS orders_on_retired FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
WHERE w.site IN (SELECT s.id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
                 WHERE s.epjutj = '#5#' AND NOT EXISTS
                   (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                    WHERE d.site = s.id AND d.type LIKE '%#5#%'));
-- 【期望】8（= 测试工单清单不换指、留旧行待业务清理；清理后 0。剔除分支同样为 8）
-- 附：逐条核对 8 张留旧行（与 m1-⑥ 清单逐条比对）
SELECT w.id, w.code, w.status, w.title FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
WHERE w.site IN (SELECT s.id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
                 WHERE s.epjutj = '#5#' AND NOT EXISTS
                   (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                    WHERE d.site = s.id AND d.type LIKE '%#5#%'));
-- 【期望】恰 8 行（测试/垃圾数据，交业务方/平台清理）

-- ⑥ patrol-stats 参与通道数 = 175（新口径：设备表 type 含 #5# 的 code 去重）
SELECT count(*) AS patrol_channels FROM
  (SELECT code FROM "qixiao-apaas".t_auto_hltgq_water_device
   WHERE type LIKE '%#5#%' AND COALESCE(code, '') <> '' GROUP BY code) t;
-- 【期望】175（与现状一致、无数字变化；僵尸 3 台默认随口径保留；接口实测见部署后）

-- ⑦ 站点表总行数 = 368 不变（迁移不删除任何行）
SELECT count(*) AS site_total FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef;
-- 【期望】368


-- =====================================================================
-- 二、灰度子集节（灰度 6 台迁移后、全量前核对）
-- =====================================================================

-- G1 双庙湖 2 目标站已追加 |#5#
SELECT count(*) AS gray_targets_appended FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id IN ('CYXjx0vlVQlWPVAZTEf', 'mqG9dLawRteYWHgjqFG') AND epjutj LIKE '%|#5#';
-- 【期望】2

-- G2 6 台设备已指向目标站、不再指向旧行
SELECT count(*) AS gray_devices_on_target FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.code IN ('1000219$1$0$0', '1000219$1$0$1', '1000219$1$0$2',
                 '1000219$1$0$3', '1000219$1$0$4', '1000219$1$0$5')
  AND d.site IN ('CYXjx0vlVQlWPVAZTEf', 'mqG9dLawRteYWHgjqFG');
-- 【期望】6
SELECT count(*) AS gray_devices_still_on_old FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.code IN ('1000219$1$0$0', '1000219$1$0$1', '1000219$1$0$2',
                 '1000219$1$0$3', '1000219$1$0$4', '1000219$1$0$5')
  AND d.site IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
                 'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs');
-- 【期望】0

-- G3 6 旧行退役（无设备指向）+ zebpsu 全 #2#
SELECT count(*) AS gray_retired FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.id IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
               'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs')
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                  WHERE d.site = s.id AND d.type LIKE '%#5#%');
-- 【期望】6
SELECT count(*) AS gray_offline FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
             'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs')
  AND zebpsu = '#2#';
-- 【期望】6

-- G4 灰度告警 / 工单已离旧行（快照 5 条 / 5 张均全部换指）
SELECT count(*) AS gray_alerts_left FROM "qixiao-apaas".t_auto_hltgq_water_alert
WHERE site IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
               'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs');
-- 【期望】0
SELECT count(*) AS gray_orders_left FROM "qixiao-apaas".t_auto_hltgq_water_work_order
WHERE site IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
               'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs');
-- 【期望】0（灰度 5 张工单均非测试数据，全部随迁）

-- G5 灰度 6 台挂靠明细（人工核对：site=目标站、目标站 epjutj 尾部含 |#5#）
SELECT d.code, d.name AS device_name, d.site, s.zzkaec AS site_name, s.epjutj
FROM "qixiao-apaas".t_auto_hltgq_water_device d
JOIN "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s ON s.id = d.site
WHERE d.code IN ('1000219$1$0$0', '1000219$1$0$1', '1000219$1$0$2',
                 '1000219$1$0$3', '1000219$1$0$4', '1000219$1$0$5');
-- 【期望】6 行：$1$0$0 / $1$0$1 / $1$0$4 → 双庙湖节制闸（#3#|#4#|#5#）；$1$0$2 / $1$0$3 / $1$0$5 → 双庙湖管理所（#2#|#5#）（口径同 m2 报头清单）


-- =====================================================================
-- 三、部署后观察节（新代码启动、首个整点轮次后执行）
-- =====================================================================

-- O1 旧行 zebpsu 未被回写（新代码停写站点状态列）
SELECT count(*) AS zebpsu_rewritten FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.epjutj = '#5#' AND s.zebpsu IS DISTINCT FROM '#2#'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
                  WHERE d.site = s.id AND d.type LIKE '%#5#%');
-- 【期望】0（>0 说明旧逻辑仍在运行或新代码未生效，立即排查）

-- O2 视频设备未被回写旧行（设备 site 均非纯 #5# 行）
SELECT count(*) AS device_on_pure5 FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.type LIKE '%#5#%'
  AND EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
              WHERE s.id = d.site AND s.epjutj = '#5#');
-- 【期望】0

-- O3 无新增"影子行"（新代码不再按 devicecode 建 "xxx-视频" 站点行）
--    口径：epjutj='#5#' 且有 devicecode 且创建于迁移时刻之后；
--    执行时把时间替换为实际迁移完成时刻（示例值仅作模板）
SELECT count(*) AS new_shadow_rows FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#' AND COALESCE(devicecode, '') <> ''
  AND created_at > TIMESTAMP '2026-09-24 00:00:00';
-- 【期望】0（>0 说明旧同步逻辑仍在运行）

-- O4 兜底站（待挂靠）核对：epjutj='#5#' 且无 devicecode 的行 = 新代码兜底站
--    （迁移前实测纯 #5# 行全部有 devicecode = 0，故此处 >0 即新兜底站）
SELECT count(*) AS fallback_stations FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '';
-- 【期望】0（122 目标站全部命中、无待挂靠；>0 → 查服务日志"待挂靠"清单交业务方裁决，B2）

-- O5 采集统计接口抽查（人工，部署后）：
--   GET /api/dahua/video/patrol-stats（无参）→ expected 基数应为 175（= ⑥ 值 × 已结束窗数）
--   服务日志核对关键字："[站点同步]" 完成行（新增/状态更新/消失标离线计数）、"待挂靠"
-- =====================================================================
