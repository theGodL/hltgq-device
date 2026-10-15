-- =====================================================================
-- m2-migrate.sql · 迁移五动作（灰度 6 台 → 全量 169 台）
-- 依据：评审说明 11.5.1（动作与幂等守卫）/ 11.5.3（分批与执行序）
-- 性质：写操作（UPDATE）。执行前提：
--   ① 旧同步服务已停（README 窗口纪律 1）；
--   ② 四张映射 CSV 已重导快照（回退基线）；
--   ③ m1-precheck.sql 全部核对通过（记录 m1-⑤/⑥ 告警与工单基线）；
--   ④ 建议按节执行：BEGIN; → 单条 UPDATE → 核对 UPDATE n → COMMIT;
--      （勿整文件批量执行；每步的数字与本文注释【期望】比对，不符即停）
-- 幂等：全部动作以 "当前值 = 旧值（用动态子查询表达 id 级映射）" 为守卫，
--   迁移中断/失败可直接重跑；各节均可独立重跑。
-- 本库实测坑：拼接一律 concat()（'||' 实测返回 false）；否定式判定一律
--   COALESCE(列,'') 包裹（NULL 行会被 NOT LIKE / <> '' 整组误排除）。
-- =====================================================================


-- =====================================================================
-- 第一部分 · 灰度节（阶段 1：双庙湖管理所 3 台 + 双庙湖节制闸 3 台）
--   覆盖 #2# 与 #3#|#4# 两类目标站、含多通道聚合场景
--   灰度名单（dry-run CSV 精确值）：
--     旧行 id / 通道code / 目标站：
--     7DfeP1a3r3x1rvQME09W / 1000219$1$0$3 / CYXjx0vlVQlWPVAZTEf（双庙湖管理所·信息化机房）
--     eHFKq6PXpbPaigoHx6xZ / 1000219$1$0$5 / CYXjx0vlVQlWPVAZTEf（双庙湖管理所·双庙湖水电站）
--     WjoitWxBZkYwpIbhwNz4 / 1000219$1$0$2 / CYXjx0vlVQlWPVAZTEf（双庙湖管理所·双庙湖闸室内）
--     ZnQKVbmZzeAT0E4glAlQ / 1000219$1$0$4 / mqG9dLawRteYWHgjqFG（双庙湖节制闸·-视频）
--     QeNZofPf4saWVqMJsdYc / 1000219$1$0$1 / mqG9dLawRteYWHgjqFG（双庙湖节制闸·上游）
--     40vmq3TCun8R9Ws5J5gs / 1000219$1$0$0 / mqG9dLawRteYWHgjqFG（双庙湖节制闸·下游）
-- =====================================================================

-- ---------------------------------------------------------------------
-- [灰度-1] 动作 1：目标站 epjutj 尾部追加 |#5#
--   双庙湖管理所： #2#      → #2#|#5#
--   双庙湖节制闸： #3#|#4#  → #3#|#4#|#5#
--   守卫：COALESCE(epjutj,'') NOT LIKE '%#5#%'（幂等；前置实测全库无复合 #5# 行）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET epjutj = CASE WHEN COALESCE(s.epjutj, '') = '' THEN '#5#' ELSE concat(s.epjutj, '|#5#') END,
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.id IN ('CYXjx0vlVQlWPVAZTEf', 'mqG9dLawRteYWHgjqFG')
  AND COALESCE(s.epjutj, '') NOT LIKE '%#5#%';
-- 【期望】UPDATE 2

-- ---------------------------------------------------------------------
-- [灰度-2] 动作 2：6 台视频设备 site 换指目标站
--   守卫（动态映射）：设备当前挂在"纯 #5# 且 ahieto 非空"的旧行下
-- ---------------------------------------------------------------------
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
-- 【期望】UPDATE 6（换指后设备 site 指向目标站，守卫不再命中，重跑幂等跳过）

-- ---------------------------------------------------------------------
-- [灰度-3] 动作 3：告警 site 换指目标站（site ∈ 6 旧行）
--   口径：site ∈ 旧视频行集合的全量告警——快照 95 行实测全部为视频巡检类，
--   与 mq 三类告警 content 零交叉（11.4-4 / 10.6-②）；如需复核先跑：
--     SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert a
--     WHERE a.site IN (6 个旧行 id) AND COALESCE(a.content,'') NOT LIKE '%视频%';
--     -- 【期望】0（>0 则先停下与三方确认口径）
-- ---------------------------------------------------------------------
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
-- 【期望】UPDATE 5（09-24 快照；灰度 6 行对应的全部告警）

-- ---------------------------------------------------------------------
-- [灰度-4] 动作 4：工单 site 换指目标站（site ∈ 6 旧行；8 张测试工单不换指）
-- ---------------------------------------------------------------------
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
-- 【期望】UPDATE 5（灰度 6 行对应工单，实测均非测试数据）

