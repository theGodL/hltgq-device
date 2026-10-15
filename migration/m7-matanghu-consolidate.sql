-- ============================================================
-- 麻塘湖设备归位 · 假兜底站退场（m7）
-- ============================================================
-- 性质：写操作（站点类型变更 + 设备/告警/工单改挂迁移 + 假站点行物理删除）。
--       承接 m6（34 个兜底站去噪改名，已执行核查通过）后的进一步归位：
--       这批"一站一设备"的站名本质是设备（序列号/点位小名），不应作为站点存在；
--       业务已建归宿站"麻塘湖灌区"（id=OOH2pADzfwQioCb5qAF，2026-10-15 档案新增），
--       本脚本把 34 台设备与历史告警/工单引用全部归位其下，并删除 34 个假站点行。
--
-- 为什么必须先改归宿站类型（第 1 节）：
--   业务新建的"麻塘湖灌区"epjutj='#5#'（纯视频）+ 空 devicecode + 空上级，
--   按同步服务 SiteRow 判定这就是"兜底站"形态：设备挂过去会被每轮空转重试；
--   且 ICC 组织名恰为"麻塘湖灌区"，同名纯#5#行的空上级会令业务站匹配返回空串，
--   后续新通道设备将被写成空站点（悬空）。改为 '#7#|#5#' 后它是业务站（片区，
--   与"望江/宿松/太湖毕岭 #7#|#5#"同模式），同步服务按组织名唯一命中直接挂靠，稳定。
--
-- 数据依据（2026-10-15 只读实测 m7-precheck/m7-precheck3，执行当日以第 2 节复核为准）：
--   - 34 个假兜底站（epjutj='#5#' 且 devicecode 空）一站一设备，34 台视频设备；
--   - 引用这批站 id 的告警/工单：随轮巡持续新增，2026-10-15 上午实测 31/31、下午 32/32
--     （引用面在增长属预期，状态式谓词自动覆盖当日全量，勿锚定具体数字）/ 隐患 0 / 巡查记录 0 / 被当上级引用 0；
--   - 告警/工单 device 列实测均未塞站点 id（都指向设备表行），删站仅涉 site 列引用面，无二次断链；
--   - 34 台设备的 ICC 组织名（wlcvig 前缀）全部统一为"麻塘湖灌区"；
--   - "麻塘湖灌区"站名全表唯一（仅业务新建的 OOH2pADzfwQioCb5qAF 一行）。
--
-- 执行顺序：1 → 2 → 3 → 4 → 5 → 6，不可乱序（第 5 节删除守卫依赖 2/3/4 已迁完）。
-- 幂等性：全部语句状态式谓词，可安全重跑（重跑命中 0 行）；
--         执行间隙整点同步自动改挂属预期行为，结果与本脚本一致，不冲突。
-- 风险声明：第 5 节为物理 DELETE（m5 同类先例），执行前第 0 节备份必须留档；
--           id 是告警/工单唯一断链风险面，迁移后本侧四表引用零悬空。
-- 本文件未经执行验证（只读通道不具写权限），实际行数以执行时点实测为准。
-- ============================================================


-- ============================================================
-- 第 0 节 备份导出（只读；执行当日导出并留档）
-- ============================================================

-- 0.1 归宿站变更前形态（恢复依据：epjutj 改回 '#5#'）
SELECT id, zzkaec, epjutj, ahieto, updated_at
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE id = 'OOH2pADzfwQioCb5qAF';

-- 0.2 待删除假站点全行导出（恢复依据：整行回插）
SELECT s.*
FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
ORDER BY s.zzkaec;

-- 0.3 待改挂设备（id + 旧 site；恢复依据：site 改回旧值）
SELECT d.id, d.name, d.code, d.site AS old_site
FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.type LIKE '%#5#%' AND d.site IN
      (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
       WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '')
ORDER BY d.name;

-- 0.4 待迁移引用的告警/工单（id + 旧 site）
SELECT a.id, a.site AS old_site FROM "qixiao-apaas".t_auto_hltgq_water_alert a
WHERE a.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
                 WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '');
SELECT w.id, w.site AS old_site FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
WHERE w.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
                 WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '');


-- ============================================================
-- 第 1 节 归宿站类型修正：纯#5#（兜底形态）→ 片区#7#|#5#（业务站形态）
-- ============================================================

UPDATE "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
SET epjutj = '#7#|#5#',
    updated_at = CURRENT_TIMESTAMP, updated_by = 'SYSTEM'
WHERE id = 'OOH2pADzfwQioCb5qAF' AND epjutj = '#5#';
-- 【期望】UPDATE 1（重跑 0：已改型则不再命中）


-- ============================================================
-- 第 2 节 前置复核（只读；逐条与期望对照，不符即停）
-- ============================================================

-- 2.1 假兜底站数（期望 34；若同步已自动改挂部分设备则仍为 34——站行未删不变）
SELECT count(*) AS fake_sites FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '';

-- 2.2 待改挂设备数（期望 34 = 2.1 同集合；若整点同步已抢跑改挂，此数会小于 34，
--     差额应等于已挂到归宿站的设备数，见 2.5，两者相加=34 才继续）
SELECT count(*) AS devs_to_move
FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.type LIKE '%#5#%' AND d.site IN
      (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
       WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '');

