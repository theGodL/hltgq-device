-- ============================================================
-- 兜底站存量治理 · 站名/设备名去噪改名（m6）
-- ============================================================
-- 性质：写操作（UPDATE，仅改名称展示字段，不改 id/code/site 等任何关联键）。
--       配套代码治理（兜底站基名 NVR 归并去噪 + 设备名防叠词，2026-10-15）处理增量，
--       本脚本处理存量：m5 之后同步服务按旧规则自建的 34 个设备式站名兜底站
--       （"五圩支渠摄像机_1"、"CCOCD6CPAN3507C_1" 等）与 34 台叠词设备名
--       （"…摄像机_1摄像机#"）。
--
-- 数据依据（2026-10-15 只读实测 site-fix-dryrun）：
--   - 34 行兜底站（epjutj='#5#' 且 devicecode 空）全部一站一设备；
--   - 去噪基名（剥除尾部 "_数字"/硬盘录像机/录像机/摄像机，与 Java TRAIL_NAME_NOISE 同规则）
--     内部零撞名、与站点表其他行零撞名；
--   - 34 台设备现名全部 = 旧站名 + "摄像机#"（叠词形式）；
--   - 告警 31 条 / 工单 31 条引用这批站，引用的都是 id —— 本脚本不改 id，无断链；
--     巡检按设备 code 引用 —— 不改 code，无断链。
--
-- 处置口径：
--   - 只改名，不删除、不改挂。兜底站机制保留：业务在档案补建同名站点后，
--     同步服务下一轮自动将这些设备改挂业务站（站行退场另行人工处置）；
--   - 23 路序列号站（CCOCD/CC0CD…）剥 "_1" 后仍是序列号 —— ICC 通道名本身是序列号，
--     数据库侧无法凭空起名，正解是档案补站自动改挂（见第 5 节待办清单）；
--   - 若执行前缀行已被同步服务改挂业务站，自动出集，各节期望行数只减不增，
--     以执行当日第 1 节实测为准。
--
-- 执行前提：
--   1. 第 0 节备份已导出留档（反向恢复 = 按 id 逐行 UPDATE 回 old_name）；
--   2. 第 1 节校验与期望一致（34 / 0 / 0 / 34）。
--
-- 执行方式：标准 SQL 客户端单条顺序执行；全部语句幂等，可重跑（重跑命中 0 行）。
-- 执行顺序：2（设备名）→ 3（站名）→ 4 核对。顺序不可颠倒：
--           第 2 节的新设备名以第 3 节变更前的旧站名为计算依据。
-- 注：updated_by 沿用 'SYSTEM'（与程序写入一致，避免站点页显示未知账号），
--     人工改名以执行时间窗鉴别。
-- 本文件未经执行验证（只读通道不具写权限），实际行数以执行时点实测为准。
-- ============================================================


-- ============================================================
-- 第 0 节 备份导出（只读；执行当日导出并留档）
-- ============================================================

-- 0.1 待改名兜底站（id + 旧名，恢复主依据）
SELECT id, zzkaec AS old_name, created_at
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = ''
  AND zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$'
ORDER BY zzkaec;

-- 0.2 待改名设备（id + 旧名 + code，恢复主依据）
SELECT d.id, d.name AS old_name, d.code
FROM "qixiao-apaas".t_auto_hltgq_water_device d
JOIN "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s ON d.site = s.id
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND s.zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$'
  AND d.type LIKE '%#5#%'
ORDER BY d.name;


-- ============================================================
-- 第 1 节 前置校验（只读；逐条与期望对照，不符即停）
-- ============================================================

-- 1.1 待改名兜底站数（期望 34）
SELECT count(*) AS sites_to_fix
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = ''
  AND zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$';

