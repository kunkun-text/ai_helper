package com.ai_helper.ai_helper.Controller.student;

import com.ai_helper.ai_helper.Service.FeedbackService;
import com.ai_helper.ai_helper.interceptor.AuthInterceptor;
import com.ai_helper.ai_helper.pojo.dto.FeedbackDto;
import com.ai_helper.ai_helper.pojo.entity.SystemFeedback;
import com.ai_helper.ai_helper.result.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端系统反馈。
 *
 * <p>【2026-10-01 新增】路径放在 {@code /student/**} 下，复用 WebConfig 既有的拦截范围
 * （{@code /api/**} 之外的接口默认不保护，故意不放 {@code /api/feedback} 以免漏保护造成越权）。</p>
 */
@RestController
@RequestMapping("/student/feedback")
public class StudentFeedbackController {

    @Resource
    private FeedbackService feedbackService;

    /** 提交反馈 */
    @PostMapping("/submit")
    public Result<Object> submit(@Valid @RequestBody FeedbackDto dto, HttpServletRequest request) {
        return feedbackService.submit(AuthInterceptor.currentUserNumber(request), dto);
    }

    /** 我的反馈列表 */
    @GetMapping("/my")
    public Result<List<SystemFeedback>> my(HttpServletRequest request) {
        return feedbackService.myList(AuthInterceptor.currentUserNumber(request));
    }
}
