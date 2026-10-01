package com.ai_helper.ai_helper.Controller.teacher;

import com.ai_helper.ai_helper.Service.FeedbackService;
import com.ai_helper.ai_helper.interceptor.RequireRole;
import com.ai_helper.ai_helper.pojo.dto.FeedbackReplyDto;
import com.ai_helper.ai_helper.pojo.entity.SystemFeedback;
import com.ai_helper.ai_helper.pojo.enums.UserRole;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师端反馈管理。
 *
 * <p>【2026-10-01 新增，借鉴 smart-medicine 的「反馈管理」】整类要求 teacher 角色。</p>
 */
@RestController
@RequestMapping("/teacher/feedback")
@RequireRole(UserRole.TEACHER)
public class TeacherFeedbackController {

    @Resource
    private FeedbackService feedbackService;

    /** 分页查询反馈；status 可选（pending / resolved） */
    @GetMapping("/list")
    public Result<PageResult<SystemFeedback>> list(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String status) {
        return feedbackService.pageAll(pageNum, pageSize, status);
    }

    /** 待处理反馈数量 */
    @GetMapping("/pending-count")
    public Result<Integer> pendingCount() {
        return feedbackService.pendingCount();
    }

    /** 回复反馈 */
    @PostMapping("/reply")
    public Result<Object> reply(@Valid @RequestBody FeedbackReplyDto dto) {
        return feedbackService.reply(dto);
    }
}