-- 1.2 去噪基名内部撞名（期望 0 行；有行则该基名相关站跳过改名，人工裁决后单独处理）
SELECT nn AS new_name, count(*) AS cnt
FROM (SELECT trim(regexp_replace(zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')) AS nn
      FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
      WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = ''
        AND zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$') t
GROUP BY nn HAVING count(*) > 1;

-- 1.3 去噪基名与表中其他行撞名（期望 0 行；有行同理，第 2/3 节的 NOT EXISTS 守卫会自动跳过这些行）
SELECT DISTINCT trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')) AS new_name,
       o.id AS conflict_id, o.zzkaec AS conflict_name, COALESCE(o.epjutj, '<NULL>') AS conflict_type
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
JOIN "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef o
  ON o.zzkaec = trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', ''))
 AND o.id <> s.id
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND s.zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$';

-- 1.4 待改名设备数（期望 34，与 1.1 同集合）
SELECT count(*) AS devs_to_fix
FROM "qixiao-apaas".t_auto_hltgq_water_device d
JOIN "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s ON d.site = s.id
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND s.zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$'
  AND d.type LIKE '%#5#%';


-- ============================================================
-- 第 2 节 设备名去叠词（必须先于第 3 节执行）
-- 新设备名 = 站名去噪基名 + "摄像机#"；基名已以"摄像机"结尾时仅追加"#"
-- （与 DeviceTableService.deviceNameOf 防叠词规则一致；匹配键 code 不变）
-- ============================================================

UPDATE "qixiao-apaas".t_auto_hltgq_water_device d
SET name = CASE
              WHEN trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')) LIKE '%摄像机'
                THEN concat(trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')), '#')
              ELSE concat(trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')), '摄像机#')
            END,
    updated_at = CURRENT_TIMESTAMP, updated_by = 'SYSTEM'
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE d.site = s.id
  AND s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND s.zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$'
  AND d.type LIKE '%#5#%'
  AND d.name <> CASE
              WHEN trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')) LIKE '%摄像机'
                THEN concat(trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')), '#')
              ELSE concat(trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')), '摄像机#')
            END;
-- 【期望】UPDATE 34（执行当日以 1.4 实测为准）


-- ============================================================
-- 第 3 节 兜底站站名去噪
-- 守卫：仅纯#5#无 devicecode 行（兜底站）、确带噪声尾巴、
--       去噪结果与其他站名不冲突（冲突行跳过，等人工裁决）
-- ============================================================

UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
SET zzkaec = trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')),
    updated_at = CURRENT_TIMESTAMP, updated_by = 'SYSTEM'
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND s.zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$'
  AND trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', '')) <> ''
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef o
                  WHERE o.zzkaec = trim(regexp_replace(s.zzkaec, '(_[0-9]+|硬盘录像机|录像机|摄像机)+$', ''))
                    AND o.id <> s.id);
-- 【期望】UPDATE 34（1.2/1.3 均 0 行时；有撞名则相应减少）


-- ============================================================
-- 第 4 节 改后核对（只读）
-- ============================================================

-- 4.1 残留噪声尾巴兜底站（期望 0；如 >0 为撞名跳过行，查 1.3 名单人工裁决）
SELECT count(*) AS residual
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = ''
  AND zzkaec ~ '(_[0-9]+|硬盘录像机|录像机|摄像机)$';

-- 4.2 残留叠词设备名（期望 0）
SELECT count(*) AS residual_dev
FROM "qixiao-apaas".t_auto_hltgq_water_device d
JOIN "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s ON d.site = s.id
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND d.type LIKE '%#5#%' AND d.name ~ '_[0-9]+摄像机#$';

-- 4.3 设备名与新站名一致性（期望 0：设备名 = deviceNameOf(站名) 规则回验）
SELECT count(*) AS mismatch
FROM "qixiao-apaas".t_auto_hltgq_water_device d
JOIN "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s ON d.site = s.id
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND d.type LIKE '%#5#%'
  AND d.name <> CASE WHEN s.zzkaec LIKE '%摄像机'
                     THEN concat(s.zzkaec, '#')
                     ELSE concat(s.zzkaec, '摄像机#') END;


-- ============================================================
-- 第 5 节 （待办清单，本脚本不处理）序列号站归位
-- 23 路 ICC 通道名本身是摄像机序列号，改名无法解决"站点≠设备"问题。
-- 正解：业务在档案补建站点（名称 = ICC 里该 NVR/通道应属的地名），
--       同步服务每小时整点自动将设备改挂业务站，兜底站行成为无设备空行后退场。
-- 下面是当前仍挂序列号站的设备清单（供档案补站对照）：
-- ============================================================

SELECT s.zzkaec AS fallback_site, d.code AS devicecode, d.name AS device_name,
       d.wlcvig AS location, d.status
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
JOIN "qixiao-apaas".t_auto_hltgq_water_device d ON d.site = s.id
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND s.zzkaec ~ '^CC[O0]CD'
ORDER BY s.zzkaec;