-- 2.3 待迁移告警引用（期望 = 当前引用这批站的实时告警数；预演实测 32）
SELECT count(*) AS alerts_to_move FROM "qixiao-apaas".t_auto_hltgq_water_alert a
WHERE a.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
                 WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '');

-- 2.4 待迁移工单引用（期望 = 当前引用这批站的实时工单数；预演实测 32）
SELECT count(*) AS orders_to_move FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
WHERE w.site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
                 WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '');

-- 2.5 已归位归宿站的设备数（期望 0；同步抢跑时 >0，与 2.2 相加须=34）
SELECT count(*) AS devs_already_at_home
FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE type LIKE '%#5#%' AND site = 'OOH2pADzfwQioCb5qAF';

-- 2.6 站名"麻塘湖灌区"唯一性（期望 1 行；>1 即业务站匹配歧义，第 1 节改型白做，停下人工裁决）
SELECT count(*) AS name_rows FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE zzkaec = '麻塘湖灌区';


-- ============================================================
-- 第 3 节 设备改挂归宿站
-- ============================================================

UPDATE "qixiao-apaas".t_auto_hltgq_water_device
SET site = 'OOH2pADzfwQioCb5qAF',
    updated_at = CURRENT_TIMESTAMP, updated_by = 'SYSTEM'
WHERE type LIKE '%#5#%' AND site IN
      (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
       WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '');
-- 【期望】UPDATE 34（2.2 实测值；同步抢跑时为差额部分）


-- ============================================================
-- 第 4 节 历史告警/工单站点引用迁移（id 引用面断链的唯一处理）
-- ============================================================

UPDATE "qixiao-apaas".t_auto_hltgq_water_alert
SET site = 'OOH2pADzfwQioCb5qAF',
    updated_at = CURRENT_TIMESTAMP, updated_by = 'SYSTEM'
WHERE site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
               WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '');
-- 【期望】UPDATE = 2.3 实测值（预演 32；告警随轮巡增长，以当日 2.3 为准）

UPDATE "qixiao-apaas".t_auto_hltgq_water_work_order
SET site = 'OOH2pADzfwQioCb5qAF',
    updated_at = CURRENT_TIMESTAMP, updated_by = 'SYSTEM'
WHERE site IN (SELECT id FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
               WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '');
-- 【期望】UPDATE = 2.4 实测值（预演 32；工单随告警增长，以当日 2.4 为准）


-- ============================================================
-- 第 5 节 删除假站点行（物理删除，守卫依赖第 3/4 节已完成）
-- 谓词不含站名形态假设（m6 已去噪，不能再按噪声尾巴圈定），改用三态定义 +
-- 四重"无引用"守卫：无设备/无告警工单隐患引用/未被当上级/非归宿站
-- ============================================================

DELETE FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s
WHERE s.epjutj = '#5#' AND COALESCE(s.devicecode, '') = ''
  AND s.id <> 'OOH2pADzfwQioCb5qAF'
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_device d WHERE d.site = s.id)
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_alert a WHERE a.site = s.id)
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_water_work_order w WHERE w.site = s.id)
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h WHERE h.site = s.id)
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef c WHERE c.ahieto = s.id);
-- 【期望】DELETE 34（若执行间隙同步新建了其他空兜底站，行数可能 >34，属同类退场，可接受）


-- ============================================================
-- 第 6 节 改后核对（只读）
-- ============================================================

-- 6.1 归宿站形态（期望 epjutj='#7#|#5#'、站名唯一）
SELECT id, zzkaec, epjutj FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE zzkaec = '麻塘湖灌区';

-- 6.2 归宿站设备数（期望 34；一站多设备终态）
SELECT count(*) AS devices_at_home FROM "qixiao-apaas".t_auto_hltgq_water_device
WHERE type LIKE '%#5#%' AND site = 'OOH2pADzfwQioCb5qAF';

-- 6.3 残留空兜底站（期望 0；若 >0 为执行间隙同步新建，查名称另行归位）
SELECT count(*) AS residual_fallback FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef
WHERE epjutj = '#5#' AND COALESCE(devicecode, '') = '';

-- 6.4 设备悬空站点引用（期望 0：site 非空但站点行不存在）
SELECT count(*) AS dangling_dev_site
FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.type LIKE '%#5#%' AND COALESCE(d.site, '') <> ''
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = d.site);

-- 6.5 告警/工单悬空站点引用（期望 0 / 0）
SELECT (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_alert a
        WHERE COALESCE(a.site, '') <> '' AND NOT EXISTS
        (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = a.site)) AS alert_dangling,
       (SELECT count(*) FROM "qixiao-apaas".t_auto_hltgq_water_work_order w
        WHERE COALESCE(w.site, '') <> '' AND NOT EXISTS
        (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = w.site)) AS order_dangling;

-- 6.6 归位全景抽查（眼检：设备在归宿站下、名称可区分）
SELECT d.name AS device_name, d.code, d.status, d.wlcvig AS location
FROM "qixiao-apaas".t_auto_hltgq_water_device d
WHERE d.site = 'OOH2pADzfwQioCb5qAF' ORDER BY d.name;
