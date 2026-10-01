package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.FeedbackService;
import com.ai_helper.ai_helper.mapper.SystemFeedbackMapper;
import com.ai_helper.ai_helper.pojo.dto.FeedbackDto;
import com.ai_helper.ai_helper.pojo.dto.FeedbackReplyDto;
import com.ai_helper.ai_helper.pojo.entity.SystemFeedback;
import com.ai_helper.ai_helper.result.PageResult;
import com.ai_helper.ai_helper.result.Result;
import com.ai_helper.ai_helper.result.ResultCode;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统反馈实现。
 */
@Slf4j
@Service
public class FeedbackServiceImpl implements FeedbackService {

    /** 反馈状态：待处理 */
    private static final String STATUS_PENDING = "pending";

    @Resource
    private SystemFeedbackMapper feedbackMapper;

    @Override
    public Result<Object> submit(String userNumber, FeedbackDto dto) {
        if (isBlank(userNumber)) {
            return Result.error(ResultCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        int rows = feedbackMapper.insertFeedback(userNumber.trim(), dto.getContent().trim(), dto.getContact());
        if (rows <= 0) {
            return Result.error("提交失败：未找到该用户");
        }
        return Result.success("反馈已提交，感谢你的建议");
    }

    @Override
    public Result<List<SystemFeedback>> myList(String userNumber) {
        if (isBlank(userNumber)) {
            return Result.error(ResultCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        return Result.success(feedbackMapper.selectByUserNumber(userNumber.trim()));
    }

    @Override
    public Result<PageResult<SystemFeedback>> pageAll(Integer pageNum, Integer pageSize, String status) {
        int pn = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int ps = (pageSize == null || pageSize < 1) ? 10 : pageSize;
        PageHelper.startPage(pn, ps);
        List<SystemFeedback> list = feedbackMapper.selectAll(status);
        PageInfo<SystemFeedback> info = new PageInfo<>(list);
        return Result.success(new PageResult<>(info.getList(), info.getTotal(), pn, ps));
    }

    @Override
    public Result<Object> reply(FeedbackReplyDto dto) {
        SystemFeedback existing = feedbackMapper.selectById(dto.getFeedbackId());
        if (existing == null) {
            return Result.error(ResultCode.NOT_FOUND, "该反馈不存在");
        }
        feedbackMapper.replyFeedback(dto.getFeedbackId(), dto.getReply().trim());
        return Result.success("回复成功");
    }

    @Override
    public Result<Integer> pendingCount() {
        return Result.success(feedbackMapper.countByStatus(STATUS_PENDING));
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
