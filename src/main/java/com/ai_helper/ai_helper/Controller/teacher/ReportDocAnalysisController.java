package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.ReportDocAnalysisService;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 上传报告的 AI 评析（教师端 · 2026-10-16 · 报告五维评分第一期）。
 *
 * <p>教师端手动触发：教师打开某场答辩的「答辩详情」，看到学生上传的报告后，
 * 点按钮触发一次评析，然后轮询 {@code /status} 拿进度与结果。</p>
 *
 * <p><b>权限口径：</b>整类 {@code @RequireRole(TEACHER)}，路径在 {@code /teacher/**}
 * （已被 {@code WebConfig.PROTECTED_PATHS} 覆盖，无需改配置）。
 * 与既有「教师可查看任意场次的答辩记录/录音/审计日志」保持同一口径，故不再按课题归属过滤。</p>
 *
 * <p><b>为什么用句柄式 {@code /status} 而不是 {@code @Async} 直接返回结果：</b>
 * 一次评析要读文档 + 最多 10 次模型调用（8 次证据抽取 + 点评 + 评分），
 * 在低功耗机上可能几分钟；HTTP 连接扛不住，必须"立即返回 + 轮询"。</p>
 */
@RestController
@Slf4j
@RequestMapping("/teacher/defense/reportDoc")
@RequireRole(UserRole.TEACHER)
public class ReportDocAnalysisController {

    @Resource
    private ReportDocAnalysisService reportDocAnalysisService;

    /**
     * 触发（或幂等命中）某场答辩的报告评析。
     *
     * <p>出参 {@code {status, score, analysis, msg}}：
     * {@code status=PROCESSING} 表示已提交（含"本来就在跑"），前端应转入轮询；
     * {@code COMPLETED} 表示这份报告之前已评析过且内容未变，直接返回上次结果。</p>
     *
     * <p>前置错误（未找到场次 / 没上传报告 / 格式不支持 / 地址无效）由
     * {@code BusinessException} 经 {@code GlobalExceptionHandler} 转成带明确文案的
     * {@code Result.error}，HTTP 仍是 200（与全站口径一致）。</p>
     */
    @PostMapping("/analyze/{defenseId}")
    public Result<Map<String, Object>> analyze(
            @PathVariable Integer defenseId,
            @RequestParam(value = "force", defaultValue = "false") boolean force) {
        return Result.success(reportDocAnalysisService.trigger(defenseId, force));
    }

    /**
     * 查询评析状态与结果（前端 3 秒轮询一次）。
     *
     * <p>{@code status} 取值：{@code not_found}（从未评析）/ {@code PROCESSING}
     * / {@code COMPLETED} / {@code FAILED}。Redis 状态过期后会回查数据库，
     * 只要结果已落库就仍返回 COMPLETED。</p>
     */
    @GetMapping("/status/{defenseId}")
    public Result<Map<String, Object>> status(@PathVariable Integer defenseId) {
        return Result.success(reportDocAnalysisService.status(defenseId));
    }

    /**
     * 【2026-10-25】手动「云端复审」：用已落库的报告全文强制重跑「云端点评 → 云端评分」，
     * 覆盖本地 3B 结论（含"材料其实是简历却给了高分"这类本地判不出来的情况）。
     *
     * <p>与 {@code /analyze/{id}?force=true} 的区别：后者仍走本地 3B 主评，
     * 只有低分/跑题才会碰云端；本接口**直接走云端**，不看本地分数。</p>
     *
     * <p>同步返回 {@code {status, score, analysis, msg}}：复审要调 2~3 次云端模型
     * （点评 + 评分 + 综合评价），通常 20~60 秒。前置条件不满足（没有报告全文 /
     * 未配置云端 key / 正在评析中）会以 {@code BusinessException} 返回明确文案，
     * 库中超时或失败**不写库**，原成绩单保持不变。</p>
     */
    @PostMapping("/review/{defenseId}")
    public Result<Map<String, Object>> review(@PathVariable Integer defenseId) {
        return Result.success(reportDocAnalysisService.review(defenseId));
    }
}
