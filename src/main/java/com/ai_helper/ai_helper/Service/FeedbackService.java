package com.ai_helper.ai_helper.Service;

import com.ai_helper.ai_helper.pojo.dto.FeedbackDto;
import com.ai_helper.ai_helper.pojo.dto.FeedbackReplyDto;
import com.ai_helper.ai_helper.pojo.entity.SystemFeedback;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;

import java.util.List;

/**
 * 系统反馈服务（学生提交 / 教师查看与回复）。
 */
public interface FeedbackService {

    /** 提交反馈 */
    Result<Object> submit(String userNumber, FeedbackDto dto);

    /** 当前用户的反馈列表 */
    Result<List<SystemFeedback>> myList(String userNumber);

    /** 教师端分页查询全部反馈 */
    Result<PageResult<SystemFeedback>> pageAll(Integer pageNum, Integer pageSize, String status);

    /** 教师回复反馈 */
    Result<Object> reply(FeedbackReplyDto dto);

    /** 待处理数量（教师端角标） */
    Result<Integer> pendingCount();
}
