-- =====================================================================
-- m2-执行汇总.sql · 迁移窗口执行用（灰度 + 全量两段，勿一键整跑）
-- ---------------------------------------------------------------------
-- 语句来源：m2-migrate.sql（本文件 10 条语句与其逐字一致，已程序化比对通过；
--           详细注释与背景说明以 m2-migrate.sql 为准）
-- 性质：写操作（UPDATE）。执行前提：
--   ① 旧同步服务已停（docker stop hltgq-device）；
--   ② 四张映射 CSV 回退基线快照已重导；
--   ③ m1-precheck.sql 核对通过，已拿到窗口冻结期望卡。
-- 纪律：
--   1. 单条顺序执行，逐条核对返回 UPDATE n 与【期望】，不符即停；
--   2. 可 BEGIN; 包一组，核对后 COMMIT;（金仓支持标准事务）；
--   3. 第一段（灰度）跑完必须停下对账（m3-verify.sql 二、灰度子集节 G1~G5），
--      通过后才可执行第二段（全量）；第二段跑完对账（m3-verify.sql 一、全量节 ①~⑦），
--      全部通过后才可部署启动新代码。
--   全量段期望为 09-24 复核预览（灰度=2/6/5/5/6；全量基数 122/175/99/99/141），
--   窗口执行时以停服后的冻结期望卡为准。
-- =====================================================================


-- =====================================================================
-- 第一部分 · 灰度（双庙湖 2 目标站 / 6 台设备 / 6 旧行）
-- =====================================================================

-- [灰度-1] 目标站追加 |#5#（双庙湖管理所、双庙湖节制闸）—— 期望 UPDATE 2
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET epjutj = CASE WHEN COALESCE(s.epjutj, '') = '' THEN '#5#' ELSE concat(s.epjutj, '|#5#') END,
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.id IN ('CYXjx0vlVQlWPVAZTEf', 'mqG9dLawRteYWHgjqFG')
  AND COALESCE(s.epjutj, '') NOT LIKE '%#5#%';

-- [灰度-2] 6 台设备换指（site → 旧行对应新站）—— 期望 UPDATE 6
UPDATE "qixiao-apaas".t_auto_hltgq_water_device d
SET site = (SELECT v.ahieto
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            WHERE v.id = d.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> ''),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE d.type LIKE '%#5#%'
  AND EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = d.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '')
  AND d.code IN ('1000219$1$0$0', '1000219$1$0$1', '1000219$1$0$2',
                 '1000219$1$0$3', '1000219$1$0$4', '1000219$1$0$5');

-- [灰度-3] 告警换指（6 旧行名下）—— 期望 UPDATE 5
UPDATE "qixiao-apaas".t_auto_hltgq_water_alert a
SET site = (SELECT v.ahieto
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            WHERE v.id = a.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> ''),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = a.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '')
  AND a.site IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
                 'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs');

-- [灰度-4] 工单换指（6 旧行名下；8 张测试工单不换指）—— 期望 UPDATE 5
UPDATE "qixiao-apaas".t_auto_hltgq_water_work_order w
SET site = (SELECT v.ahieto
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            WHERE v.id = w.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> ''),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = w.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '')
  AND w.site IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
                 'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs')
  AND w.id NOT IN ('RCrbBJtuBBw9RFhdNG8', 'V3ojbJ1Zk6VrFzmCRWo', 'DM7tO3VPTpolqfGRqZk',
                   '4ltUSjH23K0HVEz8DG3', '3wdgE0zoZj4hIlMBvAg', 'BFRhKpXaOxprzEc8ATw',
                   'eD1U1Ws6IscXik2QViU', 'M9NL6kxh6dPpV1Ikir4');

-- [灰度-5] 6 旧行置 #2#（挂靠已完成、换指已完成的旧行退役）—— 期望 UPDATE 6
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET zebpsu = '#2#',
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.id IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
               'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs')
  AND COALESCE(s.ahieto, '') <> ''
  AND s.zebpsu IS DISTINCT FROM '#2#';


-- =====================================================================
-- >>>>>> 停止线：此处先停 <<<<<<
-- 灰度 5 条执行完 → 记录 UPDATE n 回执 → 跑 m3-verify.sql「二、灰度子集节」G1~G5，
-- 全部通过（建议至少观察 1 个巡检周期）之前，不要执行下方全量部分。
-- =====================================================================


-- =====================================================================
-- 第二部分 · 全量（其余旧行/设备/告警/工单；灰度已迁移对象由守卫自动跳过，幂等）
-- =====================================================================

-- [全量-1] 目标站追加（全量，幂等：重跑为 0）—— 期望 UPDATE 120（=122−灰度 2）
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET epjutj = CASE WHEN COALESCE(s.epjutj, '') = '' THEN '#5#' ELSE concat(s.epjutj, '|#5#') END,
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.id IN (SELECT DISTINCT v.ahieto
               FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
               WHERE v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '')
  AND COALESCE(s.epjutj, '') NOT LIKE '%#5#%';

-- [全量-2] 设备换指（全量）—— 期望 UPDATE 169（=175−灰度 6）
UPDATE "qixiao-apaas".t_auto_hltgq_water_device d
SET site = (SELECT v.ahieto
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            WHERE v.id = d.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> ''),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE d.type LIKE '%#5#%'
  AND EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = d.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '');

-- [全量-3] 告警换指（全量）—— 期望 = 告警基线 − 5（09-24 复核预览：99−5=94）
UPDATE "qixiao-apaas".t_auto_hltgq_water_alert a
SET site = (SELECT v.ahieto
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            WHERE v.id = a.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> ''),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = a.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '');

-- [全量-4] 工单换指（全量；8 张测试工单不换指）—— 期望 = 工单基线 − 5（09-24 复核预览：99−5=94）
UPDATE "qixiao-apaas".t_auto_hltgq_water_work_order w
SET site = (SELECT v.ahieto
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            WHERE v.id = w.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> ''),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = w.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '')
  AND w.id NOT IN ('RCrbBJtuBBw9RFhdNG8', 'V3ojbJ1Zk6VrFzmCRWo', 'DM7tO3VPTpolqfGRqZk',
                   '4ltUSjH23K0HVEz8DG3', '3wdgE0zoZj4hIlMBvAg', 'BFRhKpXaOxprzEc8ATw',
                   'eD1U1Ws6IscXik2QViU', 'M9NL6kxh6dPpV1Ikir4');

-- [全量-5] 旧行置 #2#（全量退役；僵尸 3 台随迁后仍未离线属预期）—— 期望 = 未离线数 − 6（09-24 复核预览：141−6=135）
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET zebpsu = '#2#',
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.epjutj = '#5#'
  AND COALESCE(s.ahieto, '') <> ''
  AND s.zebpsu IS DISTINCT FROM '#2#';


-- =====================================================================
-- 全量段执行完毕 → m3-verify.sql「一、全量节」①~⑦ 全部通过 → 才可部署：
--   ./deploy.sh（停旧→构建→启动；窗口前可先 docker build 压缩窗口）
-- 回执登记表：见 m2-migrate.sql 末尾（三方核对用）
-- =====================================================================
