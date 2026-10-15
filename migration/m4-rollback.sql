-- =====================================================================
-- m4-rollback.sql · 回退脚本（按映射反向执行）
-- 依据：评审说明 11.5.2（回退设计）/ 11.5.3（失败处理）
-- 性质：写操作（UPDATE）。使用场景：
--   ① 迁移中断/失败 → 全量回退（各节幂等，可重复执行）；
--   ② 单批回退 → 各节 WHERE 追加 id 名单限定（灰度名单见各节注释）；
--   ③ 设备 site 回写异常 → 先跑 m3 观察节确认范围，再按需回退。
-- 推荐执行序：C（告警）→ D（工单）→ B（设备）→ A（站点）→ E（zebpsu 原值）
--   ——先把告警/工单搬回旧行，再回退设备与站点，最后恢复旧行状态值；
--   各节守卫均以"当前值 = 迁移后新值"为准，重跑不叠加、不误伤新数据。
-- 执行纪律：BEGIN → 单节 UPDATE → 核对行数 → COMMIT；全部完成后跑文末核对。
-- 本库实测坑：拼接一律 concat()；否定式判定一律 COALESCE(列,'') 包裹。
-- =====================================================================


-- =====================================================================
-- 节 C · 告警 site 还原（新值 → 旧值）
--   映射来源：告警 device → 设备 code → 旧视频行（devicecode 匹配、epjutj='#5#' 纯）
--   守卫：旧行.ahieto = 告警当前 site（即当前指向迁移目标站才回退）
--   单批限定示例（灰度）：AND a.site IN ('CYXjx0vlVQlWPVAZTEf', 'mqG9dLawRteYWHgjqFG')
--   注：迁移窗口内（新代码未启动）执行最精确；新代码启动后其新增告警
--       同样按 device 反查回旧行（对旧版代码兼容，属预期行为）。
-- =====================================================================
UPDATE "qixiao-apaas".t_auto_hltgq_water_alert a
SET site = (SELECT v.id
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            JOIN "qixiao-apaas".t_auto_hltgq_water_device d ON d.code = v.devicecode
            WHERE d.id = a.device AND v.epjutj = '#5#' AND v.ahieto = a.site),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              JOIN "qixiao-apaas".t_auto_hltgq_water_device d ON d.code = v.devicecode
              WHERE d.id = a.device AND v.epjutj = '#5#' AND v.ahieto = a.site);
-- 【期望】= 迁移时 m1-⑤ 基线（09-24 快照 95；含灰度 5 条）


-- =====================================================================
-- 节 D · 工单 site 还原（与节 C 同构）
--   注：8 张测试工单未迁移（未换指），天然不在回退范围。
--   单批限定示例（灰度）：AND w.site IN ('CYXjx0vlVQlWPVAZTEf', 'mqG9dLawRteYWHgjqFG')
-- =====================================================================
UPDATE "qixiao-apaas".t_auto_hltgq_water_work_order w
SET site = (SELECT v.id
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            JOIN "qixiao-apaas".t_auto_hltgq_water_device d ON d.code = v.devicecode
            WHERE d.id = w.device AND v.epjutj = '#5#' AND v.ahieto = w.site),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              JOIN "qixiao-apaas".t_auto_hltgq_water_device d ON d.code = v.devicecode
              WHERE d.id = w.device AND v.epjutj = '#5#' AND v.ahieto = w.site);
-- 【期望】= 迁移时 m1-⑥ 基线（09-24 快照 95）


