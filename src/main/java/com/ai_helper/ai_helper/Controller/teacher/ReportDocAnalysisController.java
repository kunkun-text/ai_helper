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
}
