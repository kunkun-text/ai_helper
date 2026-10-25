-- ============================================================================
-- 2026-10-25 · 通用材料裁定（material verdict）
--
-- 背景：报告评析此前靠「五维点评里的切题行」判断材料对不对，3B 看到技术名词就放行；
-- 之后补过一版「简历特征词表」，但那只是针对一种文件的特殊对待——
-- 换成合同、说明书、小说、课程作业同样会漏。
--
-- 现在改为一次独立的模型裁定（详见 MaterialVerdictService），结果落这三列，
-- 列表/详情直接读列，不再解析成绩单文本、也不再维护任何文件类型词表。
--
-- 取值：
--   ok                  = 本课题的答辩材料，正常进入五维评分
--   suspect_offtopic    = 是答辩/论文类材料，但讲的是别的课题（跑题，可云端复审）
--   suspect_nonmaterial = 与答辩无关的文件（简历/合同/说明书/小说…），不出分，待复审
--   insufficient        = 几乎没有可评内容（空白 / 只有封面目录 / 通篇图片）
--   NULL                = 未裁定（历史场次）
--
-- 执行：mysql -uroot -p ai_helper < docs/ddl_material_verdict.sql
-- ============================================================================

ALTER TABLE defense_records
    ADD COLUMN material_verdict VARCHAR(32) DEFAULT NULL
        COMMENT '材料资格裁定：ok/suspect_offtopic/suspect_nonmaterial/insufficient；NULL=未裁定'
        AFTER report_doc_score,
    ADD COLUMN material_verdict_type VARCHAR(50) DEFAULT NULL
        COMMENT '模型判定的材料类型（答辩报告/个人简历/合同/说明书…），供教师核对'
        AFTER material_verdict,
    ADD COLUMN material_verdict_reason VARCHAR(500) DEFAULT NULL
        COMMENT '裁定理由（模型给出，约 40 字）'
        AFTER material_verdict_type;