-- =====================================================================
-- 节 B · 设备 site 还原（目标站 → 旧行）
--   映射来源：设备 code → 旧视频行（devicecode 匹配、epjutj='#5#' 纯）
--   守卫：旧行.ahieto = 设备当前 site（即当前挂在迁移目标站才回退）
--   单批限定示例（灰度）：AND d.code IN ('1000219$1$0$0', ..., '1000219$1$0$5')
--   说明：devicecode 交叉污染 2 例（1000328$1$0$0 / 1000230$1$0$11）的多行
--         均为非 #5# 类型，被 epjutj='#5#' 过滤天然规避；其余 code 与旧行严格 1:1。
-- =====================================================================
UPDATE "qixiao-apaas".t_auto_hltgq_water_device d
SET site = (SELECT v.id
            FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
            WHERE v.devicecode = d.code AND v.epjutj = '#5#' AND v.ahieto = d.site),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE d.type LIKE '%#5#%'
  AND EXISTS (SELECT 1
              FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
              WHERE v.devicecode = d.code AND v.epjutj = '#5#' AND v.ahieto = d.site);
-- 【期望】= 迁移时 m1-④ 基线（09-24 快照 175；含灰度 6 台）


-- =====================================================================
-- 节 A · 站点 epjutj 去尾部 |#5#（新值 → 旧值）
--   守卫：epjutj LIKE '%|#5#'（仅去"尾部追加"的迁移后缀；
--   旧行纯 '#5#' 不含 '|' 不受影响；前置实测无历史复合 #5# 行）
--   单批限定示例（灰度）：AND s.id IN ('CYXjx0vlVQlWPVAZTEf', 'mqG9dLawRteYWHgjqFG')
-- =====================================================================
UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET epjutj = left(s.epjutj, length(s.epjutj) - 4),
    updated_at = now(),
    updated_by = 'SYSTEM'
WHERE s.epjutj LIKE '%|#5#';
-- 【期望】= 迁移时动作 1 累计行数（122；含灰度 2 站）


-- =====================================================================
-- 节 E · 旧行 zebpsu 恢复原值（CSV 驱动，非动态）
--   原值 = 各旧行迁移前 zebpsu（快照 143 行 #1# / 32 行 #2#），
--   以回退基线 CSV（dry-run/dryrun-sites.csv，列 video_site_id/video_zebpsu）为准。
--   生成方法（PowerShell，在仓库根执行）：
--     Import-Csv dry-run\dryrun-sites.csv | ForEach-Object {
--       "UPDATE `"qixiao-apaas`".t_auto_hltgq_5nw74_vnqqef SET zebpsu = '$($_.video_zebpsu)', " +
--       "updated_at = now(), updated_by = 'SYSTEM' WHERE id = '$($_.video_site_id)' " +
--       "AND epjutj = '#5#' AND zebpsu IS DISTINCT FROM '$($_.video_zebpsu)';"
--     } | Out-File migration\m4e-zebpsu-restore.sql -Encoding utf8
--   然后逐条执行生成的 m4e-zebpsu-restore.sql（每条幂等：原值相同则 0 行）。
-- ---------------------------------------------------------------------
-- 模板示例（单行形式，生成脚本会输出全部 175 条）：
-- UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef SET zebpsu = '#1#',
--   updated_at = now(), updated_by = 'SYSTEM'
--   WHERE id = '<旧行id>' AND epjutj = '#5#' AND zebpsu IS DISTINCT FROM '#1#';
-- 【期望】合计命中 = 迁移时动作 5 行数（143；灰度 6 行原值均 #1#）


-- =====================================================================
-- 回退完成后核对（逐条）：
--   1) 站点无 |#5# 结尾行：
--      SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef WHERE epjutj LIKE '%|#5#';  -- 0
--   2) 设备全部指向纯 #5# 旧行（175）：
--      SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_device d WHERE d.type LIKE '%#5#%'
--        AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef v
--                        WHERE v.id = d.site AND v.epjutj = '#5#');  -- 0
--   3) 退役行动态集合 = 0（设备已回到旧行、重新计入活跃）：
--      SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
--        WHERE s.epjutj = '#5#' AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d
--          WHERE d.site = s.id AND d.type LIKE '%#5#%');  -- 0
--   4) 站点表总行数 368、设备表 357、视频设备 175（不变量）
--   5) 站点含 #5# 行数回到 175；告警 / 工单 site 回到旧行（数量 = 各自迁移基线）
-- =====================================================================