-- ---------------------------------------------------------------------
-- [灰度-5] 动作 5：旧行 zebpsu 置 #2#（退役离线留痕，B3 建议口径）
--   守卫：zebpsu IS DISTINCT FROM '#2#'（原值 #2# 的 32 行幂等跳过）
--   灰度 6 行原值均为 #1#（dry-run 快照）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET zebpsu = '#2#',
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.id IN ('7DfeP1a3r3x1rvQME09W', 'eHFKq6PXpbPaigoHx6xZ', 'WjoitWxBZkYwpIbhwNz4',
               'ZnQKVbmZzeAT0E4glAlQ', 'QeNZofPf4saWVqMJsdYc', '40vmq3TCun8R9Ws5J5gs')
  AND COALESCE(s.ahieto, '') <> ''
  AND s.zebpsu IS DISTINCT FROM '#2#';
-- 【期望】UPDATE 6

-- ===== 灰度节执行完毕 → 跑 m3-verify.sql「灰度子集节」逐条核对 =====
-- ===== → 观察 1 个同步周期（11.5.3）→ 无误后执行全量节 =====


-- =====================================================================
-- 第二部分 · 全量节（阶段 2：其余 169 台，按管理所/片区分组可再分批）
--   全量节与灰度节同构（不含灰度 id 名单限定），幂等：灰度已迁移的行自动跳过
-- =====================================================================

-- ---------------------------------------------------------------------
-- [全量-1] 动作 1：122 目标站追加 |#5#（= 175 旧行 ahieto 去重）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET epjutj = CASE WHEN COALESCE(s.epjutj, '') = '' THEN '#5#' ELSE concat(s.epjutj, '|#5#') END,
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.id IN (SELECT DISTINCT v.ahieto
               FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
               WHERE v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '')
  AND COALESCE(s.epjutj, '') NOT LIKE '%#5#%';
-- 【期望】累计 122（灰度已执行则本步 UPDATE 120；幂等重跑 0）

-- ---------------------------------------------------------------------
-- [全量-2] 动作 2：175 台视频设备 site 换指目标站
--   【僵尸剔除分支（默认不启用）】如业务方选"剔除"（设备留旧行、保持离线、不建关联），
--   本动作追加：AND d.code NOT IN ('1000324$1$0$0', '1000304$1$0$0', '1000276$1$0$0')
--   默认"随迁"：3 台僵尸设备随动作换指目标站（设备状态保持 #2#）
-- ---------------------------------------------------------------------
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
-- 【期望】累计 175（灰度已执行则本步 UPDATE 169；幂等重跑 0）

-- ---------------------------------------------------------------------
-- [全量-3] 动作 3：告警 site 换指目标站（全量）
--   执行前先跑 m1-⑤ 记录基线；本步 UPDATE n 应 = 基线值
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_water_alert a
SET site = (SELECT v.ahieto
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            WHERE v.id = a.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> ''),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.id = a.site AND v.epjutj = '#5#' AND COALESCE(v.ahieto, '') <> '');
-- 【期望】= m1-⑤ 基线（09-24 快照 95；灰度已执行则本步 = 基线 − 5）

-- ---------------------------------------------------------------------
-- [全量-4] 动作 4：工单 site 换指目标站（全量；8 张测试工单不换指）
--   8 张测试清单（业务方/平台清理；清理前永久留旧行）：递四方速 / 测试555 / 递四方速递个 /
--   问题2 / wwww / 大概是 / 巡检时添加 / 测试工单联动（id 见下）
-- ---------------------------------------------------------------------
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
-- 【期望】= m1-⑥ 基线（09-24 快照 95 = 103 − 8；灰度已执行则本步 = 基线 − 5）

-- ---------------------------------------------------------------------
-- [全量-5] 动作 5：175 旧行 zebpsu 置 #2#（全量）
--   现状：原值 #1# 143 / #2# 32（11.5.1）→ 本步命中 143（灰度已执行则 137）
--   【僵尸 3 行说明】1000324$1$0$0 / 1000304$1$0$0 / 1000276$1$0$0 通道持续缺失、
--   现值均已为 #2#（11.3-B），随迁 / 剔除两种方案下本动作均幂等跳过；
--   剔除分支仅需在动作 2 加排除（见上），本动作无需改动。
--   【边界】未来新代码兜底站（纯 #5#、无 ahieto）不命中本动作（ahieto 守卫）
-- ---------------------------------------------------------------------
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET zebpsu = '#2#',
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.epjutj = '#5#'
  AND COALESCE(s.ahieto, '') <> ''
  AND s.zebpsu IS DISTINCT FROM '#2#';
-- 【期望】143（灰度已执行则本步 137；幂等重跑 0）

-- ===== 全量节执行完毕 → 跑 m3-verify.sql「全量节」逐条核对 =====
-- ===== → 全部通过后方可启动新代码（README 窗口纪律 5）→ 部署后观察（m3 观察节） =====

-- =====================================================================
-- 附：执行记录表（回执归档用，三方核对）
-- ---------------------------------------------------------------------
-- | 动作 | 预期 | 实际 UPDATE n | 执行时间 | 执行人 | 备注 |
-- | 灰度-1 | 2 | | | | |
-- | 灰度-2 | 6 | | | | |
-- | 灰度-3 | 5 | | | | |
-- | 灰度-4 | 5 | | | | |
-- | 灰度-5 | 6 | | | | |
-- | 全量-1 | 122 累计 | | | | |
-- | 全量-2 | 175 累计 | | | | |
-- | 全量-3 | m1-⑤ 基线 | | | | |
-- | 全量-4 | m1-⑥ 基线 | | | | |
-- | 全量-5 | 143 | | | | |
-- =====================================================================
