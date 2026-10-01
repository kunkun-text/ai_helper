package com.ai_helper.ai_helper.mapper;

import com.ai_helper.ai_helper.pojo.entity.SystemFeedback;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统反馈 Mapper（表 {@code system_feedback}）。
 */
public interface SystemFeedbackMapper {

    /**
     * 提交反馈：顺带从 users 表补齐 user_id / 姓名 / 角色。
     * 学号不存在时插入 0 行。
     */
    int insertFeedback(@Param("userNumber") String userNumber,
                       @Param("content") String content,
                       @Param("contact") String contact);

    /** 某用户的反馈列表（按时间倒序） */
    List<SystemFeedback> selectByUserNumber(@Param("userNumber") String userNumber);

    /** 全部反馈（可按状态过滤），配合 PageHelper 分页 */
    List<SystemFeedback> selectAll(@Param("status") String status);

    /** 单条反馈 */
    SystemFeedback selectById(@Param("feedbackId") Integer feedbackId);

    /** 回复反馈（同时置为已回复） */
    int replyFeedback(@Param("feedbackId") Integer feedbackId, @Param("reply") String reply);

    /** 按状态统计条数；status 为空则统计全部 */
    int countByStatus(@Param("status") String status);
}
