-- ============================================================
-- 清理安全隐患档案表悬空记录（m8）
-- ============================================================
-- 性质：物理 DELETE 5 行，作用于 t_auto_hltgq_knc3g_bpzjoh（安全隐患档案表）。
--       ⚠ 该表非视频站点-设备改造所辖：本视频服务代码对其零读写，属安全/档案外部模块。
--       本次清理经用户明确授权（"没有就删除"），仅删已核实的 5 条悬空记录，不扩大谓词。
--
-- 为什么删、而不是"找归属站点改挂"：
--   这 5 条 site 全部指向 hdbjWiVAa9Y3rasq2jh——该 id 在站点表/告警/工单/设备中零引用，
--   即被引用的站点已彻底不存在；且隐患内容（安全培训记录/化学品存放/尾气排放/通风系统/
--   设备运行参数）无任何地名，4 条 device 指向不存在的幽灵设备、2 条 work_order_id 指向
--   不存在的工单 → 没有任何可靠线索能推出正确归属站点，改挂路径不成立。
--   来源为 2026-09-09 同一批次（编号连号 AQ-20260909-003~008）由档案账号所建，早于本次
--   视频架构改造，非 m7 引入，属历史遗留/疑似测试数据。
--
-- 精确锚定（2026-10-15 只读实测 hazard-check/hazard-check2）：5 行 id 枚举如下，
--   yoPVMRIk6zigLPGyeMi  AQ-20260909-005  安全培训记录缺失
--   PwzO95Sfsb5RRk293Lx  AQ-20260909-004  通风系统运行异常
--   67yKGCKmFzoKFbkFdxE  AQ-20260909-003  化学品存放不规范
--   eJY7whPUnqixMlMBvaJ  AQ-20260909-007  设备运行参数偏离
--   WWIJrOrXDw6wpfYrz3E  AQ-20260909-008  尾气排放异常
--   隐患表总数 19（这 5 条悬空、1 条 site 为空、其余正常挂水质站#8#/水库等业务站）。
--
-- 幂等性：DELETE 谓词 = id 命中上述 5 个 ∩ site 仍悬空（站点行不存在），重跑命中 0 行；
--         若某行在执行间隙被业务改挂到真实站点，则自动跳出删除集，不会被误删。
-- 风险声明：外部安全表，删除前第 0 节全字段备份必须留档（可整行回插恢复）。
-- 本文件未经执行验证（只读通道不具写权限），实际行数以执行时点实测为准。
-- ============================================================


-- ============================================================
-- 第 0 节 备份导出（只读；执行当日全字段导出并留档，作整行回插依据）
-- ============================================================
SELECT h.*
FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h
WHERE h.id IN ('yoPVMRIk6zigLPGyeMi','PwzO95Sfsb5RRk293Lx','67yKGCKmFzoKFbkFdxE',
               'eJY7whPUnqixMlMBvaJ','WWIJrOrXDw6wpfYrz3E')
ORDER BY h.code;


-- ============================================================
-- 第 1 节 前置复核（只读；逐条与期望对照，不符即停）
-- ============================================================

-- 1.1 待删行数（期望 5；若 <5 说明有行已被改挂/删除，核对差额后继续）
SELECT count(*) AS to_delete FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h
WHERE h.id IN ('yoPVMRIk6zigLPGyeMi','PwzO95Sfsb5RRk293Lx','67yKGCKmFzoKFbkFdxE',
               'eJY7whPUnqixMlMBvaJ','WWIJrOrXDw6wpfYrz3E');

-- 1.2 这 5 行的 site 是否仍全部悬空（期望 5 行均 DANGLING；若出现 EXISTS 说明已改挂，须停）
SELECT h.id, h.code, h.site,
       CASE WHEN EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = h.site)
            THEN 'EXISTS' ELSE 'DANGLING' END AS site_state
FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h
WHERE h.id IN ('yoPVMRIk6zigLPGyeMi','PwzO95Sfsb5RRk293Lx','67yKGCKmFzoKFbkFdxE',
               'eJY7whPUnqixMlMBvaJ','WWIJrOrXDw6wpfYrz3E')
ORDER BY h.code;

-- 1.3 全表悬空隐患总数（期望 5；若 >5 说明另有指向其他消失站的隐患，本脚本只删上述 5 个 id，不扩大）
SELECT count(*) AS all_dangling_hazard
FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h
WHERE COALESCE(h.site, '') <> ''
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = h.site);


-- ============================================================
-- 第 2 节 删除（物理 DELETE；双重谓词：命中 5 个 id ∩ site 仍悬空）
-- ============================================================
DELETE FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h
WHERE h.id IN ('yoPVMRIk6zigLPGyeMi','PwzO95Sfsb5RRk293Lx','67yKGCKmFzoKFbkFdxE',
               'eJY7whPUnqixMlMBvaJ','WWIJrOrXDw6wpfYrz3E')
  AND COALESCE(h.site, '') <> ''
  AND NOT EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = h.site);
-- 【期望】DELETE 5（重跑 0：已删则 id 不存在；若执行间隙被改挂真实站则跳出悬空谓词不删）


-- ============================================================
-- 第 3 节 改后核对（只读）
-- ============================================================

-- 3.1 这 5 个 id 是否已不存在（期望 0）
SELECT count(*) AS still_present FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h
WHERE h.id IN ('yoPVMRIk6zigLPGyeMi','PwzO95Sfsb5RRk293Lx','67yKGCKmFzoKFbkFdxE',
               'eJY7whPUnqixMlMBvaJ','WWIJrOrXDw6wpfYrz3E');

-- 3.2 指向消失站 hdbj 的残留隐患（期望 0）
SELECT count(*) AS hdbj_remaining FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h
WHERE h.site = 'hdbjWiVAa9Y3rasq2jh';

-- 3.3 隐患表总数（期望 19 → 14；若执行时点基线非 19，则以"删 5"的净减为准）
SELECT count(*) AS hazard_total FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh;

-- 3.4 其余正常隐患未被波及抽查（期望：南干进水闸水质站 7、花凉亭灌区水库上 6 仍在）
SELECT h.site,
       (SELECT s.zzkaec FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = h.site) AS site_name,
       count(*) AS hazard_cnt
FROM "qixiao-apaas".t_auto_hltgq_knc3g_bpzjoh h
WHERE COALESCE(h.site, '') <> ''
  AND EXISTS (SELECT 1 FROM "qixiao-apaas".t_auto_hltgq_5nw74_vnqqef s WHERE s.id = h.site)
GROUP BY h.site ORDER BY hazard_cnt DESC;
