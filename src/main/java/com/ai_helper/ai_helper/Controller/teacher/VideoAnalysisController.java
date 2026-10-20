package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.VideoAnalysisService;
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
 * 答辩视频的 AI 评分（教师端 · 2026-10-20 · VID 系列）。
 *
 * <p>教师打开某场答辩的「答辩详情」，看到学生上传的视频后点按钮触发；
 * 因为一次评分要「抽音轨 + 整段转写 + 两次模型调用」，低功耗机上可能十几分钟，
 * 所以接口立即返回任务状态，前端轮询 {@code /status}。</p>
 *
 * <p><b>权限口径：</b>与报告评析完全一致——整类 {@code @RequireRole(TEACHER)}，
 * 路径挂在 {@code /teacher/**}（已被 {@code WebConfig.PROTECTED_PATHS} 覆盖），
 * 不额外按课题归属过滤（教师本就可查看任意场次）。</p>
 *
 * <p><b>【里程碑 · 10.19 视频答辩开端】</b>本类是该里程碑的**对外入口**：
 * 两个接口 {@code POST /analyze/{defenseId}}（触发，可 {@code force=true} 重跑）与
 * {@code GET /status/{defenseId}}（轮询）就是教师端「AI 评分（基于视频）」按钮的全部后端触点。
 * 更完整的背景、相关文件清单与「必须整体推送并同步远端仓库」的提醒，
 * 见 {@link com.ai_helper.ai_helper.Service.VideoAnalysisService} 的类注释。</p>
 *
 * <p><b>排查提示：</b>前端按钮只在「该场次 {@code video_url} 非空且不等于 {@code 'abc'}」时渲染
 * （见 {@code teacher.wxml}）。历史上出现过"评分按钮不可用"的误判，实际是教师打开的场次
 * 本身没有视频，请求从未到达后端 —— 日志里没有 {@code /teacher/defense/video/analyze} 入站记录时，
 * 先查场次有没有视频，再查接口。</p>
 */
@RestController
@Slf4j
@RequestMapping("/teacher/defense/video")
@RequireRole(UserRole.TEACHER)
public class VideoAnalysisController {

    @Resource
    private VideoAnalysisService videoAnalysisService;

    /**
     * 触发某场答辩的视频评分。
     *
     * @param force true = 忽略已有结果重跑（换了视频或想再评一次）
     */
    @PostMapping("/analyze/{defenseId}")
    public Result<Map<String, Object>> analyze(@PathVariable Integer defenseId,
                                               @RequestParam(value = "force", required = false,
                                                       defaultValue = "false") boolean force) {
        return Result.success(videoAnalysisService.trigger(defenseId, force));
    }

    /** 查询进度与结果（含库里已落库的成绩单文本） */
    @GetMapping("/status/{defenseId}")
    public Result<Map<String, Object>> status(@PathVariable Integer defenseId) {
        return Result.success(videoAnalysisService.status(defenseId));
    }
}
