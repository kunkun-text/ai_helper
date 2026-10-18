package com.ai_helper.ai_helper.Service;

import java.util.Map;

/**
 * 上传报告文档的 AI 五维评析（2026-10-16 · 报告五维评分第一期）。
 *
 * <p>解决的是这样一个问题：系统一直有文件上传能力，但上传的报告**从来没有被真正分析过**——
 * {@code defense_records.report_analysis} 里的内容是答辩收尾时从模型回复里按【报告分析】
 * 截字符串得到的，没有任何真实文件内容作为输入。本服务把上传的 docx/pptx/xlsx/txt
 * 抽出全文，交给本地 3B 模型按文字答辩同一套五维打分，并落库到独立的新列。</p>
 */
public interface ReportDocAnalysisService {

    /**
     * 触发一次报告评析（教师端手动）。
     *
     * <p>同步完成前置校验（场次存在 / 已上传报告 / 格式可解析 / 未在分析中），
     * 随后提交到专用线程池异步执行，**接口立即返回**。</p>
     *
     * <p>幂等：同一份未变更的报告已分析过 → 直接返回上次结果，不重复调模型。
     * {@code force=true} 时跳过幂等检查，强制重跑（教师点「重新生成评析」；
     * 也用于给 2026-10-18 之前生成、还没有综合评价的旧场次补总评）。</p>
     *
     * @return {@code {status, score, analysis, msg}}；status 取值见 {@link #status}
     */
    Map<String, Object> trigger(Integer defenseId, boolean force);

    /**
     * 查询评析状态与结果（前端轮询用）。
     *
     * <p>status 取值：{@code not_found}（从未分析过）/ {@code PROCESSING} /
     * {@code COMPLETED} / {@code FAILED}。</p>
     *
     * <p>Redis 状态键有 24h TTL，但结果本身是**落库**的；键过期后会回查数据库，
     * 只要库里已有评析就仍然返回 COMPLETED，不会因为 Redis 过期而"丢失结果"。</p>
     */
    Map<String, Object> status(Integer defenseId);
}
